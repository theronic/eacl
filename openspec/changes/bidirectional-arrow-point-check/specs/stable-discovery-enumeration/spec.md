# stable-discovery-enumeration Specification

> This delta refines the point-check requirement modified by
> `membership-probe-point-check`; archive order follows that change.

## MODIFIED Requirements

### Requirement: Point checks and counts keep operation-appropriate plans

Point authorization MUST remain anchored to the known subject and resource; it MUST NOT enumerate the root universe, MUST NOT enumerate the subjects that hold the permission, and MUST NOT pay one side's fan-in for an arm the other side decides more cheaply. A point check SHALL be decided by the membership-probe search over the sealed plan's reverse index; each two-layer arrow arm — an arrow to a relation, or an arrow to a permission whose every derivation is a base relation — SHALL be decided by an interleaved bidirectional intersection of the resource's via-set with the subject's holdings whose consumption is bounded by the smaller side plus one physical chunk per side, and whose Boolean is proven equal to the exhaustive reverse-discovery denotation's membership (`BidirectionalArrowIntersection.dfy`). Deadline and cancellation enforcement MUST run before every adapter command the check issues. An unrecognized sealed rule kind MUST fail closed with a typed error. Sealed plans MUST NOT be shared between database views that report the same source identity but can observe different schema definitions: reuse requires an ordinary view of a stamped schema generation; other views compile per call. Exact count MUST exhaust the exact history-free stable-discovery reducer by default; an order-insensitive specialization requires an independent denotation-equivalence proof.

#### Scenario: Widely shared resource

- **WHEN** a resource reaches 100,000 intermediates through one arrow and the subject holds one of them
- **THEN** the check answers within the smaller side's cost instead of enumerating the fan-in or failing a value budget

#### Scenario: Subject with many holdings

- **WHEN** the subject holds 100,000 intermediates and the resource reaches one
- **THEN** the check answers within the resource side's cost

#### Scenario: Filtered view isolation

- **WHEN** a `d/filter` view that hides definition datoms evaluates before or after the plain database at the same schema stamp
- **THEN** neither view is served a sealed plan compiled from the other

#### Scenario: Speculative basis isolation

- **WHEN** a `d/with` value and the later committed database share a basis on an unstamped database
- **THEN** neither is served a sealed plan compiled from the other
