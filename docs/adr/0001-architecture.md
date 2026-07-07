# ADR-0001: cloud-itonami-isic-6622 -- Broker-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511` ADR-0001 (Underwriter-LLM ⊣
  UnderwritingGovernor, the pattern this ADR ports), `cloud-itonami-isic-
  6512` ADR-0001 (the sanctions/conflict-check discipline this ADR
  reuses), `cloud-itonami-isic-6621` ADR-0001 (Adjuster-LLM ⊣ Loss
  Adjustment Governor, the most recent sibling port and the source of
  the "write the lesson down, don't just fix it" discipline this ADR
  continues), ADR-2607032000 (`cloud-itonami` insurance (ISIC 65/66) +
  real-estate (ISIC 68) coverage push -- the blueprint scaffold this ADR
  deepens), langgraph-clj ADR-0001 (Pregel superstep + interrupt +
  Datomic checkpoint)
- Context: `cloud-itonami-isic-6622` published a business/operator-model
  blueprint (ADR-2607032000's insurance coverage push) but stopped at
  `:blueprint` maturity -- no governed actor implementation. This ADR
  deepens it to `:implemented`, the fourth insurance-adjacent actor in
  the fleet (after `6511` life insurance, `6512` non-life insurance and
  `6621` independent loss adjustment), continuing the SAME "pick a new
  ISIC blueprint vertical" direction that produced those three.

## Problem

Insurance intermediation needs FOUR different kinds of judgment, two of
them genuinely unlike anything the three sibling actors check for:

1. **Jurisdiction licensing/commission-disclosure correctness** -- is
   the required documentation/commission-cap based on an official
   producer-licensing regulator source?
2. **The broker's OWN conflict of interest** -- unlike `6621`'s check
   (does the EVALUATOR have an undisclosed conflict with the party they
   are evaluating for), THIS actor's version is: does the broker have an
   undisclosed incentive (e.g. a contingent-commission arrangement) to
   steer the customer toward an insurer that pays a higher commission
   rather than what serves the customer? Same code shape as the sibling
   checks (an unconditional `hit-in-proposal?` + an on-file check scoped
   to the real acts), different real-world stakes.
3. **Best-interest/shopping duty** -- did the broker actually compare
   MULTIPLE insurers' quotes before placing coverage, or bind on a
   single quote with no real comparison? None of `6511`/`6512`/`6621`
   have an analog to this check -- it is unique to an intermediary
   whose entire value proposition is comparing options on the
   customer's behalf.
4. **Real actuation, TWICE** -- placing real coverage with an insurer on
   the customer's behalf (`:placement/bind`), AND booking a real
   commission (`:commission/book`) -- a second, independent actuation
   event with its own HARD check (commission rate vs. the jurisdiction's
   own cap), the same dual-actuation shape `6512`'s bind/settle-claim
   pair established.

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run insurance intermediation with an LLM" but
"seal the LLM inside a trust boundary and layer licensing-authenticity,
independence, best-interest-duty, commission-cap-compliance, audit and
human-approval on top of it, while structurally fixing both real
actuation events as human-only."

## Decision

### 1. Broker-LLM is sealed into the bottom node; it never binds or books directly

`intermediation.brokerllm` returns exactly five kinds of proposal:
intake normalization, jurisdiction licensing/commission-cap checklist,
broker conflict-of-interest screening, placement-binding proposal, and
commission-booking proposal. No proposal writes the SSoT or places a
real policy/books a real commission directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 intermediation operation

`intermediation.operation/build` is the SAME StateGraph shape as every
sibling actor's operation namespace, copied verbatim -- this graph shape
is entirely generic across every op this actor performs. One graph run
corresponds to one intermediation operation, with no unbounded inner
loop.

### 3. Insurance Intermediation Governor is a separate system from Broker-LLM

`intermediation.governor` has four numbered HARD checks (spec-basis ·
conflict-of-interest · insufficient-quotes · commission-rate-exceeds-
cap) plus two un-numbered guards needing no jurisdiction/quote
comparison (placement-not-bound, double-booking) + confidence-floor/
actuation-gate (SOFT, human decides).

### 4. The conflict-of-interest check reuses `6621`'s already-corrected shape, not `6512`'s original buggy one

`conflict-violations` was written with the `hit-in-proposal?`-must-be-
unconditional lesson already in hand (see `6621`'s own ADR for where
that lesson came from). Applying it a SECOND time (after `6621` already
applied it once) reinforces that this is now a load-bearing convention
for this actor family, not a one-off fix: every future sibling's
sanctions/conflict-style check should start from this shape.

### 5. Best-interest/shopping duty is a genuinely NEW check, not borrowed from any sibling

`insufficient-quotes-violations` (fewer than two compared quotes on
file at bind time) has no analog in `6511`/`6512`/`6621` -- those actors'
"evidence-complete"/"document-complete" checks verify that a
JURISDICTION's required paperwork was submitted, not that multiple
OPTIONS were genuinely compared. An intermediary's core value
proposition (and core duty) is comparison; a placement based on one
quote has not discharged it, regardless of how complete that one
quote's paperwork is.

### 6. Real actuation is structurally always human-only (enforced by two independent layers), for BOTH actuation events

`intermediation.governor`'s `high-stakes` set has TWO members,
`:actuation/bind` and `:actuation/book-commission` (mirroring `6512`'s
bind/settle-claim dual-actuation shape, not `6511`'s/`6621`'s single-
actuation shape), and `intermediation.phase`'s phase table never puts
EITHER op in any phase's `:auto` set.

### 7. No fabricated international placement/commission-number standard

Same discipline as every sibling's registry: there is no single
international check-digit standard for a placement-binding or
commission-booking reference number. `intermediation.registry`
therefore does not invent one; it validates required fields and assigns
a jurisdiction-scoped sequence number only.

### 8. Relationship to `kotoba-lang/insurance`

Same self-contained-sibling relationship every prior insurance actor in
this fleet has to the shared capability lib -- no code dependency, only
a shared conceptual data-contract vocabulary (policy/premium/claim/
underwriting-decision).

## A real bug caught during demo verification (a NEW one, not a repeat)

The first draft of `placement-not-bound-violations` checked `(not= :bound
(:status (store/placement st subject)))`. This looked correct in
isolation but broke on the double-booking demo scenario: after a
SUCCESSFUL commission booking, `:status` advances from `:bound` to
`:commission-booked` (recording that the commission stage is done, the
same "status keeps moving forward" convention every sibling actor
uses). Attempting to book the SAME placement's commission a second time
then incorrectly ALSO tripped `:placement-not-bound` (since status was
no longer literally `:bound`) alongside the correct `:double-booking`
violation -- a double-fire, not a false negative, but still wrong: the
ledger should show ONLY `:double-booking` for that attempt. Caught by
running the demo and reading the actual ledger output (the same
verification discipline `6512`'s own ADR established), not by trusting
lint/compile success. Fixed by checking `(nil? (:placement-number ...))`
instead -- a fact that is SET once at binding time and never cleared,
so it correctly answers "has this placement EVER been bound" regardless
of what status it advances to afterward. This is a genuinely different
KIND of bug from `6512`'s (a status-lifecycle assumption, not an
op-scoping guard), showing that "apply the known lesson" does not
guarantee a bug-free first pass on a NEW check this actor introduces
that has no sibling precedent (`insufficient-quotes`/`commission-rate-
exceeds-cap`/`placement-not-bound` are all new to this repo).

## Consequences

- (+) Insurance intermediation gets the same governed, auditable-actor
  treatment as the other three insurance-adjacent actors, without
  centralizing liability in one vendor -- any licensed independent
  agent/broker can fork and run their own instance.
- (+) The actuation invariant (governor + phase, two layers, for BOTH
  `:placement/bind` and `:commission/book`) is regression-tested by
  `test/intermediation/phase_test.clj`'s `placement-bind-never-auto-at-
  any-phase`/`commission-book-never-auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by
  `test/intermediation/store_contract_test.clj`, the same `:db-api`-
  driven swap pattern every sibling actor uses.
- (+) The best-interest/shopping-duty check (`insufficient-quotes`) is a
  genuine domain-specific contribution to this actor family, not a
  reused pattern -- proven by a dedicated demo scenario and test.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA-NY, GBR, DEU) with an
  official spec-basis and an illustrative (not universally authoritative)
  commission-rate cap, out of ~194 worldwide; `intermediation.facts/
  coverage` reports this honestly rather than claiming broader coverage.
- (-) Policy-servicing handoff, real banking/tax/regulatory integration,
  and multi-line/multi-policy bundled placements are all out of scope
  for this OSS actor -- each operator's responsibility (see README's
  coverage table).
- 33 tests / 169 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `cloud-itonami-isic-6622` at `:blueprint` only | ❌ | Leaves insurance intermediation without an `:implemented` reference actor, unlike its three insurance siblings |
| Check placement-not-bound via `:status :bound` only (no `:placement-number` fallback) | ❌ | Breaks on the double-booking scenario once status legitimately advances past `:bound` -- see "A real bug caught during demo verification" above |
| Model best-interest duty as a generic "document-complete" check reused from `6511`/`6512`/`6621` | ❌ | A document checklist and a quote-comparison count are genuinely different kinds of evidence; conflating them would hide that THIS actor's central duty is comparison, not paperwork completeness |
| Require `kotoba.insurance` (the capability lib) directly from `intermediation.*` | ❌ | No sibling actor requires its capability lib directly; keeping the actor self-contained matches the established pattern |
| Fabricate a global placement/commission-number check-digit standard for conformance-test rigor | ❌ | No such standard exists; inventing one would be dishonest |
