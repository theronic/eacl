# `eacl-datascript`

DataScript adapter for EACL.

This module carries the EACL v7.3 behavior into the v8 shared CLJ/CLJS core,
including direction-scoped cursor frontiers, exhausted-path pruning, and
fail-closed schema/query validation. The adapter retains its existing
`:limit`/`:cursor` API so current browser consumers can upgrade by changing
only their Git SHA pins. It implements the v8 `delete-object!` protocol method,
but Datomic-specific basis consistency and authorization caching remain in
`eacl-datomic`.

Responsibilities:

- DataScript schema installation and canonical schema storage
- DataScript SPI implementation for CLJ and CLJS
- adapter-local tx stamp/token support
- DataScript contract tests and adapter-specific edge cases

Useful workspace test commands:

- `clj-nrepl-eval -p <port> "(do (require 'eacl.datascript.contract-test :reload-all) (clojure.test/run-tests 'eacl.datascript.contract-test))"`
- `clj-nrepl-eval -p <port> "(do (require '[cljs.main :as cljs] :reload) (cljs/-main \"-re\" \"node\" \"-m\" \"eacl.datascript.cljs-test-runner\"))"`
