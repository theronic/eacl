# formal-implementation-conformance Specification

## ADDED Requirements

### Requirement: Cache assurance stops at the semantic storage boundary

Production assurance SHALL model cache storage as an arbitrary finite partial
map from a complete validated key to a complete valid immutable value.
Mechanized claims MUST cover composite-key separation, validated-ingress value
induction,
hit/fresh-evaluation equality, cache bypass, independent miss computation, and
lifecycle detachment. Eviction order, library-private policy representation,
occupancy bookkeeping, and concurrent maintenance scheduling SHALL be tested host
boundary behavior and MUST NOT be represented as authorization authority.

Portable CLJ/CLJS traces and a narrow production source inventory MUST fail if
production reintroduces a policy-specific generated decision, custom eviction
state machine, wrapped loader, repeated exact-hit validator, or unmodeled
key/value ingress-validation branch.

#### Scenario: The store evicts any entry

- **WHEN** the formal cache map nondeterministically lacks a previously present valid entry
- **THEN** the modeled request takes the ordinary miss path
- **AND** produces the same semantic result or typed error as cache-disabled execution

#### Scenario: Cache adapter behavior changes

- **WHEN** the private adapter's observable lookup, touch, publication, eviction, or restore behavior changes
- **THEN** portable CLJ/CLJS traces and cache-enabled/cache-disabled differential tests expose any semantic divergence

#### Scenario: Storage policy appears in generated authorization authority

- **WHEN** a generated model or host specialization selects an authorization action from eviction priority, capacity, frequency, recency, or concurrent scheduling
- **THEN** the conformance gate rejects that mapping as outside the semantic storage boundary
