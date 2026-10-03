## ADDED Requirements

### Requirement: An intersection generates its candidates from an operand no wildcard reaches
The candidate cover of an expression follows every operand of a union, the left operand of an exclusion and one operand of an intersection, its anchor. A cover reads a wildcard branch when it follows a relation that declares `T:*`, directly, through an arrow, or through a permission. When an intersection has an operand whose cover reads no wildcard branch, sealing SHALL select such an operand as the anchor, whatever the operands are named, in whatever order they are written, and whether a wildcard operand is a relation, an arrow, a permission reference, a union or an exclusion. The selection SHALL be a function of the permission expressions and relation definitions alone and MUST NOT read relationships, caches or request state. `lookup-resources` and `count-resources` of such an intersection, with or without `:count-limit`, SHALL read relationships in proportion to the subject's own relationships in the anchor's cover, not to the resources a wildcard relationship reaches. Among operands on the same side of this rule the structural cost order SHALL decide.

#### Scenario: A named operand that follows a subscription to a wildcard
- **WHEN** `open = view & subscribed`, `subscribed = subscription->everyone`, `everyone` declares `user:*`, every one of N ledgers has a subscription holding `user:*`, and a user owns 5 of them
- **THEN** `lookup-resources` and `count-resources` of `open` for that user return those 5 ledgers and report the same work at N = 50 and N = 500
- **AND** they answer at N = 1,500 under the default traversal limits

#### Scenario: Name and position of the wildcard operand
- **WHEN** the operand is named `zsubscribed`, or written first (`subscribed & view`), or written as the arrow `subscription->everyone`
- **THEN** the sealed anchor is `view` in every case

#### Scenario: A wildcard relation on the resource
- **WHEN** `open = view & subscriber` and `subscriber` declares `user:*`, alone or beside `user`
- **THEN** the sealed anchor is `view`

#### Scenario: Two arrows to the wildcard
- **WHEN** `open = view & subscribed`, `subscribed = subscription->subscriber`, the subscription's `subscriber = plan->subscriber`, and the plan's `subscriber` declares `user:*`
- **THEN** the sealed anchor is `view`

#### Scenario: Every operand reads a wildcard
- **WHEN** `both = subscriber & public` and both relations declare `user:*`
- **THEN** the anchor is the operand the structural cost order selects

#### Scenario: Recursion that reaches no wildcard relation
- **WHEN** `loop_bounded = subscriber & loop_union` and `loop_union = owner + loop_bounded`
- **THEN** `loop_union` has no wildcard cover and is the anchor of `loop_bounded`

### Requirement: Plans that read no wildcard branch are unchanged by the anchor rule
An operator plan whose closure follows no relation that declares a wildcard SHALL keep the costs, anchors, fingerprint and cursors it had before this rule. An operator plan with a node whose cover reads a wildcard branch SHALL record that in the node's sealed cost, inside the plan fingerprint; a cursor issued for the plan another anchor seals MUST be refused with `:eacl.pagination/invalid-cursor`.

#### Scenario: A concrete relation and a recursive permission
- **WHEN** `gated = eligible & tree`, `tree = viewer + parent->tree`, and no relation declares a wildcard
- **THEN** the anchor is `eligible` and no sealed cost carries a wildcard mark

#### Scenario: A cursor of the earlier anchor
- **WHEN** a page of `open = view & subscribed` was produced by the plan that generated from `subscribed`, and its cursor is presented to the plan that generates from `view`
- **THEN** the request fails with `:eacl.pagination/invalid-cursor` and `:reason :operator-scope-mismatch`

### Requirement: A lookup whose cover reads no wildcard lists its subjects without the touch cover
When the cover of a permission declares no wildcard for the requested subject type, `lookup-subjects` and `count-subjects` SHALL list the cover's subjects, each decided exactly, with no `*` entry. Every subject that holds the permission SHALL be among them, including a subject that holds another operand only through the wildcard.

#### Scenario: Owners of a ledger a wildcard subscription opens
- **WHEN** `open = view & subscribed`, a ledger has two owners and a subscription holding `user:*`
- **THEN** `lookup-subjects` of `open` returns the two owners and no `*` entry, and `count-subjects` returns 2

### Requirement: A generator answers for every subject type
A lookup or count whose generator is an intersection or exclusion of relations SHALL answer for every subject type. The merge of one relation scan per operand SHALL run only for a subject type that every operand declares, each through a relation of its own; for any other subject type the lookup SHALL take the generic cover and decide each candidate exactly. A request MUST NOT fail because an operand does not declare the requested subject type.

#### Scenario: An exclusion whose subtracted relation does not declare the subject type
- **WHEN** `unbanned = viewer - banned`, `viewer` declares `user | agent`, `banned` declares `agent`, and a user is a viewer of a document
- **THEN** `lookup-resources`, `count-resources`, `lookup-subjects` and `count-subjects` of `unbanned` for users return that document and that user, as the check does

#### Scenario: The anchor rule selects such an operand
- **WHEN** `open = subscriber & (viewer - banned)`, `subscriber` declares `user:* | agent:*`, and the exclusion is the sealed anchor
- **THEN** a user's and an agent's listings, counts and subject listings of `open` equal their checks
