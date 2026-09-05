# Executable v9 examples

`caveats.clj` exercises the documented public API with a fresh DataScript store
and the actual optional JVM CEL evaluator. It uses an injected clock so expiry
and cursor boundaries are deterministic. No external database or secret is
required. The example expects v9 qualified serving to be activated.

From the repository root, use an existing project nREPL with `:dev:caveats-jvm`
on its classpath:

```sh
clj-nrepl-eval -p "$EACL_NREPL_PORT" '(do (load-file "docs/examples/caveats.clj") (eacl.examples.caveats/run-example!))'
```

Success returns the expected conditional, expiry, pinned/live, renewal and
deletion outcomes. Assertions additionally check detailed count categories,
physical stored-versus-active inspection, and prepared transaction composition.
The client clock starts at 1,000 ms, the ban expires at 1,500 ms, and the grant
expires at 2,000 ms. Those small epoch values are deliberate simulation inputs;
production clients normally use the trusted system clock.
