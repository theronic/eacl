## Why

Datalevin is the only bundled backend without ordered-generation proofs, so its completed answers reuse only at an identical basis. Three facts cause that, none of them a missing "proof profile":

1. **Read datoms carry no transaction.** The maintained fork's persistent datoms are reconstructed with `tx0` (`storage.clj` `retrieved->datom`), so the `:tx`-based frame Datomic, Datahike, and DataScript build cannot be read. A stamp must therefore be stored as a *value*.
2. **The existing stamps are references into a shared id space.** `:eacl/schema-generation`, `:eacl/schema-write-fence`, and `:eacl/relation-version` are `:db.type/ref` populated by `:db/current-tx`, and in Datalevin `tx0 = 1` and `e0 = 0` share one numeric space: a stamp value aliases an entity id, `allocate-eid` advances `:max-eid` for it, and retracting an entity whose id equals a stamp retracts the stamp.
3. **Nothing enforces the supported-writer contract.** `make-client` takes a caller-owned connection, the "certified sole-writer topology" is an equality check on a configuration map, and the fork has no pre-commit hook; any connection can write relationship tuples without a stamp.

Datalevin does not need a session-scoped lineage. Its source identity is persisted, its lifecycle is required persisted configuration, and its external revision watermark already rejects a rolled-back store at construction — a stronger witness than the other durable backends have. Cursors and tokens must survive provider restart as they do on Datomic.

## What Changes

- **Scalar stamps.** Replace the three ref-typed attributes with `:db.type/long`, cardinality-one `:eacl.datalevin/schema-generation`, `:eacl.datalevin/schema-write-fence`, and `:eacl.datalevin/relation-generation`. No migration: the module is unpublished and v8 is unreleased; demo stores are reseeded.
- **Commit-generation materialization in the fork.** `:db/current-tx` in the value position of a long attribute materializes to the committing `max-tx` without allocating an entity id, and the commit asserts that the prepared generation equals the generation it commits and that the store's persisted `max-tx` has not moved under it (foreign-writer detection).
- **A generic write policy in the fork, enforced at the one point every transaction entry converges after expansion.** A persisted policy names guarded attributes (writes require a per-open admission token carried in `tx-meta`), frozen attributes (administrative mutation requires the same token), and exact stamp rules (a datom on a relationship tuple attribute requires a relation-generation datom for the relation at tuple position 1 in the same transaction; a datom on a definition attribute requires a schema-generation datom on the one persisted schema singleton). The fork stays EACL-agnostic: the policy is data registered by the Datalevin writer role at schema installation.
- **Datalevin advertises `:ordered-generations`** with a `:proof-frame` that reads exactly the requested relation generations from the owned read snapshot; the certified `:schema-generation` operation reads the scalar attribute. Lineage remains persisted source id plus configured lifecycle; the revision watermark stays.
- **Remove** the topology-declaration equality check (the fork's continuity check enforces the single-writer-process constraint at commit), the idea of a path-owning constructor, and the session nonce. Unsafe LMDB flags (now including `:nolock`), WAL, and HA modes remain rejected because they break owned read snapshots or the writer lock the continuity check relies on.
- **Repair shared-store commit adoption and recover only safe same-process staleness.** Every commit path updates the original shared Store. A stale immutable connection wrapper is refreshed and the complete semantic write is retried under a fixed bound; CAS contention and every unsafe/unknown failure remain terminal.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `backend-native-revision-consistency`: Datalevin lineage is persisted source identity plus lifecycle, witnessed by the revision watermark; the write policy, not topology declarations, establishes mutation completeness.
- `dependency-validated-authorization-cache`: Datalevin managed reuse under the shared lineage-scoped frame rule; storage-enforced atomic stamping.
- `managed-reuse-certification`: Datalevin certification covers the fork write policy, scalar stamps, commit-generation equality, foreign-writer detection, and bounded frame reads.
- `cross-backend-conformance`: Datalevin joins the ordered-generation conformance matrix, including restart survival of cursors and managed state.

## Impact

- Depends on `introduce-proof-carrying-semantic-equivalence` (frame contract: relation generations only, revision domain, ceiling assertion) and on `add-authorization-views` (writer role, Datalevin lifecycle and source acquisition).
- Maintained fork (`dev.eacl/datalevin-embedded-eacl`): transaction pipeline (`:db/current-tx` for long attributes), commit path (generation equality, `max-tx` continuity), write policy (persisted in the meta DBI, admission token per open, enforcement before store commit), `update-schema` guard for frozen attributes, documentation, and contract tests.
- `eacl-datalevin`: physical schema, `ensure-physical-schema!`, writer role (policy registration, admission token in `tx-meta`, stamp planning to scalar attributes), basis adapter (`:proof-frame`, `:schema-generation`, capabilities), construction validation, certification, benchmarks, README/PORTING.
- Public authorization requests, revision tokens, and cursor formats are unchanged. The README capability table no longer states that Datalevin omits ordered-generation proofs.

## Related changes

Already applied or in progress; this change modifies their outcomes rather than their artifacts:

- Workspace change `../../../openspec/changes/add-datalevin-backend-and-demo` (in progress, 93/153): introduced the Datalevin module, its ref-typed stamps, the `:datalevin-topology` declaration (design §5 "Limit the certified consistency topology"), the external revision watermark, and the exact-only capability policy. The declaration and the ref stamps are replaced here; the watermark, owned read snapshots, and unsafe-flag rejection are kept.
- `eliminate-authorization-request-amplification` (applied): added Datalevin's certified `:schema-generation` operation and the revision-only `read-snapshot-revision-info` seam, both kept.
- `add-authorization-views` (planned): the writer role that registers the write policy and carries the admission token; Datalevin's required persisted lifecycle.
