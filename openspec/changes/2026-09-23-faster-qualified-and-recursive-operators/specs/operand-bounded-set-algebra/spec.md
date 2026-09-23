# Spec Delta

## Purpose

Set-algebra permissions (intersections, exclusions, and unions that contain
them) cost about what their operands cost, without changing any
authorization answer, result order within a walk, or cursor guarantee.

## ADDED Requirements

### Requirement: Operators over union-only recursive operands cost what their operands cost
When every recursive permission an intersection or exclusion depends on
recurses only through unions, EACL SHALL decide each operand as it would
decide that operand alone. It SHALL generate the operator's candidates from
one operand's own traversal. On the account fixtures (minimal and ledger
schemas, 636 and 2,077 accounts, warm), each median SHALL be at most twice
the sum of its operands' medians:

- a complete intersection lookup walk, against the operands' walks;
- an intersection check, against the operands' checks.

#### Scenario: Owner lookup of an intersection over recursive operands
- **WHEN** a subject holding both operands on the root of a chart walks `delete = delete_granted & read_account`
- **THEN** the walk returns exactly the accounts in both operands' answers
- **AND** its median is at most twice the sum of the two operands' median walks

#### Scenario: Recursion through an operator is not delegated
- **WHEN** a permission recurses through an intersection or exclusion that is not linearly guarded
- **THEN** EACL evaluates it exactly with the stratified recursive evaluator

### Requirement: Expiring evidence keeps operator costs bounded by their operands
When operands are decided for many resources of one subject, a resource
whose decisive evidence includes unexpired expiring relationships SHALL be
decided without a separate full evaluation per resource. On the account
fixtures, a subject whose only access is an unexpired share of the whole
chart SHALL walk `delete = delete_granted & read_account` in at most twice
the sum of its operands' median walks. Its check SHALL take at most twice
the sum of its operands' median checks.

#### Scenario: Whole-chart expiring share
- **WHEN** a subject's only relationships are unexpired expiring `deleter` and `reader` grants on the root of a chart
- **THEN** its `delete` lookup returns every account, each with has-permission
- **AND** the walk's median is at most twice the sum of the operands' median walks

#### Scenario: Certificate of a time-limited grant
- **WHEN** a resource's operand holds only through paths that each include an expiring relationship
- **THEN** the grant's certificate ends at the latest first-expiry among those paths, which is the instant the grant stops holding
- **AND** a request at or after that instant does not reuse the answer

#### Scenario: Permanent witness
- **WHEN** some path to the resource has no expiring or conditional relationship
- **THEN** the operand is definitely true with an unbounded certificate, whatever expiring alternatives exist

#### Scenario: Conditional or faulty evidence
- **WHEN** the operand's answer depends on caveated evidence, a fault, or evidence that can make access appear later
- **THEN** EACL returns the same permissionship, residual and fault as an exact evaluation of that resource alone

### Requirement: Union-rooted operator permissions generate candidates like their operands
When a recursive operator permission's root, or its anchor chain, reaches a
union, a relation or an arrow, EACL SHALL generate candidates from a
traversal of the chain's terms. That traversal SHALL flatten nested unions,
intersection anchors and exclusion left operands, and SHALL use each
union-only operand's own traversal. It SHALL NOT use one generated node per
expression node. Acyclic operator permissions keep their keyset traversal
and cursors. On the account fixtures,
`delete_top = deleter + (delete_granted & read_account)` SHALL walk in at
most 1.25 times the median walk of `delete = delete_granted & read_account`
for the same subject.

#### Scenario: Union at the root
- **WHEN** a subject walks `delete_top`
- **THEN** the walk returns exactly `deleter ∪ (delete_granted ∩ read_account)`
- **AND** its median is at most 1.25 times the median walk of `delete`

#### Scenario: Union as an intersection anchor
- **WHEN** a permission `(a + b) & c` over union-only `a`, `b` and `c` is walked
- **THEN** its candidates come from the traversal of `a + b`
- **AND** the walk returns exactly `(a ∪ b) ∩ c`

#### Scenario: Single delegated operand keeps its cursors
- **WHEN** an operator permission's flattened generator is exactly one union-only operand
- **THEN** its walks, their order, and cursors minted before this change remain valid

### Requirement: Linearly guarded recursion through an operator costs what its relaxation and guards cost
A recursive component is linearly guarded when each intersection and
exclusion node in it has exactly one child that depends on the component. Its
other children must be relations, permissions outside the component, or
arrows to either. EACL SHALL decide such a component for one subject by
following its recursion only through edges whose lower-stratum conditions
hold for that subject, and SHALL generate lookup candidates from its
flattened generator.

On the account fixtures, with
`inherited = reader + (parent->inherited & eligible)`,
`reachable = reader + parent->reachable` and `eligible_only = eligible`, the
following medians SHALL be at most twice the named sums:

- a subject's `inherited` walk, against the sum of its `reachable` and
  `eligible_only` walks;
- its `inherited` check, against the sum of its `reachable` and
  `eligible_only` checks.

#### Scenario: Owner eligible everywhere
- **WHEN** a subject that reads the root and is eligible on every account walks `inherited`
- **THEN** the walk returns every account
- **AND** its median is at most twice the sum of the `reachable` and `eligible_only` median walks

#### Scenario: Guard excludes part of the chart
- **WHEN** a subject reads one account and is eligible only within one subtree
- **THEN** its `inherited` walk returns exactly the accounts reachable from its reader grant through eligible accounts

#### Scenario: Time-dependent subtraction in a guard
- **WHEN** a recursion guard is an exclusion whose subtracted relationship expires
- **THEN** the resource's answer and certificate equal the exact stratified evaluation, and no denial is reused past the expiry

#### Scenario: Non-linear recursion
- **WHEN** an intersection in the recursive component has two children that depend on the component
- **THEN** EACL answers exactly with the stratified recursive evaluator

### Requirement: New routes change no answer and invalidate superseded cursors
For every schema and snapshot, a new route SHALL return the same results
and permissionship as the stratified least-fixed-point semantics. This holds
for lookups, counts, checks, reverse lookups and reverse counts of
intersections, exclusions and unions containing them. A walk's order SHALL
be deterministic and independent of page size and cache state. A recursive
operator cursor minted under a different generator SHALL be rejected as an
invalid cursor before any traversal.

#### Scenario: Randomized semantics
- **WHEN** random schemas mixing union-only recursion, nested operators, union roots and linearly guarded recursion are evaluated over random relationships with cycles
- **THEN** every answer equals an independent stratified least-fixed-point evaluation
- **AND** forward walks return the same sequence at every page size

#### Scenario: Superseded cursor
- **WHEN** a client presents a recursive operator cursor minted under a generator this change replaced
- **THEN** EACL rejects it as an invalid cursor and performs no traversal for it

### Requirement: Budgets and refinement are enforced
The budgets in this capability SHALL be asserted by a benchmark gate whose
budgets were written before any sampling of the implementation. Each new
decision procedure SHALL have a machine-checked model. It SHALL also have an
executable refinement check that compares production decisions, and the
retained answers, against a transcription of that model on random inputs. A
registered mutation control SHALL show that each check fails on a defect it
guards.

#### Scenario: Budget regression
- **WHEN** a change makes a gated median exceed its budget
- **THEN** the benchmark gate fails and names the case

#### Scenario: Ignored guard
- **WHEN** the guarded search is mutated to follow an edge whose guard fails
- **THEN** the refinement check or the randomized semantics check fails on both runtimes
