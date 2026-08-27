## ADDED Requirements

### Requirement: Continuation lifetime follows source durability
A cursor SHALL continue on another basis only within its lineage. For a source that persists its identity (Datomic, durable Datahike, Datalevin) a cursor minted before provider restart SHALL continue after restart when its frame is equal or its original basis is exactly selectable. For a source that cannot persist its identity (DataScript, in-memory Datahike, Datomic memory databases) a cursor SHALL be rejected for scope mismatch after the source is recreated, before any frame is read, even under the constant default lifecycle and a shared keyring. Exact selection SHALL remain an independent capability: a source MAY support proof-equivalent continuation, exact fallback, both, or neither.

#### Scenario: Datalevin provider restarts
- **WHEN** a Datalevin cursor is presented after the provider closes and reopens the same store
- **THEN** it continues on an equal-frame current basis or returns the typed stale outcome; it is not rejected for scope mismatch

#### Scenario: DataScript connection is recreated
- **WHEN** a DataScript cursor is presented to a new connection seeded with identical content and repeated revision numbers
- **THEN** EACL returns the invalid-cursor scope-mismatch outcome before reading any generation

#### Scenario: Durable proof without exact history
- **WHEN** a later basis has an equal frame but the source cannot reconstruct the original basis
- **THEN** current-basis continuation succeeds without claiming history

#### Scenario: Frame differs but exact history exists
- **WHEN** the frame differs and the original basis remains selectable under the request's freshness constraint
- **THEN** EACL continues on the original basis by identity
