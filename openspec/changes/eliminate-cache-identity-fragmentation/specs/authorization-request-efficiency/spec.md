## ADDED Requirements

### Requirement: Invocation controls do not fragment successful-result compatibility

The compatibility identity for a successful completed answer or page-navigation result SHALL exclude invocation-only execution controls: `:timeout-ms`, `:cancellation-token`, `:cache?`, and `:populate-cache?`. EACL SHALL still validate and enforce those controls for the current invocation before deciding whether to look up or publish a value. The identity MUST continue to partition the normalized operation and result-affecting query options, evaluation and demand, resource limits, page direction and size, cursor boundary, source lifecycle, complete immutable basis, adapter, engine, public order, compiler/plan compatibility, and cache-value ABI as applicable. Opposite page directions MUST NOT be treated as generally equivalent; reuse across directions is permitted only through a validated adjacent-page alias whose boundary, size, basis, order, and compatibility identities match. A pre-change entry whose key does not establish the corrected compatibility identity MUST NOT be served under the corrected identity.

#### Scenario: Remaining timeout changes between identical page requests
- **WHEN** two valid page requests differ only in their positive `:timeout-ms` or cancellation-token identity and both remain live through externalization
- **THEN** they address the same compatible successful page result
- **AND** each request independently enforces its own deadline and cancellation state

#### Scenario: Cache policy changes without changing the answer
- **WHEN** otherwise identical requests vary `:cache?` or `:populate-cache?`
- **THEN** lookup and publication obey the current request's policy
- **AND** those controls do not create distinct successful-result compatibility identities

#### Scenario: Result-affecting page input changes
- **WHEN** page size, semantic filter, demand, resource limit, cursor boundary, basis, order, or compatibility identity changes
- **THEN** EACL does not reuse a value solely because the remaining request fields match

#### Scenario: Adjacent page is revisited in the opposite navigation direction
- **WHEN** an already validated page is the exact adjacent result for a reverse or forward request with matching page size, basis, order, and compatibility identity
- **THEN** EACL may return it through the page's validated opposite-boundary alias
- **AND** no non-adjacent page or differently sized page matches that alias

#### Scenario: Legacy timeout-bearing entry is restored
- **WHEN** a restored completed entry was keyed under the superseded invocation-control identity
- **THEN** it is not served as a hit under the corrected compatibility identity
- **AND** it is either rejected by compatibility validation or remains only as bounded unreachable state until ordinary removal

### Requirement: Page-cache diagnostics are read-only on hits

Page-navigation cache diagnostics SHALL expose current bounded structure sizes and cumulative publication, replacement, alias, and eviction outcomes without being required for semantic correctness. Obtaining a compatible page hit MUST NOT mutate diagnostic counters, recency metadata, or any other shared state. Disabling optional observation MUST leave lookup eligibility, result identity, publication, eviction bounds, deadlines, and authorization results unchanged.

#### Scenario: Repeated compatible page hits are observed
- **WHEN** a client reads page-cache diagnostics before and after repeated compatible hits with no publication
- **THEN** the resident structure sizes and publication/eviction counters are unchanged
- **AND** every hit returns the same result as observation-disabled execution

#### Scenario: Page publication and eviction occur
- **WHEN** completed pages are published through replacement, alias creation, and bounded eviction
- **THEN** diagnostics report those structural outcomes from publication-side state
- **AND** the reported resident sizes remain within their documented capacity-derived bounds
