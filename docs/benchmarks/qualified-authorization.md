# Qualified authorization release qualification

The numerical budgets in `qualified-authorization-budgets.edn` were recorded
before running the semantic workload. They are local release regression limits,
not service latency promises. A failed limit blocks acceptance; changing a limit
requires a documented reason and a fresh comparison, not silently accepting a
slower result.

Compare the disabled epoch's ordinary graph with the enabled epoch on the same
host, backend, graph size, public operation, and cache mode. The ordinary case
allows 1.5 times baseline plus 100 microseconds and 32 KiB per request for request
scope/certificate construction. Sparse 5% and 10% cases allow respectively
2 and 3 times baseline plus 250/500 microseconds and 64/128 KiB. Concentrated
10% cases allow 5 times baseline plus 2 milliseconds and 512 KiB because a
bounded output page may have to examine an inactive prefix. Both median and
p95 batch latency must meet the relative limit. Absolute p95 limits are 5 ms
for points, 250 microseconds for completed-answer hits, 250 ms for a 50-item
page, and 2 seconds for an exact 1,000-document count. These limits deliberately
include public request selection, admission, context, and result construction.

The workload uses the actual optional JVM CEL evaluator. It measures direct,
negative, exclusion, arrow, recursive, first-page, continuation, exact-count,
and completed-answer-hit operations. Qualifiers are distributed over the
entire generated Relationship set; the report records exact counts and density.
Qualified prefixes use active Caveats/expiry, while expired prefixes exercise
skipping before Caveat evaluation, including subtracting Relationships. Each
document also has a parent edge, and every fourth document has a ban.

Cold requests disable answer and decoded-data caches. Warm requests use the
same fixed context and `:populate-cache? false`, after public physical
Relationship inspection populates decoded data without publishing authorization
answers. Both epochs therefore perform fresh authorization; the harness rejects
unexpected completed-answer hits. The compiled-program cache remains independent.
Completed-answer-hit requests instead keep publication enabled and their context
fixed. The clock is fixed within each case; advancing-clock correctness is
covered by the native cache trace and cursor conformance gates.

An initial warm prototype varied an unused context field. That was an invalid
isolation method: the ordinary epoch could reuse internal decisions while the
qualified epoch correctly included the whole context in its scope. Measurement
version 3 replaces that prototype with public read-only cache requests and data
preloading on both epochs. No latency or allocation threshold was changed.

JVM allocation is measured with the current thread's allocation counter and
excludes asynchronous backend work. The backend matrix, raw batch samples,
runtime/host information, first-call latency, and measured native qualification
reads must be retained with the result. Read instrumentation is separate from
timing: ordinary edges and completed-answer hits must perform zero qualifier
data reads; a cold request may fetch each qualifier, shared definition, and
Relation at most once. Each operation warms for at least one second before 100 measured batches.
Counts use five requests per batch, pages three, and points 10–50, as recorded
in each metric. These are percentiles of batch means, not a per-request tail
latency claim. The longer warmup, larger sample count, and count/page batches were introduced
after single-request samples exposed compilation/pause sensitivity near the
fixed limits; the latency and allocation thresholds were not changed. Continuation timing includes
both the first page and its resume under the same context. Completed-answer
hits use the root grant, which is qualified in every nonzero-density case, and
must report an actual answer-cache hit with zero qualification-data reads.

Run the benchmark through the project nREPL with `:datalevin-dev:caveats-jvm`
loaded. The harness is in `eacl.bench.qualified-authorization-test`; regular unit
test runs do not execute benchmarks. Release acceptance requires all four
backend reports and the checked budget comparison. ClojureScript semantic
correctness is separately gated by the advanced Node suite; these JVM allocation
and latency limits do not claim to measure JavaScript or networked deployments.

Measurement version 4 adds an assertion that the first 20 physical member
Relationships actually form the intended native qualified/expired prefix. Runs
use a fresh JVM per backend with `-Xms512m -Xmx2g` and no concurrent test or proof
work, so retained state from earlier test suites does not affect measurements.
Version 5 retains that protocol after optimizing definition-content reuse. The
checker rejects duplicate reports, mixed versions/budgets, missing operations,
and incomplete sample sets.
