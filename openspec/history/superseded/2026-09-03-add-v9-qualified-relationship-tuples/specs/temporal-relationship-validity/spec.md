> **DEPRECATED / SUPERSEDED — 2026-09-04.** This unimplemented proposal is retained for historical review only. Its requirements and unchecked tasks are withdrawn from the implementation plan; do not apply or sync these deltas into the main specifications. Use the [replacement v9 proposal](../../../2026-09-04-add-v9-caveats-and-expiring-relationships/proposal.md).
>
> The replacement uses **seven-slot Caveat + expiry-only endpoint tuples**, removes scheduled activation and the mandatory expiration index, and retains time-aware cache/cursor checks. See the [review findings and preserved REPL evidence](../../review-2026-09-04.md). The original artifact follows unchanged.

## Purpose

Define native valid-time semantics for permanent, future, expiring, and bounded
EACL relationships without requiring scheduler writes at activation or expiry.

## ADDED Requirements

### Requirement: Half-open relationship validity

Every relationship SHALL have an optional valid-time interval represented by
inclusive `valid-from` and exclusive `valid-until` bounds. EACL SHALL normalize
both bounds to exact UTC epoch-millisecond integers in the inclusive portable range
`[-9007199254740991, 9007199254740991]` and SHALL interpret omitted
bounds as unbounded. A relationship is temporally active at `t` exactly when
`valid-from <= t < valid-until`, after ignoring an omitted inequality.

EACL SHALL reject zero-width and reversed bounded intervals before transaction
submission.

#### Scenario: Permanent relationship

- **WHEN** both validity bounds are absent
- **THEN** the relationship is temporally active at every representable
  `valid-at` instant

#### Scenario: Future relationship

- **WHEN** `valid-from` is later than the selected `valid-at`
- **THEN** the relationship is stored but has no authorization effect

#### Scenario: Expiring relationship

- **WHEN** `valid-at` is exactly equal to `valid-until`
- **THEN** the relationship is inactive

#### Scenario: Adjacent intervals

- **WHEN** one relationship interval ends at the exact instant another interval
  begins
- **THEN** the half-open definition produces neither an overlap nor an
  ambiguous boundary

#### Scenario: Invalid interval

- **WHEN** a relationship update supplies `valid-from >= valid-until`
- **THEN** EACL rejects it with a typed invalid-validity error
- **AND** writes no endpoint or auxiliary data

### Requirement: Trusted valid-time snapshots

Every top-level authorization, lookup, count, relationship-read, explanation,
and batch operation SHALL evaluate against one EACL-created snapshot containing
both one immutable database basis and one captured `valid-at` instant. When a
client creates the snapshot without an explicit administrative time, EACL SHALL
obtain `valid-at` from the client's configured trusted clock exactly once.

A client MAY reuse its immutable database pin for minimize-latency consistency,
but each top-level operation targeting the client SHALL pair that pin with a
freshly captured `valid-at`. Passing an explicit EACL snapshot SHALL freeze both
the database basis and its captured `valid-at`.

Speculative snapshots produced by `eacl/with` or `eacl/with-schema` SHALL retain
their parent's captured `valid-at` unless the caller explicitly creates a new
administrative temporal view through an EACL API.

#### Scenario: Clock crosses a boundary during evaluation

- **WHEN** wall-clock time passes a relationship boundary while one
  authorization operation is running
- **THEN** every subproblem in that operation uses the same captured
  `valid-at`
- **AND** the operation returns a deterministic snapshot result

#### Scenario: Batch evaluation

- **WHEN** one batch contains multiple permission checks
- **THEN** all checks use the same database basis and `valid-at`

#### Scenario: Minimize-latency pin is reused

- **WHEN** two client-targeted operations reuse the same immutable database pin
  at different wall-clock instants
- **THEN** each operation captures its own `valid-at`
- **AND** the second operation does not inherit the first operation's time

#### Scenario: Explicit snapshot is retained

- **WHEN** a caller evaluates the same explicit EACL snapshot more than once
- **THEN** every evaluation uses the snapshot's original database basis and
  `valid-at`

#### Scenario: Prospective relationship transaction

- **WHEN** a caller applies a scheduled relationship with `eacl/with`
- **THEN** the speculative snapshot contains the stored assertion
- **AND** authorization evaluates it at the snapshot's unchanged `valid-at`

#### Scenario: Administrative valid-time view

