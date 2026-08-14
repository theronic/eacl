# Exact-admission and checkpoint representation benchmark

Status: exploration microbenchmark, not a production performance claim.

The benchmark runs in the fresh project nREPL and uses the JVM thread-allocation
counter. Each shape receives four warmups. Small cases run 20,000 repetitions;
large cases run three repetitions with 100,000 unique grants. The benchmark
source is `identity_benchmark.clj`.

## Selected measurements

| Shape | Logical entries | Allocated bytes/run | Time/run |
|---|---:|---:|---:|
| persistent flat vector identity set | 8 | 2,016 | 0.00093 ms |
| transient flat vector identity set | 8 | 1,096 | 0.00072 ms |
| persistent flat vector identity set | 100,000 | 75,818,792 | 14.72 ms |
| transient flat vector identity set | 100,000 | 15,859,600 | 9.36 ms |
| persistent packed-long identity set | 100,000 | 69,415,512 | 11.53 ms |
| transient packed-long identity set | 100,000 | 9,455,040 | 6.85 ms |
| persistent right-edge vector push/pop | 100,000 | 34,392,992 | 2.24 ms |
| transient right-edge vector push/pop | 100,000 | 7,733,400 | 1.02 ms |
| transient flat vector identity set | 1,000,000 | 194,512,456 | 192.53 ms |
| transient specialized pair-key set | 1,000,000 | 207,168,352 | 81.05 ms |
| transient packed-long identity set | 1,000,000 | 130,481,512 | 134.24 ms |
| transient right-edge vector push/pop | 1,000,000 | 77,410,640 | 12.26 ms |

For the portable flat identity representation, transient ownership reduced
measured allocation by about 79% and elapsed time by about 36% at 100,000
admissions. For the right-edge stack cycle it reduced allocation by about 78%
and elapsed time by about 54%. The eight-entry transient stack was slightly
slower in this microbenchmark but allocated about 61% fewer bytes; transient
exact admission was both faster and smaller at eight entries.

Packing is not the primary win. It saved less than the ownership change and is
not universally available because backend EIDs and multi-field work identities
may not fit an injective 64-bit layout. Packed identities are therefore an
optional certified specialization, not the portable semantic representation.
Nested node-to-set representations and an exact inline-vector prefix did not
improve the measured portable baseline.

## One-million-entry retained heap

Post-GC process-heap deltas were measured with the result held strongly and
the same fresh nREPL otherwise idle. Three repetitions produced:

| Shape | Retained deltas |
|---|---:|
| flat vector keys | 140,119,960; 138,581,080; 138,581,080 bytes |
| specialized immutable pair keys | 42,792,656; 42,682,032; 43,056,256 bytes |
| boxed packed longs | 74,572,064; 74,572,064; 74,572,064 bytes |

These are JVM measurements, not language-level byte theorems. They strongly
favor immutable specialized key classes with primitive final fields and exact
`equals`/`hashCode` over portable vector identities on JVM backends. The
two-field fixture exactly matches forward root-result identity `(node, eid)`;
other work kinds need their own complete field layouts and may retain more.
Packing is optional only when a checked injective encoding exists. CLJS needs
separate measurement and may retain the portable representation.

A mutable primitive bitmap can be smaller for favorable EID distributions,
but it is not the default checkpoint representation: copying or serializing
the full bitmap at each page/async boundary would make checkpoint cost depend
on the delivered prefix. A later chunked copy-on-write bitmap specialization
would need an exact set-refinement proof and engine-level page-boundary
allocation benchmark before replacing the persistent authority.

## Freeze and fork

Converting a persistent snapshot to a transient branch and immediately freezing
it allocated about 184 bytes for both 8-entry and 100,000-entry snapshots in
this run. Forking, admitting one new identity, and freezing allocated about 280
bytes for the small snapshot and 776 bytes for the large snapshot. These
measurements support the expected size-independent `transient`/`persistent!`
boundary and path-local copy on first mutation; they are not a language-level
complexity proof.

Repeating the boundary with a one-million-entry specialized pair-key snapshot
allocated about 184 bytes for freeze/fork and 240 bytes for fork, one new
admission, and freeze. The observed operation remained independent of snapshot
cardinality because it shares the immutable HAMT and copies only the mutated
path.

An executable control also established:

- two transient branches from one persistent snapshot evolve independently;
- the original snapshot does not change;
- freezing preserves the exact value;
- using a builder after `persistent!` is rejected.

