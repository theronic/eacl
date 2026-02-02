# 🦅 **EACL**: Enterprise Access ControL

[EACL](https://github.com/theronic/eacl) is a _situated_ [ReBAC](https://en.wikipedia.org/wiki/Relationship-based_access_control) authorization library based on [SpiceDB](https://authzed.com/spicedb), built in Clojure and backed by Datomic.

Situated here means that your permission data lives next to your Datomic entities. This has several benefits which are described below.

EACL is [open-source](https://github.com/theronic/eacl) and is used at [CloudAfrica](https://cloudafrica.net/).

## Is it any good?

Yes.

## What is EACL?

- EACL implements the core functionality of SpiceDB in Clojure, backed by Datomic.
- EACL situates permission data next to your Datomic entities, which avoids an external network hop and complex syncing to SpiceDB, while retaining optionality to sync Relationships to SpiceDB 1-for-1 if you require additional scale or consistency semantics.
- EACL exposes an `IAuthorization` protocol that closely resembles SpiceDB's gRPC protocol:
  - `eacl/can?` to check if a subject has a given permission on a resource,
  - `eacl/lookup-resources` to enumerate resources a given subject can access,
  - `eacl/lookup-subjects` to enumerate subjects that can access a given resource.
  - `eacl/write-relationships!` to create, update or delete relationships.
  - `eacl/write-schema!` to define your authorization schema using SpiceDB's schema DSL.
  - `eacl/read-schema` to retrieve the current schema.
- EACL implements efficient cursor-based permission graph traversal for enumeration via `lookup-resources` & `lookup-subjects`. All results are returned in the order they are stored in at-rest (internal Datomic eids).
- EACL has some limitations compared to SpiceDB: notably, EACL only support Union (`+`, i.e. OR-logic) permission logic. Negation can be emulated with multiple permission checks.
- EACL is fast. Depending on the graph complexity of your permission schema, EACL should be good for at least 1M permissioned resources. There are low-hanging fruit to 10x performance even further, specifically via permission path caching and more sophisticated cursors.

# Rationale

I spent the better half of 2024 integrating [SpiceDB](https://authzed.com/spicedb) at [CloudAfrica](https://cloudafrica.net/).

- Keeping permission data synced to an external authorization system is non-trivial, especially if there is an impedance mismatch between your data model and SpiceDB's permission schema (3-tuple Relationships).
- SpiceDB write operations return _ZedToken_ strings e.g. `WriteRelationships`, which you need to store on entities in your database to fully leverage the [SpiceDB cache](https://authzed.com/docs/spicedb/concepts/consistency#consistency-in-spicedb) via `at_least_as_fresh` and `at_exact_snapshot` consistency semantics.
- If you need to hit the DB (or cache) anyway to query Spice, you might as well situate your permission data in Datomic and avoid an external network hop as well as complex diffing & syncing operations – this is the promise of EACL.

Worried about load? You can horizontally scale Datomic Peers dedicated to authorization and even expose the EACL API to external consumers.

# What is EACL good for?

- EACL is suitable for Clojure & Datomic Pro and Datomic Cloud applications.
- EACL is especially suited to [Electric Clojure](https://electric.hyperfiddle.net/) applications backed by Datomic Pro, because it allows you to render dynamic permissioned menus in real-time. EACL uses low-level Datom access via `d/index-range` & `d/seek-datoms` to yield sub-millisecond results with cursor-based pagination.
- EACL performance should scale to at least 1M permissioned resources with a goal of 10M resources. If you need more scale & billions of queries, EACL's data model allows you to migrate to SpiceDB with real-time incremental syncing by tailing to the Datomic Pro transactor and monitoring EACL attributes.
- EACL query complexity scales with the size of your permission schema and the log-size of Relationship indices.

> [!WARNING]
> Even though EACL is used in production at CloudAfrica, it is under *active* development.
> I try hard not to introduce breaking changes, but if data structures change, the major version will increment.
> v6 is the current version of EACL. Releases are not tagged yet, so pin the Git SHA.

# What is SpiceDB?

- SpiceDB is a scalable ReBAC based authorization system based on Zanzibar, Google's globally-consistent authorization system.
- SpiceDB has a text-based schema DSL that defines a graph of potential Relationships between subjects & resources, called Relations.
- Relationships are 3-tuples of `[subject relation resource]`, i.e. some `<subject>` has `<relation>` to `<resource>`, e.g. `user:joe` is the `:owner` of `account-1`.
- Permissions are granted to subjects based on Relationships to other resources, but there can be multiple hops along that graph.

# Why was EACL built? The Problem with External AuthZ:

The hard part of any external authorization system is modelling & synchronization. Here's what you need to do to integrate with SpiceDB:
1. Design your SpiceDB permission schema, which hopefully matches your data model. In my experience legacy Datomic permissions models rarely matches SpiceDB's model.
2. Write a bunch of Datalog queries to compute a set of all Relationship 3-tuples of `[subject relation resource]`, e.g. `[user1 :owner account1]` & `[product2 :account account2]` and so on. As your data set grows, these queries slow down.
3. Wrap the SpiceDB Java gRPC or HTTP protocol in Clojure.
4. Write your Relationships to SpiceDB.
  - Note that to leverage the SpiceDB cache, you need to store the ZedToken string returned by SpiceDB write operations on relevant entities, i.e. you have to hit the DB anyway.
6. As your data changes, you need to continually maintain your Relationships in SpiceDB by creating, updating or deleting relationships or risk exposing unauthorized data.
7. But which Relationships to delete? It is costly to drop and re-insert all Relationships every time, causing lots of I/O to an external system, plus it kills the cache.
8. So now have to figure out which Relationships changed and then sync them _incrementally_.
9. If your data model matches the schema in Spice exactly, it's straightforward to know out which Relationships changed.
10. However, if there is any impedance mismatch between your data model and SpiceDB, it becomes a hard diffing & batch syncing problem.

I followed exactly this process at CA and it was hard to get right. Plus, because Spice is an external system, you need to deal with failures and retries and eventual consistency.

## The Solution

What if we modelled our Relationships directly in Datomic? I realized that if Relationship 3-tuples were stored directly in Datomic, you can monitor the Datomic transactor queue via `d/tx-report-queue` for any `:db/add` or `:db/retract` to Relationship entities and immediately create, delete or touch Relationships in external SpiceDB – a lot of syncing problems go away.

Once you model your Spice Relationships directly in Datomic, you might as well model the schema too...

If you already have schema & Relationships, why not just traverse the permission graph in Datomic? That way you don't need to sync at all – all our problems go away.

If you could make such an implementation fast enough, what do you get?
- you have one less external system to deal with & deploy
- you avoid a network hop to an external system, which means it can actually be faster than Spice, up to a certain scale
- you don't need to deal with gRPC in Java / Clojure
- you retain the optionality to sync your Relationships 1-for-1 to SpiceDB in the future
- you can delete any complex diffing & syncing mechanisms that get slower as your source entities grows

And that is exactly what EACL does.

## The Rest is largely lifted from the [README](https://github.com/theronic/eacl/blob/main/README.md):

In your database, you probably have `:db.type/ref` attributes like:
- `:product/account` – which account this product belongs.
- `:account/owner` – who owns this account
- `:product/category` – categories this product belongs so
- `:product/viewers` if you want to share certain resources with other users.

You can write Datalog queries to query via product -> account -> user, but these will slow down as the dataset grows. As soon as you introduce inheritance or shared viewers, e.g. product -> account -> viewers, you'll end up writing lots of Datalog queries that need to be maintained.

Syncing permissions to SpiceDB is a diffing problem if your data model does not model Spice Relations & Relationships, e.g. if you have attributes in Datomic

EACL (Enterprise Access ControL) is an embedded authorization library that lives next to your data in Datomic and avoids an external network hop. EACL is suitable for small-to-medium scale, while giving you the option to migrate to SpiceDB in future when you need more scale and consistency semantics.

EACL implements the SpiceDB gRPC API as an idiomatic Clojure protocol (`IAuthorization`), using Datomic as a backing graph store. So you can add sophisticated authorization to your Clojure project on day one and migrate to SpiceDB later.

Internally, EACL recursively traverses the permission graph using direct index-based calls (via `datomic.api/index-range`) to efficiently answer `CheckPermission`, `LookupSubjects` and `LookupResources` queries.

The goal for EACL is to provide best-in-class authorization for Clojure & Datomic applications with <10M entities. It is especially suited to Electric Clojure[^3]. EACL has been open-sourced under the AGPL, but we are likely to relicense it under a more permissive licence. EACL is used at CloudAfrica, a regional cloud host based in South Africa.

## Project Goals

- Best-in-class ReBAC authorization for Clojure/Datomic products with <10M Datomic entities.
- Clean migration path to SpiceDB once you need consistency semantics with heavily optimized cache.
- Retain gRPC API compatibility with 1-for-1 Relationship syncing.

EACL can answer the following permission questions by querying Datomic:

1. **Check Permission:** "Does `<subject>` have `<permission>` on `<resource>`?"
2. **Enumerate Subjects:** "Which `<subjects>` have `<permission>` on `<resource>`?"
3. **Enumerate Resources:** "Which `<resources>` does `<subject>` have `<permission>` for?"

## Authentication vs Authorization

- Authentication or **AuthN** means, "Who are you?"
- Authorization or **AuthZ** means "What can `<subject>` do?" i.e. permissions.

EACL leverages recursive Datomic graph queries and direct index access to support ReBAC authorization situated next to your data.

## Why EACL?

Embedded AuthZ offers some advantages for typical use-cases:

1. Situated permissions avoids network I/O to an external AuthZ system, which should be faster at small-to-medium scale.
2. Accurate ReBAC model allows 1-for-1 syncing of Relationships to SpiceDB without complex diffing, in real-time.
3. Queries are fully consistent until you need the consistency semantics of SpiceDB.

## ReBAC: Relationship-based Access Control

In a ReBAC system like EACL, _Subjects_ & _Resources_ are related via _Relationships_.

A `Relationship` is just a 3-tuple of `[subject relation resource]`, e.g.
- `[user1 :owner account1]` means subject `user1` is the `:owner` of resource `account1`, and
- `[account1 :account product1]` means subject `account1` is the `:account` for resource `product1`.

To create relationships, first define your schema using `eacl/write-schema!`:

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

In SpiceDB schema DSL, `+` means union (OR-logic). EACL does not support negation (`-`) or intersection (`&`) yet.

## EACL API

The `IAuthorization` protocol in [src/eacl/core.clj](src/eacl/core.clj) defines an idiomatic Clojure interface that maps to and extends the [SpiceDB gRPC API](https://buf.build/authzed/api/docs/main:authzed.api.v1):

- `(eacl/can? client subject permission resource) => true | false`
- `(eacl/lookup-subjects client filters) => {:data [subjects...], cursor 'next-cursor}`
- `(eacl/lookup-resources client filters) => {:data [resources...], :cursor 'next-cursor}`.
- `(eacl/count-resources client filters) => {:keys [count limit cursor]}` supports limit & cursor for iterated counting. Use sparingly with `:limit -1` for all results.
- `(eacl/count-subjects client filters) => {:keys [count limit cursor]}` supports limit & cursor for iterated counting. Use sparingly with `:limit -1` for all results.
- `(eacl/read-relationships client filters) => [relationships...]`
- `(eacl/write-relationships! client updates) => {:zed/token 'db-basis}`,
  - where `updates` is just a coll of `[operation relationship]` where `operation` is one of `:create`, `:touch` or `:delete`.
- `(eacl/create-relationships! client relationships)` simply calls write-relationships! with `:create` operation.
- `(eacl/delete-relationships! client relationships)` simply calls write-relationships! with `:delete` operation.
- `(eacl/write-schema! client schema-string)` parses a SpiceDB schema DSL string, validates it, computes deltas against existing schema, checks for orphaned relationships, and transacts changes atomically.
- `(eacl/read-schema client)` returns the current schema as a map of `{:relations [...] :permissions [...]}`.
- `(eacl/expand-permission-tree client filters)` is not impl. yet.

The primary API call is `can?`, e.g.

```clojure
(eacl/can? client subject permission resource)
=> true | false
```

The other primary API call is `lookup-resources`, e.g.

```clojure
(def page1
  (eacl/lookup-resources client
    {:subject       (->user "alice")
     :permission    :view
     :resource/type :server
     :limit         2 ; defaults to 1000.
     :cursor        nil})) ; pass nil for 1st page.
page1
=> {:cursor 'next-cursor
    :data [{:type :server :id "server-1"}
           {:type :server :id "server-2"}]}
```

To query the next page, simply pass the cursor from page1 into the next query:  

```clojure
(eacl/lookup-resources client
  {:subject       (->user "alice")
   :permission    :view
   :resource/type :server
   :limit         3
   :cursor        (:cursor page1)}) ; pass nil for 1st page.
=> {:cursor 'next-cursor
    :data [{:type :server :id "server-3"}
           {:type :server :id "server-4"}
           {:type :server :id "server-5"}]}
```

The return order of resources from `lookup-resources` is stable and sorted by internal resource ID. Future enhancements may enable a sort key.

## Quickstart

The following example is contained in [eacl-example](https://github.com/theronic/eacl-example).

Add the EACL dependency to your `deps.edn` file:

```clojure
{:deps {cloudafrica/eacl {:git/url "git@github.com:cloudafrica/eacl.git" 
                          :git/sha "3c4d93a58c49a90c018f72ff99aed2b7ed790831"}}}
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
@(d/transact conn schema/v6-schema)

;  Make an EACL client that satisfies the `IAuthorization` protocol:
(def acl (eacl.datomic.core/make-client conn
           ; optional config:
           {:object-id->ident (fn [obj-id] [:eacl/id obj-id])
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
   :limit         1000
   :cursor        nil})
; => {:data [{:type :product, :id "product-1"}]
;     :cursor 'cursor}
```

## Data Structures

EACL models two core concepts: Schema & Relationship.

1. _Schema_ consists of `Relations` and `Permissions`:
   - `Relation` defines how a `<subject>` & `<resource>` can be related via a `Relationship`.
   - `Permission` defines which permissions are granted to a subject via a `Relationship`.
     - Permissions can be _Direct Permissions_ or indirect _Arrow Permissions_. 
2. A _Relationship_ defines how a `<subject>` and `<resource>` are related via some relation, e.g.
   - `(->user "alice")` is the `:owner` of `(->account "acme")`, where
     - `(->user "alice")` is the Subject,
     - `:owner` is the name of the relation,
     - and `(->account "acme")` is the Resource,
   - In EACL this is expressed as `(->Relationship (->user "alice") :owner (->account "acme"))`, i.e. `(Relationship subject relation resource)`
   - Subjects & Resources have a `type` and a unique `id`, just a map of `{:keys [type id]}`, e.g. `{:type :user, :id "user-1"}`, or `(->user "user-1")` for short.

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

`write-schema!` validates your schema and provides informative error messages:
- **Reference validation**: Ensures all relations and permissions reference valid definitions
- **Orphan protection**: Prevents deleting relations that have existing relationships
- **Unsupported feature detection**: Rejects SpiceDB features not yet supported by EACL (see [Limitations](#limitations-deficiencies--gotchas))

### Schema Updates

When you call `write-schema!` with a modified schema, EACL:
1. Parses the new schema
2. Computes deltas (additions/retractions) against existing schema
3. Validates retractions won't orphan existing relationships
4. Transacts changes atomically

### Modelling Relations

Let's model this SpiceDB schema:

```clojure
(eacl/write-schema! acl
  "definition user {}

   definition account {
     relation owner: user
     permission update = owner
   }")
```

We define two resource types, `user` & `account`, where a `user` subject can be an `owner` of an `account` resource.

At this point, all permissions checks will return false because there are no Relationships defined:

```clojure
(eacl/can? acl (->user "alice") :update (->account "acme"))
=> false
```

### Creating Relationships

Before we can do some permission checks, let's define a Relationship between a `user` subject and an `account` resource:
- `(->Relationship subject relation resource)`
- e.g. `(eacl/->Relationship (->user "alice") :owner (->account "acme"))`

We can create Relationships in EACL via `create-relationships!` or `write-relationships!`:
```clojure
(eacl/create-relationships! acl [(eacl/->Relationship (->user "alice") :owner (->account "acme"))])
```

### Permission Checks 

Now that we have a Relationship between a user and an account, we can call `eacl/can?` to check if a user has the permission to update the ACME account:
```clojure
(eacl/can? acl (->user "alice") :update (->account "acme"))
=> true
```
However, Bob cannot, because he is not an `:owner` of the ACME account:
```clojure
(eacl/can? acl (->user "bob") :update (->account "acme"))
=> false
```

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

Here, `permission edit = account->admin` states that subjects are granted the `edit` permission _if, and only if_ they have the `admin` permission on the related account for that product.

Given that:
 1. `(->user "alice")` is the `:owner` of `(->account "acme")`, and
 2. `(->account "acme")` is the `:account` for `(->product "SKU-123")`
EACL can traverse the permission graph from user->account->product to calculate that Alice can `edit` product `SKU-123`.

Now you can use `can?` to check those arrow permissions:
```clojure
(eacl/can? acl (->user "alice") :edit (->product "SKU-123"))
=> true ; if Alice is an :owner of the Account for that Product.

(eacl/can? acl (->user "bob") :edit (->product "SKU-123"))
=> false ; if Bob is not the :owner of the Account for that Product.
```

Internally, EACL models Relations, Permissions and Relationships as Datomic entities, along with several tuple indices for efficient querying.

We have an implementation for the gRPC API that is not open-sourced at this time.

## EACL ID Configuration

SpiceDB uses strings for subject & resource IDs, whereas EACL internally uses Datomic entity IDs.

Internal Datomic eids are not guaranteed to be stable after a DB rebuild, so EACL lets you configure how IDs should be coerced from internal to external & vice versa, so e.g. you can configure EACL to return a UUID or string in your database. Note that this attribute should have `:db/unique :db.unique/identity` set: 

```clojure
(def acl (eacl.datomic.core/make-client conn
           {:entid->object-id (fn [db eid] (:your/id (d/entity db eid)))
            :object-id->ident (fn [obj-id] [:your/id obj-id])}))
```
The default options are to use `:eacl/id`, but if you want to use internal Datomic eids (e.g. if you don't expose anything to the outside world), you can pass the following options:
```clojure
(def acl (eacl.datomic.core/make-client conn
           {:entid->object-id (fn [_db eid] eid)
            :object-id->ident (fn [obj-id] obj-id)}))
```

`eacl.core/spice-object` accepts `type`, `id` and optionally `subject_relation`, and returns a SpiceObject.

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

Now you can transact relationships:

```clojure
(eacl/create-relationships! acl
  [(eacl/->Relationship (->user "user1") :owner (->account "account1"))
   (eacl/->Relationship (->platform "my-platform") :platform (->account "account1"))
   (eacl/->Relationship (->user "admin1") :super_admin (->platform "my-platform"))])
```

## Limitations, Deficiencies & Gotchas:

- No consistency semantics because all EACL queries are fully-consistent. Use SpiceDB if you need consistency semantics enabled by ZedTokens ala Zookies. SpiceDB is heavily optimised to maintain a consistent cache.
- EACL makes no strong performance claims. It should be good for <1M Datomic entities. Goal is 10M entities.
- Arrow syntax is limited to one level of nesting, e.g.
  - Supported: `permission arrow = relation->via-permission` is valid
  - Not supported: `permission arrow = relation->subrelation->permission` is not valid (yet).
- Only union permissions are supported, not negating permissions:
  - Supported: `permission admin = owner + shared_admin`
  - Not supported: `permission admin = owner - shared_member` (note the minus). Exclusion types require complex caching to avoid multiple `can?` queries.
- You need to specify a `Permission` for each relation in a sum-type permission. In future this can be shortened.
- `subject.relation` is not currently supported. It's useful for group memberships.
- `expand-permission-tree` is not implemented yet.
- No cycle detection at present, but a high-priority to implement.

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

## Funding

This open-source work was generously funded by my employer, [CloudAfrica](https://cloudafrica.net/), a Clojure shop. We occasionally hire Clojure & Datomic experts.

# Licence

- EACL switched from BSL to an Affero GPL licence on 2025-05-27. However, we are considering re-licensing under a more permissive open-source license.