- **WHEN** an authorized caller asks EACL to evaluate an EACL snapshot at an
  explicit representable instant
- **THEN** EACL returns a new immutable temporal view
- **AND** does not accept an arbitrary native database value as the basis

### Requirement: Scheduled activation and expiration require no boundary write

Authorization SHALL derive relationship activity from stored validity bounds
and the selected `valid-at`. Correct activation and expiration MUST NOT depend
on a scheduler, timer, transaction at the boundary, listener callback, or
garbage-collection pass.

#### Scenario: Scheduled grant activates

- **GIVEN** a relationship was written on 3 September with `valid-from` on
  6 September
- **WHEN** a snapshot is selected before the start
- **THEN** the relationship is inactive
- **WHEN** a snapshot is selected at or after the start and before the end
- **THEN** the relationship is active without another database transaction

#### Scenario: Grant expires

- **WHEN** a snapshot is selected at or after a finite `valid-until`
- **THEN** the relationship is inactive even when its physical tuple remains in
  the database

#### Scenario: Delayed collector

- **WHEN** the expiration collector is stopped or delayed
- **THEN** expired relationships remain non-granting

### Requirement: Temporal qualifiers participate in all permission algebra

Validity filtering SHALL occur at the relationship-leaf boundary before an edge
contributes to union, intersection, exclusion, arrow traversal, recursion,
lookup, count, or expansion. Inactive positive and negative relationships SHALL
both be absent from the selected valid-time graph.

#### Scenario: Positive grant expires

- **WHEN** the only granting relationship expires
- **THEN** a permission that was true becomes false at the expiration boundary

#### Scenario: Exclusion expires

- **GIVEN** a permission is `viewer - banned`
- **WHEN** the only active `banned` relationship expires while `viewer`
  remains active
- **THEN** the permission changes from false to true without a database write

#### Scenario: Future arrow edge

- **WHEN** an intermediate relationship used by an arrow has not reached its
  `valid-from`
- **THEN** traversal does not follow that edge

#### Scenario: Temporal lookup and count

- **WHEN** a lookup or count runs at a selected `valid-at`
- **THEN** its result contains exactly the resources or subjects authorized in
  that temporal graph

### Requirement: Relationship APIs preserve stored and effective state

Relationship writes SHALL accept optional validity bounds independently of the
logical relationship identity. `read-relationships` SHALL be able to return the
stored normalized bounds and SHALL provide an option to select stored
assertions, effective-at assertions, or both with an explicit status.

A future or expired assertion SHALL continue to exist for `:create` conflict,
`:touch`, `:delete`, audit, and integrity purposes even when it is absent from
the effective authorization graph.

#### Scenario: Read scheduled assertion

- **WHEN** a stored-view relationship read matches a future assertion
- **THEN** it returns the assertion and normalized bounds with status
  `:scheduled`

#### Scenario: Read active assertion

- **WHEN** an effective-at read matches a relationship active at the selected
  `valid-at`
- **THEN** it returns the assertion with status `:active`

#### Scenario: Read expired assertion

- **WHEN** a stored-view read matches an expired assertion
- **THEN** it can return status `:expired`
- **AND** an effective-at-only read omits it

#### Scenario: Create after expiry but before collection

- **WHEN** `:create` targets the same logical relationship as an expired
  assertion still stored
- **THEN** it reports a relationship conflict
- **AND** the caller can use `:touch` to reschedule it

### Requirement: Temporal stability is explicit authorization metadata

Every internal authorization result that may enter a reusable cache or
continuation SHALL carry a conservative temporal stability interval containing
the selected `valid-at`. Within that interval, and only while all ordinary
dependency and structural-integrity proofs remain valid, the result SHALL be invariant under the passage
of time.

The interval for a composed permission SHALL be no wider than the intersection
of the intervals required by the witnesses and counter-witnesses used to prove
that result. An implementation MAY conservatively choose a narrower interval
or disable temporal reuse, but MUST NOT certify reuse across a possible
validity boundary.

#### Scenario: Cached denial before activation

- **WHEN** a denial is computed before a future granting relationship starts
- **THEN** its temporal stability ends no later than that start boundary
- **AND** it is not reused at or after the boundary

#### Scenario: Cached grant before expiration

- **WHEN** a grant depends on a relationship with finite `valid-until`
- **THEN** its temporal stability ends no later than that expiration
- **AND** it is not reused at or after the boundary

#### Scenario: Permanent dependency graph

