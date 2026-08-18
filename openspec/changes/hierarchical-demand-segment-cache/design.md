# Design: Exact Scan-Response Cache

Supersedes `review/original-design.md` (hierarchical demand-compatible
segments). Findings that drove the rewrite are in
[`review/REVIEW.md`](review/REVIEW.md).

## Technical Approach

The stable-discovery reducer has exactly one effectful call: `fetch-fn`,
invoked from `eacl.engine.stable-reducer/fetch-values` with a read-demand
descriptor

```clojure
{:operation     :subject->resources | :resource->subjects
 :subject-type  kw  :subject-eid  eid      ; or :resource-type / :resource-eid
 :relation-eid  eid
 :resource-type kw                         ; or :subject-type (target side)
 :bound-eid     nil | eid                  ; exclusive physical position
 :limit         physical-chunk-size}
```

and expecting back **exactly** `min(limit, remaining)` strictly-ascending
values greater than `:bound-eid` — fewer than `limit` only when the scan is
exhausted after the last value returned. Physical chunk width and buffer
retention provably cannot change the released sequence
(`ChunkedScan.dfy`, `OneValueScanNormalization.dfy`): a fetch *is*
`Chunk(values, offset, limit)` over one fixed sequence.

This change adds one more layer at that seam:

```
reducer ─fetch-values─▶ caching-fetch-fn ─▶ retrying-fetch-fn ─▶ classified-fetch-fn ─▶ adapter-fetch-fn ─▶ adapter
                            │
                            ▼
                 scan-response store  (client-private, lock-free, weight-bounded)
                 key   = validity-scope + descriptor-key
                 value = {:prefix [eid …] ascending from scan start, :exhausted? bool}
```

The layer answers a fetch **only when it can return exactly what the adapter
would**; otherwise it forwards *exactly the same command* and uses the reply
to lengthen the stored prefix. Nothing above the seam changes: admission,
order, limits (`:max-advanced-datoms` still counts served values through
`fetch-values`), checkpoints, cursors, telemetry.

## Architecture Decisions

### D1 — Cache adapter responses, never reducer emissions

**Choice.** The unit of reuse is the response to one read descriptor. No plan
node, start set, level, composition, or emitted-sequence artifact exists.

**Rationale.** Emissions under a node depend on the request's global admitted
set (interior merge points keyed by target node + entity), so context-free
node segments are not substitutable (`CacheBoundary.dfy`,
`ContextFreeDenotationIsNotAStableTrace`; `bounded-physical-execution`
"sequence-refinement certificate" rule). Adapter responses are functions of
(descriptor, relation slice at the snapshot) only. The reducer performs all
composition; the cache only removes I/O.

### D2 — Elide-only, top-up by re-issuing the original command

**Choice.** For `(descriptor, bound b, limit L)`: serve iff the stored prefix
contains ≥ L values > b, or is flagged exhausted. Otherwise forward the
identical `(descriptor, b, L)` command. Never fetch from a different bound,
never with a larger limit, never ahead of demand.

**Rationale.** `demand-bounded-evaluation` requires cache code to add no
backend command absent from cache-disabled execution and to retain only
"exact backend responses that the evaluator demanded". Elide-only makes the
cached run's command multiset a subset of the uncached run's with equal
responses — trace refinement by construction. The price (re-reading the
overlap between `b` and the prefix end on a short hit) is one chunk, once,
and only when the prefix was too short.

### D3 — Validity = singleton relation frontier inside the request's proved closure

**Choice.** Key scope: `{backend-id, source-scope, source-lifecycle,
adapter-fingerprint/identity-contract, order-abi + fingerprint-domain
version, schema-stamp, relation-eid, relation-stamp}`, where
`relation-stamp` is that relation's generation taken from the request's
complete proof frame via `proof-frame/subset-descriptor` (map lookup, no
provider call, fail-closed outside the closure).

**Rationale.** `ScalarFrontierCoherence.EqualScalarProofPreservesAuthorizationInput`
holds for any dependency vector, including `[r]`; a scan of relation `r` at
schema generation `g` is a function of `r`'s slice, and every supported
mutation of `r` advances its generation atomically. Equal scope ⇒ equal
sequence. Unrelated writes leave the key unchanged (sharing survives them);
`write-schema!` changes `schema-stamp`; `expire-cache!` swaps the store.
No snapshot identity in the key: reuse across snapshots is the point.

### D4 — Placement above retry/classification, below the reducer

**Choice.** `caching-fetch-fn` wraps `retrying-fetch-fn`. Hits skip retry
and classification entirely; misses see the classified/retried result and
deposit only complete responses (a thrown failure deposits nothing).

**Rationale.** Keeps the three-outcome discipline intact and keeps the cache
out of the failure path. Served values still pass `fetch-values`' limit
accounting; `:adapter-attempts` counts only real transport attempts.

### D5 — A dedicated lock-free store, not `eacl.subproblem-cache`

