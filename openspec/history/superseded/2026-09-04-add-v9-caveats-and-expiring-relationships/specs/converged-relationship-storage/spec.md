## MODIFIED Requirements

### Requirement: Two-value endpoint representation

Each EACL relationship SHALL be represented by exactly two cardinality-many,
indexed, authoritative endpoint values: a forward value on the subject entity
and a reverse value on the resource entity. The values SHALL use this fixed
seven-component v9 order:

- forward:
  `[subject-type relation-eid resource-type resource-eid caveat-eid caveat-context-eid valid-until-ms]`
- reverse:
  `[resource-type relation-eid subject-type subject-eid caveat-eid caveat-context-eid valid-until-ms]`

The owning endpoint eid plus the first four components SHALL identify the
logical relationship. The first four components alone SHALL be only an
endpoint-local key and MUST NOT be used as a global relationship identity. Components
five through seven SHALL be qualifiers and SHALL be `nil` when absent. Both
physical halves SHALL contain the same Caveat definition ref, Caveat context
ref, and expiry.

A non-nil Caveat context ref SHALL require a non-nil Caveat definition ref. A
non-nil Caveat definition ref with a nil context ref SHALL represent a Caveat
with empty relationship-bound context. EACL SHALL store no entity whose sole
purpose is to represent every relationship.

#### Scenario: Relationship creation

- **WHEN** EACL creates a relationship between existing subject and resource endpoints
- **THEN** the backend stores exactly one forward endpoint datom and one reverse endpoint datom and stores no relationship entity

#### Scenario: Permanent uncaveated relationship

- **WHEN** EACL creates a relationship without a Caveat or expiry
- **THEN** it stores exactly one seven-slot forward value and one seven-slot
  reverse value
- **AND** slots five through seven are `nil`
- **AND** it stores no relationship entity

#### Scenario: Expiry-only relationship

- **WHEN** EACL creates a relationship with expiry and no Caveat
- **THEN** slots five and six are `nil`
- **AND** slot seven contains the normalized expiry
- **AND** both endpoint halves contain identical qualifiers

#### Scenario: Caveat with empty bound context

- **WHEN** Phase 2 creates a relationship with a Caveat and no
  relationship-bound context values
- **THEN** slot five contains the Caveat definition eid
- **AND** slot six is `nil`
- **AND** no Caveat context entity is allocated

#### Scenario: Caveat with bound context

- **WHEN** Phase 2 creates a relationship with non-empty relationship-bound
  Caveat context
- **THEN** slot five contains the Caveat definition eid
- **AND** slot six contains the same sparse Caveat context eid in both halves
- **AND** the context payload is not duplicated inside the endpoint values

#### Scenario: Context without Caveat

- **WHEN** an update supplies or stored data contains a non-nil slot six with a
  nil slot five
- **THEN** admitted writes reject it
- **AND** an affected operation rejects out-of-band authoritative corruption with a typed error

#### Scenario: Duplicate physical value

- **WHEN** the same complete endpoint value is added more than once in one database value
- **THEN** database set semantics retain one datom for that endpoint value

#### Scenario: Same tuple value on two owners

- **WHEN** two different subjects legitimately produce the same forward tuple value
- **THEN** their logical identities remain different because the owner eid is part of the key

#### Scenario: Duplicate complete physical value

- **WHEN** the same complete seven-slot endpoint value is added more than once
  in one database value
- **THEN** database set semantics retain one datom for that endpoint value

### Requirement: Ordered endpoint index access

Every backend adapter SHALL implement candidate access for exact matching, adjacency, relationship
filtering, relation-in-use checks, and forward and reverse pagination using the single v9 endpoint attribute appropriate to the requested direction.
Pair, ownership, and content-proof validation MAY require additional reads. The first
three components SHALL remain the typed relation prefix, the fourth component
SHALL remain the opposite endpoint used for ordering and pagination, and all
three qualifier components SHALL remain trailing.

Every tuple/vector seek boundary SHALL use the complete stored arity required by
the backend ordering contract. Normal v9 authorization and relationship reads
MUST NOT scan, union, merge, or prefer values from a v7 relationship attribute.

#### Scenario: Forward endpoint scan

- **WHEN** authorization or relationship pagination scans outward from a known
  subject
- **THEN** the adapter performs one seek against that subject's v9 forward
  endpoint values
- **AND** returns candidates ordered by resource eid before evaluating
  qualifiers

#### Scenario: Reverse endpoint scan

