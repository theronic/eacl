## Context

See `proposal.md` for motivation. EACL currently stores each Relationship as a four-component forward value on the subject and a symmetric reverse value on the resource. The v8 engine resolves one Relation, seeks one endpoint attribute, obtains the opposite eid directly from the value, and uses per-Relation mutation versions for managed cache coherence.

The phase must create a permanent storage boundary for sparse future qualifiers without carrying v7 compatibility through every read. It must also correct the version terminology: v8 is the library/permission release line; v9 is the new Relationship storage ABI.

## Goals / Non-Goals

**Goals:** preserve exactly two endpoint-local Relationship datoms, one ordered stream per traversal direction, one logical Relationship per first-four identity, explicit storage compatibility, a complete restartable migration, and measurable hot-path neutrality when slot five is `nil`.

**Non-Goals:** Caveat definitions, qualifier entities, `valid-until`, conditional permissionship, qualifier caching, automatic startup migration, online mixed-version service, dual writes, dual reads, in-place rollback, or retaining old storage-dependent cursors and caches.

## Decisions

### 1. Use storage ABI 9 inside the EACL v8 release line

The target storage marker is:

```clojure
:eacl/storage-version 9
```

The attribute namespace is `eacl.v9.relationship`. Permission-storage versioning remains independent. Code, diagnostics, migration namespaces, documentation, cache ABI fields, and tests use the same terminology so operators never have to infer whether “v8 storage” means library version, permission representation, or Relationship layout.

### 2. Freeze one five-component endpoint representation

```clojure
;; Stored on subject-eid
[subject-type relation-eid resource-type resource-eid qualifier-eid]

;; Stored on resource-eid
[resource-type relation-eid subject-type subject-eid qualifier-eid]
```

Persisted attributes:

```clojure
:eacl.v9.relationship/subject-type+relation+resource-type+resource+qualifier
:eacl.v9.relationship/resource-type+relation+subject-type+subject+qualifier
```

Datomic Pro, Datahike, and Datalevin declare heterogeneous tuple types:

```clojure
[:db.type/keyword :db.type/ref :db.type/keyword :db.type/ref :db.type/ref]
```

DataScript stores the same fixed-length ordinary vector. Slot five is always present. In Phase 1 it is always `nil`.

Qualifier is last because the established scan prefixes remain:

```clojure
[subject-type relation-eid resource-type]
[resource-type relation-eid subject-type]
```

and opposite endpoints remain ordered by component four. A global query by qualifier is not an authorization access pattern and does not justify moving it ahead of the endpoint.

### 3. Keep logical identity independent of the qualifier

The tuple owner plus the first four components identifies one physical half; subject, Relation, and resource identify the logical Relationship. Slot five is not identity.

The shared codec exposes explicit identity-prefix and exact-value operations. `:create` checks for any value under the first-four identity, `:touch` replaces the exact old value if qualifiers later differ, and `:delete` accepts identity only. Phase 1 still sees only `nil`, but implementing prefix identity now prevents a later qualifier from accidentally becoming a duplicate Relationship.

A direct membership probe remains one index seek. It seeks from the full-arity lower bound ending in `nil`, validates the owner/attribute/first-four prefix, and reads at most the one allowed logical Relationship. Backend runtime guards and integrity tooling detect duplicate first-four identities; normal reads do not scan a second store.

### 4. Establish one storage source, not a compatibility engine

After client construction succeeds, all authorization, lookup, count, Relationship-read, deletion, proof, and integrity paths use only v9 attributes. Production source contains no “try v9, then v7” branch and no merged cursor frontier.

The startup compatibility gate runs before a client can publish or consume authorization cache data. It performs bounded metadata/schema checks and at most a bounded existence probe against each legacy Relationship attribute. It rejects:

- any current v6 Relationship entity or v7 Relationship datom;
- a mixed legacy/v9 current database;
- an in-progress or failed migration marker;
- a storage stamp other than 9 for an initialized store;
- missing or physically incompatible v9 attribute definitions.

Client construction does **not** re-enumerate the v9 graph, recompute pair parity, or rerun migration verification. The complete migration/fresh-bootstrap stamp is the serving boundary; ordinary integrity tooling and the existing supported-writer/content-proof contracts cover later corruption. A genuinely empty fresh database may install and stamp v9 during the normal explicit schema/bootstrap operation. An existing stamped v7 database must use migration even when its current Relationship count is zero, so operational intent is explicit and repeatable.

### 5. Use a quiesced, restartable migration state machine

Each backend supplies a public side-effecting entry point in its adapter module, for example:

```clojure
(eacl.datomic.migrations.v7-to-v9/migrate! conn options)
```

Equivalent namespaces exist for Datahike, DataScript, and Datalevin where that backend supports a mutable connection. Core owns the pure planning, tuple conversion, verification record shape, and error taxonomy; adapters own index scans, transactions, schema installation, and write-policy elevation.

The durable migration phases are:

```text
:preflight -> :converting -> :verifying -> :cleaning -> :complete
```

