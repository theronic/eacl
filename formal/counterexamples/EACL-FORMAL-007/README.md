# EACL-FORMAL-007 — CLJS leaks host reader errors across the wire boundary

Both host readers reject a map with the same key twice and an unknown tagged
value. The CLJS reader reports those failures as `ExceptionInfo`, however, and
the decoder previously assumed that every `ExceptionInfo` was an EACL error
that should be rethrown unchanged. JVM reader failures took the generic catch
path and were normalized.

The CLJS path therefore failed closed but leaked raw `:reader-exception` data
instead of the public `:eacl.format/invalid` / `:malformed` contract. Callers
that handle typed EACL errors could treat identical hostile inputs differently
across runtimes.

The retained regression is the hostile-bounds section of
`eacl.secure-format-test/canonical-portable-format-test` in both runtime
targets.
