# Stable discovery exploration

Status: exploration only. These files are ignored by Git and do not alter the
runtime or release assurance claims.

## Minimum architecture

### 1. Sealed plan

Compile, validate, canonicalize, and fingerprint the permission plan once.
Request initialization references the sealed plan in constant time. Runtime
iteration over hash maps or sets must never select semantic order.

### 2. Pure deterministic reducer

The semantic authority owns only:

- a canonical LIFO work stack;
- a monotone exact set of admitted logical work identities;
- compact static-rule cursors for forward traversal;
- exact `(permission-node, resource-eid)` goal identities for reverse
  traversal;
- a scalar discovered-result count;
- scalar limits and counters that can be updated incrementally.

It does not own futures, request IDs, cache epochs, wall clocks, threads,
backend handles, or physical-capacity accounting.

`StableReducer.dfy` retains a complete result sequence only as an
observational specification. Production state must refine through
`HistoryFreeReducer.dfy`: it retains stack, admitted identities, and one
scalar discovered count, never the delivered-result history. A separate pure
pagination wrapper may retain at most the current page plus one undelivered
lookahead. After page delivery it retains at most that one lookahead. Exact
progress checkpoints use this history-free core plus the bounded pending
segment.

The hot runtime may implement the history-free state with linearly owned
Clojure/ClojureScript transients. Only the canonical reducer owner mutates;
physical workers see no builder. Page, cancellation, deadline, checkpoint, and
thread-handoff boundaries freeze to persistent values, and every resumed or
concurrent cursor forks a new transient branch. This is a representation
refinement, not mutable public cursor state.

A transition consumes exactly the canonical stack head. Newly admitted work is
placed before the old tail in canonical successor order. Only a canonical root
grant emits, and its work identity is injective in the returned EID for a fixed
query. Exact work admission therefore guarantees exact result deduplication
without a second emitted-result set. The reducer is deterministic for a sealed
plan and exact database basis.

Before mutation, the reducer stages the exact fresh identities and replacement
frontier count. Subtraction-form checks validate every
configured logical cap; only then does one owner commit the whole transition.
A rejected candidate leaves stack, admission, page state, and counters
unchanged and cannot be checkpointed as progress.

Expected semantic cost is `O(V + E + R)`, where:

- `V` is the number of distinct logical work identities admitted;
- `E` is the number of successor identities inspected;
- `R` is the number of root results appended to the bounded page state.

This assumes expected constant-time persistent hash-set and hash-map access.
There is no global result sort and no N-way ordered-stream merge. Backend and
host collection costs require independent measurement.

Reducer I/O is a two-part protocol:

1. When the canonical head needs storage, `NeedRead` returns its physical read
   descriptor without changing reducer state.
2. A complete validated response whose descriptor equals the current head
   atomically replaces that head with `new-work ++ old-tail`, after the logical
   preflight above succeeds.

There is no semantic `pending` request, request scope, next request ID, or
partially consumed response. Cancellation before integration leaves the exact
same reducer state. An empty non-terminal response is rejected as
non-progressing.

Logical counters and limits stay in the reducer: admitted work, consumed
values, grants, results, rule applications, and retained logical units.
Physical attempts, fetched bytes/values, cache hits, retries, latency, and
in-flight capacity are driver metrics and limits. Mixing those categories in
one formal state needlessly couples denotation to deployment policy.

### 3. Logical work versus physical reads

These identities must remain distinct:

- A logical scan work identity includes its semantic continuation. The same
  projection reached through two rule paths may need two logical expansions.
- A physical read descriptor includes backend/store and database identity,
  exact basis, operation/index, lower and upper bounds, the current exclusive
  physical range position, projection, requested limit, and chunking ABI. It
  excludes only the semantic consequence continuation and transport request
  ID, so complete immutable chunks can be reused safely. A later physical scan
  page is a different descriptor even when every other field is equal.
- A transport request ID correlates one command envelope and response only.

Deduplicating by physical descriptor at the logical layer is unsound. Including
semantic continuation or request ID in the projection key destroys safe reuse;
omitting the physical range position aliases distinct storage pages and is
also unsound.

Logical scan identity excludes physical position and chunk size because one
logical occurrence survives residual advancement. Physical position and limit
remain part of the exact read descriptor. `ChunkedScan.dfy` proves only that
flattening every physical chunk reconstructs the same ordered scan values. It
does **not** prove discovery-order independence: under overlapping derivations,
admitting a wide chunk before exploring its first value can suppress a later
recursive discovery that wins when the first chunk is narrower.

`LogicalScanCursor.dfy` contains that counterexample, and the source-shaped
fixture returns `[a c b]` with width one versus `[a b c]` with a wide first
chunk while preserving the same result set. The accepted design therefore
consumes exactly one ordered scan value per canonical reducer transition.
`OneValueScanNormalization.dfy` proves that arbitrary positive physical fetch
widths produce the same logical release sequence. Physical width is excluded
from the public order context because it no longer affects semantic order.

The authoritative scan frame stores the exact logical exclusive resume bound,
not a physical-buffer position. A request-side bounded chunk is only an
accelerator. It may be dropped at any transition and rematerialized from the
logical bound without changing the remaining release sequence. Checkpoints
capture no physical buffers or pins. A source-shaped campaign checked retained
and deliberately dematerialized buffers over four physical widths in 17
recursive forward/reverse cases; all 153 comparisons produced the same exact
discovery sequences. Dematerializing every deferred buffer can increase
physical commands sharply on broad scans, so normal requests retain governed
buffers and drop them only at checkpoint/cancellation or under memory pressure.
Retention is independently bounded: `BoundedSidecar.dfy` proves a fixed
newest-retained request cap, and a 100,000-depth source campaign checked
capacities 0/1/4/16 over 400,000 transitions. At physical width 64, the
capacity-16 run retained at most 1,008 unread EIDs (about 10 KiB under the JVM
fixture), rather than one buffer per recursive depth.

