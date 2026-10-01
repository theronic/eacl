## Why

EACL rejects SpiceDB wildcard relation types (`relation viewer: user:*`), so a
consumer cannot express "every user" without writing one relationship per
user. Issue #183 asks for wildcards in EACL v8. The first consumer grants
every user a conditional exit from an area:

```
caveat nothing_sensitive(carrying list<string>) { !("launch-codes" in carrying) && !("customer-list" in carrying) }
definition area {
  relation anyone: user:* with nothing_sensitive
  permission exit = anyone
}
```

## What Changes

- Accept `T:*` and `T:* with caveat` relation branches, alone or beside
  concrete `T` branches, with SpiceDB's rule that a relation holding a
  wildcard cannot be the left side of an arrow.
- Store each wildcard branch's Caveat alternatives on the existing Relation
  entity with two additive attributes, and represent every `T:*` subject by
  one EACL-owned subject entity. Relationship storage ABI 8 (five-slot
  endpoint pairs) is unchanged.
- Reserve the object ID `"*"`: it denotes the wildcard in the subject position
  of relationship writes, reads, filters and `delete-object!`. It is rejected
  as a resource, and as the subject of checks, resource lookups and resource
  counts, as SpiceDB rejects it.
- Checks, `lookup-resources` and `count-resources` grant a subject everything
  its type's wildcard holds, through union, intersection, exclusion, arrows to
  relations or permissions that hold a wildcard, recursion, Caveats and
  expiry.
- `lookup-subjects` returns the wildcard as `{:type T :id "*"}` and, where
  exclusion or intersection removes subjects from it, lists them under
  `:excluded-subjects`; `count-subjects` counts result entries.
- All public backends (Datomic, Datahike, DataScript on the JVM and in
  ClojureScript, and Datalevin), cache coherence, cursors and
  `delete-object!` support wildcard relationships.
- Add a Dafny model of the wildcard semantics and connect it to the engine
  through executable differentials.

## Capabilities

### New Capabilities

- `wildcard-subjects`: wildcard schema branches, the reserved `*` identity,
  relationship writes/reads/deletes, check and lookup semantics, subject
  lookups with exclusions, counts, backends, and the formal model.

### Modified Capabilities

- `permission-tree-expansion`: wildcard schemas are supported; a direct leaf
  lists a stored wildcard as the `*` subject.

## Impact

Core: `eacl.spicedb.parser`, `eacl.schema.expression-resolver`,
`eacl.schema.relation-allowance`, `eacl.relationships.qualifier`,
`eacl.relationships.staged`, `eacl.schema.errors`, `eacl.caveats.schema`,
`eacl.client.orchestration`, `eacl.engine.*`, `eacl.operator.*` and
`eacl.permission-tree`. Backends: schema installation, schema writers,
relation definitions and object resolution in all four modules. Existing
schemas, relationships, plan fingerprints, cursors and cache keys are
unchanged; a schema that uses wildcards needs the two new Relation
attributes, which EACL installs on the first wildcard schema write where the
backend allows it. Every serving Peer must run this release before any Peer
writes a wildcard schema.
