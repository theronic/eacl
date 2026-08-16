## Context

EACL v8 has two lookup-enumeration designs in the tree today, neither acceptable for release, plus an accepted prototype that is not in the tree.

The acyclic engine creates an entity-ID-ordered stream for every permission path and performs a global N-way merge (`merge-eid-seqs`, `eacl.lazy-merge-sort`). To prove the minimum result it must observe the head or exhaustion of every stream. The one-million-resource Datahike/S3 investigation measured the resulting cold barrier: approximately 4,536 relationship scans, approximately 3,935 unique node-cache misses at ~37.7 ms per essentially serial miss, 148.4 seconds cold, 214 ms warm.

The in-tree "stable-discovery" candidate (`portable_indexed.cljc` `:stable-discovery` mode, prefix commitments, the physical scheduler and its cross-request shared reads) is the **rejected** symmetric byte-stable design: dynamic grant/consumer buckets, join cursors, a separate emitted set, runtime canonical-byte ordering, and rolling per-result commitments. Measured on the 2,000-branch adversarial fixture it used 2,033 scans and ~203 MiB allocation. **Warning: everything named "stable-discovery" in `src` today is this rejected candidate.** The accepted engine exists only as the gitignored prototype `target/exploration/stable-discovery/forward_runtime_prototype.clj` plus 43 Dafny leaves (536 obligations), five TLA+ families, and the benchmark/audit corpus in the same ignored directory.

Constraints:

- Authorization is evaluated at one immutable basis; the accepted EACL language compiles to a finite positive monotone rule program.
- Stable means deterministic for the exact basis, normalized query, sealed plan/order fingerprint, and adapter scan-order contract — not globally sorted, not identical across adapters.
- Exact duplicate suppression is mandatory; probabilistic suppression is an authorization omission (`ExactDedupLowerBound.dfy`).
- Public cursors are bounded authenticated edge tokens; exact private state is retained or deterministically reconstructed.
- Every certified adapter today declares `strict-sequential-traversal-execution` with `:concurrent-snapshot-reads nil`. DataScript must not pay any scheduler tax.
- EACL v8 is unreleased; no migration from the development ordering ABI is required.

## Goals / Non-Goals

**Goals:**

- Replace global entity-ID ordering with stable first-discovery order so first-page work tracks the productive canonical prefix, not total fanout.
- One generic pure reducer, direction supplied entirely by the sealed plan; exact admission; result uniqueness by construction.
- Prove soundness, completeness on exhaustion, exact uniqueness, determinism, page composition, replay, and chunk invariance; carry the compact-representation contract that produced the measured memory wins.
- Ship width one on every topology with the concurrency seam (pure step / `NeedRead` / atomic integration) preserved for a future change.
- Route all public entry points, delete the old engines and the rejected candidate, and keep exactly one semantic authority in production source.

**Non-Goals:**

- Preserve global entity-ID order or development cursors.
- Ship concurrent physical execution, speculative prefetch, descriptor coalescing, or cross-request read sharing in this change. (The proved shell models are parked for a future change; reopening is cheap.)
- Encode an unbounded exact seen set into a stateless cursor; substitute cached subproblem denotations into order-sensitive enumeration.
- Change Datahike, Konserve, Datomic, DynamoDB, S3, JDBC, or LMDB internals in this repository.
- Optimize the super-user at the expense of narrow principals; it is an adversarial fanout diagnostic.

## Decisions

### 1. Separate set semantics from discovery order

For immutable basis `B` and normalized query `Q`, let `P(Q)` be the finite positive rule program and `G* = lfp(T_B)` its least fixed point. The unordered answer is `{ external-root(k) | k in G*, k a root fact for Q }`. The ordered answer is the sequence in which the versioned canonical reducer first admits distinct root identities. Declarative equality proves authorization; the operational machine defines pagination. The old merge had to inspect every stream head to select the globally smallest result; the new reducer follows one deterministic branch to a result and only then advances siblings. A late result in a lower-ranked branch may have a smaller entity ID; that no longer matters.

