## 1. Freeze baselines and independent evidence

- [x] 1.1 Record the `core2` base commit, branch, locked Clojure/Datahike/formal tool versions, JVM/Node versions, and clean union-only test commands in a reproducible evidence manifest.
- [x] 1.2 Freeze union-only semantic vectors, least-path and recursive result orders, cursor payloads, deterministic work counters, allocation samples, and matched-host latency baselines before adding operator production code.
- [x] 1.3 Add a test-only finite typed set-algebra oracle for relation, permission, one-hop arrow, union, intersection, exclusion, point membership, both lookup directions, and counts without importing production planner/evaluator code.
- [x] 1.4 Add a test-only naive Kleene-per-stratum oracle for positive recursive conjunction and strict stratified exclusion, including deterministic signed-cycle diagnostics.
- [x] 1.5 Turn the REPL cover, adaptive-batch, binary and max-head k-way leapfrog, memoization, anchor-gated join, and Datahike dense/sparse experiments into reproducible exploration commands with fixed seeds and machine-readable summaries.
- [x] 1.6 Add minimized counterexample fixtures for active-recursion-as-false, missing intersection premises, partial right-side absence, swapped exclusion operands, cursor advancement through batch overread, and wrong typed-ID join identity.
- [x] 1.7 Extend `../eacl-spicedb` with raw public-API fixtures for precedence, nested intersection/exclusion, positive recursive conjunction, and the documented negative-recursion boundary; pin the exact image digest and ignore return order.

## 2. Mandatory abstract proof gate before production implementation

- [x] 2.1 Extend the executable finite typed semantics with expression nodes, n-ary positive bodies, per-stratum least-fixed-point evaluation, and completed lower-stratum negative premises in `PermissionSetAlgebra.dfy` and the kernel include graph.
- [x] 2.2 Model signed dependency graphs, Tarjan-equivalent SCC validity, strict stratum inequalities, deterministic diagnostics, and rejection of every negative-edge cycle in `SignedDependencyStratification.dfy`.
- [x] 2.3 Prove the recursive raw candidate-cover transform contains every acyclic expression result, every recursively exact child generator emits exactly its child denotation before issuing a parent witness, and the construction is identity-equivalent for union-only plans in `CandidateCover.dfy`.
- [x] 2.4 Prove generator witnesses sound, anchor/left evidence reusable within one derivation, and scalar exact predicates equivalent to expression denotation in `WitnessPredicate.dfy`.
- [x] 2.5 Prove aligned vector predicates equivalent to scalar evaluation for every candidate, including DAG sharing, typed identity, per-candidate short-circuit masks, regrouping/permutation, malformed responses, and atomic failure in `VectorPredicate.dfy`.
- [x] 2.6 Prove demand-sized exponential batch growth respects the physical cap, candidate window, sentinel, logical progress boundary, physical-overread accounting, cancellation, and all-or-error publication.
- [x] 2.7 Prove filtered cover least-path order, exact uniqueness, ascending/descending page composition, candidate-window continuation, and resume suffix equality in `OperatorLeastPath.dfy`.
- [x] 2.8 Prove anchor-preserving max-head k-way leapfrog/galloping intersection and monotone exclusion anti-join reproduce the generic cover/predicate sequence and logical work boundary in `SeekableSetKernels.dfy`; prove zero-demand stopping, exact demand-prefix output, dimensional anchor/operand/driver seek bounds, and reject repeated binary filtering as the n-ary implementation.
- [x] 2.9 Prove checked EID-span selection bounds dense prefix realization, sparse fallback remains exact, and sorted probe decisions permute back to input order in `DensityBoundedBatch.dfy`.
- [x] 2.10 Prove anchor-gated multi-premise state derives the same least fixed point for every arrival order, handles late anchors and duplicates, separates entity types, terminates, and retains parent state only for anchor facts in `AnchorGatedConjunction.dfy`.
- [x] 2.11 Prove strict lower-stratum exclusion consumes only completed exact absence and propagates timeout, cancellation, limit, active, and backend failures in `StratifiedExclusion.dfy`.
- [x] 2.12 Prove complete signed dependency invalidation, completed-negative eligibility, exact scan-response reuse, and cache-hit elision refine cache-disabled execution in `OperatorCacheRefinement.dfy`.
- [x] 2.13 Add temporal models and killed mutants for vector subgroup cancellation, atomic publication, cursor progress under physical overread, checkpoint/resume, cache lifecycle expiry, and no partial negative authorization.
- [x] 2.14 Export the smallest abstract operator decisions through generated Java and JavaScript; add CLJ/CLJS round-trip vectors, exact dimensional counters, and fixed-seed randomized differential replay against the independent oracles.
- [x] 2.15 Update formal manifests, assurance matrix, model inventory, trusted-boundary text, source-closure ledgers, locked reports, theorem/obligation pins, and mutation registrations; run all abstract formal gates from a clean generated target.
- [x] 2.16 Record project-operator authorization to proceed after confirming tasks 2.1–2.15 are green and no production parser, storage, sealer, evaluator, or routing namespace accepts operators yet; preserve the absence of independent review as an explicit assurance qualification.

