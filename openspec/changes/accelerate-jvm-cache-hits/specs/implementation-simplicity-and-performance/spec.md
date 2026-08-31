## MODIFIED Requirements

### Requirement: Measured performance is separately regression-gated

Reproducible benchmarks MUST measure raw resident lookup, fully realized Core
count and page hits, misses, and first/continued pages with enough warmup and
samples to expose steady-state latency and allocation. Uncontended warmed JVM
completed hits with a locally available immutable basis MUST satisfy the
absolute sub-millisecond Core ceiling. Results MUST state the runtime, backend,
result cardinality, sample count, and measured boundary so raw storage, Core,
service encoding, and network time are not conflated. Relative comparisons are
supporting diagnostics; functional correctness and the absolute Core ceiling
remain the product gates.

#### Scenario: Candidate exceeds a performance threshold

- **WHEN** a warmed benchmark repeatedly exceeds its absolute completed-hit
  ceiling
- **THEN** the release gate fails with raw samples, stage attribution,
  allocation, and the measured boundary
- **AND** no deterministic proof claim is rewritten to imply a wall-clock proof

#### Scenario: Platform-sensitive artifact representation

- **WHEN** supported compiler, JVM, operating-system, or compression
  implementations produce semantically equivalent artifacts with different
  byte representations
- **THEN** exact reference measurements retain their complete environment as
  evidence but MUST NOT be enforced as cross-platform equality
- **AND** the release gate enforces reviewed absolute and incremental ceilings,
  production-graph exclusions, and semantic parity independently
- **AND** the allowed variance MUST NOT permit any applicable ceiling or
  forbidden-runtime invariant to be bypassed