### 4. Bounded read-ahead shell

The host maintains an auxiliary index of currently materialized scan work. A
request's read-ahead window counts both active physical calls and complete but
not yet canonically integrated occurrences. I/O completion does not reopen the
window. Only the reducer's current head may integrate a response, so completion
order affects latency, not semantics or pagination order.

One service-wide permanent bounded executor owns physical capacity. At least
one slot is reserved for the canonical read. Request cancellation discards
semantic ownership immediately, but a physical charge is released only when
the backend call actually returns.

A separate service-wide weighted response store bounds heap pressure. Every
descriptor declares a validated conservative maximum chunk weight, which is
reserved before I/O. One immutable chunk has three possible owner classes:
in-flight production, live request pins, and reusable projection-cache
ownership. Its reservation remains until the last owner disappears. A request
stores only a descriptor/pin, never a second response vector; a pinned chunk
cannot be evicted. Physical capacity may be released at backend return while
response capacity remains charged. Count-based TLC models one fixed-size
chunk unit; production must use conservative bytes/values and reject a read
whose reservation cannot be admitted.

The readable-work index and speculative response ownership are optional host
accelerators, not portable reducer requirements. When effective width is one
(always in ClojureScript/DataScript, and for any conservatively configured
driver), execute only the canonical head and omit the projection index,
speculative pins, and futures entirely. This prevents a remote-I/O
optimization from taxing the in-memory hot path.

Within one request, equality-complete physical descriptors are coalesced. One
descriptor owns at most one physical flight and one validated complete
response, even when several distinct logical scan occurrences have different
semantic continuations. The logical occurrences still integrate separately in
canonical order. This is host state, not reducer state. Cross-request flight
sharing remains out of the minimum design because its cancellation, epoch,
fairness, and detached-flight lifecycle are materially harder.

The first implementation should not speculatively execute pure semantic work,
share in-flight futures across requests, or persist context-incomplete reducer
progress. Those features add lifecycle state and are not required to overlap
known S3 reads.

### 5. Projection cache

Only a fully successful, bounded, validated, immutable scan chunk may gain
reusable cache ownership. Publication is atomic and synchronous with response
validation; it is not an asynchronous write-behind path. A chunk that
completes after its HTTP request is canceled may still publish when its exact
storage context and service epoch remain live; otherwise its response lease is
released when no live pin exists.

Cache rotation increments an epoch and removes old reusable ownership. It does
not free chunks still pinned by live requests. Old physical calls remain
charged until return, and an old-epoch completion cannot publish into the new
epoch, though a live exact request may consume its privately pinned response.
The executor itself is not replaced or retired.

### 5a. Exact progress checkpoints

A partial denotation set is not reusable as early work: doing so can change
first-discovery order even when every cached fact is sound. A reusable progress
entry is instead an immutable complete reducer state captured after a
deterministic number of canonical transitions. It is keyed by exact snapshot,
normalized request, sealed plan/order ABI, start cursor, limits, and reducer
ABI. Resuming it is observationally identical to uninterrupted execution.

The initial implementation atomically publishes at most one latest checkpoint
per bounded work quantum and at cancellation, subject to a strict weighted
cache bound. Publication is a synchronous in-memory pointer replacement with
an O(1) scalar weight calculation; there is no candidate queue, serialization,
or write-behind executor on the initial path. Short/in-memory queries may set a
large quantum or disable periodic publication. The
state must include the exact frontier/admission state, partial page, ordinal,
and semantic counters. Every scan frame contains its logical resume bound, but
no response vector, buffer offset, or physical pin. The live request may retain
those accelerators in a separate governed side table; capture ignores that
table, so it is constant-time in frontier depth and the restored checkpoint
simply rematerializes any needed chunk. If a remote or serialized store is
added later, its write is best-effort on separately bounded latest-only
capacity and cannot delay or alter the request. A checkpoint replaces an
earlier one only when the exact identity matches and its canonical transition
ordinal is nondecreasing.

The retained-heap control supports latest-only publication: a 100,000-key/
10,000-frame base advanced through 64 checkpoints retained 5.04 MB when only
the latest state survived and 6.39 MB when all structurally shared candidates
survived. Structural sharing reduces duplication but does not make a queue
free.

This is progress reuse, not a completed-answer or denotation hit. A completed
answer may be cached only for the exact whole request. A flat subproblem
denotation—even a complete, set-correct one—cannot replace stable enumeration:
its discovery sequence depends on the request's already-admitted work. A
subproblem cache may retain equality-complete ordered successor projections
and replay them through normal request-local admission; arbitrary partial or
complete denotation segments are not enumeration continuations.

### 6. Pagination

The public cursor binds at least:

- exact basis/snapshot identity;
- query and principal identity;
- sealed-plan fingerprint and stable-order ABI;
- fixed positive page size;
- the represented edge's one-based result ordinal and canonical external
  boundary identity.

The cursor is an edge token, not a page-start token. `after` the end edge
resumes at internal delivered boundary `ordinal`. `before` the start edge has
exclusive end `ordinal - 1`, but a history-free forward reducer must resume at
`max(0, ordinal - 1 - page-size)`, run through at most `page-size + 1`
results, validate the supplied edge as its final lookahead, and return the
preceding prefix. Backward pages are slices of the same canonical sequence and
remain in forward display order. Page-navigation mode is therefore not part
of cursor identity: one edge token may be used as `after` or `before`. The
authorization operation and propagation direction remain bound. Bare `last`
requires completing the finite sequence or an exact completed answer and
remains subject to ordinary work/deadline limits.

