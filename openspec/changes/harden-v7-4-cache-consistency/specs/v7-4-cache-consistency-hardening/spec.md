## ADDED Requirements

### Requirement: Live cache proofs and database values are coherent

EACL SHALL evaluate and cache a live authorization result only from a Datomic database value that
has observed every relationship revision represented by the accompanying coordinator proof. A
shared coordinator MUST remain correct when participating clients use different Datomic
connections whose local observations advance at different times.

#### Scenario: Reader connection lags a completed relationship deletion

- **WHEN** one participating connection commits and publishes a relationship deletion
- **AND** another participating connection still observes a basis older than that published
  revision
- **THEN** the lagging client waits for or otherwise obtains a database value at least as new as
  the published proof before evaluating
- **AND** it does not cache the old relationship state under the new proof

#### Scenario: Writer begins after coherent capture

- **WHEN** a reader captures a database value and relationship proof before a writer enters the
  coordinator mutation barrier
- **THEN** the reader may finish against that captured database value
- **AND** a later live request cannot reuse its result after the writer publishes a relevant
  revision

#### Scenario: Lagging connection cannot reach the published proof

- **WHEN** a participating connection cannot observe the coordinator's published relationship
  revision within the configured wait bound
- **THEN** EACL returns a typed freshness-unavailable error
- **AND** it neither evaluates nor publishes a live result with a mismatched database and proof

#### Scenario: Unrelated Datomic transaction advances the reader

- **WHEN** a captured database value is newer than the relationship proof only because of
  transactions unrelated to EACL relationships
- **THEN** EACL may evaluate against that database value using the unchanged relationship proof
- **AND** the unrelated basis advancement alone does not invalidate a live cache entry

### Requirement: Pagination is independent of cache availability

Every EACL pagination cursor SHALL remain pinned to the database identity, historical basis,
operation, query shape, ordering, and schema semantics selected by its first page. If an exact
cached page or continuation is unavailable, EACL MUST reconstruct the pinned Datomic value and
deterministically replay from that value. Cache enablement, eviction, admission, expiry, or
provider failure MUST affect latency only when the pinned value remains reconstructable.

#### Scenario: Cache is disabled between lookup pages

- **WHEN** a caller requests the next lookup page with a valid cursor while caching is disabled
- **AND** unrelated Datomic transactions have advanced the connection since the first page
- **THEN** EACL reconstructs the cursor's historical basis and returns the correct next page
- **AND** it does not return snapshot-unavailable merely because exact-result caching is disabled

#### Scenario: Relationship changes between lookup pages

- **WHEN** a relationship affecting the lookup changes after the first page
- **THEN** the next page is evaluated from the cursor's original historical relationship state
- **AND** the enumeration neither mixes snapshots nor fails solely because the live proof changed

#### Scenario: Read-relationships cursor survives basis advancement

- **WHEN** a valid `read-relationships` cursor is continued after any later Datomic transaction
- **THEN** EACL reads the next page from the cursor's original historical basis
- **AND** it does not expire the cursor merely because the current basis differs

#### Scenario: Cached continuation is available

- **WHEN** a matching exact cached page or recursive continuation remains available
- **THEN** EACL may use it instead of historical replay
- **AND** the returned page is identical to evaluation at the cursor's pinned snapshot

#### Scenario: Historical snapshot cannot be reconstructed

- **WHEN** a valid cursor's database identity and basis pass validation but its exact historical
  value cannot be reconstructed
- **THEN** EACL returns a typed snapshot-unavailable or cursor-expired error with the pinned
  revision
- **AND** it never falls forward to a newer database value

### Requirement: Exact consistency can use Datomic history

An `at-exact-snapshot` authorization read SHALL first use matching cache state when available and
SHALL otherwise evaluate against a reconstructable Datomic database at the requested basis.
Historical evaluation MUST use the schema semantics at that basis rather than the client's newer
schema cache.

#### Scenario: Exact result is not resident in cache

- **WHEN** a valid exact token names a retained Datomic basis but no matching cache entry exists
- **THEN** EACL evaluates the request against `d/as-of` at that basis
- **AND** it returns the same authorization result that an uncached evaluation at that basis would
  return

#### Scenario: Schema changes after requested basis

- **WHEN** the client's live schema generation is newer than an exact token or cursor
- **THEN** historical replay reconstructs or uses schema state belonging to the requested basis
- **AND** it does not evaluate the historical relationships with the live schema generation

#### Scenario: Exact basis is unavailable

- **WHEN** neither valid cache state nor a reconstructable historical database exists for an exact
  request
- **THEN** EACL returns a typed snapshot-unavailable error
- **AND** it does not evaluate against the current database

### Requirement: Cache integration failures do not escape live authorization reads

For every live consistency mode that permits authoritative recomputation, EACL SHALL treat failure
of any cache-store operation, capability probe, metric update, or provider-error callback as a
cache miss or rejected publication. Cache observability MUST be isolated from authorization
correctness, including while handling a prior provider exception.

#### Scenario: Lookup and error telemetry both throw

- **WHEN** a cache provider throws during lookup
- **AND** its provider-error recording hook also throws
- **THEN** EACL suppresses both cache-side failures and computes the authoritative result
- **AND** the telemetry exception does not escape the authorization call

#### Scenario: Capability probe throws

- **WHEN** a provider throws while EACL probes support for opaque continuation values
- **THEN** EACL treats opaque continuations as unsupported
- **AND** it uses deterministic replay without changing the result

#### Scenario: Cache publication or metrics throw

- **WHEN** cache storage, eviction accounting, hit accounting, or miss accounting throws after an
  authoritative result is computed
- **THEN** EACL returns the authoritative result
- **AND** subsequent requests remain eligible for uncached evaluation

