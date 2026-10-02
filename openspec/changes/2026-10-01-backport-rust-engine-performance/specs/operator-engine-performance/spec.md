# Spec Delta

## Purpose

Operator and union-only permissions cost about what their reads cost on the
eacl-rust benchmark graph, without changing any permissionship, residual or
fault, and with sound certificates. Budgets compare medians measured in one
JVM run of `eacl.bench.drive-parity-test`, so machine load cancels.

## ADDED Requirements

### Requirement: A benchmark gate on the eacl-rust graph
EACL SHALL keep a `^:benchmark` DataScript gate that generates the eacl-rust
benchmark graph (SplitMix64 generator, identical draw order) at 10⁴ and 10⁵
relationships, and on demand at 10⁶. The gate SHALL assert the relationship
count and FNV-1a checksum the Rust generator reports for the same scale, and
SHALL assert every workload's answer before it measures it. Its budgets SHALL
be written before any sampling of the implementation they gate.

#### Scenario: Generator parity
- **WHEN** the gate generates the 10⁵ graph
- **THEN** it holds 98,799 relationships with checksum `dcbb5c45b6fd0c5d`

#### Scenario: Answers before timings
- **WHEN** a workload's answer differs from its expected value
- **THEN** the gate fails without reporting that workload's timing

### Requirement: Operator checks cost what their union twin costs
For each check workload of the gate (direct, group, deep, denied-deep,
denied-typical), the median of the operator permission
`view = (viewer + owner + parent->view) - banned` SHALL be at most 1.5 times
the median of its union twin `view_any = viewer + owner + parent->view` for
the same subject and resource. A check that a direct relationship decides
SHALL issue at most 4 adapter commands.

#### Scenario: Direct grant decides first
- **WHEN** `u1` holds `viewer` on `d7` directly
- **THEN** `check-permission` of `view` returns has-permission after at most 4 adapter commands
- **AND** its median is at most 1.5 times the median of `view_any` for the same request

#### Scenario: Fault semantics unchanged by evaluation order
- **WHEN** one union child faults and another holds definitely
- **THEN** the union holds, whatever order the children are evaluated in

### Requirement: Operator counts and pages cost what their generator costs
On the 10⁵ graph, the median exact count of `view` for the broad subject
SHALL be at most 1.5 times the median exact count of `view_any` for the same
subject. The median first page of 50 of `view` SHALL be at most 2 times that
of `view_any`. A candidate the generator reached through plain relationships
only SHALL NOT be re-proven on the generator's operand.

#### Scenario: Broad count
- **WHEN** the broad subject counts `view`
- **THEN** the count equals the number of documents a walk of `view` returns
- **AND** its median is at most 1.5 times the median count of `view_any`

#### Scenario: Qualified candidates are still decided exactly
- **WHEN** a candidate's only path to the generator crosses an expiring or caveated relationship
- **THEN** its permissionship, residual and certificate equal `check-permission` for that document at the same time and context

### Requirement: Counts need no order
Exact and bounded counts SHALL be computed from structural sure and maybe
sets, deciding exactly only the members that are possible but not sure. On
the 10⁵ graph the median exact count of `view_any` for the broad subject
SHALL be at most 1 µs per counted result.

#### Scenario: Sure members need no exact decision
- **WHEN** every relationship on some path from the subject to a resource is plain
- **THEN** the resource is counted without an exact evaluation

#### Scenario: Bounds contain the exact answer
- **WHEN** any resource is decided exactly at any time and context
- **THEN** a has-permission resource lies in the maybe set and every member of the sure set has permission

### Requirement: A batch shares one evaluation
`check-permissions` SHALL decide demands that share a subject, permission
and caveat context with one evaluation table. The median 256-document batch
for one subject SHALL be at most 32 times the median single check of the
same subject, and its results SHALL equal the demands' individual checks in
order, including residuals and the index of the first failing demand.

#### Scenario: Equal to individual checks
- **WHEN** a batch mixes allowed, denied, conditional and faulting demands and duplicates
- **THEN** each result equals `check-permission` of that demand alone

### Requirement: Recursive membership is decided from the smaller side
Membership of a subject in a recursive union-only permission reached through
an arrow SHALL cost at most a constant factor more than the smaller of the
subject's upward closure and the resource's downward closure. On the gate's
denied-through-nested-groups check (10⁵), the operator check SHALL issue at
most 64 adapter commands.

#### Scenario: Subject in no group
- **WHEN** a subject belongs to no group and a document is visible only through a 300-group tree
- **THEN** the check is denied without reading the group tree

### Requirement: Recursion through operators costs what its reads cost
A permission that recurses through an operator non-linearly SHALL be decided
by a tabled evaluator whose work is linear in the goals and reads it demands.
On a 256-folder chain, the median check of
`reach = viewer + (parent->reach & link->reach)` SHALL be at most 4 times the
median check of `reach_union = viewer + parent->reach_union`.

#### Scenario: Two grounded witnesses on one cycle
- **WHEN** a cycle has two grounded witnesses with different deadlines
- **THEN** the decision holds until the later deadline, as the stratified semantics define

### Requirement: Cache-off walks do not replay
A walk with `:cache? false` SHALL resume each page from its cursor without
re-enumerating earlier candidates. On the 10⁵ graph, the median cache-off
walk of the broad subject SHALL be at most 1.25 times the median cache-miss
walk.

#### Scenario: Page by page
- **WHEN** the broad subject walks `view` in pages of 1000 with `:cache? false`
- **THEN** every page's work is bounded by the page's candidates plus its lookahead, not by its position in the walk
