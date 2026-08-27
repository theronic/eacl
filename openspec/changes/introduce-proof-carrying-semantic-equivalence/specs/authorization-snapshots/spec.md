## MODIFIED Requirements

### Requirement: Runtime sharing by cache class
Snapshots created from one `acl` SHALL share its runtime registries only through keys that include complete basis identity or the lineage-scoped frame. Any admissible snapshot MAY use the exact-basis tier; a snapshot whose frame is readable MAY use managed lifting under the lineage-scoped frame rule in either revision direction; a snapshot whose frame is unavailable MAY use only exact-basis entries; subproblem and projection state SHALL be isolated per basis generation.

#### Scenario: Two snapshots at different bases
- **WHEN** two snapshots of one lineage at different bases read equal frames
- **THEN** a managed answer computed through one is reusable by the other, and neither observes the other's projections or subproblems

#### Scenario: Frame unavailable
- **WHEN** a snapshot's frame cannot be read
- **THEN** it reuses only exact entries bound to its own basis identity
