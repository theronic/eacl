# EACL-FORMAL-050 — warm `can?` gate used an ambiguous single batch

The forced-authority heavy suite failed only its warm permission-check latency:
1,004.339 µs against an unchanged 1,000 µs ceiling. The cold path completed in
1,039.13 µs against 1,500 µs, completed-cache hits took 24.31 µs against
1,000 µs, and every semantic assertion passed.

The gate had executed only 2,000 warmup calls before taking one median over
5,000 calls. That observation alone could not distinguish steady-state service
time from the deep Clojure-to-generated-Java path's HotSpot transition on a
shared runner. A fresh local 1 GiB JVM with forced generated authority measured
440.42 µs warm, 440.90 µs cold, and 17.25 µs for a completed-cache hit.

The fixed gate performs 15,000 warmup calls and takes the median of three
independent 5,000-call batch medians. A local forced-authority run measured
516.6875, 515.2295, and 519.667 µs. The subsequent complete forced-authority
heavy suite measured 429.083, 431.208, and 430.208 µs (430.208 µs aggregate),
435.44 µs cold, and 14.79 µs for a completed-cache hit. On the next shared
runner, all three batches plateaued just above the ceiling. That was retained
as EACL-FORMAL-051 and fixed in the engine; it is evidence that the stronger
harness detects sustained slow service instead of hiding it behind one sample.
