# EACL v8 consistency, cache, and rollout operations

EACL v8 separates two questions that transaction counters cannot safely
collapse:

1. Which immutable database value satisfies the request's consistency mode?
2. Is an answer or cursor created on another value observationally equivalent
   on the selected value?

Snapshot selection uses an authenticated causal mutation identity.
Cross-revision reuse uses a complete schema and relationship proof. Datomic
`basis-t` and DataScript/Datahike `:max-tx` are order and waiting hints only;
they are never proof of ancestry or authorization equality.

## Consistency modes and backend capabilities

Every mode selects one immutable database value before resolving object ids,
deriving dependencies, reading a cache, or evaluating authorization.

| Backend | `:fully-consistent` | `:minimize-latency` | `:at-least-as-fresh` | `:at-exact-snapshot` |
| --- | --- | --- | --- | --- |
| Datomic | bounded zero-argument `d/sync` barrier | current local Peer DB | bounded `d/sync conn t`, then mandatory mutation-anchor lookup | `d/as-of` at the authenticated basis, then graph-head verification |
| DataScript | current immutable value of the supplied serialized connection | current connection value | bounded polling of that same connection, then anchor lookup; no replication is implied | only when `:exact-snapshot-registry-size` configures a bounded immutable-DB registry |
| Datahike | current authoritative branch head only for a direct `:self` writer | current complete local value | bounded branch refresh/polling, then anchor lookup | retained `commit-as-db`, or temporal `as-of` when the commit graph is disabled and `:keep-history? true` |

A Datahike streaming or replicated reader without an authoritative branch-head
barrier does not advertise `:fully-consistent`. A DataScript connection cannot
catch itself up from another process; its at-least mode waits only for the
caller-supplied connection to acquire the mutation anchor. Unsupported
configuration/mode combinations fail before authorization.

Tokens are scoped to backend, native database/store identity, causal family,
and Datahike branch. A branch, restore, clone, or `reset-conn!` that reuses a
numeric transaction position does not pass unless it contains the token's
random mutation identity. Datahike merge parents and commit locators are
metadata for lineage and exact reconstruction; `:max-tx` is not lineage.

## Writer authority and proof modes

Configure `:coherence-authority` explicitly:

- `:managed` asserts that every write capable of changing an authorization
  result participates in EACL's atomic v3 mutation protocol. This includes
  schema, relationships, object identity, caveat inputs, and declared custom
  dependencies. Managed clients may issue read/write Zed tokens and use
  mutation-identity proofs.
- `:unknown` makes no causal-writer claim. It does not issue read tokens or
  offer causal/exact token modes. Cache validation defaults to canonical
  full-content proofs, which detect relevant out-of-band writes but cannot
  reconstruct missing ancestry.

`:proof-mode :auto` selects `:mutation` under managed authority and `:content`
otherwise. `:mutation` is rejected without managed authority. `:content`
commits all scoped schema definitions and relationship tuples, including both
physical Datomic relationship halves. `:none` evaluates without retaining
completed answers.

Listeners are not part of any correctness argument. They may feed metrics, but
listener counts never appear in tokens, schema keys, dependency proofs, cache
validity, or freshness selection.

## Token keys, lifetime, and retention

Portable DataScript/Datahike clients use `:security-key` or
`:security-keyring`; Datomic uses `:zed-token-key` or
`:zed-token-keyring`. Configure stable shared key material in every process
that must accept the same token or cursor. Use the keyring plus current key id
for overlap-based rotation.

The default token lifetime is 3,600 seconds. Mutation records carry an
additional 300-second retention grace by default. Override these with
`:token-ttl-seconds` and `:retention-grace-seconds`. Do not prune a mutation
record before its encoded expiry. The backend-specific
`eacl.<backend>.mutation/prune-expired!` removes expired non-head records;
schedule it only after clocks, configured TTLs, and the grace window have been
accounted for.

Database retention is a separate constraint:

