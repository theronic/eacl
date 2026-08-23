## ADDED Requirements

### Requirement: Datalevin managed reuse follows the shared frame rule
After an exact-basis miss, EACL SHALL reuse a Datalevin completed answer or managed subproblem under the shared lineage-scoped frame rule: equal persisted source identity and lifecycle, equal certified schema generation, and equal scalar dependency frontier over the complete closure, in either revision direction. The frame SHALL be read from the owned immutable read snapshot with one probe per requested relation and one for the schema generation, in the `max-tx` domain, and SHALL be subject to the shared ceiling assertion.

#### Scenario: Identical basis
- **WHEN** an identical cacheable request repeats on the same selected basis
- **THEN** EACL serves the exact entry without reading any relation generation

#### Scenario: Unrelated forward commit
- **WHEN** a later basis changes no relation in the answer's closure and no schema
- **THEN** the frames are equal and the answer is reused

#### Scenario: Relevant mutation
- **WHEN** a guarded transaction changes a relation in the closure
- **THEN** that relation's generation equals the committing `max-tx`, the frontier differs, and the old answer is not reused

#### Scenario: Missing generation
- **WHEN** a relation in the closure has no generation datom on the selected snapshot
- **THEN** the frame is unavailable and the request evaluates exactly without disabling managed lifting

### Requirement: Datalevin protected mutations are storage-enforced
Every transaction reaching a Datalevin store with an installed EACL write policy SHALL be checked after expansion and before commit. A datom on a guarded attribute without the writer's admission token, a relationship tuple datom without a relation-generation datom for the relation at tuple position 1, a definition or schema-string datom without a schema-generation datom on the exact persisted schema singleton, or an added commit-generation datom carrying a value other than the committing generation SHALL abort the transaction atomically. Frozen schema or database administration SHALL require the same per-open token through the synchronous administrative scope. A transaction that changes no guarded datom SHALL require nothing; a stamp without a corresponding change SHALL be allowed.

#### Scenario: Application retracts a permissioned object
- **WHEN** application code submits `:db/retractEntity` for an entity holding relationship tuples through any connection
- **THEN** the store rejects the transaction naming `delete-object!` and no tuple or stamp changes

#### Scenario: Writer omits a stamp
- **WHEN** an admitted transaction changes a tuple for a relation without stamping that relation
- **THEN** the store rejects the transaction before commit

#### Scenario: Writer stamps the wrong schema entity
- **WHEN** an admitted transaction changes a definition but stamps a different entity
- **THEN** the store rejects the transaction before commit

#### Scenario: Administrative bypass is attempted
- **WHEN** code calls schema update, clear, drop, or re-index against protected storage without the current open's token
- **THEN** the fork rejects the administrative mutation atomically

#### Scenario: Batch changes several relations
- **WHEN** one admitted transaction adds or retracts tuples for several relations
- **THEN** every affected relation's generation equals the committing `max-tx` on the resulting snapshot

#### Scenario: Application writes its own data
- **WHEN** a transaction touches only application attributes, including `:eacl/id` on a new entity
- **THEN** the policy imposes no requirement and the transaction commits normally

### Requirement: Datalevin stamps are scalar commit generations
Datalevin SHALL store the schema generation, schema write fence, and relation generations as `:db.type/long` cardinality-one values materialized from `:db/current-tx` by the fork without allocating an entity id. Reference-typed stamps MUST NOT be installed or read.

#### Scenario: Entity with a coinciding id is retracted
- **WHEN** an entity whose id equals a previous generation value is retracted
- **THEN** no stamp changes

#### Scenario: Stamping does not allocate
- **WHEN** a guarded transaction materializes generations
- **THEN** `:max-eid` is unchanged by the stamp datoms

### Requirement: Datalevin same-process stale connections recover narrowly
When a distinct connection atom sharing the local Store prepares an EACL write
from an older immutable wrapper, the module SHALL refresh that wrapper and
retry the complete semantic operation under a fixed bound. Only the typed
stale-connection-generation result SHALL be retryable; CAS contention,
cross-process continuity failure, policy rejection, cancellation, deadline,
and unknown storage errors SHALL remain terminal.

#### Scenario: Another local connection commits first
- **WHEN** one connection advances the shared Store before another submits a correctly planned EACL mutation
- **THEN** the stale submission commits after bounded refresh without weakening stamp equality

#### Scenario: Semantic CAS loses
- **WHEN** the write-fence compare-and-set reports contention
- **THEN** EACL returns the typed concurrent-schema-write outcome without retrying it as connection staleness
