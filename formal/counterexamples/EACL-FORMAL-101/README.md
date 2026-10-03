# EACL-FORMAL-101 — a direct specialization was selected for a subject type it cannot merge

Found by `eacl.operator-engine.subject-type-differential` (random schemas over
two subject types, compared with a reference evaluator), written to review
the anchor rule of EACL-FORMAL-100, and minimized to:

```
definition user {}
definition agent {}
definition document {
  relation viewer: user | agent
  relation banned: agent
  permission unbanned = viewer - banned
}
```

A user who is a `viewer` of a document holds `unbanned` on it: `banned`
declares agents alone, so no user is banned. The check answered. Listing the
user's `unbanned` documents, counting them, and listing or counting the
document's users failed with `:eacl.operator/invalid-seekable-plan`
("Direct specialization is not eligible.").

An intersection or exclusion whose operands are all relations seals a direct
specialization: one relation scan per operand, merged in entity order. It
holds those relations per subject type that every operand declares; here,
agents. The lookup selected the specialization by its kind alone, and the
merge then refused the subject type it has no scan for. The same held for an
intersection (`viewer & editor` with `editor: user`, requested for agents)
and for one relation written twice (`viewer - viewer`).

The defect is older than the anchor rule of EACL-FORMAL-100, but that rule
reached it from plans that had answered: `subscriber & (viewer - banned)`
with `subscriber: user:* | agent:*` generated from `subscriber` before and
generates from the exclusion now, so a user's listing of it began to fail.

The lookup now selects a specialization only for a subject type it serves:
every operand has a relation for it, each a different one. Any other subject
type takes the generic cover, whose exact predicates decide each candidate.

Reproduce through a dev nREPL:

```
clj-nrepl-eval -p <port> "(require 'eacl.operator.lookup-test :reload) (clojure.test/run-tests 'eacl.operator.lookup-test)"
clj-nrepl-eval -p <port> "(require 'eacl.operator.subject-type-differential-test :reload) (clojure.test/run-tests 'eacl.operator.subject-type-differential-test)"
```
