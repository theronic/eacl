## ADDED Requirements

### Requirement: Operator schemas are validated before persistence
Schema writing SHALL parse, resolve, type-check, bound, canonicalize, build signed dependencies, classify strongly connected components, and assign strata for the complete candidate schema before any mutation. Validation SHALL report deterministic typed issues and SHALL leave the prior schema and generation unchanged on any failure.

#### Scenario: Multiple invalid operator definitions
- **WHEN** a candidate schema contains an unresolved operand, an oversized expression, and a negative dependency cycle
- **THEN** validation reports deterministic issues and stores none of the candidate schema

### Requirement: Permission expressions use a closed bounded encoding
Each permission SHALL have one versioned canonical expression payload with a root and closed node tags. The payload's format tag is authoritative; separate format, expression-digest, and policy-digest attributes are forbidden as redundant durable schema. Node count, maximum depth, direct fan-in, encoded-byte size, normalized DAG size, child slots, words, checkpoint weight, aggregate dimensions, admission limits, and relationship cardinalities are derived or client-local values and MUST NOT be stored as durable permission attributes. Unknown versions, tags, fields, operand shapes, non-canonical values, excessive source size, excessive normalized DAG size, excessive child slots, or excessive portable checkpoint weight MUST be rejected before plan construction or unbounded allocation.

#### Scenario: Nested fan-in bypass attempt
- **WHEN** individually small nested intersections flatten beyond a normalized child-slot or checkpoint-byte limit
- **THEN** schema writing fails with the corresponding typed expression-limit error

### Requirement: V8 permission storage has one authoritative expression representation
Every v8 permission, including a union-only permission, SHALL be stored only as its authoritative identity and canonical versioned payload. A v8 authorization reader SHALL reject flat-only, mixed, duplicated, or conflicting permission storage rather than synthesize an expression during an ordinary read. Retired experimental format, digest, policy, and metric attributes, when already installed in a development database, SHALL be ignored and SHALL receive no new assertions.

#### Scenario: Union-only schema is installed cleanly
- **WHEN** a valid union-only source schema is written to a clean expression-capable v8 database
- **THEN** its canonical expression compiles to the unchanged union-only execution domain and public denotation

#### Scenario: Flat or mixed permission rows reach a v8 ordinary read
- **WHEN** a snapshot contains flat-only or mixed flat-and-expression permission storage outside the explicit released-v7 upgrade operation
- **THEN** the snapshot is rejected rather than interpreted or migrated implicitly

### Requirement: Derived expression metrics are recomputed and generation-cached
The reader SHALL derive and enforce source, encoding, normalized-DAG, and aggregate dimensions from the hard-bounded canonical payload under the client's immutable effective limits before sealing. Completed structural results MAY be cached within that client by schema generation, authoritative expression fields, and effective limits. An explicit structural refresh SHALL evict and exactly recompute the cache from authoritative payloads without scanning relationship storage.

#### Scenario: Retired metric datoms disagree
- **WHEN** a database contains stale retired experimental metric datoms beside an otherwise valid canonical payload
- **THEN** v8 ignores those datoms, recomputes the metrics from the payload, and writes no replacement metric datoms

### Requirement: Expression admission limits are client-local
EACL SHALL provide calibrated default expression and aggregate admission limits as client-construction configuration. Each client MAY select a different checked profile within hard implementation ceilings. Those limits MUST NOT be transacted, read from permission entities, or require peer coordination. A client SHALL apply its own profile on every cold schema decode and schema write before sealing or mutation.

#### Scenario: Peers use different admission profiles
- **WHEN** one peer's configured maximum expression depth accepts a canonical payload and another peer's stricter maximum rejects it
- **THEN** the first peer may evaluate the schema, the second fails closed before plan construction, and neither peer changes durable schema merely to advertise its local limits

### Requirement: Released v7 permission schemas upgrade without rebuilding relationships
An explicit released-v7-to-v8 upgrade SHALL preflight the complete source schema, canonical permission replacement, the invoking client's limits, stratification, logical identities, semantic compatibility, required additive payload and coordination attributes, and transaction shape before retiring any v7 permission row. The commit SHALL atomically replace the permission rows and schema/version stamp. It MUST reuse existing v7 relationship attributes and relationship tuples without enumerating, backfilling, rewriting, or rebuilding them.

#### Scenario: Pre-populated v7 database upgrades
- **WHEN** a valid released v7 database containing existing relationships is upgraded with its compatible source schema
- **THEN** v8 permission expressions become active while relationship tuple count and content remain identical and no relationship migration scan occurs

#### Scenario: Upgrade preflight or commit fails
- **WHEN** the replacement is invalid, semantically incompatible, unwritable, races the schema stamp, or the transaction is rejected
- **THEN** the previous permission rows and schema stamp remain usable and no mixed permission representation becomes active

### Requirement: Negative recursion is rejected atomically
The schema validator SHALL reject every strongly connected component containing an exclusion-right dependency and SHALL identify a reproducible cycle, negative edge, and affected permission names without exposing backend entity identifiers.

#### Scenario: Double-negative recursive cycle
- **WHEN** a recursive dependency cycle crosses two exclusion-right edges
- **THEN** the schema is rejected even if a Boolean rewrite of one finite example appears monotone