- **WHEN** authorization or relationship pagination scans inward from a known
  resource
- **THEN** the adapter performs one seek against that resource's v9 reverse
  endpoint values
- **AND** returns candidates ordered by subject eid before evaluating
  qualifiers

#### Scenario: Exact logical relationship probe

- **WHEN** EACL probes one known subject, relation, and resource
- **THEN** it seeks by the first four components using full seven-slot low and
  high bounds
- **AND** obtains zero or one well-formed logical relationship
- **AND** treats multiple qualifier variants as corruption rather than choosing
  a grant

#### Scenario: Reverse-order continuation

- **WHEN** a descending scan resumes from a relationship cursor
- **THEN** `rseek-datoms` or its backend equivalent starts from a full
  seven-slot high bound; nil is not used as a high qualifier sentinel
- **AND** returns only values inside the requested endpoint prefix and ordering
  boundary

#### Scenario: Adjacent prefix is absent

- **WHEN** no value exists for a requested prefix but an adjacent prefix exists
- **THEN** explicit entity, attribute, arity, and component guards prevent the
  adjacent value from being returned

#### Scenario: Legacy attributes remain installed but empty

- **WHEN** a Datomic database still has immutable v7 attribute definitions but
  no v7 relationship datoms
- **THEN** v9 reads never seek those attributes
- **AND** their schema presence adds no per-hop second seek

### Requirement: Atomic pair mutation and repair

Relationship mutation SHALL add, replace, or retract both v9 endpoint values in
one admitted transaction. Every planned create, touch, delete, or collection
SHALL validate at commit time the complete identity-group and auxiliary state
on which its plan depends and SHALL preserve `:create`, `:touch`, and `:delete`
semantics over the owner-qualified first-four-component logical identity.

`:create` SHALL conflict with every stored temporal or Caveat state.
`:touch` SHALL create when absent and otherwise replace the exact old endpoint
pair and changed Caveat context entity with the requested canonical
state. `:delete` SHALL require only the logical relationship and SHALL remove
all discoverable physical variants so it can repair bounded corruption.
A writer topology unable to provide commit-time state validation SHALL reject
Relationship writes with a typed unsupported-writer error rather than use only
plan-time checks. Exceeding the configured repair bound SHALL fail atomically.

#### Scenario: Complete relationship conflict

- **WHEN** `:create` targets a logical relationship whose complete forward and reverse values exist
- **THEN** EACL reports `:eacl/relationship-conflict`

#### Scenario: Racing creates of one relationship

- **WHEN** two creates for one identity are planned against the same pre-write basis
- **THEN** commit-time validation permits exactly one creation
- **AND** the committed database contains exactly one canonical endpoint pair

#### Scenario: Racing creates with different qualifiers

- **WHEN** two `:create` transactions for one logical identity are planned
  against the same pre-write value with different qualifiers
- **THEN** exactly one creation commits
- **AND** the other fails with `:eacl/relationship-conflict`
- **AND** one canonical forward/reverse pair remains

#### Scenario: Stale concurrent touches

- **WHEN** two different touches are planned from the same old qualifiers and commit serially
- **THEN** the second plan cannot add its replacement after its expected old state changed
- **AND** it replans or fails with a typed conflict rather than leaving two variants

#### Scenario: Touch replaces all qualifiers

- **WHEN** `:touch` changes the Caveat, relationship-bound context, or expiry
- **THEN** EACL retracts the exact old forward and reverse values
- **AND** adds one matching new pair
- **AND** creates/retracts changed sparse context data atomically
- **AND** advances the affected relation mutation identity exactly once

#### Scenario: Touch is unchanged

- **WHEN** `:touch` supplies qualifiers canonically equal to the stored state
- **THEN** EACL may treat the operation as an idempotent no-op
- **AND** it does not allocate an unnecessary replacement context entity

#### Scenario: Repeated and conflicting batch updates

- **WHEN** one batch repeats an identical update for one logical relationship
- **THEN** the batch has the same outcome as one occurrence
- **AND** `:create` still conflicts with an identity that existed before the batch
- **WHEN** one batch contains semantically different updates for that identity
- **THEN** EACL rejects it with
  `:eacl/invalid-relationship-update-batch` before transaction submission

#### Scenario: Incomplete relationship repair

- **WHEN** `:touch` targets missing, mismatched, or duplicated endpoint halves
- **THEN** EACL removes bounded discoverable variants and writes one matching
  canonical pair

