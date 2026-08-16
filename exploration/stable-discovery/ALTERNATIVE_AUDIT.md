# Alternative algorithm audit

Status: exploration decision record. The question is whether the minimum
cost-ranked stable reducer can be made materially simpler without losing exact
pagination, remote-read efficiency, or backend portability.

## Non-negotiable semantics

For one exact basis and sealed schema plan, enumeration must:

- return exactly the positive EACL denotation;
- return each typed result once despite overlapping derivations;
- have one deterministic discovery sequence independent of worker completion,
  hash-map iteration, page size, and storage chunk size;
- permit replay after checkpoint eviction;
- stop at page plus exact one-result lookahead without globally sorting;
- terminate on recursive cycles under explicit logical resource limits;
- run against Datomic, Datahike, and DataScript without backend-specific
  semantic truth.

Any “simpler” proposal that drops one of these constraints is solving a
different problem.

## Comparison

| Design | Exact/stable | First-page remote work | Retained state | Verdict |
|---|---|---|---|---|
| Global EID merge over permission streams | yes | must realize every stream head | N stream cursors + merge | Rejected: reproduced 2,002 scans and the production 4,536-stream pathology |
| FIFO fixed-point agenda with exact seen set | yes | breadth can open every sibling before a shallow result | exact seen + queue | Correct baseline, not competitive for broad remote frontiers |
| Canonical-byte DFS with exact seen set | yes | can choose an arbitrarily expensive empty branch | exact seen + stack | Rejected: 2,033 scans and ~203 MiB allocation in the 2,000-group source workload |
| Deterministic round-robin lanes | yes | bounds starvation but pays a quantum on dead lanes | exact seen + lanes + lane cursors | More state; DFS/FIFO counterexamples prove no dominance |
| Cost-ranked DFS with exact seen set | yes | selects the statically cheapest remaining rule path; reads only explored branches | exact seen + stack + linear rank certificate | Chosen minimum; one scan on the adversarial source workload |
| Completion-order concurrent traversal | no, unless the schedule is recorded | often good | seen + nondeterministic schedule or unbounded recorded schedule | Rejected: pagination order changes with latency and retries |
| Deterministic task ordinals with in-order commit | yes | good when future reads are materialized | seen + stack + bounded response shell | This is the chosen reducer plus read-ahead, not a simpler semantic algorithm |
| Server-side iterator only | yes while retained | good | complete private iterator per cursor | Insufficient: eviction/restart/load balancing has no correct fallback |
| Stateless ordinal cursor with replay | yes | repeats prefix work | no server cursor state | Kept as the checkpoint-miss fallback, not the only fast path |
| Public cursor containing the seen/frontier state | yes | good | token grows with explored graph | Rejected: unbounded, leaks internals, expensive authentication/transport |
| Bloom/Cuckoo filter deduplication | no | cheap | bounded approximate set | Rejected: false positives silently suppress authorized results |
| Result-only exact seen set | not generally | cheap | result set only | Rejected: recursive logical work can repeat forever before producing a result |
| Database-side recursive query + `distinct limit` | backend-dependent | may materialize/sort the whole closure | delegated/opaque | Rejected as shared authority; no portable stable continuation or cost bound |
| Precomputed authorization/materialized closure | yes if maintained transactionally | excellent reads | potentially enormous derived index; expensive writes | Separate product architecture, not an EACL query-engine simplification |
| Deterministic hash shards | possible | must probe empty shards or duplicate traversal per shard | shard frontier + cross-shard dedup | Rejected: moves the merge boundary or repeats discovery; shard order is not a cost oracle |

## Why the chosen algorithm is the minimum

The pure forward reducer needs only:

```text
sealed plan
right-edge work stack
exact admitted-work set
page + ordinal
scalar counters/limits
```

A scan frame adds only its exact logical exclusive resume bound. Physical
fetch end, chunk width, unread response values, offsets, futures, and pins are
not reducer or checkpoint state.

Static consumer vectors live in the sealed plan, not request state. A compact
rule cursor is just work on the stack. Root-work admission is result
deduplication. Every other proposed exact online algorithm needs at least an
equivalent frontier and exact history, or pushes that state into an opaque
database/server iterator.

Reverse lookup is simpler than the earlier symmetric-machine design. Starting
from one concrete root grant, the sealed rules-by-head-node vector emits either
a transposed predecessor goal or a base-principal scan. One exact admitted-goal
set and one stack therefore suffice. Dynamic grant/consumer goal cells and
later-side Cartesian joins were artifacts of registering rules and grants in
arbitrary order even though every rule is already immutable plan state. The
static reverse prototype matched the independent oracle and every frozen
engine on star, chain, broad-union, mutual-recursion, mixed, cycle, and diamond
fixtures; the obsolete four proof leaves and randomized join campaign were
deleted.

The rank certificate is the only semantic-planner addition over plain DFS. It
is justified by a concrete three-order comparison:

```text
2,000 empty group-derived branches + 2 direct documents, page size 1

legacy global merge       2,002 scans
canonical-byte DFS        2,033 scans
cost-ranked DFS                1 scan
```

