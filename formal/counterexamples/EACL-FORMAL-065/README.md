# EACL-FORMAL-065 — pure aliases duplicated acyclic traversal streams

The Explorer schema includes both `account->view` and `account->admin`, while
`account.view` is exactly the same-resource alias `admin`. The acyclic frontier
builder expanded top-level aliases but compared arrow targets by their raw
permission names. It therefore traversed the same denotation twice. Exact
results remained correct because the merge deduplicated emitted EIDs, but the
100k count performed 515 backend scans and failed the existing 512-scan gate.

The live fix in `eacl.engine.sealed-plan` follows only exact single
same-resource self-permission bodies, with a cycle guard, after the complete
semantic graph is certified acyclic. It derives a separate least-path
execution frontier; semantic nodes, rules, reachability, and recursive
scheduling remain unchanged. Deduplication compares the complete rule identity
except its provider-independent ordinal and preserves the first canonical
path's position. Composite and relation-dependent terminals are not expanded
and no relationship data is inspected. The formal model proves alias
denotation preservation and that canonical identities cannot increase the
number of traversal streams; runtime tests require four paths, unchanged exact
results, and the unrelaxed Explorer work envelope.
