Conventions: tests via nREPL only (AGENTS.md), certification batteries on a freshly started JVM; `bin/formal source-closure` after every public-root edit; grep-anchored symbols, not line numbers; explicit-path `git add` only.

## 1. Survey and baselines

- [x] 1.1 Read every production namespace against `.rules/clojure-rules.md`, partitioned into nine areas, classifying each finding by frequency class and verifying callers by grep; record the accepted ledger.
- [x] 1.2 Baseline on a fresh nREPL: CI battery (1138 tests / 40439 assertions / 0 failures), `clj-kondo` (one stale warning), `bin/formal source-closure`, `bin/reflection-gate`; `bin/formal verify` (49 modules, 9384 proof efforts, 0 errors).

## 2. Operator engine

- [x] 2.1 Vector evaluator: masks derived from the memo row only under `*vector-stats*`; closed-key candidate check without allocation; no duplicate scan for one candidate; positional miss merge; stats guarded.
- [x] 2.2 Scalar evaluator: one deadline check per transition; nil-overrides fast path in `normalize-limits`; `arrow-values!` single counter revision; `:limit` on arrow scans.
- [x] 2.3 Recursive: components iterated in SCC order; `solve-bounds` returns the condensation reused by the exact pass; `attach-base-decisions!` decorated sort, volatile memo, touched-only rewrite; checkpoint digest once and optional (`:checkpoint? false` from the engine); single-pass arrow expansion; thunked checks; single counter revisions; dead `check-eids` removed; `published` atom replaced by a reduce.
- [x] 2.4 Seekable: one deadline check per scan; `:limit` on scans; `reduce` for furthest; dead cursor keys removed. Lookup: emission witnesses indexed per batch; widths only under stats; `add-counters` merge. Plan: `relation-partition` via `some`.
- [x] 2.5 Direct membership: batch-scoped scalar matcher; transient dispatch accumulator; thunked checks; stats map only when observed.
- [x] 2.6 Engine facade: `run-routed` envelope; shared least-path / stable-page option builders; shared recursive batch evaluator; shared count pipeline (`:cover-plan` reused on the acyclic count route); asserts and `:pre` removed; empty-page constant; dead `:basis-identity` option dropped.

## 3. Stable engine

- [x] 3.1 Fix the count-route cut-point regression: shared `stable-reducer/run-option-keys` superset used by stable-page, stable-route and least-path; regression test `exhaustive-counts-honour-the-request-cut-point-test` (shown to fail under the old keys).
- [x] 3.2 Remove engine `:pre` forms (probe check, derives-from-node?, make-context, forward/reverse page, edge-page/page, initial-state, resume, run-forward/reverse); drop the nil re-check in `derives-from-node?`.
- [x] 3.3 Least-path: route options selected once with plan and subject type; shared `earlier-in-sealed-order`; `not-any?`; no coordinate re-vectoring; reducer's `bounded-vector`.
- [x] 3.4 Reducer: `sidecar-state` two-revision retention; allocation-free `report-work-stats!`; route reporter guarded; `buffer-id` naming.
- [x] 3.5 Stable page: `subvec` page slices with a fresh lookahead vector. Sealed plan: dead `local-read-cost` removed (seal-time `current-order-contract` retained: a test pins the rebound-rank-contract derivation). Physical: dead `telemetry` removed, docs updated. Relationships: typed page rejection, `peek`/`rseq` idioms.
- [x] 3.6 Host page normalization folded into the kernel call via sentinel mapping (design D3).

## 4. Backends

- [x] 4.1 Shared `endpoint-pair/value-prefix?` component-wise; Datahike `tuple-prefix-matcher` shared by three scans; `normalize-scan-options` pass-through for maps naming a direction.
- [x] 4.2 Datomic: one lazy `endpoint-scan` for both directions with a conditional drop layer; relation definitions from the composite tuple index; `-fn` override seam and dead facade wrappers removed.
- [x] 4.3 Datahike: per-adapter store identity, revision, locator, attribute representation and storage stamp; batch kernel reads the contract once with a primitive progress cell; `scan-value` fn; cursor normalized once per scan.
- [x] 4.4 Datalevin: eager scan drops only an exclusive boundary row; numeric ids skip the read scope; dead helpers, re-exports, alias and unused assoc removed; `eager-entity` simplified; cursor normalized once per scan. DataScript: cursor normalized once per scan.
- [x] 4.5 Datalevin suite green on a fresh `:datalevin-test` nREPL.

## 5. Client, kernel boundary, caches

- [x] 5.1 Orchestration: per-candidate `check!` thunks; demand-key literal; validated-state memo path for the authorized scan; dead `can?`, dead `::request-context` branch, unreachable `:subject/relation` check, `base-filters` and a stale `declare` removed.
- [x] 5.2 Counters: ledger cache re-keyed to nested binding frames (design D6).
- [x] 5.3 Permission tree: single memo reads; duplicate cycle pre-check removed; leaves rendered directly.
- [x] 5.4 Verified kernel: allocation-free `exact-keys!`; one-key selection fast path; shared portable-integer predicates.
- [x] 5.5 Source: cached reflective `Thread.isVirtual` lookup. Proof frame: read-first `resolve!`, dead `:relation-generation-map`, single canonical-ids walk. Subproblem cache: constant-option fast path, indexed key checks. Cache: exact denotation source identity once per request. Relay: identity checks bracket the batch; volatile flag.
- [x] 5.6 Schema: `effective-expression-limits` identity memo; known-limit key set hoisted; dead `compatibility-digest`, `resolve-definitions`, `root-key`, `half-identity` removed; `expression-entity` one-arg; inner validation guards unwrapped; Datomic schema diff aliases the shared helpers; secure-format limit map passed through the walk.

## 6. Assurance harness and docs

- [x] 6.1 Manifest gate wired into the Dafny job (exit 3 accepted; Babashka set up); dormant CLJS scaling gate scheduled; verify-fast tool paths from the lock; browser-bundle freshness watches runtime patches; dead `nrepl_eval` timeout argument removed; duplicate merge-sort oracle deleted.
- [x] 6.2 Assurance contract and docs carry 9384 proof efforts; module READMEs list removed vars.

## 7. Final certification

- [x] 7.1 Fresh-JVM battery, `clj-kondo` over five roots, `bin/formal source-closure`, `bin/reflection-gate`.
- [x] 7.2 Fresh parity nREPL: mutation/generator/adversarial suites, strict counterexample replay, eight generated-differential suites, consistency-boundary and routing-certificate gates, DataScript CLJS suite; fresh Datalevin suite. Results in `implementation-notes.md`.
- [x] 7.3 `openspec validate` on the change; PR opened against the kernel-authority branch; demo repository upgraded to the resulting commit.
