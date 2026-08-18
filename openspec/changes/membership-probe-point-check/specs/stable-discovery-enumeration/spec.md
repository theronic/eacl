# stable-discovery-enumeration Specification

> This delta targets a requirement introduced by the in-progress
> `adopt-stable-discovery-enumeration` change; archive that change first or
> fold this delta into it. The header text matches its requirement name.

## MODIFIED Requirements

### Requirement: Point checks and counts keep operation-appropriate plans

Point authorization MUST remain anchored to the known subject and resource; it MUST NOT enumerate the root universe to answer one known-resource question, and it MUST NOT enumerate the subjects that hold the permission either. A point check SHALL be decided by a membership-probe search over the sealed plan's reverse index: direct relation rules for the subject's type are decided by one exact-bound probe (the scan strictly after `subject − 1` with limit one equals the subject iff the tuple exists), self-permission rules descend on the same entity, arrow rules enumerate only the resource's intermediates and then probe or descend, with a visited set on [node entity]. Its Boolean MUST equal membership of the subject in the exhaustive reverse-discovery denotation. Its cost is bounded by the number of reachable intermediates and the reducer budgets (`:max-admissions` visited states, `:max-transitions` visits, `:max-commands` fetches, `:max-values` fetched values, `:max-stack` depth), which fail typed exactly like the reducer's. Exact count MUST exhaust the exact history-free stable-discovery reducer by default; its result equals the cardinality of the complete denotation. Count MAY use an order-insensitive specialization only if that route is independently proven equal to the lookup denotation. Stable discovery order is required for paginated enumeration, not for internal aggregation.

#### Scenario: Point authorization

- **WHEN** the caller asks whether one known resource is authorized
- **THEN** EACL does not enumerate unrelated roots merely to reuse the forward page engine

#### Scenario: Popular resource

- **WHEN** a resource has one million direct subjects and the caller checks a subject that is not among them
- **THEN** the check issues one probe for that relation and does not enumerate the subjects

#### Scenario: Probe equals the reverse denotation

- **WHEN** the same subject and resource are checked by the membership-probe search and by exhaustive reverse discovery
- **THEN** both return the same Boolean

#### Scenario: Exact count specialization

- **WHEN** a backend-specific count route is selected
- **THEN** its returned count equals the cardinality of the complete stable-discovery denotation
