## ADDED Requirements

### Requirement: Operator cursors authenticate semantic and generator identity
Every operator lookup cursor SHALL authenticate the expression format and digest, signed dependency and stratum certificate, candidate-cover graph, selected generator anchors, witness program version, specialization policy version, order ABI, direction, selected snapshot/proof identity, and logical progress coordinate needed to reproduce the page boundary.

#### Scenario: Generator anchor changes
- **WHEN** a resumed request seals a different intersection generator from the cursor's generator identity
- **THEN** cursor validation fails with the established typed incompatibility before backend or cache work

### Requirement: Batch overread cannot skip results on resume
An operator cursor SHALL advance only through logically consumed generator candidates under the established filtered-page contract. Physically probed candidates beyond the accepted sentinel or logical candidate-window edge MAY populate compatible completed caches but MUST NOT move the cursor boundary.

#### Scenario: Sentinel occurs inside a batch
- **WHEN** a physical batch contains candidates after the `N+1` accepted sentinel
- **THEN** the next page resumes from the public page boundary and cannot skip an unreturned candidate because it was already probed

### Requirement: Operator page composition equals uninterrupted enumeration
For one valid cursor lineage and compatible immutable graph or proof-equivalent graph, concatenating all resumed pages SHALL equal uninterrupted evaluation of the sealed cover filtered by the exact predicate, in both supported directions, without duplicates or omissions.

#### Scenario: Candidate-window continuation
- **WHEN** a low-selectivity page returns bounded progress before producing an item
- **THEN** repeated valid continuation eventually produces exactly the same suffix or a typed configured-work failure
