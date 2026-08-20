# Tasks

## 1. Formal models (gate-green before any engine routing)

- [ ] 1.1 `formal/stable-discovery/LeastPathOrder.dfy`: per-scan
      derivation coordinates over an acyclic `StableReducer.Program`
      (arity per rule kind); strict total lexicographic order; existence
      and uniqueness of the least path per derivable entity; order is a
      pure function of (program, tuples).
- [ ] 1.2 `formal/stable-discovery/LeastPathEnumeration.dfy`: the
      per-level sub-arm merge of ascending duplicate-free streams is
      ascending and duplicate-free; the ordered DFS over merged closures
      with the smaller-witness filter emits exactly the reachable
      denotation (bridge to `ReducerCompleteness` /
      `EaclForwardGrounding`), exactly once per entity, in ascending
      least-path order.
- [ ] 1.3 `formal/stable-discovery/LeastPathResume.dfy`: seeking every
      level strictly past a boundary path equals the suffix of the full
      enumeration; descending iteration emits each entity at the same
      position as ascending.
- [ ] 1.4 Witness-check equivalence lemmas: the level-wise
      decomposition of "a strictly smaller path derives e" into
      earlier-rule and smaller-eid clauses, each decided by an
      interleaved MIN-SIDE intersection (never a one-sided fan-in
      scan), equals the order-theoretic predicate — built on
      `BidirectionalArrowIntersection.dfy`'s `DecideEqualsArmAnswer`,
      extended per level (extend that leaf or add a fourth file if
      cleaner).
- [ ] 1.5 Register all new leaves in `verify-fast.sh` (manifest + batch
      assignment balanced against the 10 s ceiling), update
      `expected_dafny_obligations`, run the full gate green.

## 2. Sealed-plan and order-contract ABI v2

- [ ] 2.1 `sealed_plan.cljc`: add `{:order-mode :recursive?}` to the plan
      record and INTO the canonical digest; `order-contract` gains
      `:abi-version 2` documenting both modes; acyclic roots seal
      `:least-path`, recursive roots `:first-discovery`.
- [ ] 2.2 Bump the engine's public `stable-order-abi`. Pre-release: no
      migration or extra rejection machinery — one smoke test that an
      old-fingerprint cursor fails typed via the existing envelope is
      sufficient.
- [ ] 2.3 Re-run `SealedVectorOrder`/`RecordFraming`-adjacent gate leaves
      and the sealed-plan refinement bridge against the extended record;
      adjust the bridge fixtures for the new digest fields.

## 3. Least-path evaluator

- [ ] 3.1 New namespace `eacl.engine.least-path` (cljc): resumable
      ordered DFS over the sealed plan — per-scan coordinates; closures
      behind arrow-to-permission steps iterated via per-level sub-arm
      merges (schema-bounded stream count; merge resume = one shared
      bound, all sub-streams seek past it); chunked scans through the
      routed fetch-fn; cut-point before every adapter command;
      reducer-equivalent typed budgets; assert streams-opened-per-page
      ≤ plan alternatives × depth via `*backend-op-stats*` in tests.
- [ ] 3.2 Smaller-witness check: earlier-arm membership via exact-bound
      probes / bidirectional intersections; same-arm earlier-intermediate
      check as the interleaved MIN-SIDE intersection of the candidate's
      via-prefix with the closure-below-bound (never a one-sided fan-in
      scan — the shared-with-10k-orgs fixture must stay bounded);
      request-local memoization only.
- [ ] 3.3 Descending iteration (reverse seeks and reverse merges, same
      witness filter; ascending/descending position agreement test).
      Counts remain on the existing reducer route — out of scope.
- [ ] 3.4 Property harness before wiring: randomized acyclic schemas +
      tuples (CLJ and CLJS) — result-set equality vs `run-forward`/
      `run-reverse`, order equality vs a materialize-sort-dedup oracle,
      resume-from-every-boundary equals suffix, ascending/descending
      agreement, duplicate-heavy overlap fixtures.

## 4. Engine routing and cursors

- [ ] 4.1 `engine/v8.cljc`: route `stable-lookup-page` on the sealed
      `:order-mode`; counts and recursive plans reach their existing
      paths untouched; acyclic `:last`/`:before` no longer requires
      `:complete-denotation` (update `complete-evaluation-required!`
      guard and its tests).
- [ ] 4.2 Acyclic cursor payload: per-scan coordinate sequence inside
      the existing authenticated envelope; size assertion against the
      cursor budget; stale/invalid typing via the existing envelope
      checks only; continuation store not consulted for acyclic plans.
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
      O(page) flat (was O(k²)); (b) cache-on warm page regression ceiling
      ≤1.5× the checkpoint-resume baseline; (c) streams opened per page
      ≤ plan alternatives × depth (assert via `*backend-op-stats*`),
      never closure-bounded; (d) witness cost bounded on the
      shared-with-10k-intermediates overlap fixture (min-side property);
      (e) no regression on recursive-plan gates.
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
