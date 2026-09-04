# Qualifier-reference storage qualification

EACL v8 behavior is held constant while Relationship storage changes from four slots at baseline commit `21e661e0` to five slots with `nil` qualifiers. Measurements were taken on an Apple M4 Max, macOS 26.4.1, Java 25.0.3. Both checkouts used the same dependency versions and the clean Datalevin fork at `a7e29c25a3034b54814e58a2d317e8c6877d1933`.

## Budgets declared before measurement

The candidate must preserve results and two datoms per logical Relationship. Median direct-check, scan, page, arrow, and exhaustive-count latency must be at most 1.5× baseline; p95 must be at most 1.75×. Allocated bytes per operation must stay within 1.5×. Warm exact cache hits must stay within 1.2× median. Serialized Relationship transaction bytes and freshly populated durable database bytes must stay within 1.5×. Migration throughput, peak bytes, and restart cost are measured separately; the 1.5× fresh-store limit does not apply to retained migration history.

## Matched public API workload

The graph has 2,000 documents, 4,001 Relationships, and 8,002 Relationship datoms. One user directly views every document and owns the account reached through each document’s account Relation. An ungranted user supplies the negative probes. Scans read 100 Relationships; pages and continuations read 50 resources; exhaustive counts return 2,000. All reads except the warm exact-hit case explicitly disable caching.

Three trials per checkout alternate baseline/candidate order. Each operation is warmed independently before 30–50 measured batches. Point checks use 5,000 warmups and 100 operations per batch; exact cache hits use 20,000 warmups and 1,000 per batch. Page/scan batches contain three operations and exhaustive-count batches contain one. Ratios below pool each operation’s raw samples across the three trials.

The first DataScript trial was unstable: pooled direct-check p95 was **1.89×**, above the 1.75× limit, while its next two trials were much closer. A separate three-trial DataScript confirmation increased point-check warmup to 20,000 and batching to 1,000 on both checkouts. That full DataScript confirmation is used below: direct-check p95 is **1.14×**. The earlier failing samples and their summary remain in the raw results; no budget was relaxed.

| Backend | Direct | Negative | Arrow | Scan | First page | Continuation | Count | Warm exact |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| DataScript | 1.13× | 1.14× | 1.12× | 1.13× | 1.09× | 1.17× | 1.22× | 1.15× |
| Datomic | 1.13× | 1.14× | 1.07× | 1.19× | 1.19× | 1.16× | 1.16× | 1.13× |
| Datahike | 1.14× | 1.15× | 1.14× | 1.30× | 1.18× | 1.19× | 1.24× | 1.13× |
| Datalevin | 1.10× | 1.07× | 1.10× | 1.06× | 1.11× | 1.08× | 1.11× | 1.02× |

All final operation medians, p95 values, and current-thread allocation ratios pass their declared budgets. The largest allocation ratio is 1.17×. Raw samples include absolute nanoseconds and allocated bytes. Allocation excludes worker threads and native/off-heap memory.

Bulk creation is a separate measured cost: the three main trials show median write-time ratios of DataScript 1.55×, Datomic 1.18×, Datahike 0.97×, Datalevin 2.43×. Datalevin’s **2.43× write-time increase** remains a limitation: qualifier-independent conflict and partial-pair validation require native identity-range probes on both halves. Duplicate planning probes were removed during implementation. This report does not claim write-latency neutrality.

## Physical density and transaction bytes

| Backend | Fresh durable bytes, 4 → 5 slots | Relationship transaction EDN ratio | Native index occupancy |
| --- | ---: | ---: | --- |
| DataScript | Not available (memory database) | 1.13× | EAVT/AEVT/AVET leaf nodes: 32/36/35 → 32/36/35 |
| Datomic | Not available (memory database) | 1.05× | Native segment/page occupancy is not exposed by the memory backend |
| Datahike | 2,935,821 → 3,035,435 (1.03×) | 1.13× | EAVT/AEVT/AVET leaf nodes: 31/38/35 → 31/38/35 |
| Datalevin | 2,162,821 → 2,293,893 (1.06×) | 1.13× | EAV leaf pages: 34 → 41; AVE leaf pages: 25 → 26 (16 KiB pages) |

Native index counts include schema and object datoms, which increase slightly for completion metadata. They are distinct from the unchanged count of Relationship datoms. Tree/page counts are inspected directly; serialized EDN is a transaction-size proxy, not the backend’s binary wire encoding. Durable bytes are apparent file lengths, including native store files, not allocated disk blocks. Datomic memory results cannot establish production durable-space or segment-density limits.

## Migration and admission

The rehearsal converts 2,000 legacy Relationships in batches of 250. It deliberately interrupts after the first converted batch, reopens durable Datahike/Datalevin connections, and resumes through independent verification. The reported throughput includes preflight, interruption, reopen, verification, and progress sampling.

