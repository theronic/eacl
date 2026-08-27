## Context

See `proposal.md` for motivation and the delta specs for normative behavior. This design is based on the committed v8 baseline `8dc3b16498788dd822b68e1c4fe25b37a8e8879f` in the isolated `core2` worktree.

The baseline already contains most of the low-level mechanisms an operator engine should reuse:

- acyclic least-derivation-path enumeration and history-free keyset resume;
- recursive first-discovery evaluation and governed continuation state;
- finite, strictly ordered, unique forward/reverse relationship scans;
- exact `:direct-match?` membership, inclusive reseek, min-side arrow intersection, candidate windows, deadlines, cancellation, and dimensional counters;
- snapshot-bound sealed plans, complete relation proof frames, completed-answer reuse, and the proposed exact scan-response cache seam;
- generated/formal authority for union-only positive unary propagation, least-path order/resume, membership-probe checks, and bidirectional arrow intersection.

The missing structures are fundamental rather than syntactic. Permission rows and sealed rules flatten every permission to a union. `GroundedPositiveProgram.dfy` models base atoms plus unary implications. There is no multi-premise rule, signed dependency, stratum, operator expression node, candidate-cover proof, Boolean vector program, or recursive join state.

The prior uncommitted `support-permission-intersection-and-exclusion` design correctly identified canonical expressions, strict stratification, and multi-premise state, but its lookup strategy is incompatible with the current least-path engine and remote-store constraints:

- k-way merge must open every selected child before it can establish its first result;
- equal-prefix selectivity pilots deliberately read every eligible child;
- ascending-EID output contradicts the committed least-path ordering ABI;
- `:direct-match-many?` specifies semantic batching but does not prevent a scalar loop or a disastrous wide range scan;
- any-child recursive join state allocates for the union of premise entities instead of a selective premise.

### REPL evidence gathered before this design

All experiments ran through nREPL from `core2`; the Datahike experiment used a generated baseline kernel and an in-memory Datahike 0.8.1759 database. These are algorithm experiments, not S3 latency claims.

| Experiment | Result | Design consequence |
| --- | ---: | --- |
| Recursive random expression cover/filter, depth up to 5 | 100,000 trials, 0 set or uniqueness failures | A recursively selected cover plus exact predicate is a viable semantic skeleton |
| High-overlap 20-item page, adaptive batch | 21 logical candidates, 21 physical candidates | Demand-sized growth avoids maximum-batch overread |
| Same page, unconditional width 256 | 21 logical candidates, 256 probes | `256` must be a cap, never the default first read |
| One-in-1,000 intersection, 10,000 candidate window | 10 accepted, 10,000 examined, bounded progress | Laziness bounds work but cannot cure a bad general generator under skew |
| Direct intersection: 100,000 dense values vs 100 sparse values | eager 100,100 values; dense driver hit window; sparse driver 24 probes; leapfrog 41 head steps + 20 reseeks | Compatible direct leaves need a seekable specialization that is not hostage to the sealed general anchor |
| Direct n-ary intersection: 100,000-row driver, 100,000-row identity operand, 100-row sparse operand | repeated binary filtering 100,199 head comparisons; max-head k-way 199 anchor rounds + 5,744 binary-search comparisons; identical 100-row sequence; 1,000 randomized trials, 0 failures | Direct n-ary specialization must position every operand at the anchor and jump the driver to the maximum child head; repeated binary filtering is not an acceptable implementation |
| Warm in-memory Datahike, 256 adjacent candidates | exact median 279.833 us; prefix median 72.250 us; 256 prefix values | Compact range merge is worthwhile |
| Warm in-memory Datahike, 256 candidates spanning 20,000 EIDs | exact median 322.750 us; prefix median 5,582.541 us; 20,000 prefix values | Unconditional min-to-max prefix batching is unacceptable |
| Shared-DAG Boolean memoization, 10,000 candidates | 60,000 leaf probes to 40,000 with equal results | Candidate-local expression-node masks should be mandatory |
| Anchor-gated 3-premise joins under skew | 25,000 trials, 0 denotation failures; 4,798,525 any-child states vs 499,797 anchor states | Allocate parent join state only after a deterministic anchor fact exists |
| Operational recursive anchor worklist | 5,000 trials, 0 result, arrival-order, or retained-state failures; 679,789 any-child slots vs 445,115 anchor slots | The retained-state transition must be proved equal to recursive least-fixed-point propagation, not only to a static join |

The existing Datahike/MinIO evidence supplies the remote interpretation: one missing persistent-set node is one S3 object GET; the store cache holds 8,192 node entries by default; warm requests often issue zero index GETs; and exhaustive 100,048-result count previously required 3,062 GETs even though its first page required five. Logical laziness and physical cache misses must therefore be measured separately.

## Goals / Non-Goals

**Goals:**

