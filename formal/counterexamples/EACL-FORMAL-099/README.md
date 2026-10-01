# EACL-FORMAL-099 — `use self` was rejected

```
use self
definition user {}
definition doc {
 relation viewer: user | doc
 permission view = viewer + self
 permission only = self
}
```

SpiceDB v1.56.0 accepts this schema and grants `self` exactly when the subject
is the resource object: `doc:1#view@doc:1` holds, `doc:1#view@doc:2` does not,
and `doc:1#only@user:1` does not, although `user:1` and `doc:1` share an id.
EACL rejected the schema as `:eacl.schema/unsupported-feature`, and rejected
every definition, relation or permission named `self`, because its union plans
name a same-resource reference with the source relation `:self`.

A `self` leaf is now its own canonical expression node. Plans evaluate it
through the definition's identity relation (`:_self`, whose subject type is the
definition), and the adapter boundary serves that relation's tuples as the
identity without storing any. Every route reads it as a definite relation;
identity is typed, so a subject of another type is denied even when EACL's
situated objects share its entity. Without `use self`, `self` is an ordinary
name, except as an arrow's base.

SpiceDB's LookupSubjects also returns `user:1` for `doc:1#only` with subject
type `user`, which its CheckPermission denies; EACL returns no subject there,
as CheckPermission answers.

Reproduce with `EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay`,
or run the `eacl.datascript.self-test` tests.
