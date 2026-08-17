## Context

EACL authenticates and scope-checks an exact-snapshot token before invoking a backend adapter's `:select-exact` operation. Datomic currently reads `(d/db conn)` and calls `d/as-of` only when the token locator is no greater than that local database's basis. A same-source token produced through another Peer can therefore name a committed basis that exists in Datomic but is still ahead of this Peer; the adapter returns `nil` and shared validation misreports replica lag as exact-snapshot unavailability.

Datomic documents two-argument `d/sync` and `d/as-of` as complementary operations: sync ensures a local connection has reached at least `T`, while as-of filters an observed database to no later than `T`. Numeric `d/as-of` does not validate that the backing database has reached `T`; EACL must establish that postcondition first. The returned Datomic `ListenableFuture` is a `java.util.concurrent.Future` and must be cancelled when EACL stops waiting.

Ordinary Datomic history has no SpiceDB-style GC window. Datahike can likewise reconstruct arbitrary historical revisions when `:keep-history? true`; when history is disabled it may instead rely on commit records that cutoff garbage collection can remove. Cursor lifetime and exact-answer cache eligibility must follow these actual backend facts rather than SpiceDB's retention policy.

The current completed cache has an exact tier, but orchestration marks every `:at-exact-snapshot` request uncacheable because the tier is implemented as one current generation. That protects against arbitrary historical views and current-only managed proofs, but it also discards a safe hit when the selected authenticated snapshot and semantic request exactly match a retained answer.

## Goals / Non-Goals

**Goals:**

- Make valid same-source Datomic exact tokens portable between Peers at different locally observed bases.
- Evaluate exactly at the authenticated basis after bounded, cancellable catch-up.
- Give full-history Datomic and Datahike cursors no default age limit.
- Preserve explicit invalidation through key retirement, format/ABI incompatibility, source-lifecycle rotation, and genuine history unavailability.
- Reuse completed answers only when their canonical snapshot identity and complete semantic request equal the selected exact request.
- Preserve bounded memory and execution: cache/checkpoint eviction causes recomputation or replay, and deadlines/resource ceilings remain enforceable.

**Non-Goals:**

- Removing causal-token TTL or changing its authenticated wire format.
- Making a cursor represent current authorization after it has deliberately pinned an older enumeration.
- Adding arbitrary historical support to DataScript.
- Reusing managed proof-backed answers across snapshots for `:at-exact-snapshot`.
- Supporting concurrent Datomic excision, Datahike purge, restore, reset, or history replacement without quiescence and source-lifecycle rotation.
- Making an arbitrary unauthenticated transaction number a valid exact locator.

## Decisions

### 1. Catch up to an authenticated Datomic basis before applying `d/as-of`

For a Datomic exact locator `T`:

1. Authentication, TTL, backend/source scope, and source lifecycle are validated before the adapter is invoked.
2. The Datomic adapter requires an integer locator and the Datomic invariant `revision == exact-locator`; malformed or contradictory payloads fail before synchronization.
3. Read one current local database.
4. If `T <= basis(local-db)`, use that database without synchronization.
5. If `T > basis(local-db)`, retain the future returned by `(d/sync conn T)`, dereference it using the remaining consistency/request deadline, and cancel it on timeout or interruption.
6. Verify `basis(caught-up-db) >= T`.
7. Construct the selected adapter from `(d/as-of caught-up-db T)` and report both native revision and exact locator as `T`.
8. Shared consistency validation independently verifies exact equality and source identity.

Targeted sync is preferred to zero-argument sync because the authenticated token already supplies the required causal point. The selected caught-up head is never evaluated directly when it is newer than `T`.

### 2. Model real errors, not a Datomic retention window

| Condition | Public classification |
| --- | --- |
| Authenticated causal-token TTL elapsed | Existing `:eacl.consistency/token-expired` |
| Malformed, negative, contradictory, or non-Datomic locator | Existing invalid-token family |
| Targeted synchronization exceeds its bound | `:eacl.consistency/freshness-unavailable`, reason `:freshness-timeout` |
| Synchronization returns below `T` | `:eacl.consistency/freshness-unavailable`, reason `:head-behind` |
| Wait is interrupted | Cancel waiter, preserve thread interruption, and throw classified cancellation |
| Unexpected synchronization, storage, or selection failure | Classified retryable selection/provider failure with cause and phase |
| Restore, reset, excision, purge, branch replacement, or destructive history rewrite | Reject through rotated source lifecycle; operation is outside the unchanged-lifecycle contract |
| Datahike history-disabled configuration loses a retained commit | Exact-snapshot unavailable; cursor boundary may expose its established unavailable/stale form |

Within an unreplaced ordinary Datomic history, an authentic same-source EACL token names a committed `T`; a locally future `T` is lag, not out of range. Datomic `:select-exact` therefore does not return `nil` merely because `T` is ahead locally and does not manufacture storage-expiry errors.

An authoritative check could distinguish a signer defect that names a transaction beyond the transactor head, but it would add a transactor round trip to an otherwise targeted synchronization path. Authenticated EACL issuance makes that state impossible under the supported lifecycle, so bounded freshness failure is the safe operational outcome unless a future design introduces an explicit signer-diagnostics mode.

### 3. Cursor TTL is optional policy, not history availability

Cursor envelopes remain authenticated, query-bound tokens. Without `:cursor-ttl-seconds`, minting omits expiry and decoding imposes no age check. With a configured positive TTL, the existing expired-cursor error remains unchanged.

