## ADDED Requirements

### Requirement: Cache-independent semantic execution
EACL SHALL make cache reuse a trace refinement of cache-disabled execution.
For the same selected immutable snapshot, normalized query, consistency mode,
execution mode, traversal limits, and deadline schedule, a cache-enabled request
SHALL use the same evaluator, traversal direction, and stopping condition as the
corresponding `:cache? false` request. Its semantic command/response trace MUST
be the cache-disabled trace with zero or more matching commands/responses
removed. A cold total miss SHALL have the same semantic trace and resource-limit
outcome. A hit MAY avoid semantic work and thereby avoid a work-limit failure,
but cache presence MUST NOT add semantic resource consumption or introduce a
failure that cache-disabled semantic execution would avoid. Cache eligibility,
validation, or proof lifting MUST NOT introduce a backend command absent from
cache-disabled execution.

#### Scenario: Cold recursive point check
- **WHEN** a recursive point check misses every compatible cache artifact
- **THEN** cache-enabled and cache-disabled execution issue the same target-anchored commands and stop at the same Boolean proof or target-local exhaustion
- **AND** cache-enabled execution does not materialize the subject's complete forward denotation

#### Scenario: Warm point artifact
- **WHEN** a compatible completed Boolean or naturally completed denotation is already cached
- **THEN** EACL may return the proven answer while omitting backend commands
- **AND** the hit does not authorize any additional traversal

#### Scenario: Stale candidate needs an additional proof scan
- **WHEN** proving a cache candidate valid would require a backend command absent from cache-disabled demand execution
- **THEN** EACL treats the candidate as a miss without issuing that command
- **AND** continues with the ordinary demand trace on the selected snapshot

#### Scenario: Resource limit parity
- **WHEN** cache-enabled and cache-disabled cold executions receive the same traversal limits
- **THEN** they either return the same value or fail at the same semantic work boundary
- **AND** cache availability alone cannot cause a complete-denotation limit failure

### Requirement: Demand is the default execution mode
Absent an explicit `:evaluation` request control, EACL SHALL normalize every
authorization operation to `:evaluation :demand`. Demand mode SHALL compute only
the semantic evidence and sentinel work required by the public result shape.

#### Scenario: Positive point proof
- **WHEN** demand-mode point evaluation certifies that the requested subject has the permission on the requested resource
- **THEN** evaluation stops without deriving unrelated grants

#### Scenario: Negative point proof
- **WHEN** demand-mode point evaluation finds no proof
- **THEN** it exhausts the target-anchored reverse question required to establish denial
- **AND** it does not enumerate unrelated resources reachable from the subject

#### Scenario: Bounded count
- **WHEN** `count-resources` or `count-subjects` receives `:count-limit L`
- **THEN** demand execution stops after graph exhaustion or `L+1` distinct results
- **AND** uses the extra result only to decide `:truncated?`

#### Scenario: Exact count
- **WHEN** a count request omits `:count-limit`
- **THEN** demand execution exhausts the result because an exact total is the requested semantic value

#### Scenario: Bounded page
- **WHEN** a lookup requests a page of `N` results
- **THEN** demand execution stops after graph exhaustion or `N+1` ordered distinct results beyond the authenticated boundary
- **AND** returns at most `N` results

### Requirement: Complete denotation is explicit
EACL SHALL accept `:evaluation :complete-denotation` only as an explicit
request control. This mode SHALL permit exhaustive compatible denotation
materialization and publication, but MUST preserve the public operation's value
and consistency contract. EACL MUST NOT expose a separate prewarm API.

#### Scenario: Explicit reusable completion
- **WHEN** a caller requests `:evaluation :complete-denotation` for a recursive point or page operation
- **THEN** EACL may exhaust and retain the complete compatible denotation before returning
- **AND** reports that complete-denotation evaluation was selected

#### Scenario: Complete evaluation of a bounded count
- **WHEN** a count with `:count-limit L` explicitly requests complete-denotation evaluation
- **THEN** EACL may exhaust and retain the compatible denotation
- **AND** renders the same limit and truncation contract as the bounded request
- **AND** exposes an exact total as the count value only when the caller omits `:count-limit`

#### Scenario: No implicit completion
- **WHEN** a demand request naturally stops before denotation exhaustion
- **THEN** no cache policy, admission signal, repeat observation, or concurrent request continues that denotation on its behalf

#### Scenario: Natural exhaustion
- **WHEN** a demand request happens to exhaust the graph before reaching its demand sentinel
- **THEN** EACL may publish the resulting completed denotation
- **AND** marks completion as natural rather than explicitly requested

#### Scenario: Invalid evaluation mode
- **WHEN** a caller supplies an unknown `:evaluation` value
- **THEN** EACL rejects the request before consistency selection, cache access, or authorization traversal

### Requirement: Cache retains only demanded work
Cache-enabled execution SHALL retain only completed artifacts and exact backend
responses that the evaluator demanded before its stopping decision. Cache code
MUST NOT increase adapter chunk size, scan bounds, generated fuel, traversal
waves, or result demand.

#### Scenario: Projection shorter than cache chunk
- **WHEN** the evaluator commands a projection scan for five values and the backend can return more
- **THEN** EACL requests, validates, and may retain at most the response authorized by that exact command
- **AND** cache configuration does not fetch a sixth value

#### Scenario: Page sentinel reached
- **WHEN** a page has obtained its `N+1` sentinel
- **THEN** EACL performs no further traversal for cache population

#### Scenario: Count sentinel reached
- **WHEN** a bounded count has obtained `L+1` distinct results
- **THEN** EACL performs no further traversal for cache population

### Requirement: Incomplete state is never an answer
EACL MUST distinguish completed Booleans, bounded responses, exact command
responses, private continuations, and complete denotations by artifact type.
Partial worklists, unfinished SCCs, prefixes, timeout state, and failed negative
searches MUST NOT be accepted as completed authorization answers.

#### Scenario: Interrupted negative search
- **WHEN** a negative point search stops because of deadline, cancellation, backend failure, or traversal limit
- **THEN** EACL does not publish or return a denial

#### Scenario: Private continuation reuse
- **WHEN** an incomplete continuation artifact is present
- **THEN** it may resume only the same snapshot, query, execution contract, and cursor position
- **AND** cannot answer another point, count, or page request directly

#### Scenario: Completed bounded count
- **WHEN** a bounded count reaches `L+1`
- **THEN** its `{count, limit, truncated?}` response may be cached under the exact normalized limit
- **AND** it is not represented as an exact complete denotation

### Requirement: Execution provenance is explicit
Detailed public responses and cache statistics SHALL distinguish selected
evaluation mode from cache lookup and publication outcomes. `:cache?` SHALL
mean only cache reuse/publication permission.

#### Scenario: Cold cache-enabled demand request
- **WHEN** a demand request misses and computes its answer
- **THEN** provenance reports a cache miss, demand evaluation, and the actual publication outcome separately

#### Scenario: Cache bypass
- **WHEN** `:cache? false` is supplied
- **THEN** provenance reports bypass
- **AND** EACL performs no cache key, lookup, proof-lifting, admission, publication, or cache-coordination work
