# Datalevin local adapter benchmark — 2026-08-22

This is the retained output of
`eacl.bench.datalevin-test/run-benchmark!` after the bounded-scan, snapshot,
schema-cache, and adapter-allocation fixes. It is local development evidence,
not Linux ARM64 release qualification or a production capacity claim.

## Environment and method

- macOS, ARM64 (`aarch64`)
- Eclipse Adoptium Java 26.0.2
- Datalevin 1.0.2 maintained-fork worktree
- 256 documents, one direct viewer relationship per document
- 25 warmups plus 51 measured calls; relationship writes use five warmups
  plus 21 measured alternating touch/delete commits
- wall latency from `System/nanoTime`; current-thread allocation from
  `ThreadMXBean`
- all times below are microseconds; allocation columns are bytes per call

| Operation | Samples | Min | p50 | p95 | p99 | Max | Mean | Alloc p50 | Alloc p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Bounded forward adapter scan, limit 25 | 51 | 97.333 | 113.708 | 204.833 | 230.291 | 239.125 | 127.010 | 77,440 | 77,496 |
| Warm permission check | 51 | 376.250 | 486.167 | 815.417 | 900.542 | 1,021.000 | 523.622 | 403,624 | 403,712 |
| First resource page, 25 | 51 | 237.542 | 308.125 | 741.417 | 785.334 | 808.416 | 372.415 | 331,712 | 331,768 |
| Exact resource count | 51 | 237.459 | 268.500 | 463.084 | 526.292 | 650.792 | 311.930 | 406,464 | 406,592 |
| Explicit snapshot acquire/info/close | 51 | 79.625 | 93.083 | 124.292 | 195.209 | 309.750 | 102.547 | 200,728 | 200,728 |
| Provider acquire/adapt/release | 51 | 113.417 | 132.500 | 304.375 | 497.875 | 603.750 | 162.822 | 229,944 | 229,944 |
| Explicit snapshot cache-bypass read | 51 | 164.834 | 194.833 | 323.750 | 385.208 | 447.583 | 221.930 | 298,032 | 298,032 |
| Relationship write commit | 21 | 4,848.625 | 5,994.667 | 6,096.458 | 6,096.458 | 6,174.791 | 5,898.329 | 1,031,224 | 1,032,904 |
| Acquire/info/close while 32 readers remain open | 51 | 69.500 | 84.166 | 153.584 | 174.375 | 241.584 | 95.884 | 201,184 | 201,240 |

## Before/after p50 evidence

The following pre-fix samples were captured in the same local fixture before
the adapter changes. They are paired-run engineering evidence, not a formal
statistical confidence interval.

| Operation | Pre-fix p50 µs | Fixed p50 µs | Latency reduction | Pre-fix alloc p50 | Fixed alloc p50 | Allocation reduction |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Provider acquire/adapt/release | 535.834 | 132.500 | 75.3% | 1,265,448 | 229,944 | 81.8% |
| Warm permission check | 1,178.500 | 486.167 | 58.7% | 1,446,640 | 403,624 | 72.1% |
| First resource page, 25 | 856.500 | 308.125 | 64.0% | 1,366,944 | 331,712 | 75.7% |
| Exact resource count | 995.800 | 268.500 | 73.0% | 1,449,512 | 406,464 | 72.0% |
| Relationship write commit | 6,881.750 | 5,994.667 | 12.9% | 1,879,912 | 1,031,224 | 45.1% |

The read-path reductions come primarily from avoiding repeated schema digest
work, avoiding duplicate snapshot schema derivation, bounding native seeks to
the logical page budget, and keeping proof-unavailable schema derivations
request-local. The write benchmark benefits indirectly from cheaper response
snapshot construction; Datalevin transaction commit remains the dominant
cost.

## Resource observations

| Measurement | Value |
| --- | ---: |
| Used heap before measured operations | 471,771,776 bytes |
| Used heap after measured operations, without forced GC | 681,486,976 bytes |
| Database directory before measured operations | 491,653 bytes |
| Database directory after measured operations | 557,189 bytes |
| Held explicit readers in pressure fixture | 32 |
| Oldest-reader age at pressure observation | 4.060 ms |
| Active readers after every benchmark scope closed | 0 |

The held-reader fixture did not increase snapshot acquisition p50 in this
short run (84.166 µs versus 93.083 µs without held readers). That is only a
32-reader local observation. It does not characterize exhaustion,
long-duration LMDB page retention, map growth under sustained writes, RSS,
native memory, or tail behavior under concurrent load. Those remain separate
Linux and deployment gates.

## Live demo request and heap observation

The local Jetty demo was also sampled through loopback HTTP after the composed
snapshot change. Each p50 is the middle of 21 sequential warmed requests,
including Ring, JSON encoding/decoding, request orchestration, and loopback
HTTP; this is diagnostic evidence rather than a load or capacity test.

| Endpoint | Warm p50 |
| --- | ---: |
| `check-permission` | 5.747 ms |
| `lookup-resources`, page 10 | 10.203 ms |
| permission-filtered `read-relationships`, page 10 | 29.546 ms |

The filtered relationship path remains the slowest because it performs scalar
point authorization for each physical candidate and an exact lookahead for a
logical `hasNextPage`. Those calls now share one selected snapshot, proof
frame, request-local schema cache, deadline, and cancellation budget; a fused
or batch point-check operation is still a separate core optimization.

The first uncapped demo process inherited a 9 GiB ergonomic maximum heap from
the 36 GiB host. Immediately before an explicit collection it had 1,882,804 KiB
used heap, of which only about 24 MiB was old-generation live data, and
2,248,352 KiB RSS. An explicit collection reduced used heap to 73,038 KiB,
demonstrating accumulation of short-lived request allocations rather than a
retained EACL/Datalevin object graph. The local `:run` profile now uses
`-Xms128m -Xmx1g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError`. After restart and
63 warmed HTTP requests, the process reported 132,954 KiB used heap,
380,928 KiB committed heap, and 585,712 KiB RSS. The 1 GiB ceiling is a local
run-profile bound, not the pending production sizing decision.