A successful page boundary may retain the exact reducer continuation. If that
checkpoint is absent or evicted, deterministic replay reconstructs the exact
resume boundary. An `after` checkpoint retains the constant-size last-result
identity needed to match its edge. A `before` request uses a checkpoint at the
computed page start, never at the exclusive end where a history-free reducer
could no longer recover preceding results. The server derives the private
lookup key from the authenticated context and normalized resume boundary and
full-compares the entry; the public cursor carries no cache pointer. This is
distinct from caching an arbitrary
half-computed request. The ordinal locates the edge only because the
authenticated context binds the exact deterministic implementation, plan,
order ABI, scan ABI, and basis; the authenticated external boundary identity
must also match the represented result. `CURSOR_TRUST_BOUNDARY.md` states the canonical encoding, one
cursor MAC/AEAD, key lifecycle, bounded-decoding, and source-refinement
assumptions; those cryptographic primitives and adapter guarantees are not
solver-proved facts.

## Direction-specific propagation

Forward resource lookup does not need a dynamic grant/consumer join. The
sealed plan has a complete reverse index from each granted permission node to
the static self-permission and arrow-permission rules that consume it. A new
forward grant therefore advances a compact cursor over that immutable rule
vector. A self-permission produces a grant without storage; an
arrow-permission issues one bounded `subject->resources` scan and turns its
values into consequence grants. Direct and arrow-to-relation rules are seed
scans. Forward state needs no grant bucket, consumer bucket, or pair history.

Reverse subject lookup begins from a concrete `(permission-node,
resource-eid)` goal. The sealed plan's rules-by-head-node vector emits exactly
the transposed predecessor goals and base-principal scans. Reverse execution is
therefore the same exact-admission stack traversal over the transposed grounded
graph; it retains no grant bucket, consumer bucket, pair history, or join
cursor. `StaticReverseFrontier.dfy` proves sound admission, one fresh processed
goal per step, and complete exhaustion. `EaclReverseProducer.dfy` proves the
static rule scans are exactly the typed transposed edges and base owners.

## Proof decomposition and speed budget

`FORMAL_ITERATION_POLICY.md` makes verification turnaround an explicit design
constraint. The working heuristic is asymmetric: a slow proof is evidence of
possible semantic coupling, hidden global work, or a poor model encoding; a
fast proof is not evidence that the abstraction matches production or that the
backend is fast. Leaf proofs, the complete semantic gate, and source/backend
qualification therefore remain separate loops.

The formal boundary is intentionally split:

