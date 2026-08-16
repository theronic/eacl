# Runtime data-structure refinement contract

Status: exploration source-refinement requirement. Logical asymptotics do not
transfer to production unless these representation rules hold.

## Canonical stack

The formal models write the canonical stack with its next work at index zero:

```text
abstract-next = admitted-in-canonical-order ++ old-tail
```

Production Clojure must not implement that expression with front insertion,
repeated `into` into a vector prefix, or a chain of retaining `subvec` values.
The intended representation is a persistent vector whose canonical head is at
the right edge:

```text
concrete = reverse(abstract)
head     = peek(concrete)
tail     = pop(concrete)
next     = into(tail, reverse(admitted-in-canonical-order))
```

`RuntimeStackRefinement.dfy` proves this representation implements the
abstract head, pop, and canonical replacement exactly. `peek`/`pop` are
effectively constant time for Clojure's 32-way persistent vector and appending
`k` successors costs `O(k)` expected/amortized host work. The source benchmark
must confirm allocation; the solver does not prove Clojure implementation
complexity or trie retention.

Every stack work value has a unique admitted logical identity. Do not use lazy
sequences as the authoritative frontier: they can retain generator closures,
database values, or earlier sequence heads and obscure memory accounting.

## Owned transient builders

Persistent values are the cursor/checkpoint authority, but they need not be the
per-transition mutation mechanism. The canonical reducer thread owns transient
builders for the right-edge stack, exact admitted set, and—when enabled—the
readable-work vector. Only that owner may call transient lookup or mutation.

At a page boundary, cooperative cancellation/deadline checkpoint, cache
publication, asynchronous `NeedRead` yield, or thread handoff:

1. call `persistent!` on every live top-level builder;
2. atomically retire the active owner state;
3. publish or retain persistent values only;
4. if execution continues, create fresh transient branches from the frozen
   values under the new owner.

No future, backend callback, physical worker, cache entry, cursor, or concurrent
request receives a transient reference. Two requests using the same cursor
fork independently from the same persistent snapshot. Use after freeze and
mutation through the wrong logical owner are source-contract violations even
where the host library does not detect them.

A synchronous width-one adapter may execute a read inline on the canonical
owner and keep the builders live when no reference escapes, no callback is
registered, and the same stack frame integrates the complete response. A
remote/asynchronous driver freezes before submission or suspension and forks
when canonical integration resumes. The constant freeze/fork boundary is
measured separately because it may occur once per remote command.

`ConcreteHistoryFreeRuntime.dfy` proves the complete right-edge
stack/admission/count/output hot loop refines the history-free reducer.
`OwnedTransientSnapshot.dfy` then proves ownership/freeze/fork for that full
concrete state, not a proxy set. Local Clojure controls confirm branch
isolation and rejection after `persistent!`; bytecode inspection shows
same-thread ownership is not enforced by the current transient hash-map
`ensureEditable`, so EACL must enforce it.
`IDENTITY_BENCHMARK.md` records the allocation evidence. CLJS behavior and the
final engine remain release qualification obligations.

## Readable-work index

The host read-ahead index is the exact scan-only projection of the materialized
canonical stack. It uses the same right-edge convention:

- popping a scan head pops the readable head;
- popping pure work leaves the readable vector unchanged;
- newly admitted scan work is appended in reverse canonical order;
- speculation reads at most the configured number of values from the readable
  right edge;
- a work identity, not a copied backend response or mutable future, is stored.

`ReadableWorkIndex.dfy` proves the abstract projection update. The source
bridge must compare the concrete readable vector with a slow `filter scan?`
oracle after every generated transition in bounded traces.

Do not instantiate this index when effective physical width is one. That path
reads only the canonical stack head and allocates no speculative descriptor,
pin, future, or readable projection. Width-one and indexed drivers must return
the same reducer sequence; the zero-width-difference source mutant deliberately
constructs the index in DataScript and must fail its allocation gate.

