# object-id-resolution Specification

## Purpose
TBD - created by archiving change fix-audit-root-causes. Update Purpose after archive.
## Requirements
### Requirement: Unknown object IDs on read operations return empty results
Read operations given a subject or resource ID that does not resolve to an entity SHALL return empty results — `read-relationships` → `[]`, `lookup-resources`/`lookup-subjects` → an empty page, `count-resources` → `{:count 0}`, `can?` → `false` — consistent with SpiceDB, where unknown object IDs simply match nothing. An unresolvable ID SHALL NOT be conflated with an absent filter.

#### Scenario: read-relationships does not degrade to a global scan
- **WHEN** relationships exist for users `alice` and `bob`, and `read-relationships` is called with `{:subject/id "i-do-not-exist"}`
- **THEN** the result is `[]` — not the full relationship set

#### Scenario: Lookups return empty pages instead of AssertionErrors
- **WHEN** `lookup-resources` is called with a nonexistent subject, or `lookup-subjects` with a nonexistent resource
- **THEN** an empty `:data` page is returned (no `AssertionError`, no exception)

#### Scenario: can? remains false for unknown objects
- **WHEN** `can?` is called with a nonexistent subject or resource ID (including `nil`)
- **THEN** it returns `false`

### Requirement: Unknown object IDs on write operations throw typed errors
`write-relationships!` (and the create/touch/delete wrappers) SHALL throw `ex-info` with `:type :eacl/unknown-object` identifying the offending `{:type … :id …}` when a relationship endpoint does not resolve to an **existing entity**, before any tx-data is built. The check SHALL verify entity existence (datom presence), not mere `d/entid` resolution — `d/entid` passes numeric inputs through unchanged, so plausible-but-unallocated eids "resolve" and would otherwise surface as raw transactor errors. Raw Datomic `:db.error/not-an-entity` / `:db.error/invalid-entity-id` errors SHALL NOT surface for this case, and `:delete`/`:touch` SHALL NOT silently no-op on unresolvable endpoints.

#### Scenario: Create with nonexistent subject
- **WHEN** `create-relationships!` is called with subject `{:type :user :id "ghost-user"}` that has no entity
- **THEN** an `ex-info` with `:type :eacl/unknown-object` and the subject's type and id in `ex-data` is thrown

#### Scenario: Create with unallocated numeric eid
- **WHEN** a relationship write is attempted (via the internal API) with a numeric ID in a valid eid range that was never allocated
- **THEN** the same `:eacl/unknown-object` error is thrown before transacting — not a raw `:db.error/invalid-entity-id`

### Requirement: Tempid pass-through in tx-relationship is opt-in
`impl/tx-relationship` SHALL throw `:eacl/unknown-object` for string IDs that do not resolve, unless called with `{:allow-tempids? true}`, in which case unresolvable strings pass through as Datomic tempids (supporting same-transaction entity+relationship creation). Ghost entities SHALL NOT be minted by default.

#### Scenario: Typo'd ID no longer mints a ghost entity
- **WHEN** `tx-relationship` is called (without the flag) with resource id `"acct-1x"` while only `"acct-1"` exists
- **THEN** it throws `:eacl/unknown-object` and no new entity is created

#### Scenario: Fixtures-style same-transaction creation still works
- **WHEN** `tx-relationship` is called with `{:allow-tempids? true}` and string IDs matching `:db/id` tempids of entities in the same transaction
- **THEN** the transaction succeeds and the relationship tuples reference the newly created entities

### Requirement: Client configuration is validated and matches documented keys
`make-client` SHALL accept `:entid->object-id` (`(fn [db eid] …)`) as the canonical ID-coercion option (as documented in the README), SHALL continue accepting `:entity->object-id` as a deprecated alias, SHALL throw `:eacl/invalid-config` when both are supplied, and SHALL throw `:eacl/invalid-config` listing unknown and known keys when an unrecognized option key is supplied.

#### Scenario: README-documented key is honored
- **WHEN** a client is built with `{:entid->object-id (fn [db eid] (str "EXT-" (:eacl/id (d/entity db eid))))}`
- **THEN** `lookup-resources` returns IDs produced by that function (e.g. `"EXT-acct-1"`)

#### Scenario: Typo'd option key fails fast
- **WHEN** a client is built with `{:entid->objectid …}` (misspelled)
- **THEN** `make-client` throws `:eacl/invalid-config` naming the unknown key and the set of known keys

#### Scenario: Conflicting aliases are rejected
- **WHEN** both `:entid->object-id` and `:entity->object-id` are supplied
- **THEN** `make-client` throws `:eacl/invalid-config`

