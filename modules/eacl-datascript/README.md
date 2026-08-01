# `eacl-datascript`

DataScript adapter for EACL.

This module implements the EACL v8 public contract in Clojure and
ClojureScript. Permission compilation, recursive fixed-point traversal,
direction-scoped frontiers, Relay windowing, counts, cache validation, and
common errors live in `eacl`; this adapter contains DataScript access and
transaction mechanics.

Responsibilities:

- DataScript schema installation and canonical schema storage
- DataScript SPI implementation for CLJ and CLJS
- current immutable-snapshot selection and object/reference conversion
- snapshot-bound opaque Relay cursors
- database-visible schema and relation cache proofs
- portable authorization caching and recursive continuations
- DataScript contract tests and adapter-specific edge cases

Only `:fully-consistent` current-snapshot reads are supported. Unsupported
consistency or historical promises fail with `:eacl/unsupported-capability`.
The v7 `:limit`/`:cursor` API is replaced by v8 `:first`/`:after` and
`:last`/`:before`; see the
[upgrade guide](../../docs/v8-backend-modules-and-upgrade.md).

Useful workspace test commands:

- `clj-nrepl-eval -p <port> "(do (require 'eacl.datascript.contract-test :reload-all) (clojure.test/run-tests 'eacl.datascript.contract-test))"`
- `clj-nrepl-eval -p <port> "(do (require '[cljs.main :as cljs] :reload) (cljs/-main \"-re\" \"node\" \"-m\" \"eacl.datascript.cljs-test-runner\"))"`
