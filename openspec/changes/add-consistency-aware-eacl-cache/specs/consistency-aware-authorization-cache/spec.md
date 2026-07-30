## ADDED Requirements

### Requirement: Caching preserves uncached authorization semantics

EACL SHALL return the same authorization answer as an uncached evaluation at the snapshot selected
by the requested consistency mode. Cache enablement, eviction, admission rejection, expiry, or a
recoverable cache-provider failure MUST NOT change a Boolean, lookup result, count, or result order.
When a requested snapshot cannot be reproduced without changing its semantics, EACL MUST return a
typed snapshot-unavailable error instead of evaluating against a different snapshot.

#### Scenario: Cache is disabled

- **WHEN** a client is constructed with caching disabled
- **THEN** `can?`, lookup, and count operations use the ordinary uncached evaluators
- **AND** their answers have the same correctness and consistency guarantees as a cache miss

#### Scenario: Best-effort provider fails

- **WHEN** a cache provider throws while serving a live operation whose consistency mode permits
  recomputation
- **THEN** EACL treats the failure as a cache miss and recomputes the operation
- **AND** the provider failure does not produce or alter an authorization answer

#### Scenario: Exact snapshot is unavailable

- **WHEN** `at-exact-snapshot` names a snapshot whose required entry is missing, expired, corrupt,
  or unavailable because its provider failed
- **THEN** EACL returns a typed snapshot-unavailable error
- **AND** EACL does not fall forward to the current database or invoke Datomic time travel

### Requirement: Recursive pagination resumes stored traversal state

EACL SHALL represent a recursive pagination continuation as bounded internal traversal state that
can resume without replaying all preceding pages. On continuation-cache hits, enumerating a result
set SHALL perform work linear in the graph work and results newly traversed or emitted, rather than
restarting the traversal for every page. Core pagination MUST NOT require permanent derived tuples
in a consumer database.

#### Scenario: Sequential recursive enumeration

- **WHEN** a caller consumes every page of a recursive lookup and each continuation remains
  available
- **THEN** each page resumes the preceding frontier
- **AND** the full enumeration does not exhibit `O(N²/page-size)` prefix replay

#### Scenario: Recursive continuation is evicted

- **WHEN** a cursor's opaque recursive continuation has been evicted
- **THEN** EACL returns a typed snapshot-unavailable or cursor-expired error
- **AND** EACL neither silently restarts at a newer snapshot nor returns duplicate or skipped
  results

#### Scenario: Cache backend cannot store opaque state

- **WHEN** a configured backend declares that it cannot store process-local opaque continuations
- **THEN** EACL rejects continuation admission for that backend
- **AND** completed portable result entries may still use the backend

### Requirement: One typed cache serves authorization reads

EACL SHALL use one cache-store abstraction with typed entry namespaces for recursive
continuations, lookup pages, counts, and `can?` Boolean results. Cache selection MUST occur before
choosing recursive or non-recursive execution; recursion classification MAY select an evaluator but
MUST NOT select a separate public cache or API.

#### Scenario: Non-recursive lookup is repeated

- **WHEN** an identical non-recursive lookup is repeated under a still-valid cache scope
- **THEN** EACL may return the completed internal page from the same cache used by recursive
  lookups

#### Scenario: Known-endpoint permission check is repeated

- **WHEN** an identical `can?` request with resolved subject and resource entities is repeated under
  a still-valid cache scope
- **THEN** EACL may return either the cached `true` or cached `false` result

#### Scenario: Count is repeated

- **WHEN** an identical `count-resources` or `count-subjects` operation is repeated under a
  still-valid cache scope
- **THEN** EACL may return the cached count response without materializing the result set again

### Requirement: Cache entries use internal object identities

Authorization result entries and canonical query keys SHALL contain internal entity IDs rather than
external subject or resource IDs. EACL SHALL resolve external inputs before cache lookup and
coerce internal result IDs to external API values only at the response boundary. EACL SHALL assume
that supported EACL object identity mappings are stable for the lifetime of an entity.