- Define one exact, finite, typed semantics for union, intersection, positive recursion, and strict stratified exclusion.
- Preserve the committed union-only plan domain, least-path order, recursive order, and deterministic work traces.
- Make bounded acyclic lookup consume one proven candidate stream plus only the unresolved exact predicates needed for examined candidates.
- Carry generator witnesses far enough to eliminate redundant anchor/left checks and share repeated DAG decisions within a batch.
- Use ordered direct-leaf specializations and Datahike locality without putting set-algebra semantics in adapters.
- Bound low-selectivity behavior with correct resumable progress rather than pretending every plan can be optimal without statistics.
- Reduce recursive conjunction state to anchor-qualified entities while remaining least-fixed-point exact for every fact arrival order.
- Reuse only proof-compatible completed values or exact scan responses; never turn incomplete negative work into absence.
- Mechanically prove the abstract semantics and every proposed algorithm before editing production evaluator/routing code, then prove/refine the implementation before enabling operator writes.
- Measure cold/warm remote work, S3 GETs, logical work, allocation, and exhaustive behavior independently.

**Non-Goals:**

- A global object/type scan, materialized permission sets, incremental authorization views, persistent statistics catalog, or derived permission metric datoms.
- A request-time selectivity pilot, learned cache-dependent generator, or arbitrary cost-based reordering of public output.
- A universal claim that a static generator is optimal under unknown data skew.
- Matching SpiceDB lookup order, experimental query-planner traces, depth-limit behavior, or unstratified negative recursion.
- Direct chained arrows, `.all()`, caveats, wildcard subjects, subject relations, `nil`, or `self`.
- Hiding exhaustive-count cost behind first-page averages or claiming an exact S3 GET ceiling before the pinned MinIO fixtures establish one.
- A new general operator-result cache. Existing completed answers, completed point subproblems, and exact scan responses are sufficient initial cache boundaries.

## Decisions

### D1. Store a source expression and compile a separate semantic DAG

The parser produces a closed source value:

```clojure
{:format :eacl.permission-expression/v1
 :resource-type :document
 :permission-name :view
 :root
 [:intersection
  [:union
   [:relation {:name :reader :subject-types [:user]}]
   [:relation {:name :writer :subject-types [:user]}]]
  [:exclusion
   [:permission {:name :member}]
   [:relation {:name :banned :subject-types [:user]}]]]}
```

Closed node tags are `:relation`, `:permission`, `:arrow`, `:union`, `:intersection`, and `:exclusion`. One-hop arrow nodes store every statically resolved subject-type partition. The source tree preserves grouping for diagnostics and permission-tree expansion.

The sealer builds a separate canonical semantic DAG:

- directly nested unions and intersections flatten;
- commutative children sort by complete canonical value and deduplicate;
- structurally equal nodes intern after equality checking;
- exclusion remains binary and ordered;
- named-permission and arrow boundaries remain explicit;
- no distributive, complement, exclusion reassociation, or recursion-sensitive rewrite is permitted.

One expression entity is keyed by `[resource-type permission-name]` and stores only its authoritative identity and canonical payload. The payload carries its own closed format version, so separate format, expression-digest, and policy-digest attributes are redundant and are not durable. Source node/depth/fan-in, encoded-byte, normalized DAG/slot/word, checkpoint-weight, aggregate, admission-limit, cardinality, and physical-cost values are likewise never durable permission attributes. Every permission, including a union-only permission, uses the canonical expression representation. Union-compatible expressions still compile into the existing union-only sealed-plan domain.

On every cold read the decoder applies hard-bounded payload decoding, canonical verification, the selected client's per-expression limit checks, DAG normalization, and aggregate schema checks before the expression can be sealed. The resulting structural metrics and normalized DAG may then be cached inside that client by `[schema-generation authoritative-expression-fields effective-client-limits]`. A runtime content fingerprint may be computed for cursor or plan identity, but collisions must be excluded or checked against the payload and the fingerprint is never stored as schema authority. Application relationship writes do not invalidate structural entries. An explicit refresh evicts and recomputes them from authoritative payloads; it never consults or recreates retired attributes.

Released v7 flat permission rows are a supported upgrade input, not an alternate v8 read representation. The upgrade parses and resolves the complete candidate schema, verifies that its permission replacement is representable and semantically compatible with the supplied/stored v7 schema, and computes the complete transaction before changing data. Necessary v8 attributes are additive. The commit atomically retires the old flat permission entities, writes canonical expression entities, and advances the schema/version stamp. Existing released v7 relationship tuple attributes and tuple datoms are reused byte-for-byte: the upgrade performs no relationship enumeration, backfill, rewrite, or rebuild. A preflight or transaction failure leaves the prior permission rows and schema stamp active; harmless additive attribute definitions may remain installed but cannot change v7 authorization semantics.

Per-permission source and normalized-DAG limits and aggregate schema limits have calibrated defaults but are immutable client-construction options. A client may tighten or raise them only within checked implementation/codec hard ceilings. They are neither schema facts nor part of the durable expression format. A stricter peer may reject a schema accepted by a more permissive peer, but every peer that accepts the payload derives the same denotation and sealed semantic plan. Code-level expression, DAG, operator-plan, and cursor format versions remain stable compatibility inputs independent of the tuneable resource limits. The existing permission-tree limits remain independent rendering limits.

