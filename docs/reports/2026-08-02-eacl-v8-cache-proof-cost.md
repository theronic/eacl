# EACL v8 cache-proof cost review

Date: 2026-08-02

## Scope

This review compares the cache immediately before the v3 freshness redesign
(`8bf9244`) with the redesigned implementation (`ac9d06f`). It separates:

1. backend proof construction;
2. one-time recursive-schema compilation; and
3. a completed `can?` cache hit, including snapshot selection, authenticated
   token/cache-envelope work, proof validation, and cache telemetry.

The old Datomic result is not a correctness-equivalent alternative: it used a
process-local epoch/listener mechanism. The old DataScript and Datahike paths
used database content but returned proof material rather than authenticated,
fixed-size digests.

## Method

- Apple M3 Pro, macOS 26.5.1
- OpenJDK 22.0.1, Clojure 1.11.4
- in-memory Datomic, Datahike, and DataScript databases
- one target permission depending on one relation
- added schema definitions and relationships were unrelated to the target
- completed-hit numbers are the median of 51 samples of 10 calls after 100
  warm-up calls
- direct proof numbers are the median of 31 samples of 5 calls after 20 warm-up
  calls
- times are microseconds per call

These are comparative microbenchmarks, not production latency promises.

## Completed cache-hit results

| Backend / workload | Pre-v3 | v3 content | v3 managed mutation |
| --- | ---: | ---: | ---: |
| Datomic, minimal | 106.6 | 2,132.9 | 1,539.5 |
| Datomic, +200 schema definitions | 53.9 | 7,281.0 | 1,529.9 |
| Datomic, +5,000 unrelated relationships | 40.3 | 4,033.7 | 1,524.6 |
| Datahike, minimal | 111.3 | 1,936.5 | 1,695.0 |
| Datahike, +200 schema definitions | 2,129.3 | 7,518.5 | 1,686.6 |
| Datahike, +5,000 unrelated relationships | 327.2 | 1,967.3 | 1,672.8 |
| DataScript, minimal | 179.8 | 1,968.2 | 1,523.4 |
| DataScript, +200 schema definitions | 337.9 | 3,652.0 | 1,521.2 |
| DataScript, +5,000 unrelated relationships | 4,660.5 | 6,308.1 | 1,513.0 |

The managed column stays flat as unrelated schema and graph data grow. The
approximately 1.5–1.7 ms floor is not backend proof construction: it includes
the redesigned consistency selection and authenticated token/cache-envelope
path.

## Direct proof results

| Backend | v3 content: complete schema at +200 defs | v3 content: scoped schema at +200 defs | v3 content: relation proof at +5,000 unrelated edges | v3 managed proof operations |
| --- | ---: | ---: | ---: | ---: |
| Datomic | 2,783.0 | 13.6 | 616.3 | 0.5–1.2 |
| Datahike | 3,637.1 | 2,114.0 | 266.5 | 0.8–1.6 |
| DataScript | 1,693.5 | 242.9 | 4,767.3 | 0.6–0.8 |

The complete schema proof is invoked to select a safe derived-schema generation.
It is why content-mode cache hits grow with unrelated schema even when the
query's scoped schema proof is cheap. Removing it without another authoritative
schema-change identity would reintroduce stale compiled permission paths under
out-of-band schema writers.

## Recursive-schema compilation

A synthetic permission chain measured the first
`traversal-permission?` classification and the cached lookup:

| Reachable permission nodes | Cold compilation (µs) | Warm lookup (µs) |
| ---: | ---: | ---: |
| 10 | 1,965.6 | 0.425 |
| 25 | 2,136.6 | 0.117 |
| 50 | 4,275.3 | 0.113 |
| 100 | 12,189.5 | 0.113 |
| 200 | 42,753.2 | 0.113 |
| 400 | 83,726.8 | 0.113 |

The implementation computes a reachability closure for each reachable node, so
its cold upper bound is `O(V(V+E))`. The benchmark confirms that this is
generation compilation, not per-call proof work: after compilation, lookup is
constant-time at measurement resolution.

## Conclusions

1. The redesigned managed proof system does not have graph- or schema-wide
   proof cost per call. Its backend proof work is dependency-sized and remained
   flat in this benchmark.
2. Recursive classification has a potentially quadratic cold compilation cost,
   but it is memoized per permission root and schema generation.
3. Unknown-writer content mode is intentionally conservative and is not
   constant-time. It pays complete-schema and relationship-content work because
   it refuses to trust writer-maintained mutation identities.
4. “Streaming digest” means fixed output and incremental hashing. It must not be
   read as a claim that collecting, sorting, or reading proof records is
   constant-time or constant-memory end to end.
5. The authenticated v3 path has a material fixed latency floor even in managed
   mode. Further optimization should profile token and cache-envelope
   encode/decode separately; weakening proof or authentication semantics is not
   an acceptable benchmark optimization.
