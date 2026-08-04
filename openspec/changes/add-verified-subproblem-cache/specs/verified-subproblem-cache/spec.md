## ADDED Requirements

### Requirement: Cache values denote complete immutable subproblems
The cache SHALL store only values that equal a complete authorization
subproblem denotation on one selected immutable graph. It MUST NOT publish
call-stack-dependent recursion guards, partial scans, partial worklists,
partially rendered pages, or traversal-order-dependent state as reusable
subproblem answers.

#### Scenario: Recursive traversal stops at a page boundary
- **WHEN** a recursive page returns before its anchored reachable worklist is exhausted
- **THEN** the engine may store query-scoped continuation state but does not publish a shared recursive denotation

#### Scenario: Anchored recursive denotation completes
- **WHEN** monotone evaluation exhausts the reachable worklist for a concrete root, direction, anchor, result type, and limit configuration
- **THEN** the complete deterministic denotation may be admitted under its semantic key and proof

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
- **WHEN** a completed reverse anchored denotation is reused by a compatible reverse operation
- **THEN** rendering preserves the reverse operation's declared ordering, de-duplication, limits, and cursor behavior

### Requirement: Retained weight and actual callback execution are bounded
The client SHALL enforce separate weighted budgets for projection,
authorization-denotation, continuation, and completed-answer entries.
Incomplete candidates MUST NOT be evicted. Flight ownership MUST be separate
from evictable cache entries and cache admission: at most one flight for a
lifecycle-qualified exact semantic key may exist concurrently, including when
the tier rejects admission.

A coordinator shared across exact and managed stores and across generation
replacement SHALL bound actual top-level compute callbacks. Saturated distinct
work MUST wait or reject; it MUST NOT execute as an uncounted uncached
fallback. A synchronous nested subproblem on the same host execution context
MAY reuse that context's permit to avoid self-deadlock. Failed, cancelled,
invalid, or partial computations MUST remove their own flight/candidate and
MUST NOT poison later requests.

Represented admission weight, represented candidates, registered flights,
waiting callers, and executing callbacks SHALL be reported as distinct
measures. Logical admission weight MUST NOT be described as JVM bytes or a
whole-process heap, CPU-time, backend-operation, or wall-time bound without a
separate checked production-refinement contract.

#### Scenario: Entry exceeds its tier budget
- **WHEN** a completed subproblem is larger than the configured maximum entry weight
- **THEN** the result is returned to its caller without shared admission

#### Scenario: Concurrent identical misses
- **WHEN** multiple requests miss the same exact subproblem concurrently
- **THEN** they observe one successfully computed immutable value or independently recompute after failure, and all returned values equal cache-free evaluation

#### Scenario: Admission rejects an identical flight
- **WHEN** a tier cannot represent a new candidate but an identical
  lifecycle-qualified semantic-key computation is already registered
- **THEN** the caller joins that flight instead of starting an unadmitted
  duplicate

#### Scenario: Generated lookup governs an unrepresented flight
- **WHEN** a lifecycle-qualified flight is registered but admission did not
  place its candidate in the tier entry map
- **THEN** lifecycle capture, recursive-self detection, represented-entry
  lookup, and registered-flight lookup occur at one linearization point
- **AND** the generated lookup input reports `computing` and its
  `join-computation` action is applied before any host storage mutation

#### Scenario: Generated action contradicts validated state
- **WHEN** the generated boundary returns a lookup, admission, or publication
  action inconsistent with the complete validated transition input
- **THEN** the boundary fails closed before the prohibited cache-state mutation

#### Scenario: Flight completes while lifecycle selection is blocked
- **WHEN** a computation finishes while another operation holds the
  lifecycle-selection lock
- **THEN** ticket-qualified flight removal waits for that lock and cannot
  interleave outside the serial order used by selection and lifecycle
  replacement
- **AND** a 64-fold increase in represented entries does not make miss
  finalization cost grow linearly

#### Scenario: Actual callback execution reaches the global bound
- **WHEN** the configured number of top-level compute callbacks is already
  executing across any exact or managed store generation
- **THEN** a miss for another distinct key waits or rejects without executing,
  while an identical-key caller may still join its registered flight

#### Scenario: A child execution context inherits recursive-self bindings
- **WHEN** a child future or thread inherits a same-key resolving marker from
  a parent callback but is not the execution context that owns the parent's
  permit
- **THEN** recursive self-bypass acquires its own coordinator permit before
  invoking the child callback
- **AND** only same-context synchronous recursion may reuse the parent's permit

#### Scenario: Generation expires during a computation
- **WHEN** an old-lifecycle callback is still executing and the same tier/key
  is requested in a new lifecycle