Alternative rejected: one backend entity per AST node. It adds portable ordered-child storage, graph replacement, and many definition reads without improving execution, because sealing still needs a bounded in-memory DAG.

### D2. Use strict stratification over a finite typed denotation

For one immutable snapshot, let a fact be `[expression-id entity-type entity-eid]`. Union is a family of single-premise positive rules. Intersection is one positive rule with every distinct child fact as a premise. Existing relation and arrow leaves provide grounded base/join facts.

An exclusion `L - R` has a positive dependency on `L` and a negative dependency on `R`. Tarjan SCC classification runs over the explicit expression dependency graph. Any SCC containing a negative edge is invalid. The condensation DAG receives deterministic strata such that a negative target is strictly lower. Each positive stratum is evaluated to its least fixed point before an upper negative premise can consume absence.

This intentionally rejects recursive double negation. Two negative edges do not cancel operationally: accepting such programs requires stable/well-founded semantics and a different proof/runtime contract.

Alternative rejected: SpiceDB-style acceptance followed by a query depth failure. It makes schema validity depend on request shape and can store permissions that never produce a total decision.

### D3. Prove formal algorithms before production implementation

Work is divided by an enforceable gate, not by convention.

**Proof phase A — no production evaluator/routing edits:**

1. Extend the finite source denotation and grounding to typed n-ary positive rules plus strict stratified negative premises.
2. Prove signed-SCC validation and stratum construction sound.
3. Prove candidate-cover containment, witness validity, predicate exactness, order/uniqueness, and cursor suffix composition.
4. Prove scalar/vector equality, adaptive batch progress, leapfrog intersection, anti-join, density-bounded range merge, and sparse seek fallback against one generic evaluator.
5. Prove anchor-gated recursive conjunction equivalent to ordinary least-fixed-point propagation for every arrival order and bounded by anchor facts.
6. Prove completed lower-stratum absence, signed cache invalidation, and cache elision refinement.
7. Register mutation controls and pin the locked proof obligation counts.

REPL experiments and isolated oracle/prototype namespaces are allowed in this phase. No public namespace may parse, store, seal, route, or execute an operator.

**Implementation phase B — after phase A is green:** production parser/storage/SPI/sealer/evaluator work may begin behind a disabled schema-write and routing gate. Source-digested specializations, generated CLJ/CLJS bridges, adapter certification, and production mutation/refinement evidence are then completed before the gate can be enabled.

This ordering prevents handwritten behavior from defining the model after the fact. It also avoids the impossible requirement that a source-digest refinement exist before its production source exists: abstract algorithm proofs are preconditions to implementation; concrete refinements are preconditions to routing.

### D4. Compile a witness-carrying recursively exact generator for every acyclic expression

Let `D(E)` be the exact denotation. The compiler derives a raw candidate cover `C(E)` and a recursively exact generator `G(E)`. The cover proves only containment; it is not automatically a semantic witness for a nested child:

```text
C(leaf)             = leaf
C(union E1 ... Ek)  = union C(E1) ... C(Ek)
C(intersection ...) = C(sealed-anchor-child)
C(exclusion L R)    = C(L)
```

The execution is compositional:

```text
G(leaf)             = exact leaf iterator
G(union E1 ... Ek)  = least-path union/dedup of G(E1) ... G(Ek)
G(intersection ...) = G(anchor), filtered by exact unresolved children
G(exclusion L R)    = G(L), filtered by exact membership in R being false
```

The proof obligations are `D(E) subseteq D(C(E))` and `set(G(E)) = D(E)`. Each node applies its own exact predicate before its candidates become child-generator emissions. The existing least-path machinery supplies raw cover coordinates and union duplicate witnesses; operator filters remove candidates without reordering. A vector predicate `P(E, candidate, witness)` proves:

```text
P(E, x, witness) = true  iff  x is in D(E)
```

The public result is the recursively filtered generator:

```text
[x emitted by G(E) in filtered least-path cover order]
```

Each generator emission carries bounded evidence:

```clojure
{:entity       [entity-type eid]
 :coords       least-path-coordinates
 :true-nodes   portable-expression-bitset
 :derivation   bounded-generator-witness}
```

Raw cover evidence proves only the physical/cover derivation. Once a node's local predicate completes, its emitted candidate proves that semantic node true. A parent intersection therefore receives exact anchor-child evidence and does not recheck it; a parent exclusion receives exact left-child evidence; and a union receives a candidate from an exact granting child. A candidate from `C(E)` that has not passed `E`'s local predicate never receives those semantic witness bits. Witnesses are request/continuation evidence unless separately completed under a reusable semantic key.

The public generator anchor is selected once by a versioned structural tuple: directness and sequence compatibility first, then leaf/arrow depth and estimated static rule work, then canonical expression id. There is no semantic pilot. Cached relationship observations cannot change this anchor because changing it changes operator order and cursor identity. Observations may select only between physical kernels that are already certified to reproduce the identical generator sequence, logical progress, error partition, and cursor boundary.

Alternative rejected: dynamically choose the smallest observed child. Without a global output order, the chosen child defines lazy result order. Making that choice data/cache dependent either destabilizes cursors or requires materialization/sorting.

