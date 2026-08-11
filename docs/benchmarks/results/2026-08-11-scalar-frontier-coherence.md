# Scalar-frontier coherence benchmark — 2026-08-11

This report records reproducible local performance evidence for automatic
proof-backed cache coherence. It supports bounded implementation claims, not a
portable latency SLA.

## Environment and method

- Java: OpenJDK 26.0.2, 64-Bit Server VM
- Host: Apple Mac Studio, M4 Max, 14 cores, 36 GB RAM
- OS: macOS 26.4.1, arm64
- Backends: Datomic Peer 1.0.7622, Datahike 0.8.1759, DataScript 1.7.8
- Test: `eacl.bench.managed-proof-cost-test/cross-backend-scalar-frontier-and-request-cost-test`
- Invocation: `clojure -M:dev`, requiring the benchmark namespace with
  `:reload`

The proof-and-key comparison interleaves scalar and full-vector modes after
eight warm-up samples. Each of 31 measured samples performs 200 calls. It
reports p50 and p95 latency, current-thread allocation, serialized key size,
and backend operation counts at 0, 1, 8, 64, and 256 dependencies. Complete
request measurements separately cover exact hits, managed hits after unrelated
commits, and misses after relevant mutations.

## Largest dependency set

| Backend | Scalar p50 (ns) | Vector p50 (ns) | p50 change | Scalar/vector key bytes | Transient allocation change |
| --- | ---: | ---: | ---: | ---: | ---: |
| Datomic | 74,043 | 77,607 | -4.6% | 64 / 8,242 | +16 B |
| Datahike | 175,121 | 178,595 | -1.9% | 54 / 4,061 | +16 B |
| DataScript | 41,693 | 44,864 | -7.1% | 54 / 4,035 | +16 B |

The scalar identity remains 54–64 serialized bytes as dependency count grows.
The former vector identity grows with every dependency. Scalar p50 improved on
all three backends at 256 dependencies in this run; p95 was also lower on
Datomic and DataScript but noisier and higher on Datahike, so no universal p95
improvement is claimed. The scalar reduction adds 16 transient allocated bytes
per proof call in the measured implementation. Its material benefit is the
constant-size identity retained and repeatedly hashed/compared by caches and
cursors.

## Complete request paths

| Backend | Exact-hit p50 | Exact proof calls | Managed-after-unrelated p50 | Managed hits / samples | Relevant-miss p50 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Datomic | 58.1 µs | 0 | 90.6 µs | 31 / 31 | 129.6 µs |
| Datahike | 53.8 µs | 0 | 93.6 µs | 31 / 31 | 125.3 µs |
| DataScript | 46.1 µs | 0 | 61.8 µs | 31 / 31 | 73.8 µs |

Exact lookup runs before proof acquisition: 3,100 measured exact requests per
backend issued zero `:proof-frame` operations. Each request after an unrelated
commit was a managed hit. The 31 managed samples issued 62 proof-frame calls,
and the 24 relevant-mutation misses issued 48: one schema frame and one
dependency frame per non-exact request, with no content or transaction-log
scan. The request frame is shared by all consumers after acquisition; the two
calls reflect the separate schema-planning and resolved-dependency phases of
one request, not repeated acquisition by each cache tier.

## Writer coordination

The companion start-barrier test committed two simultaneous writes to distinct
relations. Every backend made exactly two transaction attempts for two logical
writes and emitted zero graph-head or mutation-journal operations. For two
writes to the same relation, Datomic made three attempts because one
relation-local CAS retried; Datahike and DataScript made two attempts. This
demonstrates the absence of database-global writer serialization. It is not a
production-throughput benchmark.

The structural costs are therefore:

- exact hit: no proof read;
- first non-exact eligible request: `O(d)` indexed generation reads for `d`
  dependencies and a constant-size retained proof identity;
- supported write: `O(r)` generation stamps for `r` affected relations;
- no relationship-content scan, listener, transaction-log scan, journal, or
  database-global cache coordination.
