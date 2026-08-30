# formally-verified-authorization-engine Specification

## MODIFIED Requirements

### Requirement: Proven cache observational equivalence

The verified kernel SHALL model every shared cache tier as an arbitrary bounded
partial map and mechanically prove that every accepted hit equals fresh
evaluation of the same semantic request on the selected immutable snapshot.
Exact entries SHALL be accepted only under a composite key containing the
identical immutable selected-basis identity. Managed entries SHALL additionally
use a key containing the same schema generation and complete relevant relation
dependency proof under the explicit forward stamped-writer contract. Arbitrary
eviction, disabled caching, or a lost publication race SHALL refine a miss and
MUST NOT alter authorization semantics. Library LRU order and atomic scheduling
are host-boundary tests, not proof inputs.

#### Scenario: Exact cache hit

- **WHEN** a valid cache entry is found under the complete identical exact composite key
- **THEN** EACL may return its value and the value equals fresh evaluation

#### Scenario: Managed unrelated-write reuse

- **WHEN** a valid entry was computed before an unrelated forward transaction and the composite key's schema generation plus complete relevant relation dependency proof are unchanged
- **THEN** EACL may return the entry and the least-fixed-point frame theorem establishes equality with selected-snapshot recomputation

#### Scenario: Exact or arbitrary snapshot

- **WHEN** a public exact-snapshot operation selects a historical basis with a complete supported exact identity
- **THEN** EACL may reuse only the entry under that identical exact composite key
- **AND** an arbitrary low-level database value outside that contract bypasses completed-answer lookup and publication

#### Scenario: Incomplete managed stamp

- **WHEN** EACL cannot obtain one valid current dependency identity for every compiled relevant relation dependency
- **THEN** it rejects managed reuse and computes from the selected snapshot while exact reuse remains independently sound

#### Scenario: Lifecycle expiry race

- **WHEN** an in-flight computation publishes after explicit client cache expiry
- **THEN** publication can reach only the captured old lifecycle and cannot repopulate the new lifecycle

#### Scenario: Arbitrary eviction

- **WHEN** a valid mapping is absent for any retention-policy reason
- **THEN** EACL follows the cache-independent miss path and returns an observationally equivalent outcome