### D5. Define operator order as filtered cover order

For operator roots the order ABI is the recursively filtered least-derivation-path order of the sealed generator: every node preserves the relative order of its raw cover candidates while removing local rejects, and union composes exact child generators in sealed least-path order. It is determined by expression fingerprint, cover/generator graph, anchor identities, direction, selected snapshot, and order version.

For union-only roots, `C(E) = E` and sealing delegates to the existing plan domain byte-for-byte. Their order, fingerprints, recursive traces, and cursors do not enter operator code.

An operator cursor stores/authenticates the cover fingerprint and ordinary least-path boundary coordinates. These fields extend the authenticated semantic scope inside the current v8 cursor envelope; they do not bump its envelope version or add an old-version migration/rejection branch. Physical batch overread is not logical progress. If the accepted sentinel is candidate 21 of a 32-candidate batch, the page boundary remains the established public boundary; candidates 22–32 may have completed cache entries but are not skipped on resume. Candidate-window progress uses the last logically examined candidate, following the existing filtered-window contract.

Alternative rejected: global ascending EID order. General least-path child streams are not globally EID-sorted; producing ascending EIDs recreates the all-stream-head merge or requires a global catalog/materialization.

### D6. Evaluate exact predicates column-wise with shared Boolean masks

For a candidate batch of width `W`, the runtime allocates bounded bit vectors for known-true, known-false, unresolved, failed, and active candidates per demanded expression node. CLJ may use primitive word arrays; CLJS and portable checkpoints use vectors of 32-bit words. The portable state has explicit word count and byte weight.

Evaluation is mask-driven:

- generator witnesses initialize known-true masks;
- proof-compatible completed Boolean cache hits fill masks before physical work;
- each DAG node is evaluated once per unresolved candidate, even when the source tree references it multiple times;
- union removes candidates after the first true child;
- intersection removes candidates after the first false child and grants only after all children true;
- exclusion candidates arrive with left true and require exact right false;
- leaf misses are grouped by normalized physical descriptor;
- aligned subgroup results are scattered back to generator order;
- any subgroup failure fails the whole outstanding vector before emission/publication.

This is vectorization of exact scalar semantics, not speculative evaluation. A child that scalar short-circuiting would never demand is not probed merely because another candidate in the same batch needs a different branch; masks restrict each leaf subgroup to the candidates that demand it.

Request-local keys include expression id, typed entity, direction, anchor context, snapshot identity, and semantic configuration. Shared publication remains per completed Boolean/vector element under complete proofs; an entire transient vector is not a new cache tier.

### D7. Grow batches from remaining result demand, not from the maximum cap

The physical cap remains 256 candidates, but it is only a cap. For each filtered page step:

1. Let `R` be accepted results still needed including the lookahead sentinel.
2. If `R` is zero, stop without issuing a batch. Otherwise the first width is
   `min(256, remaining-candidate-window, R)`.
3. If the batch completes without satisfying demand, double the next width up to 256 and the remaining candidate window.
4. Cache hits are removed before backend grouping; the physical vector may therefore be smaller.
5. All physical probes, range values, seeks, and overread are charged to dimensional request counters.
6. Stop on sentinel, exhaustion, candidate window, limit, deadline, or cancellation.

This schedule makes an all-accepted 20-item page ask for 21 candidates rather than 256. Low-selectivity requests converge to full-width throughput after a few bounded calls. Batch schedule identity is sealed into semantic compatibility, although only logical progress affects output.

Once issued, a vector is one atomic semantic demand unit. If any demanded subgroup in that vector fails, the vector fails even when an earlier candidate would later prove to be the page sentinel; no prefix of that vector is published. The generic formal machine models this batch-atomic failure rule explicitly. Demand-sized first batches minimize the difference from scalar stopping behavior, while cursor progress still excludes physical candidates after the logical sentinel.

Alternative rejected: fixed width 256. The REPL case performed 256 probes for 21 logically needed candidates—more than twelve times the necessary membership work.

### D8. Specialize only sequence-compatible direct ordered leaves

The generic cover/predicate evaluator is always correct. Two specializations are admitted when their sequence equivalence is certified.

**Direct intersection:** If the anchor and remaining operands are direct, same-typed, strictly ordered unique EID scans with compatible bounds and the anchor cover order is that EID order, use anchor-preserving max-head k-way leapfrog/galloping intersection. Position children in sealed order at or above the current anchor, stop positioning immediately when one child is exhausted, jump the driver directly to the maximum child head when the remaining heads differ, and advance every matching cursor exactly once when all heads equal the anchor. Stop before opening any cursor when demand is zero; otherwise stop at demand or child exhaustion. The combined formal execution/result model proves that its output is exactly the demand-bounded prefix of the generic anchor-filtered sequence, that it executes at most one anchor round per initial driver value, at most one operand reseek per operand per anchor round, at most one driver reseek per anchor round, and at most `(operand-count + 1) * initial-driver-cardinality` combined reseeks. Its exact per-round operand-seek trace proves the aggregate operand bound without forcing operands after an exhausted child to open. Sequentially applying a binary filter is excluded: a nonselective early operand can otherwise force a full driver scan before a later selective operand is considered.