Local bytecode inspection found that the current Clojure transient hash-map
implementation invalidates the edit token at `persistent!`, but
`ensureEditable` only checks that the token is non-null; it does not compare
the stored thread with the current thread. EACL must therefore enforce linear
ownership itself. Only the canonical reducer owner may mutate a transient.
Physical workers return immutable complete chunks and never receive reducer
builders. An asynchronous read or thread handoff freezes first and forks on
the integration owner. A synchronous width-one read may remain inside the same
owner frame without freezing.

## Design consequence

The minimum runtime representation should use request-owned transients for the
hot forward admitted set, canonical stack, and optional readable index. Page,
cancellation, deadline, checkpoint, asynchronous-read, and thread-handoff
boundaries freeze them into persistent snapshots. A resumed or concurrent
cursor forks independent transients from that snapshot. Cached checkpoints
contain persistent values only; no transient or owner token escapes the
request.

Reverse traversal uses the same top-level owned stack and admitted-goal set;
there are no nested goal-cell buckets to freeze. Deep sparse chains still
require compact primitive work identities because depth-first cost order may
retain one deferred static-rule scan per level.

Before release, the same comparison must be repeated inside the real forward
and reverse engines under DataScript, Datomic, and Datahike fixtures, including
duplicate-heavy inputs, periodic checkpoint freezes, cancellation, concurrent
forks, freeze/fork once per remote command, CLJS transients, retained heap, and
GC—not just allocated bytes in this isolated model.

## Deep frontier and one-value buffer heap

`frontier_heap_benchmark.clj` measures source-shaped deferred scan frames and
one-value physical buffers after explicit full GC in a fresh JVM. Three trials
on the development host produced:

| Shape | Cardinality | Retained delta |
|---|---:|---:|
| authoritative prototype map scan frames | 100,000 frames | 10,944,960--10,944,984 bytes |
| specialized primitive logical scan frames | 1,000,000 frames | 53,419,880--53,422,688 bytes |
| specialized admitted keys plus compact logical stack | 1,000,000 keys + 100,000 frames | 48,066,736--48,450,720 bytes |
| specialized sidecar map entries | 100,000 entries × 64 primitive EIDs | 62,729,440--63,437,176 bytes |

The buffer-free map-frame result extrapolates to roughly 109 MB per million
before adding admission keys. The specialized logical frame is about 53.4
bytes including its persistent-vector reference. A separately mapped 64-EID
primitive sidecar entry, including its specialized key, is about 627--634
retained bytes. These are measured JVM deltas, not object-layout theorems or
RSS limits.

A separate structurally shared progress fixture started with 100,000 admitted
keys and 10,000 logical frames, then captured 64 checkpoints 100 admissions
apart. Retaining only the latest 116,464-unit state used 5,042,720--5,042,744
bytes. Retaining all 64 successive candidates used 6,391,160--6,391,184 bytes,
about 1.35 MB more despite HAMT/vector sharing. Candidate queues are therefore
not free. The initial design performs synchronous atomic latest-only in-memory
replacement; optional future serialization must use bounded latest-only
capacity.

`checkpoint_publication_benchmark.clj` separately measured the mechanics of
that local replacement in a fresh JVM. Across three trials, constructing and
nonregressively CAS-publishing a compact entry took 5.1--18.1 ns and about 56
allocated bytes per publication. Freezing/forking a 100,000-entry persistent
set, wrapping the complete state, and performing the CAS took 18.8--61.6 ns
and about 296 allocated bytes. These very small figures exclude exact cache-key
construction, weighted-map/LRU work, cross-request contention, and traversal;
they justify synchronous pointer publication over an async candidate queue,
not a production end-to-end latency claim.

The capacity design should initially charge at least 64 bytes per specialized
admitted key, 128 bytes per compact logical frame, and 1 KiB per live or
projection-cached 64-EID physical buffer, then calibrate upward against
production heap dumps. Request and service limits apply before acquiring a
buffer. A deferred request-side buffer is optional: the authoritative frame
stores the logical exclusive bound, so pressure may discard the buffer and
later rematerialize it without changing discovery order. The initial remote
profile additionally retains at most 16 deferred sidecar buffers per request,
about 10 KiB under the measured width-64 fixture; DataScript/effective-width-
one profiles retain none. The service-wide weighted cap remains authoritative
when many requests coexist.

Progress checkpoints deliberately contain no physical buffers or pins. Their
weight is derived from admitted keys, logical frames, page/lookahead, and fixed
metadata; the initial 64/128-byte charges conservatively exceed the measured
combined checkpoint fixture. Only the newest nonregressing checkpoint for one
exact query context is retained, and global weighted admission—not page count—
limits how many large checkpoints coexist. Projection-cache buffers retain
their independent weighted lifecycle. Production source must repeat the heap
measurement with its actual classes and validate declared weights against
active-only, checkpoint-only, projection-only, and structurally shared object
graphs.
