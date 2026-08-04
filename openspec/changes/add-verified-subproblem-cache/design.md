## Context

EACL v8 currently has four distinct optimizations:

1. a schema-generation cache for permission paths, dependency closure,
   recursion classification, and recursive traversal plans;
2. a client-private completed-answer cache keyed by the whole semantic
   operation and query;
3. query-scoped continuation/page state; and
4. whatever page/index caching the selected database already provides.

Only the first optimization is normally shared by different principals or
different top-level query keys. The completed-answer cache cannot reuse the
common `team->account->admin` portion of two otherwise different questions.
The continuation cache is intentionally even narrower: it belongs to one
query, direction, cursor and selected snapshot.

The engine is positive ReBAC over finite immutable snapshots. Recursive
permissions denote least fixed points. This is important: an atomic index
projection is independent of traversal history, but a recursive DFS result
computed while an ancestor is in a `visited` set is not a context-free
subproblem result. Publishing such a partial result is unsound.

The existing formal change proves named semantic kernels and conditional
adapter contracts, but the release manifest correctly refuses an end-to-end
claim. Strict public-boundary conversion, authoritative routing, shadow
comparison, cutover performance, and independent review remain incomplete.

## Goals / Non-Goals

**Goals:**

- Reuse immutable relationship projections and completed authorization
  subproblems across distinct top-level queries.
- Preserve exact observational equivalence with cache-free evaluation,
  including errors, ordering, limits, pagination, and consistency selection.
- Support recursive permissions by publishing only completed least-fixed-point
  SCC results.
- Keep hit validation bounded and cheaper than recomputation.
- Make retained admission weight, represented candidates, registered flights,
  actual callback execution, cache provenance, avoided backend work, and proof
  costs separately observable. Bound only the measures for which the
  implementation has an enforceable contract.
- Demonstrate a material latency and backend-read improvement over the current
  completed-answer cache on a workload whose top-level final-answer keys never
  hit.
- Connect the public CLJ and CLJS execution paths to generated verified
  decisions and retain a fail-closed verification manifest until every
  required obligation is satisfied.

**Non-Goals:**

- Sharing cache state between processes or persisting it to the database.
- Treating cache contents as authoritative data.
- Reusing results across sources, branches, schemas, identity codecs,
  contextual/caveat inputs, or unsupported direct database writes.
- Promising a wall-clock theorem. Dafny proves semantic refinement and
  operation-count bounds; reproducible benchmarks enforce elapsed-time gates.
- Claiming that Clojure, JVM, JavaScript engines, database implementations, or
  cryptographic primitives are proved internally. They remain explicit trusted
  assumptions, with executable certification at their boundaries.

## Decisions

### 1. Cache immutable denotations, not call-stack artifacts

The reusable hierarchy is:

- **projection chunk**: a bounded ordered chunk returned by
  `subject->resources` or `resource->subjects`, or a direct membership probe;
- **acyclic decision**: a completed permission-node Boolean whose value is
  independent of a recursion guard;
- **recursive component**: the completed least-fixed-point denotation of one
  SCC for a concrete anchor;
- **completed answer**: the existing rendered top-level page/count/Boolean.

Each value has a versioned semantic key. Keys include source, selected graph or
validated proof generation, schema proof, identity contract, engine version,
operation kind, direction, types, internal endpoint identities, relation or
permission node, bounds, and contextual inputs that can affect denotation.
Rendered external values and cursors remain top-level/query-specific.

Alternatives rejected:

- Caching arbitrary recursive `can*` calls with the current `visited` set:
  false results depend on call context and are not reusable.
- Caching partial worklists: publication timing and traversal order would
  change observable results.
- Caching only top-level answers: this is the current design and has no
  cross-query graph reuse.

### 2. Make projection chunks the first cross-principal sharing unit

All authorization algorithms obtain relationship data through the backend SPI.
The engine will wrap ordered scans in a lazy bounded chunk cache. A miss reads
only one fixed-size chunk; requesting a later element lazily resolves the next
chunk. A page that consumes 20 results cannot force materialization of a
10,000-edge adjacency list merely to populate the cache.

