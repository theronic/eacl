# Tasks

## 1. Inventory and Boundary Audit

- [x] 1.1 Inventory every public authorization and snapshot entry point and verify with an API test that none accepts a caller native database value.
- [x] 1.2 Inventory backend raw-database snapshot constructors and verify the migration list names every public var that must be removed or made internal.
- [x] 1.3 Inventory every persistent cache lookup and publication site, including completed answers, managed proofs, subproblems, projections, schema plans, checkpoints, and cursors, and verify each site is listed in a speculative publication audit fixture.
- [x] 1.4 Inventory every answer-affecting proof dimension used by v8, including schema, relationships, identities, existence, ordering, semantic configuration, and cursors, and verify the speculative effect certificate has a fail-closed disposition for each.
- [x] 1.5 Inventory relationship and schema planning across all supported adapters and verify the design mapping identifies shared validation, stamps, guards, native-with operation, and transaction-report shape for each adapter.

## 2. Public Snapshot Boundary

- [x] 2.1 Remove public backend functions that wrap caller native database values and verify namespace API tests cannot resolve or invoke those constructors.
- [x] 2.2 Ensure `eacl/snapshot` selects only from an EACL snapshot source and verify raw `d/with`, `d/filter`, `d/as-of`, `d/since`, and `d/history` values all fail with a typed invalid-target error.
- [x] 2.3 Preserve immutable snapshot lifecycle and basis access for EACL-created ordinary and speculative snapshots and verify `with-snapshot`, `release!`, `basis`, and `basis-token` conformance tests pass.
- [x] 2.4 Ensure evaluation on an EACL snapshot never refreshes the client and verify a spy source observes no head selection during snapshot-target operations.
- [x] 2.5 Preserve client consistency selection and verify a remote-store fixture performs no branch-head read for repeated `minimize-latency` operations after one pin.
- [x] 2.6 Reject immutable snapshots as `write-relationships!` and `write-schema!` targets and verify both return the documented typed immutable-target or unsupported-capability error without mutation.

## 3. Speculative Snapshot and Native-With Contract

- [x] 3.1 Add an internal speculative snapshot representation containing the native value, committed root, cumulative effect certificate, diagnostics, and publication-disabled query policy, and verify construction invariants reject missing provenance fields.
- [x] 3.2 Add an adapter native-with operation that returns `db-before`, `db-after`, and actual emitted datoms, and verify adapter contract tests cover transaction-function-expanded datoms.
- [x] 3.3 Implement `(eacl/with acl tx-data)` and `(eacl/with snapshot tx-data)` and verify both return readable immutable speculative snapshots while leaving the committed source unchanged.
- [x] 3.4 Detect direct EACL schema-storage mutation in generic `eacl/with` and verify it fails with a typed error directing callers to `eacl/with-schema`.
- [x] 3.5 Extract stable semantic relationship effects from actual emitted datoms using before/after schema context and verify relation removal/recreation cannot evade detection through changed native eids.
- [x] 3.6 Classify identity, existence, ordering, and other proof-relevant effects or mark them `::unknown`, and verify unclassified application datoms force cache-free evaluation.
- [x] 3.7 Union parent and child effect certificates without subtraction and verify a change followed by an apparent restoration remains affected.
- [x] 3.8 Add public read-only `tx-relationship` planning on an EACL snapshot and verify its output includes paired relationship mutations, guards, and one relation-version stamp per distinct relation.

## 4. Speculative Cache Policy

- [x] 4.1 Thread a publication-disabled policy through every speculative operation and verify instrumentation observes zero writes to every persistent cache tier after representative checks, lookups, counts, relationship reads, and schema reads.
- [x] 4.2 Prevent speculative exact-tier lookup by native database identity, basis, or transaction instant and verify a same-`t`, same-`:db/txInstant`, different-content collision cannot hit either sibling's exact answer.
- [x] 4.3 Permit read-through only to authenticated committed managed entries validated at the committed root and verify an invalid lifecycle, causal anchor, semantic key, or proof is a miss.
- [x] 4.4 Require disjoint relationship and schema dependencies plus unchanged or disjoint remaining proof dimensions and verify a fully disjoint committed proof may be reused.
- [x] 4.5 Treat incomplete dependency or effect dimensions as unknown and verify unknown forces evaluation on the speculative database with no lookup hit or publication.
- [x] 4.6 Verify request-local evaluator memoization is discarded at operation completion and repeated speculative cache misses do not create a reusable speculative tier.
- [x] 4.7 Verify no code path defines, installs, promotes, or releases a speculative cache delta or `cache-with` child.

