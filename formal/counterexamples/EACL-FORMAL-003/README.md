# EACL-FORMAL-003 — authenticated cache erases admission kind

The signed shared-cache adapter stored every envelope under the legacy weighted
kind `:authenticated-v3`. The local provider therefore could not enforce
`:two-hit-kinds` or `:kind-max-weight` for `:can?`, lookup, or count entries.

This does not forge an authorization answer: the inner value remains
authenticated. It defeats memory/admission controls and can increase cache
pollution and retained memory under one-shot traffic.

Reproduce through nREPL:

```clojure
(do
  (require 'eacl.datomic.cache-test :reload)
  (clojure.test/test-var
   #'eacl.datomic.cache-test/authenticated-store-preserves-logical-kind-test))
```

Fixed by preserving the authenticated semantic key's `:kind` at the provider
boundary. The signed inner envelope and its validation are unchanged.