Projection keys do not contain a principal or top-level permission. Therefore
different users, resource lookups, reverse lookups, point checks and recursive
traversals can reuse an identical index projection. Direct membership probes
use the same exact-generation store.

Chunk values are immutable vectors plus an explicit terminal flag. An empty
chunk is cacheable: negative scans are often the most valuable repeated proof.

### 3. Isolate entries by exact generation and lift atomic projections by relation stamp

Every client exact generation owns a weighted subproblem store. Exact hits
need no dependency proof: the cache and evaluator see the identical immutable
snapshot. Any head movement installs a new exact generation, making old
entries unreachable. Explicit expiry swaps the entire lifecycle so delayed
publication cannot resurrect an entry.

Forward-revision reuse is a second tier for atomic projections:

- EACL-managed relationship writes already stamp every affected relation in
  the same transaction as the relationship change.
- A projection proof is the relation identity plus its immutable-snapshot
  mutation datom identity (transaction and mutation value), schema mutation
  datom identity, direction, source, and complete projection semantic key.
  Including the value prevents forked histories at the same numeric
  transaction from colliding.
- A new exact generation reads that O(1) relation proof once and caches it in
  its exact store. Unchanged proofs resolve against a schema-generation
  projection store; the resulting chunk is then installed in the exact store.
- Changing relation B cannot evict relation A's projection chunks. Changing
  relation A selects a new managed key, so no old A chunk is eligible.
- A derived denotation may cross forward revisions only when the complete
  ordered union of its relation proof atoms fits the configured bound. Missing
  or over-bound proof unions remain exact-generation-only.
- Unknown writer authority, missing stamps, direct/raw snapshots, historical
  evaluation, and custom identity/context semantics without a frame theorem
  remain exact-only.

Relation-level invalidation is deliberately coarser than endpoint-level
invalidation but requires no schema migration, adds no write amplification
beyond the existing managed-writer contract, and keeps each hit proof O(1).

Alternatives rejected:

- Listener counters: they are process-local, can miss writes, and are not
  properties of the selected immutable database.
- Full transitive proof validation: its O(N) hit cost can approach the original
  traversal. Only the demanded atomic projection proof is checked on a
  managed projection miss.
- One global graph revision: correct but destroys shared-subgraph reuse after
  every write.

### 4. Exhaust the anchored recursive worklist before publication

The recursive plan identifies permission SCCs and compiles every permission
node reachable from the requested root. The production evaluator does not
publish each SCC independently: it evaluates a request-local monotone worklist
for one concrete root, direction, anchor, result type, and limit configuration.
Only queue exhaustion may publish that anchored root denotation and its bounded
proof. Consumers can stop early for pagination, but an early stop publishes no
denotation. Existing query-scoped continuations remain the mechanism for
resuming an incomplete page walk.

The cached value is the complete deterministic unique result sequence produced
by that anchored traversal, not an unordered global grant closure. Forward and
reverse denotations use distinct semantic keys. Re-rendering a compatible page
or count slices the retained sequence; incompatible direction, anchor, result
type, root, proof, or limit inputs cannot reuse it.

### 5. Separate weighted storage, flight ownership, and execution capacity

Entry weight includes fixed overhead, key size, result count, proof atoms, and
continuation metadata. The cache has separate budgets for projections,
denotations, and completed answers so a large closure cannot evict every small
hot projection. Incomplete candidates are not eviction victims. Eviction
affects performance only.

Lore's immutable analysis of PR #101 at
`theronic/lore@dabb5634b0d44e196e2b6ec63003917b3d445bec` refuted the earlier
claim that `:max-inflight` bounded actual work. The legal `A, B, A` schedule
evicted an incomplete `A`, started `B`, then admitted a second `A`: only one
candidate remained represented while three host callbacks were running. The
old model proved a represented-cache-state bound, not a production execution
bound.

