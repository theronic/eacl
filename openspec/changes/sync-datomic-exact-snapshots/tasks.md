## 1. Pin Datomic Exact-Selection Effects

- [x] 1.1 Add adapter regressions proving an already-local exact basis skips `d/sync`, calls `d/as-of` once, and reports native revision and exact locator equal to the token basis.
- [x] 1.2 Add a lagging-Peer regression proving a token basis ahead of `(d/db conn)` invokes bounded two-argument `d/sync`, verifies the returned basis, and evaluates exactly at the token basis rather than the newer synchronized head.
- [x] 1.3 Add timeout and below-floor regressions proving no `d/as-of` or authorization work occurs and errors retain requested basis, observed basis, timeout, and stable freshness reasons.
- [x] 1.4 Prove timeout and interruption cancel the Datomic `ListenableFuture`; interruption remains classified cancellation and preserves the thread interruption contract.
- [x] 1.5 Add malformed/contradictory Datomic locator tests proving non-integer locators and `revision != exact-locator` fail before synchronization.
- [x] 1.6 Add provider-failure regressions proving unexpected sync/as-of/storage failures preserve phase and cause and never become `nil`, snapshot expiry, or a newer-snapshot answer.
- [x] 1.7 Exercise direct public `:at-exact-snapshot`, Datomic bespoke cursor recovery, and shared Relay cursor recovery so every route uses the corrected adapter behavior.

## 2. Implement Bounded Cancellable Datomic Catch-Up

- [x] 2.1 Extract or reuse one targeted-sync helper that compares the authenticated locator with one local database, synchronizes only when behind, applies the remaining timeout, verifies the resulting basis, and owns waiter cancellation.
- [x] 2.2 Update Datomic `:select-exact` to validate its backend-specific token invariant, catch up to a newer locator, and construct its immutable adapter from `(d/as-of caught-up-db locator)` with exact selected metadata.
- [x] 2.3 Preserve EACL freshness errors unchanged; wrap only foreign provider failures as classified selection failures and avoid catching fatal JVM errors as retryable application outcomes.
- [x] 2.4 Remove the future-locator-as-unavailable guard and every undocumented Datomic out-of-range/retention-expiry branch.
- [x] 2.5 Confirm minimize-latency, fully-consistent, and at-least-as-fresh semantics remain unchanged except for any deliberately shared waiter-cancellation helper.

## 3. Make Cursor Lifetime Follow History Capability

- [x] 3.1 Change Datomic cursor minting so absent `:cursor-ttl-seconds` omits expiry; retain exact configured-TTL validation and expired-cursor behavior.
- [x] 3.2 Remove the Datomic five-minute default from cache/cursor lifetime coupling and keep cache capacity/TTL options independent from cursor age.
- [x] 3.3 Add cross-backend tests that resume non-expiring cursors well beyond five minutes, after ordinary schema and relationship mutations, and after checkpoint/page-cache eviction.
- [x] 3.4 Add stable-key/shared-lifecycle restart and cross-Peer cursor tests proving private continuation state is optional and exact deterministic replay preserves the remaining sequence.
- [x] 3.5 Separate immutable query/principal/configuration scope from schema/dependency snapshot proof in shared Relay cursor validation so a changed current schema can reach exact fallback.
- [x] 3.6 Preserve invalid-cursor outcomes for query/configuration/ABI mismatch, configured expiry for explicit TTL, lifecycle mismatch for history replacement, and deadline/resource errors for bounded replay.
- [x] 3.7 Verify an old cursor remains a query-bound historical enumeration and document that applications needing current entitlement recheck authorization while consuming results.

## 4. Make Datahike Exact-History Claims Durable and Honest

- [x] 4.1 Change EACL-created Datahike database defaults to `:keep-history? true` and document the storage/write-amplification trade-off plus the explicit opt-out.
- [x] 4.2 Certify configuration-specific capabilities for temporal history, retained-commit-only exact selection, and no exact reconstruction.
- [x] 4.3 Add a real Datahike cutoff-GC regression proving temporal history reconstructs an exact revision after its named commit record is unavailable.
- [x] 4.4 Preserve exact-snapshot unavailable only for a history-disabled configuration whose named commit was genuinely collected; provider failures remain classified failures.
- [x] 4.5 Add lifecycle-rotation tests for purge, branch force, reset, or equivalent destructive history replacement boundaries without attempting to support those operations concurrently with authorization traffic.

## 5. Reuse Snapshot-Exact Completed Answers

- [x] 5.1 Define one canonical snapshot-exact key containing source/branch/lifecycle, native revision and exact locator, exact view kind, adapter/identity semantics, engine/order ABI, normalized request/result shape/demand, and answer-affecting limits.
- [x] 5.2 Refactor the bounded exact completed-answer tier to retain multiple canonical snapshot generations or provide an equivalently safe composite-key store; preserve weight/LRU/admission bounds and late-publication isolation.
- [x] 5.3 Permit `:at-exact-snapshot` to probe and publish only the matching snapshot-exact tier after successful exact selection.
- [x] 5.4 Prohibit managed proof-backed lookup/publication for exact requests and prohibit current/no-history stamp validation of historical answers.
- [x] 5.5 Rebuild response tokens, cursor envelopes, cache basis, external identifiers, and all public snapshot metadata from the selected exact adapter on every hit.
- [x] 5.6 Add hit/miss/collision regressions for same `T`, different `T`, repeated numeric revisions across lifecycle rotation, different view kinds, adapter/identity changes, arbitrary DB views, disabled cache, eviction, and late historical publication.
- [x] 5.7 Add result-shape coverage for decisions, counts, pages, relationship reads, subject/resource lookups, and permission-tree expansion without caching partial traversal or pre-encoded expiring cursors.

## 6. Documentation, Specs, and Assurance

- [x] 6.1 Update README and backend guides with Datomic targeted exact catch-up, no default cursor expiry, Datahike temporal-history defaults, conditional retained-commit behavior, and snapshot-exact cache reuse.
- [x] 6.2 Reconcile stale main specs that still describe mutation graphs, DataScript exact registries, mandatory cursor expiry, or generic storage-expired behavior after delta specs are synced.
- [x] 6.3 Update operational guidance: Datomic excision and Datahike purge/cutoff history destruction require quiescence, completion, source-lifecycle rotation, cache detachment, and deliberate key/version policy.
- [x] 6.4 Audit generated consistency/cache/cursor decisions, formal smoke fixtures, mutation registry, and assurance matrix. State explicitly that backend I/O effects, full-history retention, future cancellation, and canonical cache-key truthfulness remain certified adapter assumptions rather than proved kernel facts.
- [x] 6.5 Regenerate the public-source closure ledger with `node bin/public-source-closure.mjs write` after public source or assurance changes.

## 7. Verification

- [x] 7.1 Discover project nREPLs and run targeted shared consistency, cursor, cache, Datomic, and Datahike namespaces with `:reload`.
- [x] 7.2 Run the CI-equivalent non-benchmark battery through an nREPL started with the required test aliases; resolve failures without weakening exact identity, replay, or error classification.
- [x] 7.3 Run isolated real-backend evidence for cross-Peer Datomic catch-up and Datahike history-enabled cutoff GC; do not treat same-JVM mocks alone as distribution/retention proof.
- [x] 7.4 If shared CLJC changes, restart as required and run the DataScript ClojureScript build last, matching CI ordering.
- [x] 7.5 Run affected formal smoke/mutation checks and `openspec validate sync-datomic-exact-snapshots --type change --strict`; record intentionally deferred long-running verification separately.
