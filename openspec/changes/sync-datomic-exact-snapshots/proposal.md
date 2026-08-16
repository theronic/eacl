## Why

A valid same-source Datomic exact-snapshot token can name a committed basis that a lagging Peer has not observed yet. EACL currently treats that locally future basis as unavailable instead of first catching the Peer up.

The audit also found two unnecessary losses of the stronger semantics available from immutable full-history backends:

- Datomic cursors expire after five minutes by default even though ordinary Datomic history remains exactly reconstructible; Datahike and the shared cursor specification already default to no cursor expiry.
- `:at-exact-snapshot` bypasses the completed-answer cache even when it contains an answer computed for the same authenticated immutable snapshot and semantic request.

These are implementation restrictions, not requirements of snapshot-stable authorization. Within one unreplaced full-history source lifecycle, EACL can catch a lagging reader up, evaluate exactly at `T`, replay query-bound cursors without an age limit, and reuse only answers proven to belong to that exact snapshot.

## What Changes

- Make Datomic `:at-exact-snapshot` selection compare the authenticated token basis with the current local Peer basis.
- When the token basis is newer, perform bounded targeted `d/sync` to that basis, cancel the returned future on timeout or interruption, verify the reached basis, and then apply `d/as-of` exactly at the token basis.
- Remove speculative Datomic storage-expiry/out-of-range classification. Token expiry remains a distinct authenticated-envelope rule; malformed locators are invalid tokens; catch-up failures remain typed freshness or selection failures.
- Make cursor expiry optional and off by default for every backend. Datomic and full-history Datahike cursors remain replayable across ordinary forward transactions for as long as their key, source lifecycle, format/ABI, and exact history remain valid.
- Make EACL-created Datahike databases retain temporal history by default. History-disabled or cutoff-GC configurations may advertise only their actual retained-commit behavior and may report exact snapshot unavailability when a named commit is genuinely gone.
- Permit `:at-exact-snapshot` to probe and populate a bounded snapshot-exact completed-answer tier keyed by authenticated canonical snapshot identity plus the complete semantic request. It must never use the managed cross-snapshot tier.
- Keep checkpoint and completed-answer retention as bounded optimizations: eviction causes deterministic exact replay or recomputation, never cursor expiry or a fall-forward to another snapshot.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `backend-native-revision-consistency`: Datomic exact selection catches up to a locally newer authenticated basis and distinguishes token invalidity/expiry, freshness failure, provider failure, and lifecycle replacement without inventing a Datomic retention window. Datahike durable exact-history claims depend on temporal-history configuration.
- `cursor-token-handling`: Cursor TTL remains configurable but defaults to no expiry uniformly, including Datomic.
- `snapshot-stable-pagination`: A query-bound cursor on a full-history backend can reconstruct its original schema, graph, and position without age-based invalidation; query scope must not reject exact fallback merely because the current schema generation changed.
- `forward-history-cache-coherence`: Authenticated exact requests may use only completed answers bound to the identical canonical snapshot and semantic request; managed cross-snapshot reuse remains current-only.

## Impact

- Datomic selection and cursor encoding in `modules/eacl-datomic/src/eacl/datomic/backend.clj` and `modules/eacl-datomic/src/eacl/datomic/core.clj`.
- Datahike default history configuration and exact capability behavior in `modules/eacl-datahike`.
- Shared cursor scope/recovery and completed-answer cache orchestration in `modules/eacl`.
- Datomic/Datahike integration tests, shared cursor/cache contract tests, and formal/assurance fixtures that classify selection, continuation, or cache outcomes.
- README, backend, cursor, consistency, cache, and operational lifecycle documentation.

The public request shapes and cursor/token formats remain compatible: cursor expiry fields stay optional, configured TTLs remain enforced, and old authenticated cursor versions continue according to their existing version/key policy.
