# single-flight-coordination Specification

## Purpose
TBD - created by archiving change eacl-v8-root-fixes. Update Purpose after archive.
## Requirements
### Requirement: Wedge-free coordination
The subproblem single-flight coordinator SHALL never enter a state in which no participating thread can make progress. Specifically, no thread SHALL block on the computation-slot semaphore while holding any flight result lock, and no thread SHALL wait unboundedly on a flight whose owner cannot obtain a computation slot.

#### Scenario: Convergent burst beyond the inflight bound
- **WHEN** `max-inflight` is configured to N and more than N threads concurrently miss on overlapping subproblem keys, including a thread that becomes flight owner of a key other permit-holding threads subsequently join
- **THEN** every request completes (value or typed error) within a bounded deadline; no permanent wedge occurs; the deterministic regression test (N=2, three threads, shared nested key — the schedule that wedges the current implementation) passes

#### Scenario: Read-only joiners under contention
- **WHEN** `lookup!` joins an in-flight computation while the executing side is saturated at `max-inflight`
- **THEN** the lookup completes when the flight completes or fails; it SHALL NOT hang indefinitely on a flight whose owner is queued for a slot

### Requirement: Bounded concurrent execution
At most `max-inflight` top-level subproblem computations SHALL execute concurrently per client. Any documented fallback that executes outside a slot (for example a timed-acquire inline fallback, if that mechanism is chosen) SHALL be counted in a dedicated metric so the effective bound is observable.

#### Scenario: Saturation accounting
- **WHEN** the coordinator is saturated and additional misses arrive
- **THEN** executing computations never exceed `max-inflight` plus the documented fallback allowance, and `cache-stats` reports slot waits and any out-of-slot executions distinctly

### Requirement: Honest hit metrics
Single-flight joins SHALL NOT be reported as cache hits. Hit counters SHALL count only lookups served from completed cached state; join waits SHALL be counted separately.

#### Scenario: Join is not a hit
- **WHEN** a caller joins an in-flight computation and waits for its completion
- **THEN** `:single-flight-waits` increments and tier hit counters do not

