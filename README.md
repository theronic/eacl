# 🦅 **EACL**: Enterprise Access ControL

EACL is a _situated_ [ReBAC](https://en.wikipedia.org/wiki/Relationship-based_access_control) authorization library inspired by [SpiceDB](https://authzed.com/spicedb), built in Clojure and backed by Datomic Pro, Datahike or DataScript.

_Situated_ here means that your permission data lives _next to_ your application data in the backend you already control, which has some benefits:
1. Avoids a network hop. To leverage SpiceDB's consistency semantics, you need to hit your DB (or cache) to retrieve the latest stored ZedToken anyway, so you might as well query the DB directly, which is what EACL does.
2. One less external dependency to deploy & sync relationships.
3. No relationship-sync lag between the application database and authorization
   data. Minimize-latency reads use the current database basis visible to the
   local Peer; that is locally consistent, not a claim of global full
   consistency.

EACL is pronounced "EE-kəl", like "eagle" with a `k` because it keeps a watchful eye on permissions.

## Goals

- Best-in-class ReBAC authorization for Clojure applications backed by Datomic Pro, Datahike or Datascript with a performance goal of 10M permissioned entities.
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
5. EACL is fast. You may be tempted to roll your own ReBAC system using recursive Datomic child rules, but the eager Datalog engine materializes intermediate results and cannot efficiently handle every grounding case. Correct bidirectional cursor pagination is also non-trivial because parallel paths through the permission graph can yield duplicate resources. EACL handles both traversal and pagination.

## Performance

- EACL traverses acyclic ReBAC paths via low-level Datomic `d/index-range`, `d/seek-datoms` & `d/rseek-datoms` calls. Recursive permission closures use deterministic traversal order with request-local dedupe, avoiding both Datomic recursive Datalog materialization and persisted grant caches. Acyclic lookup results are returned in Datomic eid order; recursive lookup results are returned in traversal order.
  - I have investigated implementing custom Sort Keys, but they are not currently feasible without adding a lot of storage & write costs.
- EACL is fast, but makes no strong performance claims at this time. For typical workloads, EACL should be as fast as, or faster than, SpiceDB. EACL is not meant for hyperscalers.
- EACL is internally benchmarked against ~800k permissioned resources with good latency (5-30ms per query). You can scale Datomic Peers horizontally and dedicate peers to EACL as needed.
- The performance goal for EACL is to handle 10M permissioned entities with real-time performance.
- EACL does not support all SpiceDB features. Please refer to the [limitations section](#limitations-deficiencies--gotchas) to decide if EACL is right for you.
- EACL uses a bounded, client-private, multi-tier cache. Repeated operations
  can reuse complete answers, while different operations can share compiled
  schema plans and unchanged relationship projections where their graph paths
  overlap. The cache never changes authorization semantics and can be disabled
  globally or per request. See [Caching](#caching).
- Acyclic lookup cursors retain a per-permission-path intermediate frontier. Later pages resume each arrow path at the earliest intermediate that can still contribute, and permanently skip paths exhausted in that scan direction. This prevents deep pages from repeatedly scanning intermediates already known to be irrelevant.
- Acyclic lookup performance should scale roughly with permission graph complexity * `O(logN)` for `N` resources in terminal resource Relationship indices. Recursive lookup pages are deterministic traversal-order pages with request-local dedupe. Continuation hits make a sequential walk approximately linear in traversed work; a continuation miss deterministically replays the prefix against the same exact snapshot. Counts consume bounded frontier pages (at most 16,384 EIDs at once) or one explicit recursive state machine; they never retain an entire broad lazy result head. Subjects are typically sparse compared to resources, i.e. 1k users will have access to 1M resources – rarely the other way around.

Public cursors are opaque, authenticated, and tied to the query and database
snapshot that created them. A cursor walk stays on that snapshot even when the
current database advances. If the backend can no longer reconstruct it, EACL
returns a typed cursor-expired or snapshot-unavailable error.

## Project Status

> [!WARNING]
> EACL is under active development.
> I try hard not to introduce breaking changes, but if data structures change, the major version will increment.
> The current version is the EACL 8.0 release candidate.
> The four `8.0.0-SNAPSHOT` artifacts are available from Clojars under the
> verified `dev.eacl` group.

## Modules

Choose the adapter for your backend. It brings in the shared EACL module at
the same version:

```clojure
;; Datomic Pro
{:deps {dev.eacl/eacl-datomic {:mvn/version "8.0.0-SNAPSHOT"}}}

;; Datahike
{:deps {dev.eacl/eacl-datahike {:mvn/version "8.0.0-SNAPSHOT"}}}

;; DataScript
{:deps {dev.eacl/eacl-datascript {:mvn/version "8.0.0-SNAPSHOT"}}}

;; Core-only consumers and backend authors
{:deps {dev.eacl/eacl {:mvn/version "8.0.0-SNAPSHOT"}}}
```

For Git-based development, pin a commit and select the module root:

```clojure
{:deps {dev.eacl/eacl-datomic
        {:git/url "https://github.com/theronic/eacl.git"
         :git/sha "REPLACE_WITH_FULL_SHA"
         :deps/root "modules/eacl-datomic"}}}
```

For a full local checkout, keep the same library coordinate and use
`:local/root`; the backend module resolves the sibling core module:

```clojure
{:deps {dev.eacl/eacl-datomic
        {:local/root "/absolute/path/to/eacl/core/modules/eacl-datomic"}}}
```

<a id="source-dependencies-and-formal-tooling"></a>

### Development from source

Source consumers who compile the EACL kernel locally need the Clojure CLI,
Node.js, and the repository-pinned Dafny, Apalache, and TLA+ tools. Prepare the
generated JVM and browser runtimes before using a Git or `:local/root`
dependency:

```bash
cd modules/eacl

# Default Java target
clojure -T:build prep

# Example Java 17 target
clojure -T:build prep :java-release 17
clojure -T:build jar :java-release 17
```

Pass the same `:java-release` to `prep` and `jar` or `install`. The default is
Java 26; source builds may target Java 8 through Java 26, subject to their
backend and application dependencies. See [formal/README.md](formal/README.md)
for tool versions and the full verification commands.

EACL does not select a logging implementation. Applications remain responsible for their own logging backend and configuration.

For module selection, current capability differences, cache mutation rules, and recursive controls, see the [backend guide](docs/v8-backend-modules-and-upgrade.md). Backend authors should also read the [adapter boundary](docs/v8-backend-adapter-boundary.md).

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
- `(eacl/expand-permission-tree acl filters) => {:expanded-at token :tree-root node}`
  returns the shallow SpiceDB-compatible expansion for one resource and
  relation or permission.

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

### Deadlines and cooperative cancellation

Every bounded read accepts an optional per-request `:cancellation-token` in
addition to `:timeout-ms`. Create and cancel the token through the public EACL
API:

```clojure
(let [token (eacl/cancellation-token)]
  ;; Give `token` to the HTTP/request owner before starting the read.
  (future
    (eacl/lookup-resources
     acl
     {:subject (eacl/spice-object :user "alice")
      :permission :view
      :resource/type :document
      :first 100
      :cancellation-token token}))
  (eacl/cancel! token))
```

Cancellation is cooperative and best-effort. EACL checks it at the same
orchestration, cursor, cache, traversal-quantum, and adapter-command boundaries
as the absolute deadline and, when observed before completion, throws
`:eacl.execution/cancelled` without returning a partial answer. A synchronous
adapter call already in progress must return before the next check, and a
completed result may win a race with a late cancellation. Applications must
therefore keep the server deadline and bounded admission control; interrupting
a worker thread is not a substitute. The token is execution-only and is
excluded from cache, continuation, and authenticated cursor identity. One
token belongs to one logical request.

### Schema Maintenance

- `(eacl/write-schema! acl schema-string)` parses a SpiceDB schema DSL string, validates it, computes deltas against existing schema, checks for orphaned relationships, and transacts changes atomically.
- `(eacl/read-schema acl)` returns the current schema as a map of `{:relations [...] :permissions [...]}`.

All schema changes must use `eacl/write-schema!`. If an application changes
the authorization schema directly, follow the recovery procedure in
[Caching](#caching) before resuming authorization traffic.

### Permission-tree expansion

Expansion accepts exactly `:resource`, `:permission`, and the optional
`:consistency`, `:timeout-ms`, and `:cancellation-token` keys:

```clojure
(eacl/expand-permission-tree
 acl
 {:resource (eacl/spice-object :document "readme")
  :permission :view
  :consistency consistency/fully-consistent
  :timeout-ms 5000})
;; =>
;; {:expanded-at "eacl_z3_..."
;;  :tree-root
;;  {:expanded-object {:type :document :id "readme"}
;;   :expanded-relation :view
;;   :intermediate
;;   {:operation :union
;;    :children
;;    [{:expanded-object {:type :document :id "readme"}
;;      :expanded-relation :viewer
;;      :leaf {:subjects [{:type :user :id "alice"}]}}]}}}
```

A node contains exactly one of `:leaf` or `:intermediate`. Permission and
arrow boundaries remain visible; expansion is shallow in the SpiceDB sense,
so leaves contain subjects found by direct relation scans rather than a
flattened effective-membership set. To decide whether a subject has the
permission, use `can?`; do not infer authorization by flattening a tree.

Child and subject vector order is non-semantic and may differ by backend.
Empty branches and duplicate paths are preserved. Compare trees as annotated
topology with child/subject multisets when order is irrelevant. The exact
supplied root ID is retained, while scanned IDs are converted with the
selected client's object-ID codec.

The response tree and `:expanded-at` token are derived from the same selected
immutable snapshot. Replay the token with
`(consistency/at-exact-snapshot (:expanded-at response))` only on a backend
that advertises exact historical selection; otherwise use it as an
at-least-as-fresh causal floor. Unsupported consistency, unavailable history,
deadlines, unknown roots, cycles, codec failures, adapter-contract failures,
and structural limits produce typed all-or-error failures—no lazy or partial
tree is returned.

Clients accept positive exact-integer `:permission-tree-limits` overrides.
They are configuration-only, not request keys:

```clojure
(eacl.datascript.core/make-client
 conn
 {:permission-tree-limits
  {:max-depth 50
   :max-schema-components 100000
   :max-relationship-values 100000
   :max-tree-nodes 100000
   :max-leaf-subjects 100000}})
```

Every bundled backend uses the same portable expansion kernel. Their only
observable differences are supported consistency modes, historical retention,
native scan order, and configured identity conversion.

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

Forward and backward pages return results in the same order for one fixed query
and cursor-pinned snapshot. Acyclic lookup uses backend internal-ID order,
recursive lookup uses deterministic traversal order that is stable across page
sizes, and relationship reads use backend tuple-index order. These are
pagination orders, not a global, cross-backend, or domain sort order. Backward
pagination returns the previous window; it does not reverse the result order.

## Datomic Pro Quickstart

The following example is contained in [eacl-example](https://github.com/theronic/eacl-example).

Add the Datomic adapter dependency to your `deps.edn` file:

```clojure
{:deps {dev.eacl/eacl-datomic {:mvn/version "8.0.0-SNAPSHOT"}}}
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

; Make an EACL client that satisfies the `IAuthorization` protocol:
(def acl
  (eacl.datomic.core/make-client
   conn
   {:object-id->lookup-ref (fn [obj-id] [:eacl/id obj-id])
    :entid->object-id (fn [db eid] (:eacl/id (d/entity db eid)))}))

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

## Datahike Quickstart

For Clojure/JVM applications backed by Datahike, add the Datahike adapter
dependency to your `deps.edn` file:

```clojure
{:deps {dev.eacl/eacl-datahike {:mvn/version "8.0.0-SNAPSHOT"}}}
```

```clojure
(ns my-eacl-datahike-project
  (:require [datahike.api :as d]
            [eacl.core :as eacl]
            [eacl.datahike.core :as eacl.datahike]))

; Create an in-memory Datahike database and install EACL's Datahike schema:
(def conn (eacl.datahike/create-conn))

; Make an EACL client that satisfies the `IAuthorization` protocol:
(def acl (eacl.datahike/make-client conn {}))

; Write your permission schema using SpiceDB schema DSL:
(eacl/write-schema! acl
  "definition user {}

   definition account {
     relation owner: user
     permission admin = owner
   }")

; Transact application entities with unique `:eacl/id` values:
(d/transact conn
  [{:eacl/id "user-1"}
   {:eacl/id "account-1"}])

; Create a Relationship between existing entities:
(eacl/create-relationship! acl
  (eacl/spice-object :user "user-1")
  :owner
  (eacl/spice-object :account "account-1"))

; Run a Permission Check with `can?`:
(eacl/can? acl
  (eacl/spice-object :user "user-1")
  :admin
  (eacl/spice-object :account "account-1"))
; => true
```

## DataScript Quickstart

For server-side or browser demos, use the DataScript adapter:

```clojure
{:deps {dev.eacl/eacl-datascript {:mvn/version "8.0.0-SNAPSHOT"}}}
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

Internally, EACL stores relation and permission definitions as entities and
stores each relationship in both directions for efficient traversal.

## EACL ID Configuration

SpiceDB uses strings for all external subject & resource IDs, whereas EACL uses Datomic entity IDs internally for all IDs. However, EACL lets you configure how internal IDs should be coerced to external IDs and vice versa.

*Note*: internal Datomic eids should not be exposed to consumers, because those eids are not guaranteed to be stable after a DB rebuild.

`eacl.datomic.core/make-client` accepts a Datomic connection and
`:entid->object-id`/`:object-id->lookup-ref` functions for converting between
internal entity IDs and external object IDs.

It is common to attach a unique UUID to permissioned entities for exposing them externally, or you can convert external->internal at your call sites. Here is how you can configure EACL to convert to/from a unique attribute named `:your/id`:

```clojure
(def acl (eacl.datomic.core/make-client conn
           {:entid->object-id (fn [db eid] (:your/id (d/entity db eid)))
            :object-id->lookup-ref (fn [obj-id] [:your/id obj-id])}))
```

Note that this attribute should have property `:db/unique :db.unique/identity`.

The default options are to use the built-in EACL string attr `:eacl/id`, but you can use the internal Datomic eids with the following "identity" functions:
```clojure
(def acl (eacl.datomic.core/make-client conn
           {:entid->object-id (fn [_db eid] eid)
            :object-id->lookup-ref (fn [obj-id] obj-id)}))
```

`make-client` rejects unknown options with `{:type :eacl/invalid-config}`.
Page tokens expire after 5 minutes by default; tune with
`:cursor-ttl-seconds`.

### Caching

Caching is automatic, bounded, and private to each EACL client. Cache data is
never written to the application database. EACL first looks for an answer from
the exact immutable database value selected by the request. It may reuse an
older answer only when it can establish that the relevant schema and
relationships have not changed. If it cannot establish that safely, it runs the
authorization query normally.

A long-running request can continue using the immutable database value it
started with while newer requests see newer data. EACL does not promise cache
reuse for arbitrary `as-of`, `since`, filtered, speculative, or
caller-constructed database values.

Cache coherence is guaranteed only when authorization mutations use EACL's
supported paths:

- Change schemas with `eacl/write-schema!`.
- Add and remove relationships with EACL relationship APIs, or transact
  EACL-produced transaction data intact.
- Delete permissioned entities with the documented
  [safe deletion flow](#deleting-a-permissioned-entity).

Ordinary application datoms that do not affect authorization are unrestricted.
If an application changes EACL schema or relationship storage directly, splits
EACL transaction data, changes the identity of a permissioned object outside
the documented contract, or leaves relationships behind during deletion,
cached authorization results may be stale.

To recover after an unsupported authorization mutation:

1. Stop affected authorization traffic in every process.
2. Repair the schema, identity, or relationship data through a supported EACL
   path.
3. Expire or recreate every affected EACL client in every process.
4. Resume traffic only after repair and cache rotation are complete.

Cache expiry removes remembered answers; it does not repair ghost
relationships. Rewriting an unchanged schema is also not a cache flush.

Most applications need no cache configuration. Disable caching for one client
with `eacl.cache/no-cache`:

```clojure
(require '[eacl.cache :as eacl-cache])

(def acl
  (eacl.datomic.core/make-client
   conn
   {:cache eacl-cache/no-cache}))
```

Or bypass the cache for one request:

```clojure
(eacl/can? acl
           {:subject alice
            :permission :view
            :resource doc
            :cache? false})
```

Use `eacl/check-permission` when a caller needs cache provenance in addition
to the Boolean decision:

```clojure
(eacl/check-permission
 acl
 {:subject alice
  :permission :view
  :resource doc})
;; => {:allowed? true, :cached? false, :cache-basis ...}
```

Inspect or expire a client through its backend API:

```clojure
(eacl.datomic.core/cache-stats acl)
(eacl.datomic.core/expire-cache! acl)

(eacl.datahike.core/cache-stats acl)
(eacl.datahike.core/expire-cache! acl)

(eacl.datascript.core/cache-stats acl)
(eacl.datascript.core/expire-cache! acl)
```

After a database restore, reset, branch replacement, or other operation that
can replace history, expire or replace every affected client before serving
requests. Multi-process deployments that exchange cursors or tokens must
coordinate the source-lifecycle rotation described in the
[cache guide](docs/cache.md).

Custom ID converters remain local to one client unless every participating
process uses the same deterministic converter and stable adapter fingerprint.

For cache tuning, custom identity codecs, metrics, proof availability, and the
full recovery and correctness model, read
[Cache behavior and coherence](docs/cache.md).

### Consistency and Zed tokens

Authorization defaults to the immutable database value currently visible to
the local backend. Mutation responses include an authenticated revision token.
Reads can request stronger behavior when the backend supports it:

```clojure
(require '[eacl.spicedb.consistency :as consistency])

;; Default: current local database value.
(eacl/can? acl subject :view resource
           consistency/minimize-latency)

;; Synchronize before selecting the database value.
(eacl/can? acl subject :view resource
           consistency/fully-consistent)

;; Read at least as new as an earlier EACL mutation.
(eacl/can? acl subject :view resource
           (consistency/at-least-as-fresh write-token))

;; Read the exact historical snapshot named by a token, if available.
(eacl/can? acl subject :view resource
           (consistency/at-exact-snapshot prior-token))
```

Datomic supports synchronization and exact historical reconstruction while the
required history remains available. Datahike advertises only the guarantees
supported by its configured store and writer. DataScript does not provide
general historical snapshot reconstruction. If a backend cannot satisfy the
requested guarantee, EACL returns a typed error rather than silently selecting
a different snapshot.

Treat Zed tokens as opaque. A token proves freshness only for its original
backend, database, branch, and lifecycle. For a token returned through an
untrusted frontend, the backend should normally choose
`at-least-as-fresh`. Do not let a frontend request exact historical
authorization without a separate authorization decision.

Multi-process deployments must configure the same cursor and Zed-token
verification keys on every instance that accepts the same tokens:

```clojure
(def acl
  (eacl.datomic.core/make-client
   conn
   {:security-key "32+ bytes of shared secret key material"
    :security-kid :cursor-2026-07
    :zed-token-keyring {:zed-2026-06 old-zed-root
                        :zed-2026-07 current-zed-root}
    :zed-token-kid :zed-2026-07}))
```

Retain old verification keys for the intended token lifetime during key
rotation. The default keys are client-local, so default cursors and tokens do
not survive restarts or load balancing.

See the [backend guide](docs/v8-backend-modules-and-upgrade.md) for exact
capabilities, synchronization timeouts, checkpoints, key rotation, and
recursive traversal controls.

### Unknown object IDs

EACL follows SpiceDB semantics for object IDs that don't resolve to an entity:

- **Reads** (`can?`, `lookup-resources`, `lookup-subjects`, `count-resources`, `count-subjects`, `read-relationships`) treat unknown IDs as matching nothing: `can?` returns `false`, lookups and reads return empty pages.
- **Writes** (`write-relationships!` and friends) throw `ex-info {:type :eacl/unknown-object, :object {:type … :id …}}` — a relationship to a nonexistent entity is unsatisfiable, and failing loudly beats minting ghost entities or raw Datomic errors.

If a lookup result has no external ID in the selected database,
`lookup-resources` and `lookup-subjects` raise
`{:type :eacl/unresolvable-object}` and identify every offending internal ID
instead of silently omitting authorized objects. This usually indicates a
dangling relationship left by retracting an entity before its relationships.
`read-relationships` still returns the damaged relationship half with a nil
ID so it can be repaired.

### Deleting a permissioned entity

> [!IMPORTANT]
> Do not call the backend's ordinary entity-retraction operation on a
> permissioned entity before removing its EACL relationships.

EACL stores both directions of a relationship. A native entity retraction
removes the half stored on the target, but it cannot follow the peer ID stored
inside the other endpoint's tuple or vector. The surviving half is a **ghost
relationship** and can continue granting access.

The portable deletion sequence is:

```clojure
;; Remove every relationship touching the object in both directions.
(eacl/delete-object! acl (->account "acme"))

;; Then delete the application entity with the backend's normal operation.
@(d/transact conn [[:db.fn/retractEntity account-eid]])
```

`delete-object!` removes relationships but does not delete the application
entity. It is idempotent and batches high-degree cleanup.

Backends that support transaction functions also provide an optional atomic
`:eacl.fn/retractEntity`. It removes both relationship halves and the target
entity in one transaction. The function is not installed by the normal EACL
schema; enabling it is an explicit deployment step.

| Backend/configuration | Safe-retraction support |
| --- | --- |
| Datomic Peer/Pro | Named `:eacl.fn/retractEntity` |
| DataScript CLJ/CLJS | Named or direct in-process function |
| Datahike with an in-process writer | Named or direct, depending on schema configuration |
| Datahike remote/function-unsafe writer | Use `delete-object!` and ordinary deletion |

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

The target can be a numeric entity ID or a valid lookup ref. Multiple and
repeated invocations compose in one transaction:

```clojure
@(d/transact conn [[:eacl.fn/retractEntity 1]
                   [:eacl.fn/retractEntity 2]
                   [:eacl.fn/retractEntity 1]])
```

A numeric entity ID can repair peer-side ghosts after an earlier native
retraction. A lookup ref that no longer resolves cannot reveal the former
entity ID, so it cannot perform that repair.

Do not add relationships involving a target in the same application
transaction that safely retracts it. Prefer `delete-object!` for very
high-degree targets so cleanup can be batched.

Use the backend's `safe-retraction/support-descriptor` before choosing a
Datahike or DataScript deployment mode. Installation, direct-mode examples,
restore behavior, integrity reports, and repair tools are documented in the
adapter guides:

- [Datomic](modules/eacl-datomic/README.md#optional-atomic-entity-retraction)
- [Datahike](modules/eacl-datahike/README.md#optional-atomic-entity-retraction)
- [DataScript](modules/eacl-datascript/README.md#optional-atomic-entity-retraction)

## Schema Syntax

EACL uses the SpiceDB schema DSL. Use `eacl/write-schema!` to define your schema:
Like SpiceDB, each `relation` or `permission` declaration ends at a newline;
put the next declaration and the definition's closing brace on a later line.
Empty definitions may still use the compact `definition user {}` form.

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

- *Exact snapshots require backend history:* `at-exact-snapshot` and continued
  cursors require the backend to reconstruct the selected database value. If
  it is unavailable, EACL returns a typed snapshot-unavailable or
  cursor-expired error rather than silently using a newer value.
- *No negation operator:* EACL only supports Union (`+`) permission operators, not `-` negation, e.g.
  - `permission admin = owner + shared_admin` is valid,
  - but `permission admin = owner - banned_member` is not (note the `-` Negation operator).
  - You can work around this limitation by doing a negation in your application logic, e.g. `(and (not (eacl/can? acl ...) (eacl/can? acl ...)))`, but it is not free. Caching may reduce the cost when the component checks are reused.
- Arrow syntax is limited to one level of nesting, e.g.
  - `permission arrow = relation->via-permission` is supported,
  - but `permission arrow = relation->subrelation->permission` is not. To implement this would require anonymous shadow relations. May require schema changes.
- You need to specify a `Permission` for each relation in a sum-type permission. In future this can be shortened.
- `subject.relation` is not currently supported. It's useful for group memberships.
- *Expansion is structural, not a membership proof:* permission trees preserve
  relation, permission, union, and arrow boundaries. Use `can?` for an
  authorization decision.
- *Cache coherence requires EACL authorization writers:* Bypassing EACL for
  schema, relationship, permissioned identity, or deletion mutations can
  leave cached answers stale. Stop affected traffic, repair the data, and
  expire every affected client before resuming.
- *Deleting entities:* Native entity retraction does not remove the
  relationship stored at the other endpoint. Delete relationships first with
  `delete-object!`, or use the optional safe-retraction function — see
  [Deleting a permissioned entity](#deleting-a-permissioned-entity).
- *Recursive permissions have safety limits:* use `:count-limit` to bound
  counts, and raise recursive traversal limits only after load testing. If a
  cached continuation is unavailable, EACL may replay earlier traversal work
  to continue a cursor.
- *Return order:* EACL makes no global, lexical, or cross-backend ordering
  promise. For a fixed query and cursor-pinned snapshot, acyclic lookups use
  backend internal-ID order, relationship reads use backend tuple-index order,
  and recursive lookups use deterministic traversal order. This stability is
  sufficient for a cursor walk with no movement or duplicates; sort by a
  domain key after reading if presentation order matters. SpiceDB likewise
  returns results in discovery or schema order.

## Differences from SpiceDB

EACL follows SpiceDB's schema vocabulary and shared authorization semantics,
but it is not a byte-for-byte or operational clone:

- Result order is backend-defined. Compare lookup and relationship results as
  sets unless your application explicitly sorts them; never compare EACL and
  SpiceDB page membership or cursor bytes.
- EACL cursors pin the database snapshot selected by page one. Later writes do
  not appear midway through an EACL cursor walk. In verified SpiceDB v1.56.0
  behavior, native minimize-latency lookup cursors can admit later writes;
  EACL deliberately does not reproduce that behavior.
- Omitted consistency means `:minimize-latency`. For EACL's current Peer
  backend that is the current basis visible to the local Peer. SpiceDB may use
  an optimized cached revision, so freshness can differ. Use each backend's
  own causal token with `at-least-as-fresh` or `at-exact-snapshot` when the
  distinction matters; tokens and cursors are backend-local.
- EACL provides `count-resources`, `count-subjects`, a controllable EACL result
  cache, and atomic logical `delete-object!` behavior on supported situated
  backends. These do not have direct SpiceDB API equivalents.
- EACL currently supports a smaller schema subset: unions and its documented
  arrow forms, but not caveats, wildcard subjects, expiration, intersections,
  exclusions, or subject relations.
- A relationship filter containing `:subject/id` must also contain
  `:subject/type`. This fails closed instead of interpreting one external ID
  across every subject definition.

## Funding

Some of this open-source work was generously funded by my former employer, [CloudAfrica](https://cloudafrica.net/).

# Licence

- EACL is licensed under the Eclipse Public License v2.0.
