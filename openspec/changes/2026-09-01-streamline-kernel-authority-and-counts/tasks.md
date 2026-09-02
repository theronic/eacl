Conventions: as in the cleanup change — nREPL tests only, `bin/formal
source-closure` after public-root edits, CLJS suite last on its JVM.

## 1. Host-native consistency authority

- [x] 1.1 Add `verified/host-decide` (validate-input! → portable/decide →
      validate-result!, no selection/crossing) and route
      `consistency.cljc/decide` through it for `:consistency-plan` and
      `:consistency-validation` on both platforms. Verify: consistency +
      relay + cache/v3 suites green; decision values unchanged.
- [x] 1.2 Update `verified_authority_suite` required operations to
      `#{:cursor-continuation :relationship-page}` and its docstring.
      Verify: `run-nonbenchmark!`-equivalent accounting logic green on a
      spot backend (full suite in CI).
- [x] 1.3 Update `eacl.formal.consistency-boundary-benchmark` to the host
      authority model: keep the latency ceiling and identical-decision
      assertions, assert zero generated crossings for the fixture path.
      Verify: gate passes; crossing counters read zero.
- [x] 1.4 Update `formal/assurance_contract.clj` consistency operation:
      entry points name `eacl.engine.portable-decisions` fns +
      `eacl.consistency/selection-plan`; note the generated model as
      offline oracle. Verify: dafny-cleanup gate (entry-point resolution)
      green.
- [x] 1.5 Sweep for other consumers of consistency crossing counts
      (`record-kernel-crossing!` observers, characterization pins).
      Verify: grep + affected suites green.

## 2. Streaming recursive counts

- [x] 2.1 Capture paged-path outputs for the fixture matrix (bounded,
      exact, empty, single-page, multi-page truncated) before the change.
      Verify: recorded in the change dir or as test fixtures.
- [x] 2.2 Implement the internal count page (cover-edge in,
      `{:count :cover-edge :more?}` out; no outer digests/edges/page-info)
      and rewrite `recursive-operator-count` on it, preserving per-page
      budgets and deadline checks. Verify: matrix outputs identical;
      operator lookup/recursive suites green.
- [x] 2.3 Add a regression test asserting a multi-page recursive count
      computes zero outer semantic-scope digests. Verify: test green,
      fails against the paged implementation.

## 3. Certification and evidence

- [x] 3.1 Battery + Datalevin suite + DataScript CLJS suite (last) green.
      Verify: 0 failures/errors each.
- [x] 3.2 Differential nREPL suites + mutation-control + strict replay
      67/67 + source-closure + reflection gate green. Verify: exit 0 each.
- [x] 3.3 After-benchmarks on the same host/session: consistency gate
      (p50s, median-p95, crossings) and the 1,500-match recursive count
      (p50/p90/min). Verify: recorded beside the before-numbers; count
      identical; no gate regression.
