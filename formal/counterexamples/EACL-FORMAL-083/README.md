# EACL-FORMAL-083 — a filtered lookup's result policy decided whether it failed

Found by the Rust port's differential campaign (eacl-rust `BUGS.md`,
EACL-RS-008). `doc:d` is viewable by `user:u` under a Caveat whose parameter
the request omits, so the decision is conditional. The lookup filters on
`doc:d#owner@user:o`, an edge whose Caveat faults. The candidate's value is
the decision composed with the filter edge: a fault in the completions where
the decision is true.

The default `:definite` policy dropped the conditional candidate from the raw
candidate stream before the filter edge was consulted and returned an empty
page. The same request with `:detailed`, or with a context that decided the
viewer true, failed. Treating a conditional as false before a conjunction is a
short cut that only a definite false may take.

Filtered lookups now request a raw candidate stream that keeps every possibly
active decision, faults included. Each decision is composed with its filter
edge before the result policy applies, so both policies fail on the same
candidates; a context that decides the viewer false still returns the empty
page, because a definite denial absorbs the faulting filter edge.

The eacl-rust repro faults through the string `"x"` for the filter Caveat's
`int` parameter. Fail-fast admission counts the filter clause's Relation among
the reachable Caveats and rejects it before evaluation; the closing regression
uses a missing map key. The repro's `c1` never reads `n`, which SpiceDB's
schema reader rejects as an unused parameter, so the fixture's `c1` also tests
`n >= 0`.

Reproduce through a dev nREPL:

```
clj-nrepl-eval -p <port> "(require 'eacl.datascript.kleene-fault-test :reload) (clojure.test/test-var #'eacl.datascript.kleene-fault-test/a-filter-edge-composes-with-every-possible-decision)"
```
