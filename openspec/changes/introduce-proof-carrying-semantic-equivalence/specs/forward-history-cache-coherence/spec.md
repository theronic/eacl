## RENAMED Requirements

- FROM: `### Requirement: Historical bases never use managed cross-snapshot lifting`
- TO: `### Requirement: Managed lifting requires a readable frame in one lineage`

## MODIFIED Requirements

### Requirement: Complete scalar dependency proof
For admissible bases with readable, contract-valid frames in one lineage, managed reuse SHALL require equal lineage (source scope and source lifecycle), semantic request identity, result shape, certified schema generation, and scalar dependency frontier. The complete canonical dependency closure SHALL be a deterministic function of equal schema semantics and normalized semantic request. The frontier SHALL be the maximum relation generation over every relation in that closure, or the distinguished initial value when the closure is empty. Generations SHALL be expressed in the same numeric domain as the basis revision, and every generation visible at a basis SHALL be less than or equal to that basis's revision. A proof SHALL be valid only when every supported relevant mutation stamps every affected relation atomically with the committed revision of its own transaction, which is greater than every generation visible before it. The rule SHALL hold in both directions of one lineage: equal frames at two bases imply equal protected semantics regardless of which basis is older.

#### Scenario: Unrelated transaction
- **WHEN** a transaction changes no relation in the complete dependency closure and does not change authorization schema
- **THEN** the schema generation and dependency frontier remain equal and the managed answer may be reused

#### Scenario: Relevant relationship mutation
- **WHEN** a supported transaction changes any relation in the complete dependency closure
- **THEN** the transaction stamps that relation with its committed revision, the frontier at every later basis is greater, and the previous managed answer cannot match

#### Scenario: Several relevant relations change atomically
- **WHEN** one supported transaction changes several dependency relations
- **THEN** every affected relation receives the same committed revision and the frontier advances once

#### Scenario: Older retained basis
- **WHEN** a request at an older basis of the same lineage reads a frame equal to an entry computed at a newer basis
- **THEN** the entry's value equals exact evaluation at the older basis and may be returned

#### Scenario: Empty dependency closure
- **WHEN** the complete authorization dependency closure contains no relationship relation
- **THEN** EACL uses the distinguished initial frontier together with the schema generation rather than treating proof as unavailable

### Requirement: Complete proof availability
EACL SHALL classify each frame read as complete, unavailable, or a contract violation. Unavailable covers an adapter without the proof operation, an uninitialized generation for a relation in the closure, a closure above the configured relation bound, and a transient adapter failure; it SHALL produce exact evaluation on the selected basis, no managed publication, and a counted reason, and SHALL NOT be sticky. A contract violation covers a malformed frame, duplicate or non-canonical relation ids, a non-integer generation, and a generation above the selected basis's revision; it SHALL produce exact evaluation for the request, sticky disablement of managed lifting for the runtime until `expire-cache!`, a counted reason, and a report through the optional diagnostic reporter. Neither class SHALL change authorization availability, substitute an initial generation, or use partial evidence.

#### Scenario: Uninitialized relation generation
- **WHEN** a relation in the closure has no generation on the selected basis
- **THEN** the frame is unavailable and the next request reads again

#### Scenario: Generation above the revision
- **WHEN** an adapter returns a generation greater than the selected basis's revision
- **THEN** the frame is a contract violation, the request evaluates exactly, and managed lifting is disabled for the runtime

#### Scenario: Cache is disabled
- **WHEN** a consumer selects the no-cache implementation or disables caching for one request
- **THEN** EACL performs no completed-answer cache lookup or publication


### Requirement: Managed lifting requires a readable frame in one lineage
A request at any admissible basis — ordinary, captured, loaded by exact locator, supplied directly, or as-of — MAY use managed lifting when its frame is readable: an exact-integer revision in the stamp domain, every closure generation present, the schema generation present, and every generation `<= revision`. A basis whose frame is unavailable SHALL probe only exact answers bound to its identical basis identity and SHALL publish only at that exact key. Basis kind SHALL NOT by itself exclude a basis from lifting, and EACL MUST NOT validate any answer using stamps read through a view that cannot expose them.

#### Scenario: As-of basis with retained stamp history
- **WHEN** a Datomic `as-of` basis at `t` reads its frame after stamp history is retained and an index job has run
- **THEN** the frame equals the stamps current at `t` and a managed answer with an equal frame in the same lineage may be returned

#### Scenario: Frame unavailable at a basis
- **WHEN** a basis's revision is not an exact integer in the stamp domain or a closure generation is absent
- **THEN** the request uses only exact answers for that identical basis and evaluates otherwise

#### Scenario: Reader Peer changes session
- **WHEN** a reader selects a new session basis by exact token after unrelated writes
- **THEN** managed answers computed in the previous session with equal frames are reused

#### Scenario: Public response metadata
- **WHEN** an exact or managed answer is reused
- **THEN** tokens, cursor envelopes, cache basis, external identifiers, and other public metadata are rebuilt from the selected basis
