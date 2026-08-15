## ADDED Requirements

### Requirement: Authenticated causal revision tokens
EACL SHALL encode every revision token as an opaque, versioned, authenticated envelope bound to one backend source scope and containing a retained EACL graph mutation anchor. A token MAY also contain a backend order hint and exact snapshot locator. EACL MUST reject tampered, expired, cross-source, cross-branch, and unknown-version tokens before authorization or cache access.

#### Scenario: Managed write returns a causal token
- **WHEN** a managed EACL graph write commits
- **THEN** its response token contains the mutation id committed atomically with the write
- **AND** derives its order hint and exact locator from committed `db-after`

#### Scenario: Token is replayed against another source
- **WHEN** a valid token is presented to an incompatible backend, native database/store identity, or scoped branch
- **THEN** EACL rejects it with a typed token-scope error

#### Scenario: Token authentication fails
- **WHEN** any authenticated token field is modified
- **THEN** EACL rejects the token without selecting a snapshot or consulting the completed-answer cache

### Requirement: At-least freshness requires causal dominance
For `:at-least-as-fresh`, EACL SHALL select exactly one immutable snapshot that contains the authenticated token's retained graph mutation anchor. A numeric basis, `:max-tx`, commit timestamp, database id, or branch name MUST NOT establish freshness without the causal postcondition.

#### Scenario: Selected snapshot contains the anchor
- **WHEN** a same-scope snapshot contains the token mutation anchor
- **THEN** EACL may use that snapshot for dependency validation or authorization
- **AND** returns a token for the selected snapshot's graph head

#### Scenario: Numeric position is newer but history diverged
- **WHEN** a snapshot's order hint is greater than or equal to the token hint but the token mutation anchor is absent
- **THEN** EACL returns a typed history-diverged or token-expired error
- **AND** MUST NOT evaluate as though the freshness floor were satisfied

#### Scenario: Source is initially behind
- **WHEN** the first observed snapshot lacks the token anchor and the backend can refresh or wait
- **THEN** EACL waits within the configured deadline and rechecks anchor membership on each candidate complete snapshot

#### Scenario: Freshness deadline expires
- **WHEN** no qualifying snapshot is selected before the deadline
- **THEN** EACL returns `:eacl.consistency/freshness-unavailable`
- **AND** includes safe requested/observed diagnostic data

### Requirement: Mutation anchors have bounded retention
EACL SHALL authenticate token expiry and MUST retain mutation anchors for at least the maximum accepted token lifetime plus a conservative cleanup grace interval. A deployment supporting non-expiring tokens SHALL retain their anchors indefinitely.

#### Scenario: Anchor is within token lifetime
- **WHEN** an unexpired token is valid for the source scope
- **THEN** the source retains enough mutation-journal state to test causal dominance

#### Scenario: Anchor retention has ended
- **WHEN** a token or its retained anchor is outside the supported lifetime
- **THEN** EACL returns a typed token-expired error rather than numeric fallback

### Requirement: Causal modes require complete writer authority
EACL SHALL issue read tokens representing selected authorization state and advertise `:at-least-as-fresh` only when every writer of schema, relationships, mutable identity mappings, caveat inputs, and custom adapter data that can affect authorization publishes an atomic mutation anchor. Full-content proof MUST NOT be treated as a replacement for missing causal history.

#### Scenario: All authorization writers participate
- **WHEN** every authorization-relevant mutation path publishes the v3 journal/head/dependency metadata atomically
- **THEN** EACL may issue causal read/write tokens and advertise at-least selection

#### Scenario: Raw authorization writer is possible
- **WHEN** an in-scope writer can change authorization-relevant data without publishing a mutation anchor
- **THEN** EACL MAY use full-content proof for current-snapshot cache safety
- **AND** MUST NOT issue a token claiming that unjournaled state or advertise causal at-least semantics for that source

#### Scenario: Mutable object identity participates
- **WHEN** object-id mapping data can change the meaning or ordering of authorization results
- **THEN** those writes advance the graph mutation journal and relevant identity proof

### Requirement: Consistency modes select one immutable snapshot
Consistency selection SHALL complete before cross-revision cache validation or authorization. Every engine operation, dependency read, proof read, cursor operation, and response token for one request MUST use the selected immutable snapshot.

#### Scenario: Concurrent commit follows selection
- **WHEN** another transaction commits after snapshot selection
- **THEN** the in-flight request remains on its selected immutable value

#### Scenario: Minimize latency
- **WHEN** a caller requests `:minimize-latency`
- **THEN** the adapter selects an already available complete local snapshot without an authoritative-head wait
- **AND** the completed-answer cache is validated on that snapshot

#### Scenario: Exact snapshot
- **WHEN** a caller requests `:at-exact-snapshot`
- **THEN** EACL loads only the token's exact locator and verifies its source scope and graph-head identity
- **AND** returns snapshot-expired if that exact value is unavailable

