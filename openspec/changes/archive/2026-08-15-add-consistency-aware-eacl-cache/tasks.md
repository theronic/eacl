## 1. Establish the cache correctness model

- [x] 1.1 Record cache-disabled results for `can?`, both lookup directions, both counts, and paged
  recursive traversal as the reference behavior used by new tests
- [x] 1.2 Define versioned cache key, entry-kind, database-identity, coordinator-incarnation,
  schema-generation, dependency-revision, and exact-snapshot value records
- [x] 1.3 Add strict wrapper and value-shape validation that converts mismatches into misses
- [x] 1.4 Add typed snapshot-unavailable and cursor-expired errors that cannot be mistaken for
  authorization denials
- [x] 1.5 Bump the experimental cache format so existing cache values cannot be reused

## 2. Make cache state explicit and bounded

- [x] 2.1 Extend `CacheStore` with provider capabilities and EACL-namespace-scoped clearing while
  keeping stores and clocks explicit arguments
- [x] 2.2 Implement hard limits for total weight, per-entry weight, entry count, and TTL in the
  built-in memory store
- [x] 2.3 Add configurable entry-kind shares and high-cardinality `can?` admission so permission
  traffic cannot monopolize the store
- [x] 2.4 Add per-kind and total hit, miss, admission, rejection, eviction, expiry, provider-error,
  entry-count, and weight metrics
- [x] 2.5 Verify two independent cache contexts in one process share no hidden global state
- [x] 2.6 Verify expiry is enforced during lookup even when physical cleanup is delayed

## 3. Fix schema lifecycle and dependency compilation

- [x] 3.1 Read `:eacl/schema-version` once during client construction and remove per-DB schema
  marker or definition-index validation from hot reads
- [x] 3.2 Treat a missing schema marker as an unstamped current installation and disable result
  caching until `write-schema!` establishes a generation
- [x] 3.3 Rotate the client's immutable schema cache and generation only after a successful
  `write-schema!`
- [x] 3.4 Compile the complete transitive relation-definition dependency set for positive and
  negative authorization answers
- [x] 3.5 Remove traversal-time recursive versus non-recursive dependency discovery from cache
  selection
- [x] 3.6 Test that ordinary application transactions and relationship-only DB advances perform no
  schema marker lookup or definition-index scan
- [x] 3.7 Test schema rotation, failed schema writes, unstamped clients, and explicit client refresh

## 4. Publish relationship revisions through the coordinator

- [x] 4.1 Give every coordinator a collision-resistant incarnation and explicit uncertainty
  generation
- [x] 4.2 Replace local incrementing dependency clocks with each changed relation definition's
  committed `d/basis-t` from `db-after`
- [x] 4.3 Compress an operation's complete dependency revisions to a scalar maximum plus
  incarnation and uncertainty, and unit-test the monotonicity proof
- [x] 4.4 Make coherent readers capture the DB, schema generation, and dependency scope inside the
  short read barrier and release it before cache I/O or traversal
- [x] 4.5 Hold the mutation barrier across Datomic transaction completion and revision publication
  for every relationship write and delete helper
- [x] 4.6 Detect relationship no-ops so they do not advance dependency revisions
- [x] 4.7 Rotate the uncertainty generation when a mutation may have committed but its basis or
  changed definitions cannot be established
- [x] 4.8 Ensure `delete-relationships!` invalidates affected dependencies before a consumer
  retracts the now-unrelated object entity
- [x] 4.9 Test relevant writes, unrelated relation writes, unrelated application transactions,
  ambiguous outcomes, restarts, and read/write barrier races

## 5. Implement Zed tokens and consistency selection

- [x] 5.1 Implement a versioned `:zed/token` whose semantic revision is a Long Datomic basis `t`
  and whose validated envelope is bound to one database identity
- [x] 5.2 Return exact committed write revisions and selected read revisions where the public
  response contract exposes a token
- [x] 5.3 Resolve `fully-consistent` to the current locally observed DB plus coherent dependency
  state without calling zero-argument `d/sync`
