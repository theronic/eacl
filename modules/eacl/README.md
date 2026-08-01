# `eacl`

Core, backend-neutral EACL module.

Responsibilities:

- `eacl.core` protocol and public records
- cursor/token helpers and consistency semantics
- schema IR, parser, validation, and diffing
- backend SPI and shared authorization engine
- contract test fixtures and backend-neutral tests

This module must not depend on Datomic or a logging backend.

## Backend SPI

Backends supply a map consumed through `eacl.backend.spi` with exactly these
functions:

- `cache-stamp`
- `relation-defs`
- `permission-defs`
- `subject->resources`
- `resource->subjects`
- `direct-match?`

The SPI arguments describe logical types, relation/permission identifiers, and
cursor boundaries. Datoms, attribute ids, and backend-specific index APIs stay
inside each adapter.

The shared contract fixture is
`modules/eacl/test/eacl/contract_support.cljc`. `assert-seeded-contracts!`
retains the contract used by the DataScript adapter and Datahike PR #81;
`assert-v8-seeded-contracts!` expresses the current Datomic pagination surface.

## Upgrading Datahike PR #81

PR #81 can keep its `modules/eacl-datahike` source, database primitive layer,
and six-function SPI implementation. Rebase its three Datahike commits from
the old modular base onto this branch:

```shell
git fetch origin codex/v8-modular-backends fix/audit-root-causes-datascript
git switch feat/datahike-backend
git rebase --onto origin/codex/v8-modular-backends \
  origin/fix/audit-root-causes-datascript
```

The first two commits both touch the root `deps.edn`. Resolve those conflicts
by keeping the v8 dependency versions and logging removals, then add only the
Datahike source/test paths, `org.replikativ/datahike`, and its build alias. In
the Datahike module itself, align Clojure to `1.11.4`, add the directly used
`com.rpl/specter` dependency, and drop the unused
`org.clojure/tools.logging` dependency.

The v8 protocol adds `delete-object!`; implement that method in
`eacl.datahike.core`. No redesign of `eacl.datahike.db`,
`eacl.datahike.impl`, or the six SPI functions is required. The complete
`modules/eacl-datahike` patch from PR #81 applies to this workspace without
source-file conflicts.
