## Why

EACL needs one stable Relationship storage seam for future conditional behavior without giving up its endpoint-local, single-source traversal. The current four-component v7 endpoint tuples have no place to identify sparse qualifier data, and adding a second authoritative Relationship store would impose dual reads, merged pagination, and two-source cache proofs on every permission path.

This change establishes a five-component v9 Relationship storage ABI now, while every qualifier reference is still `nil`. It also replaces implicit compatibility with an explicit, restartable v7-to-v9 storage migration so no EACL v8 client can silently authorize against the wrong physical representation.

This four-change series supersedes the combined `2026-09-04-add-v9-caveats-and-expiring-relationships` proposal. The combined proposal and these phased changes MUST NOT both be applied.

## What Changes

- **BREAKING:** replace the populated v7 Relationship endpoint attributes with v9 attributes whose values are forward `[subject-type relation-eid resource-type resource-eid qualifier-eid]` and reverse `[resource-type relation-eid subject-type subject-eid qualifier-eid]`.
- Keep `qualifier-eid` last so the existing typed Relation prefix and opposite-endpoint ordering remain the leading index order.
- Preserve exactly two authoritative endpoint datoms per Relationship and one physical Relationship stream per traversal direction. No Relationship entities, supplemental Relationship tuples, or v7/v9 dual reads are introduced.
- Treat the endpoint owner plus the first four value components as logical identity. The qualifier reference is replaceable metadata and cannot create a second Relationship for the same subject/relation/resource.
- In this phase, all supported writers emit `nil` in slot five. Any non-`nil` qualifier encountered by an authorization or Relationship-read path fails closed as unsupported v9 data rather than being interpreted as unconditional.
- **BREAKING:** require Relationship storage version 9 at EACL v8 client startup. Legacy v6 Relationship entities, a populated v7 store, a mixed v7/v9 store, an incomplete migration, or an incompatible v9 shape prevents client construction with a typed error naming the required upgrade step.
- Add an explicit, side-effecting, backend-specific `v7-to-v9/migrate!` operation that installs v9 schema, validates pair integrity, converts Relationships in bounded batches, verifies the result, removes current v7 Relationship datoms, advances affected Relation versions, and stamps completion only after all checks pass.
- Make migration idempotent and resumable after interruption, but require a documented maintenance window with all old Relationship writers stopped. Client construction never auto-migrates.
- Invalidate old storage-dependent cache snapshots, continuations, and cursor ordering fingerprints through an ABI/version bump rather than retaining compatibility branches.
- Document backup, rehearsal, quiescence, migration, verification, restart, and restore-only rollback procedures.

## Capabilities

### New Capabilities

- `relationship-storage-upgrade`: explicit detection, quiesced v7-to-v9 conversion, restartable verification, startup fencing, operational documentation, and restore-only rollback.

### Modified Capabilities

- `converged-relationship-storage`: one five-component endpoint representation, qualifier-independent logical identity, single-source ordered access, atomic pair mutation, and cross-backend conformance.

## Impact

The change affects the shared endpoint-pair codec, all bundled backend schemas and ordered scan bounds, exact Relationship probes, write conflict/repair/delete planning, safe object deletion, integrity reports, Relation mutation stamps, cache and cursor ABI fingerprints, migration tooling, tests, and upgrade documentation.

The EACL library/permission line remains v8 while the independent Relationship storage ABI becomes version 9. The migration is therefore named v7-to-v9 and writes `:eacl/storage-version 9`; this proposal deliberately does not introduce an ambiguous storage-version 8 under v9 attribute names.

No Caveat definition, qualifier entity, expiry semantics, qualifier lookup, request context, or traversal-engine conditional behavior is implemented by this phase. Performance qualification must show that five-slot `nil` tuples retain one-seek traversal and meet explicit direct-check, page, arrow, count, storage-density, and migration-throughput budgets before release.
