## MODIFIED Requirements

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

## ADDED Requirements

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

#### Scenario: Arbitrary historical view
- **WHEN** a caller supplies a filtered, since, history, speculative, or otherwise uncertified database view
- **THEN** EACL does not identify it with an ordinary current/as-of exact generation merely because source and numeric revision match