#### Scenario: Input external ID does not exist

- **WHEN** a `can?`, lookup, or count input cannot be resolved to an internal entity
- **AND** the request does not require an exact historical snapshot
- **THEN** EACL returns the operation's ordinary false or empty result at the boundary
- **AND** EACL does not cache that missing external ID or boundary result

#### Scenario: Exact input boundary can no longer be resolved

- **WHEN** `at-exact-snapshot` names a cached historical query but a subject or resource external
  ID can no longer be resolved to its internal entity at the current boundary
- **THEN** EACL returns snapshot-unavailable
- **AND** EACL does not report an ordinary false or empty answer as though it came from the exact
  snapshot

#### Scenario: Exact result contains an unresolvable entity

- **WHEN** a cache-resident exact lookup contains an internal entity ID that can no longer be
  resolved at the response boundary
- **THEN** EACL returns snapshot-unavailable
- **AND** EACL does not silently omit, replace, or reinterpret the entity

### Requirement: Cache keys identify the full authorization computation

Every cached answer SHALL be wrapped with a format version, entry kind, and its complete canonical
key. The key SHALL distinguish database identity, coordinator incarnation, schema generation,
operation, internal query inputs, consistency snapshot or live dependency revision, pagination
position, and any option that can change the answer.

#### Scenario: Provider returns a mismatched value

- **WHEN** a provider returns an entry whose version, kind, embedded key, or value shape does not
  match the requested key
- **THEN** EACL treats the value as a miss
- **AND** EACL does not use the value in an authorization answer

#### Scenario: Two databases share a store

- **WHEN** clients for different databases use the same cache backend
- **THEN** their cache entries cannot collide or be reused across database identities

### Requirement: Relationship mutations publish dependency revisions

Every supported EACL relationship mutation SHALL execute through the same explicit relationship
coordinator used by cached reads. A writer SHALL hold the coordinator's mutation barrier across the
relationship transaction and publication of its committed revision. A reader SHALL briefly hold
the read barrier while capturing a database value and the matching dependency state, then release
the barrier before cache access or graph evaluation.

#### Scenario: Relevant relationship changes

- **WHEN** an EACL relationship mutation changes a relation definition on which a cached operation
  depends
- **THEN** the committed Datomic basis `t` becomes that relation definition's last-change revision
- **AND** prior live entries for the operation become unreachable before a subsequent coherent read

#### Scenario: Unrelated application transaction occurs

- **WHEN** the Datomic database advances because application data unrelated to EACL relationships
  changes
- **THEN** EACL does not invalidate authorization entries merely because the database basis changed

#### Scenario: Unrelated relationship changes

- **WHEN** a relationship mutation changes only relation definitions outside an operation's
  complete dependency set
- **THEN** the operation's live cache scope remains valid

#### Scenario: Relationship helper is a no-op

- **WHEN** a supported relationship helper commits no relationship datom change
- **THEN** it does not advance any relation definition's last-change revision

### Requirement: Dependency revision compression is safe

For each cached computation, EACL SHALL derive the complete set of relation definitions that could
affect both positive and negative answers. It MAY compress their last-change revisions to the
maximum committed `t`, provided the cache scope also contains a coordinator incarnation and an
uncertainty generation. A new coordinator incarnation MUST NOT reuse live entries created by a
previous incarnation.

#### Scenario: A non-maximum dependency changes

- **WHEN** a dependency whose previous revision was below the cached maximum changes after the
  cached evaluation
- **THEN** its new committed `t` is greater than the evaluation's observed basis
- **AND** the compressed maximum changes and invalidates the entry

#### Scenario: Relationship transaction outcome is ambiguous

- **WHEN** an EACL mutation can have committed but its changed dependencies or committed basis
  cannot be determined
- **THEN** the coordinator advances its uncertainty generation
- **AND** every prior live result in that coherence scope becomes unreachable

#### Scenario: Client or process restarts

