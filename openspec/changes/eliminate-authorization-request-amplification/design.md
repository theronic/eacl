## Context

See [proposal.md](proposal.md) for motivation and the delta specs for normative behaviour. The design rests on measurements taken on the retained Datalevin benchmark fixture (256 documents, `permission view = viewer`, Temurin 26.0.2, the fixture's own `distribution` helper, p50 of 51 samples for scalar rows and 7–21 for page rows) with the demo's filtered-page loop replicated verbatim inside `with-snapshot`:

| Operation | p50 | allocation | plan seals |
| --- | ---: | ---: | ---: |
| `can?` on the client, cache hit | 482 µs | 403 KB | 0 |
| `check-permission`, `:cache? false` | 943 µs | 889 KB | 1 |
| same, with a basis-keyed plan memo | 238 µs | 407 KB | 0 |
| `read-relationships`, page 10 | 547 µs | 853 KB | 0 |
| plan seal alone | 152 µs | 474 KB | — |
| demo loop, dense page (10 of 10 accepted) | 4.80 ms | 9.1 MB | 11 |
| demo loop, dense page, plan memo | 1.83 ms | 3.9 MB | 0 |
| demo loop, sparse page (20 % accepted) | 18.7 ms | 49 MB | 51 |
| demo loop, all rejected (256 candidates) | 68.0 ms | 186 MB | 256 |
| demo loop, all rejected, plan memo | 37.9 ms | 65.5 MB | 0 |
| `lookup-resources`, page 10, subject with no grants | 0.54 ms | 1.1 MB | 1 |

Code facts:

- Schema-derived state is keyed by the schema stamp carried inside the ordered-generation proof frame. A backend without `:ordered-generations` (Datalevin; any raw, speculative, or unstamped value) receives a request-local derived cache with no plan identity, and the sealed-plan lookup then bypasses memoization entirely — even the basis-keyed process FIFO — on the assumption that a basis key "can never be re-hit". That assumption holds only for the Datomic raw facade's random per-request lifecycle; Datalevin's lifecycle is a required persisted configuration value and its revision moves only on writes.
- Every backend already persists an EACL schema generation transactionally (`:eacl/schema-version` on Datomic; `:eacl/schema-generation` plus a write fence on Datahike, DataScript, and Datalevin). Only the proof-frame coupling prevents reading it cheaply.
- `with-snapshot` already shares one selected snapshot, proof frame, execution contract, and request-local schema cache across nested reads. Composition removes acquisition; it does not remove per-call plan sealing, cache lookup, result construction, or cursor minting.
- Datalevin acquisition reads the snapshot metadata including the full physical attribute schema and compares it structurally to compute a fingerprint that nothing needs for correctness: the EACL schema cannot change without advancing `max-tx`, which is already in basis identity.
- The portable cursor envelope is authenticated plaintext although `cursor-dependency-validity` requires AEAD on every backend; the relay code records the consequence.
- Sealed plans embed backend-internal relation identifiers, so they are reusable only within one source scope and lifecycle. A content digest of the schema text cannot make them portable across stores; the generation inside one lifecycle is the right key.
- The demo's filtered page is a scan-and-check loop whose lookahead reads 32-row probe pages until it finds an accepted row or exhausts the scan; its per-candidate checks bypass the completed-answer cache.

Overlapping unarchived changes: `stable-engine-request-path-performance` (plan reuse by generation, validation catalog, leaf probing, non-collecting counts, reducer bookkeeping) and `add-authorization-views` (runtime/basis/snapshot split, runtime-owned registries, constant default lifecycle). This change supplies the shared request-context and certified schema-generation seam for `add-authorization-views`; the view change consumes those primitives rather than introducing a second request lifecycle or derived-state key. PR #144 (`with-snapshot`, snapshot provider) is open and is the required base for this change.

## Goals / Non-Goals

**Goals:**

- Remove the root causes in order of measured size: proof-coupled schema keying; fixed per-call scalar cost; candidate-proportional filtering with no progress; then add the aggregate operations on top of the repaired scalar path.
- Make every backend, including Datalevin, reuse schema-derived state across requests under one certified key, with a request-local floor that guarantees at most one seal per root per request everywhere.
- Give authorized relationship pagination a cost proportional to the smaller of the relationship set and the authorized set, chosen explicitly by the caller, and make every page make progress under a bounded budget.
- Keep scalar semantics, coherence, stable order, consistency, deadline, cancellation, and typed failure contracts unchanged except where this change deliberately breaks them (cursor bytes, `:schema-identity`).
- Make gains attributable: paired same-process measurements per mechanism, deterministic counters for amplification, release ratios against the pre-change baseline.

**Non-Goals:**

- Adaptive route selection between scan and enumerate (deferred; see Open Questions).
- Parallel traversal or execution width greater than one.
- Materialized authorization views, denormalized grant tables, speculative prefetch, background warming.
- Exact historical selection or ordered relationship generations for Datalevin.
- Per-item error values inside a batch (decided against; D4).
- A sub-millisecond SLA for HTTP or for any schema shape.
- Deploying or coalescing demo frontends.

## Decisions

### D1. One certified schema generation keys all schema-derived state, independently of relationship proofs

Add a backend operation that returns the transactionally persisted EACL schema generation of the selected snapshot with one index probe: Datomic reads `:eacl/schema-version`; Datahike, DataScript, and Datalevin read `:eacl/schema-generation` guarded by the write fence. The value is memoized on the adapter for the life of the selected snapshot. Engine schema-version resolution reads it directly; the ordered-generation proof frame keeps its `:schema-stamp` for relationship-proof purposes and must agree with it when both exist (a mismatch is a typed backend-integrity failure).

Every schema-derived artifact — sealed plan, validation catalog, permission paths, relationship-dependency closure, routing analysis, direct-grant relations — lives in the generation-keyed derived cache under `[engine-abi backend source-scope source-lifecycle generation]`. The request-local derived cache is the floor: when the generation is nil (raw unstamped value, speculative or filtered database, or a backend that cannot certify), every artifact is still memoized for the request, so a request seals a root at most once on every backend. The basis-keyed process FIFO and its request-local bypass are deleted.

Why the generation and not a content digest: sealed plans carry internal relation identifiers, so plan equality across stores is not implied by schema-text equality; within one source scope and lifecycle the generation already advances in the same transaction as every managed schema write. Soundness therefore rests, exactly as for proof-backed answers today, on lifecycle rotation after restore or history replacement and on schema writes going through the managed pipeline. An external unstamped schema mutation leaves derived state stale until the next managed write or `expire-cache!`; that is the documented existing exposure, not a new one.

Why remove the physical fingerprint: it adds nothing to basis identity and computing it reads and structurally compares the whole attribute schema on every acquisition.

Alternatives rejected: a split logical/physical identity lattice with per-backend certification matrices (it restates the generation key under new names and adds a field with no consumer); digesting definitions per request (reintroduces the reads being removed); keeping the basis-keyed FIFO as the fallback (it still re-seals after every unrelated transaction, which is what the bypass was avoiding).

### D2. One request execution context for every public read

A private context value is constructed by `eacl.request.context/make-context` exactly once per public read after structural validation and snapshot selection:

```clojure
{:runtime            client-private caches, codecs, keys, limits, clock
 :adapter            immutable basis adapter
 :ownership          owned | borrowed, released exactly once in finally
 :basis-identity     complete basis identity (no schema fingerprint)
 :schema-generation  certified generation or nil
 :contract           one absolute deadline, cancellation token, per-demand and aggregate limits
 :proof-frame        one ordered-generation frame (lazy)
 :derived            generation-keyed derived cache, or the request-local floor
 :memos              root -> prepared root; complete key -> dependency proof; complete key -> cursor proof; exact demand -> decision
 :counters           acquisitions, public entries, seals, definition reads, generation reads, proof derivations, cursor builds, commands, fetched values, candidates examined, probes, publications
 :publication-buffer valid artifacts to publish after semantic success}
```

The scalar `check-permission`, `read-relationships`, `lookup-*`, `count-*`, and `expand-permission-tree` paths are rewritten to accept the context; the public entry points only validate, select, construct the context, execute, and release. `with-snapshot` constructs one context and hands the view nested reads that reuse it; the `add-authorization-views` snapshot does the same. The context is synchronous, owner-thread checked on the JVM, never stored in a registry, never returned. Internal helpers accept the contract, never `:timeout-ms`, so no layer can renew time.

Alternative rejected: waiting for the `add-authorization-views` runtime/basis split before touching orchestration. The context is expressed in terms of a runtime, a basis adapter, and ownership — the three things that rewrite keeps — so either change can land first and the later one rebases onto `eacl.request.context/make-context`.

### D3. The scalar fixed cost is profiled first and gated, not assumed

Known waste, each with its own gate: Datalevin acquisition must read only revision metadata (the maintained fork gains a metadata-free revision read; the fingerprint is gone); the completed-answer hit path must cost less than a cache-bypass evaluation of the same direct-relation demand (today 482 µs against 238 µs — a hit performs more work than the evaluation it avoids); a relationship page must not allocate 853 KB to mint two cursors; identity conversion, execution-contract normalization, semantic-key construction, and result rendering get per-call allocation ceilings ratcheted from a profile recorded before any fix.

The mechanism is deliberately left to the profile. The spec states outcomes (relative and absolute) and the conformance suite states deterministic counters; an implementation that meets them by any means that keeps the one-pipeline rule is acceptable.

Alternative rejected: gating only the aggregate paths. The fixed cost is paid by every endpoint, including the 5.7 ms HTTP scalar check, and it caps what fusion can ever return.

### D4. Batch checks are an ordered fold with memoized root preparation and whole-batch failure that names the demand

`check-permissions` validates the whole vector structurally before selection, validates every distinct root against one parsed catalog on the selected snapshot, then evaluates demands in input order. On first encounter of a normalized `[resource-type permission subject-type]` root the context prepares it once (plan, definitions, dependency template, routing facts) from the generation-keyed derived cache or the request-local floor. An exact duplicate demand reuses the completed decision in the request memo; its `:cached?`/`:cache-basis` describe the artifact the first evaluation used.

Failure is whole-batch: any demand-local typed failure (traversal limit, backend error) or request-wide failure (deadline, cancellation, aggregate limit) throws the typed error extended with `:demand-index` and the aggregate counters; no partial vector is returned or published. Per-item error values were rejected because a typed failure at a vector position is indistinguishable, to a careless caller, from a denial (`(:allowed? item)` is nil), which violates "failure is never an authorization value"; naming the index gives the caller what it needs to retry without the offending demand.

Equivalence is stated as refinement: each position's decision value equals scalar evaluation of that demand on the same snapshot with the same initial cache state; intra-batch sharing of certified subproblems may only remove commands, so a demand that would exhaust its scalar traversal limit may complete inside a batch, never the reverse, and no decision value depends on another demand. Identical typed failures regardless of position cannot be promised under sharing and are not.

Alternative rejected: grouping by root before execution — it changes which failure is observed first for no gain once preparation is memoized.

### D5. Authorized relationship pagination is one page contract with two explicit routes

Both routes paginate a stable candidate stream filtered by a predicate, in bounded windows:

| Route | Request | Stream | Predicate | Cost | Use when |
| --- | --- | --- | --- | --- | --- |
| scan | `read-relationships` + `:authorization {:subject s :permission p :on :subject|:resource}` | relationships in relationship-index order | point check of `s` on the designated endpoint | candidates × check | the relationship set is small (an account's servers) |
| enumerate | `lookup-resources` + `:resource/relationship {:relation r :subject o}`; `lookup-subjects` + `:subject/relationship {:relation r :resource o}` | authorized objects in stable-discovery order | one direct-match probe | authorized × probe | the authorized set is small (one user's documents) |

A window examines at most the configured candidate budget. Within a window the route stops at physical exhaustion or at the `N+1`st accepted candidate (exact lookahead). If the budget is reached first, the page returns the rows found so far — possibly fewer than `N`, possibly none — with `:has-next-page? true`, `:bounded? true`, and a progress cursor anchored at the last examined candidate. Otherwise `:has-next-page?` is exact and the cursor anchors at the last examined candidate as well. Concatenating pages yields the filtered stream in stream order with no duplicate or omission regardless of where windows cut. A deadline or cancellation inside a window is still the typed error; only the budget produces a short page.

Why progress cursors are sound and safe: the cursor is confidential (D6), so the anchor reveals nothing; the anchor is a stream position, which both cursor kinds already represent (relationship keyset and stable-discovery ordinal); and a short page is a complete answer for the bounded window, not an incomplete answer presented as complete.

Why two routes rather than one fused scan: neither shape dominates — enumerate-and-probe is two orders of magnitude cheaper when few objects are authorized and catastrophically worse when a super-admin filters five relationships. The caller knows which side is small; the documentation states the rule and the counters make a wrong choice visible.

Alternatives rejected: fetch `N+1` relationships then batch-check (wrong pages whenever a candidate is rejected); anchoring cursors only at returned rows (cannot advance through a window with no accepted row); treating budget exhaustion as an error (locks out exactly the users the budget protects).

### D6. Cursors are confidential, route-bound, and carry no rejected identity

The portable envelope becomes AEAD with the existing key material, kid, and domain separation; the compact authenticated-plaintext format is deleted without version negotiation because v8 is unreleased. A cursor binds the route, the ordinary filters or lookup query, the authorization or relationship clause, direction, page demand, window budget, complete basis/source/lifecycle identity, schema generation, engine/order ABI, and the dependency proof the existing pagination contract requires. Cursors of one route or clause are rejected by another before any traversal. No public field names or counts rejected candidates; `:bounded?` discloses only that the window budget was reached and is documented as such; deadline diagnostics keep their bounded work counts.

### D7. Cache identities refine scalar identities

Point decisions inside any aggregate keep their scalar completed-answer keys and subproblem boundaries. A completed authorized page is keyed by the full route/query/clause/window identity. Datalevin keeps identical-basis reuse only. Request-local memos are never reported as durable hits. Publication is a decorator after semantic success under the original deadline; partial scalar artifacts may publish under their own keys, never as an aggregate answer.

### D8. Resource accounting is hierarchical and never resets

The contract carries the per-demand traversal limits (`:max-derived-grants`, `:max-advanced-datoms`, `:max-queued-work`) and new aggregate ceilings: maximum batch size, cumulative commands, transitions, fetched values, candidates examined, probes, output units, allocation proxy, publication attempts, and the per-window candidate budget. Aggregate counters accumulate across demands and windows. A per-demand or aggregate limit inside a batch is a typed error naming the demand; the window candidate budget inside pagination is the one bound that ends a page instead of failing it. Limit values that change an answer or error boundary are part of normalized request and cache identity.

### D9. Gates attribute gains to mechanisms

Deterministic counters (one acquisition and release per request, zero nested public entries, seals and definition reads at most the distinct roots not already in the generation store, zero definition reads on a second identical request on every backend, candidates examined within the window budget, probes equal to candidates on the enumerate route) are portable and run in CI. Performance ratios are paired same-process series with interleaved samples: scalar loop vs scan route, scalar loop vs enumerate route, cache hit vs bypass, acquisition before vs after. Release ratios compare the final implementation with the pre-change baseline recorded at task 1 on a matching host class; checked-in absolute ceilings are per host class. HTTP series isolate framework overhead with a no-op endpoint and are reported, not ratio-gated.

The attribution thresholds are modest on purpose: once plan sealing is gone the dense ten-row loop costs about 1.8 ms, of which fusion can remove the second relationship read, one cursor mint, and eleven times the per-call orchestration (about 65 µs each) — roughly a third. The large ratios belong to the generation fix (−62 % on the dense page by itself) and to the enumerate route on sparse data (−99 % on the all-rejected page), and the release gate is written against the pre-change baseline so that they are counted.

### D10. Formal truth is filter-then-window; batch truth is `mapv`

Add one small model, `FilteredPagination`: a stable stream, a predicate, a window budget, and arbitrary cut points; prove that the concatenation of emitted pages equals the filtered stream in order, that `:has-next-page?` is exact whenever `:bounded?` is false, and that no page is emitted across a deadline cut. Map its assumptions to both routes. Batch equivalence needs no model: the executable oracle is `mapv` of scalar evaluation on one snapshot, compared by seeded property tests including the refinement direction for limit failures. Named mutation controls cover snapshot mixing, reorder or deduplication, cross-demand evidence contamination, deadline renewal, counter reset, failure-as-denial, cursor proof omission, skipped or duplicated candidates at window boundaries, per-candidate sealing, permission re-evaluation on the enumerate route, and release imbalance.

### D11. Reconciliation with overlapping changes

`stable-engine-request-path-performance` drops its tasks 2.1–2.4 and 3.1–3.3 (superseded by D1) and keeps sections 1 and 4–8; its D2 (Datomic raw-facade lifecycle threading) remains useful and unaffected. `add-authorization-views` keys its runtime-owned plan and schema registries by the certified generation, removes `:schema-identity` from basis identity, and exempts Datalevin from the universal default lifecycle, which Datalevin's constructor already rejects. Neither change is a sequencing prerequisite; whichever lands second rebases onto the request-context constructor.

## Risks / Trade-offs

- **[Stale derived state after an unstamped schema mutation]** → same exposure as proof-backed answers; the managed schema write and `expire-cache!` reset it; a schema-change mutation test on every backend kills stale-plan reuse; the integrity check compares the generation with the proof-frame stamp when both exist.
- **[Wrong route chosen by the caller]** → both routes are bounded and make progress; counters expose candidates examined versus rows returned; documentation gives the selection rule; adaptive routing is a follow-up.
- **[Short pages surprise consumers expecting exactly `N` rows]** → `:bounded?` is explicit; documentation and the demo treat a non-nil end cursor as "continue", never as a row count.
- **[AEAD cursors are larger and cost more to mint]** → ratcheted envelope-size and minting-allocation gates; digests instead of definition bodies; key and kid handling unchanged.
- **[Profiling finds the fixed cost inside the proof-backed cache design]** → the gate is relative (hit cheaper than bypass), so the hit path may be restructured; the one-pipeline rule still applies.
- **[Request-local sharing leaks evidence across demands]** → only immutable root state and exact completed demand keys are memoized; per-demand traversal and stopping state stay separate; oracle comparison on every batch.
- **[Two unarchived changes overlap this orchestration]** → D11 names the exact tasks that move; reconcile before implementation rather than merging mechanically.
- **[Dense-page fusion gain shrinks as fixed costs fall]** → attribution thresholds are set at the level the arithmetic supports; release ratios are against the pre-change baseline.

## Migration Plan

No compatibility period: v8 is unreleased. Ordered by measured size of the root cause:

1. Record the pre-change baseline and build the paired harness (core and HTTP, all fixtures, counters).
2. Certified schema generation and generation-keyed derived state on all four backends; delete the fingerprint, the `:schema-identity` field, and the FIFO bypass; amend the two overlapping changes.
3. Request execution context; migrate scalar reads; release-balance and one-acquisition gates.
4. Scalar fixed cost: profile, then fix acquisition, the hit path, and cursor minting; ratchet ceilings.
5. AEAD portable cursor envelope with progress anchors.
6. `check-permissions`; oracle and property tests; four-backend matrix.
7. Scan route, then enumerate route; formal model; mutation controls; conformance.
8. Demo migration, documentation, release qualification with pre-change ratios.

Rollback during development is by branch; there are no deployed cursors or tokens to drain.

## Open Questions

- Adaptive route selection: a first-window cardinality probe could choose scan versus enumerate and bind the route into the cursor. Deferred until both explicit routes have counters in production use; it changes neither the specs nor the tasks here.
- Whether the Datalevin fork's metadata-free revision read can also serve `max-eid` cheaply, or whether identity conversion needs its own lazy read.
