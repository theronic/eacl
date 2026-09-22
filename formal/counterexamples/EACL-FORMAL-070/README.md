# EACL-FORMAL-070 — snapshot options could replace trusted identity resolution

The ordinary `eacl/snapshot` wrapper always supplied an empty options map, but
the underlying protocol method accepted any map and merged it over the trusted
client runtime. Direct protocol callers could therefore replace internal
dependencies such as `:spice-object->internal`.

The minimized real-backend witness grants Bob access, denies Alice, then
captures a snapshot whose injected resolver maps Alice to Bob. The same check
changes from denied to allowed. No database or schema mutation is required.

The shared client now accepts only an exactly empty snapshot options map.
`SnapshotOptionBoundary.dfy` models the separation between the public
consistency choice and trusted client dependencies. A portable executed mutant
removes the guard, and the DataScript regression exercises the actual protocol
and authorization path.