- [x] 5.4 Resolve `minimize-latency` to the newest qualifying coherent cached snapshot, falling back
  to the current local DB
- [x] 5.5 Resolve `at-least-as-fresh` to a cached or local revision at least `T`, using targeted
  synchronization for `T` only when the local Peer is behind
- [x] 5.6 Resolve `at-exact-snapshot` only from an entry explicitly recorded for `T`, returning
  snapshot-unavailable on a miss without calling `d/as-of`
- [x] 5.7 Reject malformed, unsupported-version, cross-database, and consistency/cursor-conflicting
  tokens
- [x] 5.8 Add an optional bounded, lazily sampled ring of monotonic capture times and actually
  observed basis revisions
- [x] 5.9 Add a helper that constructs an `at-least-as-fresh` token for N seconds ago without
  performing arithmetic on `t` or forcing synchronization
- [x] 5.10 Test every consistency mode with cache hits, misses, Peer-ahead and Peer-behind states,
  timeouts, eviction, and provider failure

## 6. Canonicalize internal queries at the API boundary

- [x] 6.1 Capture the selected DB and resolve subject and resource external IDs before constructing
  canonical query keys
- [x] 6.2 Return ordinary false or empty results for unresolved non-exact inputs without storing
  their external IDs or negative boundary results; fail exact unresolved boundaries closed
- [x] 6.3 Include every answer-affecting query option and pagination position in canonical internal
  cache keys
- [x] 6.4 Store only internal entity IDs in lookup pages and permission keys, and coerce external
  response values after cache evaluation
- [x] 6.5 Return snapshot-unavailable when an internal entity in an exact cached lookup cannot be
  resolved at the response boundary
- [x] 6.6 Test stable object identity, unresolved endpoints, recreated external IDs, and key
  separation across databases and operations

## 7. Cache live authorization results in one store

- [x] 7.1 Route recursive and non-recursive lookups through one typed completed-page cache before
  evaluator selection
- [x] 7.2 Cache `can?` `true` and `false` results only after both endpoint entities resolve
- [x] 7.3 Cache `count-resources` and `count-subjects` responses under the same consistency and
  dependency proof used by lookups
- [x] 7.4 Preserve stable lookup order and existing public response shapes on cache hits
- [x] 7.5 Treat cache read, write, admission, and serialization failures as misses for recomputable
  live operations
- [x] 7.6 Treat the same failures as snapshot-unavailable for cache-resident exact operations
- [x] 7.7 Verify caching disabled, cold misses, warm hits, TTL expiry, relevant invalidation, and
  unrelated-write reuse for every result kind

## 8. Replace recursive prefix replay with continuations

- [x] 8.1 Refactor recursive lookup into an explicit resumable state machine containing frontier,
  visited state, stable ordering state, canonical query metadata, and bounded index-scan chunks
- [x] 8.2 Store a bounded continuation after each emitted page and resume it directly for the next
  cursor
- [x] 8.3 Pin cursors to the selected schema generation, dependency scope, and exact basis revision
- [x] 8.4 Replay a missing continuation only while its complete relationship proof matches; after
  a relevant change, return an exact retained page or snapshot-unavailable
- [x] 8.5 Reject opaque continuations from providers that declare portable-values-only capability
- [x] 8.6 Test cycles, diamonds, deep recursion, duplicate suppression, stable ordering, empty
  pages, eviction, expiry, alternate coordinator incarnation, and incompatible freshness options
- [x] 8.7 Instrument sequential pagination and prove continuation hits eliminate repeated-prefix
  `O(N²/page-size)` work

## 9. Make count misses safe for large databases

- [x] 9.1 Replace head-retaining lazy count loops with an eager reducing loop or bounded cursor in
  both count directions
- [x] 9.2 Ensure an uncached count never materializes all matching internal entity IDs
- [x] 9.3 Add retained-memory tests for large counts with caching disabled, admission rejected, and
  provider failures
- [x] 9.4 Verify the cached count entry contains only the bounded response and proof metadata

## 10. Support optional portable cache providers

- [x] 10.1 Define the portable value and serialization-version contract for completed pages,
  counts, Booleans, and exact metadata; keep monotonic-time checkpoints client-local
