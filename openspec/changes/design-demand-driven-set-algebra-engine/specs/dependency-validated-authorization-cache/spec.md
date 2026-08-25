## ADDED Requirements

### Requirement: Operator proof frames contain complete signed dependencies
Every reusable operator answer or subproblem SHALL be authenticated by the complete static relationship dependency closure of the semantic expression, including all generator and non-generator intersection operands and both exclusion operands. Runtime witnesses, short-circuiting, current data absence, and physical grouping MUST NOT narrow the proof frame.

#### Scenario: Exclusion absence becomes present
- **WHEN** an allowed exclusion answer was cached and a relationship is added through any right-operand dependency
- **THEN** the old proof fails validation and the cached grant is not reused

### Requirement: Negative evidence is reusable only when exact and complete
An exclusion-right false result MAY be reused only when it is a completed exact Boolean or compatible completed lower-stratum denotation under a complete dependency proof. A failed probe, bounded prefix, candidate-window boundary, unfinished batch, active recursion marker, or incomplete fixed point MUST NOT be published as absence.

#### Scenario: Cancelled right batch
- **WHEN** a right-membership batch is cancelled after some negative scalar outcomes
- **THEN** none of those provisional outcomes can authorize or populate a shared negative entry

### Requirement: Cache state cannot alter the operator plan
Cache availability MAY elide an already selected semantic or physical subproblem but MUST NOT select another candidate generator, change witness interpretation, reorder child decisions, widen demand, alter a stopping boundary, or mint a cursor with a different plan identity.

#### Scenario: Warm non-generator child
- **WHEN** one non-generator intersection child is fully cached
- **THEN** the sealed generator remains unchanged and only matching predicate work is elided

