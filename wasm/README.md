# wasm/ — kotoba-wasm deployment of the commission-rate-exceeds-cap check

`commission_cap.kotoba` is a port of `intermediation.governor/commission-
rate-exceeds-cap-violations`'s pure ground-truth comparison -- does a
placement's own recorded commission rate exceed THIS jurisdiction's own
recorded commission-rate cap? (see `src/intermediation/governor.cljc`
lines ~148-164) -- into the minimal `.kotoba` language subset, compiled
to a real WASM module via `kotoba wasm emit`, and hosted via
`kototama.tender` (`test/wasm/commission_cap_test.clj`).

The comparison itself already lives in the safe-kotoba subset as
`intermediation.kernels.gate/rate-exceeds-cap` (`src/intermediation/
kernels/gate.cljc`) -- this port is the same integer comparison, proven
under a real WASM guest instead of only JVM Clojure. This follows the
same `kotoba wasm emit` → `kototama.tender` pattern already proven by
`cloud-itonami-isic-6492`'s `wasm/affordability.kotoba`,
`cloud-itonami-isic-6511`'s `wasm/underwriting_decision.kotoba`,
`cloud-itonami-isic-6512`'s `wasm/claim_coverage.kotoba` and
`cloud-itonami-isic-6630`'s `wasm/fee_accrual.kotoba` (ADR-2607062330
addendum 5) -- another sibling actor's hot-path decision function ported
to real WASM.

## Why the source differs from `intermediation.governor`/`intermediation.kernels.gate`

The `.kotoba` compiler's actual WASM code-generator supports only a small,
empirically-verified subset: the special forms `do`/`let`/`if` plus
`+ - * quot / rem mod = < > <= >= zero? not inc dec` (confirmed by reading
`compile-wasm-expr` in `kotoba-lang/kotoba/src/kotoba/runtime.clj` -- no
`pos?`/`neg?`/`and`/`or`/`when`, unlike the broader tree-walking
interpreter). The port therefore:

- Ports ONLY the pure ground-truth arithmetic core -- the direct
  comparison of `commission-rate-bps` against `cap-bps` -- never the
  `store/placement` lookup, the `facts/commission-rate-cap` lookup, the
  nil-cap guard (`nil` means "no cap recorded", handled by the façade
  BEFORE the kernel is ever called), or the `:commission/book` op-dispatch,
  all of which stay in Clojure and never get ported (no maps, no
  protocols, no op-keyword dispatch, no nil in the wasm-compilable
  subset).
- Uses plain positional integer args instead of `{:keys [...]}` map
  destructuring (no maps in the wasm-compilable subset).
- Compares `commission-rate-bps <= cap-bps` directly as plain integers
  (basis points -- see below) instead of `intermediation.governor`'s
  `rate->x10000` double-scaling bridge feeding `gate/rate-exceeds-cap`'s
  `(< cap-x10000 rate-x10000)` -- no floats needed for a direct integer
  comparison, consistent with `cloud-itonami-isic-6492`/`-6512`/`-6630`'s
  own convention of representing rates/amounts as plain integers. No
  multiplication is needed here (unlike `fee_accrual.kotoba`'s formula
  recompute) -- this is a straight rate-vs-cap comparison, the same shape
  as `claim_coverage.kotoba`'s claim-vs-coverage comparison.
- Inverts the polarity relative to `gate.cljc`'s `rate-exceeds-cap`
  (returns 1 when the rate EXCEEDS the cap, i.e. on violation), whereas
  this module's `rate-within-cap?` (and `main`) returns `1` when the rate
  is WITHIN the cap (i.e. NOT a violation) and `0` when it exceeds the
  cap -- the more natural "is this OK" polarity for a boolean-shaped WASM
  export, same polarity convention as `affordability.kotoba`'s
  `affordable?` and `claim_coverage.kotoba`'s `claim-within-coverage?`.

This is a simple port -- one direct comparison, no multi-term formula, no
zero-guard branch -- the same shape as `claim_coverage.kotoba`, and
simpler than `fee_accrual.kotoba`'s rate×basis formula recompute.

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters (`:main-arity` --
the compiler only ever exports a 0-arity `main`, see `compile-wasm-expr`
in `kotoba-lang/kotoba/src/kotoba/runtime.clj`), so real inputs are passed
through the guest's exported linear memory instead -- the same convention
`cloud-itonami-isic-6492`'s `affordability.kotoba`,
`cloud-itonami-isic-6512`'s `claim_coverage.kotoba` and
`cloud-itonami-isic-6630`'s `fee_accrual.kotoba` use. A host writes two
little-endian i32 values (basis points -- `rate * 10000`, matching
`intermediation.kernels.gate`'s own `rate/cap int x10000` wire-code
convention and `fee_accrual.kotoba`'s `rate-bps` convention) before
calling `main()`:

| offset | field                 | unit                                          |
|--------|-----------------------|------------------------------------------------|
| 0      | `commission-rate-bps` | basis points (selected commission rate * 10000) |
| 4      | `cap-bps`              | basis points (jurisdiction's recorded cap * 10000) |

`main()` returns `1` (commission rate within the jurisdiction's cap --
compliant on this check) or `0` (commission rate exceeds the cap -- a
HARD `:commission-rate-exceeds-cap` violation per
`intermediation.governor`). Both offsets are well below `heap-base`
(2048), so they never collide with anything the compiler itself places
in memory.

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6622/wasm/commission_cap.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6622/wasm/commission_cap.wasm --json
```

Fleet deployment: not attempted in this pass — see cloud-itonami-isic-6492/6511 for the established pattern.
