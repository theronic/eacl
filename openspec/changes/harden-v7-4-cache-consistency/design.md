## Context

The v7.4 branch introduces a relationship-aware result cache, an explicit relationship
coordinator, consistency descriptors, Zed tokens, and retained recursive continuations. Adversarial
tests exposed a gap between the coordinator's latest published proof and the database value
observed by another Datomic connection: the reader can pair a pre-mutation DB with a post-mutation
proof and publish a stale answer as current.

The same implementation made exact cached state a correctness dependency. A cursor therefore
fails after ordinary basis advancement when caching is disabled or a continuation is absent,
whereas v7.3 reconstructed the cursor basis with Datomic history. Provider safety wrappers also
allow secondary telemetry failures to escape, and two mutation APIs have incomplete error or
accounting semantics. The draft Zed-token format is only encoded EDN, so a token sent through a
frontend can be modified to select an arbitrary database revision before the backend parses it.

This design refines and, where stated, supersedes the exact-cache-only and proof-equivalent replay
decisions in `add-consistency-aware-eacl-cache`. It keeps the useful cache-key and coordinator
model, but restores historical replay as the correctness path beneath the cache.

## Goals / Non-Goals

**Goals:**

- Make a live database/proof pair coherent across independently lagging Datomic connections.
- Make cursors and exact reads correct without relying on cache residency.
- Contain all cache-side failures during authorization reads.
- Authenticate Zed tokens returned through untrusted frontends and bound targeted synchronization.
- Normalize unsupported relationship operations and committed retraction accounting.
- Preserve public request and successful response shapes wherever possible.

**Non-Goals:**

- Make EACL safe when relationship writers bypass the configured coordinator.
- Turn Zed tokens into user credentials, capabilities, or Internet-facing bearer tokens.
- Encrypt Zed-token database or revision claims; integrity and authenticity are sufficient.
- Make an authentic Zed token single-use, bind it to an end-user identity, or prevent replay.
- Replace encrypted/authenticated page cursors with Zed tokens.
- Persist cache generations, continuations, or historical schema snapshots in Datomic.

## Decisions

### 1. Catch lagging readers up to the coordinator floor, then retry capture

The local coordinator already records `:observed-t`, the greatest committed basis published by a
participating relationship mutation. `capture-result-context` will use this as a visibility floor:

1. Acquire the coordinator read barrier and read both `(d/db conn)` and the coordinator snapshot.
2. If the DB basis is at least `:observed-t`, prepare the query and derive its dependency proof
   from that same snapshot.
3. If the DB basis is behind, release the barrier, wait specifically for `:observed-t`, and retry
   from step 1.

The retry is necessary because another writer may publish a higher floor while the connection is
catching up. No cache I/O or graph traversal occurs under the barrier. Query preparation remains
inside the successful short capture because dependency selection must correspond to the captured
schema and inputs.

This uses the coordinator-wide floor rather than trying to cap dependency revisions to the stale
DB. Capping would fabricate a historical coordinator state that the in-memory coordinator does not
retain and could make a stale negative or positive answer reusable. The global floor can cause an
extra targeted catch-up for an unrelated relationship write, but only for a connection that is
already behind.

The same bounded wait helper will serve explicit `at-least-as-fresh` requests and coordinator-floor
catch-up. It will dereference the future from `d/sync conn t` with a finite timeout and return
`:eacl.consistency/freshness-unavailable` with `:reason :timeout`, `:requested-t`, `:observed-t`,
and `:timeout-ms` on expiry. A new positive top-level client option
`:consistency-sync-timeout-ms` will default to 30,000 milliseconds.

Alternative considered: hold the read barrier while waiting. That prevents publication races but
also blocks every coordinated writer on a network/storage wait, violating the short-barrier
design. Retrying after an out-of-barrier targeted wait preserves both properties.

### 2. Historical Datomic replay is the authoritative fallback

Page tokens already authenticate their encoded operation, query identity, basis, and cache scope.
Cursor decoding will happen before database selection. A request with a valid cursor selects its
cursor basis; an exact consistency token selects its token basis; an ordinary first page selects a
coherent live capture.

For a cursor or exact request, EACL will:

1. use a validated matching exact page or continuation when available;
2. otherwise ensure the local connection can observe the requested basis using the bounded wait
   when necessary;
3. construct `d/as-of` at the requested basis;
4. resolve external inputs, evaluate, replay cursor boundaries, and coerce outputs against that
   historical DB.

The historical path will bind a schema cache built from the historical DB rather than the client's
live schema atom. This is essential after `write-schema!`: an old relationship snapshot evaluated
with new permission definitions is not an exact snapshot.

`read-relationships` will use the same cursor-basis selector and remove its equality check against
the current connection basis. Lookup replay keeps deterministic boundary verification and
recursive traversal safety ceilings. Cursor expiry remains appropriate for invalid identity,
authentication, query-shape, ordering, TTL, or unreconstructable history—not ordinary basis
advancement or cache disablement.

