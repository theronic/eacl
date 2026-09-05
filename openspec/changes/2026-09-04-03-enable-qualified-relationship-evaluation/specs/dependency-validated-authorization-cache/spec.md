## ADDED Requirements

### Requirement: Qualifier decode reuse is source- and version-scoped
A decoded qualifier MAY be reused only under a source lifecycle, qualifier eid, supported format, and immutable qualifier version derived from creation `t` or a certified persisted equivalent (or a stronger exact proof) that together exclude eid reuse or content replacement. Managed reuse that omits a repeated entity read SHALL additionally depend on the supported-writer/non-reuse contract and the owning Relation proof. Unknown-writer sources SHALL require exact-snapshot or content-proof validation.

#### Scenario: Same immutable qualifier on a later basis
- **WHEN** a supported-writer source advances for unrelated data and the same qid/qualifier-version remains referenced
- **THEN** its decoded qualifier may be reused while expiry and Caveat evaluation still run for the new request

#### Scenario: Touch replaces qualifier
- **WHEN** `:touch` installs a new qid and advances the Relation version
- **THEN** no result or decoded-qualifier lookup aliases the old qid as the new qualifier

#### Scenario: Unknown writer mutates qualifier content
- **WHEN** a source cannot certify qualifier immutability
- **THEN** qid/qualifier-version alone cannot authorize cross-basis reuse and exact/content proof is required

### Requirement: Caveat result cache identity is context complete
Any cached authorization value depending on Caveats SHALL distinguish canonical request context, evaluator/profile identity, selected schema/Caveat definition, Relationship-bound context, and result kind. Conditional residuals and missing fields MUST NOT alias definite values.

#### Scenario: Request context differs
- **WHEN** two checks share graph/time but have different request context
- **THEN** their final Caveat-dependent cache entries cannot collide

#### Scenario: Bound context changes through touch
- **WHEN** a new qualifier binds different context
- **THEN** tuple/Relation/qualifier proof changes invalidate old result reuse

#### Scenario: Conditional is replayed as definite
- **WHEN** a cache provider returns a conditional value under a definite-value kind or incomplete identity
- **THEN** EACL rejects it as a miss or malformed entry and recomputes

### Requirement: Temporal cache reuse requires a stability certificate
Every reusable value whose denotation can depend on expiring Relationships SHALL carry a certified half-open evaluation-time interval. A request may reuse it only when its captured time lies inside that interval and all ordinary schema/Relation/qualifier/context proofs match.

#### Scenario: Grant reaches expiry on unchanged basis
- **WHEN** a cached grant's certificate ends at `valid-until` and a later request occurs at that instant
- **THEN** the entry is not reused even though no database version changed

#### Scenario: Ban reaches expiry
- **WHEN** a cached denial depends on an active subtracting Relationship whose expiry is its certificate end
- **THEN** a later request at/after expiry recomputes and may grant

#### Scenario: Permanent decisive witness
- **WHEN** the formal witness proves the result independent of every expiring alternative under the selected request
- **THEN** its certificate may be unbounded without scanning unrelated graph data

#### Scenario: Certificate completeness is unavailable
- **WHEN** evaluation cannot certify a safe interval for a reusable intermediate/result
- **THEN** EACL returns the exact result but does not publish it for managed temporal reuse

### Requirement: Temporal correctness does not depend on cache eviction
Expiry timers, eager removal, listeners, and bounded-store eviction SHALL affect performance only. Cache acceptance SHALL always validate the captured time against the authenticated certificate.

#### Scenario: Expired entry remains resident
- **WHEN** a cache entry remains physically present after its certificate end
- **THEN** lookup rejects it and recomputes

#### Scenario: Eviction callback is delayed
- **WHEN** an optional timer/callback does not run
- **THEN** authorization correctness is unchanged
