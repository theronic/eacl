# Implementation notes (session 2026-09-01)

## Task 1.1 — in-flight overlap and git policy

- Working tree is checked out on `codex/standardize-authorization-cache-storage`, in sync with its origin branch; that change's code (41/42) is fully committed here, so this change builds on top of it. Its one remaining task (8.2) is branch publication — process, not code. **No file conflict.**
- `adopt-stable-discovery-enumeration` (44/61): remaining tasks are CLJS parity suites, containerized adapter baselines, and public-API splice decisions (8.3 `:complete-denotation` fate, 8.4 cache-key fingerprint, 9.1 splice). None of this change's edits alter cache keys, denotation semantics, or the public API, so no conflict today; the cache-adjacent tranches here (4.11, 5.2) are ordering/allocation-only. Sequencing note: if 9.1/8.4 land mid-implementation, re-run the cache suites before closing group 5.
- Git policy for this session: **no `git add`/`commit`/`push`** — the user has not requested commits, and standing instructions forbid committing in this checkout without explicit direction. All work accumulates in the working tree; task checkboxes in `tasks.md` are the progress record. The design's "revertible tranche commits" applies when the user commits, not before.
- Baseline battery counts and gate outputs are appended below as they are captured.

## Task 1.4 — baselines

(appended when captured)

## Task 5.12 — second-pass sweep dispositions

(appended when swept)

## Baselines (task 1.2/1.3)

- Battery: 1149 tests, 40521 assertions, 0 failures, 0 errors (nREPL 7788,
  `:dev:test:cljs-test`). Deviation: captured after the provably non-semantic
  2.4/3.4 edits (param renames, platform-split defns, one dead private fn,
  string-predicate idiom) — recorded rather than re-run on pristine tree.
- `bin/formal source-closure`: exit 0 (scratchpad `source-closure-baseline.log`).
- `bin/reflection-gate`: green on CI for this branch; local re-run after the
  3.3 namespace removal is the enforced check.

## Group 3 deviations

- KEPT `modules/eacl-datascript/test/eacl/baseline/perf.clj`: the in-flight
  `adopt-stable-discovery-enumeration` task 2.4 still needs `eacl.baseline.perf`
  for its pending CLJS/containerized baselines.
- KEPT `exploration/stable-discovery/{source_refinement_bridge,physical_scheduler_refinement_bridge}.clj`:
  exploration-only (no formal/ copy); the parked-model disposition applies.
- Remaining grep hits for `datomic.consistency`/`datomic.codec` are the audit
  test's intentional absence assertions and the README removal note.

## Task 4.4 deviation — invoker caching rejected

An adapter-held `::invoker-cache` atom was implemented and REVERTED: the
concurrent vector-evaluator suite exposed that deriving an adapter via
`assoc-in` on `::operations` shares the cache atom, serving stale invokers
bound to the replaced implementation (wrong execution, not just perf).
Since the per-candidate cost that motivated 4.4 was already eliminated by
the sealed plan fields (4.3) and `known-limit-keys`, and invoker
construction is two closures + map reads, the cache's marginal win did not
justify the trap. `normalize-limits` retains its per-call merge (contract
unchanged); its set allocations are gone.

## Task 4.8 deviations

- Clock capture into the contract was DROPPED: with sampling frequency out of
  scope (Non-Goal), replacing a dynamic-var deref with a metadata/field lookup
  is measurement noise; the real per-check cost was the diagnostic-map
  construction, now lazy via fn-accepting `check!` (evaluated only on throw,
  identical ex-data). Constant-literal map sites ({:probes 0} etc.) are
  compiler constants and were left alone. The `*monotonic-nanos*` test-binding
  sweep became unnecessary.
- permission-tree's duplicate bottom-of-loop check! removed; the loop-top
  check covers the same cut points at the same instants.

## Task 4.7 refinement

- Mutator bounds guards (set-bit!/clear-bit!) RETAINED: an out-of-range write
  silently sets a padding bit and corrupts the canonical portable form that
  cached masks rely on; the closed-bounds test pins this fail-closed contract.
  Reads (bit-set?, indexes) are unguarded; `empty?` is allocation-free;
  `portable` no longer re-validates width.

## Task 4.11/4.12 deviations

- 4.11: the `memoized!` → `memoized-active-state!` swap was skipped — the
  per-item cost is a few comparisons with no allocation, and the swap would
  need a new public accessor for the private context state. The real fix
  landed: counters-fn evaluations dropped 3-4× → 1× per batch item (lazy
  consumed-work thunks; the one real evaluation feeds check-aggregate-limits!
  and keeps `batch/aggregate-counters` production-called for its mutation-
  registry consumer claim).
