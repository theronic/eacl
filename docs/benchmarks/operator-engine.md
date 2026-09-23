# Operator benchmark inputs

[operator-engine-budgets.edn](operator-engine-budgets.edn) preserves the existing
union-only regression limits, Datahike remote-store ceilings, and physical-route
limits. These are authored inputs. No saved successful run is a test of the
current implementation.

The physical-route suite measures current first-page latency, allocation, and
logical work against these limits on every run. Its local latency limit is
0.56675 ms (the previous baseline plus its existing grace); the CI limit is
5 ms. Neither limit was widened by removing the old measurement file.

Use a project nREPL with the `:dev:test` aliases:

```sh
clj-nrepl-eval -p <port> "(require 'eacl.engine.physical-route-test :reload) (clojure.test/run-tests 'eacl.engine.physical-route-test)"
clj-nrepl-eval -p <port> "(require 'eacl.operator-engine.experiments-test :reload) (clojure.test/run-tests 'eacl.operator-engine.experiments-test)"
clj-nrepl-eval -p <port> "(require 'eacl.baseline.perf :reload) (eacl.baseline.perf/capture-perf!)"
```

The last command writes samples to ignored
`target/benchmarks/perf-clj-datascript.edn`. Compare source revisions on the same
host with alternating campaigns. For remote-store measurements, keep connection
and branch-head I/O separate from index reads, distinguish resident-warm from
cold and adjacent pages, and compare bounded counts separately from exhaustive
counts. Keep samples, source identity, host details, and checker results in CI
artifacts or ignored `target/benchmarks/`.

The [operator fixtures](../../formal/fixtures/operator-engine/README.md) retain
expected behavior and counterexamples exercised against current code.

## Intersection over recursive permissions

`eacl.bench.recursive-intersection-test` (Datahike module, tagged
`:benchmark`) gates `delete = delete_granted & read_account`, where both
operands recurse through `parent`, on a minimal schema and on a ledger schema
whose `read_account` spans about twenty rules, at 636 and 2,077 accounts. Each
case interleaves the intersection with both operands on one warm snapshot. The
budgets were authored before sampling: a full intersection lookup walk takes at
most twice the sum of its operands' walks, and an intersection check at most
twice the sum of its operands' checks.

```sh
clj-nrepl-eval -p <port> "(require 'eacl.bench.recursive-intersection-test :reload) (clojure.test/test-vars [#'eacl.bench.recursive-intersection-test/recursive-intersection-costs-what-its-operands-cost])"
```

Each run writes its samples to ignored `target/benchmarks/recursive-intersection/`.
