# Safe-retraction benchmark — 2026-08-09

This report records non-gating, warmed DataScript evidence for the optional
safe entity-retraction transaction function. Structural CI tests separately
assert exactly two target-scoped endpoint reads and linear emitted operation
counts; the timings below are evidence, not absolute latency thresholds.

## Environment

- EACL checkout: `02bf461a` plus the OpenSpec implementation worktree
- Java: OpenJDK 26.0.2, 64-Bit Server VM
- Host: Apple Mac Studio, M4 Max, 14 cores, 36 GB RAM
- OS: macOS 26.4.1, arm64
- Backend: DataScript 1.7.8, in-process JVM
- Command: `clojure -M:dev -m eacl.bench.safe-retraction`

The runner is `eacl.bench.safe-retraction`. It records the first installed-IFn
expansion separately, prewarms both paths 20 times, then measures warmed
expansion and commits. The legacy comparison uses the current
`tx-delete-object` generator plus native entity retraction in one DataScript
commit. It is useful as a local work comparison but is not equivalent: it does
not include EACL v3 mutation-proof bookkeeping and it calculates cleanup before
submission rather than from transaction-start state.

## Results

All times are microseconds. Values are median / p95.

| Target degree | Atomic ops | Legacy generated ops | First atomic expansion | Warm atomic expansion | Warm legacy generation | Atomic commit | Legacy cleanup commit |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 5 | 0 | 1993.750 | 120.208 / 294.083 | 6.209 / 9.334 | 513.250 / 760.583 | 36.250 / 67.542 |
| 1 | 7 | 2 | 190.667 | 61.334 / 110.916 | 10.791 / 25.458 | 260.167 / 448.500 | 47.875 / 92.292 |
| 10 | 16 | 20 | 227.667 | 45.917 / 62.209 | 17.417 / 37.958 | 256.167 / 370.167 | 110.792 / 145.417 |
| 100 | 106 | 200 | 162.000 | 75.000 / 95.000 | 123.833 / 147.709 | 1002.208 / 1047.000 | 934.500 / 1157.084 |
| 1000 | 1006 | 2000 | 761.500 | 334.709 / 434.416 | 1084.417 / 1369.000 | 8670.000 / 10952.416 | 9511.333 / 9767.333 |

The fixed atomic overhead is the authenticated mutation anchor, graph CAS and
retention record. With one affected relation, emitted work is `degree + 6` for
non-zero degree; the legacy pair cleanup emits `2 × degree`. Warm atomic
expansion crossed below legacy generation between degrees 10 and 100 on this
host. Commit cost was roughly even around degree 100 and lower for the atomic
path at degree 1000, despite the atomic path carrying mutation proofs.

## Operational guidance

The degree-1000 atomic sample committed in 8.67 ms median on this embedded
development host. This does not establish a portable cutoff for Datomic or
Datahike: transaction limits, production contention, storage and relation
distribution determine the real crossover. Prewarm installed functions during
deployment, benchmark representative production degrees, and choose atomic
safe retraction only while one serialized transaction remains acceptable. For
larger or unpredictable degrees, use batched `delete-object!` before native
entity deletion and accept that the workflow is not one-transaction atomic.