- **WHEN** a client creates a new coordinator after a restart
- **THEN** it uses a new incarnation
- **AND** it cannot accept live entries published under an earlier coordinator's in-memory state

### Requirement: Direct relationship mutation is outside cache coherence

EACL SHALL document cache coherence as guaranteed only when every modification to EACL-owned
relationship data uses an EACL mutation API participating in the configured coordinator. Core
caching SHALL NOT scan the Datomic transaction log or every new database basis to detect unsupported
direct writes.

#### Scenario: Consumer deletes an object correctly

- **WHEN** a consumer first removes the object's relationships through EACL and subsequently
  retracts the entity
- **THEN** the relationship removal invalidates affected live cache entries
- **AND** the later entity retraction requires no relationship invalidation

#### Scenario: Consumer bypasses EACL relationship APIs

- **WHEN** a consumer directly transacts or retracts EACL relationship datoms
- **THEN** cache-coherence guarantees do not apply to that mutation
- **AND** EACL may expose diagnostics for resulting reverse ghost tuples without polling for the
  bypass

### Requirement: Schema validity is client-scoped

An EACL client SHALL read the schema-version marker at most once during construction and SHALL NOT
rescan the definition index or revalidate schema for each new database value. Each successful
`write-schema!` through that client SHALL install a new immutable schema cache and rotate the schema
generation used by every cache entry.

#### Scenario: Ordinary database basis advances

- **WHEN** an application transaction or relationship write creates a new database value without a
  schema write
- **THEN** EACL retains the client's schema generation
- **AND** it performs no schema-version lookup or definition-index scan for that basis

#### Scenario: Schema changes through EACL

- **WHEN** `write-schema!` succeeds
- **THEN** subsequent reads use the newly built schema and generation
- **AND** entries under the prior schema generation are unreachable and left for bounded eviction

#### Scenario: Database lacks a schema-version marker

- **WHEN** a client is constructed against a database with no `:eacl/schema-version`
- **THEN** EACL treats it as an unstamped current installation rather than a legacy compatibility
  mode
- **AND** result caching remains disabled until a successful `write-schema!` establishes a
  generation

#### Scenario: Schema changes through another client

- **WHEN** a different client changes schema without sharing the required client lifecycle
- **THEN** the existing client's schema is not polled for changes
- **AND** the consumer must recreate or explicitly refresh that client before making authorization
  decisions

### Requirement: Consistency modes select cache snapshots explicitly

EACL SHALL accept `fully-consistent`, `minimize-latency`, `at-least-as-fresh`, and
`at-exact-snapshot` consistency descriptors for cacheable authorization reads. Consistency
selection SHALL occur before cache lookup, and a pagination continuation SHALL remain pinned to the
snapshot selected by its first page.

#### Scenario: Fully consistent read

- **WHEN** a caller requests `fully-consistent`
- **THEN** EACL captures the current locally observed `(d/db conn)` and coherent relationship
  dependency state
- **AND** it may reuse a cache entry proven valid for that state without calling zero-argument
  `d/sync`

#### Scenario: Minimize latency read

- **WHEN** a caller requests `minimize-latency`
- **THEN** EACL may use the newest coherent cached snapshot satisfying the query
- **AND** the result is explicitly allowed to be older than the current locally observed database

#### Scenario: At least as fresh read is locally satisfiable

- **WHEN** a caller requests `at-least-as-fresh` with token `T` and a coherent cached or locally
  observed snapshot has revision at least `T`
- **THEN** EACL returns an answer at that snapshot or a newer one
- **AND** EACL does not force a Peer synchronization

#### Scenario: At least as fresh token is ahead of the Peer

- **WHEN** a caller requests `at-least-as-fresh` with token `T`, no qualifying cache entry exists,
  and the local Peer has not observed `T`
- **THEN** EACL MAY call the Datomic synchronization operation that waits specifically for `T`
- **AND** it either answers at `T` or newer or returns a timeout/freshness error, never an older
  answer

#### Scenario: Exact snapshot is requested

