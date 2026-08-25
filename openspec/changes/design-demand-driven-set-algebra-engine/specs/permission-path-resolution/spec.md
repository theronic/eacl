## ADDED Requirements

### Requirement: Permission resolution preserves a source tree and semantic DAG
Resolution SHALL preserve a bounded source expression for diagnostics and permission-tree expansion and SHALL compile a separate canonical semantic DAG for authorization. Union and intersection MAY flatten, sort, intern, and deduplicate direct same-operator children; exclusion MUST remain ordered and binary. Resolution MUST NOT apply distributive, complement, or recursion-sensitive rewrites.

#### Scenario: Equivalent commutative operands
- **WHEN** two schemas exchange direct operands of the same union or intersection
- **THEN** their semantic DAGs are equivalent while their source trees remain available for introspection

#### Scenario: Exclusion operands exchanged
- **WHEN** two schemas exchange an exclusion's left and right operands
- **THEN** their semantic DAGs and permission denotations differ

### Requirement: Sealed plans contain complete operator evidence
An operator sealed plan SHALL contain expression nodes, physical leaves, signed dependency closure, positive component and stratum certificates, the candidate-cover graph, selected generator anchors, witness projection rules, exact-predicate program, specialization eligibility, limit policy, capability identity, order ABI, and a complete canonical fingerprint.

#### Scenario: Missing generator certificate
- **WHEN** an expression plan lacks a candidate-cover proof for a reachable operator node
- **THEN** plan sealing fails closed before backend work

### Requirement: Candidate selection is stable and non-observational
Generator selection and specialization eligibility SHALL be deterministic from authenticated sealed schema, capability, and explicitly versioned configuration facts. Authorization cache contents, hash-map iteration, request races, wall-clock timing, and discarded semantic pilots MUST NOT affect them.

#### Scenario: Equal plans on CLJ and CLJS
- **WHEN** CLJ and CLJS seal equivalent normalized inputs
- **THEN** generator anchors, witness rules, plan fingerprints, and public order identities are equal

### Requirement: Dependency closure ignores runtime short-circuiting
The plan's authorization dependency proof SHALL contain every relation reachable through every union operand, intersection operand, exclusion-left operand, and exclusion-right operand, regardless of the selected generator, witness, cached decision, or observed absence.

#### Scenario: Unread excluding relation changes
- **WHEN** a cached request previously short-circuited before reading one exclusion dependency and that relation changes
- **THEN** the prior proof no longer validates reuse

