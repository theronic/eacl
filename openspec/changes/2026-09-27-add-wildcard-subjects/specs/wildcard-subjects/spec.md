## ADDED Requirements

### Requirement: Schemas declare wildcard branches
A relation SHALL accept SpiceDB wildcard branches `T:*` and `T:* with c`, alone or beside concrete `T` and `T with c` branches, for any definition `T`. A named Caveat on a wildcard branch SHALL be a defined Caveat. EACL MUST reject a duplicate wildcard branch, and MUST reject an arrow whose left relation holds a wildcard (`relation parent: folder | folder:*` under `parent->view`) with `:eacl.schema/expression-resolution-failed`, as SpiceDB does. An arrow MAY lead to a relation or permission that holds a wildcard.

#### Scenario: Wildcard beside a concrete branch
- **WHEN** a schema declares `relation viewer: user | user:*`
- **THEN** `write-schema!` succeeds and `read-schema` reports the relation's wildcard branch

#### Scenario: Caveated wildcard only
- **WHEN** a schema declares `relation anyone: user:* with nothing_sensitive` and defines `nothing_sensitive`
- **THEN** `write-schema!` succeeds and every `user:*` relationship on `anyone` requires `nothing_sensitive`

#### Scenario: Wildcard on the left of an arrow
- **WHEN** a permission uses `parent->view` and `parent` allows `folder:*`
- **THEN** `write-schema!` fails with `:eacl.schema/expression-resolution-failed` and keeps the previous schema

