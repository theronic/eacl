# EACL-FORMAL-048 — the routing resource gate measured JVM history

The routing-certificate theorem and generated checker passed, as did the
complete verified-authority suite. The final resource gate nevertheless failed
in GitHub Actions because its measurement was not isolated from JVM history.

The gate used five warmup calls per size and ran after two ClojureScript
compiler builds. On a fresh local HotSpot JVM, the first run measured a
`4.1788x` max/min normalized per-node latency ratio, close to the unchanged
`5x` ceiling. Repeating the same gate after the generated boundary had reached
steady-state produced ratios between `1.019x` and `1.152x`. That is a
measurement-order defect, not evidence of superlinear routing work. The CI
wrapper also threw before printing the result, so the failed observation was
absent from the Actions log.

The routing gate now runs first after starting a fresh heap-bounded resource
JVM, uses 40 warmups and 11 samples per size, and prints its complete
observation before failing. No threshold was relaxed. A fresh patched run
measured 5.31–5.35 KiB allocated per node, a `1.007x` allocation ratio, and a
`1.73x` latency ratio while retaining the exact `P + 2V + E` logical counters.

Reproduce through a bounded nREPL:

```clojure
(require 'eacl.formal.routing-certificate-benchmark :reload)
(eacl.formal.routing-certificate-benchmark/run-gate!)
```
