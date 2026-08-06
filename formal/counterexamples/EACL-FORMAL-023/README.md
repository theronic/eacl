# EACL-FORMAL-023 — generated stale cursors changed public error shape

The generated indexed evaluator reported a stale cursor when a retained
recursive result disappeared, but its host adapter added `:direction
:forward`. The established legacy error carried only the typed stale-cursor
identifier plus internal cursor diagnostics.

Shadow comparison would detect the difference, but the campaign exercised
only valid continuation and traversal-limit failures. It never invalidated a
cursor result between raw-engine pages, so the divergent generated branch was
untested.

The generated adapter now preserves the legacy public stale-cursor shape. The
regression uses a DataScript snapshot adapter directly so public cursor
recovery does not intentionally replace the stale result with a restarted
page; this isolates the engine branch that shadow mode is required to compare.
