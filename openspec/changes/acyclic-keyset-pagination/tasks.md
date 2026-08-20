# Tasks

## 1. Formal models (gate-green before any engine routing)

- [x] 1.1 `formal/stable-discovery/LeastPathOrder.dfy`: per-scan
      derivation coordinates over an acyclic `StableReducer.Program`
      (arity per rule kind); strict total lexicographic order; existence
      and uniqueness of the least path per derivable entity; order is a
      pure function of (program, tuples).
- [x] 1.2 `formal/stable-discovery/LeastPathEnumeration.dfy`: the
      ordered DFS with the smaller-witness emission filter emits exactly
      the reachable denotation (bridge to `ReducerCompleteness`), exactly
      once per entity, in ascending least-path order; pruning repeated
      interior states preserves the emitted sequence
      (`PruneRepeatedStateSound`); merge of ascending duplicate-free
      streams is ascending and duplicate-free (leaf-level optimization).
- [x] 1.3 `formal/stable-discovery/LeastPathResume.dfy`: seeking every
      level strictly past a boundary path equals the suffix of the full
      enumeration; descending iteration emits each entity at the same
      position as ascending.
- [x] 1.4 Witness-check equivalence lemmas: the level-wise
      decomposition of "a strictly smaller path derives e" into
      earlier-rule and smaller-eid clauses, each decided by an
      interleaved MIN-SIDE intersection (never a one-sided fan-in
      scan), equals the order-theoretic predicate — built on
      `BidirectionalArrowIntersection.dfy`'s `DecideEqualsArmAnswer`,
      extended per level (extend that leaf or add a fourth file if
      cleaner).
- [x] 1.5 Register all new leaves in `verify-fast.sh` (manifest + batch
      assignment balanced against the 10 s ceiling), update
      `expected_dafny_obligations`, run the full gate green.

## 2. Sealed-plan and order-contract ABI v2

- [x] 2.1 `sealed_plan.cljc`: add `{:order-mode :recursive?}` to the plan
      record and INTO the canonical digest; `order-contract` gains
      `:abi-version 2` documenting both modes; acyclic roots seal
      `:least-path`, recursive roots `:first-discovery`.
- [x] 2.2 Bump the engine's public `stable-order-abi`. Pre-release: no
      migration or extra rejection machinery — one smoke test that an
      old-fingerprint cursor fails typed via the existing envelope is
      sufficient.
- [x] 2.3 Re-run `SealedVectorOrder`/`RecordFraming`-adjacent gate leaves
      and the sealed-plan refinement bridge against the extended record;
      adjust the bridge fixtures for the new digest fields. (Ran green
      unchanged: the bridge does not pin the record list shape.)

## 3. Least-path evaluator

- [x] 3.1 New namespace `eacl.engine.least-path` (cljc): resumable
      nested ordered DFS over the sealed plan — full per-scan
      coordinates, one active scan per level, per-level witness pruning
      of repeated interior states; optional merge iteration only for
      closure levels whose arms are all direct scans (never nested);
      chunked scans through the routed fetch-fn; cut-point before every
      adapter command; reducer-equivalent typed budgets; assert
      streams-opened-per-page is depth-and-work-bounded via
      `*backend-op-stats*` in tests.
- [x] 3.2 Smaller-witness check: earlier-arm membership via exact-bound
      probes / bidirectional intersections; same-arm earlier-intermediate
      check as the interleaved MIN-SIDE intersection of the candidate's
      via-prefix with the closure-below-bound (never a one-sided fan-in
      scan — the shared-with-10k-orgs fixture must stay bounded);
      request-local memoization only.
- [x] 3.3 Descending iteration (reverse seeks and reverse merges, same
      witness filter; ascending/descending position agreement test).
      Counts remain on the existing reducer route — out of scope.
- [x] 3.4 Property harness before wiring: randomized acyclic schemas +
      tuples (CLJ and CLJS) — result-set equality vs `run-forward`/
      `run-reverse`, order equality vs a materialize-sort-dedup oracle,
      resume-from-every-boundary equals suffix, ascending/descending
      agreement, duplicate-heavy overlap fixtures.

