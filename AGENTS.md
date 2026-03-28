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

If no nREPL is running, start one with the `dev` alias loaded:

```
clojure -M:dev:nrepl
```

Restart on `8088` through nREPL:

```
clj-nrepl-eval -p <port> "(do (require '[dev :as dev] :reload) (dev/restart-backend! {:port 8088}))"
```

Run a single test namespace:
```
clj-nrepl-eval -p <port> "(require 'some.test-ns :reload) (clojure.test/run-tests 'some.test-ns)"
```

Run all engine tests:
```
clj-nrepl-eval -p <port> "(require 'netcel.test-runner :reload) (netcel.test-runner/run-all!)"
```

Run heavy benchmark/load tests (not part of regular suite):
```
clj-nrepl-eval -p <port> "(require 'netcel.bench-test-runner :reload) (netcel.bench-test-runner/run-all!)"
```

Benchmark tests live under `test/bench/` and should be run only when explicitly validating performance/load behavior.

If you hit `Alias ... already exists` in an nREPL session, run `ns-unalias` on that alias before re-requiring the namespace.

Start nREPL with test paths: `clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version \"1.3.0\"}}}' -A:test -M -m nrepl.cmdline --port 0`