This supersedes the earlier decision that exact reads never invoke `d/as-of` and that a changed
relationship proof requires retained exact cache state. Datomic history is already the v7.3
compatibility mechanism and is a correctness dependency of cursor semantics; the cache remains an
accelerator.

Alternative considered: embed all page results in the cursor. That would avoid time travel but
creates unbounded tokens, duplicates cache storage at the boundary, and exposes large result sets
to every caller.

### 3. Cache protocol calls cross one exception-containment boundary

The cache namespace will provide non-throwing wrappers for every protocol operation used during an
authorization call, including `lookup`, `store!`, `capabilities`, and provider-error recording.
The catch path for a failed primary operation will call telemetry through its own nested
`try`/`catch`; telemetry is never allowed to replace the primary outcome.

A failed capability probe returns the conservative empty capability set. Failed lookups are
misses, and failed publications are rejected admissions. Built-in hit/miss bookkeeping performed
by safe wrappers is also best-effort. Exact operations then fall through to historical replay
rather than converting a recoverable provider failure into snapshot-unavailable.

Explicit diagnostic APIs such as a consumer's direct provider `stats` call may still surface a
provider-specific failure; the guarantee applies to authorization, lookup, count, and pagination
outcomes.

Alternative considered: require every custom provider to make telemetry infallible. The protocol
cannot enforce that property, and correctness should not depend on third-party observability code.

### 4. Zed tokens use a domain-separated HMAC envelope

The unsigned `eacl_z1_` format will be replaced before v7.4 release. A v2 token will contain a
bounded, versioned envelope with a key identifier, a base64url canonical payload, and an
HMAC-SHA-256 tag. The payload contains exactly the semantic version, database identity, and Long
basis `t`. The tag covers a fixed EACL Zed-token domain separator, envelope version, key identifier,
and the exact encoded payload bytes.

Verification proceeds in this order:

1. Enforce a small maximum encoded length, the v2 prefix, the envelope shape, and bounded key-id
   and tag encodings.
2. Select exactly the named verification key. An absent key is a generic invalid-token failure;
   verification never tries every key.
3. Recompute the HMAC and compare tags with `MessageDigest/isEqual`.
4. Only after successful authentication, parse and validate the payload's exact keys, versions,
   database identity, and non-negative Long revision.
5. Only after all validation may consistency selection call cache code, `d/sync`, or `d/as-of`.

Malformed, unknown-key, legacy unsigned, and authentication failures all use
`:eacl/invalid-zed-token` without including secret key material or treating the untrusted revision
as diagnostic truth. A valid token signed for a different database can report
`:reason :database-mismatch` after authentication.

`make-client` will accept `:zed-token-key`, `:zed-token-keyring`, and `:zed-token-kid`. Each
normalized root key will derive a distinct HMAC key using HMAC-SHA-256 and the fixed label
`eacl/zed-token/signing-key/v2`, avoiding direct cross-protocol reuse with page-token AES-GCM. When
Zed-token configuration is absent, the client derives a Zed keyring from its page-token keyring and
uses the same current key identifier. The existing random per-process default remains fail-closed
but is not portable; production backend instances that exchange frontend-returned tokens must
share stable key material.

New tokens use only the configured current key. Verification accepts any explicitly retained
keyring entry, allowing rotation by deploying a new current key, retaining the old verification
key for the intended overlap, and later removing it. The low-level token constructor will require a
signing context; the draft public key-free constructor will not remain as an unsigned escape hatch.
Every mutation response, `current-zed-token`, and checkpoint token helper will sign through its
client options.

Authentication prevents a frontend from inventing or modifying `:db` and `:t`, but it does not
make a valid token fresh, single-use, principal-bound, or authorized for every consistency mode. A
frontend can replay an authentic old token. Backends should therefore interpret ordinary
frontend-echoed tokens as `at-least-as-fresh` lower bounds. A backend that chooses
`at-exact-snapshot` is intentionally permitting historical evaluation and must authorize that
choice independently; the frontend must not control the consistency descriptor merely because it
possesses a valid token.

The finite `:consistency-sync-timeout-ms` remains defense in depth for a validly signed future
revision produced by another backend or configuration error. An unauthenticated future revision is
rejected before `d/sync`.

Alternative considered: AES-GCM would also authenticate the token but confidentiality is not a
requirement for a database identity and revision, and randomized encryption adds complexity without
preventing replay. An application-layer-only MAC was also rejected because the EACL token parser
itself must be safe when the documented token is designed to round-trip through a frontend.

### 5. Validate relationship operations before relationship resolution

`tx-update-relationship` will check membership in `#{:create :touch :delete}` before calling
`resolve-relationship`. Unsupported values will share one constructor for
`:eacl/unsupported-operation`, preserving the rejected value in `:operation`. This guarantees a
typed API error for `:unspecified`, `nil`, and future or mistyped keywords and prevents an invalid
operation from causing endpoint work or a raw `case` exception.

### 6. Count retractions from committed Datomic reports

`delete-object!` will retain its precomputed, distinct pair-retraction batches, but it will no
longer count batch commands. For each successful transaction it will count tx-report datoms where:

