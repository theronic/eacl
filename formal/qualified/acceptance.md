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

## Release checks

Run the fresh JVM battery, Datalevin contracts, advanced ClojureScript suite,
executable Caveat/expiry guide, foundation and qualified formal gates, historical
counterexample replay, mutation controls, generated Java boundaries, and public
source closure. Require zero failures/errors and no forbidden source matches.
Qualified checkpoints must reject a certificate excluding the original
observation time even if it includes the later request time. Historical traces
preserve authorization and structural-reuse oracles while selecting exact
qualified versus scalar-proof legacy answer reuse.

Run the coordinated five-module build, dependency audit, cold local Maven
installation, consumer smoke tests, and strict OpenSpec validation. Datalevin
remains excluded from Maven release eligibility until its fork artifact is
published.

Performance acceptance requires the four-backend workload and every fixed
budget comparison described in `docs/benchmarks/qualified-authorization.md`.
Keep samples, source hashes, checker results, matrices, and current verification
counts under ignored `target/` or in CI artifacts. Do not relax budgets to accept
a candidate or treat a previous successful run as current-source evidence.

## Native contention qualification after activation

The generated-authority CI benchmark exposed two previously unexercised v8
paths. Its unrelated-commit check now asserts exact-basis fallback in v8 and
managed hits only in the retained v8 compatibility binding; its result labels
state which reuse contract was measured. DataScript and Datahike writers now
recognize their native CAS failures through bounded exception wrappers and
replan from the current basis, with the existing eight-attempt public
contention limit. A changed cleanup source is also retryable; qualifier
validation faults remain terminal.

Deterministic shared tests commit a competing Relationship between planning
and submission, verify one fresh retry publishes both Relationships, verify
persistent contention stops after eight attempts, and verify validation faults
are attempted once. Run both generated-authority suites, affected native-writer
contracts, advanced CLJS, foundation/qualified gates, and public source closure
when changing this boundary.

The qualification workload measures successful read and write paths. The new retry policy affects failed native submissions;
concurrent-write behavior has separate deterministic and benchmark evidence.