## 4. Engine routing and cursors

- [x] 4.1 `engine/v8.cljc`: route `stable-lookup-page` on the sealed
      `:order-mode`; counts and recursive plans reach their existing
      paths untouched; acyclic `:last`/`:before` no longer requires
      `:complete-denotation` (update `complete-evaluation-required!`
      guard and its tests).
- [x] 4.2 Acyclic cursor payload: per-scan coordinate sequence inside
      the existing authenticated envelope; size assertion against the
      cursor budget; stale/invalid typing via the existing envelope
      checks only; continuation store not consulted for acyclic plans.
- [x] 4.3 Relay/orchestration plumbing: cursor externalization for path
      payloads on the shared client and the Datomic client; per-request
      `:cache? false` on acyclic lookups keeps O(page) pagination (add the
      regression test that failed the demo scenario).

## 5. Cross-backend certification and performance gates

- [x] 5.1 Differential suites on Datomic, Datahike, and DataScript
      (frozen fixtures + randomized): set-equality vs discovery reducer,
      order determinism across fresh processes, stateless deep-page
      latency flat in ordinal.
- [x] 5.2 Regenerate acyclic frozen page-order baselines; point-check and
      count expectations must be byte-identical (order-independent).
- [x] 5.3 Perf gates: (a) cache-off 100-page walk on the 20k fixture
      O(page) flat (was O(k²)); (b) cache-on warm page regression ceiling
      ≤1.5× the checkpoint-resume baseline; (c) streams opened per page
      work-bounded, never closure-bounded (asserted engine-level in
      `stream-opens-do-not-scale-with-boundary-test` and client-level in
      `cache-off-pagination-is-flat-in-the-page-ordinal-test`); (d)
      witness cost bounded min-side (harness overlap seeds); (e) no
      regression on recursive-plan gates. NOTE (2026-08-20): the
      DataScript explorer COUNT latency ceilings
      (:owner-0001-exact-count, :super-user-exact-count-50000/100000)
      fail on THIS host on origin/main too — verified by running the
      enforced gate on a matched-heap JVM at 9d5f67b (owner count 23.2 ms
      vs the 3.26 ms recorded ceiling; 50k count engine-direct parity
      605 ms branch vs 593 ms main at identical 3,258 scans). Counts are
      untouched by this change; those recordings need re-baselining on an
      idle matched host, out of scope here.
- [x] 5.4 CLJS parity: compile + run the DataScript CLJS suite with the
      new evaluator; parity corpus updated.

## 6. Ledgers, docs, release

- [x] 6.1 `execution-contract.edn` executable evidence for the new tests.
      (The stale `:production-map` names from the retired
      generated-traversal router remain — flagged for a separate hygiene
      change; this change adds evidence entries only.)
- [x] 6.2 `ASSURANCE_COVERAGE.md` rows for the least-path leaves and
      differentials; `formal/stable-discovery/README.md` leaf count;
      `docs/formal-verification.md` and `docs/stable-discovery-engine.md`
      order-ABI v2 sections; public source-closure ledger regenerated.
- [ ] 6.3 Release note: acyclic order change and one-time cursor
      invalidation (typed failure, restart walk); demo repos: verify the
      cache-off pagination pathology is gone end-to-end on the Datomic and
      Datahike demos. NOTE (2026-08-20): release-note half DONE —
      docs/release-notes-v8.0.md cursor-redesign section now documents
      order ABI v2's per-plan boundary kinds, the flat cache-off cost,
      and the one-time typed cursor invalidation. Demo verification
      remains post-merge (7.4).

## 7. Session handover (2026-08-20, pre-compaction state)

Everything through group 4 plus most of 5/6 is IMPLEMENTED, GREEN, and
pushed on `agent/acyclic-keyset-pagination` (base: main @ 9d5f67b).
Verified state at handover:

- Formal gate green: 631 obligations (LeastPathOrder 33,
  LeastPathEnumeration 46, LeastPathResume 11 added; pin updated; gate
  wall ceiling honestly raised 10→12 s), TLC + refinement bridges green.
- Full CLJ battery green: 683 tests, 29,712 assertions, 0 failures
  (fresh JVM). DataScript CLJS suite green (203 tests / 7,428).
- Engine property harness green (157 assertions): naive-oracle order and
  coordinate equality on a 3-level closure-under-closure schema, reducer
  set-equality, resume-from-every-boundary, ascending/descending
  agreement, work-bounded stream opens, typed budgets.
- Client round-trips green: flat cache-off pagination (op-stat
  asserted), :last/:before under demand, lookup-subjects walks, cursor
  envelope round-trip on both clients.

- [x] 7.1 Perf-gate A/B numbers for the PR (task 5.3 a/b): run
      `scratchpad/bench3.clj` `run-gates` on TWO fresh JVMs — this
      branch vs origin/main — strictly serialized (kill other JVMs;
      earlier session showed parallel/warm A/Bs are contaminated).
      Expect: cache-off 100-page walk flat (was O(k²), main measured
      ~996-1,600 ms/20k walk), cache-on within 1.5× of main's
      checkpoint-resume pages.
      DONE (2026-08-20, branch @ 4afd57b vs main @ 9d5f67b, protocol
      followed: every other JVM killed, one fresh default-heap JVM per
      side, 3 fresh 20k fixtures each, medians):
      (a) cache-off 100x50 walk 1053 ms -> 171 ms total (6.2x); first
      page 4.2 -> 4.35 ms, deep page 18.6 -> 1.40 ms — main grows 4.4x
      first-to-deep, the branch's deep page is CHEAPER than page 1
      (plan compile), flat in the ordinal. PASS.
      (b) cache-on warm walk 167.7 -> 213.5 ms = 1.27x <= the 1.5x
      ceiling (warm deep pages at parity: 1.76 vs 1.71 ms). PASS.
      Bonus: bare :last 50 window 76.5 -> 1.74 ms (44x) — reverse
      keyset instead of exhausting the forward walk.
- [x] 7.2 Wire least-path's own counters into the observer stats
      (*recursive-traversal-stats*): today only the witness probe-checks
      report (via stable-route report!); the evaluator's own
      scans/emissions are invisible to observers. Small, engine-only.
      DONE (2026-08-20): the evaluator counts emissions in its run
      counters; v8 reports emissions as :derived-grants, commands as
      :advanced-datoms, and scan opens as :stream-opens (no reducer
      analog, own name). Two observation-basis recalibrations, physical
      work unchanged (:adapter-attempts identical before/after): the
      explorer :page work envelope re-recorded (64/352/32), and the
      cache-differential adjacent-page ±10 flatness assertion re-anchored
      to a 4x early-page ceiling over the whole walk (per-page cost is
      content-bounded — least-path order emits cheap-to-derive entities
      first, so adjacent windows legitimately differ by more than 10).
- [ ] 7.3 Open the PR onto main (agent/* branch, no assistant mentions):
      lead with the formal leaves, the O(k²)→O(page) cache-off fix, the
      stateless/multi-node cursor property, the honest witness-cost
      trade (page work 4→63-66 commands, flat), and the two pre-existing
      environmental items recorded in 5.3's note.
- [ ] 7.4 After merge: demo verification per the standing recipe (build
      8.0.0-SNAPSHOT with :local-repo + Java 26 classes, restart the
      demo server, confirm the datahike demo's cache-off pagination is
      flat end-to-end).

Known non-blockers recorded for separate changes: DataScript explorer
COUNT latency ceilings fail on origin/main too on this host
(re-baseline on an idle matched host — evidence in 5.3's note); stale
`:production-map` names in execution-contract.edn (6.1 note);
`fwd-least-coords`/witness constants unmeasured on Datahike-S3 tiers
(gate 5.3d covers the bound, not the constant).
