# DataScript endpoint-pair storage benchmark

This JVM microbenchmark compares the DataScript relationship-entity layout at
PR #92's head with the endpoint-pair layout. It is reproducible through
`eacl.bench.datascript-relationship-storage/run-benchmark!`; the namespace is
under the DataScript module's test tree but is deliberately not a regular test.

## Environment and method

- Date: 2026-08-02
- Hardware: Apple Silicon (`aarch64`), 12 available processors
- OS: macOS 26.5.1
- JVM: OpenJDK 24.0.1
- Clojure: 1.11.4
- DataScript: 1.7.8
- JVM max heap: 4,831,838,208 bytes
- Graph sizes: 1,024 and 4,096 logical relationships
- Read/proof warmup: 20 runs
- Read/proof samples: 30, with 20 invocations per sample
- Write warmup: 3 runs
- Write samples: 10
- Write batch: 100 logical relationships per immutable `ds/with`
- Latency clock: `System/nanoTime`
- Allocation: current thread's allocated bytes through
  `com.sun.management.ThreadMXBean`

Fixtures use the same subject/resource pairs and relation eid. Before timing,
the runner requires old/new direct matches and forward/reverse adjacency
results to be equal. The old fixture declares five component attributes, a
unique full-key tuple, and four derived scan tuples. The new fixture stores one
ordinary four-element forward value on the subject and one reverse value on the
resource.

This is a storage/index microbenchmark, not an application throughput claim.
It excludes cursor signing, cache-store latency, mutation-journal publication,
networking, garbage-collection attribution, and browser JavaScript timing.

## Results

All latency values are p50 microseconds unless explicitly described otherwise.

| Logical relationships | Workload | Entity layout | Endpoint pair | Change |
| ---: | --- | ---: | ---: | ---: |
| 1,024 | direct match | 3.681 | 1.560 | -57.6% |
| 1,024 | forward adjacency | 13.323 | 7.454 | -44.0% |
| 1,024 | reverse adjacency | 12.331 | 8.058 | -34.7% |
| 1,024 | 100-row relationship page | 27.965 | 24.708 | -11.6% |
| 1,024 | complete content proof | 1,182.973 | 436.890 | -63.1% |
| 1,024 | create 100 | 5,114.334 | 743.416 | -85.5% |
| 1,024 | delete 100 | 3,853.541 | 1,212.917 | -68.5% |
| 4,096 | direct match | 2.748 | 1.144 | -58.4% |
| 4,096 | forward adjacency | 16.613 | 11.152 | -32.9% |
| 4,096 | reverse adjacency | 16.500 | 10.940 | -33.7% |
| 4,096 | 100-row relationship page | 26.963 | 24.608 | -8.7% |
| 4,096 | complete content proof | 4,564.723 | 1,762.481 | -61.4% |
| 4,096 | create 100 | 5,042.542 | 744.250 | -85.2% |
| 4,096 | delete 100 | 3,659.708 | 1,473.542 | -59.7% |

The measured relationship-specific datom counts were exact:

| Relationships | Entity layout | Endpoint pair | Reduction |
| ---: | ---: | ---: | ---: |
| 1,024 | 10,240 | 2,048 | 80% |
| 4,096 | 40,960 | 8,192 | 80% |

Allocation fell in every measured workload. Representative 4,096-edge mean
allocation changes were:

- direct match: 5,680 to 2,168 bytes/op;
- forward adjacency: 39,888 to 15,256 bytes/op;
- complete content proof: 5,134,576 to 1,858,952 bytes/op;
- create batch: 9,174,085 to 1,526,896 bytes/op;
- delete batch: 13,433,128 to 3,043,712 bytes/op.

## Regressions and variance

The 4,096-edge relationship-page arithmetic mean regressed by 1.8%
(27.734 to 28.238 microseconds) despite its p50 improving by 8.7%. The
4,096-edge reverse-adjacency p95 also regressed from 18.429 to 24.794
microseconds while p50 and mean improved. These tails are visible in a short,
single-process microbenchmark and should not be concealed by the lower medians.

No benchmark result establishes a universal production speedup. The defensible
conclusions are narrower: the new layout uses exactly one fifth of the
relationship datoms in these fixtures, reduced allocation in every measured
workload, and improved p50 latency for every listed workload on this machine.
