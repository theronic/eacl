# cross-backend-revision-consistency Specification

## Purpose
Define backend-specific revision selection behind one authenticated consistency
surface without portable mutation graphs or hidden snapshot registries.

## Requirements

### Requirement: Consistency modes select one immutable snapshot
Consistency selection SHALL complete before cache validation or authorization.
Every engine operation, dependency read, proof read, cursor operation, and
response token for one request MUST use the selected immutable snapshot.

#### Scenario: Concurrent commit follows selection
- **WHEN** another transaction commits after snapshot selection
- **THEN** the in-flight request remains on its selected immutable value

#### Scenario: Minimize latency
- **WHEN** a caller requests `:minimize-latency`
- **THEN** the adapter selects an already available complete local snapshot without an authoritative-head wait
- **AND** cache validation, if enabled, is scoped to that selected snapshot

#### Scenario: Exact snapshot
- **WHEN** a caller requests `:at-exact-snapshot`
- **THEN** EACL authenticates source/lifecycle and exact locator before backend selection
- **AND** validates the selected adapter's exact revision and locator postconditions
- **AND** may probe only a matching canonical snapshot-exact completed answer

### Requirement: Fully consistent requires an authoritative selection barrier
For `:fully-consistent`, EACL SHALL select a snapshot containing every
authorization mutation complete at the backend's authoritative barrier when
selection began. An adapter lacking such a barrier MUST NOT advertise this
capability.

#### Scenario: Datomic fully consistent selection
- **WHEN** a Datomic caller requests `:fully-consistent`
- **THEN** EACL uses bounded zero-argument `d/sync`
- **AND** evaluates on the database value returned by that synchronization

#### Scenario: Datahike source has no head barrier
- **WHEN** a Datahike source exposes only its latest locally replicated value
- **THEN** it advertises minimize-latency behavior and rejects `:fully-consistent`

#### Scenario: DataScript connection head
- **WHEN** a DataScript caller requests `:fully-consistent`
- **THEN** EACL selects the current immutable value of the serialized local connection

### Requirement: Datomic causal and exact selection
The Datomic adapter SHALL use native database identity for source scope and
basis `t` as its causal order and exact locator. EACL-managed writes SHALL mint
tokens from the committed transaction report without graph-journal metadata.

#### Scenario: Datomic catches up to an at-least write
- **WHEN** the local Peer basis is below an authenticated same-source token `T`
- **THEN** EACL uses bounded `(d/sync conn T)` and verifies the selected basis is at least `T`

#### Scenario: Datomic exact token is ahead locally
- **WHEN** an authenticated same-source exact token `T` is ahead of the local Peer
- **THEN** EACL performs bounded targeted synchronization, verifies `basis >= T`, and evaluates `(d/as-of db T)`
- **AND** never evaluates the newer synchronized head as the exact answer

#### Scenario: Datomic exact token is already local
- **WHEN** local basis is at least exact token `T`
- **THEN** EACL skips synchronization and evaluates `(d/as-of local-db T)`

#### Scenario: Datomic history replacement
- **WHEN** restore, reset, excision, or equivalent history replacement can reinterpret an old `T`
- **THEN** operators quiesce traffic and rotate the shared source lifecycle before resuming

### Requirement: Datahike causal and exact selection
The Datahike adapter SHALL scope tokens by stable store identity, configured
branch, and source lifecycle. It SHALL advertise durable temporal exact history
only when `:keep-history? true`, and conditional retained-commit exact
selection only when a commit graph is available.

#### Scenario: Temporal history survives commit collection
- **WHEN** `:keep-history? true` and a named commit record has been collected
- **THEN** EACL reconstructs the exact revision through temporal `as-of`

#### Scenario: History-disabled commit is retained
- **WHEN** temporal history is disabled and the exact commit remains retained
- **THEN** EACL may load that exact commit

#### Scenario: History-disabled commit is collected
- **WHEN** temporal history is disabled and the exact commit was genuinely collected
- **THEN** EACL returns exact-snapshot unavailable rather than provider failure or a substituted head

#### Scenario: Branch or store history is replaced
- **WHEN** purge, cutoff history destruction, branch force, reset, or restore changes locator meaning
- **THEN** operators quiesce traffic and rotate the shared source lifecycle before resuming

### Requirement: DataScript is current-basis only
The DataScript adapter SHALL use connection-local immutable revisions for
current and causal selection and SHALL NOT advertise arbitrary exact history or
retain hidden historical database values to emulate it.

#### Scenario: DataScript satisfies a local causal floor
- **WHEN** the serialized connection head has revision at least the same-source token floor
- **THEN** EACL may select that current immutable value

#### Scenario: DataScript exact request
- **WHEN** a caller requests `:at-exact-snapshot`
- **THEN** EACL rejects the unsupported capability before cache access or authorization

#### Scenario: DataScript process or connection is replaced
- **WHEN** reset or replacement can reuse numeric revisions for different contents
- **THEN** the consumer rotates source lifecycle and EACL does not infer historical equality from revision numbers

### Requirement: Capability claims are configuration-specific
Every adapter SHALL advertise only consistency, authoritative-head,
causal-wait, durable-history, conditional-exact, and exact-reconstruction
guarantees supported by its configured source. Unsupported guarantees MUST fail
before cache access or authorization execution.

#### Scenario: Native revision values collide across lifecycle replacement
- **WHEN** clone, restore, reset, branch force, or reseeding reuses an order hint for different contents
- **THEN** lifecycle inequality prevents token, cursor, proof, or exact-cache reuse

#### Scenario: Completed-answer cache is disabled
- **WHEN** native revision selection is supported but completed-answer caching is disabled
- **THEN** EACL still issues and selects authenticated native revision tokens