- **THEN** the two lifecycle-qualified flights may coexist, their combined
  executing callback count remains bounded, and the old result cannot publish
  into the new lifecycle

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
CLJ and CLJS path SHALL either route through generated decisions refining
those semantics or use a source-digested specialization whose complete
abstract decision partition is proved equivalent to the generated semantics.
Specializations require strict input contracts, cross-runtime differential
replay against the executable generated oracle, mutation control, and an
independent source-refinement review. Explicit adapter assumptions remain
mandatory.

#### Scenario: Public path lacks a generated refinement mapping
- **WHEN** any public authorization operation can return a decision, page, count, cursor, cache result, or typed error through an unmapped implementation path
- **THEN** `:complete-public-engine` remains incomplete and the release is not described as end-to-end formally verified

#### Scenario: Generated hot path fails its resource gate
- **WHEN** executing a generated collection primitive on every logical item materially regresses wall time, allocation, or throughput
- **THEN** that runtime routing is rejected rather than weakening the gate
- **AND** an optimized host specialization remains unverified until its complete abstract cases, source digest, differential oracle, mutation controls, and independent refinement review are recorded

#### Scenario: Ordered-merge boundary value equals a runtime extremum
- **WHEN** the first legitimate EID in an ascending or descending stream equals
  a runtime boundary integer
- **THEN** the specialization represents “no previous value” separately from
  every EID and emits that boundary value exactly once
- **AND** the mapped source surface includes the private pairwise and balanced
  fold helpers actually selected by the public wrapper

#### Scenario: Generic ordered-merge key equals the absence representation
- **WHEN** a host key function legitimately returns `nil` for the first item
- **THEN** the specialization distinguishes that key from “no previous key”
  and emits the nil-keyed equivalence class exactly once
- **AND** the assurance record states that the Dafny integer domain proves the
  optional-state shape for EIDs rather than arbitrary host comparator
  semantics

#### Scenario: Complete conditional verification
- **WHEN** all theorem, conversion, authoritative-routing, adapter-certification, temporal, mutation, differential, cross-runtime, performance, digest, and independent-review gates pass
- **THEN** the manifest may report the public engine as conditionally formally verified while enumerating its compiler, runtime, database, cryptographic, and adapter assumptions

### Requirement: Generated indexed traversal owns authorization state
The authoritative generated engine SHALL execute as a command/response state
machine over a compiled data-valued schema plan. It SHALL own traversal queues,
scan continuations, de-duplication sets, recursive goals and consumers,
emission order and ordinals, page/count state, limit decisions, and typed
failures. Host code MUST NOT duplicate an authorization-affecting transition.

The backend adapter MAY provide only bounded ordered scan responses from the
selected immutable snapshot. Every response SHALL identify its
traversal-unique request scope and traversal-local request ID, contain strictly
ordered unique values after the pending command's requested exclusive bound,
report terminal and fetched-value information, and satisfy the configured
chunk limit. Generated resume logic MUST reject any response that violates
this contract. The request-scope allocator MUST be unique among live
traversals and fail closed before safe-integer exhaustion. Snapshot selection,
immutability, and scan completeness remain explicit trusted adapter
obligations because data echoed by that same adapter cannot prove which
database it actually scanned.

#### Scenario: Traversal needs another relationship chunk
- **WHEN** generated state reaches an empty stream buffer whose projection is
  not terminal
- **THEN** it emits a `NeedScan` command and cannot derive or emit another
  result from that stream until a matching valid response is resumed

#### Scenario: Backend response belongs to another command
- **WHEN** a scan response has a different traversal request scope or local request ID, or its values violate the pending projection's bound, direction variant, or chunk contract
- **THEN** generated resume logic returns a typed adapter-contract failure and
  publishes no authorization result

#### Scenario: Materializing reference oracle agrees
- **WHEN** the finite whole graph fits both strict reference and indexed
  boundaries
- **THEN** the generated indexed result agrees with the materializing
  least-fixed-point oracle, while only dimensionally identical counters are
  compared

### Requirement: Engine resource measures are dimensionally separate
The engine SHALL separately count backend commands, adapter-fetched values,
engine-consumed values, cumulative enqueued work, current and maximum queue
depth, unique derived grants, emitted results, and logical retained-state
weight. Each configured bound and theorem SHALL name exactly one measure and
unit.

Logical weights or cardinalities MUST NOT be described as JVM bytes, live
heap, CPU time, wall time, or general backend cost without an explicit checked
refinement contract for that measure.

#### Scenario: Queue depth remains small during a long traversal
- **WHEN** a traversal repeatedly consumes and replaces one work item
- **THEN** maximum queue depth may remain constant while cumulative enqueued
  work increases, and neither counter is substituted for the other

#### Scenario: Adapter performs lookahead
- **WHEN** a backend fetches one sentinel value to determine whether a chunk is
  terminal
- **THEN** fetched values include the sentinel while consumed values include
  only values actually advanced by the generated engine