- **WHEN** a caller requests `at-exact-snapshot` with token `T`
- **THEN** EACL uses only a cache entry or continuation explicitly recorded for `T`
- **AND** core EACL does not call `d/as-of` to reconstruct the snapshot

#### Scenario: Caller combines a newer constraint with an older cursor

- **WHEN** a caller supplies a cursor pinned to one snapshot and a consistency constraint requiring
  a different snapshot
- **THEN** EACL rejects the incompatible combination
- **AND** the caller may begin a new enumeration at the requested freshness

### Requirement: Zed tokens use monotonic Datomic revisions

The semantic revision in an EACL `:zed/token` SHALL be a Datomic basis `t` represented as a Long for
constant-time comparison. The external token format SHALL be versioned and validated so malformed,
cross-database, or unsupported tokens are rejected rather than interpreted as revisions.

#### Scenario: Mutation returns a token

- **WHEN** a supported relationship write commits at basis `t`
- **THEN** EACL can return a `:zed/token` whose semantic revision is exactly `t`
- **AND** a later `at-least-as-fresh` request can compare the token without scanning Datomic

#### Scenario: Token belongs to another database

- **WHEN** a token's validated database binding does not match the client's database identity
- **THEN** EACL rejects the token as invalid for that client

### Requirement: Age-based freshness uses bounded observed checkpoints

When revision checkpointing is enabled, EACL SHALL provide a bounded checkpoint ring that maps
monotonic capture times to actual locally observed basis revisions. A helper constructing a token
"at least as fresh as N seconds ago" SHALL choose an observed revision at or after the requested
cutoff, or a newer observed revision when no older checkpoint exists. It MUST NOT derive a
revision by arithmetic on `t`. Checkpoint quantization MUST NOT participate in live relationship
invalidation.

#### Scenario: Suitable checkpoint exists

- **WHEN** the checkpoint ring contains a revision captured at or after `now - N seconds`
- **THEN** the helper returns the oldest qualifying observed revision
- **AND** `at-least-as-fresh` can use that token normally

#### Scenario: No historical checkpoint exists

- **WHEN** no retained checkpoint satisfies the requested age
- **THEN** the helper returns the current locally observed revision
- **AND** the result is over-fresh but never older than requested

#### Scenario: Checkpointing is disabled

- **WHEN** a client disables revision checkpoints
- **THEN** ordinary cache invalidation and every consistency mode except age-token construction
  remain available

### Requirement: Count execution is streaming and bounded

An uncached `count-resources` or `count-subjects` SHALL consume results without retaining the head of
a lazy result sequence or materializing all matching entities. Count cache entries SHALL contain
only the bounded count response and cache metadata.

#### Scenario: Large uncached count

- **WHEN** a count traverses a result set larger than the cache capacity
- **THEN** retained memory remains bounded independently of result cardinality
- **AND** EACL does not keep the traversed entity sequence reachable after counting it

#### Scenario: Count entry exceeds admission policy

- **WHEN** the count response or metadata violates the configured entry policy
- **THEN** the cache rejects the entry
- **AND** the already computed count remains the returned answer

### Requirement: Cache resources are bounded and class-aware

The built-in cache SHALL enforce maximum total weight, maximum entry weight, maximum entries, and
TTL at lookup. It SHALL support class-aware admission or capacity controls so high-cardinality
`can?` traffic cannot unconditionally evict all recursive continuations or expensive completed
results. Eviction MUST affect latency only, except for explicitly cache-resident exact snapshots
and continuations which become unavailable.

#### Scenario: Permission-check flood reaches capacity

- **WHEN** many distinct `can?` results exceed their configured admission or capacity share
- **THEN** EACL rejects or evicts entries according to the configured class-aware policy
- **AND** memory remains within the total configured bound

#### Scenario: Entry expires

- **WHEN** an entry's TTL has elapsed
- **THEN** lookup treats it as absent even if the backend has not physically reclaimed it

### Requirement: Cache storage is ephemeral and pluggable

