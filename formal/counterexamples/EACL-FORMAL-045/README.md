# EACL-FORMAL-045 — abstract collection cost hid quadratic targets

The generated indexed engine was functionally correct but its resource model
was false at the target boundary. Dafny counted one abstract set insertion per
new grant. Dafny 4.11's Java runtime implemented immutable union by copying a
`HashSet`, while its JavaScript runtime represented sets and maps as arrays.
JavaScript sequence suffixes and appends also copied represented prefixes.
Finally, the executable scan validator evaluated pairwise strict ordering as a
nested quantified loop.

The fixed-heap 15,000-result JVM pagination fixture made the defect undeniable:
a bare reverse page took about 5.7 seconds even though the logical counter was
linear. A smaller JavaScript fixture scaled from roughly 16 ms at 128 results
to 610 ms at 1,024 results. CPU profiling first identified the pairwise
validator; after that was made linear, V8 profiling exposed sparse Array
backing-store allocation in every persistent suffix view.

The Dafny contract still defines pairwise strict ordering.
`StrictlyIncreasingIffAdjacent` proves equivalence with the executable
adjacent-pair predicate, so the performance fix does not weaken accepted scan
responses. Generated Java sets/maps now use Clojure persistent collections.
Generated JavaScript sets/maps use Immutable 5.1.9 HAMTs, and sequences use
persistent concat/slice views with a virtual Array length. The exact patch
sources, patcher, dependency lock, and target tests are part of the manifest's
trusted source digest.

After the fix, the JVM reverse-page maximum median measured 2.78 ms in the
same fixed-heap
fixture. JavaScript measured approximately 31–32 microseconds per result from
1,024 through 16,384 results, with a normalized per-result ratio of 0.993.
These are host-specific regression measurements, not a proof of peak heap or
worst-case latency.

Reproduce through the existing nREPL-backed workflows:

```sh
EACL_NREPL_PORT=<cljs-port> \
  formal/smoke/cljs/run-indexed-traversal-benchmark

EACL_NREPL_PORT=<jvm-port> \
  formal/smoke/clj/run-verified-authority nonbenchmark
```
