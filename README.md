# 🦅 EACL: Enterprise Access ControL

EACL is a Clojure relationship-based access control (ReBAC) library for
Datomic Pro, Datahike, and DataScript. Permission data lives beside your
application data, and authorization queries run against an immutable database
value from the backend you already use.

EACL uses a subset of the
[SpiceDB schema language](https://authzed.com/docs/spicedb/concepts/schema) and
provides permission checks, subject and resource lookups, relationship
management, pagination, and consistency tokens.

> [!WARNING]
> EACL 8 is under active development. The current artifacts are
> `8.0.0-SNAPSHOT` release candidates.

## Choose a module

Add the adapter for your database. It brings in the shared EACL module at the
same version.

```clojure
;; Datomic Pro
{:deps {dev.eacl/eacl-datomic {:mvn/version "8.0.0-SNAPSHOT"}}}

;; Datahike
{:deps {dev.eacl/eacl-datahike {:mvn/version "8.0.0-SNAPSHOT"}}}

;; DataScript
{:deps {dev.eacl/eacl-datascript {:mvn/version "8.0.0-SNAPSHOT"}}}
```

Backend authors can depend on the shared module directly:

```clojure
{:deps {dev.eacl/eacl {:mvn/version "8.0.0-SNAPSHOT"}}}
```

The published snapshot currently targets Java 26. Source builds can select an
older Java target; the chosen database must also support that Java version.

## Quick start

The following Datomic example installs EACL, defines a permission model,
creates a relationship, and checks access:

```clojure
(ns example.auth
  (:require [datomic.api :as d]
            [eacl.core :as eacl]
            [eacl.datomic.core :as eacl.datomic]
            [eacl.datomic.schema :as eacl.schema]))

(def uri "datomic:mem://eacl-example")
(d/create-database uri)
(def conn (d/connect uri))

;; Install the EACL storage schema once per database.
@(d/transact conn eacl.schema/v7-schema)

;; The default ID mapping uses the unique :eacl/id attribute.
(def acl (eacl.datomic/make-client conn {}))

(eacl/write-schema!
 acl
 "definition user {}

  definition account {
    relation owner: user
    permission admin = owner
  }")

@(d/transact conn
   [{:eacl/id "alice"}
    {:eacl/id "acme"}])

(def alice (eacl/spice-object :user "alice"))
(def acme  (eacl/spice-object :account "acme"))

(eacl/create-relationship! acl alice :owner acme)

(eacl/can? acl alice :admin acme)
;; => true
```

Datahike and DataScript provide the same EACL API. Their helpers create an
in-memory connection with the EACL storage schema installed:

```clojure
;; Datahike
(require '[eacl.datahike.core :as eacl.datahike])
(def conn (eacl.datahike/create-conn))
(def acl (eacl.datahike/make-client conn {}))

;; DataScript
(require '[eacl.datascript.core :as eacl.datascript])
(def conn (eacl.datascript/create-conn))
(def acl (eacl.datascript/make-client conn {}))
```

Use `datahike.api/transact` or `datascript.core/transact!` to create the
application entities before writing relationships to them. The
[Datomic](modules/eacl-datomic/README.md),
[Datahike](modules/eacl-datahike/README.md), and
[DataScript](modules/eacl-datascript/README.md) module guides contain
backend-specific setup and deletion instructions.

## Model permissions

An EACL schema declares object types, relationships, and permissions:

```clojure
(eacl/write-schema!
 acl
 "definition user {}

  definition account {
    relation owner: user
    relation viewer: user

    permission admin = owner
    permission view = owner + viewer
  }

  definition document {
    relation account: account
    permission edit = account->admin
    permission view = account->view
  }")
```

In this schema:

- An account owner is an account admin.
- Owners and viewers can view an account.
- A document inherits `edit` and `view` permissions through its account.
- `+` means union (logical OR).
- `account->admin` follows the document's `account` relationship and checks
  the account's `admin` permission.

Always change the authorization schema through `eacl/write-schema!`. It
validates the complete schema and applies the change atomically. Invalid
schemas leave the current schema untouched.

## Work with relationships

A relationship connects a subject to a named relation on a resource:

```clojure
(def relationship
  (eacl/->Relationship
   (eacl/spice-object :user "alice")
   :owner
   (eacl/spice-object :account "acme")))
```

Use the EACL relationship APIs for every authorization relationship mutation:

```clojure
(eacl/create-relationship! acl alice :owner acme)
(eacl/delete-relationship! acl alice :owner acme)

(eacl/create-relationships! acl [relationship])
(eacl/delete-relationships! acl [relationship])

(eacl/write-relationships!
 acl
 [(eacl/->RelationshipUpdate :touch relationship)])
```

The supported update operations are:

- `:create` — add the relationship and fail if it already exists.
- `:touch` — ensure the relationship exists.
- `:delete` — remove the relationship.

Mutation responses contain a `:zed/token` that can be supplied to a later
read when read-your-writes behavior is required.

`read-relationships` returns paginated relationships. Give it at least one
subject or resource filter so that a missing filter cannot accidentally scan
every relationship.

## Query permissions

Check one permission with `can?`:

```clojure
(eacl/can? acl alice :admin acme)
;; => true or false
```

Unknown object IDs are safe on reads: `can?` returns `false`, and lookup or
relationship reads return no matches. Relationship writes to unknown objects
fail with `:eacl/unknown-object`.

List resources available to a subject:

```clojure
(def page
  (eacl/lookup-resources
   acl
   {:subject alice
    :permission :edit
    :resource/type :document
    :first 100}))

(:data page)
(:page-info page)
```

List subjects that have access to a resource:

```clojure
(eacl/lookup-subjects
 acl
 {:resource (eacl/spice-object :document "roadmap")
  :permission :view
  :subject/type :user
  :first 100})
```

Use `:first` with optional `:after` for forward pagination, or `:last` with
optional `:before` for backward pagination. Pass the returned `:end-cursor` or
`:start-cursor` unchanged. Cursors are opaque, expire, and remain tied to the
query and database snapshot that created them.

EACL guarantees stable pagination for a fixed query and snapshot, but it does
not promise alphabetical or cross-backend ordering. Sort a completed result
set by an application field when presentation order matters.

`count-resources` and `count-subjects` count the corresponding lookup. Use
`:count-limit` to put an upper bound on the work:

```clojure
(eacl/count-resources
 acl
 {:subject alice
  :permission :view
  :resource/type :document
  :count-limit 10000})
;; => {:count ..., :limit 10000, :truncated? ...}
```

The full public protocol is defined in
[`eacl.core`](modules/eacl/src/eacl/core.cljc).

## Object IDs

EACL objects have an authorization type and an application ID:

```clojure
(eacl/spice-object :user "alice")
;; => {:type :user, :id "alice", :relation nil}
```

The default adapters resolve IDs through the unique `:eacl/id` attribute. Do
not expose backend entity IDs as public object IDs.

You can map EACL to another unique application attribute:

```clojure
(def acl
  (eacl.datomic/make-client
   conn
   {:object-id->lookup-ref (fn [id] [:account/id id])
    :entid->object-id (fn [db eid] (:account/id (d/entity db eid)))}))
```

Use the same deterministic mapping in every process that exchanges EACL
cursors or tokens. See [Cache behavior](docs/cache.md) for the additional
requirements when custom mappings participate in cross-snapshot cache reuse.

## Consistency and tokens

By default, EACL reads the current immutable database value visible to the
local connection. Reads can request stronger behavior when the backend
supports it:

```clojure
(require '[eacl.spicedb.consistency :as consistency])

;; Default: use the current local database value.
(eacl/can? acl alice :admin acme consistency/minimize-latency)

;; Ask the backend to synchronize before selecting a database value.
(eacl/can? acl alice :admin acme consistency/fully-consistent)

;; Read at least as new as an earlier EACL mutation.
(def token (:zed/token (eacl/create-relationship! acl alice :owner acme)))
(eacl/can? acl alice :admin acme
           (consistency/at-least-as-fresh token))

;; Read the exact historical snapshot named by a token, if available.
(eacl/can? acl alice :admin acme
           (consistency/at-exact-snapshot token))
```

Backend capabilities differ. Datomic can reconstruct an exact snapshot while
the required history remains available. Datahike support depends on its
configuration. DataScript does not provide general historical snapshot
reconstruction. EACL returns a typed error when the requested guarantee cannot
be met; it does not silently use the wrong snapshot.

Treat Zed tokens and pagination cursors as opaque values. In a multi-process
deployment, configure the same cursor and Zed-token verification keys on every
instance that must accept them. Do not let an untrusted client choose exact
historical consistency; a backend should normally turn a returned mutation
token into an `at-least-as-fresh` request.

## Cache behavior

Caching is automatic and private to each EACL client. It is never stored in
your application database. EACL reuses an answer only when it is valid for the
database value selected by the request; otherwise it evaluates the query
normally. A long-running request can therefore keep using the older immutable
database value it started with while newer requests see newer data.

The important application rule is simple:

> Make every authorization-relevant schema, relationship, identity, and
> entity-deletion mutation through EACL's public APIs, or transact EACL-produced
> transaction data/functions intact.

This rule does not restrict ordinary application data. You can transact
unrelated attributes through the native database API as usual. What is not
supported is changing EACL schema or relationship storage yourself, splitting
an EACL-produced transaction, changing the identity of a permissioned object
outside EACL's contract, or deleting such an object with a native operation
that leaves relationships behind.

If an application bypasses that rule, cached authorization results may be
stale. To recover safely:

1. Stop authorization traffic that can reach the affected data.
2. Repair the schema, identities, and relationships.
3. Call the adapter's `expire-cache!` on every affected EACL client in every
   process, or replace those clients.
4. Resume authorization traffic.

Cache expiry removes remembered answers; it does not repair damaged data or
ghost relationships. Rewriting the same schema is not a substitute for the
recovery procedure because it may correctly be a no-op.

Normal current-database requests use the cache automatically. EACL does not
promise reusable cached answers for arbitrary `as-of`, `since`,
filtered, speculative, or caller-constructed database values. Exact evaluation
of those values can still be correct even when no cache entry is reusable.

You can disable caching for a client with `eacl.cache/no-cache`, or for one
permission check with `:cache? false`:

```clojure
(require '[eacl.cache :as eacl.cache])

(def uncached-acl
  (eacl.datomic/make-client conn {:cache eacl.cache/no-cache}))

(eacl/can? acl
           {:subject alice
            :permission :admin
            :resource acme
            :cache? false})
```

After a database restore, reset, branch replacement, or any operation that can
replace history, expire or replace every affected EACL client before serving
requests. Multi-process deployments must also rotate their shared source
lifecycle consistently.

For cache configuration, recovery details, metrics, custom ID mappings, and
the correctness model, see [Cache behavior and coherence](docs/cache.md).

## Delete permissioned entities safely

Deleting an entity directly can leave a relationship stored on the entity at
the other end. That "ghost relationship" can continue granting access even
though one object no longer exists.

The portable deletion sequence is:

```clojure
;; Removes every relationship that touches the object, in both directions.
(eacl/delete-object! acl acme)

;; Now delete acme with the backend's normal entity-deletion operation.
```

`delete-object!` removes relationships but does not delete the application
entity itself. Use it for large relationship sets because it can perform
bounded work.

Backends that support transaction functions also provide an optional atomic
`:eacl.fn/retractEntity`. It removes both sides of every EACL relationship and
the target entity in one transaction. It is not installed by the normal EACL
schema and must be enabled explicitly.

Datomic example:

```clojure
(require '[datomic.api :as d]
         '[eacl.datomic.safe-retraction :as safe-retraction])

;; Privileged, idempotent deployment step.
(safe-retraction/install! conn)

@(d/transact
  conn
  (safe-retraction/retract-entity-tx-data [:eacl/id "acme"]))
```

Multiple invocations can be placed in one transaction:

```clojure
@(d/transact conn [[:eacl.fn/retractEntity 1]
                   [:eacl.fn/retractEntity 2]])
```

The target may be a numeric entity ID or a valid lookup ref. A numeric ID can
also repair peer-side ghosts after the target entity has already been
retracted. A lookup ref that no longer resolves cannot reveal the old entity
ID, so it cannot perform that repair.

DataScript supports named and direct in-process safe retraction. Datahike
support depends on the database configuration and writer topology. Use each
adapter's `safe-retraction/support-descriptor` before choosing a deployment
mode. When transaction functions are unavailable, use `delete-object!`
followed by the backend's normal deletion operation.

Do not add relationships involving a target in the same transaction that
safely retracts it. For installation, supported modes, integrity checks, and
repair tools, see the backend module guides:

- [Datomic safe retraction](modules/eacl-datomic/README.md#optional-atomic-entity-retraction)
- [Datahike safe retraction](modules/eacl-datahike/README.md#optional-atomic-entity-retraction)
- [DataScript safe retraction](modules/eacl-datascript/README.md#optional-atomic-entity-retraction)

## Current limitations

- The schema language supports union (`+`), but not intersection or negation.
- Arrow expressions support one level, such as `account->admin`; chained
  arrows are not supported.
- Subject relations such as `group#member` are not supported.
- `expand-permission-tree` is not implemented.
- Result order is stable for cursor pagination, but is not a domain sort order.
- Exact historical reads and cursor reconstruction depend on backend history
  support and retention.
- Recursive permissions have safety limits that protect the host from
  unbounded work. Use `:count-limit` to bound counts, and raise recursive
  traversal limits only after load testing.

See the [backend guide](docs/v8-backend-modules-and-upgrade.md) for the full
capability matrix and recursion controls.

<a id="source-dependencies-and-formal-tooling"></a>

## Development from source

Consumers who compile the EACL kernel from a Git checkout or `:local/root`
need the Clojure CLI, Node.js, and the repository-pinned Dafny, Apalache, and
TLA+ toolchain. The preparation task downloads and verifies the required
tools, runs the generated-runtime checks, and builds the JVM and browser
runtime:

```bash
cd modules/eacl

# Default Java target
clojure -T:build prep

# Example source and bytecode target
clojure -T:build prep :java-release 17
clojure -T:build jar :java-release 17
```

Generated output and downloaded tools are kept under `target/` directories.
See [Formal verification](formal/README.md) for the models, tool versions, and
individual proof commands.

## More documentation

- [Cache behavior and coherence](docs/cache.md)
- [Backend modules and capabilities](docs/v8-backend-modules-and-upgrade.md)
- [Datomic adapter](modules/eacl-datomic/README.md)
- [Datahike adapter](modules/eacl-datahike/README.md)
- [DataScript adapter](modules/eacl-datascript/README.md)
- [EACL 8 release notes](docs/release-notes-v8.0.md)

## Funding

Some of this open-source work was generously funded by
[CloudAfrica](https://cloudafrica.net/).

## License

EACL is licensed under the Eclipse Public License v2.0.
