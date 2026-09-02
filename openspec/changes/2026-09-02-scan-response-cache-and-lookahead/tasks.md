## 1. Baseline and harness

- [x] 1.1 Add the sparse high-sharing fixture builder (users, groups, docs, empty fraction, seed) as a shared test-support namespace usable by every backend's client through the public write API, and verify each backend builds the fixture with identical relationship counts.
- [x] 1.2 Add a paired same-process benchmark harness that runs one workload in two modes within one JVM (warm-up, interleaved trials, medians, adapter-command counts through the invoke observer) and verify it reports both modes and their command counts for the four backends.
- [x] 1.3 Record the pre-change baseline (page, `can?`, count, batch, continuation; commands per page) per backend in the change's implementation notes and verify the numbers reproduce within 10 percent on a second run.

## 2. Canonical comparator and per-page re-rendering

- [x] 2.1 Replace the keyword branch of the canonical comparator with the cached runtime keyword string, keep every other operand pair on the rendering path, and verify the canonical-encoding fixtures and secure-format tests are byte-identical.
- [x] 2.2 Add a generative sign-equality differential between the rendering comparator and the new comparator over keyword pairs drawn from the accepted keyword grammar (namespaced and bare, every accepted character class) and verify it passes on both runtimes.
- [x] 2.3 Hoist the lineage comparison in the relay dependency context and the acquisition-time bounded canonical check to per-basis memos in the request context and verify page and `can?` results and cursors are unchanged by the existing relay and consistency suites.
- [x] 2.4 Re-run the paired harness for §2 alone on all four backends and verify p50 page and `can?` latency improve with identical results (record the numbers).

## 3. Scan-response cache

- [x] 3.1 Create the cross-runtime scan-cache namespace: descriptor key (operation, anchor type and id, relation id, target type, direction), prefix entry `{:prefix :exhausted?}`, `serve`, `extend`, and the request-local memo API, and verify unit tests cover full hit, exhausted short hit, short non-exhausted miss, fragment rejection, contiguous extension, and the per-entry cap on both runtimes.
- [x] 3.2 Add the caching fetch function that wraps the routed retrying fetch function: memo lookup, shared-tier lookup under scope, forward the identical command on miss, deposit only complete replies, honor `:direction`, and verify a fetch-level test shows the command multiset subset property and equal replies over randomized scans.
- [x] 3.3 Add the scope resolver: scope from the request's basis identity plus the scanned relation's generation taken from any complete resolved closure of the request that contains it, falling back to one resolution of the plan's closure; bypass the shared tier when the frame is unavailable, incomplete, or the snapshot is not ordinary; and verify tests for each bypass condition and for unrelated-write reuse versus relevant-write invalidation.
- [x] 3.4 Give the request context a memo slot and the internal test seam that disables it, and verify the memo is released with the request and never reachable from another request.
- [x] 3.5 Wire the shared tier as a client-owned standard store under `:scan-cache {:max-entries :max-prefix}` with per-backend defaults from §7, validate the option at client construction, expose the tier's meters in the client's cache statistics, and verify `expire-cache!` makes stored prefixes unreachable.
- [x] 3.6 Wire the caching fetch function into the engine facade's routed fetch functions for the reducer, the least-path evaluator, and the probe route, and verify the batch-of-checks and chunked-filtered-lookup scenarios issue exactly one command per repeated descriptor.
- [x] 3.7 Verify limits and deadlines are unaffected: a test that fails a fetched-value limit and a command limit at the same transition with all scans served from cache, and a test that cancels at the same check point.

## 4. Lookahead

- [x] 4.1 Add the `:lookahead` client option (`:pages`, `:max-inflight`) with typed validation and a no-op on ClojureScript, and verify construction tests for valid, invalid, and absent values.
- [x] 4.2 Add the per-client bounded lookahead executor (virtual threads when available), the in-flight set keyed by operation, normalized query, and basis key, and the submission hook after successful page publication for lookup-resources, lookup-subjects, and read-relationships, and verify a test that the continuation becomes an exact rendered-page hit after the lookahead completes.
- [x] 4.3 Verify isolation: the foreground page's result, cursors, counters, deadline behavior, and service-admission accounting are identical with and without lookahead; a saturated executor drops submissions silently; a throwing lookahead reaches only the observer.
- [x] 4.4 Verify basis movement: a write between the served page and the continuation request yields the ordinary path's answer on the new basis, and the lookahead's stale publication is never served.

## 4b. Range answer reuse

