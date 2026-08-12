## Context

See `proposal.md` for motivation and `specs/safe-entity-retraction-function/spec.md` for the behavior contract. This change follows [issue #87](https://github.com/theronic/eacl/issues/87) and is intended to land in the v8 line represented by [PR #103](https://github.com/theronic/eacl/pull/103), while other stacked work may continue to change that branch.

EACL v8 stores one logical relationship as a forward tuple on the subject and a reverse tuple on the resource. The peer eid is a component of a heterogeneous tuple/vector value rather than the value of a ref attribute. Datomic, Datahike, and DataScript therefore remove the half on the target entity but cannot follow the embedded eid to the peer half during ordinary entity retraction.

The existing `delete-object!`/`tx-delete-object` paths remain important: they can repair a raw-eid ghost after the entity is gone, and the public helper batches high-degree cleanup. The new path instead optimizes for one atomic transaction while the entity and its own endpoint halves still exist.

The native transaction-function surfaces are not identical:

| Backend | Named installation | Direct function value | Deployment constraint |
| --- | --- | --- | --- |
| Datomic Pro/Peer module | Durable `:db/fn` database function | Not needed | Stored code must be self-contained on the transactor |
| DataScript CLJ/CLJS | `:db/fn` function value in the live database | `:db.fn/call` | Embedded runtime; standard serialization cannot be assumed to preserve functions |
| Datahike | Available only where the database accepts/stores `:db/fn` values | `:db.fn/call` | EACL's default `:schema-flexibility :write` rejects `:db/fn`; direct values also require an in-process writer |
| SpiceDB adapter | None | None | Use the portable deletion workflow/API |

This matrix is based on the checked-in dependency versions (`com.datomic/peer 1.0.7622`, DataScript 1.7.8, and Datahike 0.8.1759), not on assumed API parity.

## Goals / Non-Goals

**Goals:**

- Compute cleanup from transaction-start state so a relationship committed before deletion is included without a peer-side read/submit race.
- Keep discovery to two target-scoped endpoint-attribute reads and emit only peer retractions, affected-relation bookkeeping, mutation bookkeeping, and ordinary entity retraction.
- Preserve managed cache, cursor, and causal-consistency invariants in the deletion transaction itself.
- Give each backend the same public concepts—support discovery, optional preparation/installation, and safe-retraction tx-data construction—while accurately reporting backend-specific modes.
- Keep all backend dependencies in their existing module artifacts and retain CLJ/CLJS support where DataScript already provides it.

**Non-Goals:**

- Repairing peer-only ghosts after the target entity and its own endpoint halves are already gone.
- Making an atomic deletion unboundedly safe for arbitrarily high-degree entities; the existing batched helper remains the operational escape hatch.
- Treating raw relationship datoms added in the same transaction as a safe-retraction invocation as supported composition.
- Protecting against writers that bypass both the certified EACL mutation path and the backend's concurrency checks.
- Adding transaction-function support to SpiceDB or changing the endpoint-pair storage ABI.

## Decisions

### 1. Expose backend-specific APIs over one shared request/planning contract

Add backend namespaces dedicated to safe retraction, each exposing equivalent operations:

- a support descriptor with `:mode` (`:named`, `:direct`, or `:unsupported`) and a machine-readable reason/requirements;
- an explicit installer/preparer that ensures mutation-journal prerequisites and, for `:named`, installs `:eacl.fn/retractEntity`;
- a tx-data constructor that creates the backend-native invocation together with an EACL mutation envelope.

The portable module will contain only shared constants, endpoint decoding/planning helpers, mutation-envelope construction, and contract tests. It will not require Datomic, Datahike, or DataScript. This preserves the modular Clojars boundary currently being prepared on the working branch.

The low-level installed/direct function accepts the target entity reference plus the mutation envelope. The public tx-data constructor hides that envelope in normal use, so deletion remains one submitted transaction while time/randomness is produced outside the transaction function. The envelope carries a fresh mutation id, issued-at time, retention information, and authenticated canonical request data. Each transaction function validates the envelope before returning tx data.

Alternatives considered:

- A portable `eacl/delete-entity!` method would hide backend capability but would require a connection-level mutation API and would not compose naturally with application transaction data.
- Generating random ids or reading wall-clock time inside the function would violate transaction-function purity and make `with`/retry behavior non-deterministic.
- Omitting mutation metadata would leave managed relation proofs, graph heads, cursor freshness, or anchor retention stale even if the endpoint tuples were correct.

### 2. Plan from the target's own endpoint halves

After resolving an eid or lookup ref, the function performs one entity-scoped read for each canonical relationship attribute:

1. A forward half `[subject-type relation-eid resource-type resource-eid]` yields a retraction of the matching reverse half on `resource-eid`.
2. A reverse half `[resource-type relation-eid subject-type subject-eid]` yields a retraction of the matching forward half on `subject-eid`.
3. A self-relationship needs no peer operation because ordinary entity retraction removes both local halves.
4. Each decoded half contributes its relation eid to a distinct affected-relation set.
5. The backend's ordinary entity-retraction operation is emitted last in the returned vector for readability; transaction semantics remain declarative.

The planner rejects or safely ignores malformed endpoint values rather than synthesizing a retraction against an unvalidated peer. Integrity auditing remains responsible for pre-existing corrupt storage.

This is sufficient for healthy data because every relationship touching an existing entity has exactly one half on that entity. It avoids the current repair helper's schema-sized AVET probes, which are necessary only after the local half has disappeared.

Alternatives considered:

- Scanning peer indexes for tuples whose last component equals the eid would make cost depend on schema/database size and would run in the serialized transaction pipeline.
- Reusing `tx-delete-object-stream` directly in Datomic would require the EACL jar on the transactor classpath and retain repair-oriented global discovery that is unnecessary before deletion.
- Explicitly retracting local halves duplicates work already guaranteed by ordinary entity retraction and inflates high-degree transaction data.

### 3. Publish relation and mutation proofs in the same transaction

For every distinct affected relation, the expansion updates the proof fields used by that backend:

- Datomic advances `:eacl/relation-version` to the current transaction and writes the v3 relation mutation id.
- Datahike and DataScript write the v3 relation mutation id.

If the target resolves and at least one entity datom will be retracted, the expansion also creates the supplied mutation anchor, compare-and-swaps the graph head observed in transaction-start state, advances the graph order to the current transaction, and applies normal previous-anchor retention. If the target does not resolve, it returns no data and does not create a no-op mutation.

The Datomic relation-version write composes with the existing optimistic relationship CAS: a stale certified writer loses if deletion commits first; if the writer commits first, the transaction function sees and removes its tuple. Datahike and DataScript serialize the same race through their graph-head mutation CAS. Cache coordinators are neither acquired nor notified because all proofs are database-visible.

Only one safe-retraction invocation is supported per application transaction. Datomic transaction functions do not observe other functions' returned tx data, and multiple independently enveloped invocations would compete to advance the same graph head. Consumers deleting several entities atomically must use a future batch-aware function rather than concatenate singular invocations.

### 4. Keep the Datomic database function self-contained and versioned

The Datomic module defines a quoted `d/function` body that calls only Clojure core and `datomic.api`. It does not resolve EACL Vars on the transactor. A shared oracle test invokes the stored function value and compares its expansion/result with the portable planner to control duplication drift.

The installed entity uses `:eacl.fn/retractEntity` and a version/digest marker in `:db/doc`, avoiding a new mandatory schema attribute. Installation behavior is:

- absent ident: install the current definition;
- recognized current definition: succeed idempotently;
- recognized older EACL definition: upgrade only through the explicit installer;
- unrecognized existing ident: fail with an installation-conflict error rather than overwrite consumer code.

This follows Datomic's database-function deployment model and avoids requiring `DATOMIC_EXT_CLASSPATH` changes. Installation remains a privileged schema/deployment action and is documented as such.

### 5. Use native embedded functions for DataScript and conditional Datahike modes

DataScript installs an IFn under the same ident in the live database and uses the same IFn through direct invocation in tests. The support descriptor warns that consumers serializing a DataScript database must reinstall after restore unless their serializer explicitly supports function values. Both JVM and ClojureScript suites exercise installation and invocation.

Datahike support is capability-tested rather than inferred solely from a version:

- when a function value can be transactionally stored, read back, and invoked, report `:named` and install the ident;
- with the default strict EACL schema, report `:direct` and construct `[:db.fn/call ...]` using the same function body when the writer is in-process;
- with a writer boundary that cannot transport function values, report `:unsupported` and retain the portable `delete-object!` path.

The probe/install transaction is atomic, and failures are wrapped in an EACL structured error. EACL will not switch a Datahike database from `:write` to `:read` schema flexibility because that is a creation-time data-governance choice, not an acceptable side effect of this feature.

### 6. Verify cost structurally before using timing as evidence

Correctness suites cover resource, subject, both directions, multiple relations, self-relationships, lookup refs/eids, missing targets, inbound refs/components, cached answers, stale certified writers, and pre-existing ghosts. Cross-backend contract fixtures assert the same resulting endpoint set and proof advancement.

Performance verification has three layers:

1. instrument backend index access and assert exactly the target-scoped endpoint reads plus fixed bookkeeping reads;
2. assert emitted operation counts are linear in local degree plus affected relation count and independent of unrelated database size;
3. record warmed compilation/expansion and commit benchmarks for representative degrees, separating first-use compilation from steady-state cost.

CI gates use structural counts and scaling ratios rather than fragile absolute latency. Benchmark reports retain host/runtime metadata. The README warns that a database function runs in the serialized transaction pipeline; consumers with degrees too large for one acceptable transaction use batched `delete-object!` instead.

## Risks / Trade-offs

- **[High-degree targets can monopolize a serialized transactor or exceed transaction limits]** → Keep work degree-bounded, publish benchmark evidence, and document the existing batched helper as the large-object fallback.
- **[Datahike's named-function support varies by schema flexibility, store, and writer topology]** → Probe the actual connection, never mutate schema flexibility, provide the in-process direct mode, and return structured unsupported results elsewhere.
- **[DataScript function values may not survive database serialization]** → Treat installation as a live-database capability, test reinstall behavior, and document reinstall-after-restore.
- **[The self-contained Datomic body can drift from the shared planner]** → Keep the body minimal and require oracle/differential tests over generated and adversarial endpoint sets.
- **[A consumer manually adds a relationship involving the target in the same transaction]** → Mark this composition unsupported in docs and tests; transaction functions see transaction-start state, not sibling tx-data.
- **[A malformed mutation envelope could corrupt proof state]** → Generate envelopes through the public constructor and validate ids, timestamps, retention bounds, fingerprints, and canonical target data inside every backend function.
- **[An existing entity already owns the requested ident]** → Version/digest the installed definition and refuse to overwrite unrecognized data.
- **[Safe retraction is mistaken for post-hoc repair]** → Keep missing targets a bounded no-op and link directly to integrity audit/repair and raw-eid `delete-object!` documentation.

## Migration Plan

1. Before implementation edits, refresh the target v8/PR #103 context and reconcile only the relevant module/document files with concurrent branch work; do not disturb unrelated worktree changes.
2. Add the shared contract and backend implementations behind explicit APIs. Run the full CLJ suite, DataScript CLJS suite, backend differential tests, structural performance gates, and focused benchmarks.
3. Publish the feature in the existing backend artifacts with no default schema change. Existing applications require no data migration.
4. Opting in is: upgrade the relevant backend artifact, run its installer/preparer once after the ordinary EACL schema is available, then submit tx-data produced by the safe-retraction constructor.
5. Rollback is: stop constructing new safe-retraction invocations and return to `delete-object!` plus ordinary entity retraction. The installed definition is inert when unused and may be explicitly removed after callers are rolled back; no relationship data rewrite is required.
