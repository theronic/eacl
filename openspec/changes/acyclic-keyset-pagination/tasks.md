# Tasks

## 1. Formal models (gate-green before any engine routing)

- [ ] 1.1 `formal/stable-discovery/LeastPathOrder.dfy`: derivation
      coordinates over an acyclic `StableReducer.Program`; strict total
      lexicographic order; existence and uniqueness of the least path per
      derivable entity; order is a pure function of (program, tuples).
- [ ] 1.2 `formal/stable-discovery/LeastPathEnumeration.dfy`: ordered DFS
      with the smaller-witness filter emits exactly the reachable
      denotation (bridge to `ReducerCompleteness`), exactly once per
      entity, in ascending least-path order; emission count equals
      denotation cardinality (licenses the count route).
- [ ] 1.3 `formal/stable-discovery/LeastPathResume.dfy`: seeking every
      level strictly past a boundary path equals the suffix of the full
      enumeration; descending iteration emits each entity at the same
      position as ascending.
- [ ] 1.4 Witness-check equivalence lemmas: the probe-decided
      "strictly smaller path derives e" predicate equals the
      order-theoretic predicate, built on
      `BidirectionalArrowIntersection.dfy` (extend that leaf or add a
      fourth file if cleaner).
- [ ] 1.5 Register all new leaves in `verify-fast.sh` (manifest + batch
      assignment balanced against the 10 s ceiling), update
      `expected_dafny_obligations`, run the full gate green.

## 2. Sealed-plan and order-contract ABI v2

- [ ] 2.1 `sealed_plan.cljc`: add `{:order-mode :recursive?}` to the plan
      record and INTO the canonical digest; `order-contract` gains
      `:abi-version 2` documenting both modes; acyclic roots seal
      `:least-path`, recursive roots `:first-discovery`.
- [ ] 2.2 Bump the engine's public `stable-order-abi`; confirm cursor,
      continuation, and answer-cache identities that embed the fingerprint
      or order-abi reject stale values typed (they already carry both —
      add regression tests for both directions of mismatch).
- [ ] 2.3 Re-run `SealedVectorOrder`/`RecordFraming`-adjacent gate leaves
      and the sealed-plan refinement bridge against the extended record;
      adjust the bridge fixtures for the new digest fields.

## 3. Least-path evaluator

- [ ] 3.1 New namespace `eacl.engine.least-path` (cljc): resumable ordered
      DFS over the sealed plan — per-level stream state
      `(rule-ordinal, bound-eid)`, one open stream per level, chunked
      scans through the routed fetch-fn; cut-point before every adapter
      command; reducer-equivalent typed budgets
      (`:max-commands`/`:max-values`/`:max-stack` analogues).
- [ ] 3.2 Smaller-witness check: earlier-arm membership via exact-bound
      probes / bidirectional intersections; same-arm earlier-intermediate
      check via the candidate's via-set scan cut at the current
      intermediate with per-candidate point checks; memoize per-page
      arm-level closures in request-local state only.
- [ ] 3.3 Descending iteration (reverse seeks, same witness filter) and
      the count route (`limit+1` target, emission counting).
- [ ] 3.4 Property harness before wiring: randomized acyclic schemas +
      tuples (CLJ and CLJS) — result-set equality vs `run-forward`/
      `run-reverse`, order equality vs a materialize-sort-dedup oracle,
      resume-from-every-boundary equals suffix, ascending/descending
      agreement, duplicate-heavy overlap fixtures.

## 4. Engine routing and cursors

- [ ] 4.1 `engine/v8.cljc`: route `stable-lookup-page`, `count-resources`,
      `count-subjects` on the sealed `:order-mode`; recursive plans reach
      the existing stable-page path untouched; acyclic `:last`/`:before`
      no longer requires `:complete-denotation` (update
      `complete-evaluation-required!` guard and its tests).
- [ ] 4.2 Acyclic cursor payload: derivation path in place of
      `(ordinal, eid)` inside the existing authenticated envelope; size
      assertion against the cursor budget; stale/invalid/mismatch typing
      per the spec; continuation store not consulted for acyclic plans.
- [ ] 4.3 Relay/orchestration plumbing: cursor externalization for path
      payloads on the shared client and the Datomic client; per-request
      `:cache? false` on acyclic lookups keeps O(page) pagination (add the
      regression test that failed the demo scenario).

## 5. Cross-backend certification and performance gates

- [ ] 5.1 Differential suites on Datomic, Datahike, and DataScript
      (frozen fixtures + randomized): set-equality vs discovery reducer,
      order determinism across fresh processes, stateless deep-page
      latency flat in ordinal.
- [ ] 5.2 Regenerate acyclic frozen page-order baselines; point-check and
      count expectations must be byte-identical (order-independent).
- [ ] 5.3 Perf gates: (a) cache-off 100-page walk on the 20k fixture
      O(page) flat (was O(k²)); (b) acyclic count ≥5× vs reducer
      exhaustion; (c) Datahike stream-open count per page bounded by
      plan depth + witness probes (assert via `*backend-op-stats*`), never
      by total stream count; (d) no regression on recursive-plan gates.
- [ ] 5.4 CLJS parity: compile + run the DataScript CLJS suite with the
      new evaluator; parity corpus updated.

## 6. Ledgers, docs, release

- [ ] 6.1 `execution-contract.edn` executable evidence for the new tests;
      fix the stale `:production-map` names left from the retired
      generated-traversal router while touching the file.
- [ ] 6.2 `ASSURANCE_COVERAGE.md` rows for the least-path leaves and
      differentials; `formal/stable-discovery/README.md` leaf count;
      `docs/formal-verification.md` and `docs/stable-discovery-engine.md`
      order-ABI v2 sections; public source-closure ledger regenerated.
- [ ] 6.3 Release note: acyclic order change and one-time cursor
      invalidation (typed failure, restart walk); demo repos: verify the
      cache-off pagination pathology is gone end-to-end on the Datomic and
      Datahike demos.
