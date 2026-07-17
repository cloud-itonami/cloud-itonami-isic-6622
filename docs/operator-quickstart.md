# Operator Quick Start

This guide is for licensed independent insurance agents and brokers who want to fork and operate this blueprint as an open-source governed intermediation runtime in their jurisdiction.

**What this guide covers:** cloning the repository, running the demo actor, executing the test suite, and locating the core Governor implementation. For production deployment, licensing, and compliance certification, see the [Operator Guide](./operator-guide.md) and [Business Model](./business-model.md).

## Prerequisites

- **Clojure CLI** (`clojure` command) — [install guide](https://clojure.org/guides/install_clojure)
- **Git** for cloning the repository
- **Monorepo siblings** (if working inside the workspace) — this repository references:
  - `kotoba-lang/langgraph` (the StateGraph actor runtime)
  - `kotoba-lang/langchain` (langchain-clj, transitive dependency)
  - `cloud-itonami/cloud-itonami-isic-8291` (optional, for corporate-intelligence cross-references)

  If running this as a standalone fork outside the monorepo, update `deps.edn` to use git coordinates instead of `:local/root` paths.

## Run the Demo

The demo walks one clean placement-binding lifecycle and one clean commission-booking lifecycle through the actor, plus six hard-hold test cases:

```bash
clojure -M:dev:run
```

This runs `src/intermediation/sim.cljc`, the demo driver. Watch the console output to see:
- Placement intake and needs assessment
- Per-jurisdiction licensing checks
- Broker conflict-of-interest screening
- Placement-binding proposal with quote-comparison validation
- Commission-booking proposal with rate-cap validation
- Audit ledger entries for each decision

## Run Tests

The test suite covers the governor contract, phase invariants, store parity, registry conformance, and jurisdiction facts coverage:

```bash
clojure -M:dev:test
```

Key test files:
- `test/intermediation/governor_test.clj` — Insurance Intermediation Governor hard/soft checks
- `test/intermediation/phase_test.clj` — Phase invariants (placement-bind and commission-book never auto)
- `test/intermediation/registry_test.clj` — Placement/commission draft records
- `test/intermediation/store_test.clj` — Store protocol (MemStore and DatomicStore parity)
- `test/intermediation/facts_test.clj` — Jurisdiction facts coverage and spec-basis citations

## Governor Location

The core **Insurance Intermediation Governor** lives in:

```
src/intermediation/governor.cljc
```

This namespace implements:
1. **Spec-basis check** — did the proposal cite an official jurisdiction source?
2. **Conflict-of-interest check** — undisclosed broker conflicts are hard-hold
3. **Insufficient-quotes check** — placements need ≥2 compared quotes
4. **Commission-rate-exceeds-cap check** — jurisdiction cap is un-overridable
5. **Soft check** — low confidence or actuation gate (human may approve)

See the docstring and test suite for the full contract.

## Related Namespaces

- `src/intermediation/operation.cljc` — StateGraph actor runtime
- `src/intermediation/brokerllm.cljc` — Broker-LLM Advisor (mock or LLM-backed)
- `src/intermediation/phase.cljc` — Phase table (0→3 workflow stages)
- `src/intermediation/facts.cljc` — Per-jurisdiction licensing/commission-cap catalog
- `src/intermediation/registry.cljc` — Placement-binding and commission-booking draft records
- `src/intermediation/store.cljc` — Store protocol (MemStore or DatomicStore) + audit ledger

## Static Analysis

Run clj-kondo linter (errors fail CI):

```bash
clojure -M:lint
```

## ClojureScript Testing (Optional)

The core `.cljc` code is portable to ClojureScript. Run the test suite in a Node.js runtime:

```bash
clojure -Sdeps '{:paths ["src" "test"]}' -M:dev:cljs \
  -m cljs.main --target node -m intermediation.portable-cljs-test-runner
```

## Next Steps

1. **Read the architecture** — `docs/adr/0001-architecture.md` covers design decisions, actor pattern, and the governor contract in detail.
2. **Review the business model** — `docs/business-model.md` defines the offer, revenue, and trust controls.
3. **Operator guide** — `docs/operator-guide.md` outlines deployment, production controls, and certification requirements.
4. **Fork and deploy** — See the main `README.md` for deployment notes and the open-business model.

## Troubleshooting

**`clojure` command not found?**  
Install the Clojure CLI: https://clojure.org/guides/install_clojure

**Local paths in `deps.edn` not resolving?**  
You're running this outside the monorepo. Edit `deps.edn` and replace `:local/root` with git coordinates:
```edn
io.github.com-junkawasaki/langgraph-clj
{:git/url "https://github.com/com-junkawasaki/langgraph-clj.git"
 :git/sha "<commit-hash>"}
```

**Tests failing?**  
Ensure the `test/` directory is present and all sibling repos are available (for the workspace checkout).