**Choice.** `ConcurrentHashMap<Key, Entry>` with immutable entries, CAS
`merge` choosing the longer prefix, `LongAdder` metrics, approximate
recency (racy last-touch tick in the entry, no CAS on hit), sampled
eviction (Redis-style: sample N entries, evict the least recent) triggered
when the weight budget is exceeded, per-entry `:max-prefix` cap.

**Rationale.** Measured: `subproblem/lookup!` costs 1.4 µs per hit and
serialises at ~0.5 M ops/s under eight threads (three atom `swap!`s per
hit incl. an LRU vector conj); a page issues 20–40 scans. A CHM probe costs
~0.1 µs. Correctness does not depend on eviction policy (any prefix of the
sequence, or its absence, is valid), so recency may be approximate.

### D6 — Eligibility mirrors managed proof

**Choice.** Lookup and deposit require: client cache enabled and request
`:cache?` true; ordinary current snapshot (no `as-of`/`since`/filtered/
speculative/caller-supplied values); deterministic identity contract; a
complete request proof frame that contains the scanned relation. Any
condition failing disables the layer for that request (or that scan) — the
plain fetch-fn runs.

**Rationale.** Same boundary already documented for proof-backed answers;
no new coherence protocol.

### D7 — Prerequisite: fix plan-cache thrash first

**Choice.** A separate change fixes `impl/with-request-engine`'s random
per-request lifecycle and keys `stable-plan` by schema generation, and hoists
`prepare`'s per-request `read-schema`. This change's benchmark gate is
measured only after that lands.

**Rationale.** Measured 2.4–6.6× on Datomic miss pages and 5.7× on `can?`;
without it traversal-level effects are invisible.

### D8 — Remove the retired tiers as part of this change

**Choice.** Delete the `:denotation` tier, the relationship-projection use of
`:projection`, dead metric keys, stale docs and spec text, and verification
claims naming deleted vars; keep `:denotation-max-weight` accepted (ignored,
warned) for one release, then reject.

**Rationale.** The user directive and `implementation-simplicity-and-
performance` both require superseded cache mechanisms to be deleted; the
inventory is in `review/REVIEW.md` §"Cleanup" and tasks §7.

## Data Structures

```clojure
;; key (value-equal, hashed once)
{:scope {:backend-id kw :source-scope any :source-lifecycle any
         :adapter-fingerprint any :identity-contract kw
         :order-abi 1 :plan-domain "eacl.sealed-plan.v1"
         :schema-stamp n :relation-eid eid :relation-stamp n}
 :descriptor {:operation kw :anchor-type kw :anchor-eid eid
              :relation-eid eid :target-type kw}}          ; = fetch descriptor minus :bound-eid/:limit

;; entry (immutable; replaced by CAS)
{:prefix     [eid …]      ; strictly ascending, from the scan's first value
 :exhausted? boolean      ; true ⇒ prefix is the complete scan
 :weight     n            ; 16 + 8·count(prefix)
 :touched    long}        ; racy recency tick, eviction hint only
```

Empty exhausted entries (`{:prefix [] :exhausted? true}`) are the negative
cache and the smallest entries.

## Lookup and Deposit Algorithm

```text
fetch(descriptor, b, L):
  key := scope(relation-eid) + descriptor-key           ; nil scope ⇒ plain fetch
  e   := store.get(key)
  if e:
     avail := values of e.prefix strictly > b            ; b nil ⇒ whole prefix (binary search)
     if |avail| ≥ L            → hit;  return take(L, avail)
     if e.exhausted?           → hit;  return avail       ; short reply is truthful
  values := inner(descriptor, b, L)                       ; EXACTLY the uncached command
  exhausted := |values| < L
  deposit(key, e, b, values, exhausted)
  return values

deposit(key, e, b, values, exhausted):
  candidate :=
     if b = nil                         → {values, exhausted}                 ; a prefix from the start
     else if e and b ∈ e.prefix at i    → {e.prefix[0..i] ++ values, exhausted}
     else if e and b = last(e.prefix)   → {e.prefix ++ values, exhausted}
     else                               → none                                ; fragment without its start
  if candidate and |candidate.prefix| ≤ max-prefix:
     store.merge(key, candidate, longer-prefix-wins)     ; both are prefixes of one sequence
     account weight; evict by sampling if over budget
```

Invariants: every stored prefix equals `values[0..k]` of the adapter's scan
for that key; `exhausted?` ⇒ `k = |values|`; a served reply equals
`Chunk(values, pos(b), L)`. Extension never issues an extra command.
Failures (throw) deposit nothing.

## Compatibility Rules (normative)

1. **Exact-slice rule.** A served reply MUST equal the adapter's reply for the
   same descriptor, bound and limit at any snapshot inside the key's scope.
2. **Elide-only rule.** The layer MUST NOT issue any command absent from
   cache-disabled execution, and MUST NOT change bound, limit, or chunk width.