## 3. Parse, validate, and encode permission expressions

- [x] 3.1 Change the grammar to union-before-intersection-before-exclusion precedence, left-fold repeated exclusion, and preserve explicit grouping in the source AST; retain every existing unsupported construct rejection.
- [x] 3.2 Add closed data constructors and a versioned canonical codec for relation, permission, one-hop arrow, union, intersection, and exclusion nodes with unknown-tag/version/field rejection.
- [x] 3.3 Recursively resolve relation types, named permissions, and every one-hop arrow partition across all source-relation subject types; report deterministic missing, ambiguous, and type-invalid references.
- [x] 3.4 Implement source byte/node/depth/direct-fan-in checks before allocation and normalized DAG node/child-slot/word/checkpoint-weight checks after canonicalization.
- [x] 3.5 Build signed expression dependencies, compute SCCs and strata, reject negative cycles with reproducible typed paths, and verify positive recursive components remain accepted.
- [x] 3.6 Add CLJ/CLJS parser, codec, canonicalization, corruption, exact-boundary, fuzz, and atomic-validation tests against the independent syntax/denotation oracle.
- [x] 3.7 Calibrate and check in per-permission and aggregate expression/normalized-state limit defaults from reproducible codec and allocation measurements; expose them as checked client defaults rather than durable schema policy.

## 4. Persist expressions and evolve backend capabilities

- [x] 4.1 Add authoritative expression identity and canonical versioned payload storage directly to the v8 Datomic, DataScript, Datahike, and Datalevin schemas; keep clean installation idempotent.
- [x] 4.2 Implement atomic expression replacement, schema deletion, snapshot reads, export/import, backup/restore, and failed-replacement atomicity tests on every built-in backend.
- [x] 4.3 Remove flat permission persistence for every permission, verify that schema writes emit only canonical expressions, and compile union-compatible expressions into the unchanged union-only sealed-plan domain.
- [x] 4.4 Read valid expression-only snapshots using the payload-carried format; fail closed on flat-only, mixed, corrupt, conflicting, duplicated, or unsupported-format storage.
- [x] 4.5 Add explicit storage tests proving ordinary v8 reads perform no implicit legacy synthesis, migration, dual write, old-reader rollout, or binary-downgrade preparation.
- [x] 4.6 Extend adapter construction with a required expression capability and optional bounded batched-direct-membership capability; validate capability/operation pairing and include it in operator compatibility.
- [x] 4.7 Define the immutable-basis, normalized-descriptor, distinct-typed-input, aligned-Boolean, maximum-width, cancellation, and atomic-failure contract for batched direct membership.
- [x] 4.8 Differentially certify scalar fallback and native batches in forward/reverse direction for present/missing values, identifier extrema, batch boundaries, concurrent head advancement, and malformed provider responses.

## 5. Implement locality-aware physical leaf kernels

- [x] 5.1 Add the backend-neutral batch dispatcher that removes proof-compatible cache hits, groups remaining candidates by descriptor, sorts/deduplicates for I/O, and scatters aligned results back to generator order.
- [x] 5.2 Implement Datahike dense prefix merge for checked `span <= 4 × candidate-count`, stopping at the last candidate and charging every realized tuple.
- [x] 5.3 Implement Datahike sparse exact/galloping membership without scanning the min-to-max range, selecting a fresh `d/db`, re-reading schema, or crossing descriptor endpoint/type/relation guards.
- [x] 5.4 Add forward/reverse Datahike differential tests over dense, sparse, empty, all-present, all-absent, mixed, overflow, numeric-extreme, cancelled, and temporal/unsupported wrapper cases.
- [x] 5.5 Implement or explicitly select certified scalar fallback for Datomic, DataScript, and Datalevin; add native batching only where it beats scalar work without widening demand.
- [x] 5.6 Add dimensional telemetry for scalar-equivalent predicates, physical subgroups, exact seeks, galloping reseeks, prefix values, cache hits, adapter commands, fetched values, and batch overread.
- [x] 5.7 Benchmark multiplier values around the proved bound, retain `4` only if Datahike memory/file/MinIO gates support it, and seal the accepted physical-policy identity into compatibility data.

