# Safe-retraction benchmark — 2026-08-09

This report records non-gating, warmed DataScript evidence for the optional
safe entity-retraction transaction function. Structural CI tests separately
assert exactly two target-scoped endpoint reads and linear emitted operation
counts; the timings below are evidence, not absolute latency thresholds.

## Environment

- EACL checkout: `6d68215` plus the `simplify-cache-coherence` implementation
- Java: OpenJDK 26.0.2, 64-Bit Server VM
- Host: Apple Mac Studio, M4 Max, 14 cores, 36 GB RAM
- OS: macOS 26.4.1, arm64
- Backend: DataScript 1.7.8, in-process JVM
- Command through the repository nREPL: `(eacl.bench.safe-retraction/run-benchmark!)`

The runner is `eacl.bench.safe-retraction`. It records the first installed-IFn
expansion separately, prewarms both paths 20 times, then measures warmed
expansion and commits. The comparison uses the current `tx-delete-object`
generator plus native entity retraction in one DataScript commit. Both paths
publish native relation generations and neither uses the optional legacy
journal. The comparison is still not semantically identical: the safe function
discovers cleanup from the transaction-start database, while `tx-delete-object`
calculates it before submission.

## Results

All times are microseconds. Values are median / p95.

| Target degree | Atomic ops | Legacy generated ops | First atomic expansion | Warm atomic expansion | Warm legacy generation | Atomic commit | Legacy cleanup commit |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 1 | 0 | 200.500 | 9.417 / 14.333 | 3.416 / 7.584 | 23.250 / 36.334 | 15.542 / 34.792 |
| 1 | 3 | 4 | 71.750 | 12.125 / 46.834 | 9.084 / 15.750 | 40.542 / 190.292 | 32.666 / 46.083 |
| 10 | 12 | 22 | 108.333 | 13.209 / 17.625 | 25.750 / 94.709 | 78.375 / 155.500 | 103.458 / 119.291 |
| 100 | 102 | 202 | 86.417 | 65.375 / 80.792 | 176.750 / 237.625 | 723.625 / 781.375 | 1246.167 / 1450.500 |
| 1000 | 1002 | 2002 | 750.708 | 604.000 / 718.292 | 1846.000 / 3689.208 | 13187.666 / 20000.792 | 16624.667 / 16721.709 |

The target-only function has no journal/anchor/CAS envelope. For a non-empty
single-relation target it emits `degree` peer-half retractions, one native root
retraction, and one relation-generation stamp: `degree + 2`. The precomputed
comparison emits both tuple-half retractions plus the root retraction:
`2 × degree + 1`, plus one relation-generation stamp and one
schema-write-fence predicate when the degree is non-zero. On this host the
safe-function expansion and commit were already lower at degree 10 and
remained lower through degree 1000.

## Operational guidance

The degree-1000 atomic sample committed in 13.19 ms median on this embedded
development host. This does not establish a portable cutoff for Datomic or
Datahike: transaction limits, production contention, storage and relation
distribution determine the real crossover. Prewarm installed functions during
deployment, benchmark representative production degrees, and choose atomic
safe retraction only while one serialized transaction remains acceptable. For
larger or unpredictable degrees, use batched `delete-object!` before native
entity deletion and accept that the workflow is not one-transaction atomic.
