# EACL-FORMAL-037 — Datomic bypassed certified recursive routing

Datahike and DataScript schema caches carried the shared
`:traversal-analysis` generation slot, but the Datomic indexed cache retained
only the older per-root `:traversal-permissions` map. Datomic therefore
classified recursive roots with the host fallback even in
verified-authoritative mode. Current answers happened to agree, but the
generated routing certificate was not authoritative on that backend.

The Datomic schema cache now carries and evicts the same shared traversal
analysis and recursive-plan generation state as the other adapters. The
forced-authority harness additionally requires every backend to invoke
`:recursive-routing-certificate`; a backend can no longer pass merely by
calling some unrelated generated operation.

The closing nonbenchmark run observed 206 Datomic routing-certificate calls,
32 Datahike calls, and 30 DataScript calls with 18,716 assertions passing.
The heavy run independently observed 90, 3, and 7 calls respectively.