### 2. Seal a direction-specific, cost-ranked plan

Normalization produces portable positive nodes and rules. Compilation assigns dense canonical ordinals and builds immutable direction indexes: forward consumers by granted permission node, reverse rules by head permission node. A compact certificate proves the static shortest remaining number of storage-read boundaries (0/1 edge costs; generator untrusted, checker a linear scan of edge inequalities plus a well-founded witness — 18 obligations, ~1 s). Every alternative vector is ordered by `(certified-rank, canonical-ordinal)`.

The rank is static: schema shape and adapter-declared cost classes only, never latency, cache residency, completion order, or statistics. Rank does not promise universal dominance (a frozen broad-union reverse counterexample needs 8 reads under cost order versus 5 under byte order); its payoff is the adversarial case — 1 scan versus 2,002 (legacy merge) and 2,033 (byte DFS) on the 2,000-branch fixture — and stability of ties via ordinals. The complete normalized plan, order contract, transition interface, admission-key granularity, and adapter scan-order contract are folded into **one composite fingerprint** bound into cursors, checkpoints, and the completed-answer cache key.

### 3. One generic reducer; direction lives in the plan

The earlier draft specified two direction-specific reducers. The accepted prototype's forward and reverse machines already share field-for-field identical state (stack, exact admitted set, static cursors, scalar discovered count, logical scan frames, counters) and identical scheduling; they differ only in two case dispatchers whose branches instantiate three transition shapes: scan (adapter read + plan-supplied successor), pure expansion (push successors), and emission. This change therefore ships **one reducer** over a sealed-plan transition interface — transition descriptors indexed by dense ordinals plus an admission-key codec — and two plan compilers. The direction-specific Dafny producers (`EaclForwardProducer`, `EaclReverseProducer`) become proofs about the two plan constructions; the machine-level proofs (`HistoryFreeReducer`, `ConcreteHistoryFreeRuntime`, `RuntimeStackRefinement`, `SealedPlanReducerComposition`) are already direction-agnostic.

Admission-key granularity is per work kind, fixed by the plan, and is part of the order ABI:

- **merge-point work** (forward grants, reverse goals) is keyed by *target node + entity identity* — never by producing rule — so union arms deduplicate; the root emission key of Decision 4 is the degenerate case where the node is the unique root;
- **scan occurrences** are keyed by rule ordinal + binding, excluding the resume bound.

