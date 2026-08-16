## Purpose

Define a fast authorization-cache contract for ordinary forward database history using immutable request snapshots and complete database-visible schema and relation generations.

## ADDED Requirements

### Requirement: One immutable snapshot per request
EACL SHALL select one immutable backend snapshot before schema resolution, dependency extraction, cache lookup, authorization evaluation, result rendering, or cache publication, and SHALL perform all of those stages against that snapshot.

#### Scenario: Concurrent commit after selection
- **WHEN** a relationship transaction commits after a request has selected its immutable snapshot
- **THEN** the request completes consistently against the selected pre-commit snapshot without combining old and new state

#### Scenario: Long-running request
- **WHEN** a long-running API call retains an older immutable database value while newer transactions commit
- **THEN** the call remains correct for its selected value and any late cache publication is isolated from newer exact generations

### Requirement: Exact-current generation isolation
The exact-current cache SHALL admit and return an entry only within the lifecycle and immutable snapshot generation for which it was computed.

#### Scenario: Any forward transaction advances the snapshot
- **WHEN** the selected current snapshot changes after any committed transaction
- **THEN** the previous exact-current generation is unreachable from requests selecting the new snapshot

#### Scenario: Late old-generation publication
- **WHEN** computation against an old snapshot finishes after a newer exact generation is installed
- **THEN** its result cannot populate or replace the newer generation

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
Every supported relationship addition, retraction, repair, deletion, or transaction-function cleanup SHALL advance every distinct affected relation generation in the same database transaction as the relationship datoms it changes.

#### Scenario: Multi-relation transaction
- **WHEN** one transaction changes relationships in multiple relations
- **THEN** each distinct affected relation receives the transaction's new generation and unrelated relations do not

#### Scenario: Repeated relation in one transaction
- **WHEN** multiple operations in one transaction affect the same relation
- **THEN** they compose into one idempotent current-transaction relation generation

#### Scenario: Writer bypasses the contract
- **WHEN** a library consumer changes authorization data without an EACL writer or documented stamp-producing helper
- **THEN** managed-cache coherence is outside EACL's guarantee while exact-current reuse remains snapshot-isolated

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

### Requirement: Explicit coherence authority
EACL SHALL default to unknown writer authority and SHALL enable cross-snapshot managed reuse only after the consumer explicitly asserts managed stamped-writer authority.

#### Scenario: Unknown authority
- **WHEN** a client uses the default authority setting
- **THEN** it uses exact-current reuse only and does not infer relation coherence from stamps

#### Scenario: Managed authority
- **WHEN** a client opts into managed authority and all relevant writers obey the atomic stamp contract
- **THEN** it may reuse answers across unrelated forward transactions using complete dependency proofs

### Requirement: Explicit lifecycle boundary
Cache correctness SHALL cover ordinary forward history only. Database restore, reset, branch force, history replacement, source reseeding, or equivalent replacement SHALL require the consumer to quiesce and expire or recreate affected EACL clients before serving requests.

#### Scenario: Consumer restores a database
- **WHEN** a consumer restores or replaces the database history
- **THEN** the consumer expires or recreates the EACL client before resuming authorization traffic

#### Scenario: Backend can detect replacement
- **WHEN** a backend exposes reliable source-lifecycle replacement evidence
- **THEN** EACL may automatically rotate its cache lifecycle instead of requiring an explicit expiry call

#### Scenario: Arbitrary historical database value
- **WHEN** a low-level caller evaluates an arbitrary historical, filtered, speculative, or caller-supplied database value
- **THEN** completed-answer caching is bypassed

#### Scenario: Long-running ordinary request
- **WHEN** an ordinary request holds an older immutable current database value after a newer transaction commits
- **THEN** it may use stores and generations captured from the same lifecycle that are valid for its selected value, but EACL makes no cache-availability promise for separately constructed `as-of` values

#### Scenario: Complete lifecycle rotation
- **WHEN** `expire-cache!` rotates a client lifecycle
- **THEN** completed answers, managed subproblems, continuations, cursor/page navigation, derived schema caches, revision checkpoints, diagnostics, and token/cursor source identity from the prior lifecycle become unreachable by new requests

### Requirement: Cache coherence without graph metadata
Completed-answer validity SHALL NOT depend on mutation-journal records, mutation anchors, graph head/order values, anchor retention, wall-clock leases, transaction listeners, or transaction-log scans.

#### Scenario: Database without mutation-graph schema
- **WHEN** a correctly stamped database has no EACL mutation-graph attributes installed
- **THEN** exact-current and managed-current cache behavior remains available according to the configured authority

#### Scenario: Existing graph metadata
- **WHEN** an upgraded database still contains legacy EACL graph or mutation-journal datoms
- **THEN** cache validity ignores those datoms and does not require destructive cleanup

### Requirement: Bounded cache overhead
For a transaction changing `n` relationship tuples across `r` distinct relations, cache-coherence bookkeeping SHALL be `O(r)` and total relationship maintenance SHALL remain `O(n + r)` without a database-global cache CAS.

#### Scenario: Large same-relation batch
- **WHEN** one transaction changes many relationships using one relation
- **THEN** cache bookkeeping emits one idempotent relation-generation update for that relation

#### Scenario: First read after unrelated transaction
- **WHEN** an unrelated transaction rotates the exact generation
- **THEN** the first eligible request performs work bounded by its dependency closure, may hit managed-current, and promotes the result into the selected exact generation

#### Scenario: Subsequent read at the same snapshot
- **WHEN** an identical eligible request follows at the same snapshot
- **THEN** it can hit exact-current without rereading relation generations

### Requirement: Selected-snapshot result rendering
Managed cache entries SHALL contain semantic authorization results only. Cache basis, cursor/token context, external identifiers, and other public snapshot metadata SHALL be rebuilt from the immutable snapshot selected for the current request.

#### Scenario: Candidate was computed at another basis
- **WHEN** a dependency-equivalent managed answer computed at snapshot `C` is reused for selected snapshot `S`
- **THEN** the returned public cache basis and identifiers describe `S`, while any internal computation basis remains non-public
