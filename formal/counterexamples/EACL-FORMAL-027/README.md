# EACL-FORMAL-027 — adapter EID domain exceeded the generated proof domain

EACL's generated boundary and Dafny indexed/acyclic models use nonnegative
safe-integer EIDs. The shared adapter contract previously required only an
exact signed integer, and its optional runtime guards accepted negative object
IDs and ordered scan values. A third-party adapter could therefore drive the
legacy acyclic source with values that the generated engine rejects, invalidating
the claimed common input domain even though the current Datomic, Datahike, and
DataScript stores allocate nonnegative persistent entity IDs.

The adapter obligations now state the nonnegative requirement explicitly.
Runtime guards fail closed on negative object IDs, order hints, and forward or
reverse scan values, while portable adapter certification rejects a negative
visible-object identity. Existing exact-integer upper/lower range checks remain
separate from this domain check.
