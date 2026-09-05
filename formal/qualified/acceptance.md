# Phase 3 acceptance crosswalk

The independent finite models, native conformance, and public serving tests are
complementary. Models never execute in the authorization dependency closure.
`bin/formal fast` runs both the foundation and qualified gates. Every production
mutation first runs its unchanged test gate successfully, then must make that
same gate fail. These checks do not substitute for the broader host refinement
and independent review obligations recorded in the assurance manifest.

| Obligation | Production refinement / conformance |
|---|---|
| Qualifier lookup, faults, bound context, exclusive expiry | `qualification_bridge.clj`; four native qualification-data suites; JVM evaluator corpus |
| Correlated Caveat algebra and decisive temporal evidence | `evidence_bridge.clj`, `operator_bridge.clj`, `recursive_bridge.clj` |
| Native scans, arrows, stable discovery, counts, retained evidence | `seekable_bridge.clj`, `arrow_bridge.clj`, `stable_route_bridge.clj`, `discovery_bridge.clj`, `lookup_bridge.clj`, `legacy_lookup_bridge.clj` |
| Point-cache and live/pinned cursor interval acceptance | `temporal_bridge.clj`; public `qualified_check_test.cljc` and `qualified_cursor_test.cljc` |
| Complete content identity and unknown native writers | DataScript `qualifier_cache_test.cljc`, `qualified_cache_trace_test.cljc`; shared native cache-trace contract |
| Pair publication, retained definition references, deletion | Foundation `native_bridge.clj`; shared publication, batch, schema-allowance, and deletion contracts |

The evidence bridge additionally runs a reproducible 2,000-operation compound
expression campaign (seed `20260905`). It carries independent completion-set
oracle values through generated intermediate expressions, checking denotation,
deadline, completeness, and wire round trips. Fresh leaves remain in the sample
pool so absorbing faults do not collapse the campaign into only fault results.

| Required killed control | Registered production control |
|---|---|
| Omit qualifier lookup | qualified `:qualifier-reference-ignored` |
| Missing qualifier becomes ordinary | foundation `:missing-qualifier-becomes-nil`; qualified `:authoritative-failure-becomes-plain` |
| Publish only one endpoint | foundation `:one-half-publication` |
| Publish unresolved qualifier eid | qualified `:prepared-publication-leaks-unresolved-qid` |
| Inclusive expiry boundary | qualified `:expiry-boundary-retains-permission` |
| Reverse bound/request precedence | foundation `:bound-context-loses` |
| Conditional becomes true | qualified `:conditional-becomes-truthy` |
| Fault becomes absence | qualified `:fault-becomes-absence` |
| Unsafe decoded-data reuse | qualified `:qualifier-cache-omits-native-content`, `:qualifier-cache-omits-definition-content`, `:qualifier-cache-exact-scope-omits-native-basis` |
| Omit Relation stamp / commit fence | foundation `:publication-stamp-stalls`; qualified `:batch-publication-omits-relation-fence` |
| Reuse past an expiring ban | qualified `:temporal-point-loses-expiring-ban-witness`, `:qualified-cursor-loses-skipped-ban-deadline` |

Numerical release budgets and the executable four-backend workload are described
in `docs/benchmarks/qualified-authorization.md`. The nil-eid path must issue no
qualification-data reads. Compiled portable plans and native programs share one
bounded evaluator cache; it holds data/programs, never authorization decisions.
Every evaluator call validates the current definition envelope before content
reuse. Partial inputs still avoid native program construction and evaluation.

The exact content cache includes the original parameter payload, name, source,
and profile. A hit reuses the decoded parameter data as well as the compiled
plan. Bounds and closed field presence are checked before lookup; any changed
content misses and undergoes complete decoding/compilation. Dedicated tests
reject malformed or changed parameters, profile, name, and source after a hit.
The qualified gate explicitly runs all six Evidence unit tests in addition to
the refinement bridges and individual mutation controls.

The performance audit found repeated serialization of timeless Boolean evidence,
portable-plan compilation, byte-vector construction for UTF-8 length, and parsing
of freshly encoded host contexts. Their removal preserves canonical bytes and
input admission. BMP/surrogate conformance and host-context differential tests
check the changed boundaries, alongside the existing adversarial format suites.

The activated v9 default passes the fresh JVM battery: 1,431 tests / 149,296
assertions, plus 67 Datalevin tests / 8,172 assertions and 782 advanced
ClojureScript tests / 110,313 assertions. All have zero failures and errors.
The executable Caveat/expiry guide runs without an internal feature binding.
The coordinated five-module 9.0.0-SNAPSHOT build, dependency audit, cold local
Maven installation, and consumer smoke tests also pass. Datalevin remains
excluded from Maven release eligibility until its fork artifact is published.

The foundation gate verifies 82 obligations and 35,148 assertions; the qualified
gate verifies 71 obligations and 607,000 assertions across 392 tests, including
108 killed production controls. Source-closure reports 115 public roots, 2,939
reachable definitions, and no forbidden match. Qualified checkpoint checks
reject a certificate that excludes the original observation time even when it
includes the later request time. Strict OpenSpec validation passes.

The four-backend performance gate passes all 24 reports and 1,440 fixed budget
comparisons. Raw samples, source hashes, the checker result, and the full metric
matrix are retained in `docs/benchmarks/results/qualified-authorization-2026-09-05/`.
The benchmark explicitly selected legacy and qualified epochs before changing
the default and release/continuation ABI metadata. Numerical budgets were not
relaxed. Before activation, the full legacy JVM battery also passed 1,431 tests /
149,323 assertions; Datalevin's affected concurrent-write and cache contracts
passed in both epochs.
