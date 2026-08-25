## Why

EACL v8 rejects SpiceDB intersection (`&`) and exclusion (`-`), and the earlier operator design could reintroduce the remote-read amplification that the least-path engine removed: k-way opening and selectivity pilots may touch every operand before a bounded page returns. EACL needs exact set-algebra semantics and a proof-first execution design whose first-page work is driven by demanded candidates, not by complete operand cardinality.

## What Changes

- Accept nested union, intersection, exclusion, parentheses, named permissions, and the existing supported one-hop arrow form with one versioned syntax and denotation. Continue rejecting chained arrows, `.all()`, caveats, wildcards, subject relations, `nil`, and `self`.
- Persist a bounded canonical permission-expression value instead of flattening every permission into union rows. Compile signed dependencies, positive strongly connected components, and strict strata; reject every dependency cycle containing an exclusion-right edge before storing the schema.
- Replace eager operator lookup planning with a sealed witness-carrying candidate plan. Union preserves all child generators, intersection selects one proven child generator, and exclusion selects its left generator. Exact vector predicates filter only unresolved operands. Operator results use the sealed generator's least-derivation-path order; union-only plans keep their existing plan domain and ordering ABI.
- Specialize compatible direct ordered leaves with seekable leapfrog/galloping intersection and monotone anti-join. General compound operands use adaptive, demand-sized batches and density-aware physical leaf probes rather than unconditional 256-candidate batches or data-dependent planning pilots.
- Extend positive recursive evaluation with anchor-gated multi-premise join state. Evaluate exclusion only against completed lower strata; incomplete work, timeout, failure, or a candidate-window boundary never proves absence.
- Reuse candidate witnesses, request-local Boolean/vector masks, exact point decisions, completed answers, and eligible exact scan-response prefixes. Cache state may elide certified work but cannot select a different public generator, stopping boundary, error, or result order.
- Add an optional exact batched-membership backend capability with aligned-result and immutable-basis obligations. Datahike receives a density-aware endpoint-local implementation that chooses compact tuple-prefix merge or sparse exact/galloping seeks without selecting a new database basis per batch.
- Make formal completion a hard predecessor of production implementation: extend and prove the finite denotation, stratification, cover-generator exactness, witness refinement, vector predicate, direct-leaf specialization, recursive conjunction, absence, order, resume, cache, and bounded-work models before routing any public operation through operator code.
- Add independent finite-set and fixed-point oracles, generated CLJ/CLJS differentials, cross-backend suites, pinned `eacl-spicedb` black-box comparisons, mutation controls, and Datahike/MinIO cold/warm S3-GET gates. Exact counts are qualified separately from bounded pages.
- **BREAKING**: the unreleased v8 persisted permission representation and backend capability contract change in place. No legacy storage reader, migration, dual-write, old-reader rollout, cursor-envelope version bump, or binary-downgrade path is provided. Existing union-only source schemas and public execution behavior remain compatible through the unchanged union-only fast path after clean schema installation.

## Capabilities

### New Capabilities

- `permission-set-algebra`: Accepted syntax, precedence, denotation, positive recursion, strict stratified exclusion, deterministic operator ordering, and unsupported-language boundary.
- `witness-carrying-operator-plans`: Sealed candidate-cover, witness, exact-predicate, specialization, batching, and progress contracts for demand-driven operator execution.
- `remote-operator-io-efficiency`: Backend-neutral and Datahike/MinIO requirements that distinguish logical probes, index-node cache misses, S3 GETs, warm reuse, and exhaustive work.

### Modified Capabilities

- `schema-write-safety`: Validate, bound, stratify, atomically store, and reject corrupt or unexecutable expression schemas in the single unreleased v8 representation.
- `backend-unification`: Carry versioned expressions and capability-negotiated exact batched membership through immutable snapshot adapters.
- `modular-backend-workspace`: Define the optional batched membership operation and its certification boundary without leaking backend query languages into core.
- `permission-path-resolution`: Seal canonical expression DAGs, signed dependency closures, generator witnesses, routing certificates, and compatible union projections.
- `portable-v8-authorization-engine`: Evaluate operator checks, lookups, counts, filters, recursion, and failures identically across runtimes and built-in backends.
- `schema-aware-traversal-routing`: Route acyclic operator plans, positive recursive components, and lower exclusion strata through equivalent certified kernels.
- `permission-tree-expansion`: Preserve operator structure and exclusion operand direction under existing snapshot and size guarantees.
- `dependency-validated-authorization-cache`: Include every positive and negative branch in proof scopes even when witnesses or short-circuiting avoid runtime reads.
- `verified-subproblem-cache`: Reuse only completed Booleans, exact scan responses, and complete lower-stratum absence under compatible proofs.
- `demand-bounded-evaluation`: Require sealed cover generation, witness-aware filtering, adaptive batching, bounded progress, and no eager operand materialization or planning-only reads.
- `cursor-dependency-validity`: Bind operator expression, generator, witness, strategy, stratum, order ABI, and progress interpretation into resumable state.
- `formally-verified-authorization-engine`: Extend the executable denotation and proofs to multi-premise positive rules and strict stratified negation.
- `formal-implementation-conformance`: Prohibit production operator routing until the extended models, generated decisions, refinements, and mutation gates are complete.
- `verified-enumeration-performance`: Gate union-only regressions and operator work, allocation, latency, first-page, continuation, and exhaustive-count behavior separately.
- `recursion-performance-gates`: Bound anchor-gated join state, fact propagation, negative-stratum work, and checkpoint weight on adversarial recursive operators.
- `cross-backend-conformance`: Compare every public operator operation with independent oracles and the supported SpiceDB subset across backends and runtimes.

## Impact

Affected code includes the SpiceDB parser and schema model, all built-in schema stores, the v8 adapter SPI, sealed-plan compiler, least-path and recursive engines, scan/probe cache seams, cursor/checkpoint codecs, permission-tree rendering, counters and limits, Dafny/TLA+/generated CLJ/CLJS assurance boundary, module conformance suites, Datahike tuple-index kernels, the Datahike demo's MinIO benchmark harness, documentation, and the sibling `eacl-spicedb` differential repository. No new production dependency or global resource catalog is introduced.
