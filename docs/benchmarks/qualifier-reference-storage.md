# Qualifier-reference storage qualification

This workload compares the four-slot storage at `21e661e0` with the five-slot
qualifier-eid representation while holding ordinary EACL v8 behavior constant.
Run both checkouts on the same host, runtime, dependency versions, and backend.

## Acceptance budgets

Preserve authorization results and two datoms per logical Relationship. Median
direct-check, scan, page, arrow, and exhaustive-count latency must be at most
1.5× baseline; p95 at most 1.75×. Allocated bytes per operation must stay within
1.5×. Warm exact cache hits must stay within 1.2× median. Serialized Relationship
transaction bytes and freshly populated durable database bytes must stay within
1.5×. Measure migration throughput, peak bytes, restart cost, and bulk write
latency separately; the fresh-store budget does not cover retained migration
history, and successful read budgets do not establish write-latency neutrality.

## Reproduction

The graph has 2,000 documents and 4,001 Relationships. One user directly views
every document and owns the account reached through each document's account
Relation. An ungranted user supplies negative probes. Scans read 100
Relationships; pages and continuations read 50 resources; exact counts return
2,000. Disable caching except for the warm exact-hit case.

Start separate baseline and candidate nREPLs with the required backend aliases.
Run [the paired driver](../../bin/qualifier-reference/run.py) from the repository
root with explicit `--baseline-port` and `--candidate-port`. It loads the same
[workload](../../modules/eacl-datalevin/test/eacl/bench/qualifier_storage_test.clj)
in both sessions and alternates A/B order across three trials. Its default
output is ignored `target/qualifier-reference-reproduction/`.

Through nREPL, load [the summary script](../../bin/qualifier-reference/summarize.clj)
with `*command-line-args*` bound to the report directory. It checks the unchanged
latency/allocation budgets. Preserve failing trials alongside confirmations in
the ignored run directory; record any warmup/batching adjustment on both sides.

For CLJS, build `eacl.bench.qualifier-cljs` through nREPL using `cljs.build.api/build`
with Node target in each checkout, then pass an ignored output filename to each
Node entry point. Use [the CLJS summary](../../bin/qualifier-reference/summarize-cljs.clj)
for three matched trials. The baseline needs the candidate test namespace on
its classpath while retaining baseline production sources.

The separate `qualifier-density-test`, `qualifier-migration-test`, and
`qualifier-admission-test` benchmark namespaces cover physical occupancy,
interrupted migration/reopen/resume, and million-Relationship admission.
Current-thread allocation excludes asynchronous and native/off-heap work;
transaction EDN size is not binary wire size, and in-memory backends cannot
establish durable-space limits. Keep raw measurements and machine provenance
under `target/` or in CI artifacts, never in Git.