- Datomic must retain the basis needed for exact `d/as-of`.
- DataScript retains only the configured number of immutable registry values.
- Datahike must retain commit records, or temporal history when that fallback
  is enabled. Branch deletion and storage GC can expire old commits.

A valid token with a missing causal anchor never falls back to transaction
number comparison. A cursor whose proof changed and whose exact database value
has expired returns `:eacl.consistency/snapshot-expired`.

## Cache theorem and proof lifting

The cache first selects snapshot `S`. A candidate computed at `C` is reusable
only when:

- its authenticated semantic key, source/branch scope, engine and adapter
  fingerprints, result kind, query identities, and configuration match;
- `S` contains `C`'s computation mutation anchor, preventing backward or
  sibling-history lifting;
- the complete dependency closure and selected schema/relationship proofs
  match; and
- the value shape and entry authenticator validate.

Every cross-revision hit recomputes the proof on `S`. `validated-at` is
telemetry, not a lease. `computed-at` remains the original computation point;
the response token identifies `S`.

Provider failures, corrupt or old entries, proof errors, and missing proofs
become misses evaluated on the already selected snapshot. Token
authentication, causal freshness, scope, and exact-snapshot failures remain
request errors. `eacl.cache/stats` exposes exact hits, causal lifts, proof
mismatches, future-history rejections, unauthenticated entries, no-proof
bypasses, and provider failures.

Use `eacl.cache/no-cache` for portable adapters or
`eacl.datomic.cache/no-cache` for Datomic to disable retention without changing
authorization semantics. A request-level `:cache? false` bypasses the
configured store for that call.

## Proof-equivalent cursors

Cursors are authenticated; Datomic cursors are additionally AES-GCM
encrypted. They bind the query, source scope, graph anchor, exact locator,
adapter/configuration identity, complete dependency-scope and proof digests,
stable position, and expiry.

Continuation follows this order:

1. select a snapshot satisfying the request, including any newer at-least
   floor;
2. rederive the complete dependency closure and proof;
3. continue on that selected snapshot when the proof equals the cursor proof,
   rebasing new cursors to its graph;
4. on a mismatch, use the verified original exact snapshot only when no newer
   at-least floor forbids moving backward; otherwise return
   `:eacl.consistency/cursor-consistency-conflict`.

Cursor failures are intentionally distinguishable:

- `:eacl.pagination/invalid-cursor` — authentication, scope, query, format, or
  configuration mismatch;
- `:eacl.pagination/expired-cursor` — authenticated envelope lifetime elapsed;
- `:eacl.pagination/stale-cursor` — proof changed and exact fallback is not
  supported;
- `:eacl.consistency/snapshot-expired` — the exact locator is no longer
  reconstructable;
- `:eacl.consistency/cursor-consistency-conflict` — a newer causal floor has a
  different proof.

## Initial v8 configuration

The v3 token, cursor, and cache formats have no downgrade or dual-format mode.
Portable tokens use `eacl_z3_`, portable cursors use `eacl_c3_`, and portable
completed entries use `eacl_ce3_`. Datomic retains the `eacl4_` encrypted
prefix, but payloads without v3 proof context are rejected.

Client construction performs the idempotent mutation-journal migration. Use
`:coherence-authority :managed` only when every schema, relationship,
object-identity, caveat, and custom-data writer uses EACL helpers or the
exported mutation transaction-data builders. Otherwise keep authority
`:unknown`; `:proof-mode :auto` then uses complete content proofs.

During key rotation, publish the new current key while retaining old read keys
until all tokens and cursors issued under them have expired. Mutation pruning
and backend history/commit garbage collection must respect the configured
token and cursor lifetime.

## Failure diagnostics

For incidents, record the typed error plus backend, source/branch, requested
and observed order hints, graph anchor, exact locator, proof mode, coherence
authority, cache metric, and key id. Never log opaque token/cursor contents or
signing/encryption key material.
