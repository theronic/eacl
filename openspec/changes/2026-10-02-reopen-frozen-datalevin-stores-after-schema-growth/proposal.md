# Reopen frozen Datalevin stores after the physical schema grows

## Why

`eacl-datalevin` at `eeb1f844` cannot open a store that the same module
bootstrapped at `6982d388`. `create-conn` fails inside Datalevin with
`:datalevin/frozen-attribute-write`, before EACL has read anything from the
store. Wildcard subjects added two Relation attributes to the module's
physical schema in between; nothing else about the store differs.

Wildcard subjects were specified to need no migration: "Relationship and
permission storage SHALL remain version 8", and "Datomic and Datahike SHALL
install the two attributes on the first schema write that declares a
wildcard, Datalevin when a client opens the connection". The release notes say
the same. Datalevin does that only for a store that has no write policy yet.
A store the module has bootstrapped can no longer be opened at all, so anyone
who keeps data in one cannot take the release.

The same failure meets an application that adds an attribute of its own to
`create-conn`'s `extra-schema` after bootstrap, with any module version.

## What Changes

- `create-conn` declares only the application's schema to Datalevin when it
  opens a store, and adds the module's missing attributes itself only while
  the store has no write policy. It never changes a protected store's EACL
  attributes or policy.
- Client construction (`ensure-physical-schema!`) adds the module attributes a
  protected store lacks and extends the persisted write policy to them, using
  the store's per-open admission token. It commits no transaction.
- The extension is admitted only from the module's own policy for the
  attributes already covered, only to attributes the module declares, and only
  while those hold no data. Anything else fails with the existing
  `:eacl.datalevin/write-policy-drift`, which now names the attributes, and
  leaves the store unchanged. A redefined attribute still fails with
  `:eacl.datalevin/physical-schema-drift`.
- No fork change, no storage-version change, and no new operator step.

`design.md` evaluates the alternatives: an explicit migration step, a storage
version bump, declaring the upgrade unsupported, and admitting the change in
the fork instead of in EACL.

## Capabilities

### New Capabilities

- `datalevin-physical-schema-growth`: an earlier frozen store opens with a
  later module, gains the attributes that module added under its write
  policy, and refuses any other difference without being changed.

### Modified Capabilities

No existing specification files are edited by this proposal. It makes the
Datalevin clause of the wildcard-subjects requirement "Wildcard branches are
stored additively" hold for stores bootstrapped before that change.

## Impact

`modules/eacl-datalevin/src/eacl/datalevin/schema.cljc` (`create-conn`,
`ensure-physical-schema!`, `expected-write-policy`), the module README and
PORTING notes, and a new module test namespace. The module is unpublished and
outside workspace CI, so its suite runs in the Datalevin nREPL against the
sibling fork checkout. The maintained fork is unchanged; two fork follow-ups
are listed in `design.md`. Datomic, Datahike and DataScript are not touched.
