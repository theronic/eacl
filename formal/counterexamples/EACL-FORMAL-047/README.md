# EACL-FORMAL-047 — lookup cursor falsely reported a current rebase

A recover-current lookup cursor is allowed to continue on the current graph
after its dependency proof changes, but only by its authenticated stable result
identity. The prior path-frontier state is proof-specific and cannot be reused.

The streaming legacy path did not follow that rule for ordinary `:lookup-eid`
cursors. Its adapters labeled the request `:rebased` while passing the old
frontiers into the current-schema lookup. It also never checked whether the
cursor's result still belonged to the current permission denotation. Generated
authority correctly restarted when the identity was absent, exposing a
legacy/generated disagreement in the forced-authority suite.

Both generic and Datomic adapters now mark `:lookup-eid` cursors for identity
rebasing. The streaming engine point-checks the old result EID under the
current permission. A surviving result resumes exclusively from its EID after
discarding all old frontiers. A missing result drops the bound and restarts the
same authenticated query in the requested direction. Exact-snapshot behavior
is unchanged.

`PageWindow.PaginateRelationshipContinuation` and
`RebaseCursorBoundChunked` already prove the complete-denotation
rebase-or-restart law. The implementation regressions exercise the streaming
refinement on Datomic and on shared DataScript CLJ/CLJS code; the full
forced-authority suites cover Datomic, Datahike, and DataScript.

Reproduce through nREPL:

```clojure
(require 'eacl.datascript.contract-test :reload)
(require 'eacl.datomic.schema-basis-test :reload)
(clojure.test/test-vars
 [#'eacl.datascript.contract-test/current-lookup-cursor-restarts-when-result-identity-disappears-test
  #'eacl.datomic.schema-basis-test/page-token-recovers-on-the-current-schema-generation-test])
```