#### Scenario: Unconditional deletion

- **WHEN** `:delete` targets a complete, incomplete, corrupt, or absent pair
- **THEN** EACL removes every variant that is safely attributable within the configured repair bound
- **AND** it never retracts auxiliary data whose exclusive ownership has not been established

#### Scenario: Delete omits qualifiers

- **WHEN** `:delete` supplies the subject, relation, and resource but no Caveat,
  context, or expiry
- **THEN** EACL removes the matching logical relationship if present
- **AND** removes its singly-owned context data

### Requirement: Endpoint deletion and integrity

Every backend adapter SHALL treat peer eids, Caveat definition eids, and Caveat
context eids embedded in endpoint values according to its native value
semantics. EACL object cleanup SHALL explicitly remove every touching endpoint
pair and owned auxiliary data before endpoint retraction.

The module SHALL expose an offline integrity report covering malformed arity,
dangling or mismatched halves, qualifier disagreement, owner-qualified identity
collisions, invalid Caveat/context combinations, missing or multiply-owned
context entities, missing or malformed referenced Caveat expressions, and malformed expiry. An authoritative integrity fault encountered in an operation MUST
abort that operation with `:eacl/invalid-relationship-state`; it MUST NOT be modeled as
relationship absence, because absence on the subtracting side of exclusion can
grant access.

#### Scenario: EACL object cleanup

- **WHEN** `delete-object!` removes an endpoint
- **THEN** it removes local and peer endpoint values for every touching
  relationship
- **AND** removes singly-owned Caveat context values
- **AND** retains shared Caveat definitions and expression entities
- **AND** publishes every affected relation mutation identity

#### Scenario: Direct endpoint retraction

- **WHEN** a consumer retracts an endpoint entity outside EACL
- **THEN** peer halves and context entities can remain as detectable corruption
  because embedded refs are not relied upon for automatic cascading

#### Scenario: Integrity scan

- **WHEN** the offline integrity report scans the database
- **THEN** it reports each invalid pair, identity collision, Caveat/context
  fault or expiry fault without mutating data

#### Scenario: Corrupt relationship reaches authorization

- **WHEN** authorization encounters an authoritative integrity fault on a candidate
- **THEN** the affected authorization operation fails with
  `:eacl/invalid-relationship-state`
- **AND** it does not publish an answer or reusable continuation

### Requirement: Database-visible proof coverage

Unknown-writer relationship proofs SHALL commit to both complete seven-slot
endpoint values, the public identities of their endpoints, and all authoritative
relationship-bound Caveat context payloads. They SHALL cover only authoritative graph data, not optional future derived maintenance indexes. A proof SHALL separately validate structure; digest change
detection alone is not a structural-integrity certificate. A proof MUST change when any
answer-affecting relationship qualifier or referenced context changes out of band.

Caveat definitions, their expression references, and complete canonical expression payloads SHALL be covered by the schema proof and generation. Managed expression immutability or a stored digest SHALL NOT replace selected-view content validation for unknown-writer sources. Expression entities SHALL remain shared schema data; they SHALL NOT change the seven-slot endpoint ABI or become Relationship-owned cleanup data.

#### Scenario: One half changes out of band

- **WHEN** one endpoint half changes for a relation in a cached request's
  dependency closure
- **THEN** the next content-proof validation rejects the previous answer

#### Scenario: Context payload changes out of band

- **WHEN** a referenced context entity retains its eid but its payload changes
- **THEN** the database-visible content proof changes
- **AND** the prior answer is not reused

#### Scenario: Qualifier ref changes out of band

- **WHEN** a Caveat ref or expiry changes without an
  admitted relation-version update
- **THEN** the content proof changes and prior reuse is rejected

#### Scenario: Endpoint identity changes out of band

- **WHEN** an endpoint's public identity changes while its eid remains stable
- **THEN** the content proof changes and prior authorization results are not
  reused

#### Scenario: Shared expression changes out of band

- **WHEN** a named Caveat's expression reference or payload changes without the admitted schema-generation update
- **THEN** the selected schema content proof detects the change and rejects prior affected authorization reuse
- **AND** a cached program cannot hide that changed authoritative input

### Requirement: Shared logical storage layer

The core workspace SHALL provide backend-neutral pure functions for fixed-arity
v9 endpoint construction, decoding, logical-identity extraction, Caveat/context
qualifier validation, peer-half construction, exact retractions, expiry
validation, and prefix validation wherever all backends share behavior.
Backend-specific index, transaction, clock, schema, and history operations SHALL
remain inside adapters.

