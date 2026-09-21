# EACL-FORMAL-072 — numeric public cursor IDs entered the native-EID domain

EACL permits applications to map public IDs of any non-nil host type through a
custom codec. A numeric public ID therefore remains application data; it is not
automatically a database entity ID. The v8 adapter exposed one resolver for
both public IDs and already-normalized native EIDs, however, and its numeric
fast path could not tell those domains apart.

An authenticated cursor containing public user ID `0` was consequently resumed
from native entity ID `0` instead of the entity selected by the application's
codec. In a real DataScript relationship scan, pages advanced from a string ID
to `0`, then rewound to the first page forever and never reached a later grant.
The same ambiguity could resolve a numeric permission-tree root as the wrong
object.

The adapter contract now has a distinct `:public-object-id->internal`
operation. Relay cursors, standalone public pagination, permission-tree roots,
and adapter certification use that operation; cache-normalized engine requests
retain the separate native-EID pass-through. Every bundled backend implements
the split. A Dafny counterexample, portable mutation control, and real
DataScript pagination/tree regression prevent the domains from being merged.