| Model | Claim | Current result |
|---|---|---|
| `StableReducer.dfy` | unique work admission, injective result projection without an emitted set, stable prefixes, deterministic replay | 24 verified, 0 errors, about 1.1 s |
| `HistoryFreeReducer.dfy` | runtime stack/admission/scalar-count state refines the observation-rich reducer step-for-step and across runs/checkpoints without retaining complete result history | 9 verified, 0 errors, about 1.0 s |
| `TargetedResultDriver.dfy` | the history-free response driver buffers exactly the newly discovered suffix, stops at a scalar target, and cannot overrun its result bound | 14 verified, 0 errors, about 1.0 s |
| `ConcreteHistoryFreeRuntime.dfy` | right-edge stack, exact admission, scalar count, and optional output refine the history-free step and run as one concrete hot-loop state | 7 verified, 0 errors, about 1.0 s |
| `OwnedTransientSnapshot.dfy` | one live owner may mutate the complete concrete reducer state; freeze preserves it and revokes mutation; persistent snapshots fork into independent exact branches | 12 verified, 0 errors, about 0.8 s |
| `ReducerCompleteness.dfy` | exhaustion equals the least reachable successor-closed fixed point without stored history | 17 verified, 0 errors, about 1.1 s |
| `StaticReverseFrontier.dfy` | one admitted goal set and one work stack soundly traverse static transposed predecessors; exhaustion contains every reachable goal; each step processes one fresh goal | 16 verified, 0 errors, about 1.1 s |
| `BidirectionalReachability.dfy` | generic exact-identity path reversal over a transposed graph proves reverse discovery iff forward authorization | 14 verified, 0 errors, about 0.8 s |
| `EaclBidirectionalReachability.dfy` | directly instantiates path reversal with typed EACL `Grant(node, resource)` identities and exact typed bases/edges, with no numeric packing assumption | 9 verified, 0 errors, about 1.1 s |
| `ChunkedScan.dfy` | exact scan coverage and physical-chunk flattening; it makes no discovery-order claim | 11 verified, 0 errors, about 0.7 s |
| `LogicalScanCursor.dfy` | residual position replaces one admitted logical occurrence; nonterminal chunks preserve completed work, terminal chunks complete it once, and varying eager batch width has a proved ordering counterexample | 18 verified, 0 errors, about 1.0 s |
| `OneValueScanNormalization.dfy` | a bounded physical buffer refines one-value logical release; physical width and arbitrary buffer dematerialization cannot change the remaining release order | 17 verified, 0 errors, about 1.0 s |
| `BoundedSidecar.dfy` | newest-retained physical sidecars remain within a fixed request cap, preserve unique keys, and retain the current accelerator when capacity is positive | 10 verified, 0 errors, about 1.0 s |
| `DescriptorIdentity.dfy` | logical consequence identity is separate from an equality-complete physical range key; distinct scan positions cannot coalesce and request IDs do not fragment reuse | 4 verified, 0 errors, about 0.7 s |
| `CacheBoundary.dfy` | an exact ordered projection preserves one reducer step; equal projection sets with different order do not; a complete context-free subtree denotation can have the correct set but the wrong stable trace under overlapping admission | 7 verified, 0 errors, about 0.9 s |
| `WeightedResponseLease.dfy` | positive conservative response weight is reserved before I/O, held exactly while any in-flight/pin/cache owner exists, never double-charged on cache hits, and safely released from the aggregate governor | 20 verified, 0 errors, about 0.6 s |
| `StablePagination.dfy` | internal delivered-count boundary, exact forward slices, deterministic replay, adjacent forward non-overlap, and the equal-prefix/different-next counterexample | 16 verified, 0 errors, about 0.8 s |
| `RelayEdgePagination.dfy` | one-based public edge cursors; exact `after end-cursor` and `before start-cursor` arithmetic; same-sequence forward/backward slices; bounded pages; short-edge-page correctness; and cross-page-size rejection | 29 verified, 0 errors, about 1.1 s |
| `EdgeBoundaryAuthentication.dfy` | exact external identity at the represented edge ordinal; minted start/end identity; replay drift rejection; and safe reuse of one authenticated edge in both navigation modes | 26 verified, 0 errors, about 1.1 s |
| `RelayCheckpointExecution.dfy` | public edge refinement to history-free forward checkpoints; exact `after` last-edge match; backward page-start resume; page-plus-one validation lookahead; and bounded target-driver composition | 24 verified, 0 errors, about 1.2 s |
| `LookaheadPagination.dfy` | undelivered lookahead remains the next result; exact checkpoint and checkpoint-miss replay have the same residual suffix without retaining delivered-prefix state | 22 verified, 0 errors, about 0.9 s |
| `PaginationComposition.dfy` | the internal delivered-count boundary and exact private checkpoint denote the same forward page; checkpoint hit and replay miss use the same boundary | 6 verified, 0 errors, about 0.7 s |
| `BoundedPageBuffer.dfy` | page construction retains at most page plus one lookahead, emits exactly the public page, advances to the exact next ordinal, and leaves at most one pending result | 14 verified, 0 errors, about 0.7 s |
| `RuntimeCheckpointComposition.dfy` | history-free scalar progress, proof-only observed prefix, internal delivered boundary, and private pending segment form one exact checkpoint boundary and return the replay page | 6 verified, 0 errors, about 0.9 s |
| `ConcreteOutputIdentity.dfy` | forward and reverse root-grant work identities are injective in returned EID | 4 verified, 0 errors, about 0.7 s |
| `ExactCountComposition.dfy` | at exhaustion, emitted results are exactly reachable result nodes; unique sequence length and the history-free scalar count equal denotation cardinality; counting all admitted work is constructively refuted | 8 verified, 0 errors, about 1.0 s |
| `AtomicLogicalAdmission.dfy` | a staged successor batch is accepted wholly within admitted/frontier caps or leaves state unchanged; a post-check mutant can exceed the cap | 7 verified, 0 errors, about 0.8 s |
| `ReadableWorkIndex.dfy` | an incrementally maintained scan-work stack exactly projects the canonical work stack | 10 verified, 0 errors, about 0.7 s |
| `RuntimeStackRefinement.dfy` | a right-edge persistent-vector stack using `peek`/`pop` and reverse successor append exactly refines the abstract front-headed stack | 11 verified, 0 errors, about 0.8 s |
| `ReducerCost.dfy` | admitted work partitions into completed and pending logical work; every atomic logical expansion completes one fresh node and retained logical work is bounded by the finite program | 7 verified, 0 errors, about 1.2 s |
| `ExactDedupLowerBound.dfy` | every exact online membership summary is injective over admitted subsets; equal admitted counts cannot determine membership | 2 verified, 0 errors, about 0.5 s |
| `GroundedPositiveProgram.dfy` | finite base grants and unary positive implications compile exactly to reducer reachability and exhaust to the least ground closure | 14 verified, 0 errors, about 1.1 s |
| `EaclForwardGrounding.dfy` | typed direct/arrow-relation facts ground to bases; typed self/arrow-permission rules ground exactly to unary edges; node, relation, allowed-subject, entity, and grant resource domains agree | 6 verified, 0 errors, about 1.3 s |
| `EaclForwardProducer.dfy` | for one admitted grant, the exact sealed consumer vector plus self/arrow scan production emits a consequence iff the typed fully grounded graph contains that outgoing edge | 4 verified, 0 errors, about 1.1 s |
| `EaclReverseProducer.dfy` | sealed reverse rules emit exactly the transposed grounded predecessors, and direct/arrow-relation scans report exactly the principals owning forward base grants | 4 verified, 0 errors, about 1.2 s |
| `StaticDirectionIndex.dfy` | target-keyed forward and head-keyed reverse indexes contain exactly their matching static rules; unique sealed rule IDs remain unique; one offset cursor returns each indexed rule exactly once | 23 verified, 0 errors, under 1 s |
| `ReducerCheckpoint.dfy` | exact immutable reducer checkpoints resume to the same state and retain the same result prefix as uninterrupted execution; ordinal-ranked concurrent candidates lie on one deterministic trajectory | 10 verified, 0 errors, about 1.0 s |
| `WeightedCheckpointSlot.dfy` | one exact key retains only a positive-weight latest checkpoint; replacement cannot regress or exceed aggregate capacity, and other-key eviction only releases charge | 11 verified, 0 errors, about 0.8 s |
| `OrderIrrelevance.dfy` | deterministic successor/rule reordering changes discovery order but preserves the least positive denotation | 3 verified, 0 errors, about 0.9 s |
| `ReadRankCertificate.dfy` | local edge inequalities plus a well-founded witness successor certify exact shortest remaining 0/1 storage-read distance, including zero-cost cycles | 18 verified, 0 errors, about 1.0 s |
| `SealedVectorOrder.dfy` | exact membership, unique ordinals, and strict `(rank, ordinal)` order admit one unique sealed vector; membership-only and rank-only tie checks admit order drift, while duplicate ordinals alias compact work identities | 23 verified, 0 errors, about 1 s |
| `SealedPlanReducerComposition.dfy` | reversing one accepted vector into the concrete right-edge stack drains exactly in canonical order; any accepted vector for the same expected set yields the same trace; push-without-reverse changes the trace | 8 verified, 0 errors, under 1 s |
| `ReducerReadAhead.tla` | dynamic read-ahead cannot change canonical integration; active plus ready work stays inside the request window; ready chunks remain pinned; cancellation freezes semantics | 490 distinct states, under 1 s |
| `ServiceLifecycle.tla` | physical and weighted-response reservations have separate lifetimes; in-flight, pinned, and reusable owners retain memory across completion/rotation; publication is epoch-safe | 653 distinct states, under 1 s |
| `DescriptorCoalescing.tla` | equal request-local physical descriptors have at most one flight while distinct logical occurrences integrate in stable order | 106 states, under 1 s |
| `ProgressCheckpoint.tla` | cancellation freezes semantics while atomic latest-only exact-context reducer checkpoints never regress, cross epochs/contexts, or masquerade as answers | 219 states, under 1 s |
| `AtomicAttempt.tla` | partial and failed transport attempts never integrate or publish; overlapping retries remain charged and one logical occurrence integrates at most once | 374 states, under 1 s |

