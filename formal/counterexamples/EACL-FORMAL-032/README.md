# EACL-FORMAL-032 — typed-error projection concealed portable data drift

Recursive shadow comparison previously retained only keyword and integer
`ExceptionInfo` fields. Two public typed errors with the same error keywords
but different strings, booleans, vectors, or nested maps therefore appeared
equal to rollout telemetry.

The comparison boundary now canonicalizes the complete portable `ex-data` map.
Only result variants and changed top-level field names reach the reporter, so
authorization values and error payloads remain undisclosed. Non-portable or
untyped exception data produces an explicit comparison-unavailable diagnostic
and cannot count toward a verified-authority rollout gate.

Replay through the formal nREPL suite:

```text
EACL_NREPL_PORT=<port> bin/formal counterexample-replay
```
