## MODIFIED Requirements

### Requirement: SpiceDB compatibility is scoped and reproducible
For EACL-supported union, same-resource reference, and single-level arrow schemas using SpiceDB-valid string object ids, including relations that hold wildcard relationships, shallow tree topology, expanded annotations, empty branches, duplicate multiplicity, and direct-subject membership SHALL equal the response captured from a version-pinned SpiceDB Docker image after mechanical field conversion and unordered-multiset normalization. A direct leaf SHALL list a stored wildcard relationship as the subject `{:type T :id "*"}`. Authzed features rejected by EACL schema validation, non-string custom ids, token byte equality, incidental vector order, error-code identity, and resource-limit timing SHALL remain outside the equivalence claim.

#### Scenario: Provenance-bearing golden fixture
- **WHEN** a supported fixture records its schema, relationships, request, Docker image tag and digest, and raw protobuf JSON response
- **THEN** mechanical normalization of its tree equals every shipped backend's result

#### Scenario: Wildcard subject in a direct leaf
- **WHEN** a relation holds `user:*` and `user:carol` on a resource and that relation is expanded
- **THEN** its leaf lists the subjects `*` and `carol`, as SpiceDB's expansion does

#### Scenario: Unsupported feature
- **WHEN** a schema contains intersection, exclusion, subject relations, caveats, `.all`, multi-level arrows, or another already rejected feature
- **THEN** expansion does not weaken schema validation or claim SpiceDB equivalence for it

#### Scenario: Custom id extension
- **WHEN** EACL expands a valid numeric or custom external id
- **THEN** the EACL rendering contract applies but the SpiceDB differential claim does not
