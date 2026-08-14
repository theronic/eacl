# Stable-discovery benchmark protocol and frozen baseline

Status: exploration-only benchmark contract. The harness and this report are
ignored by Git. No production source was modified to obtain these results.

## Purpose

The benchmark prevents a fast correctness proof from hiding an expensive
discovery order or an inefficient source refinement. It compares three
authorities through the public DataScript EACL API:

1. **legacy**: current default global ordered merge/FIFO behavior;
2. **byte-stable**: the current opt-in stable-discovery candidate, ordered by
   canonical rule bytes;
3. **cost-stable**: an exploration-only wrapper around the same candidate that
   orders work by certified static storage-read distance and canonical bytes.

The wrapper changes no engine source. It intercepts plan compilation through
the public kernel protocol, independently computes exact 0/1 distances for an
explicit root, and reuses the candidate reducer. It is a scheduler experiment,
not the proposed replacement implementation. In particular, its allocation
and retained-state costs still include the older symmetric candidate state
that the minimum design removes.
The replacement does not retain that wrapper's runtime byte comparison: it
seals static vectors in `(rank, canonical-rule-ordinal)` order and preserves
dynamic producer order as specified in `CANONICAL_ORDER_CONTRACT.md`.

## Reproduction context

- Repository commit at capture: `142882c56e2e4f0c4e37a5740fd0f0db96d066e9`
- Branch: `agent/stable-discovery-enumeration`, with the existing uncommitted
  candidate changes described by `git status`
- Host: Apple M4 Max Mac Studio, 14 cores, 36 GB RAM
- JVM: Temurin OpenJDK 26.0.2
- Backend: in-process DataScript at one immutable basis
- Harness: `source_benchmark.clj`
- REPL: fresh nREPL on port 60492 for this capture

The latency and allocation values are host- and JVM-specific. Backend-command
counts and exact result comparisons are deterministic workload properties.

## Measurement rules

1. Seed once per workload and select one immutable basis.
2. Construct one client per engine, disable completed-answer retention with
   `:cache {:remember-answers false}`, and reuse the client for warm samples so
   schema-plan compilation is not charged repeatedly.
3. Run one unreported warmup, then ten measured samples unless noted.
4. Measure one public `lookup-resources` call, including correct one-result
   `has-next` lookahead.
5. Count actual adapter scan commands, not reducer transitions or projections
   inferred from a plan.
6. Read same-thread allocated bytes from `com.sun.management.ThreadMXBean`.
   This is cumulative allocation during the request, not retained heap or RSS.
7. Separately request the complete bounded denotation from every engine and
   require exact set equality, exact expected cardinality, no duplicates, and
   correct `has-next` behavior.
8. Do not infer S3 GETs from logical commands. A Datahike/Konserve command may
   restore zero, one, or many storage nodes; direct physical GET/PUT counters
   remain a separate qualification gate.

## Adversarial non-recursive workload

Schema:

```text
definition user {}
definition group { relation member: user }
definition document {
  relation parent: group
  relation reader: user
  permission view = parent->member + reader
}
```

The user belongs to `N` groups. No group parents any document. The user is a
direct reader of exactly two documents. The request asks for one document and
must prove a second result exists. A globally merged or unlucky byte-DFS plan
opens every empty group-derived stream before it reaches the direct branch.
The static rank gives the direct seed cost 1 and the arrow-to-relation seed
cost 2.

All reported runs returned the same complete two-document denotation, no
duplicates, and `has-next-page? = true`.

### Scaling result

Ten warm samples per row, except the 500-group row, which uses 30 after a
ten-sample run encountered an isolated pause. Times are medians in
milliseconds; allocation is median same-thread request allocation in MiB.

| N groups | Engine | Commands | Median ms | Allocated MiB |
|---:|---|---:|---:|---:|
| 0 | legacy | 2 | 0.552 | 0.944 |
| 0 | byte-stable | 2 | 1.230 | 2.276 |
| 0 | cost-stable | 1 | 1.434 | 2.223 |
| 1 | legacy | 3 | 0.523 | 0.947 |
| 1 | byte-stable | 3 | 1.230 | 2.372 |
| 1 | cost-stable | 1 | 1.263 | 2.223 |
| 10 | legacy | 12 | 0.492 | 0.974 |
| 10 | byte-stable | 12 | 1.495 | 3.253 |
| 10 | cost-stable | 1 | 1.134 | 2.223 |
| 100 | legacy | 102 | 0.633 | 1.224 |
| 100 | byte-stable | 103 | 5.537 | 12.165 |
| 100 | cost-stable | 1 | 0.972 | 2.223 |
| 500 | legacy | 502 | 1.296 | 2.408 |
| 500 | byte-stable | 509 | 17.022 | 52.287 |
| 500 | cost-stable | 1 | 0.850 | 2.225 |
| 1,000 | legacy | 1,002 | 1.617 | 3.885 |
| 1,000 | byte-stable | 1,017 | 33.139 | 102.578 |
| 1,000 | cost-stable | 1 | 0.778 | 2.224 |
| 2,000 | legacy | 2,002 | 4.033 | 6.839 |
| 2,000 | byte-stable | 2,033 | 64.648 | 203.130 |
| 2,000 | cost-stable | 1 | 0.839 | 2.225 |

