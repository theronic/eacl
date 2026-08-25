## Purpose

Defines demand-driven operator plans that enumerate a proven cover, carry candidate witnesses, and decide exact membership without eager operand materialization.

## ADDED Requirements

### Requirement: Every acyclic operator plan has a sealed candidate cover
For every acyclic expression `E`, the sealed plan SHALL define a finite raw candidate cover `C(E)` such that the denotation of `E` is a subset of the denotation of `C(E)`, and a recursively exact generator `G(E)` whose emitted set equals the denotation of `E`. A leaf generator SHALL be exact, a union generator SHALL compose exact child generators, an intersection generator SHALL enumerate one exact selected child generator and filter unresolved operands, and an exclusion generator SHALL enumerate its exact left generator and filter exact right membership. Raw cover membership alone MUST NOT be treated as proof that a nested semantic child holds. Selection MUST be deterministic from sealed inputs and MUST NOT depend on cache state, request timing, or planning-only semantic reads.

#### Scenario: Nested cover contains every answer
- **WHEN** a nested union/intersection/exclusion expression is sealed
- **THEN** every authorized entity occurs in the sealed cover and no global entity universe is required

#### Scenario: Driver selection is replayable
- **WHEN** an operator cursor is resumed without compatible cache entries
- **THEN** the same sealed cover and candidate order are reconstructed without a selectivity pilot

### Requirement: Candidate witnesses remove redundant predicates
Every candidate emitted by a recursively exact child generator SHALL carry or reconstruct a bounded witness proving the semantic memberships completed by that child evaluation. A raw cover candidate SHALL carry only raw derivation evidence until its local exact predicate completes. Parent predicate evaluation SHALL test every unresolved expression obligation and SHALL accept a candidate if and only if it belongs to the parent denotation.

#### Scenario: Intersection anchor witness
- **WHEN** a candidate is generated from an intersection's selected child
- **THEN** that child is not probed again and all remaining children must complete true before emission

#### Scenario: Exclusion left witness
- **WHEN** a candidate is generated from an exclusion's left cover
- **THEN** left membership is treated as proven and the candidate is emitted only after exact right membership completes false

### Requirement: Vector predicates preserve scalar semantics and order
A vector predicate SHALL accept a bounded ordered vector of distinct typed candidates plus their witnesses and SHALL return an aligned decision vector exactly equal to independent scalar evaluation under the same immutable snapshot. It MAY reorder and group internal physical probes, but MUST map decisions back to input order and MUST publish no partial vector after cancellation, failure, or malformed backend response.

#### Scenario: Sorted physical schedule
- **WHEN** candidates in public generator order are internally sorted for index locality
- **THEN** the aligned decisions and emitted public order equal scalar evaluation in the original order

#### Scenario: Batch failure
- **WHEN** any physical subgroup fails before the vector decision is complete
- **THEN** no candidate from that vector is emitted or cached as a completed decision

### Requirement: Batching is demand-adaptive and fully charged
The evaluator SHALL begin with a bounded demand-sized batch, grow deterministically only when rejection requires more candidates, and never exceed the configured physical batch cap or remaining candidate window. Every physically probed candidate SHALL count against the appropriate work limits. Probe overread inside a completed physical batch MUST NOT advance the public cursor past the last logically consumed candidate.

#### Scenario: High-selectivity first page
- **WHEN** a page reaches its accepted lookahead sentinel in an early small batch
- **THEN** EACL stops without issuing an unconditional maximum-width batch

#### Scenario: Low-selectivity bounded progress
- **WHEN** rejected candidates reach the candidate window before the page fills
- **THEN** EACL returns the established bounded-progress state and a resumable logical boundary rather than exhaustion

### Requirement: Direct ordered specializations refine the generic plan
Any replacement of generic witness-aware filtering with seekable leapfrog/galloping intersection or monotone anti-join SHALL occur only when the selected operands have certified compatible order and bounds, and the specialization produces exactly the generic plan's decisions, result sequence, progress boundary, typed failures, and work-limit interpretation. Direct n-ary intersection SHALL position every operand at or above the current anchor and jump the driver to the maximum operand head; it MUST NOT be implemented as repeated binary filtering.

For direct n-ary intersection, zero result demand SHALL perform zero anchor rounds and zero operand or driver reseeks. For positive demand, the specialization SHALL return exactly the demand-bounded prefix of the generic sequence, execute at most one anchor round per initial driver value, execute at most one operand reseek per operand per anchor round, execute at most one driver reseek per anchor round, and therefore execute at most `(operand-count + 1) * initial-driver-cardinality` combined reseeks. Operand positioning SHALL stop after the first exhausted child without opening later children in that round.

#### Scenario: Demand stops k-way execution
- **WHEN** a seekable n-ary intersection has produced the requested number of results
- **THEN** it returns that exact generic prefix without positioning another operand or reseeking the driver

#### Scenario: Exhausted child stops later operand opens
- **WHEN** positioning an operand proves that the intersection has no remaining result
- **THEN** the specialization returns without opening any later operand in the sealed order for that round

#### Scenario: Sparse ordered intersection
- **WHEN** compatible direct operand streams have a large gap between successive common values
- **THEN** the specialization may inclusively reseek to the opposing head without linearly consuming the gap

#### Scenario: A nonselective operand precedes a selective operand
- **WHEN** a direct n-ary intersection contains an early identity-like operand and a later sparse operand
- **THEN** max-head k-way progress considers all current operand heads before advancing and avoids a complete intermediate driver scan

#### Scenario: Incompatible compound order
- **WHEN** a compound operand cannot certify sequence-compatible ordered progress
- **THEN** EACL uses the sealed cover and exact predicate path rather than opening every child stream

### Requirement: Exact count and bounded demand remain distinct
Bounded pages and bounded counts SHALL stop at their specified accepted sentinel, candidate window, or work limit. Exact count SHALL exhaust the exact operator iterator and MAY perform work proportional to the complete candidate cover, while retaining bounded memory and exact failure semantics.

#### Scenario: Exact count is not presented as a page bound
- **WHEN** an exact operator count traverses a large denotation
- **THEN** its exhaustive backend work is measured separately from first-page performance
