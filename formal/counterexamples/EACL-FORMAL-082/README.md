# EACL-FORMAL-082 — a faulting edge that reaches no resource failed walks

Found by the Rust port's differential campaign (eacl-rust `BUGS.md`,
EACL-RS-007). One caveated edge `group:r#member@project:a` faults when the
request context cannot evaluate its Caveat. No organisation has a `manager`
edge to `group:r`, so no organisation's denotation depends on it, and every
point check of an organisation answered `:no-permission`. A reverse
enumeration from `project:a` nevertheless released and qualified that row
before discovering that no arrow continued from it, threw
`:eacl.authorization/evaluation-failure`, and so `lookup-resources` and
`count-resources` failed on both the least-path (acyclic) and the
first-discovery (recursive) routes.

Strong-Kleene fault semantics carry the fault as path evidence. The row joins
its arrow continuation like any other row; with no continuation the path is
false, and the walk returns an empty page and a zero count. A walk fails only
when it consumes a candidate whose complete decision faults.

A wildcard subject listing had the same flaw. The `*` entry of a page names
the touched subjects the wildcard does not grant, and building that list
decided every touched subject, threw on the first faulting one, and failed the
page even when the walk had not reached that subject's own candidate. A
faulting subject is now excluded like any subject without a definite grant;
its own candidate fails only the page that consumes it.

The eacl-rust repro passes the string `"b"` for the `int` parameter `n`. That
mismatch is now rejected before evaluation by fail-fast context admission
(`:eacl.caveat/invalid :context-type`, with `:parameter "n"`), on every
operation alike. The closing regression uses a missing map key instead, which
only evaluation can detect. The repro's `c1` never reads `n`, which SpiceDB's
schema reader rejects as an unused parameter, so the fixture's `c1` also tests
`n >= 0`.

Reproduce through a dev nREPL:

```
clj-nrepl-eval -p <port> "(require 'eacl.datascript.kleene-fault-test :reload) (clojure.test/test-var #'eacl.datascript.kleene-fault-test/a-faulting-edge-that-reaches-no-resource-never-fails-a-walk)"
```