EACL core SHALL define a cache-store protocol and a built-in bounded in-memory implementation.
Consumers MAY supply local or shared backends, including RocksDB, Apache Kvrocks, Redis, or other
stores, without EACL core depending on their client or serialization libraries. The cache design
MUST add no Datomic schema attributes, derived tuples, or other permanent consumer-database data.

#### Scenario: Consumer supplies a custom store

- **WHEN** a configured store satisfies the cache-store protocol and declared capabilities
- **THEN** EACL uses it for compatible typed entries
- **AND** authorization correctness does not depend on store locality

#### Scenario: Shared backend is used by multiple clients

- **WHEN** multiple clients share a backend but not a relationship coordinator
- **THEN** coordinator incarnation namespacing prevents them from reusing live entries as though the
  shared backend provided mutation ordering

#### Scenario: Namespace is cleared

- **WHEN** EACL requests cache cleanup
- **THEN** the provider removes only the configured EACL namespace
- **AND** EACL never requires a whole Redis or Apache Kvrocks database flush

### Requirement: Portable entries exclude process-local state

Backends declaring portable serialization SHALL store only completed internal lookup pages, count
responses, Boolean permission results, and exact-snapshot metadata composed of supported scalar or
collection values. They MUST reject process-local functions, lazy sequences, Datomic database
values, traversal engine objects, and opaque continuations.

#### Scenario: Portable completed page is stored

- **WHEN** a completed lookup page contains internal entity IDs and supported metadata only
- **THEN** a portable backend may serialize and store it

#### Scenario: Lazy or database value is offered

- **WHEN** EACL attempts to admit a lazy sequence or Datomic database value to a portable backend
- **THEN** the backend rejects the entry
- **AND** the live operation continues with its computed answer

### Requirement: Cache state is explicit

Cache stores, relationship coordinators, clocks, and checkpoint state SHALL be explicit client or
function arguments rather than hidden process-global atoms. Every reader and relationship writer
in one coherence scope MUST use the same coordinator.

#### Scenario: Two independent clients run in one process

- **WHEN** two clients use separate cache contexts
- **THEN** their stores, coordinator incarnations, mutation state, and metrics remain independent

#### Scenario: Functions are tested without a cache

- **WHEN** internal DB-oriented evaluators are invoked without cache arguments
- **THEN** they behave as pure uncached computations over the supplied database value

### Requirement: Existing public lookup APIs remain sufficient

The change SHALL preserve the existing connection-oriented public `can?`, `lookup-resources`,
`lookup-subjects`, `count-resources`, and `count-subjects` APIs and SHALL NOT add recursive,
non-recursive, or snapshot-specific lookup function names. Internal evaluators MAY continue to
accept explicit database values.

#### Scenario: Caller uses lookup-resources

- **WHEN** a caller invokes the existing `lookup-resources` API with consistency options
- **THEN** EACL applies cache and snapshot selection internally
- **AND** the caller does not need a `lookup-resources-at` variant

### Requirement: Performance and race behavior are verified

The implementation SHALL be verified against cache-disabled reference behavior, concurrent
relationship mutations, schema rotations, cache-provider faults, and the v7.3 performance
baseline. It MUST introduce no material regression on the fast acyclic path and MUST demonstrate
the removal of recursive prefix replay when continuations hit.

#### Scenario: Cached and uncached models are compared

- **WHEN** randomized relationship graphs and mutation sequences are evaluated
- **THEN** every cacheable live answer matches the cache-disabled evaluator for the same selected
  snapshot

#### Scenario: Read races a relevant write

- **WHEN** a coherent read begins concurrently with a relevant EACL relationship write
- **THEN** the read observes either the complete pre-write state and revision or the complete
  post-write state and revision
- **AND** it never pairs one state with the other's cache scope

#### Scenario: Benchmarks are compared

- **WHEN** cold and warm lookup, count, `can?`, and recursive pagination benchmarks are run against
  v7.3
- **THEN** the results report hit, miss, admission, and memory behavior
- **AND** any statistically meaningful regression is fixed or explicitly blocks release
