## Why

The release gates that the cache, cursor, and checkpoint changes rely on are weaker than their ledgers claim. Two defects, both in already-applied and archived work:

1. **Most registered mutation controls never execute anything.** `formal/mutations/registry.edn` lists 107 mutants (103 Clojure, 4 Apalache) with `:required-score 1.0`, and `modules/eacl/test/eacl/formal/mutation_control_test.clj` reports every one killed. Of the 103 Clojure detectors, roughly eleven touch production code or repository files; the rest restate the expected and mutant values in test-local literals and compare them (for example `cache-fail-open-killed?` at lines 102–109, `numeric-ancestry` at 86–92, `cursor-scope` at 94–100, `current-cache-missing-entry-hit` at 133–141, `snapshot-exact-key-omits-lifecycle` at 143–154). Several of the executing ones are source-text greps (`fuel-cut-wave-rolls-back-original-state` at 1192–1211, `cljs-default-restores-generated-kernel` at 1108–1122). A "killed" mutant of that kind demonstrates that two literals differ, not that EACL detects the bug class. The four Apalache controls are genuine (`bin/formal` requires exit code 12 on a weakened configuration).
2. **Ledgers disagree and nothing checks them.** `formal/verification/manifest.edn` records `:killed 96 :registered 96` while the registry and `mutation-control.edn` both say 107; `assurance-matrix.edn` records the source closure as 58 files / 63 roots / 1,404 definitions while the committed `public-source-closure.json` says 77 / 70 / 1,715; `formal/README.md` and `docs/formal-verification.md` say the stable-discovery tree is 42 leaves / 528 obligations while `formal/stable-discovery/README.md` says 46 and `verify-fast.sh` enforces 631. `formal-implementation-conformance` already requires a stale theorem count to fail manifest validation; mutant counts and closure counts have no such check. `cache-kernel.edn` has recorded "live production consumer or removal" as remaining for `CacheKernel.dfy`, which is proof-only with zero consumers.

`introduce-proof-carrying-semantic-equivalence`, `enable-proof-equivalent-cursor-streams`, and `enable-proof-equivalent-checkpoints` each add obligations whose mutation controls must run against the implementation. Those controls should land on a harness where "killed" means executed.

## What Changes

- Every registered Clojure mutation control SHALL execute the production implementation, the generated artifact, or the formal model under the registered mutation; literal restatements are deleted or rewritten. The registry gains a `:mechanism` classification (`:executed-production`, `:executed-generated`, `:executed-model`, `:source-text`) and `:source-text` is permitted only for properties that are by nature about source (a namespace not required, a flag absent), never for a decision.
- Manifest validation compares the mutant count, the source-closure counts, and the stable-discovery leaf/obligation counts with their ledgers and fails on disagreement, exactly as it already does for theorem counts; the three stale records and the two READMEs are corrected.
- `CacheKernel.dfy` is removed from the corpus with its manifest rows, or given the consumer its ledger has been waiting for; the graph-ancestry model it carries was superseded by `ScalarFrontierCoherence.dfy`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `formally-verified-authorization-engine`: mutation controls must execute the thing they claim to mutate.
- `formal-implementation-conformance`: drift detection extends to mutant, closure, and leaf counts.

## Impact

- `formal/mutations/registry.edn`, `modules/eacl/test/eacl/formal/mutation_control_test.clj`, `formal/verification/{manifest,assurance-matrix,mutation-control,cache-kernel}.edn`, `bin/validate-verification-manifest`, `bin/generate-verification-manifest`, `formal/README.md`, `docs/formal-verification.md`, `formal/dafny/CacheKernel.dfy` and `EaclKernel.dfy` includes if removed.
- No production code changes. Independent of every other active change; best landed before the frame-reuse changes so their new controls are written against the repaired harness.

## Related changes

Already applied or archived; this change modifies their outcomes rather than their artifacts:

- `archive/2026-08-15-formally-verify-eacl-engine`: introduced the mutation registry, `mutation_control_test.clj`, and the "verification harness mutation controls" requirement whose detectors are tautological.
- `archive/2026-08-15-demand-bounded-authorization-execution`: origin of `formal-implementation-conformance` and its drift checks, which cover theorem counts but not mutant or closure counts.
- `archive/2026-08-15-eacl-v8-root-fixes` (`trusted-surface-hygiene`): "Formal models correspond to shipped algorithms"; `CacheKernel.dfy` is the remaining model with no shipped consumer.
- `adopt-stable-discovery-enumeration` (in progress): owns the retired-engine formal cut (task 9.2) and the stable-discovery gate counts; the leaf/obligation ledger check here must agree with that gate.
