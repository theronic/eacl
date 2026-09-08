## Why

EACL's public source lifecycle is an opaque incarnation identifier, but its API currently accepts bounded strings and structured values while rejecting native UUIDs. The 0tx portable ACL integration exposed this mismatch: it preserves UUID source identifiers in its artifact, then stringifies them for EACL; narrowing the lifecycle contract can remove this representational ambiguity and make its costs measurable without weakening source isolation.

## What Changes

- **BREAKING:** Require a native UUID for an explicitly supplied `:source-lifecycle` and the coordinated argument to `expire-cache!`. Reject legacy strings, keywords, maps and vectors with typed upgrade guidance; do not hash, stringify or silently translate them. The operator selected this policy on 2026-09-08.
- Retain separate backend/source/branch identity, native revision, schema/dependency generations and private runtime incarnation. A lifecycle UUID identifies history incarnation, not a commit, permission decision, session or cryptographic key.
- Preserve cross-process defaults for backends that currently support them using one documented reserved initial UUID. Retain Datalevin's explicit persisted lifecycle requirement. History replacement uses a fresh UUID, with coordinated persistence when processes exchange artifacts. Preserve same-lifecycle local full cache reset and private-incarnation isolation.
- Add an explicit, type-preserving UUID representation to the bounded canonical format and version every affected lifecycle-bearing artifact/identity domain. Require fresh tokens, cursors and cache snapshots; do not maintain a legacy execution or restore path.
- Validate identical CLJ/CLJS meaning and encoding, wrong-source/lifecycle rejection, restart/rotation behavior, stale in-flight publication and retained snapshots through formal refinement, executable negative controls and backend conformance.
- Compare existing UUID strings and structured lifecycles with native UUIDs using matched correctness workloads, allocation/heap measurements, encoded sizes and end-to-end warm operations. Report API simplification separately from measured performance; a UUID object alone does not establish smaller wire tokens or faster requests.
- The operator accepted the measured CLJS latency and retained-memory trade-offs on 2026-09-08 because this runtime primarily serves in-browser DataScript demos. UUID-specific CLJS timing and heap comparisons remain reported diagnostics; JVM budgets, correctness, deterministic work and artifact size limits remain release requirements.

## Capabilities

### New Capabilities

- `uuid-source-lifecycle`: UUID-only lifecycle input, canonical typed representation, default and rotation ownership, upgrade errors, formal correspondence and UUID-specific performance qualification.

### Modified Capabilities

- `cross-backend-revision-consistency`: Require a shared UUID lifecycle throughout source selection and token issuance, preserving backend-specific lifecycle ownership and complete source scope.
- `cursor-token-handling`: Reject superseded lifecycle-bearing cursor formats explicitly without silent restart, preserving rejection of raw cursor maps and existing exact-history recovery.
- `portable-authorization-cache`: Bind persisted cache provenance to the UUID lifecycle and upgraded format; reject old snapshots without disturbing installed runtime state.

## Impact

Primary implementation surfaces are `modules/eacl/src/eacl/causal_token.cljc`, `secure_format.cljc`, `client/orchestration.cljc`, `cursor.cljc`, cache identity/provenance and backend constructors. Public basis/snapshot views, cache export/restore, keyring-authenticated artifacts, CLJ/CLJS tests, formal source closure and version inventories are in scope. Backend modules include Datomic, Datahike, DataScript and Datalevin; demo and consumer call sites require an upgrade inventory, not edits outside this workspace during this change.

No financial storage, Datahike transaction semantics, cache backend, generic object-ID or cryptographic primitive change is proposed. This proposal does not modify 0tx or the EACL implementation. API/token incompatibility is intentional and must be released as a documented breaking change with coordinated rollout and fresh artifacts. No measured speedup or completed proof is claimed.