Replacing the exact shortest-read certificate with a local “direct first” tag
is simpler but wrong as a general cost model. In the executable transitive-rank
family, one root-direct seed competes with 1,000 direct seeds whose grants must
still cross one arrow-permission read to reach the root. Canonical ordinals put
the dead seeds first. Local-only ranking therefore places the root at position
1,000 and requires at least 1,000 seed scans before it; shortest remaining
distance places the root at position zero. The 1,001-branch plan compiled and
checked in 138 ms in the warm REPL. A superficially direct rule can feed a long
permission chain, while another direct rule can already be at the root.
Shortest remaining 0/1 distance captures the whole static permission path.
The generator may be ordinary 0/1 shortest path; the trusted checker is only a
linear scan of edge inequalities and a well-founded witness successor per
node. The simplified 18-obligation checker stores only distance,
witnessed-edge index, and hop arrays and verifies in about one second, so
deleting it would save negligible iteration time while reopening unbounded
remote-read regressions.

## What stays outside the semantic minimum

The following improve operations without changing discovery truth:

- request-local complete-descriptor coalescing;
- bounded read-ahead whose completions integrate only at canonical head;
- projection chunks;
- exact reducer progress checkpoints;
- permanent physical-capacity governor;
- backend/topology-specific width and cache policy.

They are host layers. Folding them into the reducer would make both formal
verification and the hot sequential DataScript path slower.

## Accepted simplification: one-value scans with a detachable sidecar

Variable eager scan batches are not merely awkward; they are semantically
wrong for stable discovery. In the overlap counterexample, a wide chunk admits
later values before recursion can discover them, changing `[a c b]` to
`[a b c]`. Fixing one versioned eager width would make a tuning parameter part
of the public order ABI and would still stage many unnecessary identities for
a small page.

The reducer now releases exactly one ordered scan value per transition. A
physical read may fetch a wider chunk into a request-local sidecar, so this does
not sacrifice S3/DynamoDB batching. The authoritative frame advances only to
the last value actually released. The sidecar can therefore be discarded and
refilled from that bound without changing the trace. Checkpoint capture ignores
it, response-memory pressure can dematerialize it, and physical width/cache
residency disappear from cursor semantics.

This is simpler than pinning buffers through immutable checkpoints. The 17-
obligation normalization proof verifies in about one second and replaces a
26-obligation checkpoint-buffer ownership leaf. Source-shaped retained and
always-dropped sidecars produced one exact sequence in 153 recursive
comparisons. Always dropping broad width-64 buffers roughly doubled logical
scan commands in the 200-account star, so detachment is a pressure/checkpoint
fallback rather than the normal hot path.

## Accepted simplification: remove the rolling prefix commitment

The earlier design computed a keyed rolling digest for every delivered result.
The pagination proof revealed that it was redundant: replay equality was an
explicit premise, not a consequence of the digest. Worse, two sequences can
have the same committed prefix at an ordinal and different next pages, so the
digest does not establish continuation correctness.

The minimum cursor now authenticates the complete exact context, semantic and
order ABIs, sealed-plan fingerprint, basis, fixed page size, and represented
edge's one-based ordinal plus canonical external identity once. The sealed
reducer and adapter scan-order contract deterministically reconstruct the
unique sequence. Any order-changing implementation change must bump an
authenticated ABI. This removes per-result rolling encoding/HMAC work without
weakening the actual correctness premise; an exact forward checkpoint retains
only one constant-size last-result identity for an `after` match.

## Accepted simplification: derive private checkpoint lookup

The authenticated exact context and request-derived forward resume boundary
already define the checkpoint namespace. `after` uses edge ordinal; `before`
uses `max(0, ordinal - 1 - page-size)` and validates the edge after a bounded
page-plus-one forward run. Carrying a separate opaque public reference is
redundant, can make otherwise identical retry cursors differ, and adds another
collision, rotation, and cross-context binding surface. The server instead
derives an internal bounded key, full-compares the complete entry context, and
selects the newest nonregressing exact checkpoint. A collision or eviction is
only a miss followed by replay. This removes public state without adding
runtime traversal work.

## Rejected simplifications that remain useful later

### Drop the rank certificate after generating deterministic ranks

This makes source smaller but expands the trusted computing base. A reversed
edge or zero-cycle bug changes order and cost without changing denotation, so
ordinary correctness tests may miss it. The 18-obligation checker verifies in
about one second and is cheaper than debugging production cold-read
regressions.

### Cache arbitrary intermediate denotation facts

Facts may be individually true yet injecting them early can change discovery
order. Projection chunks are order-neutral reads; exact reducer checkpoints
preserve the whole ordering state. Completeness of a context-free subproblem
denotation is insufficient: `CacheBoundary.dfy` gives an overlapping-path
counterexample whose fresh subtree traversal has the correct result set but a
different sequence from traversal under the request's existing admission set.
Only a whole-request completed answer, an exact request checkpoint, or ordered
successor projections replayed through normal admission may accelerate stable
enumeration. A flat subproblem result bag is not a continuation.

### Share in-flight reads across requests

This can reduce duplicate cold reads but requires waiter ownership,
cancellation, epoch, orphan, retry, and fairness state. Request-local
coalescing plus a global capacity cap is the simpler safe first release. A
later shared-flight change must justify its complexity with measured duplicate
traffic.

## Conclusion

No reviewed alternative is both simpler and at least as correct and performant
across the target backends. The chosen design is not “one generic recursive
engine plus a scheduler framework.” It is a small deterministic positive
fixed-point reducer, specialized by direction, with one proof-carrying order
key and a separate bounded I/O shell. The remaining simplification opportunity
is implementation discipline: compact internal identities, right-edge vectors,
no forward joins, no old cursor/merge routes, and no duplicated formal
authorities.
