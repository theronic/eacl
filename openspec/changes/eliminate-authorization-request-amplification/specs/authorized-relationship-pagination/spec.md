## Purpose

Provide one snapshot-coherent page contract for relationships filtered by authorization, served by an explicit scan route and an explicit enumerate route, so that the cost of a page is proportional to the smaller of the relationship set and the authorized set and every page makes progress under a bounded budget.

## ADDED Requirements

### Requirement: Two routes share one denotation

EACL SHALL provide authorized relationship pagination through two explicit request shapes. The scan route is `read-relationships` with an `:authorization` clause `{:subject s :permission p :on :subject|:resource}`: its denotation is the set of relationships matching the ordinary filters whose designated endpoint object passes the scalar check of `s` for `p` on the same snapshot, ordered by the relationship order. The enumerate route is `lookup-resources` with `:resource/relationship {:relation r :subject o}` (or `lookup-subjects` with `:subject/relationship {:relation r :resource o}`): its denotation is the set of objects authorized for the lookup that hold the named direct relationship with `o`, ordered by the lookup's stable order. For the same subject, permission, relation, and anchor object the two denotations SHALL be the same set. An endpoint or object type incompatible with the permission root or relation SHALL fail typed schema validation before traversal.

#### Scenario: Scan route on the resource endpoint

- **WHEN** a relationship query designates `:on :resource` for subject `s` and permission `p`
- **THEN** the returned relationships are exactly the raw matching relationships whose resource objects pass the scalar check on the selected snapshot, in relationship order

#### Scenario: Enumerate route with a relationship filter

- **WHEN** a lookup for subject `s` and permission `p` carries `:resource/relationship {:relation r :subject o}`
- **THEN** the returned objects are exactly the authorized objects holding relationship `r` with `o`, in the lookup's stable order
- **AND** the set equals the scan route's result set for the same inputs

#### Scenario: Incompatible type

- **WHEN** the designated endpoint type cannot be a resource of the permission, or the relationship filter names a relation undefined for the result type
- **THEN** EACL throws the uniform schema validation error before relationship or authorization traversal

### Requirement: Pages are bounded windows that always make progress

Each route SHALL examine candidates in stream order within one request execution context and SHALL stop at physical exhaustion, at the `N+1`st accepted candidate, or when the configured per-page candidate budget is reached. The page SHALL contain the accepted candidates found, at most `N`. `:has-next-page?` SHALL be exact when the window ended at exhaustion or at the sentinel, and SHALL be true with `:bounded? true` when the window ended at the budget with candidates remaining. The end cursor SHALL anchor at the last examined candidate. Concatenating consecutive pages without intervening relevant mutation SHALL yield the denotation in stream order with no duplicate or omission, regardless of where windows end. Reaching the budget MUST NOT produce an error, a denial, or `:has-next-page? false`.

#### Scenario: Dense acceptance

- **WHEN** the first `N+1` candidates are accepted
- **THEN** the page holds `N` rows, `:has-next-page?` is true, and `:bounded?` is false
- **AND** no later candidate is examined for page population or cache warming

#### Scenario: Budget reached before N rows

- **WHEN** fewer than `N` candidates are accepted before the candidate budget is reached and candidates remain
- **THEN** the page holds the accepted rows, possibly none, with `:has-next-page? true` and `:bounded? true`
- **AND** the next page continues after the last examined candidate with no duplicate or omission

#### Scenario: Exhaustion

- **WHEN** the stream is exhausted before `N+1` acceptances and before the budget
- **THEN** `:has-next-page?` is false and `:bounded?` is false

#### Scenario: Backward pages

- **WHEN** a backend declares reverse cursors and a `:last`/`:before` page is requested
- **THEN** the same window, sentinel, budget, and anchoring rules apply in reverse order

### Requirement: Each route performs only its own predicate work

The scan route SHALL evaluate the designated endpoint with the context-bound point kernel without public scalar re-entry, snapshot reacquisition, deadline construction, or re-preparation of an already prepared root, and SHALL read physical relationships in chunks no larger than the remaining window. The enumerate route SHALL decide each candidate with exactly one certified direct-match probe and MUST NOT evaluate permissions a second time, widen the lookup, or fetch beyond the sentinel or budget.