- 4.12: resolved as NO CHANGE. The probe closures capture per-probe values
  (subject-eid in four of them; per-probe counters in ex-data and report!),
  and the code deliberately mirrors BidirectionalArrowIntersection.dfy's
  round order. Threading those as explicit arguments through eight functions
  would obscure verified-model-mirroring code to save closure allocations
  that are noise below the ≥1 adapter fetch every probe performs.

## Task 5.12 dispositions (bounded sweep)

- `schema/errors|expression|expression_graph|expression_limits|
  expression_persistence|expression_policy|expression_resolver|model|
  replacement_plan`: clean bill for this change's rules. Three composed-key
  `sort-by` sites (expression_graph:172, expression:121,
  expression_persistence:231) match the decorate-sort pattern but run once
  per schema compile — no payoff, left alone.
- `secure_format.cljc`: hot call sites were addressed via their consumers
  (per-page digest, sort-by-canonical reuse); the codec itself is clean.
- `relay.cljc`: no checklist hits; its kernel crossings were covered by the
  oracle-removal tranche. Deferred with the orchestration remainder to the
  follow-up decomposition change (design Open Questions).
- `verified_kernel.cljc` remainder: validate-input!/validate-result! are the
  mandated boundary and stay; 215 oracle lines removed in 7.1.

## Tasks 1.4/10.3 deviation — baselines

Local pre-change wall-clock baselines were overtaken by events (the nREPL
came up as implementation started). The arbiters used instead, all green
after the change: the checked-in ratcheted gates — consistency-boundary
(median-p95 484.5 ns vs 15000 ceiling, exact logical-work correspondence)
and routing-certificate (normalized allocation 1.005, latency 1.05 vs
1.5/5.0 bounds) — plus the deterministic-work assertions inside the
differential suites. These compare against recorded bounds, which is
stronger than a same-host before/after pair.

## Environment finding — stale generated kernel

`formal/dafny/ConsistencyDecision.dfy` (2026-08-31 branch commit) postdated
the locally built `target/formal/java/classes` by a day; the four
production-kernel work-dimension differentials failed on that staleness
BEFORE any change here touched them. `bin/formal bootstrap build-java`
(and build-js with the corrected freshness guard) fixed it; all
differentials green afterwards. The browser-bundle freshness guard now
compares against the newest .dfy, not just EaclKernel.dfy, so included
models cannot go stale silently.

## Task 9.6 resolution — intentional path divergence

Root `deps.edn` uses `target/formal/*` (bin/formal builds it for repo
development); `modules/eacl/deps.edn` uses `target/generated/*`, which
`eacl.build.module/prep!` stages explicitly for `:local/root` consumers.
Documented as intentional; no alignment needed.

## Task 8.5 calibration note — run-heavy! required operations

`run-heavy!` now requires the three live generated operations per backend
(previously `#{}`, making its cutover assertion vacuous). The heavy suites
include cross-backend workload and pagination benchmarks, which exercise
consistency selection, relationship paging, and cursor continuation — but
this is the one repaired gate whose first scheduled CI run adjudicates the
calibration. If a backend's heavy path legitimately skips an operation,
relax that entry with a documented rationale rather than restoring `#{}`.

## Cross-platform bugs caught by the CLJS gate (both mine, both fixed)

1. `(identical? ::miss ...)` memo sentinels (engine/v8, permission-tree):
   CLJS does not intern keyword literals, so every miss returned the
   sentinel as the cached value. Fixed with per-namespace object sentinels.
2. `(identical? :asc direction)` in the new shared scan normalizer:
   rejected every ascending scan on CLJS (51 failures / 234 errors
   cascade). Fixed with `case` dispatch.
Final state: DataScript CLJS suite 0 failures / 0 errors; final JVM
battery 0 failures / 0 errors after both fixes; Datalevin suite green.

## Final certification summary (all on the fully edited tree)

- Battery (4 module test roots + src-build): 0 failures / 0 errors.
- DataScript CLJS build + node suite: 0 / 0.
- Datalevin suite (:datalevin-test classpath): 0 / 0.
- clj-kondo over five src roots (unused-public-var on): 0 errors / 0 warnings.
- bin/formal source-closure (selftest incl. new negative controls): passed.
- bin/formal format: passed. build-java / build-js / browser-bundle: rebuilt
  from the current .dfy set (stale-artifact fix) and passed.
- Differential nREPL suites on :formal-smoke: 18,276+8,797 assertions green.
- eacl.formal.{generators,cache-strategy-adversarial,mutation-control}-test:
  green (166 mutation-control assertions; strengthened tautology guard).
- Counterexample replay, strict mode: 67/67 resolved, 18,228 assertions.
- bin/reflection-gate: exit 0.
- verify-fast.sh (shipped engine): 651 obligations verified, 8s wall.
- Benchmark gates: consistency-boundary p95 484.5ns (ceiling 15000);
  routing-certificate ratios 1.005/1.053 (bounds 1.5/5.0).