#### Scenario: Equivalent relationship

- **WHEN** all supported adapters encode the same relationship and qualifiers
- **THEN** their forward and reverse values have identical seven-component
  order
- **AND** decode to the same endpoints, Caveat ref, context ref, and expiry

#### Scenario: Permanent normalization

- **WHEN** a relationship omits every qualifier
- **THEN** the shared codec emits three explicit trailing `nil` values rather
  than a shorter value

#### Scenario: Backend-specific database access

- **WHEN** an adapter reads or writes endpoint values
- **THEN** it uses its native APIs without introducing backend dependencies
  into the shared codec

### Requirement: Cross-runtime validation

The v9 endpoint-pair adapter SHALL satisfy the shared backend contract and each
backend's storage, pagination, consistency, mutation, cache-proof, cleanup,
integrity, temporal, and Caveat tests. DataScript SHALL satisfy the same logical
contract on JVM and ClojureScript runtimes.

#### Scenario: JVM suite

- **WHEN** the JVM backend tests run
- **THEN** direct and recursive authorization, lookup, count, relationship read/write, proof, deletion, and integrity behavior pass

#### Scenario: JVM backend suites

- **WHEN** Datomic Pro, Datahike, Datalevin, and DataScript JVM suites run
- **THEN** direct and recursive authorization, lookup, count, relationship
  reads/writes, qualifier evaluation, cache proof, cleanup, collection, and
  integrity behavior pass

#### Scenario: ClojureScript suite

- **WHEN** the compiled DataScript ClojureScript suite runs under Node
- **THEN** fixed seven-slot ordering, seeks, pagination, expiry, Caveat
  evaluation, mutation, and corruption handling match JVM semantics

#### Scenario: Single-source traversal benchmark

- **WHEN** v9 direct checks, adjacency, arrows, lookup, and pagination are
  benchmarked against v7
- **THEN** every logical relation hop obtains candidates from one authoritative
  endpoint stream
- **AND** total reads, integrity-proof work, candidates advanced, tuple bytes,
  and active-Caveat overhead are measured without a hidden
  v7 fallback

### Requirement: Prerelease compatibility boundary

The v9 adapter SHALL validate the specific seven-slot format identity, not arity or version number alone, and SHALL use only this endpoint-pair layout for
authorization, mutation, reads, cleanup, integrity, and proof generation. EACL
SHALL NOT provide an automatic v7-to-v9 relationship migration, dual-read
compatibility mode, mixed-format authorization path, or per-relation fallback.

A v9 client SHALL refuse startup when v7 relationship datoms remain or when the
persisted relationship-storage version is incompatible. It SHALL provide typed
rebuild/reseed guidance. Legacy Datomic schema definitions MAY remain inert. Every current-basis
selection and mutation commit SHALL also validate a persisted semantic
capability epoch so a long-lived Phase 1 client cannot serve or write after
Phase 2 activation. Activation SHALL drain or fence old clients before serving
Caveated current views; a capability epoch read from a retained old immutable
pin is insufficient. Without an enforceable fence, activation SHALL require a
coordinated stop/upgrade/activate cutover.

#### Scenario: Existing prerelease demo database

- **WHEN** a DataScript database created from the old relationship-entity branch is opened with v9
- **THEN** its old entities are not interpreted as active v9 Relationships
- **AND** startup rejects the incompatible populated store and directs the user to recreate the database or reload Relationships through the v9 API

#### Scenario: Populated v7 database

- **WHEN** a v9 client opens a database containing any v7 forward or reverse
  relationship datom
- **THEN** startup fails with `:eacl/storage-version`
- **AND** no authorization request interprets the old data
- **AND** the error directs the operator to rebuild or reseed Relationships

#### Scenario: Fresh or rebuilt database

- **WHEN** a database contains no old relationship datoms and has the required
  v9 schema
- **THEN** EACL stamps or validates relationship storage version 9
- **AND** every subsequently admitted relationship uses only v9 values

#### Scenario: Mixed relationship data

- **WHEN** both v7 and v9 relationship datoms are detected
- **THEN** EACL fails closed at startup rather than merging the graphs

#### Scenario: Live Phase 1 client during Phase 2 activation

- **WHEN** another writer atomically enables the Phase 2 Caveat capability epoch
- **THEN** a Phase 1 client fails its next current-basis selection or mutation
- **AND** it cannot treat a Caveated relationship as absent

