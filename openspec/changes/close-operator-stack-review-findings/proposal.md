## Why

The PR stack `#144 → #153` (`agent/design-operator-engine-performance`, head `7faac92`, rooted directly on `main` `9900b8a`; +77,809/−15,061) was adversarially reviewed on 2026-08-26 before merging into `main`. Every automated suite and gate in the tree was executed locally and is green, and the operator engine's core semantics held up under independent live probing. The review nevertheless found one **critical authorization defect** (a denied subject is granted access after an admissible-looking snapshot is captured), one **critical verification-infrastructure defect** (a CI gate that cannot fail), and a set of correctness, performance, and assurance-integrity gaps that should not enter `main` unrecorded.

This change records exactly what was tested, what was found, and what must be fixed — so the findings survive session compaction and can be closed in task order rather than re-derived.

## What Changes

- **BREAKING (fail-closed)** Public authorization and snapshot APIs accept only EACL clients and EACL-created snapshots. Prospective state is created explicitly through `eacl/with` or `eacl/with-schema`; it cannot read the exact-basis tier and cannot publish into persistent cache tiers.
- The v7→v8 permission migration verifies that a supplied replacement schema is semantically equivalent to the stored v7 permissions, instead of checking relation identities only.
- Batched direct membership holds its density-bounded realization ceiling on every admissible basis kind; wrapped Datahike snapshots select the exact-probe kernel.
- Registered mutation controls execute their production consumers, and the manifest counts only controls with executable kill evidence.
- Counterexample replay and mutation-control commands throw on test failures, with their failure paths covered by the gate self-test.
- Relationship-observation recording is opt-in and allocates no store or request-path key work by default.
- The formal ledger proves and differentially binds the demand-clamped batch schedule used by production, with the exported kernel included in the enforced digest closure.

## Capabilities

### Modified Capabilities

- `schema-write-safety`: the released-v7 permission upgrade must reject a replacement schema that is not semantically equivalent to the stored v7 permissions.
- `cross-backend-conformance`: batched direct membership must preserve its certified realization bound on every admissible basis kind, and conformance must cover the density-mode selection boundary.
- `formal-implementation-conformance`: registered mutation controls must execute production code under mutation and be counted only when their kill assertion is falsifiable; assurance gates must fail the build on failure.
- `implementation-simplicity-and-performance`: request-path telemetry that no production consumer reads must not be recorded unconditionally.

## Impact

- **Authorization correctness (critical):** public snapshot boundaries, explicit speculative provenance, speculative effect certification, and read-only managed-cache reuse.
- **Schema migration:** `eacl.datomic.schema/migrate-v7-permissions!` and the `v7_to_v8` qualification suite.
- **Physical read path:** `eacl.datahike.direct-membership` dense kernel and `eacl.datahike.db/eavt-tuple-prefix`'s non-direct-DB fallback.
- **Assurance:** `formal/mutations/registry.edn`, `eacl.formal.executed-mutation-controls`, `bin/formal counterexample-replay`, `.github/workflows/formal.yml`, `formal/verification/{manifest,operator-phase-a,operator-phase-b}.edn`, `formal/dafny/AdaptiveBatching.dfy`.
- **Request-path performance:** `eacl.metrics`, `eacl.engine.physical/realize-chunk`, `eacl.backend.direct-membership/direct-match-many?`.
- **Public API:** raw native-database snapshot construction is removed; `eacl/with`, `eacl/with-schema`, `eacl/tx-relationship`, and speculative diagnostics are added. Operator semantics and the ordinary cursor wire format are unchanged.