A uniform producing-edge key would double-emit resources reachable via two union arms; an entity-only key for interior grants would merge distinct permission nodes and lose results (both have executable counterexamples in the prototype's qualification suite).

### 4. Result uniqueness by construction, not by compiler proof

The earlier draft required the compiler to prove root work identity injective in public resource identity and reject plans otherwise. That obligation was only ever dischargeable by the construction the accepted model already uses (`ConcreteOutputIdentity.dfy` proves both injectivity lemmas with empty bodies): **every plan has a single root emission point whose admission key is the emitted entity's identity**. First admission of that key is emission. There is no separate emitted-result set and no plan-rejection path. Adapters still certify a total injective internal/external identity mapping; violating it fails certification, not the compiler.

### 5. A reducer step is pure; logical release width is exactly one

Each step either performs one bounded pure transition or returns `NeedRead(descriptor)` for the canonical head without changing state. A validated complete response for that exact head is staged, all limits are checked, and the entire effect commits atomically (`AtomicLogicalAdmission.dfy`). Futures, handles, clocks, permits, retries, and buffers are never reducer state.

The reducer releases **exactly one ordered scan value per logical transition**. Eager multi-value admission is semantically wrong for stable order — the overlap fixture yields `[a c b]` at width one but `[a b c]` at eager width 64 — and the accepted prototype itself defaults to a logical chunk of 64, so its order evidence is valid only with the logical width pinned to one. Production hard-fixes logical release at one value; no width knob exists in the order ABI, and a logical-width mutant control guards the gate. The authoritative scan frame stores only the logical exclusive bound. Expected complexity is `O(V + E + R)` under expected constant-time exact-set operations.

### 6. Physical execution is width one; the concurrency seam is the boundary, not machinery

Every topology runs the direct reducer-to-adapter path: no executor tasks, futures, descriptor index, speculation, or coalescing. Evidence: every certified adapter declares sequential execution; measured width>1 request-path gains are ~1.58x best case on cold direct S3 under 10 ms injected latency with GET amplification 40→48, and no monotone improvement without injected latency (the one larger win — cold LMDB tier fill, 1,168→193 ms at width 32 — is a restart/warm-up operation, not the request path); identical descriptors amplify backing reads exactly with width on JDBC and DynamoDB and near-linearly on S3 (3→35 GETs from width 1 to 32); the warm LMDB tier (the recommended remote topology) measured zero S3 GETs at 0.7–1.7 ms where width is irrelevant; and dependent deep chains are serial at any width because a next-level descriptor cannot exist before its parent value. With the DFS order fix, the dense first page is expected to cost the productive prefix (~tens of misses, on the order of a second cold at the measured 37.7 ms/miss) instead of 148 s — a modeled expectation pending follow-on qualification. Deep chains remain depth × RTT serial cold — a schema property no concurrency design shortens, mitigated by storage tiering.

What survives from the earlier shell design, because it is not concurrency machinery:

- **Bounded per-request chunk retention.** Each open scan frame may retain its current fetched chunk; retained buffers are capped per request (initial cap 16, ~10 KiB at the measured 627–634 bytes per width-64 buffer) and evictable — eviction re-reads from the logical bound. Dropping all buffers roughly doubled logical commands on broad stars; unbounded retention is a deep-recursion memory hazard. Buffers never enter cursors or checkpoints.
- **Demand exactly `P`.** Requesting `P+1` physical values cost one extra backing read at every one of fifteen measured interior leaf boundaries on S3, JDBC, and DynamoDB. The reducer's one-result semantic lookahead independently decides whether another read happens.
- **Service-edge admission.** A small bulkhead bounds concurrent enumerations and holds a cancelled request's slot until its synchronous backend call physically returns (a deadline is not a physical lifetime bound on the reviewed stores). The replay admission ledger (currently embedded in the service governor) is rehomed here. Per-request work/time/memory budgets come from the existing execution contract.

The pure-step/`NeedRead` boundary plus the already-proved refinement models (`ReducerReadAhead.tla`, `DescriptorCoalescing.tla`, `WeightedResponseLease.dfy`, `ServiceLifecycle.tla`) are parked as the designed seam for a future concurrency change. The adapter capability record keeps the split between semantic concurrent-read safety and deployment width so that change needs no SPI break.

### 7. Adapter reads have three outcomes, classified

Every attempt produces exactly one of:

- **complete** — validated, strictly ordered, duplicate-free, strictly progressing values; possibly legitimately empty;
- **failure** — classified retryable or terminal, carrying a cause code (missing node, decode error, retry exhaustion, count of discarded partial values); partial output is always discarded atomically;
- **cancelled**.

Only complete integrates. Missing storage, decode errors, and retry exhaustion are failures, never empty authorization results; an adapter that cannot distinguish them is not qualified. Every retry preserves the exact basis and descriptor and the original absolute deadline.

This change also repairs EACL's own instances of the anti-pattern: Datomic and Datahike `select-exact` currently catch `Throwable` and return nil, converting transient faults (and interrupts) into terminal "snapshot expired". The fix is classification, asymmetric per backend: Datomic narrows the catch (nothing in its body legitimately throws for trimmed history); Datahike maps genuine absence (GC'd commit, foreign locator format) to nil and propagates everything else as a classified failure. Konserve DynamoDB 0.1.32 collapses all exceptions to nil upstream; Datahike/DynamoDB stays unqualified until that is repaired or wrapped.

