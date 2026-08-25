## ADDED Requirements

### Requirement: Operator conformance uses independent expected values
The shared conformance corpus SHALL derive expected operator checks, forward and reverse sets, bounded and exact counts, grouping, errors, and recursive fixed points from independent finite-set and naive fixed-point oracles rather than the production planner or evaluator.

#### Scenario: Same fixture across built-in backends
- **WHEN** a deterministic operator fixture runs on Datomic, DataScript, Datahike, and Datalevin where supported
- **THEN** every backend agrees with the independent oracle on values and typed failures

### Requirement: CLJ and CLJS operator traces are equivalent
The portable operator decision boundary SHALL run the same generated vectors and randomized minimized counterexamples in CLJ and CLJS. Host-specific physical scheduling MAY differ only where a registered refinement proves identical public decisions, boundaries, and dimensional counters.

#### Scenario: Vector mask word boundary
- **WHEN** an intersection operand count crosses a portable bit-vector word boundary
- **THEN** CLJ and CLJS derive the same joins, failures, and retained logical weight

### Requirement: SpiceDB black-box comparison is set-based and pinned
The sibling `eacl-spicedb` corpus SHALL submit the shared supported syntax and relationship fixtures to a digest-pinned SpiceDB release through public APIs and SHALL compare point decisions and duplicate-free lookup sets without requiring return-order equality. EACL-only pagination/count behavior SHALL be compared to the cardinality and set obtained by draining the corresponding SpiceDB lookup.

#### Scenario: Return order differs
- **WHEN** SpiceDB and EACL return the same operator result set in different orders
- **THEN** the semantic differential passes while EACL's independent order/cursor tests remain mandatory

### Requirement: Semantic-boundary differences are explicit
Differential qualification SHALL exclude only constructs outside EACL's documented supported subset or deliberately rejected negative-recursive schemas, and SHALL record the schema, expected boundary, SpiceDB version, image digest, request, response, and EACL result.

#### Scenario: SpiceDB stores negative recursion
- **WHEN** the pinned SpiceDB version accepts a negative-recursive schema that EACL rejects as unstratified
- **THEN** the case is recorded as an intentional schema-validation boundary rather than an authorization mismatch