**Direct exclusion:** Preserve the left scan as output. Advance a compatible right scan monotonically to the current left EID and emit only on inequality/right exhaustion. Never collect the right set.

Compound unions or nested permissions whose least-path order is not the same EID order do not qualify, even when every leaf is individually sorted. They use cover/predicate evaluation.

Alternative rejected: general k-way intersection. It forces a head from every child and can recursively open data-proportional arrow streams—the same remote-read shape removed from v8.

### D9. Give Datahike a density-bounded exact leaf batch

Datahike stores forward and reverse relationship tuples on endpoint-local EAVT attributes. For one descriptor and sorted distinct candidate EIDs, the batch kernel chooses without reading data:

```text
k    = candidate count
span = checked(last-eid - first-eid + 1)

if span <= 2 * k:
    bounded tuple-prefix merge from first through last
else:
    sorted exact probes with inclusive/galloping seek reuse
```

The production `2` multiplier is versioned and selected by the pinned Datahike memory/file/MinIO gate. The safety argument does not depend on observed density: a unique integer-EID range contains at most `span` distinct relationship values, so range mode realizes at most twice the candidate count. The proved `4k` bound remains a conservative upper bound, not the selected production threshold. Overflow or a non-portable identifier rejects range mode.

Prefix mode merges the sorted candidates against the endpoint/relation/type tuple prefix and stops after the last candidate. Sparse mode performs exact full-tuple probes or reuses an inclusive prefix cursor to gallop, whichever the adapter certification shows has fewer node accesses without widening the semantic demand. Both use the same borrowed immutable DB captured by the adapter. Neither calls `d/db`, schema lookup, or branch selection per candidate/batch.

Range and scan-based galloping operations pass through the existing routed physical-fetch seam and may reuse an eligible exact scan response. A backend-native or scalar exact-membership operation instead produces completed point Booleans; it does not manufacture or populate a scan-response prefix unless it actually issued the corresponding bounded scan descriptor.

The candidate vector is sorted/deduplicated only for I/O. A permutation maps decisions to the input generator order. Forward and reverse implementations are independent certified duals.

This is not an S3 multi-get. Fewer GETs arise from bounded contiguous B-tree traversal, shared interior nodes, and the 8,192-node LRU. Sparse probes may still touch many leaves. The MinIO gate, not the vector call count, decides remote success.

Alternative rejected: always scan from the minimum to maximum candidate. In
the retained REPL fixture it realized 20,000 tuples and took 5.583 ms for 256
sparse candidates whose exact probes took 0.323 ms.

### D10. Make caching elide exact work at existing safe boundaries

Cache layers are consulted in this order:

1. completed top-level answer;
2. request-local expression-node Boolean/mask;
3. proof-compatible completed point Boolean or lower-stratum answer;
4. eligible exact scan-response prefix at the physical fetch seam;
5. backend scalar/batched read.

Generator selection, child order, batch schedule, candidate-window boundary, and cursor progress are sealed independently of cache state. A hit only fills an already demanded mask or returns the identical physical chunk. `:cache? false` performs no lookup, proof-lifting, flight, or publication work.

Negative entries require a completed exact right Boolean or completed lower-stratum denotation and the full static signed dependency proof. A witness, empty bounded page, scan prefix, cancelled batch, timeout, active recursion marker, or unfinished fixed point never proves absence.

The proposed exact scan-response cache composes for leaf work that issues ordinary bounded scan descriptors. Native/scalar point membership composes through completed Boolean entries instead. Operator execution does not introduce a second prefix/segment cache, reinterpret a point result as a scan prefix, or widen fetches for cache population.

Two additional non-authoritative cache classes remain strictly separate:

1. **Structural expression metrics.** Exact source/DAG dimensions and normalized DAGs are recomputed from canonical expression payloads under the client's effective limits and cached by schema generation, authoritative expression fields, and those limits. These entries are immutable until that client-local key is evicted.
2. **Relationship observations.** Bounded cardinality/selectivity and physical-I/O observations are keyed by selected backend/source identity, relation descriptor and direction, relation version or adapter high-watermark, and operation shape. Each entry carries its completeness class; exact exhaustion monotonically dominates later samples at the same key. Ordinary scans, probes, batches, and exhausted iterators update the entry organically. An exhausted exact stream may publish an exact cardinality for its watermark; a partial stream publishes only lower bounds and sampled density/selectivity. No request is widened and no otherwise-undemanded index is opened to improve an estimate.

An explicit refresh API supports structural recomputation and relationship-stat refresh. Structural refresh is exact and bounded by schema limits. Relationship refresh defaults to clearing stale observations and collecting only bounded probes under caller-supplied work limits. Exact relationship counting is available only as an explicit exhaustive mode and is charged like exact count; it is never triggered by a cache miss. High-watermark mismatch invalidates or partitions observations before reuse. Backends without native statistics use EACL counters alone.

