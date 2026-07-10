# cloud-itonami-isic-6622

Open Business Blueprint for **ISIC Rev.5 6622**: Activities of insurance
agents and brokers. This repository publishes an independent insurance-
intermediation execution actor as an OSS business that any qualified,
licensed operator can fork, deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
[`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511)
(life insurance), [`cloud-itonami-isic-6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512)
(non-life insurance) and [`cloud-itonami-isic-6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621)
(independent loss adjustment). Here it is **Broker-LLM ⊣ Insurance
Intermediation Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a
> customer-needs summary, normalizing placement intake, and comparing
> quotes across insurers -- but it has **no notion of which
> jurisdiction's producer-licensing/commission-disclosure requirements
> are official, no license to place coverage, and no way to know on its
> own whether the assigned broker has an undisclosed conflict of
> interest with the insurer paying the highest commission** -- the ONE
> risk that makes "independent" intermediary advice untrustworthy.
> Letting it place a policy or book a commission directly invites
> fabricated licensing citations, steering customers toward whichever
> insurer pays the broker the most rather than what serves the customer,
> and silent liability for whoever runs it. This project seals the
> Broker-LLM into a single node and wraps it with an independent
> **Insurance Intermediation Governor**, a human **approval workflow**,
> and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor drafts and governs an insurance-intermediation workflow:
placement intake, per-jurisdiction licensing/commission-cap
checklisting, broker conflict-of-interest screening, a placement-
binding proposal, and a commission-booking proposal. It does **not**,
by itself, hold a license to place or bind insurance coverage in any
jurisdiction, and it does not claim to. Whoever deploys and operates a
live instance (a licensed independent agent, an insurance brokerage)
supplies the jurisdiction-specific license, the real carrier
relationships and the real placement/commission-processing
integrations, and bears that jurisdiction's liability -- the software
supplies the governed, spec-cited, audited execution scaffold so that
operator does not have to build the compliance layer from scratch for
every new market.

### Actuation

**Placing real coverage with an insurer on the customer's behalf, or
booking a real commission, is never autonomous, at any phase, by
construction.** Two independent layers enforce this
(`intermediation.governor`'s `:actuation/bind`/`:actuation/book-
commission` high-stakes gate and `intermediation.phase`'s phase table,
which never puts either op in any phase's `:auto` set) -- see
`intermediation.phase`'s docstring and `test/intermediation/phase_test.
clj`'s `placement-bind-never-auto-at-any-phase`/`commission-book-never-
auto-at-any-phase`. The actor may draft, check, screen and recommend; a
human operator (a licensed agent/broker) is always the one who actually
places coverage or books a commission.

## The core contract

```
placement intake + jurisdiction facts (intermediation.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ Broker-LLM   │ ─────────────▶ │ Insurance               │  (independent system)
   │  (sealed)    │  + citations    │ Intermediation Governor:│
   └──────────────┘                 │ spec-basis · conflict-  │
                             commit ◀────┼──────────▶ hold │ of-interest · insufficient-
                                 │             │           │ quotes · commission-rate-
                           record + ledger  escalate ─▶ human  exceeds-cap (un-overridable)
                                             (ALWAYS for
                                              :placement/bind AND
                                              :commission/book)
```

**The Broker-LLM never binds a placement or books a commission the
Insurance Intermediation Governor would reject, and never does either
without a human sign-off.** Hard violations (fabricated jurisdiction
requirements; a broker's conflict of interest; a placement bound on too
few compared quotes; a commission rate exceeding the jurisdiction's own
cap; a double-booking) force **hold** and *cannot* be approved past; a
clean placement or commission proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean placement-bind lifecycle + one clean commission-booking lifecycle + six HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a document-courier and kiosk
robot collects in-person signatures for policy binding, under the
actor, gated by the independent **Insurance Intermediation Governor**.
The governor never dispatches hardware itself; `:high`/`:safety-
critical` actions require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Insurance Intermediation Governor, placement-binding + commission-booking draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6622`). Related capability contracts (policy/premium/claim shapes) are
published as [`kotoba-lang/insurance`](https://github.com/kotoba-lang/insurance);
this actor's `intermediation.*` namespaces are a self-contained governed
implementation -- it does not require the capability lib directly, the
same "self-contained sibling" relationship its insurance siblings have
toward the same lib.

## Layout

| File | Role |
|---|---|
| `src/intermediation/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + placement-binding + commission-booking history |
| `src/intermediation/registry.cljc` | Placement-binding + commission-booking draft records (no fabricated international check-digit standard -- see docstring) |
| `src/intermediation/facts.cljc` | Per-jurisdiction licensing/commission-cap requirement catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/intermediation/brokerllm.cljc` | **Broker-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/conflict-screening/binding/commission-booking proposals |
| `src/intermediation/governor.cljc` | **Insurance Intermediation Governor** -- 4 HARD checks (spec-basis · conflict-of-interest · insufficient-quotes · commission-rate-exceeds-cap) + placement-not-bound/double-booking guards + 1 soft (confidence/actuation gate) |
| `src/intermediation/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess/screen → supervised (binding and commission booking always human; placement intake auto-eligible, no liability risk) |
| `src/intermediation/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/intermediation/corporate_intel.cljc` | optional cross-reference into [`cloud-itonami-isic-8291`](https://github.com/cloud-itonami/cloud-itonami-isic-8291)'s `:disclosure/relationship-check` (ADR-2607110400 addendum 4) -- catches a broker clean on every LOCAL field but with an undisclosed professional-capacity relationship to THIS placement's customer in 8291's own sourced relationship-graph data; wired into `screen-conflict` via an injected `:placement-id` + fn, default is a no-op so every prior caller's behavior is unchanged unless explicitly opted in |
| `src/intermediation/sim.cljc` | demo driver |
| `test/intermediation/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage · corporate-intelligence integration |

## Business-process coverage (honest)

This actor covers placement intake through binding, and commission
booking -- the core governed lifecycle this blueprint's own `docs/
business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Placement intake + per-jurisdiction licensing/commission-cap checklisting, HARD-gated on an official spec-basis citation (`:placement/intake`/`:jurisdiction/assess`) | Policy-servicing handoff (a real-world post-placement operational workflow, not modeled as a governed data op) |
| Broker conflict-of-interest screening, HARD-gated un-overridable on any hit (`:conflict/screen`) | Real transfer-agent/banking-payment integration, tax/regulatory reporting |
| Placement-binding proposal, independently checked against a minimum-quote-comparison (best-interest) requirement (`:placement/bind`) | Multi-line/multi-policy bundled placements |
| Commission-booking proposal, independently checked against the jurisdiction's own recorded commission-rate cap and double-booking-protected (`:commission/book`) | |
| Immutable audit ledger for every intake/assessment/screening/binding/booking decision | |

Extending coverage is additive: add the next gate as its own governed
op with its own HARD checks and tests, following the SAME "an
independent governor re-verifies against the actor's own records before
any real-world act" pattern this repo's two flagship ops already
establish.

## Jurisdiction coverage (honest)

`intermediation.facts/coverage` reports how many requested
jurisdictions actually have an official spec-basis in `intermediation.
facts/catalog` -- currently 4 seeded (JPN, USA-NY, GBR, DEU) out of ~194
jurisdictions worldwide. This is a starting catalog to prove the
governor contract end-to-end, not a claim of global coverage. Adding a
jurisdiction is additive: one map entry in `intermediation.facts/
catalog`, citing a real official source -- never fabricate a
jurisdiction's requirements to make coverage look bigger.

## Maturity

`:implemented` -- `Broker-LLM` + `Insurance Intermediation Governor` run
as real, tested code (see `Run` above), promoted from the originally-
published `:blueprint`-tier scaffold, modeled closely on the sibling
`cloud-itonami-isic-6511`/`6512`/`6621`'s architecture. See
`docs/adr/0001-architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
