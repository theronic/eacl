## 1. Freeze Behaviour and Instrument the Boundary

- [x] 1.1 Extend the existing characterization suites (`v8_characterization_test`, shared `contract_support`) to pin every public read/write function and convenience arity, so the protocol replacement is checked against recorded behaviour.
- [x] 1.2 Inventory every in-repository implementation, extension, and `satisfies?` check of `IAuthorization`/`IDetailedAuthorization` (backend records, test doubles, `core_test`), plus the out-of-tree `eacl-spicedb` record and its clean-consumer gate, and record the list in the change directory.
- [x] 1.3 Inventory the Datomic client's behaviour that the shared pipeline must absorb: CAS retry loop and contention classification, batched `delete-object!`, schema fence and relation stamps, `write-schema!` metadata, token `revision == locator` invariant, `ordinary-view?`, cursor codec, page cache, result-context capture, `expire-cache!`/`cache-stats`.
- [x] 1.4 Classify every key of the orchestration option map and of the Datomic `opts` map as runtime, basis, source, writer, or request-scoped state.
- [x] 1.5 Rename the provider op-stats to source op-stats and make the shared test support count source acquisitions, adapter reads, writer submissions, and releases independently (the counters already exist; this consolidates them).

## 2. Public Capabilities, Values, and Errors

- [x] 2.1 Define `IAuthorizationReader`, `IAuthorizationWriter`, `ISnapshotSource`, and `IAuthorizationSnapshot` in `eacl.core`; delete `IAuthorization` and `IDetailedAuthorization`.
- [x] 2.2 Rewrite every public read function to normalize arities into one request map and dispatch to the reader method; make `check-permission` canonical and `can?` its `:allowed?` projection; throw `:eacl/invalid-authorization-target` for non-targets.
- [x] 2.3 Rewrite every public mutation function to normalize into the three canonical writer operations and throw `:eacl/unsupported-capability` `{:capability :write}` for targets without a writer.
- [x] 2.4 Add `eacl/snapshot` (1- and 2-arity), `eacl/with-snapshot` (CLJC macro), `eacl/release!`, `eacl/released?`, `eacl/basis`, `eacl/basis-token`, `eacl/snapshot?`, `eacl/acl?`.
- [x] 2.5 Implement the error taxonomy: `:eacl/unsupported-capability` (capability + target kind), `:eacl/unsupported-database-value` (basis kind), `:eacl/snapshot-released`, `:eacl.consistency/selection-required`, `:eacl.consistency/freshness-unavailable` `:snapshot-behind`, `:eacl.consistency/basis-conflict` (`:source :token|:cursor`); every error carries equal `:type` and `:eacl/error`.
- [x] 2.6 Add API tests proving each typed failure occurs before cache access, source acquisition, evaluation, planning, or submission, on CLJ and CLJS.

## 3. Runtime, Basis, Snapshot, and Acl

- [x] 3.1 Extract `Runtime`: identity converters, codecs and keyring, limits, timeouts, clock, instrumentation, lifecycle state, and every registry (exact-basis generations, managed tier, plan cache, derived-schema registry, continuation and visited-page stores); consume it through the shared `eacl.request.context/make-context` seam.
- [x] 3.2 Define `Basis` as the existing selected-snapshot value extended with basis kind and the runtime lifecycle merged into its identity, explicitly excluding `:schema-identity`; keep ownership, release state, owner thread, and idempotent release.
- [x] 3.3 Implement `Snapshot` (runtime × basis) implementing `IAuthorizationReader` and `IAuthorizationSnapshot`; add the structural test that no field or reachable runtime value holds a connection, source, writer, or selection callback.
- [x] 3.4 Implement `Acl` (runtime × source × optional writer) implementing `IAuthorizationReader` (select-then-delegate), `ISnapshotSource`, and `IAuthorizationWriter`; use `acl` naming in new public names, locals, and docs; accept `:read-only? true`.
- [x] 3.5 Implement owned/borrowed lifecycle semantics on `Snapshot`: at-most-once native release, borrowed non-closure, use-after-release rejection, thread-affinity enforcement from the source's execution constraints.

## 4. Backend Roles

