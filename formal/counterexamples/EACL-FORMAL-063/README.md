# EACL-FORMAL-063 — acyclic shortcut ignored explicit completion

The v8 execution contract makes `:evaluation :demand` the default and reserves
full denotation materialization for an explicit
`:evaluation :complete-denotation` request. Production correctly implemented
that distinction for recursive roots, but the certified acyclic dispatcher
always selected its demand shortcut.

Consequently, acyclic point checks, pages, and counts returned correct public
values but did not perform the expensive completion the caller explicitly
requested. The control was decorative, no completed semantic denotation was
published, and proof-equivalent cross-generation reuse could not occur. A
legacy heavy benchmark exposed the mismatch by observing 3,920 backend scans,
zero managed denotation hits, and no latency improvement.

The corrected shared route selector keeps acyclic point/page/count shortcuts
demand-only. Explicit completion selects the generated fixed-point evaluator
for every defined root. The execution-contract model proves that route law,
and CLJ/CLJS contract tests require actual acyclic-denotation hits plus
zero-backend-work reuse across point, count, and lookup operations.