## 6. Compile canonical operator plans

- [x] 6.1 Compile source trees to bounded canonical semantic DAGs with associative flattening, commutative sorting/deduplication, structural interning with equality checks, and ordered binary exclusion.
- [x] 6.2 Compute complete signed relation closures, positive SCCs, strata, child-consumer indexes, leaf descriptors, direct-order compatibility, and deterministic structural cost tuples.
- [x] 6.3 Compile the recursive raw-cover and exact-generator graph, ensure local predicates complete before child semantic witnesses are issued, and select one deterministic intersection anchor per acyclic node without semantic pilots, cache observations, or host-order dependencies.
- [x] 6.4 Compile bounded witness projection rules and exact scalar/vector predicate programs, including typed entity identity and leaf descriptor grouping metadata.
- [x] 6.5 Seal direct intersection/anti-join eligibility only when sequence, direction, bounds, types, and generic-cover order are compatible.
- [x] 6.6 Include runtime semantic-DAG identity, code-level expression/DAG formats, normalized DAG, signed certificate, strata, cover, anchors, witness/predicate versions, physical-policy version, capability identity, request execution limits, and order ABI in operator fingerprints; exclude client-local schema-admission profiles and all retired durable digest/policy fields.
- [x] 6.7 Preserve the existing union-only sealed-plan domain, fingerprint, least-path/recursive routing, cursor identity, counters, and cache keys without constructing operator state.
- [x] 6.8 Add cross-runtime and cross-backend plan/fingerprint differentials, commutative operand equivalence tests, ordered exclusion inequality tests, and missing/stale/malformed certificate rejection.

## 7. Implement acyclic checks, vector predicates, and bounded lookup

- [x] 7.1 Implement stack-safe point expression evaluation with sealed child order, completed-value short-circuiting, per-request memoization, exact exclusion, and fail-fast selected-branch errors.
- [x] 7.2 Implement bounded portable candidate vectors and known-true/known-false/unresolved/failed bit masks, with primitive CLJ specialization and 32-bit portable/CLJS representation.
- [x] 7.3 Apply generator witness masks before cache/backend work and evaluate each demanded DAG node at most once per candidate.
- [x] 7.4 Implement the demand-sized initial batch and deterministic doubling schedule up to 256 and the remaining candidate window; charge all physical work while advancing only logical progress.
- [x] 7.5 Implement recursively exact least-path generators and local cover filters for forward and reverse lookup, filters, bounded counts, exact counts, candidate-window continuation, deadlines, cancellation, and dimensional limits.
- [x] 7.6 Implement seekable direct-leaf intersection and monotone exclusion anti-join behind certified eligibility, inclusive reseek, and exact sequence/boundary differential tests.
- [x] 7.7 Extend the authenticated semantic scope of the current v8 cursor envelope with expression, signed certificate, cover, anchors, witness, physical-policy, order, snapshot/proof, direction, and logical coordinate identity; do not bump the envelope version or add old-version migration/rejection branches.
- [x] 7.8 Prove by executable differential that every resumed page sequence equals uninterrupted filtered cover order and that physical overread cannot skip the next page's candidates.
- [x] 7.9 Route every acyclic public operation through operator evaluation only behind a disabled test feature gate; retain union-only routing unchanged.

## 8. Implement recursive conjunction and stratified exclusion