The corrected design has three separate mechanisms:

- tier weight budgets bound represented cache weight;
- a coordinator-wide registry owns exactly one flight for each
  `(lifecycle, tier, semantic-key)`, independently of cache admission and
  eviction; and
- one fair JVM semaphore bounds actual top-level compute callbacks across
  exact and managed stores and across generation replacement. Cache-admission
  rejection does not bypass this semaphore.

Same-key callers join the registered flight even when its value cannot be
admitted to a tier. A saturated distinct flight waits for execution capacity;
it does not run as an uncounted uncached fallback. Nested subproblems executing
synchronously on the same host execution context reuse that context's permit
to avoid recursive semaphore deadlock, but a child `future` is a different
execution context and must acquire its own permit. Exceptions, cancellation,
invalid values and partial computations remove their own flight/candidate and
never publish an answer.

Lifecycle capture, recursive-self detection, represented-entry lookup, and
lifecycle-qualified flight lookup occur at one store-lock linearization point.
The generated lookup action is dispatched from that complete stable state
before the host installs any new flight. A registered flight is therefore
`computing` even when tier admission did not represent it. Only generated
`start-computation` may attempt installation; a defensive compare-and-set
collision is converted by re-dispatching generated `join-computation`, not by
silently substituting a host decision. The strict generated boundary rejects
an action inconsistent with its validated lookup, admission, or publication
input. Flight completion removes its lifecycle-qualified ticket under the
same store lock, so registration, selection, lifecycle replacement, and
ticket-qualified removal share one serial order. The lock is acquired once
per completed miss, not on completed-answer hits or per traversed edge; the
resource gate separately checks that finalization cost does not grow linearly
with represented entry count.

Lifecycle replacement clears represented entries but does not reset the
coordinator or its semaphore. An old `(old-lifecycle, tier, key)` flight and a
new `(new-lifecycle, tier, key)` flight may coexist; their combined executing
callback count still respects the global bound, and only the new flight can
publish into the new store lifecycle.

The registry cardinality and the number of host callers waiting on flights or
the semaphore are observable but are not claimed to be bounded independently
of caller concurrency and distinct-key traffic. Admission weights are logical
units, not JVM bytes. Consequently, this component establishes bounded
retained weight and executing callbacks, not a whole-process heap, CPU-time,
wall-time, or backend-operation theorem.

CLJ and CLJS use the same state-transition functions. Host-specific waiting is
kept outside the verified decision kernel.

### 6. Keep cache-free evaluation authoritative during differential rollout

`:cache? false` bypasses completed answers, subproblems, admissions,
single-flight state and proof providers. Schema compilation remains separately
reported because it is immutable derived schema, not an authorization answer.

Generated state-command tests run every public operation with cache on and off
against the same selected snapshot. They compare:

- value and typed error;
- ordering, count limits, cursors and page flags;
- selected graph/source/schema identity;
- cache provenance and proof decisions; and
- CLJ/CLJS/backend parity.

No cached path becomes authoritative for a release while an unexplained
difference exists.

### 7. Prove semantic refinement and bounded cost; benchmark elapsed time

The formal model defines a pure denotation `D(graph, subproblem-key)`. The main
theorem is:

`ResolveCached(graph, key, cache).value == D(graph, key)`

for exact entries, and for managed entries only under the localized frame
predicate. Separate lemmas prove:

- semantic-key injectivity over all answer-affecting inputs;
- ordered projection-chunk concatenation equals the complete backend
  projection;
- direct and acyclic memoization refinement;
- recursive SCC fixed-point completion and partial-publication exclusion;
- exact-generation and expiry race safety;
- bounded proof validation, represented admission weight, one computation per
  lifecycle-qualified concurrent key, and global active callback execution;
  and
- composition from internal denotation through public rendering.

The generated kernel decides admission, hit eligibility, proof composition and
publication. Public orchestration may return a cached value only after that
kernel accepts it.

Elapsed-time gates compare the same fixture and process against:

