# raw-request-context

## ADDED Requirements

### Requirement: Single schema proof per raw request
A raw-facade authorization request (list, count, or point check on a caller-supplied immutable database value) SHALL compute the backend schema proof at most once. The public client path SHALL compute at most one engine-side content proof per request in addition to its selection-registry read.

#### Scenario: Instrumented raw list request
- **WHEN** a raw `lookup-resources` call runs with backend-operation counters bound
- **THEN** the `:schema-proof` operation count for the request is exactly 1 (and 0 for raw `can?`, which does not consult routing)

### Requirement: Single plan compilation per raw request
A raw request SHALL compile and certify the recursive plan for a given root at most once, including requests carrying recovery-mode cursors.

#### Scenario: Raw recursive first page
- **WHEN** a raw first-page request executes against a populated recursive root with plan-compilation counters bound
- **THEN** `:compiled-recursive-plans` increments exactly once for the request

#### Scenario: Raw resumption with a recovery cursor
- **WHEN** a raw request resumes a cursor flagged for recovery
- **THEN** plan compilation still occurs at most once

### Requirement: No cache-key work against absent stores
When the subproblem store is not bound, the engine SHALL NOT construct denotation cache keys, compute dependency closures for cache validation, or perform any other work whose only consumer is an absent cache.

#### Scenario: Nil-store lookup path
- **WHEN** a raw recursive page or count executes with no subproblem store bound and key/dependency counters bound
- **THEN** denotation-key builds and dependency-closure calculations for cache purposes are both 0

### Requirement: Request-scoped isolation
Request-local derived state (memoized proof, permission paths, routing analysis, compiled plans, semantic identities) SHALL NOT outlive the request, SHALL NOT be published to any cross-request registry, and SHALL be keyed to the exact immutable database value of the request. Speculative (`with`), filtered, and historical database values SHALL receive fresh context per request.

#### Scenario: Same values across distinct raw calls
- **WHEN** two successive raw calls are made with the same database value without caller-managed schema caching
- **THEN** each call recomputes its own context (no cross-request reuse is introduced by this capability), and no derived state from the first call is observable in the second

#### Scenario: Speculative database value
- **WHEN** a raw call is made with a `d/with` speculative value
- **THEN** its derived context is isolated to that call and cannot be observed by requests on other database values
