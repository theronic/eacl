## 1. Evidence and contract correction

- [x] 1.1 Reproduce deployed Datomic page/count hits without contention and record cardinality, cache counters, and service percentiles showing that 64-item rendering—not answer lookup—is the dominant delta
- [x] 1.2 Benchmark raw store lookup, subproblem lookup, exact completed resolve, public page rendering, cursor construction, and full public calls with representative EACL keys and verify stage attribution identifies the millisecond work
- [x] 1.3 Benchmark Caffeine against the immutable `core.cache` atom/CAS adapter under uncontended and eight-thread loads and verify both raw latency and throughput are reported
- [x] 1.4 Reconcile the active cache artifacts with runtime-specific frequency/recency policy and exact rendered-page reuse so the product requirements describe the implemented behavior

## 2. Concurrent JVM cache adapter

- [x] 2.1 Replace JVM `core.cache` storage with a private Caffeine 3.2.4 manual-cache adapter while retaining `cljs-cache` for CLJS, and verify dependency resolution and both runtimes compile
- [x] 2.2 Preserve explicit absence, `nil`/`false`, held-value eviction safety, independent miss publication, conditional touch/replacement, lifecycle clear, and snapshot enumeration through focused adapter tests
- [x] 2.3 Replace strict victim-order assertions with settled hot-key-survives-cold-churn and concurrent progress assertions, move JVM hit telemetry off global immutable atoms, then verify the JVM and CLJS cache suites pass
- [x] 2.4 Update executable JVM support documentation to Java 17 without changing the generated-kernel-only bytecode target, and verify no complete EACL JVM module still claims Java 8 runtime compatibility

## 3. Exact rendered page fast path

- [x] 3.1 Define one versioned exact transport-page key/value contract in the existing cache lifecycle and private storage adapter, and verify separation covers exact basis, complete raw request/boundary token, full authenticated consistency descriptor including exact/floor token, cursor-key policy, operation, demand/limits, engine/order ABI, and adapter identity
- [x] 3.2 Retain only an operation-typed validated complete public page after successful token authentication/rendering, accepting only EACL `SpiceObject`/`Relationship` wrappers; admit bounded canonical scalar/vector cursor object IDs while rejecting map/set and custom-record IDs; copy ordinary query containers; exclude the tier from portable snapshots; and disable it when current policy or an authenticated input cursor carries expiry
- [x] 3.3 Look up complete raw lookup-resource, lookup-subject, and relationship-read requests before cursor decode, object-identity/anchor internalization, and proof/schema forcing; use canonical public exact keys before internalization for point, count, and permission-tree operations under deterministic immutable/injective identity; on miss authenticate/internalize normally, retain the managed semantic path, avoid duplicate internal exact page publication, and publish transport only after validation
- [x] 3.4 Make request proof state lazy until semantic evaluation, managed reuse, recovery, or cursor-context miss requires it, and verify exact count/page hits do not force schema or proof providers
- [x] 3.5 Add differential tests for first/continued/forward/backward pages, mutable external-identity contracts, disabled/read-only population, 1,000/1,001-item admission, eviction, lifecycle rotation, and snapshot restore
- [x] 3.6 Add lookup and relationship-read cursor tests proving first, continued, forward, and backward transport hits perform zero cursor-decode/backend identity/proof/schema calls; exact/floor consistency descriptors, tokens, operations, and cursor-key policies cannot alias; local/cross-policy TTL and oversized malformed inputs bypass transport lookup/publication; metadata, records, list/vector, subvec/vector, integer-class, all map/set IDs, signed-zero, deeply nested, wide, and long identity inputs fail closed; copied query containers retain no caller comparator; and returned cursors resume the same cache-disabled pages

## 4. Fixed Datomic demo boundary

- [x] 4.1 Retain and borrow the fixed initialization EACL Snapshot for ordinary demo requests, keep historical selection request-scoped, and release each owned Snapshot and the connection exactly once in tests
- [x] 4.2 Add sequential and concurrent boundary tests proving normal fixed requests do not issue/select/release exact snapshots per call, each retained-Snapshot read preserves and authenticates its own exact/floor consistency refinement, and deadline, cancellation, admission, and historical behavior remain unchanged

## 5. Performance and product verification

- [x] 5.1 Add a warmed uncontended JVM performance gate for exact point, both counts, permission tree, and 64-item first/continued/reverse lookup and relationship-read Core hits below 1 ms with fully realized results, allocation, warmup, samples, and stage boundaries disclosed; reject the pre-token prototype after its 331/429 KB hit allocation caused a 6.68 ms GC tail
- [x] 5.2 Run focused cache, orchestration, pagination, cursor, snapshot, Datomic, Datahike, DataScript JVM/CLJS, and concurrency suites after the final adversarial fixes; record fresh JVM/CLJS counts, reflection results, and JAR build evidence
- [x] 5.3 Compare tracked source/dependency complexity against the parent branch and remove superseded cache code, tests, docs, and requirements rather than leaving dual paths
- [ ] 5.4 Commit and push the EACL change onto PR #162, update every canonical demo to that EACL SHA, run demo tests/builds, and deploy all supported targets
- [ ] 5.5 Re-run identical live hit/bypass probes and verify uncontended Core hits are below 1 ms, Datomic fixed-boundary service hits no longer pay snapshot selection or per-item rendering, results/checksums match, and any remaining platform overhead is explicitly outside Core
