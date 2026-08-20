# acyclic-keyset-pagination Specification

## ADDED Requirements

### Requirement: Acyclic plans paginate by least-derivation-path keyset order

For a sealed plan whose reachable rule graph is acyclic, `lookup-resources` and `lookup-subjects` MUST order results by ascending least-derivation-path: each result's position is the lexicographically least sequence of `(rule-ordinal, eid)` coordinates that derives it, with rule ordinals compared by the sealed `(rank, canonical-ordinal)` alternative order and eids ascending. Each derivable entity MUST be emitted exactly once, at its least path. The order MUST be a pure function of the sealed plan and the selected snapshot — never of traversal history, cache state, physical chunking, or prior requests. Duplicate suppression MUST be decided by bounded index probes for a strictly smaller witness path, using only the certified adapter scan operations; no server-side traversal state may be required for correctness.

#### Scenario: Deterministic order without history

- **WHEN** the same acyclic query runs twice on one snapshot, in fresh processes, with all caches disabled
- **THEN** both runs return identical result order

#### Scenario: Entity derivable through multiple alternatives

- **WHEN** an entity is derivable through several union arms or several intermediates
- **THEN** it appears exactly once, at the position of its least derivation path

### Requirement: Acyclic cursors are self-contained and resume in constant work per page

An acyclic page cursor MUST carry the boundary result's derivation path — at most one `(rule-ordinal, eid)` pair per plan level — inside the existing authenticated envelope, and MUST remain within the existing cursor size budget. Resuming from a valid cursor MUST cost O(plan depth) index seeks plus the page's own enumeration, independent of the boundary ordinal, and MUST NOT require continuation checkpoints, prefix replay, or any other server-side state. A cursor presented against a mismatched fingerprint or basis MUST fail typed exactly as the existing cursor contract specifies; a validated path not reproducible at the pinned basis MUST fail `:eacl.pagination/stale-cursor`.

#### Scenario: Stateless deep page

- **WHEN** page k of an acyclic walk is requested with caches disabled, after a process restart, or on a different node
- **THEN** its latency is of the same order as page one, never proportional to k

#### Scenario: Cache toggle does not change pagination asymptotics

- **WHEN** a client paginates an acyclic root with per-request caching disabled
- **THEN** per-page cost does not grow with the page ordinal

### Requirement: Descending windows and exact counts share the least-path enumeration

`:last`/`:before` on an acyclic root MUST be served by descending derivation-path iteration with reverse index seeks, emitting each entity at its least path so ascending and descending walks agree on every emission position; the `:evaluation :complete-denotation` requirement MUST NOT apply to acyclic roots. `count-resources`/`count-subjects` on an acyclic root MUST count least-path emissions (honoring `:count-limit` with target limit+1) and MUST equal the denotation cardinality under the same proof that licenses the page order.

#### Scenario: Last window without complete evaluation

- **WHEN** a caller requests `:last N` on an acyclic root under demand evaluation
- **THEN** the final window is returned without exhausting the traversal or requiring `:complete-denotation`

#### Scenario: Count equals paginated union

- **WHEN** an acyclic root is fully paginated and separately counted
- **THEN** the count equals the number of distinct results delivered

### Requirement: The least-path regime is certified before it routes

The least-path order, enumeration, resume, and witness-check MUST be proved in Dafny leaves registered in the fast gate before the engine routes any request to them: the order is a strict total order with unique least paths; the enumeration emits exactly the reachable denotation once per entity in ascending least-path order; seek-past-boundary equals the suffix; the probe-decided smaller-witness predicate equals the order-theoretic one. Executable evidence MUST include randomized result-set differentials against the stable-discovery reducer and order differentials against a materialize-sort-dedup oracle, on Datomic, Datahike, and DataScript.

#### Scenario: Result-set equivalence across regimes

- **WHEN** any acyclic query runs under least-path order and under the retained discovery-order reducer on one snapshot
- **THEN** the result sets are equal

#### Scenario: Gate blocks uncertified routing

- **WHEN** the formal gate has not verified the least-path leaves at the pinned obligation count
- **THEN** the release assurance gate fails
