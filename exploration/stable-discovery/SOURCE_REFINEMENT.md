# Production compiler refinement contract

The abstract proofs do not establish that Clojure emits the modeled program.
The replacement compiler is accepted only when its sealed plan satisfies this
refinement relation and the independent executable bridge below.

## Inputs and normalization

For one exact schema basis and requested root permission, normalization must:

1. parse only the supported positive fragment: relation, permission alias,
   union, arrow-to-relation, and arrow-to-permission;
2. resolve every resource, subject, relation, and permission identity at the
   selected basis, failing on absence, ambiguity, type mismatch, malformed
   shape, or unsupported operator;
3. construct explicit node resource types, relation resource types, allowed
   relation subject types, and entity types; every sealed rule and grounded
   grant and reverse predecessor goal must satisfy the typed domain in
   `EaclForwardGrounding.dfy`;
4. flatten unions and remove exact duplicate normalized arms;
5. enumerate exactly the permission nodes whose grants can contribute to the
   requested root, including recursive strongly connected components;
6. preserve no caller map/set iteration order as semantic order.

No relationship data is consulted while compiling schema rules or rank. Cache
residency, latency, clock, worker count, and backend response order are not
plan inputs.

This is the complete EACL v8 authorization fragment: the public parser is
union-only and rejects intersection and exclusion. “Positive fragment” is not
permission to silently downgrade a schema operator that the public API
accepts; any future operator expands this refinement contract before it can
enter stable discovery.

## Exact rule correspondence

For every reachable normalized permission node `H`:

| Normalized arm | Exactly one sealed rule |
|---|---|
| direct relation `rel` with subject type `S` | `Direct(head=H, relation=rel, subject-type=S)` |
| permission alias `T` on the same resource | `SelfPermission(head=H, target=T)` |
| `via->relation target-rel` on intermediate type `I`, target subject type `S` | `ArrowRelation(head=H, via=via, intermediate=I, target-relation=target-rel, target-subject-type=S)` |
| `via->permission T` on intermediate type `I` | `ArrowPermission(head=H, via=via, intermediate=I, target=T)` |

There are no other rule forms and no missing or extra rules. Semantic rule
identity is the injective canonical tuple of its form and all fields above,
not a hash alone, host object identity, or printed map. After portable
normalization, its unique position becomes a dense canonical rule ordinal. The
runtime may use that compact ordinal only under the exact sealed-plan
fingerprint; it is not a cross-plan semantic identity.

The fixed-basis grounding is then exactly `EaclForwardGrounding.dfy`:

- direct relationships and pairs of target/via relationships create base
  grants;
- aliases create zero-read unary edges;
- each actual via relationship grounds an arrow-permission unary edge.

That grounding must have the same least fixed point as the independent public
authorization oracle for the accepted schema and relationship set.
`EaclForwardProducer.dfy` additionally requires the sealed consumer vector and
on-demand self/arrow scan producer to emit exactly the outgoing edges of that
grounding for every admitted grant.
`BidirectionalReachability.dfy` defines generic reverse denotation as traversal
of an exact transposed graph; `EaclBidirectionalReachability.dfy` instantiates
it directly with typed `Grant(node, resource)` identities and proves equality
with forward authorization without assuming numeric packing. The replacement
reverse rule index/producer must emit exactly those predecessors and
principal-owned bases as stated by `EaclReverseProducer.dfy`; the exact
admitted-goal frontier must refine `StaticReverseFrontier.dfy`.

## Direction indexes

The sealed plan derives indexes mechanically from the complete rule vector:

- forward seeds by principal subject type contain every compatible `Direct`
  and `ArrowRelation` rule exactly once;
- forward consumers by target permission node contain every
  `SelfPermission` and `ArrowPermission` rule exactly once;
- reverse rules by head node contain every rule with that head exactly once.

All indexes are immutable and complete. Forward traversal admits grants and
walks static consumer vectors. Reverse traversal admits concrete
`(node, resource-eid)` goals and walks static rules-by-head vectors. Neither
direction creates dynamic grant/consumer goal cells.

`StaticDirectionIndex.dfy` proves exact membership, unique rule IDs, cursor
progress, and exhaustion for both forward target-keyed and reverse head-keyed
indexes through one direction parameter. This closes the abstract reverse-index
shape without duplicating a forward-only proof. Production ranked-vector
construction and source conversion remain independent obligations.

## Root identity

Forward canonical result work is exactly `(root-node, resource-eid)`. Reverse
canonical result work is exactly
`(root-node, root-resource-eid, result-subject-type, subject-eid)`. For a fixed
request these tuples are injective in the returned internal EID. The adapter
must certify that internal returned EIDs map totally and injectively to public
typed identities at the selected basis. Failure or collision aborts the page;
renderer deduplication is forbidden.

