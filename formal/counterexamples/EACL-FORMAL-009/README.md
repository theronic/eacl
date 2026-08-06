# EACL-FORMAL-009 — cache bypass silently bypassed generated authority

The public adapters passed `:engine-selection` into the completed-answer cache
resolver. That resolver correctly bound the selection while computing a miss.
However, an explicit cache bypass returned through an earlier branch and called
the engine without that binding. The result was usually correct because legacy
authority ran, which made value-only differential tests falsely reassuring.

The adapters now bind engine selection at the evaluation boundary before cache
eligibility branches diverge. The retained state trace asserts that generated
plan compilation and state transitions occur for cache-disabled recursive
requests on Datomic, Datahike, and DataScript.
