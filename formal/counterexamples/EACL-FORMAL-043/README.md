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

Verified-authoritative mode now sends every valid permission root through the
generated indexed state machine. Shadow mode retains the optimized acyclic
path as primary but executes generated indexed authority as the alternate.
Legacy mode remains host-only for rollback and performance comparison.

The two algorithms intentionally encode a page position differently: legacy
uses a lookup EID and indexed traversal carries an ordinal plus EID. A
cross-algorithm shadow comparison therefore compares ordered public data, page
flags, and normalized cursor EID positions. Exact internal continuation state
and logical work counters remain compared when both sides run the indexed
algorithm; cross-algorithm performance is enforced by separate resource gates.

Reproduce through nREPL by running:

```clojure
(require 'eacl.formal.state-trace-differential-test :reload)
(clojure.test/test-vars
 [#'eacl.formal.state-trace-differential-test/generated-mode-routes-and-does-not-reorder-acyclic-multipath-pages
  #'eacl.formal.state-trace-differential-test/acyclic-shadow-compares-generated-authority-and-legacy-stays-host-only])
```
