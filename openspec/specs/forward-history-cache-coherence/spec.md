# forward-history-cache-coherence Specification

## Purpose
Define a fast authorization-cache contract for ordinary forward database history using immutable request snapshots and complete database-visible schema and relation generations.
## Requirements
### Requirement: One immutable snapshot per request
EACL SHALL select one immutable backend snapshot before schema resolution, dependency extraction, cache lookup, authorization evaluation, result rendering, or cache publication, and SHALL perform all of those stages against that snapshot.

#### Scenario: Concurrent commit after selection
- **WHEN** a relationship transaction commits after a request has selected its immutable snapshot
- **THEN** the request completes consistently against the selected pre-commit snapshot without combining old and new state

#### Scenario: Long-running request
- **WHEN** a long-running API call retains an older immutable database value while newer transactions commit
- **THEN** the call remains correct for its selected value and any late cache publication is isolated from newer exact generations

### Requirement: Exact-current generation isolation
The exact-current completed-answer tier SHALL admit and return an entry only for the canonical immutable snapshot generation, source lifecycle, and complete semantic request for which it was computed. A newer current request MUST NOT observe an older exact entry, while an explicit exact request selecting that older snapshot MAY use the separately bounded snapshot-exact retention path if it remains retained.

#### Scenario: Any forward transaction advances the snapshot
- **WHEN** the selected current snapshot changes after any committed transaction
- **THEN** the previous exact-current generation is unreachable from requests selecting the new snapshot

#### Scenario: Late old-generation publication
- **WHEN** computation against an old snapshot finishes after a newer exact generation is installed
- **THEN** its result remains keyed to the old canonical snapshot and cannot populate, replace, or masquerade as the newer generation

#### Scenario: Same authenticated exact snapshot
- **WHEN** `:at-exact-snapshot` selects canonical snapshot `T` and a completed answer for the identical semantic request at `T` remains retained
- **THEN** EACL may return it without traversal or managed-proof reads
- **AND** rebuilds public snapshot metadata and tokens from the selected adapter at `T`

#### Scenario: Snapshot-exact retention is bounded
- **WHEN** weight, entry, or admission bounds evict an exact answer
- **THEN** a later exact request recomputes on its selected immutable snapshot
- **AND** eviction does not imply snapshot or cursor expiry

### Requirement: Exact requests never use managed cross-snapshot lifting
An `:at-exact-snapshot` request SHALL probe only completed answers bound to its identical canonical snapshot. It SHALL NOT use relation/schema proof equality to lift a managed answer computed at another revision, and SHALL NOT validate historical answers using current-only or no-history stamps.

#### Scenario: Managed answer has equal dependency proof
- **WHEN** a managed answer computed at `T2` has proof equal to the exact request's proof at `T1`
- **THEN** the request does not use that answer unless a separate snapshot-exact entry exists for `T1`

#### Scenario: Exact cache miss
- **WHEN** no retained completed answer matches the selected exact snapshot and semantic request
- **THEN** EACL evaluates against the already selected exact adapter
- **AND** may publish only the completed semantic answer at that exact key

#### Scenario: Public response metadata
- **WHEN** a snapshot-exact completed answer is reused
- **THEN** response tokens, cursor envelopes, cache basis, external identifiers, and other public metadata are rebuilt from the selected exact adapter

### Requirement: Exact cache identity is complete and lifecycle-scoped
The canonical snapshot-exact key SHALL include stable backend/source/branch identity, configured source lifecycle, native revision and exact locator, ordinary exact view kind, adapter fingerprint and identity contract, engine/order ABI, normalized semantic request, result kind/shape, normalized demand, and answer-affecting limits. Numeric revision equality alone SHALL NOT establish snapshot identity.

#### Scenario: Source lifecycle rotates
- **WHEN** cache expiry or history replacement installs a new source lifecycle
- **THEN** every old snapshot-exact entry becomes unreachable even if native revision numbers repeat

#### Scenario: Adapter semantics change
- **WHEN** adapter fingerprint, identity contract, engine/order ABI, or an answer-affecting limit changes
- **THEN** entries computed under the prior semantic identity are ineligible

#### Scenario: Native database value is not a public target
- **WHEN** a caller offers a native filtered, since, history, speculative, or otherwise uncertified database value to a public EACL operation
- **THEN** EACL rejects it as an authorization target before exact-generation lookup

### Requirement: Complete managed dependency proof
Managed-current reuse SHALL require an equal schema generation and an equal, canonically ordered version for every relation in the request's complete authorization dependency closure.

