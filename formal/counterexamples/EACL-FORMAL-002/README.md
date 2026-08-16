# EACL-FORMAL-002 — recursive continuation cache is unreachable

The authenticated v3 cache redesign disabled all engine continuation handles by
making `continuation-context` return `nil`. Pages remain semantically correct
while replay stays within configured limits, but a walk becomes quadratic and
may reject a valid later page with
`:eacl.recursive-traversal/limit-exceeded`.

The original heavy-suite witness had identical cached and replay work:

- 4,000-node chain, 160 pages;
- cached work: 322,159;
- explicit replay work: 322,159;
- repeated completed-walk work: 322,159;
- continuation hits: none.

Reproduce the minimized regression through nREPL:

```clojure
(do
  (require 'eacl.datomic.lookup-cache-test :reload)
  (clojure.test/test-var
   #'eacl.datomic.lookup-cache-test/recursive-cursors-resume-from-the-client-private-denotation-test))
```

The fix must not restore unauthenticated opaque values in a caller-supplied
provider. Opaque engine state may only be retained in a bounded client-private
store keyed by the selected snapshot's complete authenticated proof identity.

The final v8 implementation satisfies that boundary with an explicitly
requested completed denotation. A later page slices the client-private
denotation without backend traversal. A different client or an unretained
artifact replays from its independently selected immutable snapshot.
