(ns intermediation.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 22): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`intermediation.operation` -> `intermediation.governor` ->
  `intermediation.store`) through a scenario adapted from this repo's own
  `intermediation.sim` demo driver (`clojure -M:dev:run`, confirmed by
  actually running it before this file was written -- every id used below
  (placement-1/placement-2, party-2/party-4) resolves exactly against
  `intermediation.store/demo-data`'s real seed, and the printed audit
  ledger shows the expected dispositions -- no isic-851-class id/seed
  mismatch here), trimmed to a representative subset (one clean
  auto-commit, four escalate-then-approve steps that actually bind a
  placement and book its commission, and three DISTINCT HARD-hold reasons
  that never reach a human) and rendered deterministically -- no invented
  numbers, no timestamps in the page content, byte-identical across
  reruns against the same seed (verified by diffing two consecutive
  runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [intermediation.store :as store]
            [intermediation.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :broker :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: placement-1's intake update auto-commits clean at
  phase 3 (no capital/liability risk -- the ONLY op in any phase's
  `:auto` set, see `intermediation.phase`); placement-1's jurisdiction
  assessment and its broker party-2's conflict screening are each
  write-enabled-but-not-auto at phase 3, so both escalate and are
  approved; placement-1's binding and its commission booking are each
  ALWAYS-escalate (`:actuation/bind` / `:actuation/book-commission`,
  `intermediation.governor/high-stakes` -- no phase ever auto-commits
  these, a permanent structural fact, not a rollout milestone) and are
  approved, actually binding the placement and booking its commission.
  Then three DISTINCT HARD holds, none of which ever reach a human:
  party-4's conflict screening (`:conflict-hit?` true in the seed data ->
  `:conflict-of-interest`), placement-2's jurisdiction assessment for a
  jurisdiction (ATL) with no official spec-basis in
  `intermediation.facts` (`:no-spec-basis`), and a second commission-
  booking attempt on the already-booked placement-1
  (`:double-booking`). Returns the resulting store -- every field read by
  `render` below is real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "p1-intake" {:op :placement/intake :subject "placement-1"
                               :patch {:id "placement-1" :status :ready}})

    (exec! actor "p1-assess" {:op :jurisdiction/assess :subject "placement-1"})
    (approve! actor "p1-assess")

    (exec! actor "party2-screen" {:op :conflict/screen :subject "party-2"})
    (approve! actor "party2-screen")

    (exec! actor "p1-bind" {:op :placement/bind :subject "placement-1"})
    (approve! actor "p1-bind")

    (exec! actor "p1-book" {:op :commission/book :subject "placement-1"})
    (approve! actor "p1-book")

    (exec! actor "party4-screen" {:op :conflict/screen :subject "party-4"})

    (exec! actor "p2-assess-nospec" {:op :jurisdiction/assess :subject "placement-2" :no-spec? true})

    (exec! actor "p1-book-again" {:op :commission/book :subject "placement-1"})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- party-name [db id]
  (or (:name (store/party db id)) id))

(defn- placement-row [db ledger {:keys [id customer broker jurisdiction status]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (party-name db customer)) (esc (party-name db broker))
          (esc jurisdiction) (esc (name (or status :n-a))) (status-cell ledger id)))

(defn- party-row [ledger {party-id :id pname :name :keys [role disclosure-doc]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc party-id) (esc pname) (esc (name (or role :n-a)))
          (if disclosure-doc "<span class=\"ok\">on file</span>" "<span class=\"warn\">missing</span>")
          (status-cell ledger party-id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README `Ops`
  ;; table, `intermediation.governor`/`intermediation.phase`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:placement/intake</code></td><td><span class=\"ok\">auto-commit when clean, no capital risk</span></td></tr>"
   "        <tr><td><code>:jurisdiction/assess</code></td><td><span class=\"warn\">ALWAYS human approval &middot; requires official spec-basis</span></td></tr>"
   "        <tr><td><code>:conflict/screen</code></td><td><span class=\"warn\">ALWAYS human approval &middot; HARD hold on any conflict-of-interest hit</span></td></tr>"
   "        <tr><td><code>:placement/bind</code></td><td><span class=\"warn\">ALWAYS human approval &middot; places real coverage (actuation/bind), &ge;2 quotes required</span></td></tr>"
   "        <tr><td><code>:commission/book</code></td><td><span class=\"warn\">ALWAYS human approval &middot; books a real commission (actuation/book-commission), rate independently checked against jurisdiction cap</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        placements (store/all-placements db)
        parties (->> (store/demo-data) :parties vals (sort-by :id))
        placement-rows (str/join "\n" (map (partial placement-row db ledger) placements))
        party-rows (str/join "\n" (map (partial party-row ledger) parties))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-6622 &middot; insurance intermediation (agents &amp; brokers)</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Insurance intermediation &mdash; agents &amp; brokers (ISIC 6622) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · placement binding &amp; commission booking always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Placements</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>intermediation.store</code> via <code>intermediation.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Placement</th><th>Customer</th><th>Broker</th><th>Jurisdiction</th><th>Status</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     placement-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Parties</h2>\n"
     "    <p class=\"muted\">Customers and brokers referenced by the placements above. Conflict-of-interest disclosure is never trusted as compliant by assumption — the Governor holds un-overridably on any hit.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Party</th><th>Name</th><th>Role</th><th>Disclosure doc</th><th>Last screening status</th></tr></thead>\n"
     "      <tbody>\n"
     party-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Insurance Intermediation Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Commission rates are independently checked against the jurisdiction's own recorded cap, never trusted from the proposal; placement binding requires at least two compared quotes.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/binding-history db)) "placement bindings,"
             (count (store/commission-history db)) "commission bookings )")))
