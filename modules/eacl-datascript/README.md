# `eacl-datascript`

DataScript adapter for EACL.

Responsibilities:

- DataScript schema installation and canonical schema storage
- DataScript SPI implementation for CLJ and CLJS
- adapter-local tx stamp/token support
- DataScript contract tests and adapter-specific edge cases

Useful workspace test commands:

- `clj-nrepl-eval -p <port> "(do (require 'eacl.datascript.contract-test :reload-all) (clojure.test/run-tests 'eacl.datascript.contract-test))"`
- `clojure -M:datascript-cljs-test`