Datomic I/O stats are optional physical telemetry. They describe cache/storage-tier I/O performed by an operation and depend on the current Datomic cache state; they do not reveal exact relationship cardinality. The Datomic adapter may normalize them into cache-hit/miss and storage-read cost observations. Datahike, DataScript, Datalevin, and third-party adapters may report equivalent normalized telemetry or none. Correctness and plan identity never depend on this optional capability.

### D11. Use anchor-gated semi-naive state for positive recursive intersections

Recursive operator roots remain in a generated deterministic scheduler. Facts are keyed by `[expression-id entity-type entity-eid]` and admitted once. Every positive intersection chooses one sealed anchor slot.

The engine retains the complete fact set for each expression node because those facts are the least-fixed-point state. Parent join state is different:

- a non-anchor child fact is admitted normally;
- if the entity has no anchor fact, no parent join state is allocated;
- when an anchor fact arrives, allocate one bit vector and initialize it by exact membership in every already admitted child fact set;
- when a later non-anchor fact arrives and the anchor exists, set that slot;
- a satisfied-count prevents rescanning the bit vector;
- when all distinct slots hold, admit the parent fact exactly once;
- duplicate child facts are idempotent.

Thus retained parent state is bounded by distinct typed anchor facts rather than the union of child facts. The result is independent of arrival order; the 25,000-trial campaign observed no mismatch and reduced retained state by about 90 percent under the tested skew.

Union schedules the parent on any child fact. Exclusion is not part of a same-stratum worklist: an upper left candidate asks an exact completed lower-stratum question. Completed lower answers are request-memoized.

Portable checkpoints contain fact/frontier state, anchor join words, satisfied counts, completed lower-stratum markers, pending deterministic commands, and undelivered boundaries. They never contain backend handles, host arrays, or mutable cache state. Admission reserves checked fact, join-state, slot, and portable-byte budgets before allocation.

Alternative rejected: allocate intersection state on the first fact from any child. Under skew it retains state for every entity in the union of premises even though any single premise is a sound anchor bound.

### D12. Extend the backend SPI without moving semantics into backends

The required expression capability exposes canonical expression reads and metadata on a selected snapshot. The optional physical operation is conceptually:

```clojure
[:direct-match-many?
 {:descriptor normalized-direct-relation-descriptor
  :candidates [[entity-type eid] ...] ; distinct, bounded
  :direction :forward|:reverse}]
;; => [boolean ...] ; exactly aligned
```

The semantic contract is equality to repeated certified scalar membership. The adapter may select prefix merge, exact probes, native index batching, or scalar fallback. It may not evaluate compound permission expressions. Capability identity is validated and included in operator plan compatibility; absence selects scalar fallback.

Built-in and third-party adapters must expose canonical expression reads before they can construct a v8 client. Built-in schemas do not define the retired derived metric attributes; databases in which an experimental build installed those attributes may leave the attribute definitions unused, but v8 readers, writers, pulls, exports, and comparisons ignore them and new expression transactions never assert them.

The released-v7 upgrade boundary is explicit. Existing relationship tuple storage is already the v8 relationship ABI and is reused without data migration. Only permission definitions and the schema/version stamp change. The migration requires a complete valid source schema (or a verified canonical conversion of the stored v7 source), performs all semantic validation before commit, and rejects missing, ambiguous, non-equivalent, corrupt, or unwritable replacements without retracting the old permission rows. No dual-read or dual-write authorization path is introduced. Development databases written by the superseded unreleased expression format may be recreated rather than interpreted as released v7.

### D13. Formal source and proof structure

The abstract proof phase adds or extends these leaves under `formal/stable-discovery/` and the executable kernel:

- `PermissionSetAlgebra.dfy`: finite typed expression denotation and per-stratum least fixed point;
- `SignedDependencyStratification.dfy`: SCC rejection and strict stratum construction;
- `CandidateCover.dfy`: recursive cover containment and union-only identity;
- `WitnessPredicate.dfy`: witness soundness and scalar predicate iff denotation;
- `VectorPredicate.dfy`: aligned masks, DAG sharing, short-circuit partitions, and batch atomicity;
- `OperatorLeastPath.dfy`: filtered cover order, uniqueness, bidirectional page composition, and logical progress under physical overread;
- `SeekableSetKernels.dfy`: leapfrog/galloping intersection and monotone anti-join sequence refinement;
- `DensityBoundedBatch.dfy`: checked span criterion, bounded prefix values, sparse fallback, permutation alignment;
- `AnchorGatedConjunction.dfy`: arrival-order-independent least-fixed-point equivalence and anchor-state bound;
- `StratifiedExclusion.dfy`: completed lower-stratum absence and fail-closed incomplete outcomes;
- `OperatorCacheRefinement.dfy`: signed proof invalidation and elide-only cache equivalence;
- temporal models/mutants for cancellation between subgroups, atomic vector publication, checkpoint/resume, and lifecycle expiry.

`EaclKernel.dfy`, the generated Java/JavaScript exports, manifest, assurance matrix, production decision inventory, source-closure ledgers, bridge vectors, obligation pins, and locked-tool reports are updated together. Mutation controls cover wrong precedence, swapped exclusion operands, missing intersection slot, active-recursion-as-false, partial negative publication, unsigned dependency closure, batch misalignment, cursor overread advancement, any-child state allocation, and cache-selected generator.