The canonical relation-generation vector MAY be empty when the complete authorization dependency closure is empty. In that case, equal source lifecycle, schema generation, semantic identity, and result shape form the complete managed proof.

#### Scenario: Unrelated relation mutation
- **WHEN** a transaction changes only relations outside the complete dependency closure
- **THEN** the managed proof remains equal and the cached semantic answer may be reused on the new current snapshot

#### Scenario: Relevant relationship addition
- **WHEN** a relationship is added using any relation in the complete dependency closure
- **THEN** that relation's version changes and the previous managed answer cannot match

#### Scenario: Relevant relationship retraction
- **WHEN** a relationship or dangling relationship half is retracted using any relation in the complete dependency closure
- **THEN** that relation's version changes and the previous managed answer cannot match

#### Scenario: Schema mutation
- **WHEN** any authorization schema definition changes
- **THEN** the schema generation changes and all managed answers and schema-derived plans from the previous generation are ineligible

#### Scenario: Incomplete or unreadable proof
- **WHEN** EACL cannot establish the complete dependency closure or read a valid version for every dependency
- **THEN** it treats the managed lookup as a miss and evaluates against the selected exact snapshot

#### Scenario: Empty complete dependency closure
- **WHEN** authorization depends on schema and object state but no relationship relation
- **THEN** EACL represents the complete relation proof as `[]` instead of rejecting managed reuse merely because the vector is empty

#### Scenario: Unrelated object churn
- **WHEN** objects outside the query-local selected internal/external identity frame change while the schema and complete relation proof remain equal
- **THEN** a managed semantic answer may be reused and all public object identifiers and metadata are rendered from the selected snapshot

#### Scenario: Mutable custom identity conversion
- **WHEN** a custom identity converter can change without a declared complete generation contract
- **THEN** the request remains exact-current-only

### Requirement: Initial relation generation
Every supported schema writer SHALL initialize a relation generation atomically when declaring a relation, including a relation with no relationship tuples.

#### Scenario: Empty new relation
- **WHEN** a permission depends on a newly declared relation that has never received a relationship write
- **THEN** EACL can construct a complete managed proof for the relation's empty initial state without a separate mutation-journal identity

#### Scenario: First relationship write
- **WHEN** the first relationship using that relation commits
- **THEN** the relation generation advances from its initialized value in the same transaction as the relationship datoms

#### Scenario: Missing physical generation
- **WHEN** an existing or newly declared relation lacks a valid stored relation generation
- **THEN** EACL treats managed mode as unprepared and does not synthesize an `:initial` generation from schema state

### Requirement: Atomic supported-writer publication
Every supported relationship addition, retraction, repair, deletion, or transaction-function cleanup SHALL advance every distinct affected relation generation in the same database transaction as the relationship datoms it changes. The assigned generation SHALL be the native committed transaction generation and SHALL be strictly greater than every relation generation visible before that transaction.

#### Scenario: Multi-relation transaction
- **WHEN** one transaction changes relationships in multiple relations
- **THEN** each distinct affected relation receives the same new committed generation and unrelated relations do not

#### Scenario: Repeated relation in one transaction
- **WHEN** multiple operations in one transaction affect the same relation
- **THEN** they compose into one idempotent current-transaction relation generation

#### Scenario: Writer bypasses the contract
- **WHEN** a library consumer changes authorization data without an EACL writer or documented stamp-producing helper
- **THEN** managed-cache coherence is outside EACL's guarantee until the documented integrity and lifecycle recovery completes

### Requirement: Commit-time endpoint liveness guards
Every client-calculated relationship addition SHALL prove at commit time that each distinct subject and resource endpoint still has the identity observed by the planning snapshot. A stale planned write SHALL abort instead of recreating relationship tuples on an endpoint deleted after planning.

#### Scenario: Endpoint is deleted after write planning
- **WHEN** a relationship addition is planned from a snapshot where an endpoint exists and a safe entity retraction deletes that endpoint before the addition commits
- **THEN** the endpoint identity guard fails and no forward or reverse tuple is committed

#### Scenario: Batch shares endpoints
- **WHEN** a relationship batch contains several tuples for the same subject or resource
- **THEN** the transaction emits at most one equivalent identity guard per distinct endpoint

#### Scenario: Transaction function owns discovery
- **WHEN** a backend transaction function discovers and mutates endpoint tuples from its transaction-start database
- **THEN** it may satisfy the endpoint-liveness invariant intrinsically rather than accepting a stale client-planned endpoint set

