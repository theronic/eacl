# `eacl-datomic`

Datomic adapter for EACL.

Responsibilities:

- Datomic physical schema and schema installation
- Datomic tuple/index storage implementation
- Datomic relationship write planning and transaction execution
- v8 consistency descriptors, Zed tokens, encrypted Relay-style pagination, and historical reads
- v8 authenticated result cache with mutation/content proofs and cache-store contract
- v6-to-v7 migration and v8 object-deletion/integrity helpers
- Datomic compatibility namespaces preserving the existing public surface
- Datomic-only regression and storage-mechanics tests

Depending on this module keeps existing `eacl.core` and `eacl.datomic.*`
require forms unchanged.

```clojure
{:deps {dev.eacl/eacl-datomic {:mvn/version "8.0.0-SNAPSHOT"}}}
```

Its POM depends on `dev.eacl/eacl` at the exact same version, so consumers do
not declare core separately. EACL targets Java 26 by default; explicit
source/custom builds can target older Java, subject to Datomic's own runtime
requirements. Build this module in isolation with `clojure -T:build jar`; Git and `:local/root`
development must first follow the explicitly opt-in
[core source preparation instructions](../../README.md#source-dependencies-and-formal-tooling).
Maven consumers install no formal tools.

For the cross-backend capability matrix, recursive controls, and cache
mutation rules, see the
[v8 backend and upgrade guide](../../docs/v8-backend-modules-and-upgrade.md).
