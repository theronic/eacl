# EACL-FORMAL-046 — root names prevented semantic cache sharing

The semantic denotation cache originally included the queried root permission
name in its identity. That was safe but unnecessarily specific. Distinct
permissions with identical normalized incoming rule bodies produced the same
least-fixed-point grants yet could not reuse one another's denotation. The
shared-subgraph benchmark consequently showed no useful network effect across
top-level queries.

The corrected key is compiled once per root and schema generation from the
exact portable indexed rule maps accepted by generated plan certification. It
erases only each matching rule's root `:head`, then stores the remaining bodies
as a structural set. The key retains the resource type, exact relation entity
ids, subject/intermediate types, target relation ids, and target permission
nodes. Changing a relation binding or downstream target therefore produces a
different key.

`RootDenotation.dfy` proves that equal semantic rule bodies preserve equal root
grants through every immediate-consequence iteration and hence in the least
fixed point. `IndexedRootDenotation.dfy` connects exact indexed compiler output
and relation catalogs to that theorem. The DataScript regression checks that
exact boundary in both Clojure and ClojureScript: equal bodies reuse a
denotation, while changed relation and target-node cases do not collide and
still deny. Host persistent-collection equality remains a trusted runtime
boundary rather than a Dafny theorem.

On the clean 2026-08-05 heavy run, the distinct-query shared-subgraph benchmark
measured 0.181959 ms p50 with the layered cache versus 0.684250 ms for the
completed-answer-only baseline, a ratio of 0.265925, with zero backend
operations in the reused path. These measurements are a host-specific
regression gate, not a latency proof.
