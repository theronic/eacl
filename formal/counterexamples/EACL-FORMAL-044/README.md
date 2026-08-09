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

That intermediate recovery design is superseded. Final v8 never rebases or
restarts a cursor on changed current data. Equal dependency proof may continue
on current. A changed proof may continue only on a verified exact old snapshot
when the backend supports history and no newer freshness floor forbids it;
otherwise the request fails typed stale/conflict. This removes the complete
current-denotation scan that the original fix introduced.

Reproduce through nREPL by running:

```clojure
(require 'eacl.datomic.recursive-cache-test :reload)
(clojure.test/test-vars
 [#'eacl.datomic.recursive-cache-test/recursive-cursor-falls-back-to-exact-snapshot-after-relevant-write-test
  #'eacl.datomic.recursive-cache-test/exact-cursor-fallback-never-violates-newer-freshness-floor-test])
```
