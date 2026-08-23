## 1. Baseline, Harness, and Reconciliation

- [x] 1.1 Record the pre-change baseline on the retained Datalevin fixture plus a sparse (20 %) and an all-rejected fixture: cache-hit and cache-bypass scalar checks, ten-row relationship page, acquisition, plan seal, scalar-loop dense/sparse/all-rejected pages, and `lookup-resources` for each subject — p50/p95/p99, allocation, raw samples, host/JDK/heap/backend metadata — as a machine-readable file under `formal/verification/`.
- [x] 1.2 Build the paired same-process benchmark harness (interleaved arms, shared fixture, environment metadata, absolute-ceiling check per host class) and the per-request counter ledger (acquisitions, public entries, context constructions, seals, definition reads, generation reads, proof derivations, cursor builds, commands, fetched values, candidates examined, probes, publications).
- [x] 1.3 Record loopback HTTP series for the demo's check, lookup, and filtered-page endpoints together with a no-op endpoint that isolates Ring/JSON/semaphore overhead.
- [x] 1.4 Amend `stable-engine-request-path-performance`: remove tasks 2.1–2.4 and 3.1–3.3 as superseded by this change, keep D2 lifecycle threading and sections 1 and 4–8, and record the dependency in its design.
- [x] 1.5 Amend `add-authorization-views`: key runtime-owned plan and schema registries by the certified schema generation, remove `:schema-identity` from basis identity, keep Datalevin's explicit persisted lifecycle, and name the request-context constructor of this change as the shared seam.

## 2. Certified Schema Generation and Derived-State Keying

