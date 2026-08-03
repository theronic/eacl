## ADDED Requirements

### Requirement: Cache values denote complete immutable subproblems
The cache SHALL store only values that equal a complete authorization
subproblem denotation on one selected immutable graph. It MUST NOT publish
call-stack-dependent recursion guards, partial scans, partial worklists,
partially rendered pages, or traversal-order-dependent state as reusable
subproblem answers.

#### Scenario: Recursive traversal stops at a page boundary
- **WHEN** a recursive page returns before its permission component reaches a least fixed point
- **THEN** the engine may store query-scoped continuation state but does not publish a shared recursive-component answer

#### Scenario: Recursive component completes
- **WHEN** monotone evaluation reaches a fixed point for a concrete recursive component and anchor
- **THEN** the complete component denotation may be admitted under its semantic key and proof

### Requirement: Semantic keys separate every answer-affecting input
Every subproblem key SHALL commit to the source, selected graph or validated
proof generation, schema identity, engine and key version, identity contract,
direction, internal endpoint identities, types, relation or permission node,
bounds, and all contextual inputs that can alter its denotation.

#### Scenario: Distinct principals share an atomic projection
- **WHEN** two distinct top-level queries on the same selected graph require the identical query-independent relationship projection
- **THEN** they resolve the same projection key even though their completed-answer keys differ

#### Scenario: Context changes
- **WHEN** an identity codec, caveat context, source, branch, schema, endpoint, direction, or bound changes
- **THEN** an entry created under the prior semantic input is not eligible under the new key

### Requirement: Projection chunks preserve backend scan semantics
The engine SHALL cache backend projections as bounded immutable chunks whose
lazy concatenation is exactly the finite, strictly ordered, unique result
required by the backend contract. A cache miss MUST NOT realize the complete
projection beyond the configured chunk boundary.

#### Scenario: Small page over a wide adjacency list
- **WHEN** a page consumes fewer values than a high-degree relationship projection contains
- **THEN** cache admission realizes at most the bounded chunk and does not materialize the full adjacency list

#### Scenario: Negative projection
- **WHEN** a certified backend scan returns no values for a projection
- **THEN** the cache may store an explicit terminal empty chunk and later reuse it

### Requirement: Exact-generation reuse is observationally transparent
An exact subproblem entry SHALL be eligible only for the identical immutable
selected graph generation. Cache lookup, miss, admission, eviction, expiry,
single-flight waiting, or provider failure MUST NOT change the public value,
typed error, order, cursor, page flags, count limit, or selected graph.

#### Scenario: Unrelated transaction advances the head
- **WHEN** the client selects a different immutable graph generation
- **THEN** entries from the prior exact generation become unreachable without requiring individual invalidation

#### Scenario: Explicit expiry races with computation
- **WHEN** expiry replaces the cache lifecycle while an older computation is still running
- **THEN** delayed publication can reach only the obsolete lifecycle and no later request can observe it

### Requirement: Managed reuse has a complete localized frame
Forward-revision subproblem reuse SHALL be disabled unless the selected backend
provides a complete managed-writer proof for every relationship projection on
which the value depends. EACL-managed writes SHALL atomically advance the
affected relation and schema identities. Each atomic projection proof MUST
have O(1) validation cost; a derived value whose complete proof exceeds its
configured bound remains exact-only.

#### Scenario: Write touches a cached relation
- **WHEN** an EACL relationship write creates, deletes, or changes an edge in a relation used by a cached projection
- **THEN** the relation identity changes in the same transaction and the old managed entry is rejected

#### Scenario: Write is outside the proof
- **WHEN** a forward transaction changes only relations absent from a complete cached proof
- **THEN** the managed entry remains eligible and equals recomputation on the selected graph

#### Scenario: Writer authority is unknown
- **WHEN** relationship data may have changed outside the certified EACL writer path
- **THEN** managed subproblem reuse is disabled while exact-generation reuse remains available

### Requirement: Recursive caching refines least-fixed-point semantics
Request-local and shared recursive evaluation SHALL compute the same least
fixed point as the cache-free positive ReBAC semantics. False results derived
under a non-empty DFS visited set MUST NOT be treated as context-free cached
answers.

