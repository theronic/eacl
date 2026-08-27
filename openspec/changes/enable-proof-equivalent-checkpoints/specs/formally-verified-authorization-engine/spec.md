## ADDED Requirements

### Requirement: Frame-keyed checkpoint resume equals replay
The assurance matrix SHALL cite, for frame-keyed checkpoint resumption, the composition of the history-free reducer and checkpoint leaves (resume equals continuation at one basis), the reducer read-scope bridge, and the scalar-frontier theorem, establishing that resuming a checkpoint captured at one basis on an equal-frame basis of the same lineage yields the same next page, next state, next boundary, and cumulative resource outcome as replay from the public boundary. A mutation control SHALL restore native revision to the checkpoint key and require the unrelated-write conformance case to fail; a second SHALL remove a counter from the history-free state and require the cumulative-limit test to fail.

#### Scenario: Composition is cited
- **WHEN** the assurance matrix entry for checkpoints is inspected
- **THEN** it names the three components and their premises and lists supported-writer stamping and lineage as adapter assumptions

#### Scenario: Counter is dropped
- **WHEN** the mutation control removes accumulated admissions from the state
- **THEN** a required gate fails