- [x] 8.1 Extend the generated recursive command/state model with typed expression facts, positive consumer edges, deterministic intersection anchors, strata, and exact lower-stratum questions.
- [x] 8.2 Retain complete child fact sets and allocate parent join state only on anchor admission; initialize late anchors from existing facts and update only anchored entities on later non-anchor facts.
- [x] 8.3 Represent join slots as portable 32-bit word vectors plus satisfied counts, make duplicate facts idempotent, and admit each completed parent fact exactly once.
- [x] 8.4 Evaluate strict strata bottom-up within anchored demand and allow exclusion to consume only completed exact lower-stratum Boolean/denotation results.
- [x] 8.5 Extend deterministic recursive scheduling/order for operator roots while preserving byte-for-byte union-only recursive traces and checkpoint identities.
- [x] 8.6 Extend checkpoint/replay with facts, anchor states, satisfied counts, completed strata, pending negative questions, command identity, and undelivered boundaries without backend handles or mutable cache state.
- [x] 8.7 Add pre-allocation limits and dimensional counters for facts, anchor states, join words/slots, strata, commands, values, transitions, queue, checkpoint weight, deadline, and cancellation.
- [x] 8.8 Differentially test positive self/mutual conjunction, stars, chains, arrows, late anchors, duplicate derivations, typed-ID collisions, unseeded cycles, multiple strata, and failure injection against the naive oracle.

## 9. Integrate proof-compatible cache reuse

- [x] 9.1 Extend top-level, point-subproblem, and continuation keys with operator expression, signed certificate, cover/anchor, witness/predicate, physical-policy, capability, code-level compatibility formats, request execution limits, order, direction, and snapshot/proof inputs as applicable. Keep client-local schema-admission profiles out of semantic plan identity while including them in client-owned structural-validation cache keys.
- [x] 9.2 Add request-local complete Boolean/mask memoization keyed by expression node and complete typed context; keep in-progress, witness-only, join, anti-join, and unfinished-stratum state private.
- [x] 9.3 Publish individual completed point/vector decisions only after the whole demanded vector succeeds and under complete positive/negative dependency proofs.
- [x] 9.4 Integrate eligible exact scan-response prefixes at the existing fetch seam without widening descriptor bounds/limits or creating a second operator segment cache.
- [x] 9.5 Make proof invalidation cover every positive and negative relation even when runtime witnesses, short-circuiting, or cached branches avoided reads.
- [x] 9.6 Verify relevant/unrelated writes, selected basis, lifecycle expiry, eviction, concurrent identical/different requests, provider failure, and newly excluding relationships.
- [x] 9.7 Verify `:cache? false` performs no operator-related lookup, proof-lifting, coordination, admission, or publication while retaining the same plan, logical demand, value, error, order, and boundary.

## 10. Complete concrete formal refinements before routing

- [x] 10.1 Bind the production parser/codec/signed-graph decisions to the phase-A semantic inputs with generated vectors and source-closure digests.
- [x] 10.2 Bind plan canonicalization, cover/anchor selection, witness programs, fingerprints, and routing certificates to their abstract decisions in CLJ and CLJS.
- [x] 10.3 Bind scalar/vector evaluation, adaptive batching, direct specializations, progress/cursor state, and typed failures to the abstract complete-case partitions and dimensional traces.
- [x] 10.4 Bind anchor-gated recursive state, strata, checkpoint/replay, limits, and scheduling to generated authority and replay every phase-A counterexample.
- [x] 10.5 Certify each backend's expression reads, ordered scans, inclusive reseeks, scalar/batch membership, basis stability, cancellation, and error classification premises.
- [x] 10.6 Add and kill production mutations for wrong precedence, swapped exclusion, unsigned dependency, missing join slot, duplicate satisfaction count, partial negative, vector misalignment, overread cursor advance, any-child allocation, and cache-selected generator.
- [x] 10.7 Regenerate Java/JavaScript/browser artifacts and update size bounds, source closures, theorem pins, manifests, model inventory, trusted assumptions, and public decision inventory from a clean checkout.
- [x] 10.8 Complete the source-refinement and assurance audit; keep public operator routing and schema writes disabled until every section-10 gate is green, with any unperformed independent review recorded as an explicit assurance qualification.

## 11. Establish semantic and storage conformance

