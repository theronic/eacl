# EACL

You are an expert Clojure programmer assisting with development of EACL.

# Agentic Development Rules & Guidelines

Follow the agentic coding [rules](.rules/AGENTS.md) and sub-rules.

## Testing

**IMPORTANT: Always run tests via nREPL, never via `clojure` CLI.**
JVM startup from cold is far too slow. Use a running nREPL instead.

# Clojure Parenthesis Repair

The command `clj-paren-repair` is installed on your path.

Examples:
`clj-paren-repair <files>`
`clj-paren-repair path/to/file1.clj path/to/file2.clj path/to/file3.clj`

**IMPORTANT:** Do NOT try to manually repair parenthesis errors.
If you encounter unbalanced delimiters, run `clj-paren-repair` on the file
instead of attempting to fix them yourself. If the tool doesn't work,
report to the user that they need to fix the delimiter error manually.

The tool automatically formats files with cljfmt when it processes them.

## Starting the project

Use this exact sequence:

```
clj-nrepl-eval --discover-ports
```

If no nREPL is running, start one with the `dev` alias loaded (add `:test`
when you need the CI test runner on the classpath, exactly as CI does):

```
clojure -M:dev:nrepl
clojure -M:dev:test:cljs-test:nrepl --port 7788
```

Run a single test namespace:
```
clj-nrepl-eval -p <port> "(require 'some.test-ns :reload) (clojure.test/run-tests 'some.test-ns)"
```

Run the CI-equivalent battery (all four module test roots, benchmark and
formal-artifact suites excluded) on an nREPL started with the `:test` alias:
```
clj-nrepl-eval -p <port> "(do (require '[cognitect.test-runner.api :as runner] :reload) (runner/test {:dirs [\"modules/eacl/test\" \"modules/eacl-datomic/test\" \"modules/eacl-datascript/test\" \"modules/eacl-datahike/test\" \"src-build\"] :excludes [:benchmark :formal-artifact]}))"
```

Heavy benchmark/load suites are tagged `^:benchmark` and live under each
module's `test/eacl/bench/` (for example `modules/eacl-datomic/test/eacl/bench/`);
run them only when explicitly validating performance/load behavior:
```
clj-nrepl-eval -p <port> "(do (require 'eacl.bench.pagination-test :reload) (clojure.test/run-tests 'eacl.bench.pagination-test))"
```

If you hit `Alias ... already exists` in an nREPL session, run `ns-unalias` on that alias before re-requiring the namespace.

Run the DataScript ClojureScript build (`cljs.main/-main ... -c eacl.datascript.cljs-test-runner`, then `node target/datascript-cljs-test.js`) last, exactly as CI does: `cljs.main/-main` calls `shutdown-agents` when it finishes, after which every `future`-based test in that JVM fails with `RejectedExecutionException` until the nREPL is restarted.

After any edit under a public source root, run `bin/formal source-closure`.
The generated report lives under ignored `target/formal/verification/`; do
not commit it.
