## ADDED Requirements

### Requirement: Checkpoint conformance is subordinate and differential
For every backend advertising ordered generations, the shared suite SHALL compare checkpoint hits with deterministic replay: forward and reverse, same basis, equal-frame later basis, equal-frame retained older basis, relevant write (an exact-capable source resumes the checkpoint at the accepted original basis; a current-only source performs no hit), durable-source restart, non-durable-source recreation, eviction, over-weight drop, and `:populate-cache? false`. A backend without ordered generations SHALL remain correct through replay and SHALL read no checkpoint across bases.

#### Scenario: Hit equals replay
- **WHEN** a backend resumes a checkpoint on an equal-frame basis
- **THEN** page values, next boundary, next checkpoint state, and resource outcome equal replay from the authenticated boundary

#### Scenario: Relevant write
- **WHEN** a relation in the closure changes between pages
- **THEN** no checkpoint hit occurs and the cursor follows its exact-fallback or stale outcome

#### Scenario: Absent or evicted
- **WHEN** the checkpoint is absent, evicted, or over-weight
- **THEN** the backend records the miss reason and returns the replay result
