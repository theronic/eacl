# EACL-FORMAL-038 — routing results were not relationally bound to inputs

The portable boundary validated the shape and scalar types of a generated
routing-certificate result, but initially did not relate that result to the
validated request. A hostile or defective kernel implementation could return
an accepted traversal vector shorter than `node-count`, or counters that did
not describe its alleged execution, and still cross the Clojure boundary.

The boundary now requires every accepted vector to contain exactly
`node-count` Booleans, exactly `2 * node-count` node checks, and exactly
`count(edges)` edge checks. Rejected decisions may stop early but cannot report
more than those maxima. These are boundary postconditions derived from the
Dafny checker contract, not a second authorization algorithm.

The regression runs identically in CLJ and CLJS. Two registered mutants cover
the omitted vector-length and counter relations.