1. cache disabled;
2. the current completed-answer-only implementation; and
3. the new layered cache.

The shared-subgraph benchmark uses distinct top-level semantic keys and asserts
zero completed-answer hits. After warmup, it must reduce backend scan/probe
work by at least 50% and improve p50 latency by at least 25% over the
completed-answer-only baseline. Identical-query hot hits and cache-free p50
must not regress by more than 5%. Thresholds are evaluated with recorded raw
samples and a noise guard rather than one timing.

### 8. Define “entire engine formally verified” as a release gate

The manifest may report end-to-end conditional verification only when:

- every public operation routes all authorization-affecting decisions through
  a generated kernel refining the Dafny semantics;
- strict CLJ/CLJS conversions reject non-representable inputs;
- all adapter obligations have machine-readable certification evidence;
- temporal, mutation, differential, cross-runtime and performance gates pass;
- source/generated/tool digests identify the shipped artifacts; and
- an independent security/formal-methods review is recorded.

Until then, individual theorem families may be reported as passed, but
`:complete-public-engine` and the release claim remain incomplete.

### 9. Make indexed traversal a generated command/response state machine

The existing generated authorization evaluator is a whole-graph reference
oracle: it accepts complete object and relationship vectors, repeatedly
computes global immediate consequences, and charges
`|relationships| + |rules|` per saturation round. Production instead holds a
FIFO queue of indexed stream/grant/goal work, asks the backend for bounded
ordered chunks, de-duplicates grants incrementally, and may stop at a page or
point-query boundary. The two implementations agree on small differential
fixtures, but their state, order, failure boundaries, and resource counters do
not refine one another. The materializing oracle therefore cannot be the
authoritative production engine.

The authoritative generated engine will instead be a coroutine:

- a compiled schema plan contains only data-valued rules and continuation
  descriptors—no host closures;
- generated state owns the FIFO queue, scan continuations, seen grants/goals,
  reverse consumer descriptors, emitted-result set, ordinal, page/count state,
  and every authorization-affecting limit decision;
- generated output is one of `NeedScan`, `Emit`, `Complete`, or
  `LimitExceeded`;
- the host adapter may only answer `NeedScan` with a bounded response from the
  selected immutable snapshot, carrying a traversal-unique request scope and
  traversal-local request ID, strictly ordered unique values, terminal flag,
  and fetched-value count; and
- generated resume logic validates the response before incorporating it. A
  malformed or mismatched response fails closed.

Generated code proves command-scope ownership by matching the response's
traversal scope and local request ID to the pending command. The monotonic
scope allocator is unique among live traversals and fails closed before
safe-integer exhaustion. Keeping the complete projection in pending generated
state makes the scalar response binding stronger than projection echoing:
same-projection traversals cannot exchange responses, and no complete
projection is allocated and converted on every scan response. The backend
adapter remains trusted for actually executing that command against the
selected immutable snapshot and for complete ordered index semantics. A
snapshot token echoed by the same host could not prove that provenance. The
host is not trusted for traversal, de-duplication, recursive closure,
pagination, counts, cursors, or limits. Java and JavaScript adapters perform
strict total conversion to and from the same generated datatypes.

Resource accounting follows Lore's dimensional separation. The state machine
records at least:

- backend commands issued;
- values fetched by the adapter, including lookahead;
- values advanced/consumed by the engine;
- work items enqueued cumulatively;
- current and maximum queue depth;
- unique grants derived;
- results emitted; and
- logical retained-state weight.

Each limit is named for its own measure. A queue-depth theorem says nothing
about cumulative work; a logical retained-weight theorem says nothing about
JVM bytes; a backend-command theorem requires the adapter command/refinement
edge. Wall time remains a benchmark gate, not a theorem.

The finite materializing oracle and production indexed traversal also have
different resource scopes. The oracle closes the whole supplied graph before
projecting a request, while the indexed engine seeds query-local work.
Completed authorization values may be compared; their limit outcomes and work
counters may not be substituted. Production resource refinement belongs to
the generated indexed state machine under its certified ordered-adapter
contract.

