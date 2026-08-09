# EACL-FORMAL-008 — dependency-proof provider failure aborts authorization

`cache/resolve!` already isolated cache-store reads and writes, but it called
the adapter's `schema-proof` and `relation-proof` callbacks before entering a
failure wrapper. A provider exception therefore escaped and prevented the
cache-disabled authorization computation from running.

The formal cache kernel models provider failure as a miss. The executable
boundary must do the same: record provider-failure telemetry, skip all cache
admission/reuse logic, and return a freshly computed result. This is an
availability/fail-closed orchestration defect, not a false grant.

The final v8 simplification removes the proof-provider callback path rather
than retaining an exception boundary around it. The live regression is
`eacl.datomic.trusted-surface-audit-test/deleted-trusted-surfaces-stay-deleted-test`:
the deleted `eacl.cache/resolve!` surface must not return.
