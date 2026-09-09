## 1. Contract and Removal

- [x] 1.1 Re-read current callers and reconcile the authorized-read preservation/migration clauses in `2026-09-09-remove-lookup-relationship-filters`; verify the updated planning text points to this removal and preserves that change's unrelated scope.
- [x] 1.2 Remove `:authorization` from relationship-read admission and delete its dedicated validator; verify valid, nil, false, empty and malformed clauses all fail with `:eacl.filters/unknown-filter` before snapshot/cache/backend work on each bundled backend.
- [x] 1.3 Delete `authorization-scan-page` and authorized-read-only orchestration, schema and relay branches; verify a plain read containing a separately denied endpoint still returns its relationship with zero permission evaluations.
- [x] 1.4 Remove exclusively unused helpers, counters, scan benchmarks and tests; verify `rg` finds no supported authorized-read path and expiry-active inspection plus bulk-check tests still exercise their retained shared machinery.

## 2. Compatibility and Semantic Regression

- [x] 2.1 Verify old authorized cursor and restored cache-page identities cannot satisfy a plain read; add targeted compatibility regressions and change the relevant ABI only if the existing scope checks are insufficient.
- [x] 2.2 Verify plain read pagination across first/next/previous/final pages, cache read/population combinations and supported consistency modes using the existing cross-backend fixtures; confirm no gaps, duplicates or full collection materialization.
- [x] 2.3 Verify stored/expiry-active qualified inspection retains qualifier metadata, expiry filtering and bounded progress after removal using existing qualifier tests.
- [x] 2.4 Add or adapt a focused test-only `member - banned` case and run point, bulk and ordinary lookup/count regressions; confirm exclusion and the other existing operator/caveat suites do not depend on the removed API.
- [x] 2.5 Verify the public direct-read-plus-`can?` and direct-read-plus-`check-permissions` examples on one explicit snapshot, including denied endpoints and the APIs' existing result/error distinctions.

## 3. Documentation and Verification

- [x] 3.1 Update aggregate authorization docs, README references and release notes to remove the supported clause and explain caller-owned filtering/paging; verify live documentation has no current-use example of authorized relationship reads and preserve historical records as history.
- [x] 3.2 Capture before/after work and latency for a non-final 25-row single-relation anchored page with result caches and background lookahead disabled; verify zero permission evaluations and bounded consumed datoms, and keep raw reports under ignored `target/benchmarks/`.
- [x] 3.3 Run affected JVM tests through nREPL, the required core/backend regression battery and Datalevin coverage where configured; run the DataScript CLJS suite last and report actual pass/fail/unavailable results for each runtime.
- [x] 3.4 Run `bin/formal source-closure` after public source changes and the affected executable assurance checks; verify retained models/gates refer to supported operations rather than a removed authorized-read path.
- [x] 3.5 Validate this change strictly and deliver the implementation diff, test evidence and core version for the linked demo change; verify no unrelated source, fixture, storage or production deployment changes are included.
