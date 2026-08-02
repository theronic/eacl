# EACL-FORMAL-005 — portable cursor expiry differs from Datomic

The portable CLJ/CLJS cursor used by DataScript and Datahike accepted a token
while `now == expires-at`; Datomic rejects its encrypted cursor at that same
boundary. The portable decoder also ignored the injected test clock, preventing
deterministic cross-runtime boundary vectors.

Reproduce through nREPL:

```clojure
(do
  (require 'eacl.secure-format-test :reload)
  (clojure.test/test-var
   #'eacl.secure-format-test/portable-cursor-expiry-boundary-test))
```

The correction must preserve authentication-before-expiry validation and use
the same inclusive expiry boundary in CLJ, CLJS, and the formal cursor model.

Fixed by honoring the deterministic clock option and rejecting when
`now >= expires-at`; authentication and payload validation still run first.
