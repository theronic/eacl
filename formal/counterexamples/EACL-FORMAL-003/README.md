# EACL-FORMAL-003 — authenticated cache erases admission kind

The signed shared-cache adapter stored every envelope under the legacy weighted
kind `:authenticated-v3`. The local provider therefore could not enforce
`:two-hit-kinds` or `:kind-max-weight` for `:can?`, lookup, or count entries.

This does not forge an authorization answer: the inner value remains
authenticated. It defeats memory/admission controls and can increase cache
pollution and retained memory under one-shot traffic.

The obsolete provider cannot be reconstructed through the public API anymore.
The retained regression instead verifies that provider configuration is rejected:

```clojure
(do
  (require 'eacl.cache-test :reload)
  (clojure.test/test-var
   #'eacl.cache-test/cache-configuration-is-count-only-and-client-private-test))
```

The provider boundary, its weighted-kind dispatch, and its authentication
envelope were deleted after source closure established that no production
reader consumed them.