The important finding is not the speedup ratio on an in-memory database. It
is the change from work linear in irrelevant memberships to one storage scan.
At remote-storage latency the 2,001 avoided logical scans dominate. The
current byte-stable candidate is unacceptable: it combines the wrong order
with extreme allocation growth.

The zero- and one-group rows also expose the cost-ranked wrapper's remaining
local overhead: one fewer scan still allocates about 1.28 MiB more and, in the
captured zero-group run, took about 0.88 ms longer than legacy. That cannot be
waved away by the remote
result. The replacement direction-specific reducer and warmed sealed-plan path
must reduce this constant overhead; otherwise DataScript needs a justified
backend execution specialization that preserves the same semantic authority.

## Populated recursive workloads

The 200-account suite checked complete denotations for star, chain,
broad-union, empty-principal, and a 100-account mutual-recursion graph.

| Workload | Expected full results | First-page commands: legacy / byte / cost / prototype | Full commands: legacy / byte / cost / prototype |
|---|---:|---:|---:|
| star | 200 | 2 / 2 / 2 / 2 | 204 / 204 / 204 / 204 |
| chain | 200 | 2 / 2 / 2 / 2 | 201 / 201 / 201 / 201 |
| broad union, reader branch | 200 | 4 / 3 / 3 / 3 | 206 / 206 / 206 / 206 |
| broad union, legal-entity branch | 200 | 5 / 5 / 5 / 5 | 207 / 207 / 207 / 207 |
| star, unauthorized stranger | 0 | 1 / 1 / 1 / 1 | 1 / 1 / 1 / 1 |
| mutual `view`/`edit` recursion | 50 | 4 / 4 / 3 / 3 | 102 / 102 / 102 / 102 |
| mixed, ten chains | 201 | 2 / 2 / 2 / 2 | 202 / 202 / 202 / 202 |
| cycle | 31 | 2 / 2 / 2 / 2 | 32 / 32 / 32 / 32 |
| diamond | 4 | 2 / 2 / 2 / 2 | 5 / 5 / 5 / 5 |

Every engine and the exploration-only forward prototype returned the exact
least-fixed-point set from an oracle that reads fixture relationships but does
not call the EACL compiler, reducer, adapter, or public lookup API. Replaying
the prototype produced an identical sequence and logical trace. Static cost ranking did not
increase logical commands on these cases and saved one first-page command on
the mutual cycle. Full enumeration converged to the same work because all
reachable grants eventually had to be explored.

The current stable candidate retained roughly twice the legacy logical units
on the recursive star first page (maximum 145 versus 74). This is evidence
against retaining symmetric goal-cell/join machinery in forward traversal; it
is not a JVM-byte theorem. The source-shaped prototype removes that machinery.
Across twenty warmed first-page samples it allocated 6.8--120 KB depending on
the fixture, versus 1.6--7.3 MB through the current public engines. Its median
kernel latency was 0.003--0.045 ms. Those latency numbers exclude public query
validation, consistency selection, Relay encoding, and schema/plan lookup, so
they are not public-API speedup claims. The allocation and logical-command
results do establish that the minimum forward hot state itself is small and
does not require the old symmetric reducer.

`qualify-forward-runtime.sh` reproduces the 2,000-branch adversarial campaign,
all nine forward recursive cases, and all eight static reverse cases. It is deliberately a qualification tier rather
than part of the semantic fast gate: a fresh JVM twenty-sample run performs
seeding and engine benchmarking in about 11.7 seconds and consumes
substantially more CPU than the pure proofs. The complete formal gate remains
under ten seconds, while this script can be run whenever runtime or adapter
code changes.

## One-value normalization and detachable buffers

