## Context

The survey behind this change read every production namespace line by line, partitioned into nine areas (operator, engine, kernel boundary and dispatch, caches/cursor/relay, client orchestration, Datomic+Datahike, DataScript+Datalevin, cold shared namespaces, formal runtime and gates), and classified each finding by frequency class: per-tuple, per-transition, per-candidate, per-probe, per-page, per-request, per-seal, load-time, cold. Only findings verified by reading the surrounding code and grepping callers were accepted. The planning documents of the prior pass were deliberately not trusted as evidence: three of its tasks (5.5 sidecar rebuild, 5.8 shadowing, 4.12 probe context) were marked done with the code unchanged, and one (5.7 option-key unification) regressed the count routes.

Facts established by direct survey:

- The engine's production kernel crossings are all per page or per request (`:relationship-page`, `:relationship-keyset-page`, `:cursor-continuation`; consistency decisions run the host-native portable kernel on both platforms). No formal-model-derived check runs per tuple in production Clojure; the per-candidate probes in `stable_route` and `least_path` are the verified algorithm itself.
- Compiled Dafny that executes at request time is exactly the `PageWindow` module; `FilteredPagination.dfy` and `ExecutionContract.dfy` are proof-only. The 98 % of per-push solver time spent on the retired `Indexed*` models is a CI cadence question, not a runtime one.
- Hot-path costs were dominated by bookkeeping the surrounding code never read: bit masks consumed only by an optional stats var, counter aggregates consumed only by a throw's `ex-data`, a checkpoint digest the engine discards, and re-validation of values the caller had just constructed.

## Goals / Non-Goals

**Goals:**
- Strictly less logical work per request on every touched path, with results byte-identical on every fixture the battery, replay corpus and differential suites cover.
- Every gate either able to fail for the reason it claims or scheduled honestly.
- Zero observable behavior change on valid inputs; error-surface changes confined to typed-error additions on internal paths and the restored count-route cut point.

**Non-Goals:**
- No algorithm changes (the least-path witness order, the bidirectional arm rounds, the recursive demand discovery, the seekable kernels keep their verified structure).
- No public API, wire, cursor, persisted schema or adapter-contract changes; no removal of the dormant indexed authority or its gates.
- No deadline-sampling changes on the reducer and probe paths beyond removing checks with no work between them.

## Decisions

**D1 — Observation-only bookkeeping is derived on demand, never maintained on the production path.** The vector evaluator's masks are a pure projection of the memo row and are built only under `*vector-stats*`; the recursive checkpoint is built only when `:checkpoint?` is requested (the engine's routes read only decisions; the weight is still accounted and bounded so counters are unchanged); consumed-work diagnostics are thunks resolved on the throw path; the lookup batch-width vector exists only under `*lookup-stats*`; stats reporting allocates nothing when no observer is bound. Tests that observe the stats see identical values.

**D2 — Validation moves to the boundary that owns the invariant; error contracts stay.** Engine entry-point `:pre` forms and the `calc-permission-paths` asserts duplicated typed checks made by the client boundary or by plan sealing and are removed (no test pinned the `AssertionError`). Validation that a test pins at a public entry (`check-many-eids` candidate shapes, `dispatch` probes, `direct-match-many?` requests, the FFI `validate-input!`/`validate-result!`) stays but is made allocation-free. The relationships page rejection gains the typed `:eacl.pagination/invalid-page-request` key its facade twin already carried.

**D3 — The generated kernel is the single page-normalization authority.** The host copy of `NormalizePageRequest` existed to reject values that cannot cross the portable boundary. Those values now map to the sentinel the kernel rejects for the same reason (an integer above the portable range is oversized, anything else is non-positive) while the error report still cites the raw query value, so messages, error keywords and `ex-data` are unchanged and one implementation remains.

**D4 — Repaired regression through one shared list.** The count routes lost `:cut-point!` because four hand-maintained option-key vectors drifted. One `^:no-doc` reducer superset now serves page, count, probe and least-path routes (`select-keys` of an absent key is a no-op), and a regression test cancels mid-count in both directions and was shown to fail under the old list.

**D5 — Adapter hot paths change only where the engine already guarantees the invariant.** Datalevin's eager scan drops only an exclusive boundary row because the native prefix seek starts at the inclusive bound and yields one datom per distinct value, and `guard-scan!` (runtime guards are on for Datalevin) still verifies uniqueness and order fail-closed; Datomic reads relation definitions from the composite tuple index because the shared planner applies the type filters itself; Datahike caches per-db identities because every adapter operation closes over one immutable db value.

**D6 — Request-counter fast path survives nested bindings.** The thread-local `[frame ledger]` cache is re-keyed to the current binding frame on a miss (only inside a ledger scope), so the per-tuple increments made under the execution-contract and observer bindings stay allocation-free; nested rebinding semantics are preserved because the dynamic var is always the fallback.

**D7 — Harness honesty over harness volume.** The never-run manifest gate is wired into the Dafny job with its documented exit 3 accepted; the dormant CLJS scaling gate joins the scheduled runs like its routing-certificate sibling; tool versions in the fast verifier follow the lock; stale proof counts are corrected to the locked run. Rewriting the sixteen answer-substitution mutation controls into real production mutants is recorded as follow-up work rather than attempted here.

## Risks / Trade-offs

- [Warm nREPL protocol/record staleness masquerades as failures] → every tranche's battery ran on a freshly started JVM; reload-only runs are used for iteration, never for certification.
- [Sentinel mapping changes page-error precedence for exotic sizes] → the kernel evaluates its rejection reasons in the same order the host copy did; the page-window bridge, the query matrix and the relay suites cover the shapes; replay corpus green.
- [Deadline observation granularity] → the only removed checks had no work between them (evaluator pre-probe, seekable double check, relay per-object identity checks bracket the batch instead); reducer and probe sampling is unchanged.
- [Removed vars used by an external consumer] → each removal was grep-verified across sources, tests, formal, build and docs; the module READMEs list them with replacements.

## Migration Plan

Internal refactor; no data or deployment migration. The change is a stacked branch on the previous kernel-authority change; the demo repository pins the resulting commit through its single `deps.edn` pin.

## Open Questions

None blocking. Follow-ups are listed in the proposal's Impact section and in `implementation-notes.md`.
