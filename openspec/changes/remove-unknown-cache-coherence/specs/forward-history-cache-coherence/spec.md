## ADDED Requirements

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

#### Scenario: Historical or constructed database value
- **WHEN** a request explicitly uses `as-of`, `since`, a filtered or speculative database, or an unsupported caller-supplied database value
- **THEN** EACL makes no managed-cache availability guarantee and bypasses proof-backed completed-answer reuse

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

## MODIFIED Requirements

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

## REMOVED Requirements

### Requirement: Explicit coherence authority
**Reason**: Supported authorization writers already maintain the complete native-generation proof, so selectable unknown authority only disables safe reuse and preserves an unsupported mutation model.

**Migration**: None. The option is invalid in this release and no compatibility mode is provided.