Mutation configurations must kill arbitrary-response integration, lending the
canonical reserve, post-cancellation integration, early capacity release, and
late old-epoch publication, duplicate equal-descriptor flights, incomplete or
old-epoch checkpoints, post-control computation, checkpoint regression,
cross-context restore, progress treated as a completed answer, freeing the
request window merely because I/O completed, evicting a pinned response,
ignoring aggregate retained-response capacity, and freeing pinned memory at
cache rotation.
Atomic-attempt controls additionally kill partial-response integration,
duplicate late-success integration, and partial-response cache publication.

Provisional iteration gate: every leaf model targets under two seconds and
must verify in under five seconds on the development machine; the entire fast
suite targets under seven seconds and must finish in under ten seconds. Four
independent Dafny batches, the randomized campaign, and five isolated TLC
family JVMs overlap. Within a family, the valid model and its mutants reuse
one JVM; unrelated families never do. The runner maps TLC error constants
through `EC.ExitStatus`, requires exact success/safety-violation outcomes, and
terminates explicitly after each family. The gate also requires the four Dafny result counts to
   sum to the expected 536 obligations, so accidentally dropping a leaf or claim
does not manufacture a faster passing gate. Every TLC invocation has a
distinct `java.io.tmpdir`; without that
isolation, concurrent TLC JVMs were observed deleting or corrupting shared
extracted standard-library files. Ten isolated four-model parallel probes and
repeated complete gates were reliable. Three consecutive complete runs of the
former 369-obligation gate took 4.36--4.72 seconds (median 4.58 seconds). The
former gate checked 451 Dafny obligations, all TLC models and required mutation
kills, and 16,000 randomized refinement checks including twenty-one executable
mutation controls. Three consecutive runs took about 4.94--5.39 seconds
(median 5.10 seconds); the larger 160,000-check campaign took 6.07 seconds.
The current 536-obligation gate keeps the record-framing and simplified
three-array witnessed-edge rank certificate, replaces four rejected
reverse-join leaves with the 16-obligation static reverse-frontier proof, adds
logical-scan cursor, one-value/dematerialized-buffer normalization, and bounded
sidecar leaves, proves that ordinal-ranked exact checkpoints lie on one
deterministic trajectory, and explicitly composes exhausted result-set
completeness and uniqueness with the history-free scalar exact count,
runs 18,000
randomized checks with twenty-two focused mutation controls, and
manifest-checks six independent local source bridges. The
public-schema bridge checked 124 generated schemas, all 31 non-empty rule-arm
masks, exact independently reconstructed persistence IDs, four syntax/order
variants, duplicate-arm collapse, and 19 rejection families. The normalized
compiler bridge checked 248 cases, 1,408 semantic rules, and 744 exact index
comparisons. The ranked sealed-plan bridge adds 32 edit-loop cases and nine
controls; a separate two-seed qualification run checked 2,000 plans against
Bellman--Ford and exact permutation replay. A complete gate including 512
physical-scheduler completion permutations plus 24 checkpoint publication
orders, eleven exact-context mutations, six cancellation prefixes, and 64
CAS-contention rounds took 6.16 and
6.52 seconds in two consecutive former 495-obligation runs. Replacing the
forward-only static index leaf with one direction-parameterized forward/reverse
index proof raised the guarded total to 497 obligations. Adding the independent
sealed-vector uniqueness proof, including explicit unique-ordinal rejection,
raised it to 41 leaves and 520 obligations. Composing that vector with the
concrete right-edge stack raised the suite to 42 leaves and 528 obligations.
The exact-count composition now raises the current suite to 43 leaves and 536
obligations; its leaf verifies in about one second. Three consecutive complete
gates took about 7.8 seconds, below the ten-second hard ceiling but above the
seven-second soft target. The largest TLC leaf—one valid
checkpoint model and six mutants—took 0.78 seconds.
This is narrow source evidence for parser normalization and the reusable
semantic compiler seam plus prototype evidence for rank/order, not acceptance
of production replacement rank/order,
traversal, cursor, adapter, or storage code.
The fourth bridge checks the minimum authenticated edge cursor: its 1,364-byte
fixture passed 15 exact-context mutations and 14 controls for tampering,
expiry, key rotation/retirement, bounds, same-token `after`/`before`, absence
of public private-state pointers, and forced private-key collision handling.
This validates the codec shape and reusable secure-format primitive, not the
current Relay integration.
The fifth bridge checks a source-shaped request-local physical shell over 512
generated descriptor/coalescing/completion schedules. Every fully active run
integrated the canonical sequence, every canceled run froze at a canonical
prefix while draining physical calls, and the logical window stayed within
width eight. Integrate-any, omitted-position, physical-end-resume, and post-
cancel integration controls were killed. This remains a pure deterministic
shell, not production executor refinement or evidence about backend thread
safety.
A 2026-08-14 run after adopting the explicit iteration policy completed in
5.41 seconds wall time with 162 obligations; after adding the concrete stack
and undelivered-lookahead refinements, 196 obligations plus 20,000 randomized
checks completed in 5.63 seconds. After adding the sealed static-consumer-index
refinement, explicit unique-rule-ID invariant, and incremental reverse-join
cost proof, exact physical-range identity, bounded rank fields, and weighted
response ownership, typed forward/reverse grounding, and lazy forward-producer
refinement, the bidirectional denotation theorem, and the exact reverse
producer, and then simplifying cursors from per-result prefix commitments to
an authenticated exact context, fixed positive page size, and delivered
ordinal, then replacing the page-start/subtraction abstraction with exact
Relay edge-cursor `after`/`before` windows, composing internal boundary state
with private lookahead, erasing
proof-only result history from runtime state, and bounding the page/lookahead
segment, relating scalar runtime progress to the exact pending segment, and
adding the owned-transient freeze/fork refinement, 333 obligations plus 16,000
edit-loop randomized checks completed in 7.50 seconds under the former
monolithic Dafny invocation. Parallelizing the already independent Dafny
leaves and then adding the seven-obligation cache-boundary refinement produced
the former 369-obligation gate. Adding the 26-obligation authenticated-edge
refinement and the original 22-obligation Relay/checkpoint execution refinement
produced a 417-obligation gate. The bounded target driver, combined concrete
runtime, full-state ownership model, typed EACL path bridge, and direct target-
driver/Relay composition produced the former 451-obligation gate without
breaching the edit-loop budget. Every accepted Dafny leaf remained below two
seconds. The record-framing leaf and witnessed-edge certificate simplification
first produced the former 464-obligation gate while also refuting ambiguous
unframed plan concatenation. Static transposed reverse traversal then deleted
46 obsolete join/type obligations, added 16 directly relevant frontier
obligations, and removed the randomized Cartesian-join campaign.
A failure triggers
interface/state simplification before additional lemmas. This is a design
heuristic, not runtime evidence; each formal cost claim still requires
generated-code and backend benchmarks.

