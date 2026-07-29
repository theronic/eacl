# 🦅 **EACL**: Enterprise Access ControL

EACL is a _situated_ [ReBAC](https://en.wikipedia.org/wiki/Relationship-based_access_control) authorization library based on [SpiceDB](https://authzed.com/spicedb), built in Clojure and backed by Datomic.

_Situated_ here means that your permission data lives _next to_ your application data in Datomic, which has some benefits:
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
4. Queries are fully consistent. If you need consistency semantics like `at_least_as_fresh`, use SpiceDB.
5. EACL is fast. You may be tempted to roll your own ReBAC system using recursive Datomic child rules, but you will find the eager Datalog engine too slow and unable to handle all the grounding cases. The first version of EACL was implemented with Datalog rules, but it was simply too slow and materialized all intermediate results. Correct bidirectional cursor-pagination is also non-trivial, because parallel paths through the permission graph can yield duplicate resources. EACL does this for you with good performance.

## Performance

- EACL traverses acyclic ReBAC paths via low-level Datomic `d/index-range`, `d/seek-datoms` & `d/rseek-datoms` calls. Recursive permission closures use deterministic traversal order with request-local dedupe, avoiding both Datomic recursive Datalog materialization and persisted grant caches. Acyclic lookup results are returned in Datomic eid order; recursive lookup results are returned in traversal order.
  - I have investigated implementing custom Sort Keys, but they are not currently feasible without adding a lot of storage & write costs.
- EACL is fast, but makes no strong performance claims at this time. For typical workloads, EACL should be as fast as, or faster than, SpiceDB. EACL is not meant for hyperscalers.
- EACL is internally benchmarked against ~800k permissioned resources with good latency (5-30ms per query). You can scale Datomic Peers horizontally and dedicate peers to EACL as needed.
- The performance goal for EACL is to handle 10M permissioned entities with real-time performance.
- EACL does not support all SpiceDB features. Please refer to the [limitations section](#limitations-deficiencies--gotchas) to decide if EACL is right for you.
- Presently, EACL has _no result cache_ because graph traversal is fast enough over Datomic's aggressive datom caching even for ~1M permissioned resources. A cache is planned and once it lands, should bring query latency down to ~1-2ms per API call, even for large pages.
- EACL does cache resolved *permission paths*. Cache entries are scoped by an exact fingerprint of the EACL definition datoms visible in the `db` value you query, so two `db` values share a cached path set only when their schemas are identical — however the schema changed. That means `write-schema!`, a raw `d/transact` of `Relation`/`Permission` maps, an excision, a `d/filter` that hides definitions and a speculative `d/with` database each resolve against their own schema, and none of them can publish paths under another's key. There is nothing to invalidate manually.
  - The fingerprint is `O(number of definitions)` — never `O(number of relationships)` — and is memoised on the `db` value itself, so it is computed once per `db` value you query and never again. An unrelated `d/transact` yields a new `db` value with the *same* fingerprint, so relationship write load re-verifies the (small) definition index once and every permission-path entry still hits: no path is ever recomputed unless the definitions actually changed (issue #74).
  - The cost that remains is one definition-index scan per new `db` value. If your database advances between essentially every `can?` — and your schema is very large — that scan is on the hot path. It is a few microseconds for a normal schema; see the `permission-check-benchmark` in `test/eacl/bench/pagination_test.clj` for warm and cold numbers.
  - `:eacl/schema-version` is still stamped by `write-schema!`, but it is now informational (a readable schema generation, including under `d/as-of`) rather than the cache key.
- Acyclic lookup cursors retain a per-permission-path intermediate frontier. Later pages resume each arrow path at the earliest intermediate that can still contribute, and permanently skip paths exhausted in that scan direction. This prevents deep pages from repeatedly scanning intermediates that were already proved irrelevant.
- Acyclic lookup performance should scale roughly with permission graph complexity * `O(logN)` for `N` resources in terminal resource Relationship indices. Recursive lookup pages are deterministic traversal-order pages with request-local dedupe; they avoid materializing the full closure, but late pages replay the traversal prefix instead of seeking directly to a global sort key. Subjects are typically sparse compared to resources, i.e. 1k users will have access to 1M resources – rarely the other way around.

*Note* that EACL v7.3 page tokens are stable: after the first page, `:after` and `:before` continue against the same Datomic basis. If your DB changes while a UI is paging, refresh from the first page to see the newest view. You can pass a stable `db` basis and shave off a few milliseconds by calling the internals in `eacl.datomic.impl.indexed` directly – these functions take `db` as an argument directly instead of `conn`. If you do this, you will need to coerce internal Datomic eids to/from your desired external IDs yourself, and you must hold **one** `db` value for the whole paginated walk: internal cursor maps embed per-path frontiers (including `:exhausted` markers) that are only valid against the basis that minted them. Reusing a cursor against a newer `(d/db conn)` can silently skip results written after the first page — the token layer prevents this by pinning `d/as-of` for you.

Public `eacl3_` cursors are string-safe AES-GCM envelopes. Authentication is required so a caller cannot alter a result boundary, query binding, basis, or per-path frontier. Encryption is not required for pagination correctness, but it prevents internal Datomic eids and basis metadata from leaking through an otherwise merely Base64-encoded token. Token cryptography runs once when a page cursor is encoded or decoded; it is not part of each relationship-index traversal.

## Project Status

> [!WARNING]
> EACL is under active development.
> I try hard not to introduce breaking changes, but if data structures change, the major version will increment.
> v7.3 is the current development version of EACL. It retains the v7.2 pagination API and recursive traversal engine, and adds direction-scoped cursor frontiers for deep acyclic lookup pages. Releases are not tagged yet, so pin the Git SHA.
> Upgrading from v6? The relationship storage model changed — follow the [v6 → v7 migration guide](docs/migration-v6-to-v7.md).

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

The `IAuthorization` protocol in [src/eacl/core.clj](src/eacl/core.clj) defines an idiomatic Clojure interface that maps to and extends the [SpiceDB gRPC API](https://buf.build/authzed/api/docs/main:authzed.api.v1):

### Queries

- `(eacl/can? acl subject permission resource) => true | false`
- `(eacl/lookup-subjects acl filters) => {:data [subjects...] :page-info {...}}`
- `(eacl/lookup-resources acl filters) => {:data [resources...] :page-info {...}}`
- `(eacl/count-resources acl filters) => {:keys [count limit]}` counts the full result set.

### Relationship Maintenance

- `(eacl/read-relationships acl filters) => {:data [relationships...] :page-info {...}}`
- `(eacl/write-relationships! acl updates) => {:zed/token 'db-basis}`,
  - where `updates` is a collection of `[operation relationship]`, and `operation` is one of `:create`, `:touch` or `:delete`.
- `(eacl/create-relationships! acl relationships)` simply calls `write-relationships!` with `:create` operation.
- `(eacl/delete-relationships! acl relationships)` simply calls `write-relationships!` with `:delete` operation.
- `(eacl/delete-object! acl object) => {:zed/token 'db-basis, :retracted-datoms n}` removes every relationship touching `object`, in both directions. Call this before retracting a permissioned entity — see [Deleting a permissioned entity](#deleting-a-permissioned-entity).

All list APIs use the v7.3 pagination contract:

- Forward: pass `:first` and optionally `:after`.
- Backward: pass `:last` and optionally `:before`.
- Responses include `:page-info` with `:start-cursor`, `:end-cursor`, `:has-next-page?`, and `:has-previous-page?`.
- `:cursor` and `:limit` are no longer supported for list pagination.
- Acyclic lookup cursors paginate in Datomic eid order. Recursive lookup cursors paginate in deterministic traversal order.

### Schema Maintenance

- `(eacl/write-schema! acl schema-string)` parses a SpiceDB schema DSL string, validates it, computes deltas against existing schema, checks for orphaned relationships, and transacts changes atomically.
- `(eacl/read-schema acl)` returns the current schema as a map of `{:relations [...] :permissions [...]}`.
- `(eacl/expand-permission-tree acl filters)` is not impl. yet. It is a low priority to implement.

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
    :page-info {:start-cursor "eacl3_..."
                :end-cursor "eacl3_..."
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
    :page-info {:start-cursor "eacl3_..."
                :end-cursor "eacl3_..."
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

## Quickstart

The following example is contained in [eacl-example](https://github.com/theronic/eacl-example).

Add the EACL dependency to your `deps.edn` file:

```clojure
{:deps {theronic/eacl {:git/url "git@github.com:theronic/eacl.git" 
                       :git/sha "f8c3c1cf67646236ca538942120a03edde40fee7"}}}
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
;     :page-info {:start-cursor "eacl3_..."
;                 :end-cursor "eacl3_..."
;                 :has-next-page? false
;                 :has-previous-page? false}}
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

`:recursive-traversal-limits` raises the safety ceilings on recursive permission traversal (see [Limitations](#limitations-deficiencies--gotchas)):

```clojure
(def acl (eacl.datomic.core/make-client conn
           {:recursive-traversal-limits {:max-derived-grants 1000000}}))
```

Keys you omit keep their defaults (`eacl.datomic.impl.indexed/default-recursive-traversal-limits`), so a partial override cannot silently disable the limits it does not mention.

For multi-peer deployments, configure a shared page-token key so `eacl3_...` cursors survive restarts and can be decoded by every peer:

```clojure
(def acl (eacl.datomic.core/make-client conn
           {:page-token-key "32+ bytes of shared secret key material"
            :page-token-kid :prod-2026-05}))
```

### Unknown object IDs

EACL follows SpiceDB semantics for object IDs that don't resolve to an entity:

- **Reads** (`can?`, `lookup-resources`, `lookup-subjects`, `count-resources`, `read-relationships`) treat unknown IDs as matching nothing: `can?` returns `false`, lookups and reads return empty pages.
- **Writes** (`write-relationships!` and friends) throw `ex-info {:type :eacl/unknown-object, :object {:type … :id …}}` — a relationship to a nonexistent entity is unsatisfiable, and failing loudly beats minting ghost entities or raw Datomic errors.

### Deleting a permissioned entity

> [!IMPORTANT]
> `:db.fn/retractEntity` does **not** remove an entity's EACL relationships. Call `eacl/delete-object!` first.

A v7 relationship is two datoms living on two different entities, each naming its peer *inside a tuple value*:

```
[<subject-eid>  :eacl.v7.relationship/subject-type+relation+resource-type+resource  [subject-type relation-eid resource-type <resource-eid>]]
[<resource-eid> :eacl.v7.relationship/resource-type+relation+subject-type+subject   [resource-type relation-eid subject-type <subject-eid>]]
```

Datomic's `:db.fn/retractEntity` follows `:db.type/ref` *attributes*; it does not follow ref-typed *components of a heterogeneous tuple* (and a heterogeneous tuple cannot be `:db/isComponent`). So retracting a permissioned entity directly removes only the half stored on that entity and leaves the peer's half behind, where it keeps answering queries — a deleted resource still passes `can?`, a deleted subject still appears in `lookup-subjects` — and the survivor is unreachable through `write-relationships!`, because resolving either endpoint now throws `:eacl/unknown-object`.

Delete relationships first, then the entity:

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

To find and repair relationships orphaned by earlier bare `retractEntity` calls (an offline maintenance pass — it scans both relationship indexes):

```clojure
(take 10 (impl/orphaned-relationship-halves (d/db conn)))     ; inspect
@(d/transact conn (impl/tx-retract-orphaned-relationships (d/db conn)))
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

### Advanced: Programmatic Schema (Optional)

For advanced use cases, you can also define schema programmatically using the internal `Relation` and `Permission` functions:

> Programmatic definition changes take effect immediately: the permission-path cache is scoped by a fingerprint of the definition datoms themselves, not by `write-schema!`'s version stamp (see the caching note above). You still lose `write-schema!`'s parse-time validation, reference checking and orphaned-relationship guard, so prefer `write-schema!` where you can.

```clojure
(require '[eacl.datomic.impl :refer [Relation Permission]])

@(d/transact conn
  [(Relation :account :owner :user)
   (Permission :account :admin {:relation :owner})
   (Relation :server :account :account)
   (Permission :server :admin {:arrow :account :permission :admin})])
```

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

- *No consistency semantics:* all EACL queries are `fully_consistent`.
  - If you need consistency semantics, use SpiceDB. SpiceDB is heavily optimised to maintain a consistent cache across the graph.
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
- *No cache:* EACL does not presently have a result cache, because Datomic Peers cache datoms aggressively and queries so far are fast enough. A cache is planned. (Resolved *permission paths* are cached — see [Performance](#performance).)
- *Deleting entities:* `:db.fn/retractEntity` does not remove an entity's relationships. Call `eacl/delete-object!` first — see [Deleting a permissioned entity](#deleting-a-permissioned-entity).
- *Recursive permissions do not paginate cheaply:* a permission that transitively depends on itself (`permission read = reader + parent->read`) is evaluated in traversal order, and each page resumes by replaying the traversal prefix and discarding results up to the cursor. Work per page therefore grows with the page's offset — enumerating `N` results is `O(N²/page-size)` — and a single page is capped by `:max-derived-grants` (default 100 000), so a recursive permission with a very large grant set starts failing on *deep* pages while page 1 still succeeds. Raise the ceiling with `make-client`'s `:recursive-traversal-limits` (it is a memory bound), or model the permission acyclically. Acyclic permissions have no such limit: they resume from per-path cursor frontiers and cost `O(log N)` per page.
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

Migrate with the batteries-included, idempotent [`eacl.migrations.v6-to-v7`](src/eacl/migrations/v6_to_v7.clj) namespace:

```clojure
(require '[eacl.migrations.v6-to-v7 :as migrations])
(migrations/migrate! conn {:schema "definition user {} ..."})  ; re-asserts your schema via write-schema!
```

or opt into automatic migration at client construction:

```clojure
(eacl.datomic.core/make-client conn {:auto-migrate-v6 {:schema "definition user {} ..."}})
```

The migration is additive and rollback-friendly (v6 data is kept until you explicitly retract it) and end-to-end tested in [test/eacl/migrations/v6_to_v7_test.clj](test/eacl/migrations/v6_to_v7_test.clj). For the full sequence — write-pause window, verification, soak, cleanup, rollback — follow the [v6 → v7 migration guide](docs/migration-v6-to-v7.md).

## Funding

Some of this open-source work was generously funded by my former employer, [CloudAfrica](https://cloudafrica.net/).

# Licence

- EACL is licensed under the Eclipse Public License v2.0 since 2026-03-05.
- EACL was initially licensed under BSL and later Affero GPL (on 2025-05-27), but is now licensed under EPL 2.0.
