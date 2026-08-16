## MODIFIED Requirements

### Requirement: Exact-current generation isolation
The exact-current completed-answer tier SHALL admit and return an entry only for the canonical immutable snapshot generation, source lifecycle, and complete semantic request for which it was computed. A newer current request MUST NOT observe an older exact entry, while an explicit exact request selecting that older snapshot MAY use the entry through the separately bounded snapshot-exact retention path if it remains retained.

#### Scenario: Same authenticated exact snapshot
- **WHEN** `:at-exact-snapshot` selects canonical snapshot `T` and the exact tier contains a completed answer for the identical semantic request at `T`
- **THEN** EACL returns that answer without authorization traversal or managed-proof reads
- **AND** rebuilds public snapshot metadata and tokens from the selected adapter at `T`

#### Scenario: Different exact snapshot
- **WHEN** an exact-tier entry belongs to revision/locator `T1` and the request selects `T2`
- **THEN** the entry is ineligible even if its semantic answer would coincidentally be equal

#### Scenario: New current snapshot is selected
- **WHEN** a current request selects `T2` after an answer was computed at `T1`
- **THEN** it cannot use the `T1` snapshot-exact entry as an exact hit
- **AND** may use only separately certified managed proof-backed reuse

#### Scenario: Late historical publication
- **WHEN** computation at older snapshot `T1` finishes after current snapshot `T2` is installed
- **THEN** publication remains keyed to `T1` and cannot populate, replace, or masquerade as `T2`

#### Scenario: Snapshot-exact retention is bounded
- **WHEN** weight, entry, or admission bounds evict an exact answer or generation
- **THEN** a later exact request recomputes on its selected immutable snapshot
- **AND** eviction does not imply snapshot or cursor expiry

## ADDED Requirements

### Requirement: Exact requests never use managed cross-snapshot lifting
An `:at-exact-snapshot` request SHALL probe only completed answers bound to its identical canonical snapshot. It SHALL NOT use relation/schema proof equality to lift a managed answer computed at another revision, and SHALL NOT validate historical answers using current-only or no-history stamps.

#### Scenario: Managed answer has equal dependency proof
- **WHEN** a managed answer computed at `T2` has a proof equal to the exact request's proof at `T1`
- **THEN** the exact request does not use that managed entry unless a separate snapshot-exact entry exists for `T1`

#### Scenario: Exact cache miss
- **WHEN** no retained completed answer matches the selected exact snapshot and semantic request
- **THEN** EACL evaluates against the already selected exact adapter
- **AND** may publish the completed semantic answer into the snapshot-exact tier at that exact key

#### Scenario: Public response metadata
- **WHEN** a snapshot-exact completed answer is reused
- **THEN** response tokens, cursor envelopes, cache basis, external identifiers, and other public metadata are rebuilt from the request's selected exact adapter rather than copied from the cache entry

### Requirement: Exact cache identity is complete and lifecycle-scoped
The canonical snapshot-exact key SHALL include stable backend/source/branch identity, configured source lifecycle, native revision and exact locator, exact view kind, adapter fingerprint and identity contract, engine/order ABI, normalized semantic request, result kind/shape, normalized demand, and answer-affecting limits. Numeric revision equality alone SHALL NOT establish snapshot identity.

#### Scenario: Source lifecycle rotates
- **WHEN** cache expiry or history replacement installs a new source lifecycle
- **THEN** every old snapshot-exact entry becomes unreachable from new requests even when native revision numbers repeat

#### Scenario: Adapter semantics change
- **WHEN** adapter fingerprint, identity contract, engine ABI, order ABI, or an answer-affecting limit changes
- **THEN** an entry computed under the prior semantic identity is ineligible

#### Scenario: Arbitrary historical view
- **WHEN** a caller supplies a filtered, since, history, speculative, or otherwise uncertified database view
- **THEN** EACL does not identify it with an ordinary current/as-of exact generation merely because database id and numeric revision match