- [x] 4.1 Reduce `eacl.backend.v8` to the basis-adapter role: remove `:select-current/-authoritative/-at-least/-exact` and `:source-lifecycle`; add `:basis-kind` with the `:complete-identity` obligation; adapters are constructed from a value and conversion config only and never receive a connection.
- [x] 4.2 Promote `eacl.backend.snapshot-provider` to `eacl.backend.source`: static profile from configuration (no acquisition at construction), acquisitions return a native value plus ownership and release token, exact acquisition by locator never acquires current first.
- [x] 4.3 Add `eacl.backend.writer`: `transact!`, `write-schema!`, planning helpers on a value, retraction count, contention classification, declared `:max-attempts` and `:max-transaction-size`.
- [x] 4.4 Add construction-time certification for each role so a missing declared operation fails at `make-client` with a typed error naming role and operation.
- [x] 4.5 Make the default source lifecycle the constant `"eacl/initial"` for Datomic, Datahike, and DataScript; keep Datalevin's required explicitly supplied persisted lifecycle; keep `expire-cache!` rotation (fresh UUID or supplied value) clearing every runtime registry; update tests that relied on distinct random lifecycles to pass explicit ones.
- [x] 4.6 Update source and adapter contract tests; verify no supported call can reach `AbstractMethodError` or an untyped missing-operation failure.

## 5. One Read Pipeline and Consistency

- [x] 5.1 Route every `Acl` read through exactly one source acquisition, a transient `Snapshot`, `eacl.request.context/make-context`, and release in `finally`; route every `Snapshot` read through released-check, assertion validation, the same context constructor, and execution with no acquisition.
- [x] 5.2 Change engine, rewrite, recursion, schema, proof, lookup/count, expansion, and relationship-page entry points to take a snapshot context; remove every parameter or option through which a connection or source was reachable (including `:conn` in option maps).
- [x] 5.3 Implement snapshot consistency assertions: omitted/minimize-latency evaluate; at-least satisfied evaluates; exact match evaluates; `:snapshot-behind`, `:basis-conflict`, and `:selection-required` otherwise, all before cache access.
- [x] 5.4 Delete the raw-db entry points that take a client option map (`datahike-*`, `datascript-*`, `datalevin-*` read functions); keep `eacl.datomic.impl` and `eacl.engine.v8` as the engine facade with request-scoped isolation.
- [x] 5.5 Preserve deadline, cancellation, dispatch budget, recursion limits, and response-token behaviour through the snapshot path; add instrumented tests: zero acquisitions at construction, one per `acl` read, one per capture, zero per snapshot read, zero for direct construction.
- [x] 5.6 Add concurrency tests proving a retained snapshot keeps its basis while the source advances during nested evaluation and between pages.

## 6. Basis-First Cache Model

- [x] 6.1 Replace the exact-current generation and snapshot-exact tier with one exact-basis tier keyed by complete basis identity (including basis kind) and a bounded LRU of basis generations (`:retained-bases`, default 4), each owning its exact answers and subproblem/projection store.
- [x] 6.2 Implement the managed lifting rule: ordinary class, same scope and lifecycle, and equal complete schema generation and scalar frontier; do not compare candidate and selected revisions; historical class is exact-only; apply identically to all target kinds.
- [x] 6.3 Make the sealed-plan cache and derived-schema registry runtime-owned and keyed by `[engine ABI backend source scope lifecycle certified schema generation]`; use request-local state only when generation is uncertified; delete the process-global plan cache.
- [x] 6.4 Bind proof frames, dependency metadata, response tokens, and derived schema state to the snapshot's basis; keep continuation and visited-page stores keyed by exact basis and query scope.
- [x] 6.5 Add adversarial tests: two snapshots sharing one runtime, as-of versus ordinary at the same revision, evicted generation recomputation, stale proofs, late publication from an old basis, lifecycle rotation with a retained snapshot.

## 7. Cursors and Continuation

- [x] 7.1 Dispatch cursor consumption by target: same snapshot continues directly; `acl` uses the existing continuation decision with exact reconstruction through the source; a different snapshot continues only under the lifting rule and otherwise throws `:eacl.consistency/basis-conflict` `{:source :cursor}`.
- [x] 7.2 Remove every hidden source acquisition from continuation and recovery paths reachable from a snapshot; add tests for cursor replay on another snapshot, on the `acl`, and after source advance.

## 8. One Write Pipeline

- [x] 8.1 Implement the shared write pipeline: acquire one current planning basis, plan, submit through the writer, derive the token from the committed value, release; re-plan on writer-classified contention up to `:max-attempts`; batch object deletion to `:max-transaction-size`.
- [x] 8.2 Add mutation conformance: writable `acl` preserves transaction, token, contention, and batching behaviour; snapshots and read-only `acl` fail before planning.

## 9. Port Datahike, DataScript, and Datalevin

