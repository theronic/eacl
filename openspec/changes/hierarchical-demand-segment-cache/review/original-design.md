# Design: Hierarchical Demand-Compatible Segment Cache

## Technical Approach

Add an internal **segment tier** to the client-private subproblem cache. Segments are ordered, demand-bounded, frontier-stamped expansions of sealed-plan nodes (or short paths). The demand-bounded reducer may:

1. Look up compatible segments before expanding a plan node.
2. Take a prefix (or filtered intermediate view) when demand and start-set rules allow.
3. Deposit new ordered prefixes only for results it actually walked under the current demand.

No path may publish a segment that contains more eids than the demand that produced it. Composition of multi-hop segments is itself demand-aware and stops at the remaining demand.

Public completed-answer keys, cursor identities, and exact/proof-backed tiers are unchanged. Segments are an accelerator underneath them.

## Architecture Decisions

### Decision: Prefix-only reuse (never widening)

**Choice.** A segment stored under demand *D* may be used only for requests whose demand *D′ ≤ D*.

**Rationale.** This is the dual of the evaluation-widening problem that caused the old denotation path to be disabled. Prefix reuse is sound under stable order; extending a segment would require extra work the current request did not authorize.

### Decision: Key segments by plan node + start-set fingerprint + demand + validity, not by final page size

**Choice.** Page size is treated as one concrete demand value. Different `:first` / `:last` sizes share the same segment when the demand inequality holds.

**Rationale.** Completed answers already include bounds in their keys. Segments sit below that layer and maximize sharing across pagination shapes.

### Decision: Validity = existing proof descriptor

**Choice.** Every segment carries:

```clojure
{:schema-stamp     <schema-generation>
 :dependency-stamp <max relation-version over relations used by this segment>
 :lifecycle        <source lifecycle>
 :plan-fingerprint <sealed-plan fingerprint>}
```

Reuse requires exact match on these fields (same rules as proof-backed completed answers).

**Rationale.** Reuses the already-verified frontier machinery; no new coherence protocol.

### Decision: Ordered eid payloads, not dense bitsets by default

**Choice.** Primary payload is a sorted vector (or compressed run) of eids in stable-discovery order, plus optional continuation and per-intermediate references.

**Rationale.** Matches the sealed-plan release-width and uniqueness contract. Optional roaring/bloom summaries may be added later for negative checks without changing the primary representation.

### Decision: Hierarchical levels follow the sealed plan

**Choice.**

- Level 1: single plan node / single relation hop from a start-set.
- Level *k*: demand-aware composition of lower segments (or of a segment + a fresh hop).

**Rationale.** The sealed plan already defines the legal composition order and ranking. Hierarchy is therefore plan-native rather than an arbitrary graph cache.

### Decision: Best-effort admission under weight budgets

**Choice.** Segment entries compete for a dedicated weight budget (reuse or rename `:denotation-max-weight` / introduce `:segment-max-weight`). Overweight entries are rejected; eviction is LRU within the tier.

**Rationale.** Keeps the cache bounded and client-private; matches existing subproblem-cache philosophy.

## Data Structures

### Segment key

```clojure
{:tier            :segment
 :level           1|2|…
 :plan-node       <sealed-plan node id or path vector>
 :relation        <keyword>                    ; optional, for level-1 clarity
 :direction       :forward|:reverse            ; optional
 :start-set-fp    <string>                     ; stable fingerprint of sorted start eids
 :demand          <pos-int>                    ; max results this entry may contain
 :validity        {:schema-stamp …
                   :dependency-stamp …
                   :lifecycle …
                   :plan-fingerprint …}}
```

### Segment value

```clojure
{:payload
 {:ordered-eids   [<eid> …]                   ; ascending stable-discovery order
  :count          <int>
  :min-eid        <eid>
  :max-eid        <eid>
  :continuation   nil | {:next-boundary-ordinal …
                         :next-eid …
                         :reducer-state-hash …}
  :via            [{:plan-node …
                    :level …
                    :intermediate-eids [<eid> …]
                    :segment-ref <key-or-id>}]  ; optional composition trail
  :summaries      {:roaring … :bloom …}}        ; optional
 :weight          <bytes>
 :admitted-at-t   <t>
 :hits            <int>}
```

