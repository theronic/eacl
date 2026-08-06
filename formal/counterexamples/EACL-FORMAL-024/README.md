# EACL-FORMAL-024 — queue gauge was modeled as cumulative work

Lore's EACL case study separates queue depth, cumulative enqueues, and actual
host computation because none can prove a bound on another. The materialized
recursive Dafny model violated that rule: it added the size of every
fixed-point queue round to `queuedWork`, then compared the cumulative total
with production's `:max-queued-work` limit.

A two-result recursive chain has two sequential singleton queue rounds. Its
maximum pending queue depth is one, so production and the generated indexed
engine complete under `:max-queued-work 1`. The materialized generated
evaluator instead accumulated `1 + 1` and returned a limit error.

The model now records the maximum observed pending-work cardinality and checks
the current queue cardinality directly. Cumulative enqueues remain a distinct
request counter and cannot substitute for the instantaneous safety gauge.