- **WHEN** every relationship capable of affecting a result is permanent
- **THEN** its temporal stability MAY be unbounded

#### Scenario: Conservative fallback

- **WHEN** the engine cannot establish a complete temporal stability proof
- **THEN** it evaluates correctly but does not publish the result for
  cross-time reuse

### Requirement: Clock configuration fails safely

The client clock SHALL be deterministic for one invocation, return an exact
epoch-millisecond value inside EACL's cross-runtime safe range, and be sampled
only through the EACL temporal snapshot boundary. Invalid clock values SHALL
fail the request. EACL documentation SHALL require synchronized production
clocks when multiple peers authorize against one shared database.

#### Scenario: Invalid clock result

- **WHEN** the configured clock returns a non-integer, out-of-range, or
  decreasing value relative to the clock contract for that client
- **THEN** snapshot acquisition fails with a typed clock error
- **AND** authorization does not fall back to an unqualified graph

#### Scenario: Explicit test clock

- **WHEN** a test client uses a deterministic injected clock
- **THEN** boundary behavior can be tested without sleeping or scheduling
  transactions

#### Scenario: Multiple peers

- **WHEN** multiple EACL peers use one shared database
- **THEN** operational documentation states the permitted clock-skew model and
  the consequences near validity boundaries; any configured uncertainty policy
  evaluates the whole permission over the interval or returns a typed failure

### Requirement: Expiration collection is sparse and non-authoritative

A relationship with finite `valid-until` SHALL have one EACL-owned sparse
expiration-index value using the physical layout fixed by the converged
relationship-storage specification that can locate the exact qualified relationship without
scanning every endpoint tuple. Permanent and start-only relationships SHALL
have no expiration-index value.

Collection SHALL delete only assertions whose exclusive end is older than the
configured retention cutoff. It SHALL retract the exact endpoint pair, the
exact expiration-index value, and any singly-owned Caveat context entity in one
admitted transaction. Authorization MUST NOT consult this index when deciding
whether a relationship is active.

#### Scenario: Finite expiration is indexed

- **WHEN** a relationship is created or touched with finite `valid-until`
- **THEN** exactly one matching sparse expiration-index value exists

#### Scenario: Permanent relationship has no temporal index overhead

- **WHEN** a relationship has no finite `valid-until`
- **THEN** no expiration-index datom is stored for it

#### Scenario: Collection races with rescheduling

- **WHEN** a collector reads an expired entry and a concurrent `:touch`
  replaces that relationship with different qualifiers
- **THEN** exact-value guards prevent the stale collection plan from deleting
  the replacement
- **AND** the collector retries or reports a benign race

#### Scenario: Expiration entry reconstructs exact pair

- **WHEN** collection reads an expiration entry
- **THEN** its owner and eight components reconstruct the exact forward and reverse values
- **AND** current-state guards reject stale collection plans

#### Scenario: Missing index entry

- **WHEN** an expired relationship lacks its derived expiration-index value
- **THEN** authorization still denies it according to the authoritative tuple
- **AND** integrity reporting identifies the index mismatch

### Requirement: Historical and valid time remain distinct

EACL SHALL distinguish transaction-time selection from relationship valid-time
selection. On backends supporting historical database values, a temporal
authorization question SHALL be modeled as a database basis plus a separate
`valid-at` instant. Documentation SHALL state the retention limits for
reconstructing past authorization after physical collection.

#### Scenario: Bitemporal question

- **WHEN** an audit selects the database as known at transaction time `T` and
  evaluates relationships at valid time `V`
- **THEN** EACL keeps `T` and `V` as distinct authenticated inputs

#### Scenario: Physical collection and history

- **WHEN** an expired assertion has been physically collected
- **THEN** current authorization remains unchanged
- **AND** past reconstruction is available only to the extent guaranteed by the
  selected backend's retained history


### Requirement: Clock uncertainty cannot drop negative evidence

A configured skew or uncertainty policy MUST NOT make an individual relationship
inactive early as a generic fail-closed rule. EACL SHALL grant only if the whole
permission is invariantly granting throughout the selected uncertainty interval;
otherwise it SHALL return a typed clock-uncertainty failure.

#### Scenario: Exclusion ends inside uncertainty window

- **GIVEN** permission is `viewer - banned` and `banned` expires inside the clock uncertainty interval
- **WHEN** the result differs across that interval
- **THEN** EACL does not grant by expiring `banned` at the early edge
- **AND** returns the documented typed uncertainty failure
