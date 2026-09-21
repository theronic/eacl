# EACL-FORMAL-069 — public request shapes could fail open

The formal operation models began after public Clojure maps had already been
normalized. That omitted an important authorization boundary: unknown map
fields, ambiguous identity selectors, and malformed collection shapes must be
rejected before consistency selection or mutation dispatch.

The minimized witnesses are misspelled `:consistncy` and `:valid-until-mss`
keys, one Relationship record passed to the plural deletion helper, one update
map passed where a batch envelope was required, and a deletion envelope
containing both the public object and an internal native entity ID. A public
object with a nil ID also returned a successful cleanup response that removed
nothing. Before the fix, these could weaken consistency, silently turn an
expiring relationship into a permanent one, report a revocation as successful
without deleting it, or delete relationships for the native entity instead of
the named public object.

`PublicRequestBoundary.dfy` now models closed read and single-relationship
write requests, valid mutation batch shapes, and mutually exclusive
public/native deletion entry points.
It also proves that the reserved `:page/basis :live` value is rejected instead
of being accepted and silently evaluated on EACL's stable page basis.
The public schema writer now rejects the backend-only `:allow-empty-schema?`
escape hatch, preserving the documented defense against parser gaps.
Public wrappers reject invalid shapes before dispatch, and the shared client
validates mutation envelopes again at the protocol boundary. Executed mutants
remove the unknown-key and plural-delete guards; both are killed by the formal
mutation gate. DataScript regressions exercise the complete public and direct
protocol paths against a real backend.