- [x] 11.1 Run the deterministic independent-oracle matrix for checks, detailed checks, filters, both lookup directions, bounded/exact counts, pages, recursion, limits, and typed failures on all built-in backends.
- [x] 11.2 Run fixed-seed randomized bounded schemas/graphs through CLJ and CLJS, minimize every mismatch, retain seeds, and verify scalar/vector/direct-specialization/cache-on/cache-off equivalence.
- [x] 11.3 Run the digest-pinned `eacl-spicedb` corpus and randomized shared-subset comparisons through public APIs; compare sets/cardinality, record intentional boundary cases, and never require SpiceDB return order.
- [x] 11.4 Verify permission-tree operator rendering, non-semantic union/intersection child order, directed exclusion children, snapshot selection, limits, cancellation, and no relationship enumeration during schema expansion.
- [x] 11.5 Exercise clean expression-only install, ordinary-read flat-only/mixed/conflicting storage rejection, union-only plan compatibility, expression replacement, failed-write atomicity, export/import, and backup/restore on every backend; verify no implicit migration or dual-write path exists.
- [x] 11.6 Re-run the complete pre-operator union-only corpus and require identical values, order, cursors, fingerprints where promised, deterministic counters, cache behavior, and recursive traces.

## 12. Gate CPU, allocation, recursion, and remote I/O performance

- [x] 12.1 Add strategy microbenchmarks for eager collection, linear merge, bad/good scalar driver, adaptive vector predicate, leapfrog/galloping, anti-join, dense prefix, and sparse exact probes across selectivity, skew, operands, page sizes, and cache states.
- [x] 12.2 Require union-only point/page/reverse/count/recursive deterministic work to remain unchanged and matched-host median latency/allocation to remain within five percent of the frozen baseline.
- [x] 12.3 Add adversarial operator gates for tiny result over huge operands, empty/late result, dense exclusion, duplicate-heavy union, deep supported arrows, batch word boundaries, and candidate-window continuation with bounded memory.
- [x] 12.4 Measure anchor versus any-child reference state on recursive stars/chains/mixed graphs; require exact denotation and parent-state cardinality no greater than typed anchor facts.
- [x] 12.5 Extend the Datahike demo's loopback MinIO harness to isolate basis/branch metadata GETs, Datahike `:reads`, index-node misses, physical keys/bytes, EACL candidates/probes/batches, cache occupancy, allocation, and latency.
- [x] 12.6 Measure Datahike cold first page, immediate warm repeat, adjacent pages, sparse/dense batches, high/low overlap, dense/sparse exclusion, arrows, cache eviction, candidate-window progress, and exact count with fixed seeds and store/node-cache configuration.
- [x] 12.7 Check in separate accepted numeric ceilings for cold bounded pages, warm index GETs, continuation, bounded progress, and exhaustive count; require zero immediate warm index GETs only when the measured working set remains resident.
- [x] 12.8 Run bounded pages/counts separately from full enumeration/exact count and block any report or release gate that blends their latency, GETs, or logical work.
- [x] 12.9 Recalibrate expression, vector, join-state, checkpoint, batch, candidate, and remote-read defaults from accepted evidence; version any changed policy and rerun formal/cursor/cache compatibility gates.

## 13. Release gating and documentation

- [x] 13.1 Add separate disabled gates for expression schema writes and public operator routing; verify union-only behavior while each gate combination is off.
- [x] 13.2 Recreate superseded experimental-v8 development databases with expression-capable adapters before enabling writes; prove their flat-only and mixed ordinary reads fail closed without compatibility interpretation.
- [x] 13.3 Run all module tests, CLJS builds, lint/source-closure checks, abstract and concrete formal gates, mutation controls, backend certifications, conformance lanes, storage tests, and performance gates from reproducible clean commands.
- [x] 13.4 Enable test operator routing, then operator schema writes, only after proof-phase-A authorization and recorded refinement, conformance, storage, and performance evidence are present and valid.
- [x] 13.5 Update README and API/backend/formal documentation only with implemented syntax, precedence, strict stratification, order, limits, cursor/cache behavior, measured performance envelopes, and the unchanged unsupported boundary.
- [x] 13.6 Document the experimental-v8 reset contract separately from the supported released-v7 permission-schema upgrade; no experimental older-binary, persisted-cursor, implicit-migration, or dual-write compatibility is claimed.
- [x] 13.7 Run `openspec validate --strict`, record final branch/base/diff and all evidence digests, and leave the change ready for review and merge into main without modifications to the original `core` worktree.

## 14. Remove durable derived metrics and qualify the released-v7 upgrade

