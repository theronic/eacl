# EACL-FORMAL-021 — Datomic dropped flat cache capacities

DataScript and Datahike accepted the shared cache capacities. Datomic's
separate compatibility cache normalizer did not. Consequently a Datomic
consumer could not select the denotation and answer entry capacities.

The problem did not change authorization values, but it invalidated a
cross-backend resource/configuration claim and could materially increase
retained cache state or callback concurrency relative to the operator's
configuration.

Datomic now forwards the flat cache options to the backend-neutral cache
constructor. The constructor remains the single validator, so invalid values
fail during `make-client` rather than on the first authorization request. The
historical nested, projection, and managed-subproblem fields in the original
reproduction were retired; this corpus entry now names the live two-tier count
capacities.
