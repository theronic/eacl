## 1. Adversarial Regression Coverage

- [x] 1.1 Add a multi-connection test that publishes a relationship deletion through one client,
  holds another connection behind, and proves the lagging client cannot cache the stale decision
  under the new coordinator proof.
- [x] 1.2 Add lookup pagination tests showing that `:cache false`, unrelated transactions, and
  relevant relationship changes all preserve the first page's historical snapshot.
- [x] 1.3 Add `read-relationships` pagination tests showing that current-basis advancement does not
  expire a valid cursor.
- [x] 1.4 Add exact-read and cursor tests covering a missing cache entry plus schema rotation after
  the requested historical basis.
- [x] 1.5 Add hostile provider tests where lookup, capability probing, storage, metric accounting,
  and provider-error telemetry throw independently and in combination.
- [x] 1.6 Add token tests that alter the encoded database, revision, key identifier, and tag and
  prove every forgery is rejected before cache, `d/sync`, or `d/as-of` work.
- [x] 1.7 Add token tests for unsigned v1 rejection, malformed envelopes, valid cross-database
  rejection, shared-backend verification, and signing-key rotation.
- [x] 1.8 Add consistency tests for a never-completing targeted sync and a correctly authenticated
  future token.
- [x] 1.9 Add relationship update tests for `:unspecified`, `nil`, and an arbitrary operation,
  asserting uniform typed errors before endpoint resolution.
- [x] 1.10 Add `delete-object!` accounting tests for a complete pair, one surviving orphan half,
  repeated cleanup, and multiple batches.

## 2. Authenticated Zed Tokens

- [x] 2.1 Define a bounded v2 Zed-token envelope containing a key identifier, canonical encoded
  payload, and HMAC-SHA-256 tag; reject the unreleased unsigned v1 format.
- [x] 2.2 Add and validate `:zed-token-key`, `:zed-token-keyring`, and `:zed-token-kid` client
  options, including current-key membership and normalization of every configured root key.
- [x] 2.3 Derive domain-separated Zed signing keys from configured roots, defaulting safely to the
  page-token keyring when no dedicated Zed keyring is supplied.
- [x] 2.4 Implement HMAC signing over the envelope version, key identifier, and exact encoded
  payload and constant-time tag verification with the JCA APIs.
- [x] 2.5 Authenticate the envelope before parsing or using `:db` and `:t`, validate exact payload
  keys and numeric bounds afterward, and return sanitized `:eacl/invalid-zed-token` errors.
- [x] 2.6 Remove the key-free token-construction path and thread the client signing context through
  relationship mutations, `delete-object!`, `current-zed-token`, and checkpoint token helpers.
- [x] 2.7 Sign only with the current key identifier while accepting explicitly retained prior
  verification keys so deployments can perform overlap-based rotation.

## 3. Bounded Revision Visibility

- [x] 3.1 Add the positive `:consistency-sync-timeout-ms` client option with a 30,000 ms default,
  reject unknown or invalid values through `:eacl/invalid-config`, and retain it in client options.
- [x] 3.2 Implement one targeted revision-wait helper that dereferences `d/sync conn t` with the
  configured bound and reports typed freshness-unavailable timeout data without falling back to an
  older DB.
- [x] 3.3 Route both explicit `at-least-as-fresh` selection paths through the shared bounded wait
  helper and preserve requested/observed revision diagnostics.
- [x] 3.4 Change coherent live capture to compare the connection DB basis with the coordinator
  snapshot's `:observed-t`, release the barrier and wait when behind, then retry capture.
- [x] 3.5 Verify that successful capture derives dependency generations from the same coordinator
  snapshot paired with the caught-up DB and that cache I/O/evaluation stay outside the barrier.

## 4. Cache-Independent Historical Selection

- [x] 4.1 Refactor pagination setup so an authenticated cursor is decoded and validated before its
  database basis is selected.
- [x] 4.2 Implement a historical DB selector that catches the local connection up when necessary
  and returns `d/as-of` for cursor and `at-exact-snapshot` bases without falling forward.
- [x] 4.3 Resolve historical request inputs and coerce historical results against the selected
  historical DB instead of the live boundary DB.
- [x] 4.4 Build or bind schema state from the historical DB during replay so a later
  `write-schema!` cannot alter an older page or exact authorization result.
- [x] 4.5 Keep matching exact pages and recursive continuations as fast paths, then replay
  deterministically from history on cache miss, eviction, disabled caching, or recoverable provider
  failure.
- [x] 4.6 Remove current-proof/current-basis expiry checks that reject otherwise valid lookup and
  `read-relationships` cursors, while retaining identity, query, order, TTL, and boundary
  validation.
- [x] 4.7 Return typed snapshot-unavailable or cursor-expired only when the requested historical DB
  genuinely cannot be reconstructed, including requested revision data.

## 5. Cache Failure Containment

- [x] 5.1 Add nested exception containment around `record-provider-error!` so observability failure
  cannot escape a failed lookup or publication handler.
- [x] 5.2 Add conservative safe wrappers for cache capabilities and every provider operation used
  by live authorization paths.
- [x] 5.3 Replace direct capability and provider calls in recursive continuation, completed-result,
  count, and `can?` paths with the safe wrappers.
- [x] 5.4 Ensure an exact cache failure falls through to historical evaluation and a live cache
  failure falls through to authoritative computation.

## 6. Relationship Mutation Semantics

- [x] 6.1 Validate relationship update operations against `#{:create :touch :delete}` before
  resolving endpoints and centralize `:eacl/unsupported-operation` construction.
- [x] 6.2 Add a committed relationship-retraction counter that filters transaction-report datoms by
  `:added false` and the two v7 relationship tuple attribute entity ids.
- [x] 6.3 Accumulate actual committed retractions across `delete-object!` batches while retaining
  changed-dependency publication and the final batch token.

## 7. Documentation and Verification

- [x] 7.1 Document authenticated Zed-token configuration, stable shared keys for multi-instance
  backends, rotation overlap, random-default portability limits, and rejection of unsigned tokens.
- [x] 7.2 Document that authentication does not prevent replay or authorize exact historical
  access, and recommend backend-selected `at-least-as-fresh` for frontend-returned tokens.
- [x] 7.3 Document `:consistency-sync-timeout-ms`, historical cursor replay, cache-failure fallback,
  and the restored v7.3-compatible pagination guarantee.
- [x] 7.4 Run all regular EACL test namespaces through the running nREPL and confirm zero failures
  and zero errors.
- [x] 7.5 Run OpenSpec strict validation for `harden-v7-4-cache-consistency` and reconcile any
  implementation-driven contract changes before release.
- [x] 7.6 If performance validation is explicitly requested, run the heavy pagination benchmark
  namespace through nREPL and compare cache-hit and historical-replay work bounds.

## 8. Adversarial Review Follow-up

- [x] 8.1 Add regressions for cached lookup coercion after entity deletion and authenticated page
  cursor rejection across databases.
- [x] 8.2 Add regressions for recursive cache namespace isolation and reverse-continuation rule
  graph admission weight.
- [x] 8.3 Bind page-token v5 to database identity, coerce older cached lookup pages at their own
  historical basis, namespace recursive physical keys, and charge retained reverse rules.
- [x] 8.4 Update public documentation, run the focused and regular nREPL suites, and validate this
  OpenSpec change strictly.
