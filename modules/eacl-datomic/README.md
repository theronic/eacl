# `eacl-datomic`

Datomic adapter for EACL.

Responsibilities:

- Datomic physical schema and schema installation
- Datomic tuple/index storage implementation
- Datomic relationship write planning and transaction execution
- v8 consistency descriptors, Zed tokens, encrypted Relay-style pagination, and historical reads
- v8 authorization result cache, relation-scoped invalidation, and cache-store contract
- v6-to-v7 migration and v8 object-deletion/integrity helpers
- Datomic compatibility namespaces preserving the existing public surface
- Datomic-only regression and storage-mechanics tests

Depending on this module keeps existing `eacl.core` and `eacl.datomic.*`
require forms unchanged.