#### Scenario: Cycle becomes reachable through another rule
- **WHEN** a recursive state is first encountered through a cyclic path and later receives a grant through a different rule
- **THEN** the fixed-point evaluator includes the grant and no earlier cycle guard can suppress it

#### Scenario: Cached recursive result is reused in reverse lookup
- **WHEN** a completed component denotation is reused by a compatible reverse operation
- **THEN** rendering preserves the reverse operation's declared ordering, de-duplication, limits, and cursor behavior

### Requirement: Cache resources and concurrent work are bounded
The client SHALL enforce separate weighted budgets for projection,
authorization-denotation, continuation, and completed-answer entries. At most
one computation for an exact semantic key SHALL be admitted concurrently;
the number of admitted computations across distinct keys SHALL also respect a
configured global bound. Work rejected by that bound SHALL execute without
cache admission. Same-key callers MAY join an already-admitted computation;
failed, cancelled, invalid, or partial computations MUST NOT poison later
requests.

#### Scenario: Entry exceeds its tier budget
- **WHEN** a completed subproblem is larger than the configured maximum entry weight
- **THEN** the result is returned to its caller without shared admission

#### Scenario: Concurrent identical misses
- **WHEN** multiple requests miss the same exact subproblem concurrently
- **THEN** they observe one successfully computed immutable value or independently recompute after failure, and all returned values equal cache-free evaluation

#### Scenario: Distinct in-flight work reaches the global bound
- **WHEN** the configured number of distinct subproblem computations is already admitted
- **THEN** a miss for another distinct key computes without cache admission while an identical-key caller may still join its admitted computation

### Requirement: Cache bypass is a complete executable oracle
For every public authorization operation, `:cache? false` SHALL bypass
completed answers, subproblem lookups, subproblem publication, admissions,
single-flight state, and managed proof providers. It SHALL remain suitable for
differential comparison with cache-enabled execution.

#### Scenario: Cache-disabled request
- **WHEN** a client containing hot completed and subproblem entries evaluates a request with `:cache? false`
- **THEN** no answer or subproblem cache metric records a lookup or admission and the result is independently recomputed

### Requirement: Cache provenance and avoided work are observable
Client cache statistics SHALL distinguish completed-answer, exact projection,
managed projection, acyclic-denotation, recursive-component and
continuation hits; misses; admissions; rejected proofs; single-flight waits;
evictions; fetched projection values; and avoided backend scans or probes.

#### Scenario: Shared-subgraph hit
- **WHEN** a distinct top-level query reuses a projection populated by an earlier query
- **THEN** completed-answer hit count remains unchanged while subproblem-hit and avoided-backend-work counters increase

### Requirement: Shared-subgraph cache exceeds the completed-answer baseline
Reproducible performance gates SHALL compare cache-free, completed-answer-only
and layered-subproblem configurations in the same process and fixture. A
distinct-query shared-subgraph workload SHALL have zero completed-answer hits,
at least 50 percent fewer backend scan/probe operations, and at least 25
percent lower p50 latency than completed-answer-only after warmup. Identical
completed-answer hot hits and cache-free p50 SHALL NOT regress by more than 5
percent.

#### Scenario: Performance release gate
- **WHEN** raw samples fail any required latency, backend-work, heap, or throughput threshold after the configured noise checks
- **THEN** the verification manifest refuses the performant-cache release claim

### Requirement: Formal semantics govern every public engine decision
The repository SHALL prove subproblem-cache refinement, recursive fixed-point
completion, key separation, projection concatenation, lifecycle safety,
bounded proof cost, and resource bounds. Every authorization-affecting public
CLJ and CLJS path SHALL route through generated decisions refining those
semantics, with strict conversions and explicit adapter assumptions.

#### Scenario: Public path lacks a generated refinement mapping
- **WHEN** any public authorization operation can return a decision, page, count, cursor, cache result, or typed error through an unmapped implementation path
- **THEN** `:complete-public-engine` remains incomplete and the release is not described as end-to-end formally verified

#### Scenario: Complete conditional verification
- **WHEN** all theorem, conversion, authoritative-routing, adapter-certification, temporal, mutation, differential, cross-runtime, performance, digest, and independent-review gates pass
- **THEN** the manifest may report the public engine as conditionally formally verified while enumerating its compiler, runtime, database, cryptographic, and adapter assumptions
