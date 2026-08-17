# keyset-recursive-pagination

> Supersession notice (EACL v8 release stack): the later
> `demand-bounded-authorization-execution` change replaces the original
> sorted-recursive-keyset design. This file is aligned to the shipped contract
> so active OpenSpec changes do not present contradictory normative behavior.
> The authoritative detailed requirements are in
> `incremental-recursive-pagination`.

## ADDED Requirements

### Requirement: Public ordering is route specific
Certified acyclic enumeration SHALL use strictly ascending positive internal
EIDs. Recursive enumeration SHALL preserve the versioned generated logical
order and MUST NOT sort a completed recursive denotation by EID. Evaluation
mode and cache state MUST NOT change either public order.

#### Scenario: Explicit completion
- **WHEN** explicit completion obtains closure through the fixed-point evaluator
- **THEN** an acyclic public route is canonicalized once to ascending EID order
- **AND** a recursive public route retains generated logical order

### Requirement: Cursor identity binds the route's ordering ABI
An acyclic page SHALL use a `:lookup-eid` boundary. A recursive page SHALL use a
`:recursive-logical` boundary binding its ordinal and external result identity.
A cursor MUST be rejected before traversal when its route, order version,
ordinal, or boundary identity is incompatible with the selected immutable
snapshot and normalized execution contract.

#### Scenario: Recursive page cursor shape
- **WHEN** a recursive page has a continuation
- **THEN** its authenticated edge binds `:recursive-logical`, the emitted ordinal, and the boundary result identity
- **AND** continuation resumes or deterministically replays that same logical order

### Requirement: Continuation requires observational equivalence
A cursor SHALL NOT continue on another selected immutable snapshot unless an
exact snapshot rule or complete dependency and ordering proof establishes the
same public sequence and boundary interpretation. EACL MUST NOT silently restart or
reinterpret a cursor across a relevant route, schema, relationship, identity,
or ordering change.

#### Scenario: Relevant write between pages
- **WHEN** a relevant write changes the cursor's dependency or ordering proof
- **THEN** EACL uses a compatible exact snapshot when the consistency contract permits it
- **AND** otherwise returns a typed stale-cursor or consistency-conflict error

### Requirement: Completed-denotation membership preserves semantics
Point membership against a completed denotation SHALL accept only a validated,
unique sequence under the artifact's bound public order. Acyclic artifact
admission requires strictly ascending positive EIDs; recursive artifacts
require unique positive EIDs in generated logical order. No logarithmic bound
is claimed for recursive logical-order membership.

#### Scenario: Point reuses completed traversal
- **WHEN** a compatible completed artifact is already resident
- **THEN** `can?` returns the same Boolean as cache-disabled demand evaluation
- **AND** cache reuse performs no new authorization traversal

### Requirement: Denotation completion is never cache implicit
A complete denotation SHALL be eligible for publication only after natural demand exhaustion or
an explicit `:evaluation :complete-denotation` request. Cache availability,
counting, repeated observation, or publication policy MUST NOT continue a
stopped demand traversal.

#### Scenario: Count then list
- **WHEN** a count naturally exhausts or explicitly completes and publishes a compatible denotation
- **THEN** a later compatible list may reuse that artifact
- **AND** a bounded count that stops at its sentinel publishes no complete denotation

### Requirement: Backward work is explicit
Recursive `:last N :before cursor` SHALL use deterministic bounded prefix replay.
A bare recursive `:last N` SHALL require explicit complete-denotation
evaluation because finding the suffix requires exhaustion.

#### Scenario: Bare last on a recursive root
- **WHEN** a demand request supplies `{:last k}` without `:before`
- **THEN** EACL returns a typed complete-evaluation-required error before traversal
- **AND** the same request may run only when the caller selects `:evaluation :complete-denotation`
