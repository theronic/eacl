# EACL v8 layered subproblem cache

EACL v8 retains completed answers, but it no longer relies on completed-answer
hits for every cache benefit. A client also owns bounded caches for immutable
relationship projections and completed authorization denotations. These
subproblem entries are private performance state: the database remains the
authority and `:cache? false` executes without consulting or populating them.

## Resolution order

For a relationship projection or direct membership probe, the engine resolves:

1. the exact-generation store for the selected immutable snapshot;
2. for a managed current snapshot only, the relation-stamped store for the
   current schema generation; and
3. the backend index.

An exact miss may copy a valid managed value into the exact store. Later
operations on the same snapshot then need neither another relation-proof read
nor another managed lookup.

Managed cross-generation projection or denotation reuse is enabled only when
the caller explicitly selects `:evaluation :complete-denotation`. Demand mode
uses only exact selected-snapshot artifacts and never performs proof work or
widens traversal for future reuse.

The managed key commits to:

- cache/key version;
- backend source/branch and lifecycle scope;
- physical schema-generation assertion identity;
- relation-definition identity;
- physical relation-generation assertion identity and stored value;
- operation and direction;
- subject/resource types and internal endpoint IDs;
- bound, inclusivity, and chunk width.

The relation generation is read from the same immutable snapshot as the
authorization query. Including both assertion transaction and stored value,
plus an operator-rotated source lifecycle, prevents replaced histories at the
same numeric transaction from colliding.
EACL-managed writes change the identity atomically with every create, touch,
delete, and object-deletion relationship mutation. Therefore an unchanged
stamp frames that relation's projection; a changed relation selects a different
key. An unrelated relation write leaves the old key eligible.

Missing/malformed proofs, unknown writer authority, historical or arbitrary DB
values, custom unsupported identity semantics, and `:cache? false` never enter
the managed tier. They use exact-generation evaluation or the backend directly.
Proof-provider failure affects performance, not the authorization result.

This cache adds no database write beyond v8's existing managed-writer relation
mutation stamp. The trade-off is conservative invalidation: changing one
relationship invalidates cached projections for every endpoint of that
relation definition. A future endpoint-local stamp could narrow invalidation,
but would increase transaction and storage cost.

## Shared-subgraph effect

Projection keys do not contain the principal or top-level permission. Suppose
two different queries converge on the same `group#member` or `server#team`
edge. An exact selected-snapshot command response may be reused when the second
query issues the identical command. Managed reuse across graph generations is
restricted to explicit complete-denotation evaluation. Neither path permits
cache policy to issue or widen an adapter command.

Projection artifacts bind the evaluator's exact adapter command, including
direction, bound, inclusivity, maximum response size, and continuation. Cache
code cannot choose a wider chunk or fetch beyond the command. Empty terminal
responses remain useful exact negative artifacts.

Acyclic denotations and completed recursive least-fixed-point result vectors
are publishable only after the worklist completes; visited-set fragments and
partial page walks are never shared. Forward/reverse lookup, count, `can?`,
and cursor rendering can reuse a completed compatible denotation on the exact
snapshot, and under `:coherence-authority :managed` a completed denotation is
additionally reusable across unrelated forward transactions: its managed key
commits to the schema stamp and the complete sorted per-relation stamp vector
of the permission's dependency closure, and plan compilation fails if a
compiled rule could reference a relation outside that closure. This is the
same relation-stamp framing completed answers and projections use — one
generation-layering implementation, differentially tested against the
cache-free oracle under interleaved writes. Its forward-history framing proof
is machine checked in `formal/dafny/NativeGenerationCoherence.dfy`.

## Bounds and hit costs

The projection and denotation tiers have isolated weighted budgets. Publication
evicts only performance state and rejects oversized values. Identical misses
compute independently and race bounded best-effort publication; no caller
waits for another cache computation.

Completed private entries are structurally validated and weighed once.
Subsequent hits perform constant-count lookup/recency maintenance and do not
walk the cached result. Eviction scans occur on a miss that must make room, not
on a hit. Managed atomic projection validation reads one relation proof per
relation per new exact generation, independent of graph size.

Configuration:

```clojure
{:cache
 {:max-entries 4096
  :subproblem-cache
  {:enabled? true
   :projection-max-weight (* 8 1024 1024)
   :denotation-max-weight (* 8 1024 1024)
   :answer-max-weight (* 16 1024 1024)}}}
```

The weights are deterministic admission units approximating retained key/value
size, not a portable JVM/JavaScript heap-byte measurement. Completed answers
are the store's third weighted tier: bounded by `:answer-max-weight`, evicted
least-recently-used, and capped at one quarter of the budget per entry with
oversized rejection, so answers can never grow byte-unbounded and one
maximum-size page cannot displace every retained answer.

`cache-stats` exposes exact and managed completed-answer counts plus
`:subproblems` and `:managed-subproblems`. Relevant counters include
projection, denotation, and answer hits, managed projection hits, proof
reads/hits/failures, publication races/contention, admission/oversize rejection,
eviction, fetched projection values, and avoided backend operations.

## Performance gate

The checked-in DataScript benchmark uses a depth-48 shared arrow, 80 distinct
top-level permission keys, and an unrelated relationship write between warmup
and measurement. Every measured request explicitly selects
`:evaluation :complete-denotation`; this gate is not evidence of implicit
demand-mode warming. It asserts zero completed-answer hits and alternates
matched baseline/layered queries to reduce JVM phase bias.

On the recorded 2026-08-08 run after enforcing that explicit completion
overrides the certified acyclic shortcut:

- backend scan/probe operations fell from 5,120 to 0;
- one managed completed-denotation hit crossed the unrelated graph revision
  and 79 distinct roots reused it in the selected exact generation;
- the new exact generation performed one bounded dependency-proof read;
- five-run median p50 latency was 0.140 ms versus 3.419 ms, with paired
  ratios from 0.039 to 0.044;
- completed-answer hot-hit ratio was 1.002;
- `:cache? false` ratio was 1.003;
- a 2,048× recursive closure-size increase changed cached page-render p50 by
  a median 1.018×; and
- a 64× retained-entry-count increase changed hit-batch p50 by a median
  1.073×.

These are regression measurements on one host, not latency theorems. The
enforced requirements are at least 50% fewer backend operations, at least 25%
lower shared-subgraph p50, and no more than 5% regression for existing
completed-answer hits or cache-free evaluation.

## Verification status

`formal/dafny/SubproblemCache.dfy` proves conditional key separation,
projection slicing, exact-hit refinement, relation-stamp framing, complete-hit
callback bounds, weighted-retention and bounded-publication-attempt bounds,
lifecycle rejection, and
partial-recursive-publication rejection. The TLA+ models explore expiry,
eviction, failure, concurrent publication, relation/schema changes, unrelated
generation changes, and source switches; bounded depth 8 and inductive
invariants pass. CLJ, CLJS, all three backend suites, generated Java/JavaScript
boundaries, and the heavy pagination suite are differential evidence.

This does **not** make the entire public EACL engine formally verified.
Generated authoritative routing for every public decision, managed derived
denotation proofs, complete shadow/cutover gates, source/generated digests, and
independent formal/security review remain open. The release manifest therefore
continues to report `:complete-public-engine :incomplete` and refuses the
end-to-end verification claim.
