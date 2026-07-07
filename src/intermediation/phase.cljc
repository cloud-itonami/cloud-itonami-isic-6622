(ns intermediation.phase
  "Phase 0->3 staged rollout -- the insurance-intermediation analog of
  `cloud-itonami-isic-6511`'s `underwriting.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- placement intake allowed, every write
                                 needs human approval.
    Phase 2  assisted-assess  -- adds jurisdiction assessment + conflict
                                 screening writes, still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:placement/intake` (no liability risk)
                                 may auto-commit. `:placement/bind`/
                                 `:commission/book` NEVER auto-commit,
                                 at any phase.

  `:placement/bind`/`:commission/book` are deliberately ABSENT from
  every phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come. Placing real coverage
  with an insurer on the customer's behalf and booking a real commission
  are the two real-world acts this actor performs; both are always a
  human agent's/broker's call. `intermediation.governor`'s `:actuation/
  bind`/`:actuation/book-commission` high-stakes gate enforces the same
  invariant independently -- two layers, not one, agree on this.
  `:placement/intake` moves no capital or liability (governed by its own
  HARD checks in `intermediation.governor`, but never `high-stakes`), so
  it IS auto-eligible at phase 3.")

(def read-ops  #{})
(def write-ops #{:placement/intake :jurisdiction/assess :conflict/screen
                 :placement/bind :commission/book})

;; NOTE the invariant: `:placement/bind`/`:commission/book` are members
;; of `write-ops` (governor-gated like any write) but are NEVER members
;; of any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                                                    :auto #{}}
   1 {:label "assisted-intake" :writes #{:placement/intake}                                   :auto #{}}
   2 {:label "assisted-assess" :writes #{:placement/intake :jurisdiction/assess :conflict/screen} :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:placement/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:placement/bind`/`:commission/book` are never auto-eligible at any
    phase, so they always escalate once the governor clears them (or
    hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map an Insurance Intermediation Governor verdict to a base disposition
  before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
