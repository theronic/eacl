## MODIFIED Requirements

### Requirement: Backend-neutral cache store

The `eacl` module SHALL define one database-neutral semantic cache contract,
entry lifecycle, snapshot boundary, and validation flow. The JVM and CLJS
runtime implementations MAY use different internal cache libraries and data
structures when each satisfies that contract; JVM cache code MUST NOT be
compiled into CLJS and CLJS cache code MUST NOT constrain JVM performance.
Database adapters and public callers MUST NOT depend on either library's native
types or policy internals.

#### Scenario: JVM and CLJS use different storage libraries

- **WHEN** the JVM selects a concurrent native cache and CLJS retains a
  JavaScript-compatible LRU
- **THEN** both runtimes pass the same semantic key, lifecycle, eviction,
  snapshot, and cache-disabled differential traces
- **AND** database adapters import only the shared EACL cache boundary

#### Scenario: Alternate cache store

- **WHEN** a supported runtime supplies its implementation of the shared cache
  store contract
- **THEN** every database adapter uses it without importing its library-native
  types or policy operations

#### Scenario: Cache disabled

- **WHEN** no cache store is configured
- **THEN** authorization behavior remains correct and equivalent to an uncached
  execution
