# stamp-only-safe-entity-retraction Specification

## Purpose
Define an optional cache-coherent entity-retraction transaction function that removes both EACL relationship halves with native target syntax and no mutation envelope.
## Requirements
### Requirement: Native target invocation
An installed named safe-retraction function SHALL be invocable with the backend's ordinary retractEntity-style target argument and no caller-supplied mutation envelope, random mutation ID, issue time, or retention metadata.

#### Scenario: Numeric eid
- **WHEN** a consumer transacts `[:eacl.fn/retractEntity target-eid]`
- **THEN** the function treats the numeric eid as the entity and ghost-repair key

#### Scenario: Lookup reference
- **WHEN** a consumer transacts `[:eacl.fn/retractEntity lookup-ref]` and the lookup ref resolves in transaction-start state
- **THEN** the function retracts that resolved entity safely

#### Scenario: Missing lookup reference
- **WHEN** a lookup ref does not resolve in transaction-start state
- **THEN** the function returns no transaction data and does not attempt global repair

### Requirement: Complete endpoint-pair cleanup
The function SHALL retract the target entity with native entity-retraction semantics and SHALL retract every discoverable peer half of an EACL relationship that names any entity in the native component-deletion closure rooted at the target.

#### Scenario: Live subject target
- **WHEN** the target stores forward relationship halves
- **THEN** matching reverse halves are retracted from their resource entities in the same transaction

#### Scenario: Live resource target
- **WHEN** the target stores reverse relationship halves
- **THEN** matching forward halves are retracted from their subject entities in the same transaction

#### Scenario: Self relationship
- **WHEN** both halves of a relationship are stored on the target entity
- **THEN** native entity retraction removes them without requiring a peer operation

#### Scenario: Native entity semantics
- **WHEN** the target contains application attributes, references, or component relationships handled by native retractEntity
- **THEN** the EACL function preserves the backend's native entity-retraction semantics in addition to EACL peer cleanup

#### Scenario: Component descendant has relationships
- **WHEN** native entity retraction cascades from the target to a component descendant that participates in EACL relationships
- **THEN** the function cleans the descendant's peer halves and stamps every affected relation in the same transaction

#### Scenario: Protected EACL control entity
- **WHEN** the resolved target or any component descendant is an EACL schema singleton, relation, permission, or other EACL control entity
- **THEN** the function aborts with a stable typed error and directs the caller to the supported schema writer

### Requirement: Known-retracted-eid repair
The function SHALL accept a numeric eid whose entity datoms have already been retracted and SHALL remove surviving EACL peer tuples that still name that eid.

#### Scenario: Ghost reverse half after bare subject retraction
- **WHEN** a prior bare retractEntity removed the subject but a resource still stores its reverse tuple and the numeric subject eid is supplied
- **THEN** the function retracts the ghost reverse tuple

#### Scenario: Ghost forward half after bare resource retraction
- **WHEN** a prior bare retractEntity removed the resource but a subject still stores its forward tuple and the numeric resource eid is supplied
- **THEN** the function retracts the ghost forward tuple

#### Scenario: Numeric eid with no remaining peer tuples
- **WHEN** the supplied numeric eid has no entity datoms or matching peer tuples
- **THEN** the invocation is an idempotent no-op

#### Scenario: Corrupt peer-only ghost on a live target
- **WHEN** a live target has no local tuple half for a pre-existing corrupt peer-only ghost
- **THEN** repair of that unrelated integrity violation is outside live target-scoped discovery and remains available through explicit integrity repair; known-retracted numeric repair still searches schema-indexed peer values

### Requirement: Relation-stamp-only publication
Every distinct relation whose tuple is retracted SHALL receive its current transaction generation atomically, and safe retraction SHALL NOT update a mutation graph, journal, anchor, or relation mutation ID.

#### Scenario: Several tuples in one relation
- **WHEN** the target participates in several relationships using one relation
- **THEN** the transaction publishes one idempotent relation-generation value for that relation

#### Scenario: Several affected relations
- **WHEN** cleanup removes tuples belonging to several relations
- **THEN** every affected relation and only those relations receives the transaction generation

#### Scenario: Managed cached grant
- **WHEN** a cached authorization result depends on an affected relation
- **THEN** the post-transaction managed proof differs and the stale result cannot be returned

### Requirement: Multiple invocation composition
Multiple safe-retraction invocations SHALL compose in one application transaction, including invocations affecting the same relation.

#### Scenario: Two targets in one transaction
- **WHEN** a consumer submits `[:eacl.fn/retractEntity eid-1]` and `[:eacl.fn/retractEntity eid-2]` together
- **THEN** both targets and their peer tuples are retracted atomically without a shared graph-head CAS conflict

#### Scenario: Same relation touched by both targets
- **WHEN** both invocations retract tuples of the same relation
- **THEN** their identical current-transaction relation stamp composes without conflict

#### Scenario: Relationship additions in the same transaction
- **WHEN** an application combines safe retraction with separate relationship additions involving a deleted target in the same transaction
- **THEN** this ordering-dependent mixture is rejected or documented as unsupported because transaction functions discover from transaction-start state; multiple safe-retraction invocations remain supported

### Requirement: Bounded discovery strategy
Live-target work SHALL be bounded by the native component-deletion closure plus the local endpoint degree of entities in that closure, while known-retracted-eid repair MAY perform work bounded by the stored relation schema plus matching ghost degree and SHALL NOT scan all permissioned entities.

#### Scenario: Live target
- **WHEN** the target entity still stores its endpoint halves
- **THEN** cleanup uses component-closure and endpoint-scoped reads and emits work linear in closure size, closure-local degree, and affected relations

#### Scenario: Already retracted numeric target
- **WHEN** the numeric target has no local endpoint halves
- **THEN** cleanup may enumerate relation definitions and perform exact peer-index probes for tuples naming that eid

#### Scenario: High-degree target
- **WHEN** one atomic cleanup would exceed documented transaction or serialized-writer limits
- **THEN** documentation directs consumers to the existing bounded batch deletion or repair workflow

### Requirement: Optional backend capability
Each backend SHALL report whether named or direct transaction functions can provide this behavior, and preparation or installation SHALL remain explicit and optional.

#### Scenario: Supported named function
- **WHEN** a backend can durably install and invoke the named function
- **THEN** its support descriptor reports named support and its installer is idempotent

#### Scenario: Supported direct function
- **WHEN** an embedded backend supports only an in-process direct function value
- **THEN** its support descriptor reports direct support and documents transport limitations

#### Scenario: Unsupported backend
- **WHEN** a backend cannot safely execute or transport a transaction function
- **THEN** it reports unsupported with a machine-readable reason and retains the portable deletion workflow

