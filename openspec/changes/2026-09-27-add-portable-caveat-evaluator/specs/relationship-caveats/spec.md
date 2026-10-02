## ADDED Requirements

### Requirement: A portable evaluator serves profile 1 in ClojureScript
The optional `eacl-caveats-portable` module SHALL implement EACL CEL profile 1
in portable Clojure, with no dependency beyond core and no host interop. For
every admitted definition, request context and bound context, it SHALL return
the same outcome, reason, missing fields and residual as the JVM evaluator.
Its descriptor SHALL advertise profile 1 with an implementation fingerprint
distinct from the JVM evaluator's.

#### Scenario: Complete context
- **WHEN** every parameter has a request or bound value
- **THEN** the portable evaluator returns the JVM evaluator's cel-parser outcome, including `:missing-map-key` for an absent key in a supplied map and absorption of that fault by `false && ...` or `true || ...`

#### Scenario: Incomplete context
- **WHEN** a parameter that can still change the result has no value
- **THEN** it returns `:conditional` with the JVM evaluator's missing fields and canonical residual

#### Scenario: Admission and resource faults
- **WHEN** a definition is malformed, a supplied value has the wrong type, or the work preflight exceeds the profile limit
- **THEN** it returns the JVM evaluator's error reason without evaluating

#### Scenario: Non-canonical host values
- **WHEN** an admitted value is a JVM `Boolean` object or a map with a custom comparator
- **THEN** it evaluates the canonical value, as cel-parser does for a complete context

#### Scenario: ClojureScript application requires the module
- **WHEN** a ClojureScript application requires `eacl.caveats.portable` and creates a DataScript client without `:caveat-evaluator`
- **THEN** that client serves Caveated relationships with the portable evaluator

#### Scenario: Another evaluator is already registered
- **WHEN** the process already has a registered evaluator, such as the JVM module's
- **THEN** requiring `eacl.caveats.portable` leaves that default unchanged
