# EACL-FORMAL-034 — CLJS compilation terminated the formal nREPL executor

The formal CLJS launcher invoked `cljs.main/-main` inside the nREPL. That entry
point owns a command-line process lifecycle and calls `shutdown-agents`.
Running the JVM counterexample corpus afterward caused every regression using
`future` to fail with `RejectedExecutionException`.

The launcher now uses `cljs.build.api/build`, which compiles without taking
ownership of the host process. After the Node suite passes, the script submits
and dereferences a new future through the same nREPL. This makes preservation of
the executor a checked runtime postcondition.

Reproduce:

```text
EACL_NREPL_PORT=<port> formal/smoke/cljs/run
EACL_NREPL_PORT=<same-port> bin/formal counterexample-replay
```