- [x] 10.2 Reject lazy sequences, functions, Datomic DB values, traversal engine objects, and other
  process-local values from portable providers
- [x] 10.3 Provide a reusable provider contract test suite covering TTL, namespace isolation,
  capabilities, wrapper validation, failures, and concurrency
- [x] 10.4 Document how consumers can implement RocksDB, Apache Kvrocks, Redis, or another backend
  without adding those dependencies to EACL core
- [x] 10.5 Verify a shared store with separate coordinator incarnations produces misses instead of
  unsafe cross-client live reuse
- [x] 10.6 Verify provider cleanup removes only the configured EACL namespace and never requires a
  whole backing-database flush

## 11. Verify correctness with model and concurrency tests

- [x] 11.1 Add randomized graph tests comparing cached and cache-disabled answers at the same
  selected snapshot
- [x] 11.2 Add randomized mutation sequences covering positive and negative `can?`, lookups,
  counts, schema rotation, relationship no-ops, and relevant dependency changes
- [x] 11.3 Add deterministic concurrency tests proving a read sees either the complete pre-write
  state and revision or the complete post-write state and revision
- [x] 11.4 Add provider-fault tests for corrupt values, partial availability, timeouts, admission
  rejection, and exact-snapshot loss
- [x] 11.5 Add tests proving direct EACL-data writes are not polled and document them as outside the
  coherence contract
- [x] 11.6 Run the complete regular EACL test suite through nREPL and fix every failure

## 12. Benchmark, document, and prepare release

- [x] 12.1 Capture reproducible v7.3 baselines for cold and warm `can?`, recursive and
  non-recursive lookups, both counts, and paged recursive enumeration
- [x] 12.2 Benchmark cache-disabled overhead, cold misses, warm hits, relevant invalidation,
  unrelated application transactions, and unrelated relation writes
- [x] 12.3 Benchmark retained memory and admission behavior under large counts, broad recursion,
  and high-cardinality permission traffic
- [x] 12.4 Compare v7.3 and candidate results with iteration counts, dataset sizes, percentiles,
  allocation or retained-memory measurements, and environment details
- [x] 12.5 Fix any statistically meaningful fast acyclic path regression and rerun the affected
  benchmarks
- [x] 12.6 Document consistency descriptors, token construction, exact-snapshot expiry, schema
  lifecycle, explicit cache contexts, supported mutation boundaries, provider capabilities, and
  cache-disabled operation
- [x] 12.7 Document reverse-ghost detection and the required order of
  `delete-relationships!` followed by entity retraction
- [x] 12.8 Record release notes as v8.0 behavior while keeping the change eligible for a v7.3 patch
  release if compatibility and benchmark evidence support it

## 13. Adversarially verify performance and release value

- [x] 13.1 Add recursive scaling benchmarks across multiple graph sizes and page sizes, reporting
  deterministic traversal work as well as warmed wall-clock distributions
- [x] 13.2 Compare full-walk v7.3 prefix replay, cache-disabled traversal, continuation hits, and
  repeated completed-result hits so the benchmark separates algorithmic and memoization gains
- [x] 13.3 Profile recursive page setup, continuation lookup/admission, traversal, boundary
  coercion, token handling, and Datomic index access to identify the remaining bottlenecks
- [x] 13.4 Audit continuation correctness, memory growth, eviction, cursor chaining, alternate
  Peers, malformed providers, and mutation races with adversarial tests
- [x] 13.5 Audit live-result keying, dependency invalidation, consistency selection, negative
  answers, checkpointing, and provider capability handling for stale-answer loopholes
- [x] 13.6 Remove or bypass cache machinery whose measured cost or complexity is not justified,
  while preserving cache-disabled correctness and exact-snapshot fail-closed behavior
- [x] 13.7 State the cache value proposition per operation using reproducible measurements and
  block release if recursive continuations do not demonstrate linear scaling
- [x] 13.8 Run fresh-JVM regular and heavy suites through nREPL, update the report and OpenSpec
  artifacts, and republish the reviewed implementation
