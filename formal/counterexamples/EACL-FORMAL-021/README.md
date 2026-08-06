# EACL-FORMAL-021 — Datomic dropped shared subproblem-cache limits

DataScript and Datahike accept the shared nested `:subproblem-cache`
configuration. Datomic's separate compatibility cache normalizer did not.
Consequently a Datomic consumer could neither disable the layered store nor
select its projection, denotation, proof-atom, or actual-callback bounds.

The problem did not change authorization values, but it invalidated a
cross-backend resource/configuration claim and could materially increase
retained cache state or callback concurrency relative to the operator's
configuration.

Datomic now forwards the exact nested map to the backend-neutral current-cache
constructor. The constructor remains the single validator, so invalid values
fail during `make-client` rather than on the first authorization request.