### Requirement: Fully consistent requires an authoritative selection barrier
For `:fully-consistent`, EACL SHALL select a snapshot containing every graph mutation complete at the backend's authoritative barrier when selection began. An adapter lacking such a barrier MUST NOT advertise this capability.

#### Scenario: Datomic fully consistent selection
- **WHEN** a Datomic caller requests `:fully-consistent`
- **THEN** EACL uses bounded zero-argument `d/sync`
- **AND** evaluates on the database value returned by that synchronization

#### Scenario: Datahike source is a lagging replica
- **WHEN** a Datahike source can expose only its latest locally replicated value and no writer/store head barrier
- **THEN** it advertises latest-observed/minimize-latency behavior
- **AND** rejects `:fully-consistent` as unsupported

#### Scenario: DataScript connection head
- **WHEN** a DataScript caller requests `:fully-consistent`
- **THEN** EACL selects the current immutable value of the serialized local connection

### Requirement: Datomic causal selection
The Datomic adapter SHALL use native database identity for source scope, basis `t` as an order hint and exact locator, and the EACL mutation journal as the causal postcondition. Managed writes MUST mint tokens from the committed transaction report.

#### Scenario: Datomic catches up to a write
- **WHEN** the local Peer basis is below a same-source token hint
- **THEN** EACL uses bounded two-argument `d/sync`
- **AND** verifies that the resulting database contains the token mutation anchor

#### Scenario: Datomic restore reuses transaction positions
- **WHEN** a restored/divergent database later reaches a numeric basis at or above an old token but lacks its mutation id
- **THEN** EACL rejects the old token for that history

#### Scenario: Datomic exact snapshot is available
- **WHEN** a valid exact token names a retained Datomic basis
- **THEN** EACL selects `d/as-of` at that basis and verifies the expected graph head

### Requirement: Datahike causal selection
The Datahike adapter SHALL scope tokens by stable store identity and configured branch, use mutation-anchor membership for causal freshness, and use commit id as an exact locator when available. It MUST treat `:max-tx` only as an order/polling hint.

#### Scenario: Datahike branch head advances normally
- **WHEN** a reader refreshes to a branch head containing the token mutation id
- **THEN** that immutable database satisfies the at-least floor

#### Scenario: Datahike branch is force-moved
- **WHEN** `force-branch!` publishes a head whose `:max-tx` is equal to or newer than a token hint but whose graph lacks the token mutation anchor
- **THEN** EACL rejects that head as not causally fresh enough

#### Scenario: Datahike commit is retained
- **WHEN** an exact token's commit id is retained
- **THEN** the adapter loads it with `commit-as-db` and verifies source and graph identity

#### Scenario: Datahike commit is unavailable
- **WHEN** commit reconstruction and capability-gated temporal fallback cannot recover the exact value
- **THEN** EACL returns `:eacl.consistency/snapshot-expired`

### Requirement: DataScript causal selection
The DataScript adapter SHALL use a durable random causal-family id for source scope, mutation-anchor membership as its causal postcondition, and immutable DB `:max-tx` only as an order hint. It MUST NOT infer common history from equal family ids or transaction numbers.

#### Scenario: DataScript connection contains the anchor
- **WHEN** the current immutable DB contains the token's mutation id
- **THEN** EACL may select it even when later unrelated transactions advanced `:max-tx`

#### Scenario: DataScript process restarts
- **WHEN** a restored connection has the token's durable causal-family id and contains its mutation anchor
- **THEN** the token remains valid across the process/connection restart

#### Scenario: DataScript clone predates token
- **WHEN** a cloned DB copied the family id before the token mutation
- **THEN** the missing anchor prevents that clone from satisfying the token

#### Scenario: DataScript reset creates a numeric collision
- **WHEN** `reset-conn!` installs a divergent DB with the same or greater `:max-tx` but without the token anchor
- **THEN** EACL returns history-diverged or freshness-unavailable

#### Scenario: DataScript cannot catch up
- **WHEN** the supplied connection does not acquire the required mutation anchor before the deadline
- **THEN** EACL returns `:eacl.consistency/freshness-unavailable`
- **AND** does not claim a replication mechanism

#### Scenario: DataScript exact value is not retained
- **WHEN** neither the current DB nor the bounded immutable snapshot registry contains an exact token handle
- **THEN** EACL returns snapshot-expired regardless of numeric `:max-tx` equality

### Requirement: Capability claims are configuration-specific
Every adapter SHALL advertise only consistency, authoritative-head, causal-wait, and exact-reconstruction guarantees supported by its configured source. Unsupported guarantees MUST fail before authorization execution with `:eacl/unsupported-capability`.

#### Scenario: Cross-backend at-least conformance
- **WHEN** the shared contract presents a token to Datomic, Datahike, or DataScript
- **THEN** the backend either selects a same-scope snapshot containing the token anchor or returns the specified typed failure

#### Scenario: Backend order hint collides
- **WHEN** a generated clone, restore, reset, branch, or force-head trace reuses an order hint for different contents
- **THEN** no adapter accepts equality or numeric ordering as a substitute for mutation-anchor membership
