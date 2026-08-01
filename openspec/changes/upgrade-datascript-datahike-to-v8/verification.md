# Verification evidence

## Provenance

- `release/v8.0` integration baseline: `00b19c090e1482a373a7efaa7aedf5eb7ac0777c`
- Corrected PR #81 head: `a40df8045a9a2580c01a12e99ea6a5ee44332d55`
- Merged `eacl/datascript` source commit: `8e7e464306a2c96dade8b0124d337bcb1cb14ab7`
- PR #81 was corrected, passed its required checks, and was merged before the
  Datahike storage layer was imported onto the v8 integration branch.

## Requirement-to-evidence map

| Requirement scenarios | Automated or documented evidence |
| --- | --- |
| Four independent modules; core-only and adapter consumers | Each module's `deps.edn`, `build.clj`, and README; isolated nREPL suites and `clojure -T:build jar` for all four modules |
| Legacy six-function SPI compatibility | `eacl.backend.v8-test/legacy-six-function-spi-remains-compatible-test` |
| Validated v8 operations and capabilities | `eacl.backend.v8-test`, the three `eacl.*.backend` namespaces, and `docs/v8-backend-adapter-boundary.md` |
| Incremental Datahike PR #81 upgrade | `eacl.datahike.db` remains the reviewed storage layer; `eacl.datahike.backend` adapts it while recursive traversal/cache validation live in `eacl` |
| Shared public API and equivalent fixtures | `eacl.contract-support/assert-v8-seeded-contracts!`, invoked by Datomic, DataScript, and Datahike contract tests |
| Datomic extraction compatibility | `eacl.datomic.v8-characterization-test` plus the complete Datomic non-benchmark suite |
| Self and mutual recursion, deep hierarchy, denial, duplicate paths | `assert-v8-recursive-contracts!` over the 12-level recursive fixture in all three adapters |
| Fixed-point safety ceilings | `assert-v8-recursive-safety-limit!` for derived grants, advanced datoms, and queued work in all adapters |
| Forward/reverse Relay, empty anchors, invalid arguments/cursors | Shared seeded/recursive contracts plus DataScript/Datahike implementation tests and Datomic API/characterization tests |
| Bounded counts, filters, relationship CRUD, deletion | Shared seeded contract, DataScript/Datahike contract tests, and Datomic object-deletion/API tests |
| Unsupported capability rejection | `eacl.backend.v8-test`, DataScript query-validation tests, and Datahike shared contract execution |
| Backend data-access boundary | Source inspection: `modules/eacl/src` imports no backend implementation namespace; adapter namespaces contain storage operations only |
| Portable cache store, disabled mode, corrupt/unavailable stores | `eacl.cache-test` and `assert-v8-cache-disabled!` in DataScript/Datahike |
| Exact relevant/unrelated relationship and schema proofs | Shared recursive cache matrix: unrelated relation/schema writes retain hits; relevant relation/schema writes and deletion miss |
| Multi-connection proof visibility | `eacl.datahike.contract-test/datahike-multi-connection-cache-proof-test` in both attribute modes |
| Recursive continuation validity | Shared recursive stale-cursor test: portable current-snapshot adapters reject after mutation; Datomic resumes the authenticated historical basis |
| Datomic-native transaction proof | Datomic consistency/cache, watermark, lookup-cache, schema-basis, and cache differential/model suites |
| Independent correctness oracle | `eacl.authorization-oracle` uses a separate in-memory least-fixed-point evaluator with stable fixture seed `820084`; every adapter compares its authorization set |
| Datahike attribute representations | Datahike contract and backend tests run with keyword attributes and `:attribute-refs? true` |
| DataScript runtimes | Identical shared/cache contracts run on the JVM and Node/ClojureScript |
| Datomic-specific guarantees | Consistency, historical basis, encrypted cursor, transaction-proof, schema-basis, and migration/database compatibility suites |
| CI and upgrade documentation | `.github/workflows/test.yml`, `docs/v8-backend-modules-and-upgrade.md`, module READMEs, and v8 release notes |

## Final local verification

- Combined non-benchmark JVM workspace: 251 tests, 11,557 assertions, zero
  failures/errors, run through nREPL.
- DataScript Node/ClojureScript: 9 tests, 97 assertions, zero failures/errors,
  launched through nREPL.
- Isolated `eacl`: 5 tests, 34 assertions.
- Isolated `eacl-datomic`: 232 tests, 11,284 assertions.
- Isolated `eacl-datascript`: 17 JVM tests, 151 assertions; 9 CLJS tests,
  97 assertions.
- Isolated `eacl-datahike`: 12 tests, 190 assertions.
- All four isolated jars built successfully.
- Benchmark/load tests were intentionally excluded according to `AGENTS.md`.
