# acyclic-keyset-pagination Specification

## ADDED Requirements

### Requirement: Acyclic plans paginate by least-derivation-path keyset order

For a sealed plan whose reachable rule graph is acyclic, `lookup-resources` and `lookup-subjects` MUST order results by ascending least-derivation-path: each result's position is the lexicographically least per-scan coordinate sequence that derives it — the interleaved sequence of rule ordinals (compared by the sealed `(rank, canonical-ordinal)` alternative order) and scan-bound eids (ascending), with each step's arity determined by the sealed rule kind. Each derivable entity MUST be emitted exactly once, at its least path. The order MUST be a pure function of the sealed plan and the selected snapshot — never of traversal history, cache state, physical chunking, or prior requests. Intermediate closures behind arrow-to-permission steps MUST be iterated in ascending eid by merging their sub-arms' ascending streams, so the number of index streams opened per page is bounded by the plan's alternative count times its depth — never by closure size or result ordinal. Duplicate suppression MUST be decided by bounded min-side interleaved intersections for a strictly smaller witness path, using only the certified adapter scan operations; witness work for one emission MUST be bounded by the smaller side of each intersection, never by an entity's total fan-in alone; no server-side traversal state may be required for correctness.

#### Scenario: Deterministic order without history

- **WHEN** the same acyclic query runs twice on one snapshot, in fresh processes, with all caches disabled
- **THEN** both runs return identical result order

#### Scenario: Entity derivable through multiple alternatives

- **WHEN** an entity is derivable through several union arms or several intermediates
- **THEN** it appears exactly once, at the position of its least derivation path

### Requirement: Acyclic cursors are self-contained and resume in constant work per page

An acyclic page cursor MUST carry the boundary result's per-scan coordinate sequence — a rule ordinal plus at most two eids per plan level — inside the existing authenticated envelope, and MUST remain within the existing cursor size budget. Resuming from a valid cursor MUST cost O(plan depth) index seeks (a sub-arm merge resumes by seeking all its streams past one shared bound) plus the page's own enumeration, independent of the boundary ordinal, and MUST NOT require continuation checkpoints, prefix replay, or any other server-side state. A cursor presented against a mismatched fingerprint or basis MUST fail typed exactly as the existing cursor contract already specifies — no additional rejection or migration machinery is required; a validated coordinate sequence not reproducible at the pinned basis MUST fail `:eacl.pagination/stale-cursor`.

#### Scenario: Stateless deep page

- **WHEN** page k of an acyclic walk is requested with caches disabled, after a process restart, or on a different node
- **THEN** its latency is of the same order as page one, never proportional to k

#### Scenario: Cache toggle does not change pagination asymptotics

- **WHEN** a client paginates an acyclic root with per-request caching disabled
- **THEN** per-page cost does not grow with the page ordinal

### Requirement: Descending windows share the least-path enumeration

`:last`/`:before` on an acyclic root MUST be served by descending coordinate iteration with reverse index seeks, emitting each entity at its least path so ascending and descending walks agree on every emission position; the `:evaluation :complete-denotation` requirement MUST NOT apply to acyclic roots. Counts are governed by the existing contract and are out of this capability's scope.

#### Scenario: Last window without complete evaluation

- **WHEN** a caller requests `:last N` on an acyclic root under demand evaluation
- **THEN** the final window is returned without exhausting the traversal or requiring `:complete-denotation`

#### Scenario: Descending agrees with ascending

- **WHEN** an acyclic root is fully paginated forward and backward on one snapshot
- **THEN** the backward sequence is exactly the reverse of the forward sequence

### Requirement: The least-path regime is certified before it routes

The least-path order, enumeration, resume, and witness-check MUST be proved in Dafny leaves registered in the fast gate before the engine routes any request to them: the order is a strict total order with unique least paths; the enumeration emits exactly the reachable denotation once per entity in ascending least-path order; seek-past-boundary equals the suffix; the probe-decided smaller-witness predicate equals the order-theoretic one. Executable evidence MUST include randomized result-set differentials against the stable-discovery reducer and order differentials against a materialize-sort-dedup oracle, on Datomic, Datahike, and DataScript.

#### Scenario: Result-set equivalence across regimes

- **WHEN** any acyclic query runs under least-path order and under the retained discovery-order reducer on one snapshot
- **THEN** the result sets are equal

#### Scenario: Gate blocks uncertified routing

- **WHEN** the formal gate has not verified the least-path leaves at the pinned obligation count
- **THEN** the release assurance gate fails