The proof workflow follows a stricter version of that rule:

1. keep semantic, lifecycle, and physical-cost claims in separate leaf models;
2. run the affected leaf while editing and the complete gate before accepting a
   design step;
3. require a deliberately broken variant for every concurrency/lifecycle
   safety claim;
4. treat a slow or brittle proof as evidence that the state boundary or claim
   is too coupled, unless the obligation is irreducibly hard mathematics;
5. never infer JVM, heap, network, or storage bounds from solver speed.

The project-local edit commands are:

```sh
sh target/exploration/stable-discovery/verify-leaf.sh HistoryFreeReducer.dfy
sh target/exploration/stable-discovery/verify-leaf.sh DescriptorCoalescing
sh target/exploration/stable-discovery/verify-fast.sh
```

The first measured 0.75 seconds; the second, including its required mutant,
measured 1.19 seconds. The third is the complete coverage-counted gate.

The speed gate is also an architecture test. A proof that needs large coupled
state, extensive trigger tuning, or long solver searches is not accepted just
because it eventually verifies. The default response is to split the claim,
remove ghost/runtime state, or strengthen the interface invariant until the
proof is local and cheap. The failed coupled grounding proof already caused
the refinement to be split into two directional lemmas; the history-bearing
reducer proof was replaced by the smaller admitted/processed partition. Fast
verification is not proof of a fast implementation, but slow verification is
useful evidence of excessive semantic coupling that is likely to damage both
implementation iteration and runtime reasoning.

The rejected reverse-join model supplied a direct example. Its first encoding
timed out while recursively rebuilding opposite-side counts. A later scalar
encoding verified quickly, but the static sealed rule index exposed the more
important fact: reverse lookup has no dynamic second join side at all. Deleting
the join state, four obsolete proof leaves, and the corresponding randomized
family made both the model and proposed runtime smaller. The replacement
`StaticReverseFrontier.dfy` proves ordinary exact traversal of static
transposed producers in about one second.

The independent executable edit gate now runs 2,000 cases in each of nine
families: finite cyclic graph traversal, static consumer indexing, rank
certificates, runtime-stack representation, logical scan cursor replacement,
one-value physical-buffer normalization, descriptor identity, typed grounding,
and fixed-page-size forward/backward pagination. The resulting 18,000 checks
plus twenty-two focused mutation controls take under one second in an
otherwise idle warm nREPL and about 2.3 seconds while competing inside the
complete parallel gate. They compare unrelated deterministic successor orders
with an independent least-closure oracle; check exact admission, result
uniqueness, deterministic replay, page concatenation, and exact checkpoint
resumption; and compare rank generation with an independent reverse-Dijkstra
oracle on 0/1 cyclic graphs. Mutants without admission deduplication, without
shortest-path lower inequalities, without well-founded witness hops, with
canonical successors appended to the concrete stack in the wrong order, with
the last indexed consumer dropped, with a duplicate sealed rule ID, with
physical scan position omitted from a coalescing key, and with an out-of-range
witness hop accepted before arithmetic are detected. Cross-resource-type
forward-arrow and reverse-consequence mutants are also detected. Wrong-
direction reverse graph, zero-page non-progress, and cross-page-size cursor
mutants are detected. Larger multi-seed runs belong in qualification so a
random campaign does not dominate the edit loop.

