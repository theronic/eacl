## ADDED Requirements

### Requirement: Formal semantics include conjunction and stratified negation
The versioned executable backend-independent semantics SHALL model typed expression facts, multi-premise positive rules, finite least-fixed-point evaluation per positive stratum, and exact negative premises that reference only completed lower strata. It SHALL define the same check, lookup, count, and failure denotation as the public operator specification.

#### Scenario: Positive recursive conjunction
- **WHEN** a finite grounded program derives intersection premises in different waves
- **THEN** the formal least fixed point derives the conjunction exactly when every premise holds

#### Scenario: Stratified exclusion
- **WHEN** an upper rule has a negative premise in a completed lower stratum
- **THEN** the formal semantics derives the upper fact exactly when the positive premises hold and the lower fact is absent

### Requirement: Candidate-cover and witness evaluation are proved exact
The formal model SHALL prove for every valid finite acyclic expression that the sealed cover contains every result, every emitted cover candidate has its declared witness, the exact predicate accepts if and only if the root denotes the candidate, and filtering the cover preserves deterministic order and exact uniqueness.

#### Scenario: Nested randomized shape has a theorem-level contract
- **WHEN** an expression contains operators nested under named permissions and supported arrows
- **THEN** cover filtering is proved equal to its mathematical denotation rather than justified only by test cases

### Requirement: Physical operator kernels refine one generic evaluator
Seekable direct-leaf intersection, monotone anti-join, scalar probes, vector predicates, adaptive batching, density-aware range merge, and sparse galloping seeks SHALL each have a mechanically checked or source-digested refinement to the generic witness evaluator, including decisions, sequence, logical progress, bounds, and error partitions.

#### Scenario: Sparse/dense strategy boundary
- **WHEN** the physical density rule selects different kernels on two batches
- **THEN** both kernels refine the same aligned scalar predicate and public trace

### Requirement: Anchor-gated recursive joins are sound, complete, and bounded
The formal model SHALL prove anchor-gated state observationally equivalent to ordinary multi-premise least-fixed-point propagation, including non-anchor-before-anchor arrivals, duplicate facts, typed entity identity, checkpoint/resume, termination, and declared retained-state bounds.

#### Scenario: Anchor never arrives
- **WHEN** only non-anchor facts exist for many entities
- **THEN** the proof permits no parent derivation and establishes that no per-parent join state is required for those entities

### Requirement: Negative absence and cache reuse are proved fail-closed
Formal cache and exclusion models SHALL prove that only completed exact lower-stratum false results can authorize exclusion, that complete signed proofs invalidate on any relevant positive or negative dependency change, and that cache hits remove only equivalent work.

#### Scenario: Incomplete negative mutation control
- **WHEN** a model mutation treats timeout, active recursion, or a bounded prefix as false
- **THEN** the assurance gate produces a counterexample and rejects the mutation