Eager integration of a physical chunk is rejected: the recursive overlap
fixture produces `[a c b]` at logical width one and `[a b c]` at eager width
64. The accepted prototype instead releases one logical value at a time while
fetching physical widths 1, 2, 7, or 64. Across nine forward and eight reverse
recursive cases, retained buffers and buffers deliberately dropped after every
release produced one identical exact sequence in all 153 comparisons.

Retaining a width-64 buffer preserved batching: full 200-account forward star
and broad-union traversals used 204 and 206--207 commands. Dropping every
deferred buffer preserved order but raised those counts to 399 and 401--402,
close to width-one I/O. Sparse chain/reverse cases were usually unchanged
because their scans had at most one useful value. This establishes the intended
policy: buffers are governed accelerators, not semantic authority. Keep them
on the normal hot path; omit them from checkpoints and dematerialize them only
at cancellation, checkpoint boundaries, or when retaining them would violate
the response-memory cap.

A separate 100,000-depth synthetic campaign applies the actual source-shaped
newest-retained sidecar helper at capacities 0, 1, 4, and 16. Across 400,000
retentions it never exceeded the cap; at width 64 the largest case retained
1,008 unread EIDs. This guards the deep/broad adversary that can otherwise
retain one roughly 627--634-byte buffer per recursive depth. Production must repeat
retained-heap measurement with real chunk objects and concurrent requests; the
count cap composes with, but does not replace, global weighted response
capacity.

The earlier isolated 200-account measurements showed the corresponding CPU
trade: one-value release greatly reduced first-page admission/stack/allocation,
while complete star enumeration added roughly one cheap transition per scan
value and was about 11% slower in that small warm-DataScript sample. This is a
frozen directional result, not an acceptance threshold. Production must
measure logical transitions separately from physical commands and cold/warm
backend reads; full count may use an explicitly order-insensitive eager path
because it exposes no pagination sequence.

## Static reverse workloads

The reverse prototype starts from one concrete root grant and traverses only
the sealed rules-by-head index, exact admitted goals, and scan work. It has no
dynamic grant/consumer buckets or joins. Exact full denotations matched the
independent oracle and every frozen engine.

| Workload/resource | Subjects | First-page commands: legacy / byte / cost / prototype | Full commands: legacy / byte / cost / prototype | Prototype max stack |
|---|---:|---:|---:|---:|
| star leaf | 1 | 4 / 4 / 4 / 4 | 4 / 4 / 4 / 4 | 3 |
| 200-node chain tail | 2 | 400 / 301 / 301 / 301 | 400 / 400 / 400 / 400 | 201 |
| broad-union leaf | 3 | 11 / 5 / 8 / 8 | 11 / 11 / 11 / 11 | 6 |
| mutual recursion, authorized even node | 1 | 198 / 198 / 198 / 198 | 198 / 198 / 198 / 198 | 100 |
| mutual recursion, unauthorized odd node | 0 | 200 / 200 / 200 / 200 | 200 / 200 / 200 / 200 | 101 |
| mixed chain leaf | 1 | 42 / 42 / 42 / 42 | 42 / 42 / 42 / 42 | 22 |
| cycle node | 1 | 62 / 62 / 62 / 62 | 62 / 62 / 62 / 62 | 32 |
| diamond leaf | 1 | 8 / 8 / 8 / 8 | 8 / 8 / 8 / 8 | 5 |

Across twenty warmed samples the prototype allocated about 29 KB--2.42 MB,
versus about 1.58--31.4 MB for legacy and 2.32--326.6 MB for the current stable
engines. The upper prototype value is the deep chain and exposes the remaining
representation gate: rank-first DFS retains one deferred sibling scan per
level. Production must encode those frames compactly and qualify million-depth
retained heap. The broad-union prefix also refutes universal scheduling
dominance: byte order found its two-result lookahead in five reads, while the
cost/prototype order used eight. This counterexample remains in the frozen
corpus; it cannot be averaged away.

Three additional focused source cases close schema-shape loopholes omitted by
the recursive fixture family: a zero-cost permission-alias cycle, overlapping
arrow-to-relation derivations, and a relation accepting both user and robot
subjects. Ten exact forward/reverse assertions passed. The arrow overlap emits
each document/principal once, and reverse filtering returns only the requested
subject type.

## Acceptance gates for the replacement

### Correctness: absolute

- Exact complete denotation equals an independent authorization oracle for
  every fixture and generated case.
- No page contains duplicates and concatenated pages equal one uninterrupted
  discovery sequence at the same basis.