- [x] 9.1 Datahike: adapter with basis kind (ordinary, `AsOfDB` as `:as-of` identified by time-point revision and kind, other wrappers refused), source with current, conditional authoritative, at-least polling, and exact-by-commit with zero branch-head reads, writer; `snapshot`/`db` constructors; recursive Konserve store-id normalization so tiered configs produce portable identity (removing the demo's bridge).
- [x] 9.2 DataScript: adapter with basis kind (filtered values refused), current-only source, writer; `snapshot`/`db` constructors; CLJ and CLJS suites including `with-snapshot` and lifecycle.
- [x] 9.3 Datalevin: owned thread-affine source with an explicitly supplied persisted lifecycle and no universal default, adapter over read snapshots, writer; obtain basis revision bounds through the maintained fork's `read-snapshot-revision-info` without requesting full schema metadata; `snapshot` constructor over an open read snapshot (direct value construction refused as inadmissible), documented retention hazard and optional maximum retention.
- [x] 9.4 Extend each module's certification and contract suites with the full target-kind matrix, acquisition counts, lifecycle, thread affinity, and cross-process token round trips.

## 10. Converge Datomic

- [x] 10.1 Implement the Datomic basis adapter (basis kind from `ordinary-view?`), borrowed source (`d/db`, bounded `d/sync`, `d/as-of`, targeted catch-up, `validate-exact-token!`), and writer (CAS guards, schema fence, relation stamps, `:eacl.fn/assert-relation-unused`, contention classification, `:max-attempts 8`, `:max-transaction-size 1000`).
- [x] 10.2 Add `eacl.datomic.core/snapshot` and `db`; route `make-client` through the shared `Acl`.
- [x] 10.3 Delete `Spiceomic`, `execute-request`, the `eacl4_` page-token codec, the private page cache, result-context capture, and duplicated `expire-cache!`/`cache-stats`; normalize Datomic errors to dual `:type`/`:eacl/error`.
- [x] 10.4 Retarget the Datomic characterization, API-contract, consistency, exact-selection, cache, differential, and trusted-surface suites to the shared pipeline; keep op-count, plan-isolation, and differential suites on the engine facade and re-baseline envelopes only where the facade changed.

## 11. Cross-Backend and Remote Conformance

- [x] 11.1 Build the shared conformance matrix over writable `acl`, read-only `acl`, captured, selected, and direct snapshots for every public read.
- [x] 11.2 Add the snapshot conformance suite: acquisition counts, direct-snapshot zero reads, concurrent advance, lifecycle matrix, thread affinity, cross-process token round trip, cache-class sharing.
- [x] 11.3 Add a remote-style test adapter implementing only the reader and writer protocols to prove public functions need no local source, value, or snapshot capability and that refused extensions are typed.

## 12. Assurance Artifacts

- [x] 12.1 Split the adapter-obligation map by role and update its mapping to `formal/dafny/SnapshotOracle.dfy`; confirm the lifting rule matches `EqualScalarProofPreservesEveryDeterministicDenotation` and `EqualScalarProofAlsoPreservesAnOlderSelectedSnapshot` in `ScalarFrontierCoherence.dfy`, cite both from the assurance matrix, and remove any forward-only wording from `NativeGenerationCoherence.dfy` comments.
- [x] 12.2 Update `formal/verification/execution-contract.edn` (production map: `Acl`/`Snapshot` entry points, removal of `eacl.datomic.core/execute-request`), `assurance-matrix.edn`, `production-decision-inventory.md`, and `implementation-simplicity.edn` (added branches offset by removed mechanisms).
- [x] 12.3 Regenerate `formal/verification/public-source-closure.json` and run the source-closure, counterexample-replay, and stable-discovery gates.

## 13. Documentation and Downstream

- [x] 13.1 README: keep each quickstart at one `make-client`; add a "Snapshots" section (capture, select, direct, `with-snapshot`, release) and the reader-Peer session-basis pattern with tokens; document consistency assertions, read-only `acl`, basis kinds, the constant default lifecycle on Datomic/Datahike/DataScript, and Datalevin's required persisted lifecycle.
- [x] 13.2 Rewrite `docs/v8-backend-adapter-boundary.md` for the three roles, `docs/v8-consistency-cache-operations.md` for selection-versus-assertion, `docs/cache.md` for the exact-basis tier and retained bases, and `docs/release-notes-v8.0.md`.
- [x] 13.3 Record the recut plan for `eacl-spicedb` (reader/writer protocols, extension refusal) and the `eacl-datahike-demo` migration from its orchestration bridge to `snapshot`, each sequenced after the next development artifact.

## 14. Validation

- [x] 14.1 Run formatting, lint, API-surface, isolated-module, and namespace-cycle checks for every changed module.
- [x] 14.2 Run the shared, Datomic, Datahike, DataScript, and Datalevin suites through persistent nREPL sessions with `:reload`, then the DataScript ClojureScript build last.
- [x] 14.3 Run the acquisition-count benchmarks (construction, `acl` read, capture, snapshot read, direct snapshot, exact-by-locator on Datahike) and the pre-change latency baselines; record results for downstream serverless adoption.
- [x] 14.4 Publish or record the v8 development artifact coordinates required by `eacl-datahike-demo` and `eacl-spicedb` only after the full matrix passes.