#### Scenario: Exact cache access fails but history is available

- **WHEN** an exact cache access fails and the requested Datomic basis is reconstructable
- **THEN** EACL evaluates against the historical basis
- **AND** the provider failure does not become snapshot-unavailable

### Requirement: Zed tokens are cryptographically authenticated

Every `:zed/token` issued by EACL SHALL carry a versioned HMAC-SHA-256 authentication tag covering
the token format, key identifier, database identity, and monotonic Datomic basis under a
domain-separated signing input. Token verification MUST select a configured verification key by
key identifier, compare authentication tags in constant time, and authenticate the encoded payload
before interpreting its database or revision or performing cache, synchronization, or historical
database work. The token format need not conceal its claims, but MUST detect modification by a
frontend or other untrusted client.

#### Scenario: Frontend returns an unmodified token

- **WHEN** a frontend returns a token issued by a backend sharing the configured Zed-token keyring
- **THEN** EACL authenticates the token and applies its revision according to the
  backend-selected consistency mode

#### Scenario: Frontend changes the revision or database

- **WHEN** an untrusted client changes any payload byte representing `:t` or `:db` without a valid
  signing key
- **THEN** EACL rejects the token with `:eacl/invalid-zed-token`
- **AND** EACL performs no `d/sync`, `d/as-of`, cache lookup, or authorization evaluation using the
  modified claims

#### Scenario: Frontend changes the tag or key identifier

- **WHEN** an untrusted client changes the authentication tag or selects an unknown or mismatched
  key identifier
- **THEN** EACL rejects the token with `:eacl/invalid-zed-token`
- **AND** it does not fall back to another key or an unsigned parser

#### Scenario: Unsigned draft token is returned

- **WHEN** a caller submits a draft v1 unsigned Zed token or a structurally valid payload without
  an authentication tag
- **THEN** EACL rejects it with `:eacl/invalid-zed-token`
- **AND** it never interprets the unsigned revision

#### Scenario: Authenticated token belongs to another database

- **WHEN** a correctly authenticated token produced for a different database is passed unchanged
  to a client
- **THEN** EACL rejects it with `:eacl/invalid-zed-token` and reason `:database-mismatch`

#### Scenario: Signing keys rotate

- **WHEN** a keyring contains a new current signing key and retained prior verification keys
- **THEN** EACL signs new tokens with the current key identifier
- **AND** it accepts an older token only while that token's key remains in the verification keyring

#### Scenario: Multiple backend instances accept one frontend token

- **WHEN** backend instances share the same stable Zed-token keyring and current key identifier
- **THEN** a token issued by one instance authenticates on another instance
- **AND** a token signed with an instance-local random default is not assumed portable

#### Scenario: Authenticated token requests a future revision

- **WHEN** a valid at-least-as-fresh token names a revision newer than the local connection
- **THEN** EACL waits specifically for that revision for no longer than the configured finite bound
- **AND** it returns a typed freshness-unavailable timeout if the revision is not observed

#### Scenario: Valid old token is replayed as a freshness bound

- **WHEN** a frontend replays an authentic older token and the backend applies
  `at-least-as-fresh`
- **THEN** EACL may answer at that revision or any newer revision
- **AND** the token cannot force evaluation older than the backend's locally selected snapshot

#### Scenario: Backend elects exact historical consistency

- **WHEN** a backend applies `at-exact-snapshot` to an authentic older token
- **THEN** EACL may evaluate the authenticated historical revision
- **AND** documentation states that token authentication does not itself authorize historical
  access or prevent replay, so the backend MUST independently control use of exact consistency

### Requirement: Unsupported relationship operations fail uniformly

EACL SHALL validate a relationship update operation against the supported set before resolving its
relationship or constructing transaction data. Every unsupported value, including
`:unspecified`, `nil`, and arbitrary keywords, MUST throw `ExceptionInfo` with type
`:eacl/unsupported-operation` and include the rejected value as `:operation`.

#### Scenario: Unspecified operation is submitted

- **WHEN** a relationship update has operation `:unspecified`
- **THEN** EACL throws `:eacl/unsupported-operation`
- **AND** no relationship transaction is submitted

#### Scenario: Unknown keyword is submitted

- **WHEN** a relationship update has an operation outside `:create`, `:touch`, and `:delete`
- **THEN** EACL throws `:eacl/unsupported-operation` containing that value
- **AND** it does not leak a raw `case` no-matching-clause exception

#### Scenario: Operation is absent

- **WHEN** a relationship update omits `:operation`
- **THEN** EACL throws `:eacl/unsupported-operation` with `:operation nil`
- **AND** validation occurs before endpoint resolution

### Requirement: Object deletion reports committed relationship retractions

`delete-object!` SHALL set `:retracted-datoms` to the number of EACL forward and reverse
relationship datoms that the committed transactions actually retracted. Attempted no-op retractions
MUST NOT contribute to the count.

#### Scenario: Complete relationship pair is deleted

- **WHEN** both stored halves of one relationship exist and `delete-object!` removes them
- **THEN** `:retracted-datoms` is `2`

#### Scenario: Only one relationship half survives

- **WHEN** one stored half of a relationship was already absent and `delete-object!` removes the
  surviving half
- **THEN** `:retracted-datoms` is `1`
- **AND** the attempted retraction of the absent half is not counted

#### Scenario: Object deletion is repeated

- **WHEN** `delete-object!` runs after all relationships touching the object have already been
  removed
- **THEN** `:retracted-datoms` is `0`

#### Scenario: Deletion is split into batches

- **WHEN** object deletion requires multiple transactions
- **THEN** `:retracted-datoms` is the sum of matching committed retraction datoms across every
  successful batch
- **AND** the returned token names the final committed batch