- Replay, fresh traversals at different page sizes, and allowed physical
  completion reorderings do not change the sequence. Adapter physical chunk-
  size changes and arbitrary request-side buffer dematerialization must also
  preserve it under one-value normalization; eager whole-chunk admission and
  resuming from the physical fetched-end are mandatory negative controls.
  Reusing one cursor under a different page size rejects.
- Forward `after` and backward `before` traverse the same canonical sequence;
  backward pages remain in forward display order, short terminal pages do not
  overlap, and the same exact edge token works in both navigation modes.
- A backward request resumes at the computed previous-page start, performs at
  most page plus one result discoveries, and uses the final lookahead to
  validate the cursor edge. Resuming at exclusive end is a required negative
  control. Bare `last` is measured separately because it requires finite
  completion or an exact completed-answer hit.
- Cold traversal, exact ordered projection-cache hits, and cache-residency
  permutations produce the identical sequence. A complete fresh subtree
  denotation is never substituted into enumeration; the overlapping-admission
  counterexample remains a required negative control.
- Cursor/checkpoint misses replay the exact deterministic sequence to the
  authenticated ordinal.
- Checkpoints contain logical resume bounds but no physical response buffers,
  offsets, or pins. Restoring with an empty sidecar produces the same suffix as
  uninterrupted execution with retained buffers.
- Recursive SCCs, zero-cost alias cycles, overlapping derivations, empty
  prefixes, and `has-next = false` exhaustion all pass.

### Logical work: frozen workload envelopes

- The 2,000-group adversarial first page performs at most two backend scans;
  the intended result is one.
- The 200-account recursive first-page cases perform no more commands than the
  best result in the table above.
- Full enumeration performs no more than the table's command counts plus one
  diagnostic command attributable and documented by the implementation.
- A new counterexample where cost ranking loses is added to the corpus rather
  than hidden in an aggregate mean. No universal scheduling dominance is
  claimed.
- Exact-cap candidates succeed; one-over-cap candidates fail without changing
  reducer/checkpoint state. Deep reverse chains allocate no dynamic peer
  buckets or Cartesian-product staging vector; deferred static-rule work is
  measured separately and uses compact identities.

### Local CPU and allocation

- On the zero-group hot-plan DataScript case, median replacement latency must
  be within 0.25 ms or 25% of legacy, whichever allowance is larger.
- Median same-thread allocation must be at most 1.5 times legacy on the same
  case. The current cost wrapper fails this allocation gate and therefore is
  not accepted as the final implementation.
- On 100+ irrelevant branches, replacement latency and allocation must not
  scale linearly with the unvisited branch count.
- Datomic and DataScript report CPU/allocation separately; a remote-backend
  win cannot pay for an unexplained local regression.
- Publish persistent versus request-owned transient allocation separately for
  the admitted set and right-edge stack. Include page-boundary freeze/fork,
  duplicate-heavy admission, concurrent cursor forks, and eight-entry as well
  as 100,000-entry fixtures. Measure freeze/fork at every remote command as
  well as the synchronous width-one no-yield path. The isolated exploration microbenchmark is
  directional evidence, not a substitute for this engine-level gate.
- Report retained heap for portable tuple keys and every specialized JVM work
  identity at 100,000 and 1,000,000 admissions. Include deliberate hash
  collisions and duplicate-heavy traces. The exploration pair-key fixture
  retained about 42.7--43.1 MB per million, but production acceptance depends
  on complete real work identities and page-boundary snapshots.

### Physical backend qualification

- For Datahike direct S3, Datahike LMDB/S3, Datahike DynamoDB/JDBC, and Datomic
  DynamoDB, report logical commands, unique physical descriptors, actual
  GET/PUT calls, bytes, cold/warm latency distributions, retries, and maximum
  concurrent physical charges separately.
- Compare widths 1, 2, 4, and 8 where the adapter certifies immutable-basis
  concurrent reads. Stop increasing width when physical amplification or tail
  latency worsens.
- Cancellation freezes semantics immediately but capacity remains charged
  until synchronous backend work actually returns.

## Interpretation

The benchmark supports static cost-ranked discovery strongly, but it does not
yet accept the replacement engine. It proves that canonical byte order is a
bad policy and that the planned rank fixes the principal cold-read pathology.
It also falsifies any claim that the current stable candidate is already
suitable for DataScript: its small-query constant allocation is too high and
its irrelevant-branch allocation is catastrophic. The simplified forward
reducer is therefore a correctness-preserving performance requirement, not
mere cleanup.