- [x] 14.1 Remove source/DAG/count/byte/weight metric attributes from the shared expression entity and every built-in backend schema, pull, comparison, export/import, and transaction path; retain metric computation for validation and prove new schema writes assert no retired metric datoms.
- [x] 14.2 Make expression reads recompute canonical source metrics, normalized DAG metrics, encoded size, and aggregate dimensions from the bounded canonical payload; cache the completed structural result per client by schema generation, authoritative fields, and effective limits, with explicit exact eviction/recompute support.
- [x] 14.3 Add an adapter-neutral relationship-observation cache keyed by source lifecycle, selected basis/relation high-watermark, normalized descriptor, and direction, with an explicit monotone completeness class on each entry. Populate lower bounds, exact exhausted counts, selectivity, and physical-work observations only from work already demanded by authorization reads.
- [x] 14.4 Add explicit relationship-stat refresh modes: clear/observe and bounded refresh by default, plus caller-authorized exact exhaustive refresh charged to ordinary exact-count/work limits. Verify that ordinary misses and refresh defaults never widen scans or open otherwise-undemanded index streams.
- [x] 14.5 Add optional normalized adapter I/O telemetry. Integrate Datomic I/O stats only as cache/storage-tier cost evidence, retain EACL counters as the portable fallback, and prove neither telemetry availability nor observed values can alter the sealed public generator, order, cursor lineage, stopping boundary, or failure semantics.
- [x] 14.6 Keep cached observations outside semantic plan and generator selection. If an adapter consumes them for physical selection, permit only formally/source-digested sequence-equivalent kernels. Add cold/warm/stale/missing/high-watermark-change differentials showing identical values, traces at the semantic boundary, pages, and cursor composition.
- [x] 14.7 Implement a released-v7-to-v8 permission-schema upgrade that completely preflights parsing, resolution, bounds, stratification, replacement identity, semantic compatibility, and transaction writability before retiring any v7 permission row; atomically transact the permission replacement and schema/version stamp.
- [x] 14.8 Prove and test that released v7 relationship attributes and tuple datoms are reused without relationship scans, backfills, rewrites, or rebuilds. Compare relationship count and content digests before/after upgrade on pre-populated fixtures, including the 1M-resource Datomic development shape on a disposable database copy.
- [x] 14.9 Inject invalid source, corrupt legacy row, attribute conflict, CAS race, transaction rejection, and retry failures. Verify a rejected upgrade leaves the old permission rows and schema stamp usable and never exposes a mixed authorization snapshot.
- [x] 14.10 Update independent oracle, CLJ/CLJS, cross-backend, Datomic migration, Datahike/MinIO, export/import, backup/restore, formal cache-refinement, source-closure, and documentation evidence for the revised storage/cache/upgrade contract.
- [x] 14.11 Run all affected module tests and REPL probes, Datomic dev migration qualification, remote-read ceilings, formal/source-closure gates, and `openspec validate --strict`; record exact commands, cold/warm/stat-refresh results, relationship-digest evidence, and any remaining assurance qualification.

## 15. Minimize durable permission state and make admission limits client-local

- [x] 15.1 Remove separate expression-format, expression-digest, and expression-policy-digest fields from the canonical expression entity, built-in backend schemas, pulls, writers, comparisons, migration attribute installation, fixtures, and documentation. Treat already-installed experimental attributes as inert and never assert them again.
- [x] 15.2 Make the payload-carried format the only durable codec version and validate canonical payload identity against resource type, permission name, and canonical entity ID. Compute any cursor, plan, or cache fingerprint locally from authoritative content and code-level format versions.
- [x] 15.3 Add immutable per-client expression-limit configuration with calibrated defaults, exact key validation, and hard portable ceilings. Thread the effective profile through schema parsing, reading, normalization, aggregate validation, direct schema writes, and explicit/automatic v7-to-v8 migration.
- [x] 15.4 Scope structural caches to the client, schema generation, authoritative payload fields, and effective limit profile. Prove a cache entry admitted under a looser profile cannot bypass a stricter profile and that different profiles never change denotation for schemas both accept.
- [x] 15.5 Add cross-backend CLJ/CLJS storage and conformance tests for minimal durable fields, custom client profiles, exact boundaries, unsupported payload formats, experimental stale-attribute tolerance, v7 migration, startup gating, and failed-write atomicity.
- [x] 15.6 Refresh formal/source-closure and qualification evidence, rerun affected module/aggregate/CLJS/migration/performance/OpenSpec gates, and document which durable attributes remain and their coordination purpose.
