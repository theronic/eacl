# EACL-FORMAL-030 — an untyped formal edge can invent recursion

Production permission nodes are pairs of resource type and permission name.
An arrow from `[:document :read]` to `[:team :view]` therefore does not reach
`[:folder :view]`, even though both target nodes use the name `:view`.

The older `AcyclicEngine.PathDependencies` abstraction does not carry the
resolved target resource type for an arrow permission. It conservatively
selects every permission node with the target name. That is safe when the set
is used only to over-approximate a cache invalidation scope, but it is not an
exact refinement of production SCC routing: the self-loop on
`[:folder :view]` can spuriously classify `[:document :read]` as recursive.

`RecursiveEngine.PermissionDependencyEdge` now retains both complete endpoint
nodes. Generated Java and JavaScript compare the typed oracle with the actual
shared production traversal analysis, including this minimized name-collision
fixture, singleton self-loops, multi-node SCCs, acyclic ancestors, diamonds,
deep chains, and disconnected recursive components.
