# recursion-performance-gates

## ADDED Requirements

### Requirement: Populated-recursion fixtures and logical-work gates
The gate suite SHALL include populated-recursion fixtures (star, chain, mixed, and a broad-union schema with active recursive data) at diagnostic and acceptance sizes, exercising point checks (positive shallow/deep and negative), first page, continuation and non-continuation second pages, exact counts, and reverse lookups — through the genuinely recursive engine (a fixture whose recursive counters report zero recursive work SHALL fail the suite's self-check).

#### Scenario: Recursion actually exercised
- **WHEN** the populated-recursion suite runs
- **THEN** the recursive work counters are nonzero for the recursive scenarios, distinguishing this suite from the existing empty-recursion gates

#### Scenario: Exact logical-work envelopes
- **WHEN** each scenario completes with counters bound
- **THEN** kernel crossings satisfy the recorded crossing law, backend scans / schema proofs / plan compiles / nil-store key work / continuation resumptions / linearity each satisfy their recorded envelope, and every assertion names the counter it reads

### Requirement: Matched-v7 latency bound
Populated-recursion latency SHALL be gated against recorded same-host v7 baselines with a maximum ratio of 2.0× (tightening toward parity as the efficiency capabilities land), following the recorded-EDN pattern: logical work remains authoritative when timing variance exceeds harness tolerance, and gates run first in a fresh heap-bounded JVM.

#### Scenario: Latency regression
- **WHEN** a change pushes a populated-recursion scenario's warmed median above the recorded bound while logical work is unchanged
- **THEN** the failure is triaged as harness noise per the variance policy; if logical work also regressed, the gate fails the build

### Requirement: Cache-maintenance op-count invariants
Deterministic op-count invariants SHALL pin cache-maintenance complexity in fast (non-benchmark) tests: LRU record retention bounded by max(compaction floor, 2× entries); eviction probes bounded by evictions plus consumed stale records; continuation-store puts per walk bounded by pages+1; cursor-recovery kernel decisions bounded per resume. These SHALL run on every push.

#### Scenario: Regression to linear maintenance
- **WHEN** a change reintroduces O(n)-per-touch maintenance in any cache (the class fixed in the subproblem store and still present in sibling caches today)
- **THEN** an op-count invariant fails deterministically in the per-push suite, without relying on wall-clock benchmarks

### Requirement: Dormant gates wired into CI
The existing explorer enumeration acceptance gate and the TLA+ spec-mutation kill controls (`apalache-mutation-control`) SHALL run in CI, and the mutation ledger SHALL be regenerated so its recorded mutant count matches the registry.

#### Scenario: TLA mutant must die
- **WHEN** a TLA spec mutation config runs in the CI formal workflow
- **THEN** the model checker finds the counterexample (exit condition enforced); a mutant that survives fails the build

#### Scenario: Ledger consistency
- **WHEN** the mutation registry and the recorded ledger are compared in CI
- **THEN** counts and identities match (the current state — 103 registered vs 96 recorded — fails)
