# EACL-FORMAL-043 — verified authority left acyclic roots handwritten

`verified-authoritative` previously selected generated indexed traversal only
for permission roots that depended on a recursive strongly connected
component. Direct, alias, and other acyclic roots continued through the
optimized handwritten Clojure/ClojureScript engine.

The narrower Dafny source-specialization lemmas check ordered merge, leapfrog
reseek, path materialization, and outer-fold control, but they do not prove the
complete handwritten acyclic engine. In particular, `AcyclicForward` and
`AcyclicReverse` project the already-computed formal authorization relation;
they are not an executable refinement of every host callback and lazy-stream
interaction. The old dispatch therefore made complete generated authority
false even when every existing differential passed.

The correction is to stop making that false claim. Recursive roots execute the
generated indexed state machine. Certified acyclic roots execute the optimized
host engine, while generated authority decides route classification, page
windows, counts, and work acceptance. The ordered merge, bound probe, path
materialization, leapfrog intersection, and outer path fold are explicitly
classified as digest-locked source specializations. They are not mislabeled as
generated production code.

This distinction matters for certification: the recursive generated artifact
and the acyclic source-specialization evidence have different trusted
boundaries and different remaining obligations. A passing differential is not
allowed to erase that difference.

Reproduce through nREPL by running:

```clojure
(require 'eacl.formal.state-trace-differential-test :reload)
(clojure.test/test-vars
 [#'eacl.formal.state-trace-differential-test/generated-decisions-and-source-specialized-acyclic-paths-preserve-order-and-point-locality])
```
