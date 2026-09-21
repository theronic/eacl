# EACL-FORMAL-073 — an unsupported subject set became a different object

EACL documents that SpiceDB `subject#relation` usersets are not supported. Its
public object shape nevertheless accepted a non-nil `:relation`. Downstream
identity resolution used only `:type` and `:id`, so an input such as
`user:grandmother-caregiver#member` silently became the different base object
`user:grandmother-caregiver`. If that base object had access, EACL returned a
grant instead of rejecting the unsupported request.

Ordinary documented EACL usage is not affected because it constructs objects
without `:relation`. The exploitable case is an application that accepts
SpiceDB-shaped references from a request, migration layer, or policy service
and forwards them to EACL. A malicious caller could add `#member` (or another
relation) and receive the base object's authorization result even though EACL
never evaluated the requested userset semantics. This is input-boundary
confusion, not a supported way to express group membership.

Every public endpoint now rejects a non-nil object `:relation` before selecting
a snapshot or calling a backend. That includes scalar and batch checks,
enumeration and nested authorization filters, permission-tree expansion,
relationship writes, and object deletion. The DataScript regression starts
with a genuinely authorized base object and proves the related form is rejected
rather than downgraded. The Dafny model and portable mutation control preserve
the same fail-closed rule for all bundled and third-party adapters.
