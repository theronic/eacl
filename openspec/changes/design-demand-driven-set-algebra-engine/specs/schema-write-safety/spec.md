## ADDED Requirements

### Requirement: Operator schemas are validated before persistence
Schema writing SHALL parse, resolve, type-check, bound, canonicalize, build signed dependencies, classify strongly connected components, and assign strata for the complete candidate schema before any mutation. Validation SHALL report deterministic typed issues and SHALL leave the prior schema and generation unchanged on any failure.

#### Scenario: Multiple invalid operator definitions
- **WHEN** a candidate schema contains an unresolved operand, an oversized expression, and a negative dependency cycle
- **THEN** validation reports deterministic issues and stores none of the candidate schema

### Requirement: Permission expressions use a closed bounded encoding
Each permission SHALL have one versioned canonical expression payload with a root, closed node tags, canonical digest, node count, maximum depth, direct fan-in, and encoded-byte size. Unknown versions, tags, fields, operand shapes, non-canonical values, excessive source size, excessive normalized DAG size, excessive child slots, or excessive portable checkpoint weight MUST be rejected before plan construction or unbounded allocation.

#### Scenario: Nested fan-in bypass attempt
- **WHEN** individually small nested intersections flatten beyond a normalized child-slot or checkpoint-byte limit
- **THEN** schema writing fails with the corresponding typed expression-limit error

### Requirement: Permission storage has one expression representation
Every permission, including a union-only permission, SHALL be stored only as its canonical expression payload. The unreleased v8 implementation SHALL NOT add a legacy flat projection, compatibility reader, migration, or dual-write path. A reader SHALL reject flat-only, mixed, duplicated, or conflicting permission storage rather than synthesize an expression.

#### Scenario: Union-only schema is installed cleanly
- **WHEN** a valid union-only source schema is written to a clean expression-capable v8 database
- **THEN** its canonical expression compiles to the unchanged union-only execution domain and public denotation

#### Scenario: Flat or mixed permission rows are present
- **WHEN** a snapshot contains flat-only or mixed flat-and-expression permission storage
- **THEN** the snapshot is rejected as incompatible unreleased-v8 storage rather than interpreted or migrated

### Requirement: Negative recursion is rejected atomically
The schema validator SHALL reject every strongly connected component containing an exclusion-right dependency and SHALL identify a reproducible cycle, negative edge, and affected permission names without exposing backend entity identifiers.

#### Scenario: Double-negative recursive cycle
- **WHEN** a recursive dependency cycle crosses two exclusion-right edges
- **THEN** the schema is rejected even if a Boolean rewrite of one finite example appears monotone
