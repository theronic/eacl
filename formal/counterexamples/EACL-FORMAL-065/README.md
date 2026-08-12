# EACL-FORMAL-065 — pure aliases duplicated acyclic traversal streams

The Explorer schema includes both `account->view` and `account->admin`, while
`account.view` is exactly the same-resource alias `admin`. The acyclic frontier
builder expanded top-level aliases but compared arrow targets by their raw
permission names. It therefore traversed the same denotation twice. Exact
results remained correct because the merge deduplicated emitted EIDs, but the
100k count performed 515 backend scans and failed the existing 512-scan gate.

The fix follows only exact single self-permission bodies, with a cycle guard,
before computing the existing arrow frontier identity. Deduplication preserves
the first canonical path's position. Composite permissions are never rewritten
and no relationship data is inspected. The formal model proves alias
denotation preservation and that canonical identities cannot increase the
number of traversal streams; runtime tests require four paths, unchanged exact
results, and the unrelaxed Explorer work envelope.