3. **Short-chunk rule.** Every fetch-fn layer MUST return fewer than `:limit`
   values only when the scan is exhausted after the last returned value.
4. **Scope rule.** Reuse requires equality of the complete scope, including
   the scanned relation's generation and the schema generation.
5. **Boundary rule.** The layer is bypassed for `:cache? false`, cache-disabled
   clients, non-ordinary database values, unavailable proof, or relations
   outside the proved closure. Bypass is per request or per scan, never an
   error.
6. **Isolation rule.** Entries are internal eids only; never externalised,
   never inside cursors, checkpoints, or answers.

## Integration Points

| Component | Change |
|---|---|
| `eacl.engine.physical` (or new `eacl.engine.scan-cache`) | `caching-fetch-fn`, store, eviction, metrics |
| `eacl.engine.v8/stable-fetch-fn` | wrap when a scan-cache context is bound |
| Client layers (`eacl.datomic.core`, `eacl.client.orchestration`) | bind context `{:store :scope :proof-frame}` for eligible requests; construct/expire store per client lifecycle |
| `eacl.proof-frame` | `subset-descriptor` per relation (exists) |
| Config | `:scan-cache {:enabled? :max-weight :max-prefix}`; `:denotation-max-weight` deprecated |
| Metrics | `:scan-hits :scan-misses :scan-elided-commands :scan-extensions :scan-deposits :scan-evictions :scan-weight :scan-stamp-unavailable` |
| Specs | see `specs/` deltas |
| Formal | new Dafny leaf, singleton-frontier lemma, gate count, assurance matrix |

## Formal Obligations

- **Exact slice (Dafny, new leaf `ScanResponseCache.dfy`).** For a sequence
  `values`, prefix `p = values[0..k]`, `exhausted ⇒ k = |values|`:
  `Serve(p, exhausted, offset, limit) = Some(c) ⇒ c = Chunk(values, offset,
  limit)`; `Extend(p, Chunk(values, k', limit))` for `k' ≤ k` is again a prefix;
  hence the cached fetch is one of the fetches already quantified over by
  `ChunkSizeDoesNotChangeFlattenedScanValues` /
  `PhysicalWidthCannotChangeLogicalReleaseOrder`.
- **Validity (Dafny, `ScalarFrontierCoherence`).** Instantiate the main
  theorem with `dependencies = [r]`; add
  `SingletonFrontierIsRelationGeneration` and a reuse lemma that does not
  require equal full dependency vectors (relax `SubproblemCanReuse` for
  covered singletons).
- **Adapter obligation (certified, not proved).** `subject->resources` /
  `resource->subjects` for relation `r` is a function of `r`'s tuple slice at
  the snapshot; every supported mutation of that slice advances `r`'s
  generation in the same transaction (already the managed-proof assumption).
- **Trace refinement (executable).** Differential gate: for randomized
  graphs, orders and page sizes, cache-on command multiset ⊆ cache-off, equal
  responses, equal results/cursors/counts/`can?`; concurrent readers with
  interleaved supported writes.
- **Mutation controls (must fail the gate).** (a) serve a short prefix without
  exhaustion; (b) serve values ≤ bound; (c) reuse across a stale relation
  stamp; (d) fetch with a widened limit or moved bound; (e) deposit a
  fragment that does not start at the scan's first value.
- **Existing gates unchanged.** Width/retention invariance test extended
  with the cache in the loop; `verify-fast.sh` obligation count updated;
  `subproblem-cache.edn` claim vocabulary extended with
  `:exact-physical-response-prefixes`.

## Alternatives Considered

| Alternative | Why rejected |
|---|---|
| Hierarchical plan-node segments with start-set fingerprints and composition (original draft) | Context-dependent emissions; not substitutable; demand not per-node; exact start sets defeat sharing; needs new plan-node ids for nothing |
| Root-prefix answer reuse (`:first N` serves `:first M ≤ N`) | Sound but buys ~85 µs after this cache; answer/checkpoint tiers already cover the common patterns |
| Host the tier in `eacl.subproblem-cache` | 1.4 µs/hit, serialising under contention, per-snapshot exact stores |
| Key by exact snapshot only | Loses cross-snapshot sharing, the whole point |
| Fetch-ahead / longer chunk on miss | Violates demand-bounded evaluation |
| Bloom/roaring summaries | Only sound if stamped identically; premature |
| Storage-layer caching only (status quo) | Does not remove seek/realise/classify cost; 20–40 scans per sparse page |

## Open Questions

1. Whether to deposit from exhaustive routes (`count-*`, `:last` windows) at
   all, or only when the store is under 50 % of budget (avoid flooding).
2. Default `:max-prefix` (proposed 1024) and `:max-weight` (proposed 8 MiB).
3. Whether the Datahike `:memory` backend's high cold scan cost is a konserve
   artefact worth reporting upstream.
4. Whether `stable-plan` should be moved off the global `defonce` onto the
   client's schema-generation cache as part of the prerequisite change.