The referenced Lore revision was also run against an immutable synthetic Git
snapshot of the current worktree. All 22 selected cache/traversal functions
remained source-structural candidates because Lore's strict Core does not yet
cover their concurrency, laziness, persistent collections, backend calls, or
exception forms. Its old PR #101 refutation witness correctly failed revision
validation instead of being reused for the changed source. Therefore Lore
informs the dimensional model and rejects overclaiming here; it does not
currently discharge production source-to-resource refinement, JVM heap, or
worst-case elapsed-time obligations.

The existing cache-free host evaluator remains the differential oracle during
rollout. Generated authority is enabled only after all command/response
transitions, public rendering, errors, counters, and cursor/page state agree
across CLJ, CLJS, Datomic, Datahike, and DataScript, and after the generated
path meets the performance thresholds.

### 10. Treat generated collection code as an oracle unless it passes the hot-path gate

Lore distinguishes logical work from actual callbacks, backend operations,
heap and wall time. The same discipline applies to generated code: proving
that a transition performs one logical merge step does not establish that its
BigInteger conversion, immutable-sequence construction, FFI, validation or
allocation cost is acceptable in a per-EID production loop.

Two authoritative ordered-merge prototypes were therefore measured and
rejected. A strict generated call per head preserved the abstract work count
but made a fully consumed 20,000-value merge about four times slower. A
bounded generated chunk reduced FFI calls but Dafny's generated immutable
sequence construction still cost roughly twenty times the optimized host
merge per item. Neither implementation belongs on the production data plane.

The optimized CLJ/CLJS lazy merge remains the hot implementation with no
engine-selection branch. The Dafny implementation now provides executable
single-step and bounded-chunk oracles plus reconstruction lemmas. JVM and
JavaScript campaigns compare the optimized source against that oracle over
ascending, descending, empty, overlap and interleaving partitions, and a
wrong-comparator mutant must be killed. This is useful refinement evidence,
but it is not by itself a formal source-refinement proof: the assurance
manifest must keep the acyclic source mapping incomplete until a
source-digested specialization check and independent review exist.

That complete-source review must include the private specialized helpers, not
only their public wrappers. EACL-FORMAL-019 exposed why: the descending helper
used the maximum integer as the sentinel for an absent previous value and
therefore dropped a legitimate maximum EID. EACL-FORMAL-020 found the same
state-shape defect in the generic helper, where `nil` was both the absence
sentinel and a valid host sort key. Every runtime specialization now carries
an explicit presence bit, and Dafny models the same absence/value separation
with `OptionalLast`; maximum-EID and nil-key regressions run in CLJ and CLJS.
The Dafny integer domain is an oracle for EID ordering and the optional-state
shape, not a proof of every generic host comparator or value domain.

### 11. Bound proof-pipeline resources without confusing them with engine resources

Lore's no-cross-dimensional-substitution rule applies to the verifier itself.
Z3 resource counts and assertion-batch duration measure proof search; they are
not EACL queue depth, backend operations, request latency, allocation, or live
heap. A full replay found that transparent expansion of the recursive
forward/reverse drive specifications made an iterative-loop invariant consume
an unstable amount of solver work, with the reverse obligation exceeding its
60-second batch ceiling.

The drive specifications are therefore opaque outside explicit one-step
unfolding lemmas. The checksum-locked formal policy supplies both the existing
time ceiling and a deterministic per-proof-effort Z3 resource limit.
`bin/formal verify` writes per-module CSV and an aggregate JSON resource report
and fails on a timeout, non-passing effort, malformed report, or resource-limit
breach. The GitHub formal job has a separate total wall-clock ceiling. This
catches proof-engineering regressions while the parameterized Dafny resource
theorems and production benchmark gates continue to police their own distinct
dimensions.

### 12. Make public decision-source closure machine-enforced

