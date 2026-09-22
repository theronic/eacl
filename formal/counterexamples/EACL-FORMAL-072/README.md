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

The adapter contract keeps the existing `:object-id->internal` operation and
gives it one unambiguous job: apply the configured application-ID codec.
Application IDs cross that boundary exactly once. The authorization engine's
resolved-EID entry points then consume the result directly, without calling a
resolver or guessing from the value's numeric shape. This requires no new
adapter option and no client configuration change. A Dafny type boundary,
portable mutation control, and real DataScript pagination/tree regression
prevent conversion and engine execution from being merged again.