#### Scenario: Old cache or cursor

- **WHEN** a cache artifact or cursor was produced for the old relationship ABI
- **THEN** v9 rejects it through the cache, engine, adapter, or token ABI
  boundary

## ADDED Requirements

### Requirement: One logical relationship regardless of qualifiers

Logical relationship identity SHALL consist of the endpoint owner eid plus the
first four endpoint components. Conflict keys and batch deduplication MUST NOT
omit the owner or the existing wildcard/subject-relation identity, and SHALL
resolve endpoint aliases before deduplication. Caveat definition, Caveat context, and `valid-until`
SHALL NOT create distinct relationships. At most one stored relationship SHALL
exist for a logical identity.

This rule SHALL match SpiceDB behavior: relationships differing only in Caveat,
Caveat context, or expiration cannot coexist.

#### Scenario: Different Caveat is not a second relationship

- **WHEN** a relationship already exists and a caller attempts `:create` with
  the same logical identity and a different Caveat
- **THEN** EACL reports `:eacl/relationship-conflict`

#### Scenario: Different context is not a second relationship

- **WHEN** a relationship already exists and a caller attempts `:create` with
  different relationship-bound context
- **THEN** EACL reports `:eacl/relationship-conflict`

#### Scenario: Different expiry is not a second relationship

- **WHEN** a relationship already exists and a caller attempts `:create` with
  different expiry
- **THEN** EACL reports `:eacl/relationship-conflict`

#### Scenario: Out-of-band variants coexist

- **WHEN** multiple complete endpoint values share the same owner and first four components
- **THEN** integrity reports a logical-identity collision
- **AND** an affected operation fails with `:eacl/invalid-relationship-state`
- **AND** no qualifier precedence rule silently selects one variant

### Requirement: Candidate-stream work is explicitly bounded

One endpoint seek SHALL mean one ordered candidate source, not constant work or
one total database read. Effective reads, counts, and authorization SHALL charge
every advanced inactive/corrupt candidate, lookahead item, validation read,
payload byte, and evaluator step to existing request limits. Exhaustion SHALL be
a typed failure and MUST NOT be returned as a successful empty/truncated result.

#### Scenario: Clustered inactive prefix

- **GIVEN** an endpoint has a long prefix of expired relationships
- **WHEN** an effective page seeks the first 20 active results
- **THEN** every inactive candidate advanced is charged to the request
- **AND** reaching the limit fails without asserting that the stream ended

#### Scenario: Integrity validation costs reads

- **WHEN** an unknown-writer source lacks a reusable basis integrity certificate
- **THEN** pair and auxiliary validation may perform reads beyond the one candidate stream
- **AND** telemetry reports those reads separately

### Requirement: Managed source trust does not mandate duplicate endpoint reads

EACL SHALL rely on its admitted atomic writer and source-certification contracts for the application invariants they establish. It SHALL NOT require re-reading the opposite endpoint for every candidate solely to recheck storage-engine atomicity. Unknown-writer sources SHALL separately establish required pair/context structure and complete content proofs. An actually encountered authoritative fault SHALL still abort the affected operation.

#### Scenario: Managed qualified source

- **WHEN** a source satisfies the managed writer and integrity certification contracts
- **THEN** a traversal can use its local tuple for expiry without an extra peer read solely to obtain or reconfirm that deadline

#### Scenario: Unknown writer

- **WHEN** a source cannot establish the required structural guarantees
- **THEN** it performs the necessary scope validation or fails qualification
- **AND** it does not claim a matching digest alone proves pair integrity

### Requirement: Expiration does not require derived storage

A finite deadline SHALL NOT require an additional expiration-index datom or a schedule entity. Optional collection SHALL use bounded authoritative forward scans in this release. Both endpoint tuples SHALL independently provide the same expiry value. Any future derived index SHALL require separate qualification and SHALL NOT change this endpoint ABI merely to add maintenance ordering.

#### Scenario: Finite expiry without collection index

- **WHEN** a Relationship has a finite deadline and no auxiliary expiration index exists
- **THEN** forward and reverse authorization still enforce expiry directly
- **AND** optional collection can discover it through a bounded forward scan

#### Scenario: Discarded v9 format

- **WHEN** a store or artifact uses the withdrawn eight-slot layout or an experimental seven-slot layout with different meanings
- **THEN** v9 rejects it as incompatible even if a numeric version marker says 9
