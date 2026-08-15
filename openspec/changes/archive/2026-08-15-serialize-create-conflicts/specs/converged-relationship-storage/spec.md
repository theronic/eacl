## MODIFIED Requirements

### Requirement: Atomic pair mutation and repair
Relationship mutation through EACL SHALL add or retract both endpoint values in
one DataScript transaction and SHALL preserve the public `:create`, `:touch`,
and `:delete` semantics shared with Datomic Pro and Datahike. The `:create`
conflict decision SHALL be made against the transaction-time database: when
the relationship is absent at plan time, the planned transaction data carries
a transaction function that re-checks both endpoint values inside the
transaction and either adds them or fails with `:eacl/relationship-conflict`.
A writer topology that cannot execute a transaction function (a Datahike
remote writer) keeps the plan-time check only.

#### Scenario: Complete relationship conflict
- **WHEN** `:create` targets a relationship whose forward and reverse values both exist
- **THEN** EACL reports a relationship conflict

#### Scenario: Racing creates of one relationship
- **WHEN** two `:create` transactions for the same relationship are planned against the same pre-write database value and both are committed
- **THEN** the first commit adds both endpoint values and the second fails with `:eacl/relationship-conflict`
- **AND** the committed database contains exactly one forward and one reverse endpoint value for the relationship

#### Scenario: Incomplete relationship repair
- **WHEN** `:touch` targets a relationship with either endpoint value missing
- **THEN** EACL writes both values so the complete pair exists after the transaction

#### Scenario: Unconditional deletion
- **WHEN** `:delete` targets a complete, incomplete, or absent pair
- **THEN** EACL issues retractions for both endpoint values without requiring a relationship entity