## Exact admitted set

The hot identity is composed only from compact internal values:

- dense sealed-plan node/rule ordinals;
- tagged work kind;
- internal numeric resource/subject EIDs;
- compact static-rule cursor offsets.

A logical scan occurrence key deliberately excludes its changing logical
resume bound, physical fetch limit, fetched-end position, and response offset.
The exact logical exclusive bound lives in the frontier frame for that already-
admitted occurrence. Fetch limit/end and response offset live only in the
request-side accelerator and equality-complete physical descriptor. Advancing
the frame replaces pending state rather than growing exact admission once per
storage chunk.

External strings, printed maps, backend handles, futures, response vectors,
and public cursor bytes are forbidden in admitted identities. This reduces
allocation and avoids attacker-chosen string-hash collision families in the
main exact-dedup set. Expected hash-map/set complexity remains a trusted JVM
assumption, not a worst-case theorem. A collision-heavy source test must still
establish correctness, and resource limits must bound work even if hash access
degrades.

On the JVM, each semantic work kind should use an immutable specialized key
class containing exactly its primitive/internal identity fields, with checked
total equality and hash behavior. The one-million-entry exploration fixture
retained about 42.7--43.1 MB for a specialized `(node, eid)` key set versus
138.6--140.1 MB for vector keys. This is a representation recommendation, not
a portable byte theorem. Packed longs require a proved injective range and
are not the default; mutable bitmaps are ineligible as checkpoint authority
unless a copy-on-write refinement makes freeze cardinality-independent.

For CLJS, all numeric fields must stay within the certified safe-integer range.
For JVM backends, adapters must reject internal IDs that cannot be encoded
injectively at the portable boundary.

## Forward state

Forward resource lookup stores:

- right-edge work vector and readable projection;
- exact admitted identities;
- current page, delivered ordinal, pending one-result lookahead, the optional
  constant-size last-delivered result identity used to validate an `after`
  cursor checkpoint, and counters;
- compact cursor `(grant-id, static-consumer-offset)` only while walking the
  sealed immutable consumer vector.

It does not store per-goal grant buckets, consumer buckets, pair sets, peer
snapshots, or a generic emitted-result set. A root grant's admitted work
identity is the exact result-dedup authority.

## Reverse state

Reverse subject lookup stores the same minimum traversal authority as forward:

- a right-edge work vector;
- exact admitted `(permission-node-ordinal, resource-eid)` goals and scan work;
- current page/lookahead, delivered ordinal, and scalar counters.

The sealed rules-by-head-node vector is static plan state. Processing one goal
walks that vector and schedules direct base-owner scans or transposed
predecessor goals. There are no dynamic grant buckets, consumer buckets,
Cartesian pair sets, or peer cursors. A compact implementation may represent a
deferred rule scan as primitive node/rule/resource fields, but the exact work
identity remains checkpoint authority.

## Scan chunks

A physical response is fully realized and validated into one bounded vector
before reducer integration. Physical chunks flatten to the adapter's certified
value order, but a physical chunk is not automatically one logical admission
batch: the overlap counterexample proves that variable eager batch width
changes first-discovery order.

The accepted design consumes exactly one ordered scan value per canonical
reducer transition. Value-derived work is followed by the same scan occurrence
at its exact logical exclusive resume bound when nonterminal. The right-edge
representation appends that sequence in reverse. Physical chunk/cache
boundaries are not an ordering authority and are absent from the cursor/order
ABI.

The authoritative residual frame contains no response vector or physical
buffer position. A request-side table may pin one separately bounded immutable
projection chunk plus an offset as an accelerator. The table is dispensable:
dropping an entry rematerializes from the frame's logical bound and produces
the same remaining release sequence. Checkpoint and cancellation snapshots do
not retain the table. Live pins count against request and service response
capacity at every recursive depth. A configured newest-retained per-request
count (initial remote candidate: 16) bounds recursive accumulation before the
service-wide byte governor; the driver dematerializes evicted entries at the
cost of additional backend commands. A
cache hit is integrated through the same one-value normalization and admission
transition as a live response; it is not a set, result page, or subtree
denotation.