#### Scenario: Relation is removed after relationship planning
- **WHEN** a client-calculated relationship mutation resolved a relation from one schema generation and that schema generation changes before commit
- **THEN** a commit-time schema-write-fence predicate aborts the stale mutation before tuple or relation-stamp data can recreate the removed relation eid

#### Scenario: Predicate bookkeeping does not invalidate schema cache
- **WHEN** a backend implements an old-equals-old CAS by reasserting the guarded datom with a new physical transaction id
- **THEN** relationship writers guard a schema write fence distinct from the physical schema-generation assertion, and only a real schema replacement advances the cache generation

### Requirement: Linearizable schema replacement
Every supported schema replacement SHALL commit only when the schema generation used to calculate its complete diff remains current. Removing a relation SHALL additionally prove that the relation generation observed by the preflight unused check remains current until commit.

#### Scenario: Concurrent complete replacements
- **WHEN** two schema writers calculate different replacements from the same schema generation
- **THEN** at most one calculated diff commits and the stored definition entities cannot become a union paired with only one writer's schema string

#### Scenario: Relationship commits after relation-removal preflight
- **WHEN** a supported relationship mutation advances a relation generation after a schema writer checked that relation was unused
- **THEN** the relation-generation predicate aborts the schema replacement, which may be retried and then reports the relation in use

#### Scenario: Reverse-only relationship ghost
- **WHEN** a reverse tuple half still references a relation whose forward half is missing
- **THEN** the schema writer treats the relation as in use and does not remove its definition

### Requirement: Explicit lifecycle boundary
Cache correctness SHALL cover ordinary forward history only. Database restore, reset, branch force, history replacement, source reseeding, or equivalent replacement SHALL require the consumer to quiesce and expire or recreate affected EACL clients before serving requests.

#### Scenario: Consumer restores a database
- **WHEN** a consumer restores or replaces the database history
- **THEN** the consumer expires or recreates the EACL client before resuming authorization traffic

#### Scenario: Backend can detect replacement
- **WHEN** a backend exposes reliable source-lifecycle replacement evidence
- **THEN** EACL may automatically rotate its cache lifecycle instead of requiring an explicit expiry call

#### Scenario: Long-running ordinary request
- **WHEN** an ordinary request holds an older immutable current database value after a newer transaction commits
- **THEN** it may use stores and generations captured from the same lifecycle that are valid for its selected value; only authenticated adapter-selected ordinary exact snapshots are eligible for historical exact retention

#### Scenario: Complete lifecycle rotation
- **WHEN** `expire-cache!` rotates a client lifecycle
- **THEN** completed answers, managed subproblems, continuations, cursor/page navigation, derived schema caches, revision checkpoints, diagnostics, and token/cursor source identity from the prior lifecycle become unreachable by new requests

### Requirement: Cache coherence without graph metadata
Completed-answer validity SHALL NOT depend on mutation-journal records, mutation anchors, graph head/order values, anchor retention, wall-clock leases, transaction listeners, transaction-log scans, content scans, database-global cache CAS, or selectable cache-authority or proof modes.

#### Scenario: Database without mutation-graph schema
- **WHEN** a correctly stamped database has no EACL mutation-graph attributes installed
- **THEN** exact-current and proof-backed managed-current cache behavior remains available without additional coherence configuration

#### Scenario: Existing graph metadata
- **WHEN** a database still contains obsolete EACL graph or mutation-journal datoms
- **THEN** cache validity ignores those datoms and does not require destructive cleanup

### Requirement: Bounded cache overhead
For a transaction changing `n` relationship tuples across `r` distinct relations, cache-coherence bookkeeping SHALL be `O(r)` and total relationship maintenance SHALL remain `O(n + r)`. For a request depending on `d` distinct relations, proof acquisition SHALL be `O(d)`, while the derived managed cache and cursor proof identity SHALL have constant-size schema and frontier components.

#### Scenario: Large same-relation batch
- **WHEN** one transaction changes many relationships using one relation
- **THEN** cache bookkeeping emits one idempotent relation-generation update for that relation

#### Scenario: First read after unrelated transaction
- **WHEN** an unrelated transaction rotates the exact generation
- **THEN** the first eligible request performs proof work bounded by its complete dependency closure, may hit managed-current, and promotes the answer into the selected exact generation

#### Scenario: Subsequent read at the same snapshot
- **WHEN** an identical eligible request follows at the same snapshot
- **THEN** it can hit exact-current without rereading proof generations