The runtime-shaped graph family now executes three independent forms against
the same oracle: the observation-rich reducer, a history-free persistent
right-edge reducer, and an owned-transient reducer that freezes and forks at
every page. Both runtime forms retain no result history, obey the page-plus-one
bound, and reproduce the exact sequence across page sizes. Allocation results
and the transient trust boundary are recorded in `IDENTITY_BENCHMARK.md`.

## Discovery scheduling

No fixed DFS/FIFO/round-robin policy dominates. In the abstract simulator, a
direct result placed after a dead chain of 100,000 nodes cost DFS 100,002
steps, FIFO two, and round-robin quanta 4, 16, and 64 cost 5, 17, and 65.
Conversely, a first result 100 nodes down one branch with 4,535 irrelevant
siblings cost DFS 102 steps and FIFO/round-robin 4,637. A duplicate-heavy
fixture also changed which result appeared first. Scheduling is therefore a
cost problem constrained by stable order, not a semantic fixed-point problem.

The minimum policy is deterministic storage-cost-ranked DFS. On the sealed
ground rule graph, assign each permission node its shortest remaining static
storage-read distance to the requested root: self-permission edges cost zero
and arrow-permission edges cost one. The compiler attaches a proof-carrying
rank certificate. The checker verifies every edge's lower-bound inequality and
one witness successor, witness-edge cost, and strictly decreasing hop count per
non-root node. The witness proves attainability; the inequalities prove no
path is cheaper. This is linear to check, handles zero-cost cycles, and does
not put the certificate generator or shortest-path implementation in the
trusted semantic reducer. A direct-relation seed has lower bound
`1 + distance(head, root)` and an arrow-to-relation seed has lower bound
`2 + distance(head, root)`. Consumers use `distance(head, root)` plus zero for
self or one for an arrow. The compiler orders each static producer vector once
by `(rank, canonical-rule-ordinal)` and validates it before sealing. Dynamic
scan values retain certified backend index order. The reducer does not sort or compare
canonical bytes at runtime. The rank certificate, vector-order version,
scan-order ABI, and algorithm version are part of the plan fingerprint and
order ABI; `CANONICAL_ORDER_CONTRACT.md` defines the composition.

This rank is only a lower bound; it does not know whether a scan is empty or
its cardinality. It is nevertheless materially better than byte order. In an
executable DataScript fixture where a subject belonged to 2,000 groups, none
of those groups parented a document, and the subject directly read two
documents, the current byte-sorted stable candidate needed 2,033 backend
commands for the first page. The legacy global merge needed 2,002. A
REPL-only cost-rank substitution returned the exact same page with
`has-next=true` after one backend command, consuming and fetching two values.
This is adversarial evidence for cost ranking, not yet a general benchmark or
an accepted runtime implementation.

Order affects prefixes but not denotation: `OrderIrrelevance.dfy` proves that
two valid programs with identical root and successor sets have the same least
reachable closure even when their deterministic sequences differ. Correctness
therefore permits empirical policy changes provided that each policy is total,
stable for a sealed plan/basis, cursor-bound by the order ABI, and retains
exact admission deduplication.

## Datahike physical-read probe

A disposable 2,048-server Datahike database on local MinIO used a serving
cache of 16 entries and 64 fixed scan descriptors. All results matched a
sequential oracle.

With local MinIO, width 1 used 40 GETs and 162 ms. Widths 2, 4, 8, 16, and 32
used 42, 48, 56, 64, and 71 GETs respectively, with no monotonic latency gain.
With 10 ms injected in each network direction, width 1 took 1,171 ms; widths 2
and 4 took 748 and 739 ms; widths 8, 16, and 32 regressed to 765, 842, and
1,021 ms. Their GET counts were 40, 42, 48, 56, 66, and 72.

For 32 copies of one descriptor under injected latency, sequential execution
used 3 GETs and 87 ms. Widths 2, 4, 8, 16, and 32 used 5, 7, 11, 19, and 35
GETs and took 109, 113, 234, 498, and 943 ms. Datahike/Konserve does not
single-flight these cold identical reads. Request-local descriptor coalescing
is therefore a requirement, not an optional micro-optimization. The useful
outer width on this fixture is 2--4; production S3 and LMDB-tier measurements
must calibrate the deployment default independently.

An empty LMDB frontend over the same delayed MinIO store changed the cold-read
curve. Widths 1, 2, 4, 8, 16, and 32 took 1,168, 749, 599, 416, 249, and 193
ms while issuing 39, 40, 42, 44, 45, and 49 S3 GETs. Once the exact nodes were
persisted in LMDB, all repeated scans issued zero S3 GETs and completed in
roughly 0.7--1.7 ms median across widths, although concurrent runs had larger
scheduler outliers than width one in this small sample. Thirty-two copies of
one descriptor against an initially empty LMDB tier issued only 2--4 GETs, but
concurrency did not improve its wall time. The tier substantially suppresses
repeat-read amplification, but it neither establishes descriptor single-flight
nor justifies one global width for cold S3, warm LMDB, and direct-S3 fallback.

A separate 4,096-value direct-S3 probe compared consuming exactly `P`, an empty
terminal probe after `P`, and eager `P+1`. At the exact end of the index, all 54
cases across widths 1/8/64 used two GETs; the empty probe reused the already
loaded nodes. That did not generalize. A streamed pass located the actual PSS
leaf loads, and fresh connections immediately before all fifteen observed
interior boundaries showed `P=1` using two GETs while `P+1` used three. Direct
S3 must therefore remain demand-lazy: eager exhaustion detection can fetch the
next leaf before semantic demand. Terminal strategy is a topology-specific
physical choice, not part of the public order ABI.

