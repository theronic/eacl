# EACL-FORMAL-044 — current recursive cursors discarded stable progress

When the selected graph proof changed, `:recover-current` correctly refused to
reuse the old snapshot. The recursive cursor recovery path then discarded the
cursor boundary unconditionally and restarted from the first result, even when
the cursor's authenticated result identity still existed in the newly
computed answer.

That behavior was safe from unauthorized disclosure, but it was an avoidable
availability and pagination defect: a consumer could repeatedly receive
already-seen results after unrelated writes, and a write that appended a new
recursive result could fail to return that new tail from the stale cursor.

Recovery now recomputes the current complete recursive denotation, searches it
for the cursor's stable result EID, and continues exclusively after or before
the result's new ordinal. If the stable result no longer belongs to the answer,
the same authenticated and query-scoped request restarts from its first page.
Exact-snapshot continuation is unchanged.

`PageWindow.RebaseCursorBound` is executable Dafny code used by verified
authority in both Clojure and ClojureScript. It proves that a rebound ordinal
indexes the requested EID, that a restart occurs only when the EID is absent,
and that the exact logical inspection count is at most the current answer
size. Duplicate identities are accepted defensively and the first occurrence
is authoritative, matching a linear search; valid EACL denotations are unique.
The strict host boundary rejects a generated result that contradicts its
input.

Reproduce through nREPL by running:

```clojure
(require 'eacl.datomic.recursive-cache-test :reload)
(clojure.test/test-vars
 [#'eacl.datomic.recursive-cache-test/recursive-cursor-rebases-after-relevant-write-test
  #'eacl.datomic.recursive-cache-test/recursive-cursor-rebases-after-unrelated-basis-churn-test])
```