### Requirement: Selected-snapshot result rendering
Managed cache entries SHALL contain only completed semantic authorization results. Cache basis, cursor/token context, external identifiers, and other public snapshot metadata SHALL be rebuilt from the immutable snapshot selected for the current request.

#### Scenario: Candidate was computed at another basis
- **WHEN** a proof-equivalent managed answer computed at snapshot `C` is reused for selected snapshot `S`
- **THEN** the returned public cache basis and identifiers describe `S`, while any internal computation basis remains non-public

#### Scenario: Demand result was computed at another basis
- **WHEN** a completed demand-bounded answer is reused at a proof-equivalent selected snapshot
- **THEN** the normalized demand and semantic request identity match exactly and all selected-snapshot metadata is rebuilt from the new snapshot

### Requirement: Unconditional proof-backed cache coherence
Every cache-enabled EACL client SHALL attempt proof-backed managed reuse for every deterministic, cacheable, ordinary current-snapshot authorization request without requiring a coherence or proof-mode option. Exact lookup for the selected immutable snapshot SHALL occur first. Managed publication SHALL contain only a completed answer for the complete normalized request and SHALL require a complete valid proof; otherwise EACL SHALL evaluate and cache only against the exact selected snapshot.

#### Scenario: Default demand-bounded request
- **WHEN** a default client completes a demand-bounded authorization request whose normalized demand and complete dependency closure are known
- **THEN** the completed answer is eligible for managed reuse under the same semantic request key and proof

#### Scenario: Partial traversal state
- **WHEN** authorization evaluation has produced only an in-progress traversal, incomplete continuation, or partially proved subproblem
- **THEN** EACL does not publish that state as a managed completed answer

#### Scenario: Same-snapshot exact hit
- **WHEN** the exact cache contains the completed answer for the selected immutable snapshot
- **THEN** EACL returns it without reading schema or relation generations for managed proof

#### Scenario: Proof is unavailable
- **WHEN** EACL cannot establish a complete valid proof for an otherwise eligible current request
- **THEN** it evaluates against the selected immutable snapshot and does not return or publish an unproved managed answer

#### Scenario: Cache is disabled
- **WHEN** a consumer selects the no-cache implementation or disables caching for one request
- **THEN** EACL performs no completed-answer cache lookup or publication

### Requirement: Complete scalar dependency proof
For ordinary forward history, managed reuse SHALL require equal source lifecycle, semantic request identity, result shape, schema assertion generation, and scalar dependency frontier. The complete canonical dependency closure SHALL be a deterministic function of equal schema semantics and normalized semantic request. The frontier SHALL be the maximum native generation over every relation in that closure, or the distinguished initial value when the closure is empty. A proof SHALL be valid only when every supported relevant mutation stamps every affected relation atomically with a native committed transaction generation strictly greater than all generations visible before that transaction.

#### Scenario: Unrelated transaction
- **WHEN** a forward transaction changes no relation in the complete dependency closure and does not change authorization schema
- **THEN** the schema generation and dependency frontier remain equal and the managed answer may be reused

#### Scenario: Relevant relationship mutation
- **WHEN** a supported forward transaction changes any relation in the complete dependency closure
- **THEN** the transaction stamps that relation with its globally later committed generation, the dependency frontier advances to that generation, and the previous managed answer cannot match

#### Scenario: Several relevant relations change atomically
- **WHEN** one supported transaction changes several dependency relations
- **THEN** every affected relation receives the same committed generation and the dependency frontier advances once

#### Scenario: Empty dependency closure
- **WHEN** the complete authorization dependency closure contains no relationship relation
- **THEN** EACL uses the distinguished initial frontier together with schema generation rather than treating proof as unavailable

#### Scenario: Schema change
- **WHEN** any authorization schema definition changes
- **THEN** the schema assertion generation changes and every answer derived from the previous schema becomes ineligible independently of its relation frontier

#### Scenario: Adapter cannot certify global ordering
- **WHEN** an adapter supplies per-relation values but cannot certify that every supported mutation assigns a committed generation later than every prior generation
- **THEN** EACL does not use the scalar proof for cross-snapshot reuse

#### Scenario: Dependency extraction is inconsistent
- **WHEN** equal schema semantics and normalized request do not yield one complete canonical dependency closure
- **THEN** EACL treats scalar proof as unavailable rather than comparing maxima from different relation sets

### Requirement: Complete proof availability
A managed proof SHALL be all-or-nothing and scoped to the exact selected adapter, source lifecycle, and immutable snapshot. EACL SHALL treat missing, malformed, duplicate, non-canonical, oversized, exceptional, or partially available dependency evidence as unavailable and SHALL never derive a frontier from a partial relation set.