The same source-shaped probe was then run in fresh JVMs against H2 2.1.214,
loopback PostgreSQL 15, and strongly-consistent DynamoDB Local. All concurrent
scan results at widths 1/2/4/8/16 matched the captured-basis sequential oracle.
All three stores exposed the same fifteen interior PSS leaf boundaries, and
every `P+1` case performed one extra backing `SELECT` or `GetItem`. Thirty-two
identical cold descriptors amplified backing reads exactly with width on all
three stores and became slower. Unique high-latency reads sometimes became
faster at wider widths only by amplifying physical reads. The detailed counts,
backend lifecycle defects, and failure-classification audit are in
`PHYSICAL_BACKEND_AUDIT.md`.

## Backend execution matrix

The semantic capability and the operator's physical width are separate.
Adapters certify immutable-basis read safety and scan order; a deployment
profile selects bounded read-ahead for its storage/cache topology. A request
timeout is not a maximum physical lifetime.

| Backend/topology | Immutable snapshot concurrency | Physical cancellation | Initial width policy |
|---|---|---|---|
| DataScript Clojure | empirically certified on the shared 22-shape fixture | no backend cancellation channel; interrupt only | default 1; CPU-width tuning is optional |
| DataScript ClojureScript | immutable semantics, but one JS event loop provides no parallel blocking reads | not applicable as physical parallelism | 1 |
| Datomic Pro peer / DynamoDB | immutable DB/index segments support concurrent peer reads; memory-peer fixture certified | no EACL-to-index-read cancellation channel established | conservative 4, then tune against peer cache and DynamoDB |
| Datahike memory/file | fixture certified at widths 1--32 on captured DB values | no storage cancellation channel | default 1 for memory/file unless measured otherwise |
| Datahike direct S3 | snapshot-safe; cold identical misses amplify without coalescing | no certified interruption of synchronous AWS SDK reads | request-local coalescing, initial width 2--4 |
| Datahike LMDB/S3 | snapshot-safe; persistent frontend removes repeat S3 reads | no certified interruption of an in-flight backend miss | initial width 4; permit topology tuning after cold/warm measurements |
| Datahike JDBC | captured-basis H2/PostgreSQL probes exact; concurrency can amplify `SELECT`s | no storage cancellation channel; injected transient escaped once | coalesce first; default 1; calibrate bounded widening per database topology |
| Datahike DynamoDB | captured-basis local-emulator probe exact; wider reads amplify `GetItem`s; backend erases storage failure causes | no certified AWS SDK cancellation acknowledgement | strong-read/publication contract required; coalesce first; width 1 pending real DynamoDB qualification |

For each of DataScript JVM, Datomic memory peer, Datahike memory, and Datahike
file, the same 22 forward/reverse scan shapes ran 880 times at each width 1, 2,
4, 8, 16, and 32. A captured old adapter was then read 512 more times at width
16 while its connection advanced. That is 5,792 operations per backend; every
result matched the sequential oracle and each old basis survived the write.
This is empirical adapter certification, not a universal implementation
theorem or a remote-storage latency result.

Datomic's primary documentation says index/log segments are immutable and can
be consumed without coordination, a peer-cache miss ordinarily costs one or
two segment fetches, and Valcache retains immutable segments on local SSD. See
the Datomic [index model](https://docs.datomic.com/indexes/index-model.html),
[Valcache](https://docs.datomic.com/operation/valcache.html), and
[synchronization](https://docs.datomic.com/transactions/client-synchronization.html)
documentation. Those facts support semantic parallel reads; they do not pick
EACL's service width or prove cancellation.

The current adapter field named `traversal-execution` should therefore not
encode one alleged universal width. The replacement contract should expose:

- immutable-basis parallel-read safety and exact scan semantics (adapter);
- whether a real cancellation handle exists and what return means (adapter);
- service-wide physical and response-weight capacity (host);
- per-request read-ahead and request-local descriptor coalescing (host);
- topology-specific calibrated width and retry amplification (deployment).

No topology may claim a finite maximum physical lifetime merely because EACL
has a deadline. If the backend cannot terminate or acknowledge cancellation,
the charge remains live until actual return and the service needs admission
backpressure rather than fictitious reclamation.

## Open proof obligations

1. Complete the independent source-refinement bridge specified in
   `SOURCE_REFINEMENT.md`. Public parsing/normalization and the reusable
   semantic rule/forward-index seam now pass bounded executable checks. Still
   required are replacement grounding/traversal, reverse indexes, rank
   certificate/static vector order, physical descriptor identity, and
   root-result identity mutants.
2. Preserve the exploration-only forward prototype's exact-denotation,
   stable-replay, logical-work, and low-allocation results when the replacement
   is integrated through the public orchestration path. The prototype passes
   nine recursive fixture families and the 2,000-dead-branch adversarial case;
   its kernel latency is not yet comparable with public API latency.
3. Audit the exact stale source, old formal models, generated artifacts, claim
   manifests, and counters that will be deleted after the legacy engine has
   served as the final benchmark oracle.
4. Complete remote qualification where local evidence cannot decide the
   contract: repair and test Datahike DynamoDB failure-cause preservation,
   publication visibility, and real-AWS retry/amplification; test each intended
   JDBC engine; and measure Datomic Pro/DynamoDB. Local S3/JDBC/DynamoDB probes
   already establish that descriptor coalescing and demand-lazy `P` are
   mandatory defaults, not optional tuning.

No runtime implementation should begin until these obligations and their
mutation controls are closed or explicitly rejected by evidence.
