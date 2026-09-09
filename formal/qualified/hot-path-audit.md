# Qualified authorization hot-path review

Review scope: Phase 3 changes from the merged Caveat foundation through the
qualified release candidate. The source-closure gate independently checks the
public request dependency ledger; its generated report remains under `target/`.

| Path | Reviewed behavior | Executable check |
|---|---|---|
| Native Relationship scans | One endpoint stream, ordinary eid or compact `[eid qid]`; no second attribute merge | Shared edge contract and four native scan suites |
| Ordinary qualification | `qualification/qualify` returns directly for an ordinary eid, without realizing request memo or temporal state | Nil-eid instrumentation and ordinary benchmark read profiles |
| Qualified data | Selected-basis bounded entity reads; one request memo for qid, definition, and Relation; no serving ownership graph scan | Native qualification-data suites, data-read benchmark counters, missing/changed-content controls |
| Retained decoded data | One bounded store, exact basis or complete current content; no cached truth and no writer-certification assumption for arbitrary native writers | Qualifier-cache lifecycle and raw-writer traces |
| Caveat evaluation | Complete input uses the native CEL program once; incomplete input uses only the portable partial evaluator | `complete-engine-does-not-run-a-shadow-evaluator`, partial/expired compile suppression |
| Algebra | Definite Boolean operations avoid residual traversal; symbolic evidence retains bounded canonical algebra and fault propagation | Exhaustive operator bridges, compound-expression campaign, evidence wire differentials |
| Trusted time | One request sample, exclusive deadlines, sparse evidence ledger; no scheduler dependency | Clock, expiry, cache, and cursor conformance |
| Pagination | Existing traversal owns discovery, limits, frontier, and lookahead; certificate collection follows examined work | Stable-route and cursor bridges; no-fill-loop bounded-work tests |
| Result reuse | Exact qualified semantic scope and checked temporal interval; expired resident entries miss | Native cached/uncached traces and killed temporal mutants |
| Writes and deletion | Native publication guards both endpoint refs, owned qualifier cleanup, and Relation fences | Shared publication/batch/deletion contracts and stale-plan controls |

No Phase 3 runtime path invokes the independent formal models or compares an
authorization result with a shadow traversal. Existing legacy generated-kernel
namespaces remain outside the public serving dependency closure. Offline
integrity scans and migration audits remain explicit administrative operations.
Deletion uses bounded submitted transaction batches; it does not claim that
adapters which materialize deletion input now stream their planning input.

Profiling identified redundant canonical work in Boolean evidence serialization,
UTF-8 length measurement, host-context normalization, qualifier decoding, and
portable-plan compilation. The release candidate removes that work at the
validated data boundary. It retains strict wire decoding, aggregate input bounds,
complete content identity, and bounded program/data caches. Numerical acceptance
is recorded separately in the qualified authorization benchmark results.
