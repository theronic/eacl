# EACL v8 cache-proof cost review

Date: 2026-08-02

> **Superseded implementation:** the proof-per-hit v3 candidate measured below
> is not the final v8 cache. EACL now uses a private current-generation cache:
> exact hits validate immutable snapshot identity only, managed cross-
> transaction hits use current relation transaction stamps, and disabled or
> per-request-bypassed caching branches directly to evaluation before semantic
> cache-key, dependency-stamp, provider, canonicalization, or envelope work.
> See
> [the current-snapshot design](2026-08-02-eacl-v8-single-db-current-cache-design.md).

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

## Proofed cache hit versus no completed-result cache

The old/new comparison does not answer the most important operational question:
whether a proofed completed-answer hit is faster than evaluating the
authorization with result caching disabled. The same public `can?` call was
therefore measured under both writer-authority modes, first with a warmed
completed-answer cache and then through a client using `cache/no-cache`. Both
clients used the same database and had their derived schema state warmed before
measurement.

For the benchmark's cheap one-relation permission, both the optimized managed
mode and the default unknown-writer content mode were measured:

| Backend / authority | Proofed cache hit (µs) | No cache (µs) | Cache / no-cache |
| --- | ---: | ---: | ---: |
| Datomic / managed | 1,625.5 | 1,580.0 | 1.03× |
| Datahike / managed | 1,810.3 | 155.3 | 11.66× |
| DataScript / managed | 1,694.1 | 202.8 | 8.35× |
| Datomic / unknown | 1,923.7 | 1,821.1 | 1.06× |
| Datahike / unknown | 1,813.9 | 69.6 | 26.05× |
| DataScript / unknown | 1,642.7 | 111.4 | 14.74× |

The proofed cache is not faster for this workload. Datomic is effectively at
parity at this measurement scale; Datahike and DataScript are substantially
slower because their uncached direct evaluation is much cheaper than the fixed
authenticated cache path.

A recursive folder chain demonstrates the break-even behavior. The cached
answer still validates the same dependency-sized proof; the uncached call must
traverse an increasing number of relationship edges:

| Backend / chain depth | Proofed cache hit (µs) | No cache (µs) | Speedup |
| --- | ---: | ---: | ---: |
| Datahike / 100 | 1,733.4 | 1,018.2 | 0.59× |
| Datahike / 200 | 1,765.2 | 1,720.0 | 0.97× |
| Datahike / 400 | 1,726.7 | 4,260.6 | 2.47× |
| Datahike / 800 | 1,799.3 | 6,346.4 | 3.53× |
| DataScript / 50 | 1,629.4 | 729.9 | 0.45× |
| DataScript / 100 | 1,623.7 | 1,837.1 | 1.13× |
| DataScript / 400 | 1,594.8 | 11,275.8 | 7.07× |
| DataScript / 800 | 1,578.6 | 24,857.4 | 15.75× |
| Datomic / 50 | 1,618.0 | 1,622.8 | 1.00× |
| Datomic / 400 | 1,654.5 | 1,742.0 | 1.05× |

The exact crossover is hardware-, backend-, schema-, and query-dependent.
These rows establish only the governing rule: completed-result caching wins
when avoided authorization work exceeds the roughly 1.6–1.8 ms fixed
proof/envelope hit cost. It is a correctness-preserving latency trade, not a
universal optimization.

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

The measured implementation computed a reachability closure for each permission
node, so its cold upper bound was `O(V(V+E))`. The review also found that this
result was memoized per root, allowing different first-read roots to repeat the
quadratic calculation. The follow-up implementation replaces the closure matrix
with iterative strongly connected component and reverse-reachability passes:
`O(V+E)` graph-analysis time and memory once permission paths are materialized,
once per schema generation. Adapter path materialization and its deterministic
sorting are additional backend-dependent work. A shared delay makes concurrent
first reads single-flight. After compilation, any root lookup is constant-time.
The earlier intermediate fix (still using the quadratic compiler) took 21,789.5
µs for a warmed 200-node generation and 607.5 µs to read all 200 roots
afterward; it is retained here only as audit history, not as a measurement of
the final linear compiler.

The final implementation was then measured with nine fresh-generation samples
per size in the same warmed JVM:

| Permission nodes | Final cold median (µs) | Warm root median (µs) |
| ---: | ---: | ---: |
| 10 | 587.3 | 0.075 |
| 25 | 907.0 | 0.088 |
| 50 | 1,478.8 | 0.075 |
| 100 | 3,291.0 | 0.075 |
| 200 | 7,206.0 | 0.071 |
| 400 | 18,249.5 | 0.071 |
| 1,000 | 73,856.9 | 0.088 |

These end-to-end cold numbers include adapter schema-path materialization and
do not empirically claim linear wall-clock scaling. The important bound is that
the graph proof no longer constructs a quadratic closure matrix or repeats it
per root. The measured steady lookup is constant at this resolution.

## Conclusions

1. The redesigned managed proof system does not have graph- or schema-wide
   proof cost per call. Its backend proof work is dependency-sized and remained
   flat in this benchmark.
2. Recursive classification uses `O(V+E)` graph analysis once for all
   permission roots in a schema generation after permission-path
   materialization, and constant-time lookups afterward.
3. Unknown-writer content mode is intentionally conservative and is not
   constant-time. It pays complete-schema and relationship-content work because
   it refuses to trust writer-maintained mutation identities.
4. “Streaming digest” means fixed output and incremental hashing. It must not be
   read as a claim that collecting, sorting, or reading proof records is
   constant-time or constant-memory end to end.
5. The authenticated v3 path has a material fixed latency floor even in managed
   mode. It is slower than uncached direct evaluation on Datahike and DataScript
   and only pays off for sufficiently expensive/repeated authorization work.
   Further optimization should profile token and cache-envelope encode/decode
   separately; weakening proof or authentication semantics is not an acceptable
   benchmark optimization.

## Final v8 replacement measurements

After replacing the candidate, the final Datomic heavy-suite rerun measured a
cache-disabled/warm permission check at 31.38 µs and an exact-current completed
hit at 9.25 µs. The exact hit's native cache resolution accounted for about
1.57 µs; selected-snapshot capture accounted for about 5.45 µs. The former
1.5–1.8 ms proof/envelope floor is absent.

Separate repeated-check probes measured:

| Backend | Current-generation hit | Cache disabled |
| --- | ---: | ---: |
| Datomic | 8.67 µs | 33.54 µs |
| Datahike | 12.19 µs | 17.65 µs |
| DataScript | 7.20 µs | 26.81 µs |

The measurements vary with JIT and fixture state; the structural regression is
stronger than the timing. Tests replace `eacl.cache/resolve-current!` with a
throwing function and prove that globally disabled Datomic evaluation and
per-request bypasses on Datomic, Datahike, DataScript CLJ, and DataScript CLJS
still return correct results. Disabled operation does not merely miss inside
the cache strategy—it never enters native cache resolution.
