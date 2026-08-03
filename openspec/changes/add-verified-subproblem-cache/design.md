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
- Make memory, concurrent work, cache provenance, avoided backend work, and
  proof costs observable and bounded.
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
- Derived denotations remain exact-generation-only until a bounded complete
  union of their atomic relation proofs is implemented.
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

### 4. Evaluate recursive components to a fixed point before publication

The recursive plan already identifies permission SCCs. The cache-aware
recursive evaluator will maintain request-local monotone sets until no rule can
add a grant. Only then may it publish the component denotation and its bounded
proof. Consumers can stop early for pagination, but an early stop publishes no
component result. Existing query-scoped continuations remain the mechanism for
resuming an incomplete page walk.

A cached recursive component is a mathematical set/index, not traversal
order. Page rendering applies the selected operation's deterministic order and
limit after retrieving it.

### 5. Use weighted bounded storage and single-flight computation

Entry weight includes fixed overhead, key size, result count, proof atoms, and
continuation metadata. The cache has separate budgets for projections,
denotations, and completed answers so a large closure cannot evict every small
hot projection. Eviction affects performance only.

Concurrent misses install one delayed/promise computation per exact semantic
key. Waiters share the result. A separate configured global in-flight bound
prevents a spread of distinct keys from accumulating unbounded candidate
state; once full, new distinct work executes uncached while existing same-key
callers may still join. Exceptions, cancellation, invalid values and partial
computations are removed and independently recomputed; they are never
published as answers.

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
- bounded proof validation, memory weight, and one computation per concurrent
  key; and
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
- **Single-flight deadlock or exception poisoning** → The computation never
  waits on its own key, failures remove the candidate, and lifecycle
  replacement makes delayed publication unreachable.
- **Benchmarks optimize one synthetic topology** → Include deep chains, wide
  fan-out, shared arrows, negative probes, recursive SCCs, churn and all three
  backends.
- **Formal proof overstates implementation coverage** → Generated-boundary
  routing and manifest digests are mandatory; the claim remains withheld on
  any unmapped public decision.

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
