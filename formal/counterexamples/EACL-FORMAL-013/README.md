# EACL-FORMAL-013 — lookup self-wait identity omitted the lifecycle

Subproblem flights are owned by `(lifecycle, tier, semantic-key)`. `resolve!`
used that identity in its dynamic recursion set, while `lookup!` tested only
`(tier, semantic-key)`. A computation that used `lookup!` to probe its own
in-flight entry therefore failed to recognize itself and could dereference its
own unresolved delay.

The final v8 simplification deletes shared flights entirely. `lookup!` never
starts work, and two identical misses compute independently and race only a
bounded nonblocking publication. There is no own-flight object to wait on.
