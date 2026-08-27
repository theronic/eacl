## ADDED Requirements

### Requirement: Permission trees preserve operator nodes
Permission-tree expansion SHALL render union, intersection, and exclusion as explicit bounded nodes from the persisted source expression. Union and intersection child order SHALL be treated as non-semantic, while exclusion SHALL preserve its distinct left and right operands.

#### Scenario: Nested expression tree
- **WHEN** a caller expands `(reader + writer) & (reader - banned)`
- **THEN** the response preserves the intersection, union, and directed exclusion boundaries rather than flattening them into paths

### Requirement: Operator tree expansion retains existing safety contracts
Expression expansion SHALL use one selected immutable snapshot, apply existing depth, node, branch, encoded-size, deadline, cancellation, and error rules, and MUST NOT authorize or enumerate relationship denotations merely to render the schema tree.

#### Scenario: Oversized operator tree
- **WHEN** expansion would exceed a configured tree limit
- **THEN** it returns the existing typed bounded failure and no partial tree as complete
