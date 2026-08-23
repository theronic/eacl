## MODIFIED Requirements

### Requirement: Dependency-scoped continuation proofs
Cursor continuation identity SHALL be the request frame — certified schema generation and the scalar frontier over the query's complete relation closure — together with the lineage and the closure digest, consumed from the same request context values that completed answers and checkpoints use. Whole-database identity (`basis-t`, `max-tx`) SHALL NOT define cross-basis validity, and a transaction touching no relation in the closure and no schema SHALL NOT invalidate the continuation.

#### Scenario: Unrelated churn preserves continuation
- **WHEN** a cursor is issued, twenty transactions touching only relations outside the closure commit, and the cursor is resumed
- **THEN** the continuation decision is `:current`, and no fixed-point recomputation of prior pages occurs

#### Scenario: Relevant write triggers recovery
- **WHEN** a transaction touches a relation inside the closure before resumption
- **THEN** the continuation decision is exact fallback or the typed stale outcome, never reuse of the old boundary on the changed basis

#### Scenario: One frame per request
- **WHEN** a continued page validates its cursor, looks up a completed answer, and looks up a checkpoint
- **THEN** the adapter reads each relation generation at most once for that request

### Requirement: Unconditional schema-generation validation
Cursor acceptance SHALL compare the cursor's schema generation with the certified `:schema-generation` of the selected basis on every resumption, as part of frame equality. The schema generation SHALL be the actual schema mutation identity from the certified operation, not a proxy derived from `basis-t` or inferred from the dependency frontier.

#### Scenario: Cursor across a schema change
- **WHEN** a cursor minted under schema generation G1 is resumed after `write-schema!` produced generation G2
- **THEN** the frames differ and continuation proceeds only through exact fallback or the typed stale outcome; it SHALL NOT resume the walk as if the schema were unchanged

#### Scenario: Frontier equal, schema different
- **WHEN** the dependency frontier is unchanged but the certified schema generation differs
- **THEN** the frames differ and current-basis continuation is rejected