## Atomic logical preflight

Response validation may stage one bounded chunk of candidate successors, but
no candidate mutates authoritative state until exact membership filtering and
all logical cap deltas are known. Use validated natural values and
`increment <= limit - current`; never compute unchecked host addition first.
Rejection retains the original persistent snapshot exactly.

## Checkpoints and structural sharing

The complete result trace in `StableReducer.dfy` is proof observation, not a
runtime field. Concrete reducer/checkpoint state refines
`HistoryFreeReducer.dfy` and retains only a scalar discovered count. During a
page run, result values are bounded by the current page plus one lookahead.
After delivery, a checkpoint may retain the optional last-delivered identity
and pending lookahead, both constant-size in result count. A backward `before`
run starts from its computed previous-page boundary and treats the supplied
cursor edge as the one lookahead, so it retains at most `page-size + 1`
results. A checkpoint that contains an ever-growing delivered-result vector
fails source refinement even if all result values are semantically correct.

The request page size is validated against a configured positive maximum
before allocating the page, capturing a checkpoint, or replaying a cursor.
The authenticated cursor fixes the chosen size, but the server rechecks it
against the current cap; a previously valid token does not grandfather a
retired larger allocation policy.

Capturing a persistent reducer state is an O(1) reference operation, but a
retained checkpoint can keep old vector/hash-trie nodes live after the active
request advances. Therefore:

- checkpoint cache weight is measured retained heap, not entry count or
  logical node count;
- checkpoints are sparse and bounded by exact weighted admission;
- only the newest nonregressing checkpoint for one exact key is retained;
- eviction drops the entire state reference;
- initial publication is a synchronous atomic in-memory pointer replacement;
  there is no candidate backlog or serialization executor;
- async serialization, if ever added, walks state on separate bounded
  latest-only capacity and cannot delay or mutate the request.

This is the main place where persistent data makes capture fast but memory
non-obvious. Heap qualification must measure active-only, checkpoint-only, and
active-plus-checkpoint retained graphs.

## Acceptance tests

Before the formal cost claim reaches release source:

1. bounded transition traces compare concrete stack/readable operations with
   slow abstract sequence oracles after every step;
2. a mutation that appends successors in canonical rather than reverse order
   changes the sequence and is killed;
3. a mutation that pops the readable index for pure work is killed;
4. allocation scaling is measured for deep stacks, broad successor batches,
   reverse goal/scan work, scan chunks, and retained checkpoints;
5. heap dumps or JOL-equivalent retained-size measurements show no lazy-seq,
   `subvec`, response-vector, database-value, or future retention from the
   semantic frontier;
6. in-flight page values never exceed authenticated page size plus one
   lookahead; after delivery, checkpoint result values are limited to the
   optional last-delivered identity plus pending lookahead, and no complete
   delivered-result history is reachable;
7. the zero-branch DataScript gate in `BENCHMARK_PROTOCOL.md` passes;
8. two cursor forks from one persistent snapshot evolve independently, every
   cached value is persistent, retired builders reject use, physical callbacks
   cannot access builders, and every asynchronous read/reducer-thread handoff
   freezes before resume;
9. cold reads and exact projection hits produce identical transitions, an
   equal-set/different-order projection mutant fails, and no enumeration path
   consumes a flat subproblem denotation;
10. specialized JVM identity keys match the portable tuple oracle for boundary
    values, deliberate hash collisions, every work kind, and duplicate-heavy
    traces; page-boundary snapshot cost remains independent of admitted-set
    cardinality;
11. oversized fresh/join batches, exact-cap boundaries, and checked-integer
    edges prove all-or-none mutation; post-check, one-sided reverse append, and
    rejected-checkpoint mutants fail.