- `:added` is false; and
- `:a` is the entity id of either v7 relationship tuple attribute.

The public `:retracted-datoms` total is the sum across committed batches. The same tx reports
continue to derive changed relation definitions for coordinator publication. This reports `1` for
a surviving orphan half, `2` for a complete pair, and `0` for repeated cleanup.

Alternative considered: probe the pre-transaction DB for every command. That duplicates index
work, can diverge from the committed result, and is unnecessary because Datomic already reports
the actual retractions.

### 7. Bind cache and cursor state to the context that produced it

Page-token v5 will include the Datomic database identity captured by the client. Cursor validation
will compare that authenticated identity before selecting a historical basis or resolving request
objects. Deployments may intentionally share page-token keys across backend instances, but a token
from another logical database must fail even when both databases have matching schema generations,
basis revisions, internal EIDs, and query shapes.

Completed lookup pages continue to store internal EIDs. When a consistency mode selects an older
cached answer, boundary coercion will therefore use `d/as-of` at that answer's `:basis-t`. This is
distinct from ordinary live-result reuse, whose proof is promoted to the current captured basis.
Deleting an entity after caching a page must not make a legitimate stale or lower-bound read fail
merely because the live DB no longer maps the cached EID to an external identifier.

Every recursive page and continuation key will include `:cache :namespace`, matching completed
result keys. The wrapper namespace remains provider cleanup metadata; it is not a substitute for
key isolation because lookup occurs before wrapper cleanup. Separate consumers sharing one store
must neither read nor overwrite each other's recursive entries.

Continuation admission will charge every retained traversal structure. Reverse state retains the
compiled `:rules-by-node` graph, so state construction will record its rule count and
`continuation-weight` will include that scalar count without walking the graph on every emitted
page. The estimate remains an admission heuristic rather than a measured JVM heap size.

## Risks / Trade-offs

- **Historical replay can be slower than an exact cache hit.** → Keep exact pages and recursive
  continuations as accelerators, retain traversal ceilings, and benchmark deep replay separately.
- **A coordinator-wide visibility floor may synchronize for an unrelated relationship write.** →
  Prefer the conservative floor because the coordinator has no historical per-connection proof;
  the wait occurs only while a Peer is actually behind.
- **A 30-second default may be too short during a prolonged outage.** → Make the positive timeout
  configurable and return requested/observed revisions so consumers can retry intentionally.
- **Historical schema reconstruction adds a second schema-cache path.** → Keep it request-scoped or
  bounded by existing exact-cache lifetime and test schema rotation between pages.
- **Provider failures become less visible to callers.** → Preserve best-effort provider-error
  metrics and direct diagnostic APIs while never coupling them to authorization correctness.
- **Random default keys make tokens instance-local.** → Fail closed by default and require a stable
  shared Zed-token keyring for load-balanced or restart-surviving frontend round trips.
- **Valid tokens remain replayable.** → Recommend `at-least-as-fresh` for frontend echoes and
  require backend-controlled authorization before selecting `at-exact-snapshot`.
- **Key retirement invalidates outstanding tokens.** → Document an overlap window and retain prior
  verification keys until the deployment's intended token lifetime has elapsed.
- **Shared page-token keys span multiple logical databases.** → Authenticate the database identity
  in every page token and reject a mismatch before historical selection.
- **A stale cached page refers to an entity deleted from the live DB.** → Resolve its internal EIDs
  against the cached answer's historical basis.
- **Several cache namespaces share one provider.** → Put the namespace in recursive physical keys,
  not only in entry metadata used by targeted cleanup.

## Migration Plan

1. Add failing regression tests for the seven adversarial findings before changing behavior.
2. Add authenticated v2 Zed-token envelopes, client keyring normalization, rotation, and rejection
   of the unreleased unsigned draft format.
3. Introduce and validate `:consistency-sync-timeout-ms` and the shared targeted-wait helper.
4. Implement retrying coherent capture for shared coordinators and lagging connections.
5. Refactor cursor/exact database selection around cache-first, `d/as-of` fallback, including
   historical schema binding and `read-relationships`.
6. Route cache calls and nested provider-error telemetry through non-throwing wrappers.
7. Validate relationship operations early and count actual relationship retractions from tx
   reports.
8. Update README/API docs and changelog with pagination guarantees, timeout behavior, token-key
   deployment, replay limitations, and frontend usage.
9. Add follow-up regressions for stale cached lookup coercion, cross-database page cursors,
   recursive namespace isolation, and reverse-continuation weight.
10. Run the regular nREPL suite and targeted multi-connection, token-tampering, key-rotation,
    schema-rotation, and provider-fault tests; run pagination benchmarks only when explicitly
    validating performance.

No Datomic data migration is required. Rollback is code-only; if a rollout must revert before a
corrected build is available, disable v7.4 result caching and avoid issuing cross-transaction v7.4
cursors rather than relying on the known-bad paths.

## Open Questions

None required for implementation. The default wait bound can be tuned from test and deployment
feedback without changing the specified finite-timeout contract.
