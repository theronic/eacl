# forward-history-cache-coherence Specification

## MODIFIED Requirements

### Requirement: Exact-current generation isolation

The exact-current completed-answer tier SHALL admit and return an entry only
under a flat composite key containing the canonical immutable snapshot, source
lifecycle, and complete semantic request for which it was computed. A newer
current request MUST NOT observe an older exact entry. An explicit exact
request selecting an older snapshot MAY use the same count-bounded LRU tier
when its complete exact key remains resident; no nested generation store or
generation-recency policy is required.

#### Scenario: Any forward transaction advances the snapshot

- **WHEN** the selected current snapshot changes after any committed transaction
- **THEN** the new request constructs a different exact composite key and cannot observe the previous mapping

#### Scenario: Late old-generation publication

- **WHEN** computation against an old snapshot finishes after a newer snapshot is selected
- **THEN** its value remains keyed to the old canonical snapshot and cannot populate, replace, or masquerade as the newer key

#### Scenario: Same authenticated exact snapshot

- **WHEN** `:at-exact-snapshot` selects canonical snapshot `T` and a completed answer for the identical semantic request at `T` remains retained
- **THEN** EACL may return it without traversal or managed-proof reads
- **AND** rebuilds public snapshot metadata and tokens from the selected adapter at `T`

#### Scenario: Snapshot-exact retention is bounded

- **WHEN** LRU capacity evicts an exact answer
- **THEN** a later exact request recomputes on its selected immutable snapshot
- **AND** eviction does not imply snapshot or cursor expiry

### Requirement: Explicit lifecycle boundary

Cache correctness SHALL cover ordinary forward history only. Database restore,
reset, branch force, history replacement, source reseeding, or equivalent
replacement SHALL require the consumer to quiesce and expire or recreate
affected EACL clients before serving requests.

#### Scenario: Consumer restores a database

- **WHEN** a consumer restores or replaces the database history
- **THEN** the consumer expires or recreates the EACL client before resuming authorization traffic

#### Scenario: Backend can detect replacement

- **WHEN** a backend exposes reliable source-lifecycle replacement evidence
- **THEN** EACL may automatically rotate its cache lifecycle instead of requiring an explicit expiry call

#### Scenario: Long-running ordinary request

- **WHEN** an ordinary request holds an older immutable current database value after a newer transaction commits
- **THEN** it may use cache tiers captured from the same lifecycle that are valid for its complete exact or managed key

#### Scenario: Complete lifecycle rotation

- **WHEN** `expire-cache!` atomically installs a fresh client lifecycle
- **THEN** completed answers, subproblems, continuations, cursor codecs, derived schemas, diagnostics, and token/cursor source identity from the prior lifecycle become unreachable by new requests
- **AND** no externalized page-navigation store exists to rotate