A theorem list and hand-written decision inventory cannot show that a new
private helper or branch was not omitted. The locked `source-closure` check
therefore analyzes both CLJ and CLJS views of the shared and backend EACL
sources, closes the cross-namespace dependency graph from 60 named roots, and
commits exact source digests, definition locations, reachable sets, and
external call sets. Unattributed usages inside exact `defrecord` spans are
assigned to the containing public-client record. The current union contains
1,287 definitions in 51 source files.

This ledger deliberately reports
`closure-enumerated-verification-incomplete`. Static reachability does not
prove a function or resolve higher-order or keyword-based backend behavior.
The ledger turns those omissions into explicit remaining scopes and makes
future source drift fail CI while the generated-authority and source-refinement
work proceeds.

The companion backend-dispatch ledger reads every CLJ and CLJS source form,
rejects a nonliteral `backend/invoke` operation, and checks that the observed
21-key set across all 56 sites equals `required-snapshot-operations`. This
closes operation-key omission and typo risk. It does not prove that a Datomic,
Datahike, or DataScript operation satisfies its named snapshot-oracle
obligations; that remains adapter semantic refinement or explicit trust.

## Risks / Trade-offs

- **Projection caching duplicates database page-cache data** → Admit only
  bounded realized chunks and require avoided-backend-read plus latency gates.
- **A high-write relation invalidates all of its endpoint projections** → This
  is a deliberate conservative frame; a future relation-plus-endpoint
  synthetic stamp can narrow it without changing cache semantics.
- **Recursive closure entries can be large** → Separate weighted budget,
  maximum entry weight, and no publication for partial/over-limit closures.
- **Proof validation becomes O(N)** → Bound proof atoms; overflow entries are
  exact-only and never admitted to managed reuse.
- **Single-flight deadlock or exception poisoning** → The same execution
  context reuses its permit, different contexts backpressure on a fair
  semaphore, failures remove their own flight/candidate, and lifecycle
  replacement makes delayed publication unreachable.
- **A cache-weight proof is mistaken for a heap theorem** → Report represented
  admission weight, registered flights, waiting callers and executing
  callbacks separately. Treat JVM object size, persistent-map overhead,
  blocked-thread retention, GC, CPU and wall time as unproved until explicit
  production refinement and runtime contracts exist.
- **Benchmarks optimize one synthetic topology** → Include deep chains, wide
  fan-out, shared arrows, negative probes, recursive SCCs, churn and all three
  backends.
- **Formal proof overstates implementation coverage** → Generated-boundary
  routing and manifest digests are mandatory; the claim remains withheld on
  any unmapped public decision.
- **A materializing oracle is mistaken for an indexed refinement** → Record it
  as reference-only. Make the generated command/response state machine own all
  traversal state and validate every backend response before authoritative
  use.
- **Formal counters share names but not dimensions with production** → Define
  backend commands, fetched values, consumed values, cumulative enqueues,
  queue depth, derived grants, emitted results and retained logical weight as
  separate fields with separate theorems and adapter obligations.
- **A correct proof becomes operationally unbounded** → Keep a deterministic
  per-effort solver-resource ceiling, a per-batch time ceiling, and a separate
  CI wall-clock ceiling; report those as proof-pipeline measures only.

## Migration Plan

1. Add instrumentation and completed-answer-only benchmark baselines.
2. Introduce the exact-generation projection cache behind the existing
   per-request `:cache?` switch.
3. Add acyclic and recursive completed-denotation layers after their
   differential and proof gates pass.
4. Add relation-stamped bounded managed reuse for EACL-managed writers.
5. Run shadow comparison and all performance gates on every backend/runtime.
6. Make verified generated decisions authoritative only after zero unexplained
   divergence and successful cutover benchmarks.
7. Keep cache expiry and `:cache? false` as operational escape hatches; cache
   state requires no data migration and may always be discarded.

## Open Questions

No semantic question is intentionally deferred. Exact-only reuse is the
fail-closed behavior whenever a backend or identity contract cannot satisfy the
localized managed-frame obligations. Independent review remains external
release evidence and cannot be self-certified by the implementation author.
