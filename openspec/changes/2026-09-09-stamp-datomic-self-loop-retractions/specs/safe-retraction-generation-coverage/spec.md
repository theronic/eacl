## ADDED Requirements

### Requirement: Every changed Relation slice advances its generation
An accepted safe retraction SHALL atomically advance the generation of every
Relation whose forward or reverse relationship slice changes, including a
Relation affected only by a self-relationship.

#### Scenario: An isolated self-loop is deleted
- **GIVEN** entity x has Relationship x-r-x and no other edge on Relation r is incident to the deletion closure
- **WHEN** native safe retraction deletes x
- **THEN** both stored halves disappear and r's generation advances in the same commit

#### Scenario: A component owns a self-loop
- **WHEN** safe retraction deletes a parent whose component child has a self-loop
- **THEN** the child's self-loop Relation is included in the affected stamp set

### Requirement: Physical peer optimization does not erase logical effects
The affected Relation set SHALL be derived independently of whether a peer
retraction operation is required. Unaffected Relation generations SHALL remain
unchanged, and duplicate affected Relations SHALL produce idempotent stamps.

#### Scenario: No peer retraction is needed
- **GIVEN** all affected halves are local self-relationship halves
- **WHEN** the native transaction function expands the deletion
- **THEN** it emits the required Relation stamps even though its peer-retraction list is empty

#### Scenario: An unrelated Relation is present
- **WHEN** another Relation has no edge incident to the deletion closure
- **THEN** its generation is not changed by the safe retraction