## Rank and sealed vector order

Construct the permission-node dependency graph with orientation
`target -> head` and costs:

- self-permission: `0` storage reads;
- arrow-permission: `1` storage read.

All compiled nodes must reach the root. The compiler emits distance, dense
witness-edge, and hop arrays satisfying `ReadRankCertificate.dfy`. The
witnessed edge itself supplies the endpoint and 0/1 cost; duplicating those
fields in certificate arrays is forbidden. The small checker runs before
sealing the plan.
Failure is an internal schema/plan error; the engine never silently falls back
to byte order.

The source checker validates every array length and every integer range before
performing addition or indexing. `node-count` must be positive and within the
portable configured schema bound; every node index, distance, and witness hop
is a nonnegative portable safe integer smaller than `node-count`; every rule
ordinal is smaller than `rule-count`; edge and seed costs are the fixed values
above. These bounds make `cost + distance` nonoverflowing in both Clojure and
ClojureScript. No unchecked host integer arithmetic participates in plan
acceptance.

Seed rank is its fixed local read cost (direct `1`, arrow-to-relation `2`) plus
the certified distance of its head. Consumer rank is its local read cost
(self `0`, arrow-permission `1`) plus the distance of its head. The compiler
orders each static direction vector once by `(rank, canonical-rule-ordinal)`;
a linear validator checks exact membership and that ordering before sealing.
Runtime traversal consumes those vectors without sorting or comparing bytes.
Dynamic values retain certified backend scan order, as specified by
`CANONICAL_ORDER_CONTRACT.md`. The complete normalized rule vector, direction
indexes, rank certificate, static-vector/order version, scan-order ABI,
reducer phase table, and order ABI are canonical plan bytes and feed the plan
fingerprint.

The certificate generator need not be trusted. A deterministic 0/1 shortest
path implementation plus deterministic shortest-edge hop selection is the
intended generator; only the certificate checker, static-vector validator, and
canonical sealed plan are authoritative.

`SealedVectorOrder.dfy` proves that exact vector membership, globally unique
canonical ordinals, and strict lexicographic `(rank, ordinal)` ordering determine
one unique sequence. It also constructs membership-only, rank-only tie, and
duplicate-ordinal counterexamples. The existing sealed-plan bridge kills
matching missing-member, swapped-tie, duplicate-ordinal, and host-order
controls. Production linear validation and CLJ/CLJS byte equality remain
source obligations.

`SealedPlanReducerComposition.dfy` then proves that reversing an accepted
vector once into the concrete right-edge stack and repeatedly popping that
stack exposes exactly the unique canonical vector. Its push-without-reversal
control changes the trace. Production source still has to demonstrate this
composition at every static-rule and one-value scan successor boundary.

## Executable source-refinement bridge

Current exploration checkpoint: two deliberately small bridge stages are now
executable in the fast gate. The public stage checks 124 generated schemas
covering all 31 non-empty subsets of the five representative rule forms,
declaration/syntax permutations, exact independently reconstructed persistence
IDs, duplicate-arm collapse, and 19 invalid/unsupported families. The
normalized-schema stage checks 248 adapter-order cases, 1,408 exact semantic
rules, 744 exact forward-consumer/seed/node index comparisons, malformed
references, and focused drop/duplicate/reverse-arrow mutants. In a warm REPL
they take approximately 0.35 seconds and 0.07 seconds respectively; a complete
gate including fresh-JVM startup remained at six whole seconds.

A third exploration-only stage now exercises the ranked sealed-plan design.
The edit gate runs 32 generated plans; a two-seed qualification campaign ran
2,000. Every plan agreed with an independent Bellman--Ford distance oracle and
sealed identically after rule permutation/duplication. Eight controls delete
an index member, swap an equal-rank tie, duplicate an ordinal, corrupt a
distance or witness, discard transitive distance, or use host input order. All
are killed. This is prototype evidence, not production source refinement.
`RecordFraming.dfy` separately proves unambiguous pre-hash record boundaries;
the existing streaming canonical-record digest passed both the CLJ and full
CLJS suites and avoids the 65,536-byte whole-value ceiling.
The bridge also constructs a 1,000-dead-branch transitive-rank counterexample:
local-read-only ordering places the root-direct seed at position 1,000, while
the certified distance places it at position zero. The rank certificate is
therefore not removable planner ornament; without it, schema depth can restore
linear irrelevant remote reads even though every individual seed looks direct.

That checkpoint is intentionally narrower than the contract below. It does
not yet exercise each backend's `write-schema!`, the replacement plan's rank
and canonical order, reverse indexes, descriptor construction, public
traversal, CLJS, or cursor bytes. It therefore advances those two rows to
`checked-executable` without declaring this section complete.