A full-history cursor binds the operation, complete normalized query/principal, ordering ABI, adapter/identity contract, source lifecycle, exact native revision/locator, dependency or exact-snapshot proof, and stable boundary. It may continue on a proof-equivalent current snapshot or reconstruct the original exact snapshot. Ordinary forward schema or relationship changes may force exact reconstruction but do not invalidate the original cursor.

Schema generation belongs to dependency/exact-snapshot identity, not to the immutable query-scope digest used before recovery. Otherwise comparing a cursor against the current schema rejects it before EACL can select and validate the cursor's original historical schema.

Private checkpoints are acceleration only. A miss or eviction deterministically replays the authenticated prefix against the selected exact snapshot and validates ordinal plus boundary identity before publishing a page. Replay deadline/resource failures remain typed deadline/resource outcomes, not expiry.

An old cursor intentionally returns the remainder of its original enumeration. Applications that require current authorization when consuming each object must perform a current permission check; an optional cursor TTL is an application policy for limiting historical enumeration, not a storage correctness mechanism.

### 4. Make Datahike's durable-history contract configuration-honest

EACL-created Datahike databases will enable `:keep-history? true` by default so their ordinary contract matches durable exact replay. Temporal `d/as-of` is the durable fallback even when a named commit record is absent or the commit graph is disabled.

Externally supplied Datahike databases remain configuration-specific:

- `:keep-history? true`: exact revisions and cursors are durable across ordinary forward history and commit-record GC.
- history disabled with retained commit graph: exact reconstruction is available only while the named commit remains retained.
- neither temporal history nor retained exact commits: do not advertise exact-snapshot capability.

Cutoff GC, purge, branch force, and source replacement must rotate EACL's source lifecycle when they can invalidate or reinterpret prior exact locators.

### 5. Allow snapshot-exact answer reuse without managed lifting

An exact request may probe and populate a bounded snapshot-exact completed-answer tier after exact selection succeeds. Eligibility requires equality of a canonical snapshot key containing at least:

- backend and stable source/branch identity;
- configured source lifecycle;
- native revision and exact locator;
- ordinary exact-view kind;
- adapter fingerprint and identity contract;
- engine/order ABI, normalized semantic request, result kind/shape, evaluation demand, and answer-affecting limits.

The exact entry stores only the completed semantic answer. Response tokens, cursor envelopes, cache basis, external rendering context, and other public metadata are rebuilt from the selected adapter on every request.

`at-exact-snapshot` never probes or publishes to the managed cross-snapshot tier and never validates an old answer using current-only relation/schema stamps. A miss evaluates on the already selected immutable exact adapter and may publish to the snapshot-exact tier. The tier is bounded by weight/LRU/admission policy; eviction is a miss, not snapshot unavailability.

The current one-generation exact store may be replaced by a bounded map of snapshot-exact generations or by an equivalently safe composite-key answer store. The representation is not normative. A newer current request must not see an older exact entry, while an explicit request selecting that older canonical snapshot may see it if retained.

### 6. Preserve verification boundaries and state the unproved effect

The generated exact-selection decision already verifies presence, adapter validity, source equality, and exact revision/locator equality. It does not prove Datomic I/O effects, future cancellation, temporal-history retention, or cache-key truthfulness. Those remain adapter/certification obligations covered by deterministic effect tests, real backend integration tests, and the assurance matrix.

Formal cache decisions must distinguish snapshot-exact equality from managed proof equality. No proof artifact may imply that an equal numeric revision alone identifies an exact snapshot across lifecycle replacement or arbitrary DB views.

## Risks / Trade-offs

- [A far-future authenticated basis can hold a waiter] -> Bound it by the existing deadline and cancel the Datomic future on every timeout/interruption path.
- [Full Datahike temporal history increases write/storage cost] -> Make the durable-history benefit explicit; externally configured history-off stores retain their lower-cost conditional capability.
- [Non-expiring cursors require old keys and format support] -> Key retirement and unsupported versions remain explicit invalidation events; operators choose their compatibility window independently of database history.
- [Historical exact answers increase cache cardinality] -> Use one bounded weighted/LRU tier; retention is an optimization and never part of correctness.
- [Excision or purge can change the meaning of the same locator] -> Require quiescence, completion, and source-lifecycle rotation before traffic resumes.
- [Custom identity conversion may depend on non-historical data] -> Exact reuse requires the adapter identity contract/fingerprint; unsupported history-discarding dependencies fail certification or bypass cache/replay.
- [Very deep cursor replay may be expensive after checkpoint eviction] -> Preserve request deadlines and replay admission; return resource/deadline errors without expiring or rebasing the cursor.

## Migration Plan

Implement adapter/cache/cursor changes behind existing public options. Datomic clients that omitted `:cursor-ttl-seconds` change from five-minute expiry to no expiry; deployments wanting the old policy set `{:cursor-ttl-seconds 300}` explicitly. EACL-created Datahike databases enable temporal history by default; existing databases retain their stored configuration and capability-specific behavior.

Regenerate the public-source closure ledger after source/assurance edits, run targeted Datomic/Datahike/shared suites through nREPL, run the CI-equivalent non-benchmark battery, then run the ClojureScript build last if shared CLJC changed. Validate the OpenSpec change strictly and record the real-backend multi-Peer/history-GC evidence.

## Open Questions

None for semantics. The implementation may choose the simplest bounded representation for multiple snapshot-exact answer generations, provided the canonical key and no-managed-lifting requirements are preserved.
