> Historical record (2026-08-02 – 2026-08-11): these measurements were taken on the interim v8 acyclic/merge engines. The stable-discovery engine routed on 2026-08-14 changes both the enumeration order and the cost profile; see the change's `tasks.md` §10 and `formal/verification/explorer-v7-performance.edn` for current numbers.

# v6 vs v8.0 reference benchmark

Both versions run the **same permission schema** (`multipath-schema-dsl`, four union paths
including three arrows), the **same graph**, the same page size and the same page count. Only the
pagination API differs: v6 took `:cursor`/`:limit`, v7 takes `:first`/`:after`.

- 300 accounts, 4 teams and 2 VPCs per account, 500 servers per account
- 2,100 intermediate resources, 150,000 terminal servers
- 3,000 pages of 50, walked one query per page
- Subject is a platform `super_admin`, so every result arrives through the arrow chain

Each version and each cache configuration ran in its own fresh JVM
(`clojure -J-Xmx8g -M:dev:nrepl`), with 20 warmup iterations, 3 measured walks (median reported),
and 31 samples per sampled page. Seeding is excluded from all timings. Environment: Apple M3 Pro,
18 GiB, macOS arm64, Temurin OpenJDK 24.0.1.

Every run returned exactly 150,000 results with no duplicates.

Reproduce:

```shell
clojure -J-Xmx8g -M:dev:nrepl
```
```clojure
(require 'eacl.bench.version-comparison)
(eacl.bench.version-comparison/run-reference! :off)   ; :off | :default | :remember-answers
```

The v6 side lives on the `bench/v6` branch as the same namespace, run the same way.

## Results

| | v6 `f8a1a98` | v8.0 `:cache false` | v8.0 default cache | v8.0 `:live-results?` |
|---|---:|---:|---:|---:|
| Full walk, median of 3 | 44.39 s | 38.20 s | **15.61 s** | **14.52 s** |
| Throughput | 67.6 pages/s | 78.5 | 192.2 | **206.6** |
| Speedup vs v6 | 1.00× | 1.16× | **2.84×** | **3.06×** |
| Page 1 median | 28.68 ms | 20.70 ms | 20.84 ms | **0.048 ms** |
| Page 500 median | 23.54 ms | 18.44 ms | 18.44 ms | **0.155 ms** |
| Page 1,000 median | 19.11 ms | 16.68 ms | 15.70 ms | **0.158 ms** |
| Page 2,000 median | 9.93 ms | 10.06 ms | 9.66 ms | **0.150 ms** |
| Page 3,000 median | 1.05 ms | 2.70 ms | 2.56 ms | **0.155 ms** |

The retained v6 reference from 2026-07-28 recorded 41.04 s / 73.1 pages/s on this same machine; the
44.39 s measured alongside v8.0 is the same figure under current machine state, and is what the
comparison above uses.

## Reading the numbers

**Uncached, v8.0 is modestly faster.** 1.16× on the full walk, 1.39× on page 1. The engine changed
shape between versions — v6 used a cursor tree, v7 uses per-path lazy merges with per-path
intermediate frontiers — and on this graph that is close to a wash, with v7 ahead on early pages.

**The full-walk gain comes from the acyclic continuation, not from result caching.** The default
cache retains no results at all (`:exact-results?` and `:live-results?` are both off); it only
retains recursive continuations and, since this branch, the acyclic engine's per-intermediate
stream heads. Those alone take the walk from 38.20 s to 15.61 s — a 2.4× improvement on a sequential
walk over 2,100 intermediates.

**Why the sampled page latencies do not improve with the default cache.** A sampled page is
requested repeatedly with the *same* `:after` cursor. Stream heads are published under the cursor of
the page just produced and read under the page's bound, so re-requesting one page never populates
its own read key — only a sequential walk does. This is the intended shape: the continuation
accelerates pagination, not repetition. Enabling `:live-results?` serves the repeated request from
the result cache instead, which is where the 0.15 ms figures come from — 130–190× faster than v6 for
a re-read.

**v8.0 is slower on the last page**: 2.70 ms vs v6's 1.05 ms. In absolute terms both are fast, and
it is the one place v6 wins, but it is a real ~2.5× regression at the tail worth understanding
rather than explaining away.

## What this comparison does not cover

v6 has no recursive permission support, so nothing here exercises the traversal engine, its
continuations, or recursive counts. Everything v7 added beyond raw pagination speed — recursive
permissions, consistency modes (`minimize-latency`, `at-least-as-fresh`, `at-exact-snapshot`),
authenticated and encrypted cursors, counts, and the cache itself — has no v6 counterpart to
measure against.

Authenticated cursors in particular are work v6 simply did not do: v7 encrypts and authenticates
every page token it mints. That cost is included in every v7 number above.
