# EACL-FORMAL-026 — redaction concealed remaining stale-cursor data drift

EACL-FORMAL-023 removed a generated-only direction field, but its regression
compared only the redacted typed shadow view. Full public `ExceptionInfo` data
still differed: legacy exposed bound/actual cursor maps, while generated
authority exposed a generated render-error map.

The public stale-cursor contract is now deliberately minimal. Uncached
recursive traversal and generated render rejection both return only
`:eacl/error :eacl.pagination/stale-cursor`; internal cursor coordinates and
generated state-machine diagnostics stay internal. JVM and JavaScript
regressions compare the complete public map.
