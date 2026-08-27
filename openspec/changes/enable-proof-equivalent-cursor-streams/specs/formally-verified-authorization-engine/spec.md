## ADDED Requirements

### Requirement: The reducer's read scope is the plan closure
The stable-discovery assurance tree SHALL contain a leaf stating that every scan descriptor issued by the reducer for a sealed plan names a relation in that plan's closure, and that the reducer's transitions, emissions, order, and boundary positions are therefore a function of the plan and the closure's slices. Composed with the scalar-frontier theorem and the existing boundary theorems, it SHALL be the cited evidence that equal frames in one lineage imply an identical complete ordered stream and exact suffix or prefix continuation. An executable mutation control SHALL compile a plan referencing a relation outside its closure and require rejection.

#### Scenario: Bridge is cited
- **WHEN** the assurance matrix entry for proof-equivalent continuation is inspected
- **THEN** it names the read-scope leaf, the scalar-frontier theorem, and the boundary theorems, with their premises

#### Scenario: Closure guard is removed
- **WHEN** the mutation control disables the compile-time closure-completeness guard
- **THEN** a required gate fails