### 8. Result-edge cursors; continuation is consistency-aware

A public cursor is one HMAC-authenticated edge token binding: format/key/order-ABI versions, the composite fingerprint, backend and source lifecycle, exact basis identity, normalized query/principal, fixed positive page size, the boundary result's one-based ordinal and canonical external identity, and expiry. It contains no reducer state, cache pointer, seen set, or rolling prefix commitment (the rolling commitment was proved redundant: equal committed prefixes do not establish equal next pages). Navigation mode is request input: the same edge serves `after` (resume at ordinal + 1) and `before` (resume at `max(0, ordinal − 1 − page-size)`, run forward through the supplied edge as the validation lookahead, return the preceding prefix). Page size is fixed per cursor chain. Backward pagination stays in scope — it is public API today.

Two rules the earlier draft omitted:

- **Consistency modes.** Continuation must satisfy the request's consistency mode (`:minimize-latency`, `:fully-consistent`, `:at-least-as-fresh`, `:at-exact-snapshot`), evaluated against the basis actually used for continuation. When no permissible continuation basis — the exact basis, or a newer one admitted by the dependency-proof rule below — satisfies the freshness requirement, the request fails with the existing typed cursor-consistency-conflict; it is never silently served stale.
- **Current-only topologies.** DataScript has no historical basis, and Datahike without commit-graph/history cannot reselect one. Strict exact-basis pinning alone would let every unrelated write kill every outstanding cursor there. Continuation on a newer basis is therefore permitted exactly when a certified dependency proof covers the traversal's **full read scope** — every scan the replay performs, including observed-empty ranges — unchanged since the cursor basis. Anything less (answer-set equality, relation-level stamps that miss empty-range inserts) is insufficient because sequence equality needs every scan equal. Absent such a proof, the topology rejects continuation with a stale-cursor result. Reminting a cursor by entity ID against a new basis is always forbidden.

### 9. Continuation state: latest-only exact checkpoints plus governed replay

The reshaped continuation store is the progress tier — no fourth cache tier is added. A checkpoint is captured only between transitions with no pending integration and contains complete history-free reducer state: exact admitted identities, stack, static cursors, scalar counts, logical scan bounds, deterministic counters, **and the undelivered lookahead/boundary segment** (`RuntimeCheckpointComposition.dfy` requires `boundary.pending == results[ordinal..]`; a checkpoint that stores the admission key but not the pending value silently loses that result on resume). The coordinate records delivered and discovered counts distinctly, since they differ by one after a lookahead.

