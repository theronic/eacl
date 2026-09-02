## 1. Successful-Result Identity

- [x] 1.1 Add the portable invocation-control normalizer and unit tests proving it removes exactly timeout, cancellation, lookup-bypass, and publication controls while retaining every neighboring semantic field; verify the focused CLJ and CLJS identity tests pass.
- [x] 1.2 Apply the normalizer to completed lookup-page, relationship-page, count-resources, count-subjects, and visited externalized-page keys; verify focused integration tests change varying-timeout/token repeats from misses to hits while semantic, basis, consistency-mode, direction, size, and cursor-boundary changes still miss.
- [x] 1.3 Add restored-cache compatibility regressions showing timeout-bearing legacy keys remain unreachable while already canonical compatible entries remain usable; verify snapshot export/restore tests pass without changing the snapshot format or value ABI.

## 2. Deadline and Cancellation Correctness

- [x] 2.1 Add fake-clock and cancellation-token regressions around warm completed/page-cache lookup and externalization; verify different live budgets reuse answers while expired or cancelled invocations retain their typed failure and publish nothing.
- [x] 2.2 Run the existing deadline, cancellation, cursor-authentication, consistency, resource-limit, and cache-bypass suites with namespace reloads; verify there is no semantic, counter, cursor, or typed-error drift.

## 3. Bounded Page-Navigation Cache

- [x] 3.1 Replace vector filtering and reverse-index scans with the portable generation-stamped FIFO transition, direct owned-boundary removal, and bounded stale-record compaction; verify focused replacement, mixed-size alias, eviction, and clear tests pass.
- [x] 3.2 Add deterministic state-machine traces at capacities 64, 512, and 2,048 covering repeated replacement, forward/reverse alias churn, and eviction; verify entry/index/queue ceilings and amortized operation-count assertions pass in CLJ and CLJS.
- [x] 3.3 Add read-only page-navigation statistics to client cache diagnostics; verify publication/replacement/alias/eviction counters are exact under atomic retries and repeated hits leave the complete page-cache state and statistics unchanged.

## 4. Performance Evidence

- [x] 4.1 Add a reproducible source-bound benchmark for full-cache publication/eviction and exact lookup at capacities 64, 512, and 2,048; verify the frozen candidate clears the predeclared 50% capacity-2,048 improvement, 2.5x scale-ratio, and 10% hit non-regression gates.
- [x] 4.2 Add and run the predeclared end-to-end varying-timeout lookup-resources mechanism lane plus cold miss, fixed-timeout hit, adjacent reverse alias, count hit, deadline, cancellation, and CLJS safety lanes; then rerun PR 160's existing single frozen releaseWin and verify fresh confirmation evidence clears every unchanged threshold.
- [x] 4.3 Run the complete existing multi-cardinality performance battery, including all affected page/count/cache regimes and 30,000-, 100,000-, and verified 1,000,000-result Datomic lanes where supported; verify no affected lane is replaced by or omitted in favor of a single 4,096 count-resources result.

## 5. Full Qualification and Handoff

- [x] 5.1 Run the repository's complete ordinary CLJ suite, DataScript CLJS suite, Datomic/DataScript/Datahike/Datalevin contract and differential suites, reflection gate, isolated artifact builds, and cold release smoke; verify every command exits successfully.
- [x] 5.2 Run the complete generated-runtime, formal, mutation, source-closure, artifact-size, Java/JavaScript differential, and advanced-CLJS conformance battery; verify every applicable formal consumer and portable runtime gate passes.
- [x] 5.3 Re-run strict OpenSpec validation, inspect the final diff and dirty-file inventory, and verify only this change's Core source, tests, evidence, and OpenSpec artifacts are selected for commit on `codex/eacl-performance-amplification`.
