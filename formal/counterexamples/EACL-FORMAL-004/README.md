# EACL-FORMAL-004 — proofless cursor mixes graph snapshots

With `:proof-mode :none`, both schema and relationship proofs are `nil`.
`dependency-context` hashed those values and the cursor equivalence check
treated every later snapshot as proof-equivalent. The graph head was carried
for fallback but not compared during the fast path.

In the minimized trace, page 1 returned `a1`, an `a2` relationship was added,
and page 2 returned the newly authorized `a2` using page 1’s old frontier.
The correct exact-snapshot continuation is `a3` (or a typed retention/conflict
error when exact continuation is unavailable).

Reproduce through nREPL:

```clojure
(do
  (require 'eacl.datomic.cache-review-regressions-test :reload)
  (clojure.test/test-var
   #'eacl.datomic.cache-review-regressions-test/proofless-cursor-uses-exact-snapshot-test))
```

Fixed in the shared CLJC relay: missing proofs and nondeterministic adapters
now produce an exact-snapshot identity that commits to snapshot id and graph
head. Only complete deterministic proof identities can lift to a newer graph.