- [x] 2.1 Add the `:schema-generation` adapter operation to the backend SPI with guard validation: at most one index probe, memoized per selected adapter, independent of the proof frame.
- [x] 2.2 Implement it on Datomic (`:eacl/schema-version`), Datahike and DataScript CLJ/CLJS (`:eacl/schema-generation` guarded by the write fence), and Datalevin (same attributes through the snapshot's explicit reader).
- [x] 2.3 Make engine schema-version resolution read the certified generation directly; keep the proof-frame schema stamp for relationship proofs and add the integrity check that both agree when both exist.
- [x] 2.4 Key sealed plans, validation catalogs, permission paths, relationship-dependency closures, routing analysis, and direct-grant relations by `[engine-abi backend source-scope lifecycle generation]` in the derived cache; delete the basis-keyed process FIFO and its request-local bypass; make the request-local cache memoize every artifact including sealed plans.
- [x] 2.5 Remove Datalevin's physical-schema fingerprint, drop `:schema-identity` from semantic identity, cache identities, tokens, and cursors, and add the maintained-fork revision-only metadata read used by acquisition.
- [x] 2.6 Tests on all four backends: same plan instance across two bases of one generation; new plan after a managed schema write; stale-plan mutation killed; unstamped value seals once per request; zero definition reads and seals on a second identical request; the Datalevin one-hundred-check fixture seals once.
- [x] 2.7 Update `docs/cache.md`, the backend capability tables, the adapter-boundary and provider-migration docs, and the audit report §4.1 status.

## 3. Request Execution Context

- [x] 3.1 Implement `eacl.request.context/make-context` and the private context it constructs (runtime, adapter, ownership, basis identity, generation, contract, lazy proof frame, derived cache or request-local floor, memos, counters, publication buffer) with owner-thread, post-close, idempotent-cleanup, and exactly-once release guards.
- [x] 3.2 Rewrite `check-permission`, `read-relationships`, `lookup-resources`, `lookup-subjects`, `count-resources`, `count-subjects`, and `expand-permission-tree` to accept the context; reduce public entry points to validate, select, construct, execute, release.
- [x] 3.3 Make `with-snapshot` construct one context and hand nested reads the same context; prove unchanged public values, typed errors, cache provenance, deadlines, and limits across the CI battery and the CLJS build.
- [x] 3.4 Add CLJ and CLJS lifecycle tests for success, validation failure, deadline, cancellation, backend failure, rendering failure, publication failure, wrong thread, and double close, each asserting one acquisition and one release.

## 4. Scalar Fixed Cost

- [x] 4.1 Profile allocation and time for one cache-hit check, one cache-bypass check, one relationship page, and one acquisition on the retained fixture; attribute each to contract normalization, selection, identity conversion, proof frame, cache key and lookup, evaluation, rendering, and cursor minting; record the profile as the ratchet origin.
- [x] 4.2 Make Datalevin acquisition read revision metadata only and gate acquisition allocation at one quarter of the pre-change value.
- [x] 4.3 Fix the completed-answer hit path until a hit costs less than the memoized cache-bypass evaluation of the same direct-relation demand, without a second evaluator or cache-specific control flow.
- [x] 4.4 Reduce relationship-page cursor minting and result rendering allocation; ratchet per-call ceilings for contract normalization, identity conversion, key construction, rendering, and minting, each paired with a deterministic counter.
- [x] 4.5 Re-run the scalar series and record before/after in the baseline file.

## 5. Confidential Portable Cursors

- [x] 5.1 Replace the compact authenticated-plaintext envelope with an AEAD envelope using the existing key material, kid, domain separation, and size bounds; delete the old format.
- [x] 5.2 Add progress-anchor support to the relationship keyset and stable-discovery cursor kinds so that a cursor anchors at the last examined candidate.
- [x] 5.3 Tests: tamper rejection before parse, no plaintext recovery of scope, position, or proof, size-bound rejection rather than truncation, codec work counters, CLJ/CLJS round trip, key rotation; update the `cursor-dependency-validity` documentation and the key-management warning text.

## 6. Batched Point Checks

- [x] 6.1 Add `check-permissions` to the public API and reader dispatch for clients and composed snapshot views with the closed request envelope (`:checks` plus request-wide consistency, timeout, cancellation, cache, evaluation, and aggregate limits).
- [x] 6.2 Validate the complete batch shape, every demand, unknown keys, per-demand controls, and maximum size before selection or cache access with typed errors.
- [x] 6.3 Implement the ordered loop with lazy root preparation from the derived cache or request-local floor, the exact duplicate-demand memo, per-demand scalar limits, non-resetting aggregate counters, whole-batch failure with `:demand-index`, and deadline-bounded publication of independently valid scalar artifacts.
- [x] 6.4 Fixed tests: empty input, grants and denials, unknown objects, duplicates, mixed roots and types, recursive cycles, cache hit/miss/bypass per position, source advance, limit refinement, and every deterministic failure boundary with its index.
- [x] 6.5 Seeded property tests against the `mapv` scalar oracle on one snapshot, including the refinement rule, on all four backends and CLJS; retain minimized failing fixtures.

## 7. Scan Route: `read-relationships` with `:authorization`

- [x] 7.1 Extend relationship filter validation and normalized query identity with the closed `:authorization {:subject :permission :on}` clause and the window budget; validate endpoint compatibility on the selected snapshot.
- [x] 7.2 Implement the windowed scan: physical chunks bounded by the remaining window, context-bound point kernel per candidate, stop at exhaustion, sentinel, or budget; short pages with `:has-next-page?`/`:bounded?` per spec; progress anchor at the last examined candidate; backward symmetry where declared.
- [x] 7.3 Build the cursor proof once per complete key from relationship, permission-root, identity-boundary, and basis dependencies; reject cursors across routes, clauses, subjects, permissions, endpoints, directions, limits, or filters before traversal.
- [x] 7.4 Fixed tests for dense, sparse, all-rejected, budget-before-N, zero-row windows, exhaustion, backward pages, recursive permissions, duplicate proof paths, unknown anchors, zero/one/max page sizes, and envelope limits; mutation tests for skipped or duplicated candidates at window boundaries, physical-rather-than-authorized lookahead, leaked rejected metadata, cursor restart, budget-as-error, and budget-as-denial.

## 8. Enumerate Route: `lookup-*` with a Relationship Filter

- [x] 8.1 Extend lookup validation and normalized query identity with `:resource/relationship` and `:subject/relationship`; validate relation and anchor-type compatibility on the selected snapshot.
- [x] 8.2 Implement the probe filter inside the stable-discovery page loop: one certified direct-match probe per discovered candidate, exact lookahead, window budget, short pages, progress anchor at the last examined candidate, no permission re-evaluation, no fetch beyond the sentinel.
- [x] 8.3 Reuse the direct-match certification fixture to gate probe-equals-scan membership on every backend, including Datalevin.
- [x] 8.4 Fixed and differential tests mirroring 7.4 for the enumerate route, plus a set-equality test between the two routes for every fixture subject.

## 9. Formal Models and Mutation Controls

- [x] 9.1 Add the `FilteredPagination` model (stable stream, predicate, window budget, arbitrary cut points) proving that concatenation equals the filtered stream, that `:has-next-page?` is exact when not bounded, and that nothing is emitted across a deadline cut; map its assumptions to both routes.
- [x] 9.2 Add deterministic fake-clock and cancellation traces proving that batch and window execution stop before the same next semantic command in CLJ and CLJS.
- [x] 9.3 Add and kill named mutations for snapshot mixing, reorder or deduplication, cross-demand contamination, deadline renewal, counter reset, failure-as-denial, cursor proof omission, window-boundary skip or duplicate, per-candidate sealing, enumerate-route permission re-evaluation, and release imbalance.
- [x] 9.4 Regenerate formal manifests, assurance matrices, the production decision inventory, conversion-boundary metadata, and the public-source closure; reject stale generated artifacts in CI.

## 10. Cross-Backend Conformance

- [x] 10.1 Add the shared aggregate conformance harness for clients, read-only clients, composed snapshot views, and direct snapshots where each capability exists, covering batch, scan route, and enumerate route against the scalar and filter-then-window oracles.
- [x] 10.2 Run it on Datomic, Datahike, DataScript CLJ, DataScript CLJS, and Datalevin without backend-private expected results; gate the amplification counters from the conformance spec, including the second-identical-request and one-hundred-check Datalevin fixtures.
- [x] 10.3 Add Datalevin owner-thread, wrong-thread, close-on-failure, explicit-lifecycle, no-ordered-generation, and identical-basis-only cache fixtures for both aggregate operations, and concurrent source-advance tests proving every result stays on the selected snapshot.
- [x] 10.4 Update isolated module dependency bases, test runners, build metadata, and CI matrices so that every published backend compiles, tests, and builds the new operations independently; record the `eacl-spicedb` recut obligation for the new reader operations.

## 11. Performance and Resource Gates

- [x] 11.1 Wire the counter ledger into the deterministic gates: one acquisition and release, zero nested public entries, seals and definition reads bounded by distinct roots per generation, candidates examined within window budgets, probes equal to candidates on the enumerate route, balanced readers after every fixture.
- [x] 11.2 Run the paired series (scalar loop vs scan route dense and sparse, scalar loop vs enumerate route all-rejected, cache hit vs bypass, acquisition before vs after) and enforce the attribution thresholds from the spec.
- [x] 11.3 Run the release comparison against the pre-change baseline on a matching host class and enforce the 70 % dense and 90 % all-rejected ratios plus the ratcheted absolute ceilings; report HTTP series with the isolated framework share.
- [x] 11.4 Add forced-GC retained-heap, RSS/native observation, reader-pressure, repeated-failure, and long-running bounded-memory fixtures requiring zero active owned readers after each request scope.

## 12. Demo, Documentation, and Release Qualification

- [x] 12.1 Migrate the Datalevin demo's permission-filtered relationship endpoint to the scan route and add an enumerate-route endpoint for "what can this subject see among these relationships"; delete the per-row scalar loop from the server and keep it only in the benchmark oracle.
- [x] 12.2 Verify the demo's visible rows, short-page handling, cursors, cache eviction, errors, and concurrent-mutation behavior in its browser tests.
- [x] 12.3 Document both aggregate operations, route-selection guidance with the cost table, window budgets and `:bounded?`, cursor confidentiality and scope, cache provenance, the certified schema generation, and the absence of a universal sub-millisecond SLA.
- [x] 12.4 Update backend capability tables and the backend-author guide: the `:schema-generation` obligation, direct-match certification reuse, Datalevin identical-basis caching, thread affinity, explicit lifecycle, and the absence of an ordered-generation claim.
- [x] 12.5 Run the complete CLJ, CLJS, formal, mutation, isolated-module, build/JAR/POM, clean-consumer, source-closure, benchmark, allocation, retained-resource, and demo browser matrix and retain machine-readable evidence.
- [x] 12.6 Run `openspec validate eliminate-authorization-request-amplification --strict`, resolve every warning, and review proposal, specs, design, tasks, code, tests, and evidence for traceable completion before archive.

## 13. Interactive Lookup Regression

- [x] 13.1 Reproduce the Datalevin demo's 48-server `lookup-resources` page and attribute cache-disabled, cold-miss, and warm-hit latency between HTTP, stable traversal, schema/plan derivation, rendering, and cursor encoding.
- [x] 13.2 Remove redundant canonicalization work, reuse initialized cryptographic context, and use the JCA bulk CTR primitive on the JVM while preserving the portable cursor bytes, authenticated-encryption contract, CLJ/CLJS parity, size bounds, and deterministic codec counters.
- [x] 13.3 Separate completed-answer eviction from source-lifecycle rotation so an operational cache miss retains the certified schema-generation registry and sealed plans, while restore and unsupported-mutation recovery remain fail-closed.
- [x] 13.4 Re-run the focused core and loopback HTTP series, add regression coverage for the optimized primitives, and retain an honest before/after report for the interactive lookup path.
- [x] 13.5 Measure persistent cache-population cost while completed-cache reads are disabled and cursor encryption separately; avoid a request control when publication is immaterial, optimize the JVM cursor byte path at the measured bottleneck, and retain CLJ/CLJS parity plus before/after evidence.
