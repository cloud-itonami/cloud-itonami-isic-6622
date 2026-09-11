(ns intermediation.corporate-intel
  "Optional integration with cloud-itonami-isic-8291's :disclosure/
  relationship-check op (ADR-2607110400 addendum 4) -- cross-references
  whether the assigned broker has an undisclosed professional-capacity
  relationship with the placement's customer, against 8291's sourced
  relationship-graph data. Calls through 8291's OWN DisclosureGovernor --
  no bypass from this side either. A real 8291 hit escalates for 8291's
  own human reviewer first, so this namespace never peeks at Dossier-
  LLM's un-vetted draft proposal to get an early answer."
  (:require [langgraph.graph :as g]
            [dossier.store :as dstore]
            [dossier.operation :as dop]))

(def default-tenant
  "This blueprint's own tenant id under an 8291 :tier/graph contract."
  "cloud-itonami-isic-6622")

(defn demo-store
  "An 8291 MemStore seeded with 8291's own demo data, PLUS a :tier/graph
  contract for THIS blueprint's tenant. Replaces 8291's demo contracts
  entirely -- this is 6622's OWN isolated offline view."
  []
  (-> (dstore/seed-db)
      (dstore/with-contracts
       {default-tenant {:tenant default-tenant :tier :tier/graph
                         :active? true :purpose :conflict-of-interest-screening}})))

(defn build
  "Compiles an 8291 OperationActor bound to `store` (default: `demo-store`)."
  ([] (build (demo-store)))
  ([store] (dop/build store)))

(defn check-relationship
  "Runs :disclosure/relationship-check for `person-name` (the broker) x
  `target-name` (the placement's customer, company or person -- 8291
  tries both via :target-name). Returns one of:
    {:found? bool :related? bool :kind kw|nil}  -- a governor-approved
      answer (disposition :commit).
    {:pending-human-review? true :reason kw}  -- 8291 itself escalated a
      potential hit to ITS OWN human reviewer; treat as inconclusive.
    {:held? true :reason [kw ..]}  -- the query itself was rejected by
      8291's DisclosureGovernor -- a configuration problem, never treated
      as confirming no conflict.

  opts:
    :actor     -- a pre-built 8291 OperationActor (default: fresh `build`)
    :tenant    -- tenant id to query under (default: `default-tenant`)
    :thread-id -- langgraph-clj thread id (default: derived from the names)"
  ([person-name target-name] (check-relationship person-name target-name {}))
  ([person-name target-name {:keys [actor tenant thread-id]
                              :or   {actor (build) tenant default-tenant}}]
   (let [thread-id (or thread-id (str "relcheck-" tenant "-" person-name "-" target-name))
         res (g/run* actor
                     {:request {:op :disclosure/relationship-check :subject tenant
                                :person-name person-name :target-name target-name}
                      :context {:actor-id default-tenant :actor-role :client :tenant tenant}}
                     {:thread-id thread-id})]
     (case (get-in res [:state :disposition])
       :commit    (get-in res [:state :record :value])
       :escalate  {:pending-human-review? true
                   :reason (-> res :state :audit last :reason)}
       :hold      {:held? true
                   :reason (-> res :state :audit last :basis)}
       {:held? true :reason [:corporate-intel-actor-error]}))))
