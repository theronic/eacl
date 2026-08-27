## ADDED Requirements

### Requirement: A registered mutation control executes the production code it claims to mutate

A registered mutation control SHALL replace the behavior of the production definition it names and SHALL detect the mutation through a consumer that executes production code. A control whose kill assertion is decidable without executing the mutated production definition — for example a constant-valued mutant compared against a literal — SHALL NOT be registered or counted as killed.

Each control's recorded kill evidence SHALL name a test or detector that exists in the repository.

#### Scenario: Control with a constant-valued mutant

- **WHEN** a registered control replaces a production definition with a function that ignores its arguments, and asserts a difference between two literal values
- **THEN** the manifest validator rejects the control as non-executable
- **AND** the mutation score does not count it as killed

#### Scenario: Recorded kill evidence names a missing test

- **WHEN** a control records kill evidence naming a test that does not exist in the repository
- **THEN** the manifest validator fails

### Requirement: Mutation controls cover every required operator mutation class

The registered controls SHALL include one executable control per required operator mutation class, including treating an active recursion marker as a false decision. Each control SHALL be detected by a production execution path, not by a test-local reimplementation of the semantics under test.

#### Scenario: Active recursion is treated as a negative decision

- **WHEN** the production active-recursion guard is mutated to return a negative decision instead of failing closed
- **THEN** a production execution path surfaces the difference and the control is killed

### Requirement: Assurance gates fail their build step on failure

A gate command invoked by CI SHALL exit non-zero when the work it runs reports failures or errors. A gate that reports its outcome as a return value SHALL convert a failing outcome into a non-zero exit before CI evaluates the step.

#### Scenario: Replayed counterexample corpus reports errors

- **WHEN** the minimized counterexample replay reports one or more failures or errors
- **THEN** the replay command exits non-zero and the CI step fails

### Requirement: The enforced digest closure pins every generated-boundary source

Every source file that defines the generated decision boundary SHALL be pinned by the digest closure that CI enforces. A pinned digest that no longer matches its file SHALL fail validation rather than remain recorded as historical.

#### Scenario: Generated-boundary source changes without a ledger update

- **WHEN** a file defining exported generated decisions is modified and its digest is absent from or stale in the enforced closure
- **THEN** the assurance validation fails
