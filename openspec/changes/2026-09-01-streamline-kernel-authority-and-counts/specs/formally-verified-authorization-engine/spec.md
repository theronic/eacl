## MODIFIED Requirements

### Requirement: Differential cutover evidence without a production rollback engine
The verified kernel SHALL NOT become authoritative until it agrees with the formal semantics and every unexplained difference from the former engine has been resolved. Unsound former behavior SHALL be corrected rather than encoded into the formal semantics solely for compatibility. Once those gates pass, EACL v8 SHALL contain one production authorization decision engine; former handwritten implementations MAY remain only as test oracles, characterization fixtures, and minimized counterexamples.

#### Scenario: Unexplained shadow disagreement
- **WHEN** shadow execution produces a legacy/verified difference that has not been classified against the formal semantics
- **THEN** the authoritative-engine rollout is blocked

#### Scenario: Former-engine false grant
- **WHEN** a minimized witness proves the former engine grants a tuple absent from the formal authorization relation
- **THEN** EACL fixes the behavior, retains the witness as a regression, and documents compatibility impact before rollout

#### Scenario: Released v8 runtime
- **WHEN** a consumer constructs a Datomic, Datahike, or DataScript client
- **THEN** every authorization, pagination, and cursor decision uses the platform's certified authoritative kernel (the generated kernel on the JVM; the differentially certified portable kernel on CLJS)
- **AND** every consistency decision uses the differentially certified portable decision procedure on both platforms, with the generated kernel retained as its offline differential oracle
- **AND** no engine-selection option can reactivate a retired production decision path

#### Scenario: Consistency authority remains differentially certified
- **WHEN** the portable consistency decision procedure or the generated consistency model changes
- **THEN** the offline differential suites compare them across the full input classes and CI fails on any divergence