## 5. Shared Schema Planning and With-Schema

- [x] 5.1 Extract a pure schema replacement planner returning transaction data, semantic deltas, changed stable schema-component identities, affected relationships, removed relations, and no-op status, and verify unit tests cover each field.
- [x] 5.2 Route committed `write-schema!` through the pure planner plus existing generation and concurrency guards and verify committed schema safety and race suites remain green.
- [x] 5.3 Implement `(eacl/with-schema acl-or-snapshot schema options)` through the pure planner and native-with operation and verify parse, reference, expression-limit, and empty-schema errors match committed typed categories.
- [x] 5.4 Union schema and relationship effects from chained `with-schema` and `with` calls and verify both operation orders produce conservative cumulative certificates.
- [x] 5.5 Make `:orphan-policy :error` the default and verify a prospective relation removal with stored forward or reverse relationship data throws `:eacl.schema/relation-in-use`.
- [x] 5.6 Implement speculative-only `:orphan-policy :retain-inert` without relation-unused guards or tuple retractions and verify N retained relationships incur no N-tuple enumeration, count, or cleanup.
- [x] 5.7 Add bounded indexed existence diagnostics per removed relation through `eacl/speculative-diagnostics` and verify warnings identify relations with retained data without exact tuple counts.
- [x] 5.8 Ensure authorization and `read-relationships` plan only from prospective relation definitions and verify retained orphan tuples are semantically invisible while the definition is absent.
- [x] 5.9 Verify restoring an equivalent relation may reactivate retained tuples while cumulative cache effects remain conservative.
- [x] 5.10 Reject `:retain-inert` on committed `write-schema!` and verify no committed schema-orphan guard is weakened.

## 6. Collision and Cross-Backend Conformance

- [x] 6.1 Preserve the PR #154 poisoning regression and verify a speculative grant cannot authorize the live client after different content commits at the same native basis.
- [x] 6.2 Add an adversarial public-constructor test that forces same source identity, `t`, and `:db/txInstant` with different content and verify the raw value is not admitted.
- [x] 6.3 Verify a speculative relationship change blocks reuse for dependent permissions but permits a validated proof for a disjoint permission.
- [x] 6.4 Verify a speculative schema change blocks reuse for dependent schema components but permits a validated proof with disjoint schema and relationship dependencies.
- [x] 6.5 Verify transaction-function relationship cleanup appears in the effect certificate and invalidates every dependent candidate.
- [x] 6.6 Verify concurrent speculative siblings from one committed root never share computed answers and neither changes the client or the other sibling.
- [x] 6.7 Run the speculative contract suite on Datomic, Datahike, DataScript, and Datalevin and verify unsupported adapter capabilities fail closed rather than guessing effects.
- [x] 6.8 Instrument Datomic conformance tests and verify speculative and committed coherence performs no `d/log`, `d/tx-range`, log drain, or transaction-listener operation.

## 7. Documentation

- [x] 7.1 Update `docs/cache.md` with the same-basis collision, proof-only committed read-through, zero speculative publication, and unknown-effect fallback, and verify all documented APIs exist.
- [x] 7.2 Update consistency documentation with client pin versus immutable snapshot behavior and verify the remote-store examples do not imply a per-query head read.
- [x] 7.3 Update the root README with relationship, chained, schema, and `:retain-inert` prospective examples and verify snippets compile in documentation tests.
- [x] 7.4 Update affected backend READMEs to remove raw snapshot constructors and direct users to `eacl/with`, `eacl/with-schema`, and `tx-relationship`, and verify API reference links resolve.
- [x] 7.5 Document that internal raw-database injection forfeits coherence guarantees and verify no public example recommends raw `d/with` or `d/filter` wrapping.
- [x] 7.6 Record the deliberate rejection of `d/log`, `d/tx-range`, log draining, listeners, speculative cache deltas, and content inference, and verify the architecture documentation matches the design decisions.

## 8. Final Verification

- [x] 8.1 Run strict OpenSpec validation and verify `openspec validate isolate-public-snapshots-from-caller-db --strict` succeeds.
- [x] 8.2 Run focused public API, cache, schema safety, and speculative collision suites and verify all pass on supported backends.
- [x] 8.3 Run the repository's full verification command and verify no existing committed cache, consistency, pagination, or writer contract regresses.
