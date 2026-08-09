# keyset-recursive-pagination

## ADDED Requirements

### Requirement: Canonical eid order for recursive enumeration
Recursive-route enumeration SHALL present results in strictly ascending internal result-EID order, derived from the completed denotation as a canonical sorted form. The order SHALL be a function of (snapshot, query, limits) only — never of worklist derivation order, chunk configuration, or fuel partitioning.

#### Scenario: Order independence from derivation
- **WHEN** two snapshots contain the same authorized result set for a recursive permission but with different relationship structures producing different worklist derivation orders
- **THEN** enumeration returns the identical ordered sequence on both

### Requirement: One cursor kind for both routes
Both acyclic and recursive routes SHALL emit keyset cursors of kind `:lookup-eid` carrying the boundary result EID. The `:recursive-traversal` ordinal cursor kind SHALL NOT exist. The portable cursor envelope version SHALL be bumped so pre-change tokens fail with typed `:eacl.pagination/invalid-cursor`.

#### Scenario: Recursive page cursor shape
- **WHEN** a recursive-route page is returned with more results available
- **THEN** its end cursor internalizes to `{:kind :lookup-eid :result-eid <eid>}` and resuming with it returns the strictly-ascending continuation

### Requirement: No skip or duplication of surviving results
Across a paginated walk under concurrent mutation in non-exact consistency modes, any result that is authorized for the full duration of the walk SHALL be returned exactly once. Results granted below the boundary between pages MAY be omitted and results revoked mid-walk MAY be absent (ordinary keyset semantics); order-perturbing writes SHALL NOT cause silent omission or duplication of surviving results.

#### Scenario: Order-perturbing write between pages
- **WHEN** a client paginates a recursive permission and, between pages, a write changes the derivation order of surviving results (for example adding an earlier derivation path for an already-emitted resource)
- **THEN** no already-emitted surviving result is returned again and no not-yet-emitted surviving result is skipped

#### Scenario: Boundary entity loses its grant
- **WHEN** the entity at the page boundary loses the queried permission between pages under recover-current mode
- **THEN** recovery follows the certified rebase-or-restart contract with an honest `:cursor-recovery` value; the walk never silently resumes past unexamined surviving results

### Requirement: Logarithmic denotation membership
Point membership checks against a cached recursive denotation SHALL be O(log n) or better in the denotation size.

#### Scenario: Store-bound point check
- **WHEN** `can?` is evaluated with a cached denotation of n results
- **THEN** the membership decision performs at most O(log n) comparisons and does not walk the denotation vector linearly

### Requirement: Counts publish and reuse denotations
When the subproblem store is bound, an exact recursive count SHALL publish the completed denotation it computed, and subsequent compatible list/count/point operations on the same validity scope SHALL reuse it.

#### Scenario: Count then list on one snapshot
- **WHEN** an exact count completes cold with the store bound and an identical-scope list request follows on the same snapshot
- **THEN** the list request performs zero generated-traversal kernel crossings and reports a denotation hit

### Requirement: Uniform direction support
Recursive-route pagination SHALL support `:desc`/`:before` and bare `:last` with the same semantics as the acyclic route.

#### Scenario: Bare last on a recursive root
- **WHEN** a caller requests `{:last k}` on a recursive permission
- **THEN** the final k results in ascending-eid order are returned without a typed unsupported-operation error

### Requirement: Route-change-tolerant cursors
A `:lookup-eid` cursor minted while a permission routed recursive SHALL remain acceptable (subject to the cursor validity checks) if a schema change re-routes the permission acyclic, and vice versa, because both routes enumerate the same denotation in the same order.

#### Scenario: Schema edit flips the route between pages
- **WHEN** a cursor is minted under one route and the permission's routing changes before resumption
- **THEN** resumption does not fail with a cursor-kind mismatch; standard schema-generation validation governs acceptance
