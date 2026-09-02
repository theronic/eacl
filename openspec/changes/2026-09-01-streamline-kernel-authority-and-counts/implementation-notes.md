# Implementation notes (2026-09-01, tranche 1: consistency authority + streaming counts)

## Benchmarks (same host/session; before = cleanup commit c3e3b3ef)

Consistency decision boundary (consistency-boundary-benchmark/run-gate!,
5 independent trials, 40 samples x 2000 reps each):

|                                   | before                  | after                   |
|-----------------------------------|-------------------------|-------------------------|
| trial p50s (ns/decision)          | 490/514/652/492/529     | 350/347/346/360/356     |
| median of trial p50s              | 514 ns                  | 350 ns  (-32%)          |
| median p95                        | 1,677 ns                | 1,407 ns (-16%)         |
| generated crossings per decision  | 1                       | 0 (gate-asserted)       |

Recursive operator count (1,500 matches via one exclusion + parent->view
recursion, DataScript, engine-direct, operator routing enabled):

|                                   | before      | after                    |
|-----------------------------------|-------------|--------------------------|
| p50                               | 624.8 ms    | 590.7 ms (-5.5%)         |
| min                               | 605.3 ms    | 570.4 ms (-5.8%)         |
| outer scope digests per count     | one per page| 0 (regression-tested)    |
| cover seal / proof identity /     | per page    | once per count           |
| anchor resolution                 |             |                          |

Count profile (617 ms total): operator-recursive/evaluate-cached-many
595 ms (six 256-candidate batches at 90-125 ms; ~380 us per candidate on a
three-scan derivation); command-identity 15 ms; direct dispatch 7 ms.
The demand-solve per-candidate constant is the filed follow-up dominator
(proposal, streaming-count bullet).

## Certification (final tree)

Battery 1,138 tests / 40,439 assertions 0F/0E; DataScript CLJS 0/0;
Datalevin 51/4,348 0/0; differential suites 11,265 assertions 0/0;
mutation-control + dafny-cleanup + replay + characterization 4,205 0/0;
strict replay 67/67 (18,228); source-closure exit 0; reflection gate exit 0;
consistency gate :passed with the new zero-crossing assertion.

## Updated gates/consumers (same change)

verified_authority_suite + CLJS runner required ops ->
#{:cursor-continuation :relationship-page}; consistency-boundary gate
models host authority and asserts zero crossings; consistency_source_test
crossing expectation flipped to {}; assurance-contract consistency
entry-points name portable-decisions/decide (generated model = offline
oracle). Spec deltas under specs/.
