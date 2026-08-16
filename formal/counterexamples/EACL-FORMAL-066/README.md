# EACL-FORMAL-066 — partial fuel-wave rollback could livelock

The batched indexed driver collected backend scan commands until it reached 64
commands or another stopping boundary. When its 256-transition fuel quantum
ended first, it returned `Yielded` with the state from before the quantum and
discarded the collected commands. The next drive call deterministically
repeated the same transitions and discarded the same commands.

A chain fixture did not expose this because its scheduling shape flushed scans
at other boundaries. A broad recursive account fan-out did: both cache-disabled
and cache-enabled bounded server counts could run until the request timeout
without reaching the 100-result sentinel. The cache was not the cause; it could
only obscure the shared traversal defect.

The corrected Dafny forward and reverse drivers return `NeedScans` with the
current state and every pending request whenever fuel ends with a nonempty
wave. The wave remains request-ordered and bounded at 64. The portable CLJS
driver implements the identical transition. A fuel cut with no pending request
still yields current state normally.

Reproduce the direct generated regressions through a fresh formal-smoke nREPL,
the public fan-out regression through a fresh dev nREPL, and the portable plus
generated-JavaScript regressions with `formal/smoke/cljs/run` as documented in
`docs/formal-verification.md`.
