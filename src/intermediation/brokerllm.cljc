(ns intermediation.brokerllm
  "Broker-LLM client -- the *contained intelligence node* for the
  insurance-intermediation actor.

  It normalizes placement intake, drafts a per-jurisdiction licensing/
  commission-cap checklist, screens the assigned broker for a conflict
  of interest, drafts the placement-binding action, and drafts the
  commission-booking action. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it
  cited), never a committed record or a real placement/commission.
  Every output is censored downstream by `intermediation.governor`
  before anything touches the SSoT, and `:placement/bind`/`:commission/
  book` proposals NEVER auto-commit at any phase -- see README
  `Actuation`.

  Like `cloud-itonami-isic-6511`'s `underwriting.underwriterllm` /
  `cloud-itonami-isic-6512`'s `casualty.underwriterllm` / `cloud-
  itonami-isic-6621`'s `adjustment.adjusterllm`, this is a deterministic
  mock so the actor graph runs offline and the governor contract is
  exercised end-to-end. In production this calls a real LLM (kotoba-llm
  or equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/bind | :actuation/book-commission | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [intermediation.facts :as facts]
            [intermediation.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the placement's customer, broker, needs description,
  compared quotes, selected insurer or jurisdiction. High confidence,
  low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "媒介案件レコード更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :placement/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction licensing/commission-cap checklist draft. `:no-spec?`
  injects the failure mode we must defend against: proposing a
  checklist for a jurisdiction with NO official spec-basis in
  `intermediation.facts` -- the Insurance Intermediation Governor must
  reject this (never invent a jurisdiction's licensing requirements)."
  [db {:keys [subject no-spec?]}]
  (let [p (store/placement db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction p))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "intermediation.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-docs sb)) " 件、手数料上限 " (:commission-rate-cap sb) " を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-docs sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)
                    :commission-rate-cap (:commission-rate-cap sb)}
       :stake      nil
       :confidence 0.9})))

(def default-corporate-intel-check
  "No-op corporate-intelligence cross-reference: always 'nothing on
  file'. This is the default so every existing caller of `screen-
  conflict`/`infer`/`mock-advisor` keeps its exact prior behavior unless
  it explicitly wires in `intermediation.corporate-intel/check-
  relationship` (or an equivalent). Not required from this namespace
  directly -- keeping the dependency optional at the brokerllm level,
  injected only by whoever builds the advisor."
  (constantly {:found? false :related? false}))

(defn- screen-conflict
  "Conflict-of-interest screening draft. `:conflict-hit?` on the party
  record injects the failure mode: the Insurance Intermediation
  Governor must HOLD, un-overridably, on any conflict-of-interest hit.
  Missing disclosure yields low confidence -> escalate rather than
  auto-clear.

  An OPTIONAL `:placement-id` on the request additionally cross-
  references the placement's CUSTOMER against cloud-itonami-isic-8291's
  :disclosure/relationship-check op via `screen-fn` (broker name x
  customer name -> corporate-intel result, see `intermediation.
  corporate-intel/check-relationship`) -- catching an undisclosed
  professional relationship between THIS broker and the SPECIFIC
  customer of THIS placement, something the local-only :conflict-hit?/
  :disclosure-doc checks above cannot see at all (they only reflect a
  static flag on the broker's own record, with no per-placement
  customer awareness). `screen-fn` is consulted ONLY once the local
  checks are otherwise clean and a placement/customer are actually
  resolvable -- a local conflict-hit or missing disclosure is decided
  first, cheaply, without depending on an external actor at all.
  Omitting `:placement-id` (every existing caller) is EXACTLY the prior
  behavior."
  [db {:keys [subject placement-id]} screen-fn]
  (let [p (store/party db subject)]
    (cond
      (nil? p)
      {:summary "対象partyが見つかりません" :rationale "no party record"
       :cites [] :effect :conflict/set :value {:party-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (:conflict-hit? p)
      {:summary    (str (:name p) ": 利益相反を検出")
       :rationale  "スクリーニングが利益相反を検出。人手確認とホールドが必須。"
       :cites      [:conflict-registry]
       :effect     :conflict/set
       :value      {:party-id subject :verdict :hit}
       :stake      nil
       :confidence 0.95}

      (nil? (:disclosure-doc p))
      {:summary    (str (:name p) ": 利益相反開示書類が未提出")
       :rationale  "開示書類が無いため確信度を上げられない。"
       :cites      [:disclosure-doc]
       :effect     :conflict/set
       :value      {:party-id subject :verdict :incomplete}
       :stake      nil
       :confidence 0.4}

      :else
      (if-let [customer-name (when placement-id
                                (let [pl (store/placement db placement-id)
                                      customer (store/party db (:customer pl))]
                                  (:name customer)))]
        (let [rel (screen-fn (:name p) customer-name)]
          (cond
            (:pending-human-review? rel)
            {:summary    (str (:name p) ": corporate-intelligence 照会が人手レビュー待ち")
             :rationale  "cloud-itonami-isic-8291 側の DisclosureGovernor が high-stakes escalate 中。確定するまでクリアにできない。"
             :cites      [:disclosure-doc :conflict-registry :corporate-intelligence]
             :effect     :conflict/set
             :value      {:party-id subject :verdict :incomplete}
             :stake      nil
             :confidence 0.5}

            (:held? rel)
            {:summary    (str (:name p) ": corporate-intelligence 照会が拒否された(契約/設定の問題)")
             :rationale  (str "cloud-itonami-isic-8291 の DisclosureGovernor が本テナントの照会を拒否: " (pr-str (:reason rel)))
             :cites      [:disclosure-doc :conflict-registry :corporate-intelligence]
             :effect     :conflict/set
             :value      {:party-id subject :verdict :incomplete}
             :stake      nil
             :confidence 0.4}

            (:related? rel)
            {:summary    (str (:name p) ": corporate-intelligence 照会でカウンターパーティとの関係を検出")
             :rationale  "cloud-itonami-isic-8291 の関係性照会が一致(所属または関係edge)を検出。人手確認とホールドが必須。"
             :cites      [:disclosure-doc :conflict-registry :corporate-intelligence]
             :effect     :conflict/set
             :value      {:party-id subject :verdict :hit}
             :stake      nil
             :confidence 0.9}

            :else
            {:summary    (str (:name p) ": 利益相反なし、開示書類あり(corporate-intelligence 照会もクリア)")
             :rationale  "開示書類確認 + 利益相反リスト非一致 + corporate-intelligence 照会クリア。"
             :cites      [:disclosure-doc :conflict-registry :corporate-intelligence]
             :effect     :conflict/set
             :value      {:party-id subject :verdict :clear}
             :stake      nil
             :confidence 0.9}))
        ;; no :placement-id supplied -- EXACT original behavior, unchanged
        {:summary    (str (:name p) ": 利益相反なし、開示書類あり")
         :rationale  "開示書類確認 + 利益相反リスト非一致。"
         :cites      [:disclosure-doc :conflict-registry]
         :effect     :conflict/set
         :value      {:party-id subject :verdict :clear}
         :stake      nil
         :confidence 0.9}))))

(defn- propose-bind
  "Draft the actual placement-binding action -- placing real coverage
  with the selected insurer on the customer's behalf. ALWAYS `:stake
  :actuation/bind` -- this is a REAL-WORLD act, never a draft the actor
  may auto-run. See README `Actuation`: no phase ever adds this op to a
  phase's `:auto` set (`intermediation.phase`); the governor also always
  escalates on `:actuation/bind`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [p (store/placement db subject)
        assessment (store/assessment-of db subject)
        quotes-ok? (>= (count (:quotes p)) 2)]
    {:summary    (str (:customer p) " -> " (:selected-insurer p) " (" (:jurisdiction p)
                      ") の契約媒介準備ができました" (when-not quotes-ok? " (比較見積り不足)"))
     :rationale  (if assessment
                   (str "spec-basis: " (:spec-basis assessment))
                   "assessment未実施")
     :cites      (if assessment [(:spec-basis assessment)] [])
     :effect     :placement/mark-bound
     :value      {:placement-id subject}
     :stake      :actuation/bind
     :confidence (if quotes-ok? 0.9 0.3)}))

(defn- propose-book-commission
  "Draft the actual commission-booking action -- booking a real
  commission earned off a bound placement. ALWAYS `:stake :actuation/
  book-commission` -- this is a REAL-WORLD act, never a draft the actor
  may auto-run. See README `Actuation`: no phase ever adds this op to a
  phase's `:auto` set (`intermediation.phase`); the governor also always
  escalates on `:actuation/book-commission`. Two independent layers
  agree, deliberately."
  [db {:keys [subject]}]
  (let [p (store/placement db subject)
        cap (facts/commission-rate-cap (:jurisdiction p))
        within-cap? (and cap (<= (double (:selected-commission-rate p)) (double cap)))]
    {:summary    (str subject " 向け手数料計上提案 (rate="
                      (:selected-commission-rate p) ")")
     :rationale  (if cap (str "jurisdiction commission-rate-cap=" cap) "手数料上限が未確定")
     :cites      (if cap [(:jurisdiction p)] [])
     :effect     :commission/mark-booked
     :value      {:placement-id subject}
     :stake      :actuation/book-commission
     :confidence (if within-cap? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}
  `screen-fn` (default: `default-corporate-intel-check`, a no-op) is only
  consulted by `:conflict/screen`, once local checks are otherwise clean
  AND the request carries a resolvable `:placement-id`."
  ([db request] (infer db request default-corporate-intel-check))
  ([db {:keys [op] :as request} screen-fn]
   (case op
     :placement/intake     (normalize-intake db request)
     :jurisdiction/assess  (assess-jurisdiction db request)
     :conflict/screen      (screen-conflict db request screen-fn)
     :placement/bind       (propose-bind db request)
     :commission/book      (propose-book-commission db request)
     {:summary "未対応の操作" :rationale (str op) :cites []
      :effect :noop :stake nil :confidence 0.0})))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere.
  opts:
    :corporate-intel-check -- broker name x customer name -> corporate-
      intel result (see `intermediation.corporate-intel/check-
      relationship`). Default: no-op (never changes a screen-conflict
      verdict), so `(mock-advisor)` with no args keeps every existing
      caller's exact prior behavior."
  ([] (mock-advisor {}))
  ([{:keys [corporate-intel-check]
     :or   {corporate-intel-check default-corporate-intel-check}}]
   (reify Advisor (-advise [_ st req] (infer st req corporate-intel-check)))))

(def ^:private system-prompt
  (str "あなたは独立保険代理店・保険仲立人の助言者です。与えられた事実のみに"
       "基づき、提案を1つだけEDNマップで返します。説明や前置きは一切書かず、"
       "EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:placement/upsert|:assessment/set|:conflict/set|:placement/mark-bound|"
       ":commission/mark-booked) "
       ":stake(:actuation/bind か :actuation/book-commission か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess {:placement (store/placement st subject)}
    :conflict/screen     {:party (store/party st subject)}
    :placement/bind      {:placement (store/placement st subject)
                          :assessment (store/assessment-of st subject)}
    :commission/book     {:placement (store/placement st subject)}
    {:placement (store/placement st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Insurance Intermediation
  Governor escalates/holds -- an LLM hiccup can never auto-bind a
  placement or auto-book a commission."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :brokerllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
