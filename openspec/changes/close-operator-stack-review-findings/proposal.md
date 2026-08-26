## Why

The PR stack `#144 → #153` (`agent/design-operator-engine-performance`, head `7faac92`, rooted directly on `main` `9900b8a`; +77,809/−15,061) was adversarially reviewed on 2026-08-26 before merging into `main`. Every automated suite and gate in the tree was executed locally and is green, and the operator engine's core semantics held up under independent live probing. The review nevertheless found one **critical authorization defect** (a denied subject is granted access after an admissible-looking snapshot is captured), one **critical verification-infrastructure defect** (a CI gate that cannot fail), and a set of correctness, performance, and assurance-integrity gaps that should not enter `main` unrecorded.

This change records exactly what was tested, what was found, and what must be fixed — so the findings survive session compaction and can be closed in task order rather than re-derived.

## What Changes

- **BREAKING (fail-closed)** Public snapshot constructors reject database values that cannot be proven committed. Speculative values (Datomic `d/with`, Datahike/DataScript `db-with`) are currently classified `:ordinary` and admitted; a speculative snapshot then publishes into the shared exact-basis cache under an identity that collides with a real commit, so a later check at head returns the speculative answer. Confirmed end to end: with the speculative capture the live client answers `true` for a banned subject; without it the same fixture answers `false`.
- The v7→v8 permission migration verifies that a supplied replacement schema is semantically equivalent to the stored v7 permissions, instead of checking relation identities only.
- Batched direct membership holds its density-bounded realization ceiling on **every** admissible basis kind. On Datahike as-of snapshots the dense kernel currently loses its lower bound and realizes and sorts the entire endpoint prefix per batch.
- Registered mutation controls execute the production code they claim to mutate, and the manifest counts only controls whose kill assertion can actually fail. Four of the ten new operator controls are constant-function mutants whose kill compares two literals; the D13-required `active-recursion-as-false` control is absent and the production guard it would cover is untested.
- The counterexample-replay CI step fails the build when the replayed suite fails. It currently reports success regardless of failures, because the replay command returns a test summary rather than throwing.
- Non-authoritative relationship observation recording becomes opt-in (or lazily constructed) until a consumer exists; it is presently always-on, written per realized scan chunk, keyed by a basis watermark that changes on every write, and read by nothing in production.
- The formal ledger stops overstating what is bound to production: the proven batch-growth rule is replaced by (or bound differentially to) the demand-clamped rule the engine actually runs, and `EaclKernel.dfy` is pinned by the enforced digest closure.

## Capabilities

### New Capabilities

- `committed-basis-admission`: which application-supplied database values a public snapshot constructor may accept, and what a backend basis classifier must be able to prove before a value participates in shared cache tiers.

### Modified Capabilities

- `schema-write-safety`: the released-v7 permission upgrade must reject a replacement schema that is not semantically equivalent to the stored v7 permissions.
- `cross-backend-conformance`: batched direct membership must preserve its certified realization bound on every admissible basis kind, and conformance must cover the density-mode selection boundary.
- `formal-implementation-conformance`: registered mutation controls must execute production code under mutation and be counted only when their kill assertion is falsifiable; assurance gates must fail the build on failure.
- `implementation-simplicity-and-performance`: request-path telemetry that no production consumer reads must not be recorded unconditionally.

## Impact

- **Authorization correctness (critical):** `eacl.client.orchestration/direct-snapshot`; `basis-kind` classifiers in `eacl.datomic.backend`, `eacl.datahike.backend`, `eacl.datascript.backend`; the exact-basis and managed cache tiers keyed by `{source-scope, revision}`.
- **Schema migration:** `eacl.datomic.schema/migrate-v7-permissions!` and the `v7_to_v8` qualification suite.
- **Physical read path:** `eacl.datahike.direct-membership` dense kernel and `eacl.datahike.db/eavt-tuple-prefix`'s non-direct-DB fallback.
- **Assurance:** `formal/mutations/registry.edn`, `eacl.formal.executed-mutation-controls`, `bin/formal counterexample-replay`, `.github/workflows/formal.yml`, `formal/verification/{manifest,operator-phase-a,operator-phase-b}.edn`, `formal/dafny/AdaptiveBatching.dfy`.
- **Request-path performance:** `eacl.metrics`, `eacl.engine.physical/realize-chunk`, `eacl.backend.direct-membership/direct-match-many?`.
- **No public API shape changes** beyond snapshot admission becoming stricter; operator semantics, order ABI, and cursor formats are unaffected.
