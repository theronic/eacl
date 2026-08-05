# EACL-FORMAL-046 — root names prevented semantic cache sharing

The semantic denotation cache originally included the queried root permission
name in its identity. That was safe but unnecessarily specific. Distinct
permissions with identical normalized incoming rule bodies produced the same
least-fixed-point grants yet could not reuse one another's denotation. The
shared-subgraph benchmark consequently showed no useful network effect across
top-level queries.

The corrected key erases only the root permission name. It retains the resource
type, exact relation entity ids, target relation or permission nodes, target
types, and canonical recursively sorted subpaths. Changing a relation binding
or downstream target therefore produces a different key.

`RootDenotation.dfy` proves that equal semantic rule bodies preserve equal root
grants through every immediate-consequence iteration and hence in the least
fixed point. `IndexedRootDenotation.dfy` connects exact indexed compiler output
and relation catalogs to that theorem. The Clojure/DataScript regression then
checks the handwritten key boundary directly: equal bodies reuse a denotation,
while changed relation and target-node cases do not collide and still deny.

On the clean 2026-08-05 heavy run, the distinct-query shared-subgraph benchmark
measured 0.181417 ms p50 with the layered cache versus 0.673958 ms for the
completed-answer-only baseline, a ratio of 0.269181, with zero backend
operations in the reused path. These measurements are a host-specific
regression gate, not a latency proof.
