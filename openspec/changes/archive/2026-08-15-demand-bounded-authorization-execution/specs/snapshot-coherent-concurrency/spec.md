## ADDED Requirements

### Requirement: One selected immutable snapshot is the sole semantic source
Every authorization request SHALL select exactly one immutable database value
before deriving schema, plans, dependency proofs, identities, cache generation,
or traversal state. All semantic reads, externalization, cursor validation, and
response-token issuance for that request MUST derive from that selected value.

#### Scenario: Commit after selection
- **WHEN** a schema or relationship mutation commits after request snapshot `S0` was selected
- **THEN** the in-flight request completes entirely against `S0`
- **AND** a later request selects the committed snapshot

#### Scenario: Historical or arbitrary DB evaluation
- **WHEN** a backend explicitly selects a supported historical value or a raw API receives an arbitrary immutable DB
- **THEN** schema and plans are derived from that value
- **AND** current-client mutable state cannot change its meaning

### Requirement: Authorization acquires no EACL blocking schema lock
EACL SHALL execute authorization and read operations without an EACL-owned
blocking schema coordination primitive.
Authorization point, lookup, count, schema-read, and relationship-read
operations MUST NOT acquire an EACL-owned mutual-exclusion lock, read/write
lock, monitor, or semaphore whose availability depends on another request or
schema operation. Snapshot selection and atomic cache references MAY use
nonblocking runtime primitives.

#### Scenario: Long recursive read and schema write
- **WHEN** a recursive authorization request continues on `S0` while `write-schema!` commits `S1`
- **THEN** neither operation waits for an EACL schema lock held by the other
- **AND** each operation remains correct for its own transaction/snapshot boundary

#### Scenario: Concurrent readers
- **WHEN** many authorization reads use one client
- **THEN** no read waits for another read's schema, cache, or traversal critical section

### Requirement: Derived schema state is generation-scoped
EACL SHALL scope every derived schema artifact to the immutable snapshot proof
from which it was derived.
Schema catalogs, permission paths, recursive plans, dependency closures, and
derived memos SHALL be keyed by backend/source identity, the selected
snapshot's schema proof, and every answer-affecting engine/adapter ABI. A client
MUST NOT bind a latched current schema cache to a separately selected DB value.

#### Scenario: Out-of-band schema commit
- **WHEN** another client or raw supported writer commits schema generation `G1`
- **THEN** the next request selecting `G1` resolves a `G1`-keyed plan
- **AND** cannot use a `G0` plan because a local invalidation callback was missed

#### Scenario: Late old-plan publication
- **WHEN** an `S0/G0` request publishes after `G1` becomes current
- **THEN** the publication remains keyed to or reachable only from `G0`
- **AND** cannot replace the current `G1` generation

### Requirement: Writer preconditions come from the calculation snapshot
Every EACL schema or relationship write SHALL calculate its complete
transaction against one immutable snapshot `S0` and include backend-atomic
preconditions whose expected schema, relation, or mutation identities were
captured from `S0`. The implementation MUST NOT reread a newer expected head
after calculating a stale delta.

#### Scenario: Concurrent schema replacements
- **WHEN** two writers calculate different replacement deltas from the same schema generation
- **THEN** at most one stale-base transaction commits
- **AND** the loser retries from a new snapshot or returns a typed concurrent-write error
- **AND** the stored schema is one complete replacement rather than a union

#### Scenario: Delayed stale schema submission
- **WHEN** writer B calculates from `S0`, writer A commits `S1`, and B enters transaction submission only after `S1` is visible
- **THEN** B still asserts the expectation captured from `S0`
- **AND** cannot adopt `S1`'s head while submitting its `S0` delta

#### Scenario: Idempotent retry
- **WHEN** transaction outcome is ambiguous and the operation is retryable
- **THEN** EACL reuses the mutation identity and verifies the committed canonical intent
- **AND** never recomputes under a new base while pretending it is the same transaction
- **AND** retry attempts are bounded by a finite configured maximum and the one request deadline

### Requirement: Schema-removal races fail atomically
A relationship or object mutation resolved under schema generation `G0` MUST
carry a commit-time guard preventing it from committing after its required
relation was removed or changed. A schema removal MUST revalidate relevant
relationship absence at commit or conflict with every intervening EACL graph
mutation capable of invalidating its preflight.

#### Scenario: Tuple creation races relation removal
- **WHEN** a relationship is created after schema-removal preflight but before schema commit
- **THEN** either the relationship transaction or schema transaction fails its commit-time guard
- **AND** no committed snapshot contains a tuple whose relation definition was removed

#### Scenario: Stale relationship endpoint
- **WHEN** endpoint or relation resolution changes after `S0`
- **THEN** the stale relationship transaction conflicts and re-resolves from a new snapshot before any retry

### Requirement: Local invalidation affects performance only
EACL SHALL preserve authorization correctness independently of local
invalidation delivery or cleanup timing.
Schema-write cleanup, listeners, and cache-lifecycle swaps MAY eagerly detach
old generations, but missed, duplicate, delayed, or concurrent invalidation
events MUST NOT affect authorization correctness.

#### Scenario: Missed cleanup callback
- **WHEN** an old generation remains retained after a schema commit
- **THEN** generation keys prevent it from serving the new snapshot
- **AND** the consequence is limited to temporary memory retention