#### Scenario: Unprepared generation state
- **WHEN** the selected database lacks a valid schema generation or any relation generation required by the complete dependency closure
- **THEN** managed proof is unavailable and exact-snapshot evaluation remains safe

#### Scenario: Proof bound exceeded
- **WHEN** the complete dependency closure exceeds a documented implementation proof bound
- **THEN** managed proof is unavailable rather than truncated

#### Scenario: Relevant proof changed
- **WHEN** a complete valid proof is available but its schema generation or dependency frontier differs from the cached proof
- **THEN** the request is a managed miss rather than a proof-unavailable request

#### Scenario: EACL-created speculative snapshot
- **WHEN** a request evaluates an EACL-created speculative snapshot
- **THEN** managed reuse requires an authenticated committed proof with complete dependencies disjoint from cumulative speculative effects
- **AND** exact lookup and persistent publication remain disabled

#### Scenario: Long-running ordinary request
- **WHEN** an ordinary request retains an older immutable current database value while later transactions commit
- **THEN** it may use exact or managed entries proved for that selected value and lifecycle without combining state from a newer value

### Requirement: Certified custom identity reuse
Cross-snapshot reuse with a custom identity codec SHALL require an explicit stable codec fingerprint and a declared deterministic, injective round-trip contract. A codec without that contract SHALL be isolated to the exact selected snapshot and to an opaque client-local cursor identity.

#### Scenario: Deterministic fingerprinted codec
- **WHEN** a custom codec declares a stable fingerprint and satisfies certified internalize/externalize round-trip behavior
- **THEN** its completed semantic answers may use the same managed proof contract as built-in identity handling

#### Scenario: Unfingerprinted custom codec
- **WHEN** a client uses a custom codec without an explicit stable fingerprint
- **THEN** EACL does not accept its cursors or managed answers across another client lifecycle or restart

### Requirement: Supported mutation boundary and recovery
EACL SHALL guarantee managed cache coherence only for authorization-relevant schema, relationship, identity, and safe entity-deletion mutations performed through EACL APIs or documented EACL transaction data or functions that atomically publish every required generation. After unsupported mutation, the consumer MUST quiesce affected authorization traffic, restore database integrity, and rotate every affected client lifecycle in every process before cached traffic resumes.

#### Scenario: Supported transaction data
- **WHEN** a consumer transacts EACL-produced relationship transaction data intact in one database transaction
- **THEN** tuple mutations and every required generation commit atomically and coherence remains guaranteed

#### Scenario: Transaction data is split
- **WHEN** a consumer separates EACL-produced tuple operations from their generation operations or transacts them independently
- **THEN** the mutation is unsupported and cache-coherence guarantees are suspended pending recovery

#### Scenario: Valid raw relationship change
- **WHEN** an unsupported mutation changes relationship datoms but leaves valid paired tuples
- **THEN** the consumer quiesces affected traffic and expires or recreates every affected client in every process before resuming cached authorization

#### Scenario: Raw mutation leaves ghost tuples
- **WHEN** an unsupported mutation leaves a forward-only or reverse-only relationship tuple
- **THEN** the consumer first repairs the data through a supported deletion, repair, or safe-retraction path and then rotates every affected lifecycle

#### Scenario: Unsupported schema mutation
- **WHEN** EACL authorization schema is changed outside `eacl/write-schema!`
- **THEN** the consumer submits the complete desired schema through `eacl/write-schema!` and rotates every affected lifecycle even when that schema write is a database no-op

#### Scenario: Preparation is not recovery
- **WHEN** an unstamped raw relationship mutation occurred before `prepare-cache-coherence!`
- **THEN** preparation does not discover or repair the old generation and does not replace integrity repair and lifecycle rotation

#### Scenario: Unrelated application mutation
- **WHEN** application-domain datoms that are not authorization dependencies change without changing EACL schema, relationships, permissioned identity, or entity liveness
- **THEN** the EACL mutation boundary imposes no writer requirement on those datoms

### Requirement: Future authorization dependencies fail closed
Any future authorization dependency class beyond schema, relationships, selected identity, and entity liveness SHALL remain exact-only until it has a complete atomic generation contract and a proved preservation theorem integrated into the request proof.

#### Scenario: New unproved dependency class
- **WHEN** an authorization operation begins consulting a domain attribute or other state not covered by the current proof
- **THEN** managed reuse for that operation is disabled until its dependency proof is specified, implemented, and verified