### Requirement: Wildcard branches are stored additively
Each wildcard branch SHALL be recorded on the existing Relation entity, unique per resource type, relation name and subject type, with `:eacl.relation/allows-unqualified-wildcard?` (present exactly when the relation declares `T:*`, true when a bare `T:*` is allowed) and `:eacl.relation/wildcard-caveats` (the branch's Caveat definitions). A relation with only a wildcard branch SHALL store `:eacl.relation/allows-unqualified? false` without concrete Caveats. Every wildcard relationship SHALL use the EACL-owned subject entity `{:eacl/id "eacl.wildcard-subject"}`, whose subject-type slot keeps each type's wildcard apart. Relationship and permission storage SHALL remain version 8. Datomic and Datahike SHALL install the two attributes on the first schema write that declares a wildcard, Datalevin when a client opens the connection; a DataScript connection without them SHALL fail the schema write with `:eacl.schema/wildcard-attributes-missing` before any transaction. A schema replacement that removes a wildcard branch or one of its Caveats while a stored relationship uses it SHALL fail with `:eacl.schema/relationship-qualifier-in-use`.

#### Scenario: Schemas without wildcards are unchanged
- **WHEN** a schema declares no wildcard branch
- **THEN** its stored Relation entities, sealed plans, plan fingerprints, cursors and cache keys are those of the release before this change

#### Scenario: Removing a used wildcard branch
- **WHEN** a replacement schema drops `user:*` from a relation that holds a `user:*` relationship
- **THEN** the write fails with `:eacl.schema/relationship-qualifier-in-use` and the relationship keeps granting

### Requirement: The wildcard object ID is reserved
The object ID `"*"` SHALL denote the wildcard in the subject position of relationship writes, deletes, reads, relationship filters and `delete-object!`. EACL MUST reject `"*"` with `:eacl/wildcard-not-allowed` as a resource ID in every operation and as the subject of `can?`, `check-permission`, `check-permissions`, `lookup-resources` and `count-resources`. A concrete application object whose external ID is `"*"` SHALL raise `:eacl/reserved-object-id` when EACL renders it.

#### Scenario: Wildcard subject of a check
- **WHEN** a caller asks `(can? acl (spice-object :user "*") :view doc)`
- **THEN** EACL throws `:eacl/wildcard-not-allowed`, as SpiceDB rejects a check on a wildcard subject

#### Scenario: Wildcard resource
- **WHEN** a relationship names `(spice-object :area "*")` as its resource
- **THEN** the write fails with `:eacl/wildcard-not-allowed`

### Requirement: Writes validate the subject form
A relationship write SHALL be admitted only when the relation declares the subject's form: concrete (`T`) or wildcard (`T:*`). A disallowed form SHALL fail with `:eacl/unknown-relation-or-permission` and `:reason :wildcard-subject-not-allowed` or `:concrete-subject-not-allowed`. The Caveat of a wildcard relationship SHALL be one of the wildcard branch's alternatives; a bare wildcard relationship on a relation that only allows `T:* with c` SHALL fail with `:reason :caveat-not-allowed`, as a concrete relationship does.

#### Scenario: Wildcard on a concrete-only relation
- **WHEN** a caller writes `user:*` to a relation declared `relation banned: user`
- **THEN** the write fails with `:reason :wildcard-subject-not-allowed` and nothing is stored

#### Scenario: Concrete subject on a wildcard-only relation
- **WHEN** a caller writes `user:alice` to a relation declared `relation anyone: user:* with nothing_sensitive`
- **THEN** the write fails with `:reason :concrete-subject-not-allowed`

### Requirement: Checks and resource lookups include wildcard grants
A concrete subject `s` of type `T` SHALL be a member of relation `X` on resource `r` when it holds its own relationship, or when `X` declares `T:*` and a `T:*` relationship on `r` exists; each relationship contributes its own Caveat and expiry evidence. `can?`, `check-permission`, `check-permissions`, `lookup-resources` and `count-resources` SHALL apply this membership through union, intersection, exclusion on either side, arrows to relations and permissions, and recursion. A wildcard SHALL grant objects that exist; an ID that names no object remains unknown.

#### Scenario: Exclusion from a wildcard
- **WHEN** `enter = viewer - banned`, `viewer` holds `user:*` and `banned` holds `user:bob` on `a1`
- **THEN** `alice` may enter `a1`, `bob` may not, and `lookup-resources` for `alice` includes `a1`

#### Scenario: Arrow to a relation that holds a wildcard
- **WHEN** `peek = folder->viewer` and folder `f0`, the folder of `d0`, holds `user:*` as viewer
- **THEN** every existing user may peek at `d0`

#### Scenario: Caveated wildcard
- **WHEN** `area:vault` holds `user:*` on `anyone` with `nothing_sensitive` and a check of `exit` supplies `{"carrying" ["lunch"]}`
- **THEN** the subject has permission, and with `{"carrying" ["launch-codes"]}` it does not

### Requirement: Subject lookups return the wildcard with its exclusions
`lookup-subjects` SHALL return the wildcard as the subject `{:type T :id "*"}` when the wildcard's own relationships grant the permission. When the permission's positive cover can grant through a `T:*` relationship and the permission uses intersection or exclusion, EACL SHALL decide every subject that holds a relationship in the permission's touch cover (every intersection and exclusion relaxed to a union of its operands) exactly, list the granted ones, and give the `*` entry `:excluded-subjects` naming every touch-cover subject that is not definitely granted. A subject SHALL hold the permission through its own entry or, unless `*` excludes it, through `*`. EACL MAY list a granted subject that holds a relationship of its own although `*` covers it. Under `:result-policy :detailed`, a conditionally granted touch-cover subject SHALL be both excluded from `*` and returned as its own conditional entry. `count-subjects` SHALL count result entries, the `*` entry once.

#### Scenario: Exclusions of the wildcard entry
- **WHEN** `enter = viewer - banned`, `viewer` holds `user:*` and `user:carol`, and `banned` holds `user:bob` and `user:carol`
- **THEN** `lookup-subjects` returns only `*` with `:excluded-subjects` `bob` and `carol`, and `count-subjects` returns 1

#### Scenario: Intersection with a wildcard
- **WHEN** `edit = viewer & editor`, `viewer` holds `user:*` and `editor` holds `bob` and `dave`
- **THEN** `lookup-subjects` returns `bob` and `dave` and no `*` entry

#### Scenario: Conditional exclusion
- **WHEN** `guarded = anyone - banned`, `frank`'s ban has a Caveat whose context is missing, and `:result-policy :detailed` is requested
- **THEN** the `*` entry excludes `frank` and `frank` is returned as a conditional entry with the missing fields

### Requirement: Wildcard relationships keep caches, cursors and deletion coherent
Wildcard relationships SHALL be stamped, cached and paginated under their Relation like any relationship. A cursor walk over subjects SHALL return the `*` entry exactly once wherever it falls. `delete-object!` of a resource SHALL remove its wildcard relationships, and `delete-object!` of `(spice-object T "*")` SHALL remove every relationship whose subject is `T:*` and no other type's wildcard relationship.

#### Scenario: Write invalidates a cached wildcard answer
- **WHEN** a cached check granted through `user:*` is followed by deleting that wildcard relationship
- **THEN** the next check denies

#### Scenario: Deleting one type's wildcard
- **WHEN** `delete-object!` is called for `(spice-object :user "*")`
- **THEN** no `user:*` relationship remains and every `team:*` relationship is kept

### Requirement: Every public backend supports wildcards
Datomic, Datahike, DataScript on the JVM and in ClojureScript, and Datalevin SHALL pass the shared wildcard contract, with and without the answer cache, including the Caveat contract with a Caveat evaluator, and the speculative `with-schema`/`with` contract where the backend supports speculation.

#### Scenario: DataScript in ClojureScript
- **WHEN** the DataScript ClojureScript test runner executes
- **THEN** the wildcard contract, the Caveat contract with the portable evaluator and the seeded differential pass

### Requirement: Wildcard semantics are verified against SpiceDB and a model
EACL SHALL keep a version-pinned SpiceDB golden for wildcard subjects (schema, relationships, requests, raw responses and normalized results) and a test that compares EACL's answers with it; a Dafny model of wildcard membership and subject listing; and a seeded differential against an independent reference evaluator.

#### Scenario: Golden comparison
- **WHEN** `eacl.datascript.wildcard-spicedb-golden-test` runs
- **THEN** checks, resource lookups, permission trees and reads equal SpiceDB v1.56.0's, subject lookups grant every known subject what SpiceDB's grant, and rejected requests raise the mapped EACL errors