After implementation, CLJ/CLJS source-digested specializations replay the complete abstract decision partitions and exact dimensional traces. Backend certifications prove ordered scan/reseek and scalar/batch membership premises; they cannot prove the snapshot they themselves supplied, which remains an explicit trusted assumption.

### D14. Qualify semantics and performance with independent lanes

Four independent lanes block enablement:

1. **Independent EACL oracle:** finite set evaluation for acyclic expressions and naive Kleene-per-stratum evaluation for recursion. It supplies checks, both lookup sets, counts, and minimized random failures.
2. **Generated/cross-runtime:** abstract decision vectors and randomized counterexamples in JVM Clojure and ClojureScript.
3. **Cross-backend:** the same schemas/facts across Datomic, DataScript, Datahike, and Datalevin, including selected snapshots, limits, failure injection, and both directions.
4. **SpiceDB black box:** digest-pinned public API checks and drained lookup sets in `../eacl-spicedb`; return order is deliberately ignored.

Storage qualification adds a released-v7 upgrade lane populated with existing relationship tuples. It records the relationship datom/tuple count and content digest before and after upgrade, verifies zero relationship scan/rewrite commands during migration, verifies the new permission denotation, and injects every preflight and commit failure boundary. A disposable copy of the local Datomic development database may be used; destructive recreation of the source database is not part of the test.

Performance qualification freezes union-only baselines before operator routing. Union work counters must remain identical and matched-host median latency/allocation must stay within five percent. Operator fixtures separately record point, first page, continuation, candidate-window, bounded count, exact count, recursive state, and allocation.

The Datahike demo runs against loopback MinIO and records Datahike `:reads`, physical object keys/GETs, branch-head GETs, candidate/probe/batch counts, cache occupancy, bytes, and latency. Fixtures include compact and sparse batches, high/low overlap, dense/sparse exclusion, arrows, cold/warm repeat, adjacent pages, node-cache eviction, and exact count. Fixed numeric ceilings are checked into the benchmark evidence before operator writes can be enabled; this design does not fabricate those future measurements.

### D15. Error and resource semantics remain all-or-error

Client-construction configuration dimensions cover schema source bytes, per-permission source nodes/depth/fan-in/bytes, normalized DAG nodes/slots/words/checkpoint weight, aggregate schema dimensions, operator facts, live anchor states, join slots, portable checkpoint bytes, and physical batch width. Structural schema-admission limits are validated against hard implementation ceilings and remain fixed for the lifetime of one client. Existing command, fetched-value, advanced-value, probe, transition, queue, candidate, output, deadline, and cancellation limits continue to apply.

Every allocation reserves its logical weight before mutation. Every backend subgroup checks the cut point. No partial aligned vector, incomplete count, provisional page, unfinished lower stratum, or partial schema write is returned as complete. Errors use stable typed data and never expose backend entity ids.

## Risks / Trade-offs

- **[A sealed general anchor can be badly skewed]** -> Direct compatible intersections use anchor-independent leapfrog; general plans use bounded candidate-window continuation and honest telemetry. A statistics-driven generator is deferred because it would change order/cursor identity and needs its own validity model.
- **[Low-selectivity pages may still issue many S3 GETs]** -> Adaptive batching bounds demand; Datahike groups by descriptor and selects compact range or sparse seeks; node and exact-scan caches elide repeats; MinIO gates measure misses rather than inferring them from logical probes.
- **[Density by numeric EID span can miss a cheap range opportunity]** -> It is deliberately conservative: false negatives lose an optimization, while the selected `span <= 2k` rule stays inside the proved hard realized-value bound. The multiplier is versioned and benchmark-gated.
- **[Batch vectorization can violate scalar short-circuit error behavior]** -> Per-candidate masks issue only demanded leaves; any demanded subgroup failure atomically fails the vector; the scalar/vector formal differential includes failure partitions.
- **[A batch can fail on work physically after the eventual page sentinel]** -> Issued vectors are explicit atomic semantic demand units and publish no partial prefix. Start at remaining accepted demand, preserve logical cursor progress, model this rule formally, and test failure placement before, at, and after the eventual sentinel.
- **[Physical overread can corrupt pagination]** -> Separate physical probe accounting from logical candidate consumption; cursor proofs and mutation controls reject advancement past the logical sentinel/boundary.
- **[Recursive anchor choice can be poor]** -> It affects retained join state but not correctness. The deterministic structural tuple prefers direct/selective shapes; every state dimension is bounded and measured. Facts must exist regardless of anchor, but parent slot tables do not.
- **[Strict stratification rejects programs SpiceDB accepts operationally]** -> This is intentional. Total, provable semantics take precedence over schemas that fail later by depth. The differential corpus records the boundary.
- **[Superseded experimental-v8 databases use a botched representation]** -> They are disposable development artifacts and may be recreated. This does not relax the supported released-v7 upgrade: released v7 flat permission rows are accepted only by the explicit fail-closed migration, while ordinary v8 reads remain expression-only and no dual-read/write or binary-downgrade machinery is added.
- **[Formal scope is large]** -> Split abstract proof completion from concrete source refinement, pin every obligation, and keep routing disabled until both are complete. No handwritten interim semantics is accepted.
- **[Union-only code accidentally enters operator layers]** -> Preserve a separate plan domain and routing branch; require identical fingerprints/work counters and the five-percent matched-host gate.
- **[Exact count remains expensive]** -> It is inherently exhaustive. Stream with bounded memory, cache completed results where valid, and report it separately; do not weaken exactness or market it as lazy.
- **[Cache proof size grows through negative dependencies]** -> Static closure remains complete; entries exceeding managed-proof limits fall back to exact-basis or uncached execution rather than narrowing proof scope.
- **[Organic relationship estimates become stale or statistically misleading]** -> Key them by relation high-watermark, carry an explicit completeness class with exact observations dominating samples, treat partial observations as bounds rather than exact counts, and permit them to choose only trace-equivalent physical kernels. A missing or stale estimate selects the deterministic baseline and never triggers a scan.
- **[Datomic I/O stats are mistaken for relation cardinality]** -> Normalize them only as operation/cache-tier cost telemetry. Cardinality observations come from EACL's own realized values, probes, and exact exhaustion evidence.
- **[Peers use different local schema-admission limits]** -> Limits determine whether a client is willing to load or write a schema, not its denotation. Every read is checked under the receiving client's immutable configuration; caches are client-owned and limit-scoped; rejected schemas fail before planning. Operators that require fleet-wide availability must deploy a common limit profile operationally, without turning that profile into database authority.
- **[The v7 permission upgrade partially commits]** -> Fully validate and construct the replacement before mutation, keep relationship storage out of the transaction, and atomically swap permission entities plus the schema/version stamp. Test transaction rejection and retry against a disposable database copy.