#### Scenario: Scan route counters

- **WHEN** a ten-row scan-route page requires eleven authorization decisions for one root
- **THEN** instrumentation observes one acquisition, one request context, one root preparation, zero public point-check entries, and physical fetches bounded by the window

#### Scenario: Enumerate route counters

- **WHEN** an enumerate-route page examines `k` authorized candidates
- **THEN** instrumentation observes exactly `k` direct-match probes and zero permission re-evaluations
- **AND** no fetch beyond the sentinel or budget

### Requirement: Authorized cursors are complete, confidential, and route-bound

A cursor SHALL authenticate and encrypt the route, the ordinary filters or lookup query, the authorization or relationship clause, direction, page demand, window budget, selected source/lifecycle/basis identity, schema generation, the dependency proof required by the existing pagination contract, ordering ABI, and operation kind. A cursor presented with a different route, clause, subject, permission, endpoint, relation, anchor object, filter, direction, or answer-affecting limit SHALL be rejected before traversal. Continuation MUST NOT mix relationship and authorization proofs or silently restart when the required proof or exact basis is unavailable.

#### Scenario: Cursor moved across routes or clauses

- **WHEN** a scan-route cursor is supplied to the enumerate route, or a cursor is supplied with a different subject, permission, endpoint, relation, or anchor object
- **THEN** EACL returns the typed invalid-cursor/query-scope error before traversal

#### Scenario: Authorization revocation between pages

- **WHEN** a dependency revokes a candidate after page one
- **THEN** continuation is rejected or exact reconstruction preserves the original denotation
- **AND** EACL never emits a page spanning two authorization proofs

### Requirement: Rejected candidates are not public

Responses SHALL contain only accepted rows, page booleans, ordinary cache provenance, and opaque cursors. No public field or error SHALL name a rejected candidate's identity or stream position. The progress anchor inside a cursor is confidential. `:bounded?` discloses only that the window budget was reached; bounded work counts in typed deadline or limit diagnostics disclose work, not identities. Rejected-candidate counts MAY appear only in explicitly privileged diagnostics.

#### Scenario: Gap before the next accepted row

- **WHEN** several rejected candidates precede an accepted row
- **THEN** the response exposes the accepted row and truthful page booleans
- **AND** neither the response nor the cursor plaintext exposes the rejected identities or their count

### Requirement: Request-wide failures are atomic

Deadline, cancellation, backend, traversal-limit, rendering, cursor, or publication failures inside a window SHALL throw their typed error and MUST NOT return a partial page, a denial-derived omission, or a cursor for uncertified state. The owned snapshot SHALL be released exactly once. Only the window candidate budget ends a page early.

#### Scenario: Deadline during a window

- **WHEN** the deadline expires after `N` accepted rows but before the window is certified
- **THEN** EACL throws `:eacl.execution/deadline-exceeded`
- **AND** returns neither the rows nor a cursor

#### Scenario: Candidate check exceeds its bound

- **WHEN** a scan-route candidate cannot be granted or denied within its per-demand traversal limit
- **THEN** the page fails with the scalar typed resource error
- **AND** the candidate is not treated as rejected

### Requirement: Both routes are oracle-equivalent

Formal and executable models SHALL define the result as the stable candidate stream filtered by the route's predicate on one snapshot, cut into windows by the sentinel and budget rules. Differential suites MUST compare first, continued, forward, and backward pages of both routes against the oracle for dense, sparse, and all-rejected acceptance, recursive permissions, duplicate proof paths, every window cut point, cursor replay, relevant and unrelated mutations, and injected failures.

#### Scenario: Seeded window comparison

- **WHEN** a generated fixture, page size, and candidate budget are evaluated by a route and by the independent filter-then-window oracle
- **THEN** page rows, page booleans, `:bounded?`, continuation, and typed failures are equivalent
- **AND** a reproducible seed is retained for every disagreement
