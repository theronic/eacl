# Qualified authorization performance acceptance

All 24 reports passed all 1,440 fixed latency and allocation comparisons. Each report contains 18 operation/cache-mode combinations and 100 measured batches per combination. Ordinary edges and completed-answer hits issued zero qualification-data reads; no request fetched a qualifier, shared definition, or Relation more than once.

This run measures implementation commit `89e25860f198c1c00279e78e047d962ae902077b`, explicitly binding each semantic epoch before the default-v9 activation. The activation changes the default and release/continuation ABI metadata; the benchmark already selects each evaluated epoch explicitly.

The workload has 1,000 documents and 2,253 Relationships. The sparse cases have 112 (4.97%) and 225 (9.99%) qualified Relationships. Measurements used macOS 26.4.1, Apple M4 Max, 36 GiB RAM, and Java 26.0.2, with a fresh 2 GiB maximum-heap JVM per backend and no concurrent tests, proof runs, or builds.

Exact-count median latency, milliseconds (cold cache):

| Backend | Legacy 0% | V9 0% | V9 5% | V9 10% | Qualified prefix | Expired prefix |
|---|---:|---:|---:|---:|---:|---:|
| datascript | 1.519 | 1.764 | 2.560 | 3.670 | 3.229 | 2.455 |
| datomic | 1.659 | 1.842 | 2.863 | 4.131 | 4.608 | 3.536 |
| datahike | 1.736 | 1.898 | 2.976 | 4.067 | 3.609 | 2.793 |
| datalevin | 23.033 | 23.664 | 24.970 | 26.310 | 25.868 | 28.634 |

The [CSV](metrics.csv) contains first-call latency, medians, p95 batch means, allocations, sample counts, and native read counts for every operation. These percentiles describe batch means, not individual-request tail latency or a service-level promise. The [raw archive](raw-reports.tar.gz) retains every batch sample and budget; [provenance](provenance.json) pins production source hashes, the benchmark, dependency declarations, and the Datalevin fork commit. The executable checker result is [budget-comparison.edn](budget-comparison.edn).

Earlier prototype/isolation runs failed fixed limits and led to changes in compiled-definition reuse and measurement isolation. Those failures were not accepted as release evidence. Version 5 uses the documented fresh-JVM, fixed-context protocol and the original numeric budgets without relaxation. The complete protocol and limits are in [the benchmark guide](../../qualified-authorization.md).
