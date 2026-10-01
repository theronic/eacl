# EACL-FORMAL-084 — a faulting branch beside a grant decided by evaluation order

Recorded from the Rust port's specification review (eacl-rust spec/10 §6.1,
NEW-1; spec/12 §3.12). `permission view = reader + editor` with a faulting
`reader` edge and a plain `editor` edge was a grant when a route evaluated
`editor` first and an evaluation failure when it evaluated `reader` first.
Composition was fault-dominant and every route stopped at the first decisive
operand, a fault included. The order was a function of sealed rule ordinals,
canonical node ids, entity ids, scan chunks, batch composition and route, so
recreating entities, renaming a relation or adding an unrelated intersection
could flip the answer. PR #209 pinned one such order for recursive operators.

Composition is now strong-Kleene, pointwise per completion of the residual
Caveat atoms. A definite grant absorbs a fault in a union; a definite denial
absorbs one in an intersection, an arrow, or an exclusion's left side, and a
definite subtracted grant makes an exclusion false. A fault is never decisive,
so every route keeps looking for an absorber. The answer is a function of the
schema, the relationships, the time and the request context; the regression
permutes operand order, relation declaration order, entity creation order and
relationship order, and compares every public route, cache on and off.

Wildcard subjects (`user:*`) showed the same dependence. Membership in such a
relation is the union of the subject's own relationship and the wildcard's.
The scalar and vector evaluators stopped at a definite or faulting own tuple,
while the tabled recursive evaluator always demanded both, so a definite own
grant beside a faulting wildcard Caveat granted on one route and failed on the
other. Every route now takes the union, and a definite grant through either
relationship absorbs a fault through the other.

Reproduce through a dev nREPL:

```
clj-nrepl-eval -p <port> "(require 'eacl.datascript.kleene-fault-test :reload) (clojure.test/run-tests 'eacl.datascript.kleene-fault-test)"
```