| Backend | Pairs/s | Initial → peak bytes | Native reopen | Resume to completion | Complete rerun |
| --- | ---: | ---: | ---: | ---: | ---: |
| DataScript | 23,939 | Memory database | Runner restart only | 67.46 ms | 0.05 ms |
| Datomic | 2,351 | Memory database | Runner restart only | 829.84 ms | 0.71 ms |
| Datahike | 749 | 1,272,751 → 4,585,187 | 1.25 ms | 2181.47 ms | 0.30 ms |
| Datalevin | 7,331 | 1,360,005 → 1,917,061 | 9.02 ms | 205.39 ms | 0.10 ms |

Datahike retains source assertions, source retractions, and target assertions in native history: peak apparent space in this small rehearsal is **3.60×** its initial size. Reserve migration capacity based on a restored production-sized rehearsal; fresh-store density is insufficient for sizing a migration. Current source data is empty after conversion, while native history remains.

A separate DataScript fixture contains **1,000,000 Relationships / 2,000,000 endpoint datoms**. Client construction takes **0.82 ms** and makes **zero target Relationship index reads**. Instrumentation records one migration-state read and four bounded legacy/v6 existence probes. A permission check against the last document confirms that the graph is usable.

Cold-first-request timings are included in the raw public workload results. They follow seeding in a live JVM; OS caches were not flushed. Durable restart measurements reopen native connections while OS caches remain warm. These measurements do not establish cold-disk production latency, JVM retained heap, remote Datomic latency, or browser production latency.

## Reproduction and evidence

All execution uses nREPL, following the repository’s testing rules. Start separate Java 25 nREPLs with `:dev:test:datalevin-dev` in the baseline checkout and current checkout. Load the current `modules/eacl-datalevin/test/eacl/bench/qualifier_storage_test.clj` by absolute path into both, then invoke `run-backend!` with backend keyword, document count `2000`, and an output filename. Use the alternating-trial runner alongside the raw results to reproduce the main and longer DataScript trials.

The companion `qualifier_density_test.clj` provides `run-density!`; `qualifier_migration_test.clj` provides `run-migration!`. The latter runs only in the candidate checkout. The million-Relationship check is `eacl.bench.qualifier-admission-test/million-relationship-startup-is-bounded-test`. The existing recursive-performance and cross-backend workload gates cover recursive arrows and reverse traversal in addition to this matched workload.

[Raw samples, density measurements, migration phase traces, startup observations, and summaries](qualifier-reference-results/) are committed with this report. The initial pooled timing summary is `benchmark-summary.edn`; the final summary using the longer DataScript confirmation is `benchmark-summary-final.edn`.

## DataScript ClojureScript qualification

Node 25.9.0 runs the same 2,000-document / 4,001-Relationship graph in three alternating trials per checkout. Forward page IDs and reverse page IDs match exactly, and both layouts contain 8,002 Relationship datoms. Unoptimized CLJS builds use the same compiler version and portable decision kernel. Latency samples are collected before allocation profiling.

Allocation is estimated with V8’s sampling heap profiler at a 16 KiB interval, retaining samples for objects collected by both major and minor GC. This measures temporary allocation as well as retained objects; it is a statistical estimate rather than JVM thread allocation accounting. See the [V8 inspector HeapProfiler protocol](https://chromedevtools.github.io/devtools-protocol/v8/HeapProfiler/#method-startSampling).

| Operation | Median ratio | p95 ratio | Estimated allocation ratio |
| --- | ---: | ---: | ---: |
| direct | 1.03× | 1.06× | 1.01× |
| negative | 1.00× | 0.99× | 1.00× |
| arrow | 1.04× | 1.08× | 0.99× |
| scan | 1.16× | 1.14× | 1.22× |
| page | 1.10× | 1.00× | 1.15× |
| continuation | 1.05× | 1.09× | 1.11× |
| reverse | 0.91× | 1.00× | 1.02× |
| count | 1.35× | 1.35× | 1.35× |
| warm-exact | 1.01× | 0.99× | 0.98× |

All CLJS cases pass the same latency/allocation budgets. Build `eacl.bench.qualifier-cljs` with `cljs.main/-main` through nREPL, using Node target and `none` optimization in each checkout. The baseline compiler needs the current DataScript test directory as an extra classpath root so it can find this new benchmark namespace while retaining baseline production sources. Run each resulting Node entry point with an output filename; the raw `*-cljs-*.edn` files and `summarize-cljs.clj` preserve the measurements and comparison.

## Release verification

- JVM: 1,215 tests, 58,148 assertions; Datalevin: 57 tests, 4,541 assertions.
- DataScript CLJS: 602 tests, 30,719 assertions.
- Recursive/reverse performance battery: 2 tests, 430 assertions.
- Counterexample replay: 53 tests, 2,963 assertions; mutation controls: 3 tests, 176 assertions.
- Dafny: 49 modules, 9,385 proof efforts, zero errors.
- Public source closure: 96 roots, 2,466 definitions, zero forbidden policy matches.
- Reflection gate, Java 25 release build/install/cold-start smoke, and strict OpenSpec validation pass.
