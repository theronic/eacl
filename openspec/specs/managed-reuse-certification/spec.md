# managed-reuse-certification Specification

## Purpose
TBD - created by archiving change eacl-v8-root-fixes. Update Purpose after archive.
## Requirements
### Requirement: Documentation matches shipped reuse
Cache documentation SHALL state that managed cross-revision reuse applies to projections AND completed denotations under the relation-stamp framing, the exact writer contract it depends on, and the open status of its dedicated formal proof. Claims that denotation reuse is disabled SHALL be removed.

#### Scenario: Doc/behavior audit
- **WHEN** the cache documentation is compared against the layered-resolution implementation
- **THEN** every documented reuse rule matches an implemented rule and no implemented cross-revision reuse path is documented as disabled

### Requirement: Randomized differential coverage of the managed tier
A randomized cached-versus-cache-free differential oracle SHALL run with the managed tier active on all three backends, interleaving EACL-API relationship and schema writes with checks, lookups, and counts, asserting answer equality at every step.

#### Scenario: Managed oracle in CI
- **WHEN** the differential suites run in CI
- **THEN** at least one generator-driven configuration per backend has `:coherence-authority :managed` with answer caching enabled, and its interleaved-write comparisons pass

### Requirement: Dependency-closure completeness guard
Recursive plan compilation SHALL assert that every relation EID referenced by the compiled rules is contained in the plan's dependency closure, failing compilation rather than permitting reuse framed by an incomplete closure.

#### Scenario: Closure regression
- **WHEN** a future edit causes a compiled rule to reference a relation outside the computed closure
- **THEN** plan compilation fails with a typed error naming the missing dependency, instead of silently enabling under-framed managed reuse

