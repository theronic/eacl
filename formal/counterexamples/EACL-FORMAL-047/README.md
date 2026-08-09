# EACL-FORMAL-047 — lookup cursor falsely reported a current rebase

The former recover-current design tried to continue on a current graph after
its dependency proof changed. The prior path-frontier state was proof-specific
and could not be reused safely.

The streaming legacy path did not follow that rule for ordinary `:lookup-eid`
cursors. Its adapters labeled the request `:rebased` while passing the old
frontiers into the current-schema lookup. It also never checked whether the
cursor's result still belonged to the current permission denotation. Generated
authority correctly restarted when the identity was absent, exposing a
legacy/generated disagreement in the forced-authority suite.

Final v8 deletes that mechanism. Current continuation requires equal proof;
verified exact-snapshot reconstruction is the only changed-proof continuation.
DataScript has no time travel and fails changed-proof cursors typed stale.

Reproduce through nREPL:

```clojure
(require 'eacl.datascript.keyset-recursion-test :reload)
(clojure.test/test-vars
 [#'eacl.datascript.keyset-recursion-test/order-perturbing-write-rejects-current-only-cursor-test
  #'eacl.datascript.keyset-recursion-test/revoked-boundary-is-stale-test])
```
