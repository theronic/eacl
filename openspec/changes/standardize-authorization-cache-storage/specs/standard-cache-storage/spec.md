# standard-cache-storage Specification

## Purpose

Define one small, deterministic, cross-runtime retention boundary for EACL's
completed immutable cache values without making eviction policy part of
authorization semantics.

## ADDED Requirements

### Requirement: Shared cache storage is a count-bounded LRU partial map

Every enabled shared cache tier MUST be a finite partial map with a validated
positive cross-runtime safe-integer entry capacity. Every factory call MUST use
an empty seed; restore MUST insert validated entries sequentially rather than
depending on nonempty-seed trimming or tied seed priorities. A successful
resident lookup MUST atomically apply explicit membership, value capture, and the
standard cache library's LRU hit transition to the local cache atom, and
inserting an absent key into a full tier MUST evict the least recently used
resident entry. The immutable value returned by a successful lookup MUST remain
usable by that reader even if a later transition evicts its mapping. Disabling
caching MUST bypass the store rather than constructing a zero-capacity policy
object.

For the portable key and value domain, Clojure and ClojureScript MUST produce
the same resident keys and values for the same sequential trace of lookups and
absent-key insertions. Library-private priority maps, ticks, or recency state
MUST NOT enter EACL configuration, snapshots, metrics, or formal semantics.

#### Scenario: A hot entry survives cold churn

- **WHEN** one resident key is read repeatedly while distinct cold keys enter a full LRU tier
- **THEN** each hit updates the library-managed recency state
- **AND** a less recently used cold mapping is evicted before the hot key

#### Scenario: The runtimes replay one trace

- **WHEN** Clojure and ClojureScript replay the same portable hit/miss trace at the same capacity
- **THEN** every lookup outcome and the final resident mapping are equal

#### Scenario: Capacity or tick approaches a runtime numeric boundary

- **WHEN** configuration exceeds the portable safe-integer capacity contract or a CLJS LRU tick approaches `Number.MAX_SAFE_INTEGER`
- **THEN** invalid capacity is rejected and library recency normalization preserves the same hot-key eviction order

### Requirement: Cache identity is one opaque composite key

Every shared entry MUST be addressed by one immutable versioned composite key
containing a storage domain and that domain's complete canonical identity.
Cache storage MUST treat the key as opaque. Authorization answer and subproblem
key constructors MUST additionally commit to tier, exact or managed reuse mode,
source lifecycle, schema and engine identity, operation semantics, and either
the complete exact selected-basis identity or complete managed dependency
proof. Continuation, cursor-codec, and derived-schema domains MUST commit to
their own complete validation identity without inventing exact/managed fields.
All answer-affecting inputs MUST appear in the key or in a collision-checked
canonical identity named by it. Storage MUST NOT implement nested generations,
proof-aware lookup, aliases, or key matching of its own.

Membership MUST be tested explicitly before reading a value so every valid
cacheable value is distinguishable from absence. Validated publication and
validated off-side restore MUST be the only supported transitions that install
an authorization or derived-artifact mapping. Every generic publisher that
accepts an already computed authorization or derived artifact MUST require an
explicit callable artifact validator and MUST NOT supply a trusting default or
overload. Closed continuation and cursor-codec constructors MAY instead enforce
their fixed artifact contract internally; they MUST NOT expose an unchecked
generic insertion path. These ingress paths MUST establish the expected artifact
type, operation value, key agreement, and ABI before insertion. An exact resident hit then MUST be an
ordinary lookup by its complete key without repeating shape, operation, or ABI
validation. A managed resident hit MUST additionally reject a value whose
immutable computation revision is later than the request's selected revision.
Application access to or mutation of private cache records, library values, or
backing atoms is outside the supported contract.

#### Scenario: Exact bases differ

- **WHEN** two otherwise identical requests select different immutable basis identities
- **THEN** their composite keys differ and neither can observe the other's exact entry

#### Scenario: Managed proof differs

- **WHEN** any schema generation or relevant relationship dependency identity changes
- **THEN** the managed composite key changes and the prior value is a miss

#### Scenario: A value has the wrong artifact ABI at ingress

- **WHEN** completed publication or snapshot restore receives a value that fails operation-specific or ABI validation
- **THEN** EACL rejects that insertion and never makes the value resident

#### Scenario: A low-level publisher omits its validator

- **WHEN** internal code calls an answer, subproblem, or derived-artifact publisher without an explicit callable validator
- **THEN** the publisher rejects the call before inserting a mapping
- **AND** there is no compatibility overload that silently trusts the value

#### Scenario: A validated exact value is read repeatedly

- **WHEN** supported ingress installed a value under its complete exact key
- **THEN** later exact hits return it by membership without repeating artifact validation
- **AND** each hit still applies the standard library's LRU recency transition

### Requirement: Authorization miss computation is independent and publication contains no computation

An authorization cache miss MUST leave computation owned by the requesting operation under
its own snapshot, deadline, cancellation, and limits. Only a completely
computed, immutable, validated value MAY be offered for retention. Publication
MAY perform one local atomic absent-key swap. Atom contention MAY retry only
the pure library cache-state transformation; it MUST NOT rerun validation,
semantic computation, externalization, or any application callback. If the key
became resident, the request skips its insertion and returns its own completed
value. EACL MUST NOT use cache loaders, single-flight promises, delays, locks,
or wrapped lookup-and-compute APIs.

Eviction, a lost publication race, disabled caching, or an unavailable tier
MUST only change future reuse. None may change the current value, error,
ordering, selected snapshot, cursor, or semantic work limit.

#### Scenario: Concurrent identical misses

- **WHEN** two requests miss the same composite key concurrently
- **THEN** both compute independently and return cache-free-equivalent values
- **AND** publication runs no request computation inside the atomic state transform

#### Scenario: Publication observes unrelated atom contention

- **WHEN** the tier state changes while an already completed value is being inserted
- **THEN** only the pure absent-key LRU transform may be retried against current state
- **AND** the request computation is not repeated or adopted by another request

### Requirement: Lifecycle replacement makes late publication unreachable

Each client cache lifecycle MUST own fresh cache-tier atoms. Expiry, restore,
or source-incarnation replacement MUST atomically replace the outer lifecycle
reference rather than clearing or mutating old tiers in place. An in-flight
request MAY continue to use an immutable value or tier reference captured from
the old lifecycle, but any late publication through that reference MUST remain
unreachable from the installed lifecycle.

#### Scenario: Expiry races completed computation

- **WHEN** expiry installs lifecycle `L1` while a request still holds `L0`
- **THEN** that request may finish and attempt publication only against `L0`
- **AND** no request that captures `L1` can observe the late value

### Requirement: Large completed pages are not retained

A completed public page containing more than 1,000 result items MUST remain
computable and returnable but MUST NOT enter exact or managed shared
completed-answer storage. The common answer-publication boundary MUST use the
already materialized result count and report a distinct ineligible outcome;
storage MUST NOT recursively estimate arbitrary values or describe item count
as bytes. This retention rule MUST NOT clamp, truncate, or otherwise change the
public page. Projection, denotation, continuation, checkpoint, proof, traversal,
and other artifacts retain their existing authoritative semantic bounds.

#### Scenario: A caller requests a 10,000-item page

- **WHEN** current public limits permit EACL to compute a valid 10,000-item page
- **THEN** EACL returns the same page as cache-disabled evaluation
- **AND** it does not publish that completed page under either exact or managed keys