### Start-set fingerprint

Initial implementation: SHA-256 (or project-standard hash) of the sorted, canonical internal eids of the starting set for that plan node. Later refinements may add common-prefix or schema-guided clustering for high-sharing intermediate nodes without changing the external segment contract.

### Composition (demand-aware)

```text
compose(seg-A, hop-or-seg-B, remaining-demand):
  result = []
  for each intermediate in seg-A.ordered-eids:
    if remaining-demand = 0: break
    products = expand-or-lookup(hop-or-seg-B, intermediate, remaining-demand)
    append products to result   ; already in stable order
    remaining-demand -= |products|
  return ordered segment of result with demand = original remaining-demand bound
```

Only eids actually produced under the active demand are stored. The `:via` trail records which intermediates contributed so frontier checks remain precise.

## Lookup and Deposit Flow

```
1. Exact completed-answer lookup
2. Proof-backed completed-answer lookup
3. Hierarchical segment lookup for relevant plan nodes
   - match validity, start-set-fp compatibility, demand ≥ request demand
   - on hit: take prefix / filter intermediates / resume continuation
4. On miss: run demand-bounded reducer
5. While walking, deposit level-1 and composed segments under current frontier
   (subject to weight budget; never deposit more than walked demand)
6. Publish completed answer as today
```

## Compatibility Rules (normative)

1. **Prefix rule.** Segment under demand *D* usable only when request demand *D′ ≤ D*.
2. **Frontier rule.** `dependency-stamp` and `schema-stamp` (and lifecycle / plan fingerprint) must match.
3. **Order rule.** All payloads and compositions preserve sealed-plan stable-discovery order and uniqueness.
4. **No widening.** Cache hit may only remove work the reducer would have done; it may never issue extra scans or raise the effective demand.
5. **Start-set rule.** Exact fingerprint match required in v1; compatible-subset / high-sharing refinements are explicit later extensions.
6. **Cursor isolation.** Public cursors and checkpoints remain exact-snapshot and execution-identity scoped; segments are internal only unless a future authenticated segment continuation is designed.

## Integration Points

| Component | Change |
|-----------|--------|
| `eacl.cache` / subproblem store | New segment tier, keys, weight budget, LRU |
| Sealed-plan compiler | Expose stable node ids / path vectors for keys |
| Demand-bounded reducer | Optional segment lookup before node expansion; deposit after walk |
| Proof / frontier | Reuse existing relation-version max over segment’s relation set |
| Config | `:segment-max-weight` (or map denotation budget); `:segment-cache {:enabled? true}` |
| Metrics | Segment hits, misses, prefix truncations, admission rejects, composition counts |

## Alternatives Considered

| Alternative | Why rejected (for now) |
|-------------|------------------------|
| Full multi-hop denotation publishing | Evaluation widening; hard coherence; previously removed |
| Global resource-side inverted index | Eager materialization; demand-unbounded; schema-agnostic cost |
| Page-size in segment key | Prevents sharing across `:first 20` vs `:first 50` |
| Unordered sets / only bloom filters | Breaks stable order and pagination correctness |
| Background segment pre-warm | Violates demand-bounded default; complexity |

## Formal Obligations (to be stated in verification)

- Soundness: any eid sequence returned from a segment under demand *D′* and frontier *F* equals the sequence the demand-bounded reducer would emit on the same snapshot for the same start set and plan node.
- Safety: no segment causes a request to observe results beyond its demand or after a frontier advance.
- Progress: segment miss degrades to ordinary evaluation; never blocks or fails closed incorrectly.

## Open Questions

1. Exact start-set fingerprint algorithm and whether high-sharing intermediate nodes get a special “shared-intermediate” key mode in v1.
2. Whether level-*k* composition is stored eagerly or only level-1 + on-the-fly compose.
3. Interaction with recursive-traversal limits and fuel boundaries when depositing segments mid-walk.
4. Whether segment continuations may ever be exposed inside authenticated cursors (out of scope for initial delivery).