- [x] 4b.1 Retain per-item cursor edges in the internal page value at the three page-assembly sites for unbounded routes, bump the completed-answer envelope format, and verify existing answer-tier tests treat older envelopes as misses and new envelopes round-trip.
- [x] 4b.2 Add the range key (exact semantic key minus page size, plus direction and start edge) and its retention rule (longest page wins) to the answer tier, and verify a test that a `:first 20` publication supersedes a `:first 10` entry under the same range key.
- [x] 4b.3 Add prefix derivation on exact miss for `:first` and suffix derivation for `:last`, rebuilding page info from retained edges, routed through the ordinary render-and-publish path, and verify derived pages equal uncached computations (results, cursors, flags) on all four backends.
- [x] 4b.4 Record composition for longer requests (resident page plus its ordinary continuation) as a follow-up with the plumbing it needs (re-entering the page pipeline with an internal boundary), and verify the notes name the spec's MAY clause it leaves unexercised.
- [x] 4b.5 Verify `:cache? false` and a disabled client cache bypass range reuse, and that bounded candidate-window pages never publish a range entry.

## 5. Request I/O observation

- [x] 5.1 Add ledger counters for scan-cache hits, misses, and elided commands and verify the counter tests and the preindexed-slot invariants still pass.
- [x] 5.2 Add the `:io-observer` client option and the request-boundary hook (operation, provenance, elapsed nanoseconds, mandatory meters) with a single reference test on the unobserved path, and verify tests for observer values equal to the limit-governing counters, observer exceptions not affecting results, and lookahead provenance.
- [x] 5.3 Add the Datahike storage-statistics helper resolving the S3 backend's statistics functions at call time, register it as a public source-closure root, and verify a test with a stub backend namespace and a test without it (`:unavailable`).

## 6. Formal and mutation controls

- [x] 6.1 Add `ScanResponseCache.dfy` proving served chunks equal `Chunk(values, pos(b), L)` and that contiguous extension preserves the prefix invariant, add the singleton-frontier lemma to the scalar-frontier model, update the fast verifier's expected obligation count and the assurance matrix from the report, and verify `bin/formal verify` and the fast verifier pass.
- [x] 6.2 Add the five executed mutation controls (short non-exhausted serve, values not beyond the bound, stale relation generation, widened limit or moved bound, fragment deposit) to the mutation registry and verify each mutant is killed by a named existing or new test.
- [x] 6.3 Add the cache-neutrality differential to the parity suites: commands subset, replies equal, public outcomes identical across shared-tier on/off and memo on/off, on randomized graphs with interleaved supported writes, all four backends, both runtimes, and verify it runs in the parity job.
- [x] 6.4 Recorded instead of modeled: exact-basis keys make a stale background publication unreachable for a newer basis, and the Datomic lookahead test exercises the basis-moved case executably (see implementation notes, deviations).
- [x] 6.5 Add `RangeAnswerReuse.dfy` (shorter page is a prefix of the longer resident page from the same start boundary, a complete resident page answers larger requests unchanged, derived next-page flag) to the fast verifier, and two executed mutation controls for `derive-page` (end-edge off by one, ignored next-page flag).
- [x] 6.6 Add `scan_response_cache_refinement_bridge.clj`: the production `serve`/`extend-entry` against a transcription of `ScanResponseCache.dfy` over 4,000 randomized cases per operation in both scan directions, with model-level killed controls, declared in the fast verifier's bridge manifest.

## 7. Paired gates and defaults

- [x] 7.1 Run the paired gate per backend (oracle equality, ≥90 percent elision on the sparse fixture after warm-up, ≤2 percent p50 regression with the tier enabled and empty) and set each backend's `:scan-cache` default from the outcome, recording refusals in the verification evidence.
- [x] 7.2 Add a CI smoke test of the paired harness on one small fixture per backend and verify it runs in the ordinary test workflow.
- [x] 7.3 Verify the consistency-boundary and routing-certificate gates and the reflection gate still pass on the final tree.

## 8. Documentation

- [x] 8.1 Update `docs/cache.md`, `docs/v8-subproblem-cache.md`, and `docs/stable-discovery-engine.md` for the scan-response tiers, lookahead, and observation, and verify the documentation characterization tests pass.
- [x] 8.2 Document the new client options and the Datahike helper in the module READMEs and verify the option lists match the validated key sets.
- [x] 8.3 Replace hardcoded proof-effort and module counts in `docs/formal-verification.md` and `formal/README.md` with a pointer to the CI verification report, and verify no numeric count remains in those documents.

## 9. Certification

- [x] 9.1 Run the CI battery, the ClojureScript suite, the Datalevin suite, the parity trio, strict counterexample replay, and the differential suites on fresh JVMs and verify all pass with the counts recorded in the implementation notes.
- [x] 9.2 Run `clj-kondo`, the source-closure gate, and the reflection gate and verify they are clean.
- [x] 9.3 Open the pull request on an `agent/*` branch with the change's evidence and verify CI is green.

## 10. Recorded follow-ups (not in this change)

- [x] 10.1 Record in the implementation notes, with the measurements that justify deferral: order-insensitive counts (CPU and memory, not remote reads), compact reducer checkpoints, endpoint-scoped dependency stamps, materialized recursive closure, Datalevin native scan cost, and DynamoDB storage-backend statistics; verify each entry names the evidence.
