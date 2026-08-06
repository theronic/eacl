# EACL-FORMAL-041 — leapfrog reseek count omitted side and target

The bounded leapfrog proof and CLJ/CLJS differential agreed on the Boolean
intersection result and the total number of backend reseeks. That evidence did
not compare which input stream was reseeked or the exact inclusive target
supplied to the backend.

A nearby wrong target can preserve the Boolean result while performing extra
backend work, and other fixtures can turn the same error into a missed
intersection. Aggregate count agreement therefore did not establish exact
source-control correspondence.

`AcyclicEngine.dfy` now produces a proved ordered trace of `[side target]`
reseek events. Generated Java and JavaScript compare that trace with the actual
CLJ/CLJS callbacks across the complete 4,100-case campaigns. Independent
wrong-target and wrong-side mutants are killed. Adapter certification checks
inclusive and exclusive scan windows at every materialized fixture EID, rather
than only the first and last EIDs.
