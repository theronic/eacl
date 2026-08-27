## MODIFIED Requirements

### Requirement: Verification harness mutation controls
The verification and differential harness SHALL contain registered incorrect variants representing each critical bug class. Each registered variant SHALL declare its mechanism, and its detector SHALL apply the variant to the production implementation, a generated artifact, or a formal model it names and observe a required gate failing. A detector that only restates expected and mutated values in test-local literals MUST NOT count as a kill. Source-text detectors SHALL be permitted only for structural facts and SHALL record the exact pattern they check.

#### Scenario: Known traversal mutant
- **WHEN** CI evaluates a registered wrong-direction, premature-cycle-cut, de-duplication, or frontier mutant
- **THEN** the mutation is applied to the engine or its model and at least one required correctness gate fails

#### Scenario: Known cache or cursor mutant
- **WHEN** CI evaluates a registered incomplete-dependency, numeric-ancestry, scope-omission, fail-open, or publication-race mutant
- **THEN** the mutation is applied to the production decision or its model and at least one required correctness gate fails

#### Scenario: Literal-only detector
- **WHEN** a registry entry's detector does not reference the implementation, artifact, or model the entry names
- **THEN** the registry test fails and the entry does not contribute to the mutation score
