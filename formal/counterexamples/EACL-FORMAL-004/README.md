# EACL-FORMAL-004 — proofless cursor mixes graph snapshots

With `:proof-mode :none`, both schema and relationship proofs were `nil`.
`dependency-context` hashed those values and the cursor equivalence check
treated every later snapshot as proof-equivalent. The old model silently
treated that equivalence as permission to lift graph-specific resume state.

In the minimized trace, page 1 returned `a1`, an `a2` relationship was added,
and page 2 returned the newly authorized `a2`. That result is valid for weak,
non-exact pagination only when EACL deliberately re-evaluates the scoped query
on the selected current graph and reports `:cursor-recovery :rebased`.
An explicit `at-exact-snapshot` request instead continues on retained history.

Reproduce through nREPL:

```clojure
(do
  (require 'eacl.datomic.cache-review-regressions-test :reload)
  (clojure.test/test-var
   #'eacl.datomic.cache-review-regressions-test/proofless-cursor-falls-back-to-exact-snapshot-test))
```

Fixed in the shared CLJC relay and Datomic runtime: cursor authentication binds
the complete semantic query, non-exact continuation re-evaluates on one
selected current graph, and only explicit exact mode selects retained history.
