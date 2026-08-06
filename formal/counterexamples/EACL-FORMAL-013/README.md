# EACL-FORMAL-013 — lookup self-wait identity omitted the lifecycle

Subproblem flights are owned by `(lifecycle, tier, semantic-key)`. `resolve!`
used that identity in its dynamic recursion set, while `lookup!` tested only
`(tier, semantic-key)`. A computation that used `lookup!` to probe its own
in-flight entry therefore failed to recognize itself and could dereference its
own unresolved delay.

`lookup!` now uses the identical lifecycle-qualified identity. Such a probe is
a cache miss, performs no nested computation, and cannot wait on itself.