Keys are the complete exact execution identity **including the composite fingerprint** (today's continuation scope identity omits it; an order-ABI change without a schema change would otherwise alias checkpoints). Replacement is scalar: for one key only a strictly greater canonical transition ordinal replaces the retained checkpoint — deterministic execution totally orders states per key, so the earlier component-wise vector adds nothing. Publication is synchronous, bounded, best-effort, `O(1)`; an overweight checkpoint is dropped without failing the request. The store exposes a byte-weight cap as a client option (closing the current 128 MiB-default gap where clients can pass only `:max-entries`).

On a miss, governed deterministic replay reconstructs the prefix and validates the boundary ordinal and identity before returning results. Replay is bounded by the admission ledger and per-request budgets. The composition of byte cap, latest-only retention, and replay budgets creates a deterministic cliff: once the admission set outgrows the cap, no later checkpoint publishes and pages beyond replay budget are unreachable. That is a **distinct typed failure** (resource-exhaustion, not stale-cursor), and cap and budgets must be configured coherently. Cold evicted-checkpoint replay p95/p99 is the headline number for follow-on remote qualification.

### 10. Two cache artifacts; everything else is the storage layer's job

The engine keeps exactly:

| Artifact | Value | Safe reuse |
|---|---|---|
| progress checkpoint | one quiescent exact reducer state per execution identity | resume that exact execution |
| completed answer | a fully prepared public result | return the completed operation |

The projection tier is cut: it is order-safe but purely accelerative, is co-evicted with the checkpoint in every real miss scenario (restart, rotation, pressure), and duplicates bytes the storage layer (Datahike node cache, LMDB tier, Datomic peer cache) already caches with correct invalidation. The denotation tier is cut: a set-correct subproblem denotation provably cannot be substituted into stable enumeration (`CacheBoundary.dfy` counterexample), so the tier could never legally serve its purpose; the prohibition survives as a spec rule. Production occupancy of both was measured trivial (70 entries / ~9 KB; zero entries).

Two answer-cache rules are new requirements: the completed-answer key must incorporate the composite fingerprint (today it binds only `:engine-version`, so a routing flip could serve pages in the other engine's order from one process), and a cached answer containing pagination cursors must not be served across a basis change unless continuation of the embedded cursors remains permitted under the continuation rules — otherwise a managed-generation hit on a current-only topology serves a permanently dead cursor.

Cancellation salvage is the latest checkpoint only. At width one with one-value release, the abandoned in-flight state is at most one chunk buffer, reconstructible from the logical bound.

### 11. The compact-representation contract is normative

The measured memory wins are representation wins and are required, not optional: specialized immutable per-kind admission key classes (42.7–43.1 MB per million versus 138.6–140.1 MB for vector keys), request-owned transients with freeze-before-publish and fork-from-persistent (79% allocation and 36% time reduction at 100k admissions; Clojure does not enforce transient thread ownership, so EACL enforces linear ownership itself), right-edge stack with `peek`/`pop` and reversed successor append, compact ~53-byte logical scan frames, and latest-only checkpoint retention (5.04 MB versus 6.39 MB for a 64-candidate queue). The backing proof leaves (`RuntimeStackRefinement`, `ConcreteHistoryFreeRuntime`, `OwnedTransientSnapshot`) are part of the retained assurance set.

### 12. One semantic authority; assurance is evidence plus bridges, not a runtime twin

The production engine is a hand-written portable CLJC port of the accepted prototype (**not** of `portable_indexed.cljc`'s rejected `:stable-discovery` mode), with logical width pinned to one. There is no generated-Java runtime authority and no host-side recomputation double-authority for the new engine: the current verified-kernel pattern recomputes ~10 trivial decision tables and fails closed on mismatch, which never guarded the traversal semantics this change replaces, and its Dafny-interop marshalling is itself unverified trusted surface.

What replaces it: (a) cheap structural invariants stay **always-on and fail closed** at the engine boundary (page within validated bounds, take-count ≤ requested size); only expensive per-value adapter-obligation guards remain opt-in; (b) every finite decision table that survives gets an exhaustive whole-input-domain test bridge; (c) CLJ and CLJS trace bridges against the Dafny model, differential oracles against the frozen engines until routing flips, and the mutation controls (including the logical-width and order mutants). The fast formal gate (43 leaves / 536 obligations / ~7.8 s) is design evidence; the release gate is the production bridge suite.

### 13. Delivery: archive, build, gate locally, route, delete; remote qualification follows

Sequenced to keep one authority and an honest rollback story:

1. **Archive the evidence.** Copy `target/exploration/stable-discovery/` (prototype, models, protocols, audits) into tracked storage before anything else.
2. Freeze reproducible denotation/cancellation/pagination/allocation baselines against the current engines (the retained oracles).
3. Implement sealed planning, the generic reducer, pagination/checkpoints, and the width-one shell behind an internal switch, porting only the retained formal leaves.
4. Implement the point-check and exhaustion-count routes on the new engine, the answer-cache composite-fingerprint keying, and decide the fate of the public `:complete-denotation` evaluation mode (currently forced through machinery this change deletes). `expand-permission-tree` is engine-independent and needs no route.
5. **Local gates**, all pre-flip: DataScript CLJ and CLJS semantic/allocation/latency gates (the end-to-end public width-one path has never been measured; the binding budgets are median within 0.25 ms or 25% of legacy, allocation ≤ 1.5x, no linear scaling in unvisited branches); adapter contract tests with failure/latency/missing-node injection on MinIO, JDBC, and DynamoDB-local; the `select-exact` classification fix; tiered LMDB/S3 branch-head freshness and writer-synchronization correctness via MinIO.
6. **Route every public entry point** through the new engine on all backends. Width one is profile-neutral: no certified adapter behavior changes.
7. **Delete**: the acyclic merge route and `lazy_merge_sort`, the symmetric/byte-stable candidate (dual-mode branches, prefix commitments, join buckets, dual limit ABIs), the physical scheduler entirely (including cross-request shared-read machinery), the service governor (its replay ledger rehomed per Decision 6), the projection and denotation cache tiers, obsolete formal models, and the old cursor branches. Deletion is gated on the frozen baselines and the evidence archive; rollback after deletion is git revert of the routing commit, acceptable only because v8 is unreleased.
8. **Follow-on remote qualification** (real S3, tiered LMDB/S3 at scale, JDBC pools, DynamoDB after the Konserve repair, Datomic Pro): performance envelopes, topology width defaults for the future concurrency change, and evicted-checkpoint replay p95/p99. It gates performance claims and topology enablement, not the engine.

## Risks / Trade-offs

- [Stable DFS can choose an empty or late-productive branch first] → certified static rank + canonical tie-breaking; benchmark narrow, unauthorized, and late-productive principals; rank non-dominance is documented.
- [Deep dependent chains are serial at width one] → depth × RTT is a schema property no width fixes; storage tiering (LMDB: 0 GETs, 0.7–1.7 ms warm) is the mitigation; the concurrency seam stays open.
- [Admission state grows with the prefix] → specialized keys, per-request budgets, typed resource-exhaustion failure; never approximate suppression.
- [Latest-only checkpoints + byte cap + replay budgets make deep pages unreachable past a threshold] → distinct typed failure, cap/budget coherence rule, p95/p99 replay measurement in follow-on qualification.
- [Current-only topologies lose cursor durability under strict basis pinning] → certified full-read-scope dependency-proof continuation, else explicit stale rejection.
- [One-value release adds ~11% warm CPU on full enumeration] → CPU-only (I/O batching preserved by retained buffers); order-insensitive count specialization remains permitted with an independent denotation-equivalence proof.
- [Hand-written single authority could silently diverge where the double-authority failed closed] → always-on structural invariants, exhaustive-domain bridges for finite tables, differential oracles until the flip, mutation controls.
- [Cold evicted-checkpoint replay is minutes-scale on cache-less remote stores] → bounded governed refusal (budgets/timeouts), storage tiering, and explicit follow-on measurement; deep-cold topologies are exactly the ones gated behind qualification.
- [Formal models diverge from optimized Clojure] → source bridges, mutation controls, and oracles precede deletion.

## Migration Plan

No data or published-cursor migration. Development proceeds by replacement per Decision 13. Incompatible development tokens are rejected explicitly after the ABI flip, never reinterpreted. Rollback before deletion is the internal engine switch; after deletion it is git revert of the routing commit.

## Open Questions

- What are the real p95/p99 first-page, continuation-hit, and evicted-replay latencies per production topology at width one? (Follow-on qualification; decides whether the concurrency change is ever worth opening.)
- What checkpoint weight and replay depth would justify durable remote checkpoint serialization? (In-process latest-only until measured.)
- Which Datahike commit/history retention policy yields the intended cursor TTL at acceptable storage cost per deployment? (Current-only operation with the dependency-proof path remains a valid explicit capability.)
