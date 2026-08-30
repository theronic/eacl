## Why

EACL's shared caches currently duplicate eviction, recency, generation, weight,
snapshot, and publication machinery across several stores even though semantic
eligibility is ordinary key equality over immutable completed values. The
custom machinery makes cache hits coordinate several homegrown shared states,
multiplies nominal budgets per retained generation, overstates logical
estimates as bytes, and forces policy details into the formal model; the
maintained JVM and CLJS cache libraries now provide the bounded retention
policy EACL actually needs.

## What Changes

- Add one private cross-runtime standard-cache boundary backed by unwrapped
  `org.clojure/core.cache` LRU on Clojure and
  `com.github.theronic/cljs-cache` LRU on ClojureScript.
- Flatten exact and managed generation nesting into versioned composite keys.
  Exact answer and denotation keys contain the complete selected immutable
  basis identity; managed completed-answer keys contain source lifecycle,
  schema generation, complete dependency identity, and dependency proof. Cache
  implementations treat both as opaque keys.
- Replace homegrown weighted LRU, repeat-admission windows, access sidecars, stamped
  queues, tombstones, compaction, and policy-specific metrics with bounded
  standard LRU entry counts. Completed pages above 1,000 result items remain
  computable but are ineligible for shared retention; an eventual public
  1,000-item page maximum is a separate API change.
- Preserve independent request-owned miss computation. Semantic validation and
  computation occur outside cache mutation; a hit may atomically install the
  library's recency update, and publication atomically inserts only an already
  completed value. Atom retries may repeat only pure cache-state transforms,
  never application callbacks. Cache loaders and single-flight APIs remain
  prohibited.
- Replace cache expiry and restore by atomic lifecycle replacement. In-flight
  work can publish only into the lifecycle it captured, so late publications
  become unreachable without custom lifecycle tokens inside cache state.
- Delete the redundant externalized `PageNavigationCache`; completed internal
  pages remain cacheable and cursor externalization occurs for the current
  request. The first unseen opposite-direction route may recompute once.
- Migrate shared continuation, cursor-codec/context, derived-schema, and
  bounded stable-page checkpoint retention to the standard boundary. Keep
  checkpoint admission-count and latest-progress rules outside storage.
  Delete the unreachable Datomic provider `LocalStore`, dead Datomic revision
  checkpoints, unused derived fields, and opt-in relationship-observation
  cache after source-closure confirmation. Evaluator work queues and plain
  request-local memoization remain out of scope.
- Simplify the formal cache model to an arbitrary bounded partial map from a
  validated key to a valid completed immutable value. Retention policy is a
  tested library boundary, not authorization semantics.
- Supersede `simplify-idempotent-page-cache` without syncing its three-store
  canonical-page/route/boundary design.
- **BREAKING** Remove weighted-cache and repeat-admission configuration,
  generation-specific recency statistics, and compatibility-only cache policy
  fields; `:max-entries` becomes the actual standard LRU capacity control.
- **BREAKING** Bump the portable cache snapshot and key ABI. Prior snapshots
  fail with the existing typed incompatibility outcome rather than being
  silently trusted or carrying the deleted policy model forward.

## Capabilities

### New Capabilities

- `standard-cache-storage`: Defines the cross-runtime LRU boundary,
  opaque composite-key contract, entry-count capacity, lifecycle replacement,
  and pure local-atom hit/publication semantics.

### Modified Capabilities

- `demand-bounded-evaluation`: Scopes `:cache?` bypass to authorization
  answer, denotation, continuation, and managed-proof work while leaving
  request-independent derived-schema and cursor-construction LRUs under their
  own closed validity contracts.
- `answer-cache-bounding`: Replaces approximate-byte homegrown LRU and
  repeat-admission requirements with standard LRU entry capacity and
  bounded-page cache eligibility.
- `authorization-request-efficiency`: Removes authorization decisions based on
  recency, generated finite cache-stage specialization, and separately retained
  externalized pages from the request path. Successful retrieval still applies
  the standard library's local LRU touch.
- `enumeration-continuation-reuse`: Retains authenticated bounded continuation
  reuse while making the standard keyed store and replay fallback independent
  of tombstone/family eviction machinery.
- `formal-implementation-conformance`: Replaces policy-specific generated
  cache decisions with traceability for composite-key construction,
  completed-value validation, lifecycle detachment, and library conformance.
- `formally-verified-authorization-engine`: Abstracts cache storage as a
  bounded partial map whose arbitrary eviction can only turn a hit into a miss.
- `implementation-simplicity-and-performance`: Requires removal of superseded
  cache state machines and qualifies the standard LRU/page-cache-deletion path
  against real EACL workloads.
- `recursion-performance-gates`: Replaces custom LRU queue/compaction operation
  counts with portable standard-LRU traces and boundary regression gates.
- `nonblocking-cache-coordination`: Replaces native-weight and multi-attempt
  publication machinery with validation outside mutation and atomic insertion
  of an already completed value whose retry function contains no request work.
- `portable-authorization-cache`: Selects the internal host implementations and
  defines the flattened serialization/restore boundary without allowing
  provider-owned stores to control authorization state.
- `verified-subproblem-cache`: Replaces nested weighted generation stores with
  an exact-denotation LRU and an exact/managed completed-answer LRU, while
  preserving complete immutable values and cache-free equivalence and deleting
  measured-negative shared physical projections.
- `single-flight-coordination`: Makes retained entry count and one publication
  attempt the only cache coordination bounds while preserving request-owned
  miss computation.
- `forward-history-cache-coherence`: Recasts exact-generation isolation as flat
  exact composite-key separation and removes page-navigation state from
  lifecycle rotation.
- `dependency-validated-authorization-cache`: Removes externally writable
  provider/authenticator and hit-mutated validation metadata requirements now
  that the cache is private, while preserving full keys and selected-snapshot
  recomputation.

## Impact

- Core cache, continuation, cursor, relay, derived-schema, orchestration,
  configuration, snapshot, statistics, documentation, benchmark, and test
  namespaces are affected.
- Dafny/TLA/generated cache decisions, refinement mappings, mutation controls,
  source-closure inventories, and formal evidence are simplified or removed.
- JVM EACL uses `org.clojure/core.cache`; CLJS/DataScript demos use the tested
  `theronic/cljs-cache` Git SHA directly. EACL adds no recency sidecar of its
  own. Datahike dependency surfaces exclude upstream
  `com.github.pkpkpk/cljs-cache` so two implementations of the same
  `cljs.cache` namespace cannot coexist.
- Cache configuration, statistics, and exported cache snapshots change ABI.
  Authorization results, ordering, consistency, cursor validity, deadlines,
  and cache-disabled behavior remain unchanged.