The first invocation:

1. verifies that the source is v7 or a recognized interrupted v7-to-v9 run; a v6 entity source is rejected with the exact existing v6-to-v7 prerequisite rather than guessed through;
2. validates both source attributes, Relation references, endpoint existence, forward/reverse symmetry, and first-four uniqueness;
3. computes and durably records the canonical source Relationship count and content digest used by final verification;
4. installs compatible target attributes and the migration marker;
5. records source/target versions and a migration run identity.

Conversion processes bounded canonical batches. For each healthy v7 pair, one transaction adds the exact v9 pair with `nil` qualifier, retracts the exact v7 pair, advances every affected Relation version once, and records progress. If a matching v9 pair already exists from an interrupted transaction boundary, the rerun validates it and only removes the surviving source pair. A conflicting v9 value, dangling half, missing Relation, or changed endpoint identity stops the migration with diagnostics; it is never guessed through.

Finalization performs a complete source-empty check, v9 pair/uniqueness verification, target count/digest comparison against the preflight source certificate, affected-Relation version verification, and migration-marker CAS. Only that transaction writes `:eacl/storage-version 9` and `:complete`. A crash before it leaves the store unbootable but safely resumable.

### 6. Require writer quiescence rather than simulate online compatibility

All v7-capable clients and out-of-band Relationship writers must be stopped before preflight and remain stopped until final verification. The migration acquires the strongest backend-local fence available and checks source mutation evidence between phases, but no library can make an unknown old process obey a new protocol.

This maintenance requirement is security-relevant: an old-format write after its range was converted could otherwise be omitted from v9; an omitted subtracting or deny Relationship may broaden permission. Detection or a changed source proof aborts rather than completing with a potentially partial graph.

No `:auto-migrate` client option is supplied. Startup is a read/check boundary, not an unannounced large transaction job.

### 7. Preserve history but remove current v7 data

On history-preserving backends, old v7 assertions and their retractions remain available through native history. Current authorization does not read them. Datomic cannot uninstall old attribute definitions; those definitions may remain inert while current v7 datom count is zero.

Migration does not promise that an old exact cursor can be replayed by new code. Storage, adapter, engine, proof, cache, and ordering ABIs advance so old artifacts are rejected or treated as cache misses. Operators recreate clients and restart pagination after cutover.

### 8. Make Phase 1 qualifier behavior explicit and fail closed

Supported Phase 1 writers construct only values with `nil` slot five. Decoders accept the fixed shape but return a typed unsupported-qualifier result for non-`nil`. Authorization and public Relationship reads do not silently strip the component or treat it as unconditional.

This one branch is the only future-feature branch in Phase 1. There is no qualifier entity read, no Caveat dependency, no clock access, and no new traversal state.

### 9. Qualify performance at public operation boundaries

Benchmarks compare the released four-slot baseline with five-slot `nil` v9 storage for:

- positive and negative direct checks;
- forward and reverse adjacency;
- one-hop and recursive arrows;
- first page, continuation page, and exhaustive count;
- cold and warm Datomic/Datahike/Datalevin stores;
- DataScript JVM and CLJS ordering/allocation;
- tuple segment/node density and durable bytes;
- migration throughput, peak mixed storage, transaction size, and restart cost.

The release gate uses measured numerical budgets recorded with the implementation. It does not add runtime shadow reads, model evaluation, duplicate full-graph checks, or proof recomputation to ordinary requests.

## Risks / Trade-offs

- **Wider values can reduce index density** → measure actual segment/node density and durable bytes; do not infer neutrality from unchanged datom count.
- **Prefix direct-match replaces exact four-value equality** → benchmark one-seek probes and specialize the backend primitive without changing semantics if necessary.
- **Maintenance migration causes downtime** → provide rehearsal, progress, bounded batches, and restore procedures rather than permanent compatibility complexity.
- **Unknown writers can defeat quiescence** → abort on mutation evidence and document supported-writer requirements; never complete optimistically.
- **Interrupted migration leaves mixed current data** → mixed data is intentionally unbootable and rerunning the same function is the only supported forward recovery.
- **Old historical artifacts are unavailable to v9 code** → retain the old deployment/database for audit or restore; do not add old-layout routing to current authorization.

## Migration Plan

1. Release migration tooling and documentation before requiring storage 9 in a serving client.
2. Back up and rehearse the complete procedure on a restored production-sized database.
3. Stop every EACL v7 Relationship writer and authorization peer; verify the maintenance fence.
4. Run the backend `v7-to-v9/migrate!` function with bounded batch and progress options.
5. Require its final verification report and storage stamp 9; independently sample known grants, exclusions, arrows, and Relationship pages.
6. Deploy/restart EACL v8 clients that require storage ABI 9, discard old caches, and restart old cursors.
7. Resume writes only after all peers report target compatibility.
8. Rollback means stopping the new deployment and restoring/switching to the pre-migration database with the matching old EACL version. There is no in-place v9-to-v7 downgrade.
