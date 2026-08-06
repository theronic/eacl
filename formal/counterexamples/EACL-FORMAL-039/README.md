# EACL-FORMAL-039 — routing edges were trusted independently of paths

The first proof-carrying SCC boundary established exact classification only
for the indexed dependency-edge vector Clojure supplied. A defect in the
earlier translation from materialized permission paths could therefore produce
a perfectly valid proof for the wrong graph.

The generated checker now consumes every materialized path descriptor.
Direct-relation and arrow-relation paths emit no routing edge.
Self-permission and arrow-permission paths emit exactly one ordered
`head -> target` edge. The checker rejects invalid node indices, omitted or
invented edges, and edge permutations before checking the SCC certificate.

On acceptance Dafny proves exact path derivation and exact recursive routing in
`P + 2V + E` certified loop iterations. Clojure constructs the host graph and certificate
from the same derived edge vector. Backend path materialization and the small
host map-to-descriptor translation remain explicit adapter/source assumptions,
covered by cross-runtime production differentials rather than represented as
proved Clojure bytecode.