The existing independent adapter-certification authority was also rerun for
DataScript, local Datahike in keyword and numeric-attribute modes, and Datomic
memory. All five tests passed. Each report materializes fixture truth and
checks complete strictly ordered duplicate-free forward/reverse windows for
both directions and inclusive/exclusive bounds, direct-match equivalence,
identity round trips, schema facts, immutable snapshot identity, and every
advertised exact-selection capability. The full DataScript CLJS suite passed
as well. This closes the local logical adapter API seam, not direct-S3,
LMDB-tier, real-DynamoDB retry/GET behavior, or replacement descriptor
conversion.

Fresh-JVM physical probes subsequently exercised the same captured-basis scan
shape against direct MinIO/S3, H2, loopback PostgreSQL 15, and strongly
consistent DynamoDB Local. Every concurrent result matched its sequential
oracle, but identical cold descriptors multiplied backing reads exactly with
width on JDBC/DynamoDB and nearly so on direct S3. At each of fifteen observed
interior PSS leaf boundaries, `P+1` issued one more GET/SELECT than demand-lazy
`P`. These are physical refinement facts, not new reducer theorems.

The failure seam is now explicit. A source adapter may submit only
`ValidatedComplete(values)` to the semantic driver after proving the bounded
range response is complete and ordered; a legitimate empty range is such a
success. Missing required storage nodes, retryable/terminal transport errors,
partial responses, and cancellation are failures and leave reducer state
unchanged. This is already the semantic classification proved by
`AtomicAttempt.tla`, so duplicating it in a second larger formal machine would
add proof latency without a new claim. The physical probe establishes that
konserve-dynamodb 0.1.32's catch-all `get-item` returns `nil`, after which PSS
raises `:node-not-found`: it does not become an empty EACL range, but its
original failure cause and retryability are lost. Repair/certification belongs
at the storage adapter boundary.

The exploration forward/reverse prototype now separates authoritative logical
scan frames from request-side physical buffers. A frame contains only the
logical occurrence and exact exclusive resume bound; the sidecar is keyed by
that occurrence plus the bound and may be empty. One-value release was checked
over physical widths 1, 2, 7, and 64 on nine forward and eight reverse
recursive fixtures. Retaining sidecars and deliberately dropping every
deferred sidecar produced the same exact sequence in all 153 comparisons; the
dropped runs retained zero sidecar buffers. A focused mutant that resumes at
the physical fetched-end rather than the logical bound skips values and is
killed. This is source-shaped prototype evidence, not yet production reducer,
checkpoint, cache, or concurrent-driver refinement.

The same sidecar helper was driven through 100,000 distinct nested-buffer keys
at configured capacities 0, 1, 4, and 16. All 400,000 transitions retained the
exact newest suffix and never exceeded the cap; width-64/capacity-16 retained
at most 1,008 unread values. `BoundedSidecar.dfy` proves the corresponding
unique/newest/count invariant. Real object weight, projection-cache ownership,
and concurrent aggregate capacity still require production heap qualification.

A fifth fast-gate bridge isolates the request-local physical shell. Across 512
generated exact-descriptor/completion schedules and window widths one through
eight, complete runs integrated only the canonical order; canceled runs froze
at a canonical prefix, discarded request-ready state, and still drained every
physical flight. Exact descriptors coalesced and the logical active-plus-ready
window never exceeded its cap. Integrate-any, omitted-position, physical-end-
resume, and post-cancel-integration controls are killed. The bridge is a pure
source-shaped state machine, so executor APIs, exceptions, real thread races,
backend retries, and response-store byte accounting remain production/source
qualification.

Before implementation is accepted, a test-only independent bridge must:

1. generate bounded valid and malformed schema ASTs without production parser,
   normalization, rule-index, rank, or traversal helpers;
2. generate finite typed object/relationship sets containing empty arms,
   overlaps, diamonds, self loops, zero-cost cycles, recursive SCCs, duplicate
   syntax, multiple subject types, deep aliases, broad arrows, and disconnected
   schema/data;
3. submit the external schema through the public production parser/compiler;
4. compare the complete sealed rule sequence and every direction-index
   sequence with an independent normalized-rule/rank oracle, including exact
   canonical ordinals and `(rank, ordinal)` order;
5. validate the rank certificate and compare all distances with an independent
   shortest-path oracle;
6. ground the sealed rules independently and compare their least fixed point
   with the independent authorization oracle;
7. compare public forward and reverse result sets with that same oracle across
   fresh traversals using different page sizes and adapter chunk sizes, while
   checking deterministic replay and no duplicate page items. Exercise exact
   Relay edge ordinals and external boundary identities, the short-terminal-
   page backward chain, and the same edge token as both `after` and `before`.
   Require `before` to resume at the computed page start, return the preceding
   canonical prefix, and validate the cursor edge as one bounded lookahead;
   cross-page-size reuse rejects, while bare `last` succeeds only after finite
   completion or an exact completed-answer hit under ordinary limits. Require
   one-value logical release, vary physical width independently, and drop
   request-side buffers at arbitrary transitions; every trace must be exact;
