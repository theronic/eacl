# EACL-FORMAL-028 — generated-artifact gate measured a stale foundation

The performance ledger marked generated-artifact size as passed by comparing
an old foundation baseline to its old foundation maximum. It did not measure
the Java sources, Java classes, JavaScript runtime, or browser bundle rebuilt
from the current full generated kernel. Those current artifacts already
exceeded every old foundation maximum, so the claimed release gate was not a
gate on the code under review.

The full kernel now has a reviewed baseline for each separately defined byte
measure. `bin/formal artifact-size` deterministically sums the exact rebuilt
files, writes a machine-readable observation, and fails when any measure
exceeds 125 percent of its reviewed full-kernel baseline. The formal CI job
runs the check only after rebuilding all four artifact forms. Solver effort,
source bytes, class bytes, JavaScript bytes, allocation, heap, and latency
remain separate resource dimensions.
