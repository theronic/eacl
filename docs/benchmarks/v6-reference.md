# v6 cursor-tree reference benchmark

This branch preserves the optimized v6 cursor-tree implementation and a
reproducible public-API benchmark for comparisons with later EACL versions.

The baseline is commit `f8a1a98`. The benchmark graph contains:

- 300 accounts
- 4 teams per account
- 2 VPCs per account
- 500 servers per account
- 150,000 terminal servers
- 2,100 account/team/VPC intermediate resources
- 4 permission paths
- pages of 50 results

Run it in a fresh JVM through nREPL:

```shell
clojure -M:dev:nrepl
clj-nrepl-eval -p <port> --timeout 600000 \
  "(do (require 'eacl.bench.version-comparison :reload)
       (eacl.bench.version-comparison/run-reference!))"
```

The runner verifies that all 150,000 resources are returned exactly once and
reports the complete 3,000-page walk, pages per second, and page-latency
samples at pages 1, 500, 1,000, 2,000, and 3,000.

Always compare versions in separate fresh JVMs. Seeding, warmups, and repeated
sample measurements are excluded from the complete-walk timing. The reported
complete-walk result is the median of three walks.

## Retained result

The machine-readable result from 2026-07-28 is stored in
`docs/benchmarks/results/v6-f8a1a98-2026-07-28.edn`.

Environment: Apple M3 Pro, 18 GiB RAM, macOS arm64, Temurin OpenJDK 24.0.1.
Debug logging was disabled for the measured lookup calls.

| Measurement | v6 `f8a1a98` |
|---|---:|
| Complete walk, median of 3 | 41.041 s |
| Throughput | 73.10 pages/s |
| Page 1 median | 25.632 ms |
| Page 500 median | 21.492 ms |
| Page 1,000 median | 17.479 ms |
| Page 2,000 median | 8.924 ms |
| Page 3,000 median | 1.127 ms |