8. run the canonical cursor bytes, plan fingerprint, and cursor MAC/AEAD in
   Clojure and ClojureScript and require byte-for-byte equality; require
   deterministic public payloads with no checkpoint pointer and derived
   private-key collisions to fail full-context comparison as cache misses;
9. exercise the weighted response store with positive descriptor reservations,
   cache-hit pins, cancellation, completion, eviction, and rotation; require
   actual chunk cardinality within the declared maximum and calibrate the
   configured base/per-value weight as a conservative heap bound;
10. inspect concrete reducer/checkpoint state after long enumeration and require
    history-free stack/admission/scalar refinement, an in-flight result segment
    no larger than page size plus one, only optional last-delivered identity and
    pending lookahead after delivery, exact logical scan resume bounds but no
    physical response vector/offset/pin in authoritative frames or checkpoints,
    no reachable delivered-result trace, and
    rejection of zero/oversized/cross-size page requests before allocation;
11. run the width-one driver without constructing a readable-work index,
    future, speculative pin, or scheduler response object and compare its exact
    sequence with the indexed driver under completion-order permutations;
12. compare persistent and request-owned transient reducer representations;
    fork concurrent branches from one frozen checkpoint, reject use after
    freeze and wrong-owner mutation, freeze before thread handoff, expose no
    transient to physical workers or caches, freeze at every asynchronous read
    yield while allowing a proven synchronous width-one fast path, and run the
    same controls in CLJS;
13. run every enumeration fixture cold, with exact projection-cache hits, and
    with deliberately permuted cache residency; require identical pages and
    reducer traces. Whole-request completed answers and exact progress
    checkpoints use disjoint types from ordered projection chunks. No flat
    subproblem denotation may enter enumeration as a continuation, successor
    batch, or result batch;
14. stage oversized and boundary-equal successor batches and require
    subtraction-form preflight before any stack, admission, page, or
    counter mutation. Rejection must leave the complete persistent snapshot
    identical and unpublishable as newer progress;
15. print seed, shrunk AST/data, plan bytes, rule diff, rank diff, and traversal
   trace for every failure.

Required mutants delete or duplicate each rule form, reverse an arrow, swap
head/target, omit a subject type, fail to flatten a union, keep a duplicate,
drop a recursive node, use host iteration for ties, swap two equal-rank static
entries, omit rank certificate edge inequalities/hops, omit a fingerprint
field, accept an out-of-range witness hop before checking arithmetic, reorder a
backend chunk, omit physical scan position from a descriptor, and allow
noninjective result projection. Scan-normalization mutants eagerly integrate a
whole physical chunk, put a physical buffer/end position into checkpoint
authority, resume a dematerialized frame at fetched-end instead of the last
logically released value, or let cache width alter release order. A required
grounding mutant omits grant
resource-type equality on an arrow; a reverse mutant admits a cross-type
predecessor goal; another uses forward rather than transposed edges for reverse
lookup. Response-store mutants accept zero weight,
reserve after I/O, free at completion, double-charge a cache-hit pin, evict a
pinned chunk, or release pins at rotation. Every mutant must be detected by a
focused test. Representation mutants reuse a builder after `persistent!`,
publish a transient into a checkpoint, give one live builder to two cursor
branches, mutate on a physical callback, or hand a builder to another reducer
thread without freezing. Cache-boundary mutants treat projection values as an
unordered set, substitute a complete fresh subtree denotation into a request
with overlapping admitted work, route a subproblem result bag through the
projection namespace, or bypass request-local admission on a projection hit.
Logical-limit mutants check after inserting fresh identities, overflow host
addition before comparison, or publish the rejected half-transition as a
checkpoint. Cursor mutants omit external edge-identity validation, bind
page-navigation mode into the token, resume a history-free backward request at
its exclusive end, omit the validation lookahead, reverse the returned
backward page, or allow bare `last` to return an unproved suffix.

Existing candidate evidence—14/247 stable-discovery tests, 28/11,361
executable-model tests, 29/195 DataScript enumeration tests, 4/26 Datahike
enumeration tests, and 27/264 Datomic indexed tests passing—is useful
characterization, not acceptance of the replacement source bridge.

## Assurance statement

The final claim is conditional and exact:

> If the sealed plan passes structural validation, the verified rank checker,
> the independent source-refinement bridge, backend scan qualification, and the
> versioned formal gate, then the production reducer is authorized to interpret
> that plan as the modeled finite positive program at the selected immutable
> basis.

Anything less remains `unknown`; it does not inherit `proved-model` status from
the abstract grounding theorem.
