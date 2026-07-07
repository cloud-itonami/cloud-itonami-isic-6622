(ns intermediation.governor
  "Insurance Intermediation Governor -- the independent compliance layer
  that earns the Broker-LLM the right to commit. The LLM has no notion
  of which jurisdiction's producer-licensing/commission-disclosure
  requirements are official, no way to know whether the assigned broker
  has an undisclosed conflict of interest with the insurer paying the
  highest commission (the ONE risk that makes 'independent' intermediary
  advice untrustworthy), no way to know whether the customer's best
  interest was even served by comparing enough quotes, and no business
  being the one that decides a real placement is bound or a real
  commission is booked today, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD -- the intermediation analog
  of `cloud-itonami-isic-6511`'s UnderwritingGovernor / `cloud-itonami-
  isic-6512`'s Non-Life Insurance Governor / `cloud-itonami-isic-6621`'s
  Loss Adjustment Governor.

  Five checks, in priority order. The first four are HARD violations: a
  human approver CANNOT override them (you don't get to approve your way
  past an undisclosed conflict of interest, a fabricated licensing
  citation, an insufficiently-shopped placement, or a commission rate
  exceeding the jurisdiction's own recorded cap). The last is SOFT: it
  asks a human to look (low confidence / actuation), and the human may
  approve -- but see `intermediation.phase`: for `:stake :actuation/bind`/
  `:actuation/book-commission` (placing real coverage or booking a real
  commission) NO phase ever allows auto-commit either. Two independent
  layers agree that actuation is always a human call.

    1. Spec-basis                -- did the jurisdiction proposal cite
                                     an OFFICIAL source (`intermediation.
                                     facts`), or invent one?
    2. Conflict of interest        -- does THIS proposal itself report a
                                     conflict-of-interest hit (a
                                     `:conflict/screen` that just found
                                     one), or does the placement's
                                     assigned broker already carry one on
                                     file? Undisclosed steering toward
                                     the insurer paying the highest
                                     commission is exactly the failure
                                     mode 'independent' intermediation
                                     exists to prevent.
    3. Insufficient quotes          -- for `:placement/bind`, were at
                                     least TWO insurers' quotes actually
                                     compared? A placement based on a
                                     single quote has not discharged the
                                     broker's best-interest/shopping
                                     duty.
    4. Commission rate exceeds cap  -- for `:commission/book`, does the
                                     placement's own recorded commission
                                     rate exceed THIS jurisdiction's own
                                     recorded cap? Independently checked
                                     against the jurisdiction's own
                                     ceiling, never trusting the
                                     placement's rate as compliant by
                                     assumption.
    5. Confidence floor / actuation gate -- LLM confidence below
                                     threshold, OR the op is `:placement/
                                     bind`/`:commission/book` (REAL acts
                                     -- see README `Actuation`) ->
                                     escalate.

  Two more guards, placement-not-bound and double-booking, are enforced
  but NOT listed as numbered HARD checks above because they need no
  jurisdiction/quote comparison at all -- `placement-not-bound-
  violations` refuses to book a commission for a placement that was
  never actually bound, and `double-booking-violations` refuses to book
  the SAME placement's commission twice, each off this actor's own
  records."
  (:require [intermediation.facts :as facts]
            [intermediation.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Placing real coverage with an insurer on the customer's behalf and
  booking a real commission are the two real-world actuation events
  this actor performs."
  #{:actuation/bind :actuation/book-commission})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:placement/bind`) proposal with no
  spec-basis citation is a HARD violation -- never invent a
  jurisdiction's licensing/commission-disclosure requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :placement/bind} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- conflict-violations
  "A conflict-of-interest hit -- reported by THIS proposal (e.g. a
  `:conflict/screen` that itself just found one), or already on file in
  the store for the placement's assigned broker (`:placement/bind`/
  `:commission/book`) -- is a HARD, un-overridable hold. NOTE:
  `hit-in-proposal?` is deliberately UNCONDITIONAL (not scoped to any
  particular op) -- only `:conflict/screen` proposals ever set
  `:verdict`, so scoping this branch to a specific op would silently
  prevent the screening op itself from ever being held on its own
  finding (the exact bug caught in `cloud-itonami-isic-6512`'s R0 build,
  and the exact lesson `cloud-itonami-isic-6621`'s ADR names applying
  up front -- see both repos' ADRs)."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :hit (get-in proposal [:value :verdict]))
        broker-id (when (contains? #{:placement/bind :commission/book} op)
                    (:broker (store/placement st subject)))
        hit-on-file? (and broker-id (= :hit (:verdict (store/conflict-of st broker-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :conflict-of-interest
        :detail "利益相反のあるブローカーによる媒介は進められない"}])))

(defn- insufficient-quotes-violations
  "For `:placement/bind`, at least TWO insurers' quotes must actually
  have been compared -- a placement based on a single quote has not
  discharged the broker's best-interest/shopping duty."
  [{:keys [op subject]} st]
  (when (= op :placement/bind)
    (let [p (store/placement st subject)]
      (when (< (count (:quotes p)) 2)
        [{:rule :insufficient-quotes
          :detail "2件未満の見積り比較での契約媒介提案"}]))))

(defn- commission-rate-exceeds-cap-violations
  "For `:commission/book`, the placement's own recorded commission rate
  must not exceed THIS jurisdiction's own recorded cap -- independently
  checked, never trusting the placement's rate as compliant by
  assumption."
  [{:keys [op subject]} st]
  (when (= op :commission/book)
    (let [p (store/placement st subject)
          cap (facts/commission-rate-cap (:jurisdiction p))]
      (when (and cap (> (double (:selected-commission-rate p)) (double cap)))
        [{:rule :commission-rate-exceeds-cap
          :detail (str subject " の手数料率が法域上限を超過している")}]))))

(defn- placement-not-bound-violations
  "For `:commission/book`, the referenced placement must actually have
  been bound at some point -- a commission cannot be booked for a
  placement that was never placed. Checks `:placement-number` (set once
  by `:placement/mark-bound` and never cleared) rather than `:status
  :bound` directly -- a SUCCESSFULLY booked placement's status later
  advances to `:commission-booked`, and re-checking `:status :bound`
  there would spuriously re-fire this HARD check on a double-booking
  attempt instead of letting `double-booking-violations` alone report
  it (a real bug caught by running the demo and reading the actual
  ledger output, not by trusting lint/compile success)."
  [{:keys [op subject]} st]
  (when (= op :commission/book)
    (when (nil? (:placement-number (store/placement st subject)))
      [{:rule :placement-not-bound
        :detail (str subject " は成立(bound)していないため、この契約への手数料計上は受理できない")}])))

(defn- double-booking-violations
  "For `:commission/book`, refuses to book a commission for the SAME
  placement twice, off this actor's own commission history -- needs no
  jurisdiction/quote comparison at all."
  [{:keys [op subject]} st]
  (when (= op :commission/book)
    (let [p (store/placement st subject)]
      (when (store/commission-already-booked? st (:placement-number p))
        [{:rule :double-booking
          :detail (str subject " は既に手数料計上済み")}]))))

(defn check
  "Censors a Broker-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (conflict-violations request proposal st)
                           (insufficient-quotes-violations request st)
                           (commission-rate-exceeds-cap-violations request st)
                           (placement-not-bound-violations request st)
                           (double-booking-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
