# 🦅 **EACL**: Enterprise Access ControL

EACL is a _situated_ [ReBAC](https://en.wikipedia.org/wiki/Relationship-based_access_control) authorization library based on [SpiceDB](https://authzed.com/spicedb), built in Clojure with a backend-neutral core and Datomic, DataScript, and Datahike adapters.

_Situated_ here means that your permission data lives _next to_ your application data in the backend you already control, which has some benefits:
1. Avoids a network hop. To leverage SpiceDB's consistency semantics, you need to hit your DB (or cache) to retrieve the latest stored ZedToken anyway, so you might as well query the DB directly, which is what EACL does.
2. One less external dependency to deploy & sync relationships.
3. Fully consistent queries – an external authz system necessitates eventual consistency.

EACL is pronounced "EE-kəl", like "eagle" with a `k` because it keeps a watchful eye on permissions.

## Goals

- Best-in-class ReBAC authorization for Clojure/Datomic applications that is fast for 10M permissioned Datomic entities.
- Clean migration path to SpiceDB once you need consistency semantics with a heavily optimized cache.
- Retain compatibility with SpiceDB gRPC API to enable 1-for-1 Relationship syncing by tailing Datomic transactor queue.

## Rationale

Please refer to [eacl.dev](https://eacl.dev/).

## Authentication vs Authorization

- Authentication or **AuthN** means, "Who are you?"
- Authorization or **AuthZ** means "What can `<subject>` do?", so AuthZ is all about permissions.

## Why EACL?

Situated AuthZ offers some advantages for typical use-cases:

1. If you want [ReBAC](https://en.wikipedia.org/wiki/Relationship-based_access_control) authorization without an external system, EACL is your only option.
2. Storing permission data directly in Datomic avoids network I/O to an external AuthZ system, reducing latency.
3. An accurate ReBAC model syncing Relationships 1-for-1 from Datomic to SpiceDB in real-time without complex diffing, for when you need SpiceDB performance or features.
4. Queries default to the current DB visible to the local Peer and also support
   `minimize-latency`, `at-least-as-fresh`, and historical `at-exact-snapshot` semantics.
5. EACL is fast. You may be tempted to roll your own ReBAC system using recursive Datomic child rules, but you will find the eager Datalog engine too slow and unable to handle all the grounding cases. The first version of EACL was implemented with Datalog rules, but it was simply too slow and materialized all intermediate results. Correct bidirectional cursor-pagination is also non-trivial, because parallel paths through the permission graph can yield duplicate resources. EACL does this for you with good performance.

## Performance

- EACL traverses acyclic ReBAC paths via low-level Datomic `d/index-range`, `d/seek-datoms` & `d/rseek-datoms` calls. Recursive permission closures use deterministic traversal order with request-local dedupe, avoiding both Datomic recursive Datalog materialization and persisted grant caches. Acyclic lookup results are returned in Datomic eid order; recursive lookup results are returned in traversal order.
  - I have investigated implementing custom Sort Keys, but they are not currently feasible without adding a lot of storage & write costs.
- EACL is fast, but makes no strong performance claims at this time. For typical workloads, EACL should be as fast as, or faster than, SpiceDB. EACL is not meant for hyperscalers.
- EACL is internally benchmarked against ~800k permissioned resources with good latency (5-30ms per query). You can scale Datomic Peers horizontally and dedicate peers to EACL as needed.
- The performance goal for EACL is to handle 10M permissioned entities with real-time performance.
- EACL does not support all SpiceDB features. Please refer to the [limitations section](#limitations-deficiencies--gotchas) to decide if EACL is right for you.
- EACL has one optional, bounded ephemeral completed-answer cache for `can?`,
  lookup pages, and counts. Entries are authenticated v3 envelopes and can
  lift across snapshots only after causal-anchor and complete dependency-proof
  validation. The old process-local recursive-continuation side cache is not
  trusted; cursors replay deterministic authenticated frontiers.
- Completed answers are keyed by semantic query/configuration identity.
  Mutation-identity or canonical content proofs cover exactly the compiled
  schema and relationship closure the permission reads, so unrelated writes
  can retain valid entries without treating `basis-t` or a listener as proof.
- EACL derives permission paths from the one immutable snapshot selected for
  the request and keys compiled state by its complete schema proof.
  - Under `:coherence-authority :managed`, `eacl/write-schema!` is the required
    schema mutation boundary and atomically publishes the schema mutation
    identity. An identical write keeps the existing generation hot.
  - Under the default `:unknown` authority, schema writes from another client,
    process, or low-level transaction change the selected snapshot's complete
    content proof, so existing clients rederive the permission graph without
    trusting a listener. Detecting arbitrary out-of-band writes requires a
    complete schema read; content mode is the conservative interoperability
    path, not the low-latency configuration.
  - Low-level calls against arbitrary `db`, `d/as-of`, `d/with`, or filtered values are deliberately uncached. Connection-backed cursor and exact reads build request-scoped schema state from their historical DB; they do not publish paths into the client's live schema cache.
  - Permission paths and dependency closures are compiled lazily and memoized
    within a schema-proof generation. Recursive routing for all permission
    nodes shares one generation analysis. Iterative SCC and reverse-reachability
    passes make the graph-analysis portion `O(V+E)` cold work for `V` permission
    nodes and `E` permission edges once their paths are materialized; another
    first-read permission root reuses the result.
  - Managed proof reads are dependency-sized. Unknown-writer content proofs
    currently perform graph-wide collection/filtering before hashing the
    dependency relations. See the
    [proof-cost review](docs/reports/2026-08-02-eacl-v8-cache-proof-cost.md)
    for measured costs and exact methodology.
- Acyclic lookup cursors retain a per-permission-path intermediate frontier. Later pages resume each arrow path at the earliest intermediate that can still contribute, and permanently skip paths exhausted in that scan direction. This prevents deep pages from repeatedly scanning intermediates that were already proved irrelevant.
- Acyclic lookup performance should scale roughly with permission graph complexity * `O(logN)` for `N` resources in terminal resource Relationship indices. Recursive lookup pages are deterministic traversal-order pages with request-local dedupe. Continuation hits make a sequential walk approximately linear in traversed work; a proof-equivalent miss is slower but correct because it replays the prefix. Counts consume bounded frontier pages (at most 16,384 EIDs at once) or one explicit recursive state machine; they never retain an entire broad lazy result head. Subjects are typically sparse compared to resources, i.e. 1k users will have access to 1M resources – rarely the other way around.

EACL page tokens bind database/source identity, graph head and exact locator,
operation, query, ordering, configuration, and complete dependency/proof
digests. Page two continues on a newer selected snapshot when the complete
proof is equal. Only a proof mismatch falls back to the authenticated original
exact value; a newer incompatible freshness floor returns a typed conflict.

The authenticated database identity is checked before EACL selects a cursor basis or resolves
query inputs. Sharing a stable page-token key across backend instances does not make a cursor
portable to another logical Datomic database, even when a cloned database has matching schema,
basis revisions, internal EIDs, and query shape.

The internal functions in `eacl.datomic.impl.indexed` continue to accept a `db` directly. This is
the escape hatch for deliberate `d/as-of`, `d/with`, or prospective database evaluation:
construct the DB yourself, apply the EACL schema to a prospective DB when needed, keep one DB value
for the operation, and perform the internal EID coercion yourself. Those calls do not publish into
a connection-backed client's schema or result cache.

Public `eacl4_` cursors are string-safe AES-GCM envelopes. Authentication is required so a caller cannot alter a result boundary, query binding, basis, or per-path frontier. Encryption is not required for pagination correctness, but it prevents internal Datomic eids and basis metadata from leaking through an otherwise merely Base64-encoded token. Token cryptography runs once when a page cursor is encoded or decoded; it is not part of each relationship-index traversal.

The payload uses a compact binary encoding (`eacl.datomic.codec`) rather than EDN. Cursors are
opaque and short-lived (5 minutes by default), so the format is not a compatibility surface:
a token EACL does not recognise is rejected as `:eacl.pagination/invalid-cursor`, which every
caller already handles for expiry.

## Project Status

> [!WARNING]
> EACL is under active development.
> I try hard not to introduce breaking changes, but if data structures change, the major version will increment.
> The changes on this branch are the [v8.0 candidate](docs/release-notes-v8.0.md). v8 adds an additive database-visible mutation journal, authenticated v3 causal/cache/cursor formats, and upgrades the DataScript and Datahike list/count APIs to the Relay contract. Client construction performs the idempotent additive migration; v7 relationship storage remains unchanged. See the [v8 backend and upgrade guide](docs/v8-backend-modules-and-upgrade.md) and [consistency/cache operations guide](docs/v8-consistency-cache-operations.md). Releases are not tagged yet, so pin the Git SHA.
> Upgrading from v6? The relationship storage model changed — follow the [v6 → v7 migration guide](docs/migration-v6-to-v7.md).

## Modules

The repository is a workspace with four independently consumable modules:

- `modules/eacl`: public protocol and records, schema model/parser, consistency descriptors, shared authorization engine, and six-function backend SPI.
- `modules/eacl-datomic`: the complete v8 Datomic adapter, including consistency, encrypted cursors, object deletion, migrations, and authorization caching.
- `modules/eacl-datascript`: the CLJ/CLJS DataScript adapter and its backend contract tests.
- `modules/eacl-datahike`: the CLJ Datahike adapter, including both keyword and numeric attribute-reference representations.

Choose the adapter you use:

```clojure
{:deps
 {theronic/eacl-datomic
  {:git/url "git@github.com:theronic/eacl.git"
   :git/sha "REPLACE_WITH_SHA"
   :deps/root "modules/eacl-datomic"}

  ;; Or, for DataScript:
  ;; theronic/eacl-datascript
  ;; {:git/url "git@github.com:theronic/eacl.git"
  ;;  :git/sha "REPLACE_WITH_SHA"
  ;;  :deps/root "modules/eacl-datascript"}

  ;; Or, for Datahike:
  ;; theronic/eacl-datahike
  ;; {:git/url "git@github.com:theronic/eacl.git"
  ;;  :git/sha "REPLACE_WITH_SHA"
  ;;  :deps/root "modules/eacl-datahike"}

  ;; Core-only/backend authors:
  ;; theronic/eacl
  ;; {:git/url "git@github.com:theronic/eacl.git"
  ;;  :git/sha "REPLACE_WITH_SHA"
  ;;  :deps/root "modules/eacl"}
  }}
```

The root `deps.edn` is a development workspace, not the consumer artifact boundary. Build or install modules independently with `clojure -T:build-eacl install`, `clojure -T:build-eacl-datomic install`, `clojure -T:build-eacl-datascript install`, or `clojure -T:build-eacl-datahike install`.

EACL does not select a logging implementation. Applications remain responsible for their own logging backend and configuration.

For module selection, capability differences, cache mutation rules, recursive controls, and DataScript/Datahike migration details, see the [v8 backend and upgrade guide](docs/v8-backend-modules-and-upgrade.md). Backend authors should also read the [v8 adapter boundary](docs/v8-backend-adapter-boundary.md).

## ReBAC: Relationship-based Access Control

In a [ReBAC](https://en.wikipedia.org/wiki/Relationship-based_access_control) system like EACL, objects (_Subjects_ & _Resources_) are related via _Relationships_.

A `Relationship` is just a 3-tuple of `[subject relation resource]`, e.g.
- `[user1 :owner account1]` means subject `user1` is the `:owner` of resource `account1`, and
- `[account1 :account product1]` means subject `account1` is the `:account` for resource `product1`.

EACL models two core concepts to model the permission graph: Schema & Relationship.

1. _Schema_ consists of `Relations` and `Permissions`:
   - `Relation` defines how a `<subject>` & `<resource>` can be related via a `Relationship`.
   - `Permission` defines which permissions are granted to a subject via a chain of `Relationships` between subjects & resources.
     - Permissions can be _Direct Permissions_ or indirect, known as _Arrow Permissions_. An arrow implies a graph traversal.
2. A _Relationship_ defines how a `<subject>` and `<resource>` are related via a named relation, e.g. `[(->user alice) :owner (->account "acme")]` means that
   - `(->user "alice")` is the Subject,
   -  `:owner` is the name of the `Relation` (as defined in the schema)
   - `(->account "acme")` is the Resource
   - so this reads as `(->user "alice")` is the `:owner` of `(->account "acme")`.
   - In EACL, this is expressed as `(->Relationship (->user "alice") :owner (->account "acme"))`, i.e. `(Relationship subject relation resource)`
   - Subjects & Resources are just maps of `{:keys [type id]}`, e.g. `{:type :user, :id "user-1"}`, or `(->user "user-1")` when using a helper function.

### Schema & Relationships

To create a Relationship, first define your schema using `eacl/write-schema!`:

```clojure
(eacl/write-schema! acl
  "definition user {}
  
   definition account {
     relation owner: user
     relation viewer: user
     
     permission admin = owner
   }
   
   definition product {
     relation account: account
     
     permission edit = account->admin
     permission view = account->admin + account->viewer
   }")
```

This schema defines:
- An `account` can have `owner` and `viewer` users, with `admin` permission granted to owners
- A `product` belongs to an `account`, with `edit` permission for account admins and `view` permission for account admins and viewers

In SpiceDB schema DSL, `+` means union (OR-logic). EACL does not support negation (`-`) or intersection (`&`) yet.

## EACL API

The `IAuthorization` protocol in [modules/eacl/src/eacl/core.cljc](modules/eacl/src/eacl/core.cljc) defines an idiomatic Clojure interface that maps to and extends the [SpiceDB gRPC API](https://buf.build/authzed/api/docs/main:authzed.api.v1):

### Queries

- `(eacl/can? acl subject permission resource) => true | false`
- `(eacl/lookup-subjects acl filters) => {:data [subjects...] :page-info {...}}`
- `(eacl/lookup-resources acl filters) => {:data [resources...] :page-info {...}}`
- `(eacl/count-resources acl filters) => {:keys [count limit]}` counts the full result set.
- `(eacl/count-subjects acl filters) => {:keys [count limit]}` counts the full subject result set.

Pass `:count-limit n` to either count operation to bound work. The result then includes
`:truncated?`; `true` means at least one additional result exists.

### Relationship Maintenance

- `(eacl/read-relationships acl filters) => {:data [relationships...] :page-info {...}}`
- `(eacl/write-relationships! acl updates) => {:zed/token "eacl_z3_..."}`,
  - where `updates` is a collection of `[operation relationship]`, and `operation` is one of `:create`, `:touch` or `:delete`.
- `(eacl/create-relationships! acl relationships)` simply calls `write-relationships!` with `:create` operation.
- `(eacl/delete-relationships! acl relationships)` simply calls `write-relationships!` with `:delete` operation.
- `(eacl/delete-object! acl object) => {:zed/token "eacl_z3_...", :retracted-datoms n}` is a convenience helper that removes every relationship touching `object`, in both directions. `n` counts relationship datoms actually retracted by the committed transactions. Consumers are expected to delete relationships before retracting a permissioned entity — see [Deleting a permissioned entity](#deleting-a-permissioned-entity).

All list APIs use the v8 Relay pagination contract:

- Forward: pass `:first` and optionally `:after`.
- Backward: pass `:last` and optionally `:before`.
- Responses include `:page-info` with `:start-cursor`, `:end-cursor`, `:has-next-page?`, and `:has-previous-page?`.
- `:cursor` and `:limit` are no longer supported for list pagination.
- Acyclic lookup cursors paginate in Datomic eid order. Recursive lookup cursors paginate in deterministic traversal order.

### Schema Maintenance

- `(eacl/write-schema! acl schema-string)` parses a SpiceDB schema DSL string, validates it, computes deltas against existing schema, checks for orphaned relationships, and transacts changes atomically.
- `(eacl/read-schema acl)` returns the current schema as a map of `{:relations [...] :permissions [...]}`.
- `(eacl/expand-permission-tree acl filters)` is not impl. yet. It is a low priority to implement.

All schema changes must use `eacl/write-schema!`. EACL clients deliberately do not detect raw
definition transactions on every authorization call. After an out-of-band schema write, recreate
other clients (or call `eacl.datomic.integrity/client-schema-status` explicitly to detect the
generation mismatch).

### Example Queries

The primary API call is `can?`, e.g.

```clojure
(eacl/can? acl subject permission resource)
=> true | false
```

The other primary API call is `lookup-resources`, e.g.

```clojure
(def page1
  (eacl/lookup-resources acl
    {:subject       (->user "alice")
     :permission    :view
     :resource/type :server
     :first         2})) ; defaults to 1000.
page1
=> {:data [{:type :server :id "server-1"}
           {:type :server :id "server-2"}]
    :page-info {:start-cursor "eacl4_..."
                :end-cursor "eacl4_..."
                :has-next-page? true
                :has-previous-page? false}}
```

To query the next page, pass the `:end-cursor` from page1 as `:after`:

```clojure
(eacl/lookup-resources acl
  {:subject       (->user "alice")
   :permission    :view
   :resource/type :server
   :first         3
   :after         (get-in page1 [:page-info :end-cursor])})
=> {:data [{:type :server :id "server-3"}
           {:type :server :id "server-4"}
           {:type :server :id "server-5"}]
    :page-info {:start-cursor "eacl4_..."
                :end-cursor "eacl4_..."
                :has-next-page? true
                :has-previous-page? true}}
```

To go back from page2, pass its `:start-cursor` as `:before` with `:last`:

```clojure
(eacl/lookup-resources acl
  {:subject       (->user "alice")
   :permission    :view
   :resource/type :server
   :last          2
   :before        (get-in page2 [:page-info :start-cursor])})
```

Forward and backward pages return results in the same order for the query. Acyclic lookup uses Datomic eid order; recursive lookup uses deterministic traversal order. Backward pagination returns the previous window; it does not reverse the result order. Bare `:last` without `:before` is not supported for recursive lookup because it requires traversing the full closure.

## Datomic Quickstart

The following example is contained in [eacl-example](https://github.com/theronic/eacl-example).

Add the Datomic adapter dependency to your `deps.edn` file:

```clojure
{:deps {theronic/eacl-datomic
        {:git/url "git@github.com:theronic/eacl.git"
         :git/sha "REPLACE_WITH_SHA"
         :deps/root "modules/eacl-datomic"}}}
```

```clojure
(ns my-eacl-project
  (:require [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.core]
            [eacl.datomic.schema :as schema]))

; Create an in-memory Datomic database:
(def datomic-uri "datomic:mem://eacl")
(d/create-database datomic-uri)

; Connect to it:
(def conn (d/connect datomic-uri))

; Install the latest EACL Datomic Schema:
@(d/transact conn schema/v7-schema)

; Write your permission schema using SpiceDB schema DSL:
(eacl/write-schema! acl
  "definition user {}
   
   definition account {
     relation owner: user
     
     permission admin = owner
     permission update = admin
   }
   
   definition product {
     relation account: account
     
     permission edit = account->admin
   }")

; Transact some Datomic entities with a unique ID, e.g. `:eacl/id`:
@(d/transact conn
  [{:eacl/id "user-1"}
   {:eacl/id "user-2"}
   
   {:eacl/id "account-1"}
   
   {:eacl/id "product-1"}
   {:eacl/id "product-2"}])

;  Make an EACL client that satisfies the `IAuthorization` protocol:
(def acl (eacl.datomic.core/make-client conn
           ; optional config:
           {:object-id->ident (fn [obj-id] [:eacl/id obj-id]) ; optional. to convert external IDs to your unique internal Datomic idents, e.g. :eacl/id can be :your/id, which may be a unique UUID or string.
            :entity->object-id (fn [ent] (:eacl/id ent))})) ; optional. to internal entities to your external IDs.
 
; Define some convenience methods over spice-object:
; `eacl.core/spice-object` is just a record helper that accepts `type`, `id` and optionally `subject_relation`, to return a SpiceObject of {:keys [type id]}. `subject-relation` is not currently supported in EACL.

(def ->user (partial spice-object :user))
(def ->account (partial spice-object :account))
(def ->product (partial spice-object :product))
  
; Write some Relationships to EACL (you can also transact this with your entities):
(eacl/create-relationships! acl
  [(eacl/->Relationship (->user "user-1") :owner (->account "account-1"))
   (eacl/->Relationship (->account "account-1") :account (->product "product-1"))])

; Run some Permission Checks with `can?`:
(eacl/can? acl (->user "user-1") :update (->account "account-1"))
; => true
(eacl/can? acl (->user "user-2") :update (->account "account-1"))
; => false

(eacl/can? acl (->user "user-1") :edit (->product "product-1"))
; => true
(eacl/can? acl (->user "user-2") :edit (->product "product-1"))
; => false

; You can enumerate the :product resources a :user subject can :edit via `lookup-resources`:
(eacl/lookup-resources acl
  {:subject       (->user "user-1")
   :permission    :edit
   :resource/type :product
   :first         1000})
; => {:data [{:type :product, :id "product-1"}]
;     :page-info {:start-cursor "eacl4_..."
;                 :end-cursor "eacl4_..."
;                 :has-next-page? false
;                 :has-previous-page? false}}
```

## DataScript Quickstart

For server-side or browser demos, use the DataScript adapter:

```clojure
{:deps {theronic/eacl-datascript
        {:git/url "git@github.com:theronic/eacl.git"
         :git/sha "REPLACE_WITH_SHA"
         :deps/root "modules/eacl-datascript"}}}
```

```clojure
(ns my-eacl-datascript-demo
  (:require [datascript.core :as ds]
            [eacl.core :as eacl]
            [eacl.datascript.core :as eacl.datascript]))

(def conn (eacl.datascript/create-conn))
(def acl (eacl.datascript/make-client conn {}))

(ds/transact! conn
  [{:db/id -1 :eacl/id "user-1"}
   {:db/id -2 :eacl/id "account-1"}])

(eacl/write-schema! acl
  "definition user {}

   definition account {
     relation owner: user
     permission admin = owner
   }")

(eacl/create-relationship! acl
  (eacl/spice-object :user "user-1")
  :owner
  (eacl/spice-object :account "account-1"))

(eacl/can? acl
  (eacl/spice-object :user "user-1")
  :admin
  (eacl/spice-object :account "account-1"))
; => true
```

Run the shared CLJS contract suite with:

```clojure
clojure -M:datascript-cljs-test
```

## Migration Notes

- Replace the monolithic/root Git dependency with `:deps/root "modules/eacl-datomic"`; no source namespace changes are required.
- Existing Datomic `require` forms remain valid: `eacl.datomic.core`, `eacl.datomic.impl`, and `eacl.datomic.schema`.
- `Relation` and `Permission` now live in the backend-neutral schema model, with Datomic compatibility re-exports still available.
- The repository root is a development workspace now. Consumers should depend on the module they actually need.
- DataScript keeps its existing `:limit`/`:cursor` list API while sharing the v8 protocol and schema/parser core; Datomic retains the v8 Relay-style pagination API documented above.

## EACL Schema

EACL uses the SpiceDB schema DSL to define your authorization model. Use `eacl/write-schema!` to parse, validate, and transact your schema:

```clojure
(eacl/write-schema! acl
  "definition user {}

   definition account {
     relation owner: user
     
     permission admin = owner
     permission update = admin
   }
   
   definition product {
     relation account: account
     
     permission edit = account->admin
   }")
```

### Schema Validation

`write-schema!` validates your schema and provides informative error messages. An invalid schema throws and nothing is transacted:
- **Parse validation**: unparseable schema strings and duplicate `definition`/relation declarations throw. `//` and `/* */` comments are supported.
- **Reference validation**: all relations and permissions must reference valid definitions. Arrow targets must exist on **every** subject type of the source relation.
- **Orphan protection**: relations with existing relationships cannot be deleted.
- **Empty-schema guard**: replacing a non-empty schema with zero definitions throws unless you pass `{:allow-empty-schema? true}`.
- **Unsupported feature detection**: rejects SpiceDB features not yet supported by EACL (see [Limitations](#limitations-deficiencies--gotchas))

### Schema Updates

When you call `write-schema!` with a modified schema, EACL:
1. Parses the new schema
2. Computes deltas (additions/retractions) against existing schema  
3. Validates retractions won't orphan existing relationships
4. Transacts changes atomically

### Modelling Relations

Let's model the following SpiceDB schema in EACL:

```
definition user {}

definition account {
  relation owner: user
}
```

We define two resource types, `user` & `account`, where any `user` subject can be the `:owner` of an `account` resource. 

A Relationship is just a 3-tuple of `[subject relation resource]`:
```clojure
(eacl/->Relationship (->user "alice") :owner (->account "acme"))
```

### Permission Schema: Direct Relations

Let's add a direct permission to the schema for `account` resources:

```clojure
(eacl/write-schema! acl
  "definition user {}

   definition account {
     relation owner: user
     permission update = owner
   }")
```

Here, `permission update = owner` means any user who is an `:owner` of an account will have the `update` permission for that account.

At this point, all permissions checks via `eacl/can?` will return `false`, because there are no Relationships defined:

```clojure
(eacl/can? acl (->user "alice") :update (->account "acme"))
=> false
```

What happens when we create some Relationships between users & accounts?

### Creating Relationships

In EACL, Relationships are expressed as 3-tuples of `[subject relation resource]` using the `->Relationship` helper, e.g. user alice is an `:owner` of acme account:
```clojure
(eacl/->Relationship (->user "alice") :owner (->account "acme"))
```

Now let's create a Relationship between a `user` subject and an `account` resource using `eacl/create-relationships!`:
```clojure
(eacl/create-relationships! acl [(eacl/->Relationship (->user "alice") :owner (->account "acme"))])
```

*Note*: `eacl/create-relationships!` is just a wrapper over `eacl/write-relationships!` with the `:create` operation. It will throw if there is an existing relationship that matches input.

### Permission Checks 

Now that we have created a Relationship between a user and an account, we call `eacl/can?` to check if a user has the `:update` permission on the ACME account, e.g. "can Alice `:update` the ACME account?"
```clojure
(eacl/can? acl (->user "alice") :update (->account "acme"))
=> true
```

Indeed, she can. Why? Because Alice is an `:owner` of the ACME account and the `:update` permission is granted to all users who are `:owner(s)`.

Can Bob `:update` the ACME account?
```clojure
(eacl/can? acl (->user "bob") :update (->account "acme"))
=> false
```
No, he cannot, because Bob is not an `:owner` of the ACME account.

### Arrow Permissions

Arrow permissions imply a graph hop. Arrows are designated by `->` in the SpiceDB schema DSL:

```clojure
(eacl/write-schema! acl
  "definition user {}

   definition account {
     relation owner: user
     
     permission admin = owner
     permission update = admin
   }

   definition product {
     relation account: account
     
     permission edit = account->admin
   }")
```

Here, `permission edit = account->admin` states that subjects are granted the `edit` permission _if, and only if_ they have the `admin` permission on the related account for that product. Only account owners have the `admin` permission on the related account. So given that:
 1. `(->user "alice")` is the `:owner` of `(->account "acme")`, and
 2. `(->account "acme")` is the `:account` for `(->product "SKU-123")`,
 3. EACL can traverse the permission graph from user -> account -> product to derive that Alice has the `:edit` permission on product `SKU-123`.

Now you can use `can?` to check those arrow permissions:
```clojure
(eacl/can? acl (->user "alice") :edit (->product "SKU-123"))
=> true ; if Alice is an :owner of the Account for that Product.

(eacl/can? acl (->user "bob") :edit (->product "SKU-123"))
=> false ; if Bob is not the :owner of the Account for that Product.
```

Internally, EACL models Relations, Permissions and Relationships as Datomic entities, along with several tuple indices for efficient querying.

## EACL ID Configuration

SpiceDB uses strings for all external subject & resource IDs, whereas EACL uses Datomic entity IDs internally for all IDs. However, EACL lets you configure how internal IDs should be coerced to external IDs and vice versa.

*Note*: internal Datomic eids should not be exposed to consumers, because those eids are not guaranteed to be stable after a DB rebuild.

`eacl.datomic.core/make-client` accepts a Datomic conn and a config map of `{:keys [entity->object-id object-id->ident]}`, which are functions to convert between internal to/from external IDs.

It is common to attach a unique UUID to permissioned entities for exposing them externally, or you can convert external->internal at your call sites. Here is how you can configure EACL to convert to/from a unique attribute named `:your/id`:

```clojure
(def acl (eacl.datomic.core/make-client conn
           {:entity->object-id (fn [ent] (:your/id ent))
            :object-id->ident (fn [obj-id] [:your/id obj-id])}))
```

Note that this attribute should have property `:db/unique :db.unique/identity`. 

The default options are to use the built-in EACL string attr `:eacl/id`, but you can use the internal Datomic eids with the following "identity" functions:
```clojure
(def acl (eacl.datomic.core/make-client conn
           {:entity->object-id (fn [ent] (:db/id ent))
            :object-id->ident (fn [obj-id] obj-id)}))
```

`make-client` validates its options: unknown keys throw `{:type :eacl/invalid-config}` (a silently dropped ID-coercion key would mean silently wrong external IDs). `:entity->object-id` (`(fn [entity] id)`) is a deprecated alias for `:entid->object-id`; supplying both throws. Page tokens expire after 5 minutes by default; tune with `:page-token-ttl-seconds`.

### Lookup cache configuration

Completed answers use bounded authenticated v3 cache envelopes. Cursor
continuation does not trust the old process-local recursive/frontier side
cache; it replays from authenticated proof context. Disable completed-answer
retention without changing authorization semantics:

```clojure
(require '[eacl.datomic.cache :as eacl-cache])

(def acl (eacl.datomic.core/make-client conn {:cache eacl-cache/no-cache}))
```

Capacity and lifetime can be tuned, though most consumers do not need to:

```clojure
(def acl
  (eacl.datomic.core/make-client
   conn
   {:cache {:max-weight (* 64 1024 1024)
            :max-entry-weight (* 8 1024 1024)
            :max-entries 4096
            :kind-max-weight {:can? (* 16 1024 1024)}
            :two-hit-kinds #{:can?}
            :ttl-ms 300000}}))
```

The cache selects one immutable request snapshot before lookup. A candidate
computed elsewhere is reusable only when the selected history contains its
authenticated mutation anchor and its complete dependency closure, schema
proof, and relationship proof match. `:proof-mode :mutation` requires complete
managed-writer authority; `:proof-mode :content` detects relevant out-of-band
writes. Datomic content proof covers both physical relationship halves and
endpoint identity mappings. `basis-t`, listener counts, TTL, and “latest”
pointers are never validity evidence.

Entry admission includes a conservative estimate of
the retained key and result shape; the weight settings are estimates, not literal JVM
bytes or a heap guarantee. Oversized entries are rejected rather than allowed to threaten the
host heap. `:kind-max-weight` and `:two-hit-kinds` keep high-cardinality permission checks from
displacing every expensive lookup.

`:cache` names the cache adapter the client uses. Omit it and EACL builds a default client-local
one; that is what most consumers want.

```clojure
(make-client conn {})                                      ; default adapter
(make-client conn {:cache eacl-cache/no-cache})            ; no caching
(make-client conn {:cache (eacl-cache/local-store
                           {:max-weight (* 64 1024 1024)})}) ; your own adapter
```

`:cache` takes a `eacl.datomic.cache/CacheStore`. Any implementation is a valid adapter, so a
custom or shared store needs no backend dependency in EACL core.
`eacl.datomic.cache/no-cache` is the adapter that caches nothing. `nil`, or omitting `:cache`
entirely, gives you the default in-memory adapter.

Reach for `no-cache` when the same permission check is essentially never asked twice — a batch job
sweeping distinct resources, say. A read then pays for a cache lookup it can never benefit from.
When checks do recur, the cache is the faster path.

Lookups and counts report where their answer came from:

```clojure
(let [{:keys [data cached? cache-basis]} (eacl/lookup-resources acl query)]
  (when cached?
    ;; how old is this answer?
    (- (System/currentTimeMillis)
       (.getTime (eacl.datomic.core/basis-instant acl cache-basis)))))
```

`:cached?` says whether this response came from the cache. `:cache-basis` is the Datomic `t` the
answer was computed at, and `basis-instant` resolves it to a wall-clock time. In the default
consistency mode a hit's basis is older than the read's own and the answer is still exactly current
— nothing it depends on changed in between; only the staleness-tolerant modes can return an answer
that is genuinely behind. `can?` returns a plain boolean and carries neither.

To bypass the configured cache for a **single call**, pass `:cache? false` on the request:

```clojure
(eacl/can? acl {:subject alice :permission :view :resource doc :cache? false})

(eacl/lookup-resources acl {:subject alice
                            :permission :view
                            :resource/type :doc
                            :cache? false})
```

Note the two different keys. `:cache` on the client says *which* cache — a thing. `:cache?` on a
request says *whether* to use it — a boolean. It is accepted on the map arity of `can?` and in the
query map for `lookup-resources`, `lookup-subjects`, `count-resources`, `count-subjects` and
`read-relationships`, and it skips both reading and writing for that call only. Cursors are
unaffected: a page token is minted and validated from the request rather than from the cache, so a
cursor minted with the bypass is usable without it and vice versa.

There is no process-local listener/coordinator correctness path. Managed
writers publish database-visible random mutation identities atomically;
unknown-writer deployments use selected-snapshot content proof.

A configuration map may be supplied in place of an adapter for capacity tuning and tests —
`:store`, `:max-weight`, `:max-entry-weight`, `:max-entries`, `:ttl-ms`, `:namespace`,
`:kind-max-weight`, `:two-hit-kinds`, `:admission-entries`, `:checkpoints`, `:remember-answers`.
These are deliberately not part of the API most consumers need. `:remember-answers` defaults to
`:on-repeat`, which keeps a finished answer only once the same check has been asked twice.

TTL and capacity govern resource retention only. Every hit still authenticates
the entry and revalidates causal ancestry plus complete dependency proofs.

Unrelated Datomic transactions, relationship no-ops, and changes to relations outside a cached
permission's dependency set do not expire an entry.

Result values contain internal IDs and are externalized against the request's
selected immutable snapshot. A query input naming an unknown external ID
returns the ordinary false/empty boundary result and is not cached. Content
proof includes endpoint identity mappings; managed deployments must publish
identity mutations through the v3 journal.

A *result* object that has no external ID in the database it was evaluated against is a different
matter, and `lookup-resources`/`lookup-subjects` raise `{:type :eacl/unresolvable-object}` listing
every offending eid rather than silently omitting rows from an authorization enumeration. This is a
data-integrity fault, not a cache fault — it is raised identically with
`{:cache eacl-cache/no-cache}`. The usual
cause is an entity retracted without first calling `delete-relationships!`, leaving a relationship
half that still grants; `eacl.datomic.integrity/dangling-relationship-report` finds them.
`read-relationships` deliberately still returns such a half with a nil id, because reading it is how
you repair it.

Custom stores implement `eacl.datomic.cache/CacheStore`. New providers may also implement
`CacheProvider` to declare `:portable-values`, `:opaque-values`, `:ttl`, and
`:namespaced-clear`. Portable providers may store completed pages, counts, Booleans, and exact
metadata, but must reject process-local recursive continuations. A cursor with an unchanged proof
then recomputes its prefix; it never changes the answer. This supports adapters backed by RocksDB,
Apache Kvrocks, Redis, an ephemeral Datomic database, or another store without adding those
dependencies to EACL core. Namespace cleanup must remove only EACL's configured namespace; it must
never require flushing the provider's whole database. Provider projects can call
`eacl.datomic.cache-store-contract/assert-provider-contract!` to verify
TTL, wrapper validation, namespace isolation, failures, and concurrent access.

Cache lookup, capability probing, publication, eviction, and provider-error telemetry are
best-effort on authorization paths. A provider failure is contained as a miss or rejected
publication: live reads compute authoritatively, and exact/cursor reads fall back to historical
evaluation.

No cache data or derived tuples are written to the consumer's Datomic database.

### Consistency and Zed tokens

Under `:coherence-authority :managed`, graph writes and reads return an
authenticated v3 `:zed/token` bound to backend, native source/store identity,
causal family/branch, random graph mutation anchor, order hint, exact locator,
issue time, and expiry. Transaction counters are hints, never ancestry proof.
Read operations accept these descriptors:

```clojure
(require '[eacl.spicedb.consistency :as consistency])
(require '[eacl.datomic.core :as eacl-datomic])

;; Authoritative backend barrier. This is the default.
(eacl/can? acl subject :view resource consistency/fully-consistent)

;; Current complete local snapshot, with normal proof-validated cache lookup.
(eacl/can? acl subject :view resource consistency/minimize-latency)

;; Select a snapshot containing the token's mutation anchor. Datomic may use
;; targeted (d/sync conn T) as a bounded waiting hint.
(eacl/can? acl subject :view resource
           (consistency/at-least-as-fresh write-token))

;; Exact historical snapshot. A matching cache entry is a fast path; otherwise
;; EACL reconstructs the basis with d/as-of and historical schema state.
(eacl/can? acl subject :view resource
           (consistency/at-exact-snapshot prior-token))

;; Construct lower-bound tokens without synchronizing.
(def now-token (eacl-datomic/current-zed-token acl))
```

Datomic `fully-consistent` performs a bounded zero-argument `d/sync`
authoritative barrier. At-least waits and exact/cursor reconstruction are
bounded by `:consistency-sync-timeout-ms` (30,000 ms by default). Failure
returns a typed error; it never falls back to an older or incomparable graph.
DataScript and Datahike advertise only the modes their configured source can
actually establish.

Zed-token authentication prevents a frontend from changing the database or revision, but it does
not make a valid token single-use, bind it to an end user, prevent replay, or authorize historical
access. For a token echoed through an untrusted frontend, the backend should normally choose
`at-least-as-fresh`. Selecting `at-exact-snapshot` permits historical evaluation and must be a
backend-controlled, independently authorized decision; do not let the frontend choose the
consistency descriptor.

Optional bounded checkpoints let callers request "at least as fresh as N seconds ago" without
doing arithmetic on `t`:

```clojure
(def acl
  (eacl.datomic.core/make-client
   conn
   {:coherence-authority :managed
    :cache {:checkpoints true}}))

(def lower-bound
  (eacl-datomic/zed-token-at-least-seconds-ago acl 30))

(eacl/can? acl subject :view resource
           (consistency/at-least-as-fresh lower-bound))
```

Checkpoints contain only sampled monotonic capture times and observed Long revisions. They retain
no DB values, start no timer, are disabled by default, and never call `d/sync`.

`:recursive-traversal-limits` tunes hard safety ceilings on recursive permission traversal (see [Limitations](#limitations-deficiencies--gotchas)):

```clojure
(def acl (eacl.datomic.core/make-client conn
           {:recursive-traversal-limits {:max-derived-grants 1000000}}))
```

Keys you omit keep their defaults (`eacl.datomic.impl.indexed/default-recursive-traversal-limits`), so a partial override cannot silently disable the limits it does not mention.
These are heap-protection limits, not ordinary page-size controls. Prefer `:count-limit` for
situated authorization counts; raise traversal ceilings only after load-testing the host JVM.

For multi-peer deployments, configure stable shared page- and Zed-token keys so `eacl4_...`
cursors and frontend-returned `eacl_z3_...` tokens survive restarts and can be verified by every
backend:

```clojure
(def acl (eacl.datomic.core/make-client conn
           {:coherence-authority :managed
            :page-token-key "32+ bytes of shared secret key material"
            :page-token-kid :page-2026-07
            :zed-token-keyring {:zed-2026-06 old-zed-root
                                :zed-2026-07 current-zed-root}
            :zed-token-kid :zed-2026-07
            :consistency-sync-timeout-ms 10000}))
```

New Zed tokens are signed only with `:zed-token-kid`; all keys explicitly retained in
`:zed-token-keyring` remain verification keys during a rotation overlap. Remove an old key only
after the intended outstanding-token lifetime. A single `:zed-token-key` is shorthand for a
one-key ring. If no dedicated Zed key is configured, EACL derives domain-separated signing keys
from the page-token keyring. The default page key is random per client instance, so the derived
default is fail-closed but not portable across clients, restarts, or backend instances. The unreleased unsigned
`eacl_z1_` format is rejected.

### Unknown object IDs

EACL follows SpiceDB semantics for object IDs that don't resolve to an entity:

- **Reads** (`can?`, `lookup-resources`, `lookup-subjects`, `count-resources`, `count-subjects`, `read-relationships`) treat unknown IDs as matching nothing: `can?` returns `false`, lookups and reads return empty pages.
- **Writes** (`write-relationships!` and friends) throw `ex-info {:type :eacl/unknown-object, :object {:type … :id …}}` — a relationship to a nonexistent entity is unsatisfiable, and failing loudly beats minting ghost entities or raw Datomic errors.

### Deleting a permissioned entity

> [!IMPORTANT]
> `:db.fn/retractEntity` does **not** remove an entity's EACL relationships. Delete those relationships first; `eacl/delete-object!` is a convenience helper for doing so.

A v7 relationship is two datoms living on two different entities, each naming its peer *inside a tuple value*:

```
[<subject-eid>  :eacl.v7.relationship/subject-type+relation+resource-type+resource  [subject-type relation-eid resource-type <resource-eid>]]
[<resource-eid> :eacl.v7.relationship/resource-type+relation+subject-type+subject   [resource-type relation-eid subject-type <subject-eid>]]
```

Datomic's `:db.fn/retractEntity` follows `:db.type/ref` *attributes*; it does not follow ref-typed *components of a heterogeneous tuple* (and a heterogeneous tuple cannot be `:db/isComponent`). So retracting a permissioned entity directly removes only the half stored on that entity and leaves the peer's half behind, where it keeps answering queries — a deleted resource still passes `can?`, a deleted subject still appears in `lookup-subjects` — and the survivor is unreachable through `write-relationships!`, because resolving either endpoint now throws `:eacl/unknown-object`.

The expected workflow is to call `eacl/delete-relationships!` for relationships known by the
consumer, then retract the entity. `delete-object!` is a convenient catch-all:

```clojure
(eacl/delete-object! acl (->account "acme"))   ; removes both halves of every relationship touching it
@(d/transact conn [[:db.fn/retractEntity account-eid]])
```

Or in one application transaction, using the tx-data directly:

```clojure
(require '[eacl.datomic.impl :as impl])

@(d/transact conn (conj (impl/tx-delete-object (d/db conn) account-eid)
                        [:db.fn/retractEntity account-eid]))
```

`delete-object!` retracts relationships only — retracting the entity itself stays your call. It is idempotent, and it also accepts the raw eid of an entity you already retracted, which is how you clean up after the fact.

EACL does not prevent direct `:db.fn/retractEntity` calls or add existence probes to every read.
To detect and repair relationship halves left by such calls, use the explicit offline integrity
API (it scans both relationship indexes):

```clojure
(require '[eacl.datomic.integrity :as integrity])

(integrity/dangling-relationship-report (d/db conn) {:sample-size 20})

(doseq [tx-data (integrity/repair-tx-batches (d/db conn) {:batch-size 1000})]
  @(d/transact conn tx-data))
```

## Schema Syntax

EACL uses the SpiceDB schema DSL. Use `eacl/write-schema!` to define your schema:

```clojure
(eacl/write-schema! acl
  "definition user {}
   
   definition account {
     relation owner: user
     permission admin = owner
   }
   
   definition server {
     relation account: account
     permission admin = account->admin
   }")
```

### Internal Definition Records

`eacl.datomic.impl/Relation` and `Permission` are implementation records used by migrations and
tests. Transacting them directly is not a supported schema API: it bypasses validation, the
schema-generation stamp, and client cache replacement. Use `eacl/write-schema!` for every schema
change. If an operational tool bypasses that boundary, recreate every affected client.

`Permission` supports the following spec syntax:
- `{:relation some_relation}` - direct permission via relation
- `{:permission some_permission}` - permission via another permission  
- `{:arrow source :permission via_permission}` - arrow to permission
- `{:arrow source :relation via_relation}` - arrow to relation

## Example Schema

Here's a complete example of defining a schema with `eacl/write-schema!`:

```clojure
(eacl/write-schema! acl
  "definition user {}

   definition platform {
     relation super_admin: user
   }

   definition account {
     relation platform: platform
     relation owner: user
     
     permission admin = owner + platform->super_admin
   }

   definition server {
     relation account: account
     relation shared_admin: user
     
     permission reboot = account->admin + shared_admin
   }")
```

This schema defines:
- `platform` resources can have `super_admin` users
- `account` resources can have a `platform` and `owner`, with `admin` permission granted to owners and platform super_admins
- `server` resources belong to an `account` and can have `shared_admin` users, with `reboot` permission granted to account admins and shared_admins
```

Now you can transact relationships. The usual way is `eacl/create-relationships!` against existing entities (see Quickstart). To create entities and relationships **in the same transaction**, use `eacl.datomic.impl/tx-relationship` with `{:allow-tempids? true}` — tempid pass-through is opt-in because a typo'd ID would otherwise silently create a ghost entity:

```clojure
(require '[eacl.datomic.impl :as impl])

(let [db (d/db conn)]
  @(d/transact conn
    (concat
      [{:db/id   "user1-tempid"
        :eacl/id "user1"}

       {:db/id   "account1-tempid"
        :eacl/id "account1"}]

      (impl/tx-relationship db
        (impl/Relationship (spice-object :user "user1-tempid") :owner (spice-object :account "account1-tempid"))
        {:allow-tempids? true}))))
```

## Limitations, Deficiencies & Gotchas:

- *Exact snapshots require Datomic history:* `at-exact-snapshot` and continued cursors reconstruct
  a historical DB on cache miss. If the authenticated basis is no longer reconstructable, EACL
  returns a typed snapshot-unavailable/cursor-expired error rather than falling forward.
- *No negation operator:* EACL only supports Union (`+`) permission operators, not `-` negation, e.g.
  - `permission admin = owner + shared_admin` is valid,
  - but `permission admin = owner - banned_member` is not (note the `-` Negation operator).
  - You can work around this limitation by doing a negation in your application logic, e.g. `(and (not (eacl/can? acl ...) (eacl/can? acl ...)))`, but it's not free. Once EACL has a cache, this becomes more viable to implement in EACL.
- Arrow syntax is limited to one level of nesting, e.g.
  - `permission arrow = relation->via-permission` is supported,
  - but `permission arrow = relation->subrelation->permission` is not. To implement this would require anonymous shadow relations. May require schema changes.
- You need to specify a `Permission` for each relation in a sum-type permission. In future this can be shortened.
- `subject.relation` is not currently supported. It's useful for group memberships.
- `expand-permission-tree` is not implemented yet.
- *Mutation-proof caching requires complete writer authority:* managed EACL
  writes atomically publish a v3 mutation record, graph head, and affected
  relation identities. Set `:coherence-authority :managed` only when every
  schema, relationship, mutable identity, caveat, and custom-dependency writer
  follows that protocol. Mixed or hand-written writers must use the default
  `:unknown` authority, where `:proof-mode :auto` selects complete content
  proofs and detects database-visible changes without listener coordination.
- *Deleting entities:* `:db.fn/retractEntity` does not remove an entity's relationships. Consumers should delete relationships first; `delete-object!` is a convenience helper, and `eacl.datomic.integrity` provides explicit detection/repair — see [Deleting a permissioned entity](#deleting-a-permissioned-entity).
- *Recursive cursors benefit from their continuation:* a permission that transitively depends on itself
  (`permission read = reader + parent->read`) is evaluated in traversal order. Sequential cache
  hits resume the traversal and avoid `O(N²/page-size)` enumeration. An evicted, expired, rejected,
  or unavailable continuation replays against the cursor's historical DB and schema, including
  after a relevant live write.
  Recursive work retains hard heap-protection ceilings, and counts use one traversal. Use
  `:count-limit` to bound count work; do not raise `:recursive-traversal-limits` without JVM load
  tests.
- *Return order:* Acyclic EACL lookups enumerate in Datomic eid order and relationship reads enumerate in tuple-index order. Recursive lookups enumerate in deterministic traversal order. SpiceDB returns results in discovery or schema order. You should not rely on either system's order as a domain sort order.

## How to Run All Tests

```shell
clj -X:test
```
## Run Test for One Namespace

```bash
clj -M:test -n eacl.datomic.impl.indexed_test
```

## Run Tests for Multiple Namespaces

```bash
clj -X:test :nses '["my-namespace1" "my-namespace2"]'
```

Note difference between `-M` & `-X` switches.

## Run a single test (under deftest)

```bash
clojure -M:test -v my.namespace/test-name
```

## Upgrading

### v6 → v7

v7 changed how Relationships are stored in Datomic (one entity per relationship → two tuple datoms on your subject & resource entities). The public API is unchanged, but stored relationship data must be migrated once. To protect you, `eacl.datomic.core/make-client` checks the storage version recorded in Datomic and **refuses to start against unmigrated v6 data** with `{:type :eacl/storage-version}` — v7 code reading a v6 database would otherwise silently answer every permission check with `false`/empty.

Migrate with the batteries-included, idempotent [`eacl.migrations.v6-to-v7`](modules/eacl-datomic/src/eacl/migrations/v6_to_v7.clj) namespace:

```clojure
(require '[eacl.migrations.v6-to-v7 :as migrations])
(migrations/migrate! conn {:schema "definition user {} ..."})  ; re-asserts your schema via write-schema!
```

or opt into automatic migration at client construction:

```clojure
(eacl.datomic.core/make-client conn {:auto-migrate-v6 {:schema "definition user {} ..."}})
```

The migration is additive and rollback-friendly (v6 data is kept until you explicitly retract it) and end-to-end tested in [modules/eacl-datomic/test/eacl/migrations/v6_to_v7_test.clj](modules/eacl-datomic/test/eacl/migrations/v6_to_v7_test.clj). For the full sequence — write-pause window, verification, soak, cleanup, rollback — follow the [v6 → v7 migration guide](docs/migration-v6-to-v7.md).

## Funding

Some of this open-source work was generously funded by my former employer, [CloudAfrica](https://cloudafrica.net/).

# Licence

- EACL is licensed under the Eclipse Public License v2.0 since 2026-03-05.
- EACL was initially licensed under BSL and later Affero GPL (on 2025-05-27), but is now licensed under EPL 2.0.
