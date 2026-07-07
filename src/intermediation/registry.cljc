(ns intermediation.registry
  "Pure-function placement-binding and commission-booking record
  construction -- an append-only insurance-intermediation book-of-record
  draft.

  Like `cloud-itonami-isic-6511`'s `underwriting.registry` / `cloud-
  itonami-isic-6512`'s `casualty.registry` / `cloud-itonami-isic-6621`'s
  `adjustment.registry`, there is no single international check-digit
  standard for a placement-binding or commission-booking reference
  number -- every agency/jurisdiction assigns its own reference format.
  This namespace does NOT invent one; it builds a jurisdiction-scoped
  sequence number and validates the record's required fields, the same
  honest, non-fabricating discipline `intermediation.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network call
  to any insurer's core-administration or commission-payment system. It
  builds the RECORD an agent/broker would keep, not the act of placing
  the policy or booking the commission itself (those are `intermediation.
  operation`'s `:placement/bind` and `:commission/book`, always
  human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  licensed agent's/broker's act, not this actor's. See README
  `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-binding
  "Validate + construct the PLACEMENT-BINDING registration DRAFT -- the
  agent's/broker's own act of placing coverage with the selected insurer
  on the customer's behalf. Pure function -- does not touch any real
  insurer system or bind any real coverage."
  [customer selected-insurer selected-premium jurisdiction sequence]
  (when-not (and customer (not= customer ""))
    (throw (ex-info "binding: customer required" {})))
  (when-not (and selected-insurer (not= selected-insurer ""))
    (throw (ex-info "binding: selected-insurer required" {})))
  (when (< selected-premium 0)
    (throw (ex-info "binding: selected-premium must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "binding: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "binding: sequence must be >= 0" {})))
  (let [placement-number (str (str/upper-case jurisdiction) "-" (zero-pad sequence 8))
        record {"record_id" placement-number
                "kind" "binding-draft"
                "customer" customer
                "selected_insurer" selected-insurer
                "selected_premium" selected-premium
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "placement_number" placement-number
     "certificate" (unsigned-certificate "PlacementBindingCertificate" placement-number placement-number)}))

(defn register-commission-booking
  "Validate + construct the COMMISSION-BOOKING DRAFT -- the agent's/
  broker's own legal act of booking a commission earned off a bound
  placement. Pure function -- does not touch any real commission-payment
  system; it builds the RECORD an agent/broker would keep.
  `intermediation.governor` independently re-verifies the commission
  rate against the jurisdiction's own recorded cap, and blocks a
  double-booking of the same placement, before this is ever allowed to
  commit."
  [placement-number commission-rate commission-amount jurisdiction sequence]
  (when-not (and placement-number (not= placement-number ""))
    (throw (ex-info "commission-booking: placement_number required" {})))
  (when-not (<= 0 commission-rate 1)
    (throw (ex-info "commission-booking: commission-rate must be in [0,1]" {})))
  (when (< commission-amount 0)
    (throw (ex-info "commission-booking: commission-amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "commission-booking: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "commission-booking: sequence must be >= 0" {})))
  (let [booking-number (str (str/upper-case jurisdiction) "-COMM-" (zero-pad sequence 6))
        record {"record_id" booking-number
                "kind" "commission-booking-draft"
                "placement_number" placement-number
                "commission_rate" commission-rate
                "commission_amount" commission-amount
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "booking_number" booking-number
     "certificate" (unsigned-certificate "CommissionBookingCertificate" booking-number booking-number)}))

(defn append
  "Append a placement-binding/commission-booking record, returning a NEW
  list (never mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