## Implementation Plan

1. Keep all work on `agent/design-operator-engine-performance` in `core2`; freeze the committed v8 semantic/order/performance baselines and retain the REPL experiment seeds/results in the design evidence.
2. Build independent finite-set and naive-stratified-fixed-point oracles plus raw SpiceDB fixtures. They remain test/exploration code and cannot route production requests.
3. Complete proof phase A: all abstract semantic, algorithm, cache, progress, and bounded-state models; generated abstract decisions; mutation controls; locked obligation pins; and explicit project-operator authorization. Do not edit production parser/storage/sealer/evaluator/routing before this gate is green. Record independent review as an assurance qualification when it has not been performed; do not fabricate it as a prerequisite.
4. Add the closed expression codec, parser/validator, signed dependency compiler, limits, and single expression storage representation with public operator writes still disabled.
5. Extend the adapter capability boundary and certify scalar/batched operations on every built-in backend. Datahike receives the density-bounded batch; no backend receives compound permission semantics.
6. Compile source expressions to semantic DAGs, candidate covers, witnesses, predicates, routing certificates, and versioned fingerprints. Preserve union-only sealing byte-for-byte where required.
7. Implement point/vector acyclic evaluation, direct specializations, filtered cover pagination/counts, and cursor validation behind test-only routing.
8. Implement generated anchor-gated recursive conjunction, strict lower-stratum exclusion, bounded checkpoint state, and dimensional telemetry behind test-only routing.
9. Complete proof phase B: generated CLJ/CLJS bridges, source-digested specialization refinements, backend certifications, production mutation controls, source-closure manifests, and a recorded assurance audit. Preserve any absence of independent review as an explicit qualification.
10. Pass independent-oracle, cross-runtime, cross-backend, cache, failure, pagination, recursion, and `eacl-spicedb` suites. Persist every minimized counterexample.
11. Run union-only matched-host gates and operator strategy/resource benchmarks. Run Datahike/MinIO cold/warm/eviction/exact-count qualification and check in fixed accepted ceilings and raw summaries.
12. Remove derived expression metrics from built-in durable schemas and transactions; add generation-scoped structural metric caching, high-watermark-scoped organic relationship observations, bounded/exact explicit refresh, and optional normalized adapter I/O telemetry. Verify that cache misses never widen authorization reads or change public plan identity.
13. Implement and qualify the released-v7 permission-only upgrade against pre-populated relationship storage, including preflight/commit failure injection, unchanged relationship content digests, and no relationship enumeration or rewrite.
14. Recreate only superseded unreleased-v8 development fixtures as needed; verify clean v8 installation, released-v7 upgrade, source-level union compatibility, expression replacement, failure atomicity, export/import, and backup/restore.
15. Enable operator schema writes and routing only after every mandatory proof, conformance, storage, upgrade, cache-statistics, and performance gate is green. Update README/API documentation only with demonstrated behavior.

Until v8 is released, superseded experimental-v8 databases remain disposable and may be recreated. Released v7 databases are not disposable: their relationship storage must survive the v8 permission-schema upgrade unchanged, and a rejected upgrade must leave the v7 permission schema usable. Persisted compatibility with older experimental-v8 binaries and cursors is deliberately not a contract. Any later change to the released v8 storage or cursor contract requires a separate design.
