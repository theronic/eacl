# 🦅 **EACL**: Enterprise Access ControL

EACL is a _situated_ [ReBAC](https://en.wikipedia.org/wiki/Relationship-based_access_control) authorization library based on [SpiceDB](https://authzed.com/spicedb), built in Clojure and backed by Datomic. EACL is used at [CloudAfrica](https://cloudafrica.net/).

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
5. EACL is fast. You may be tempted to roll your own ReBAC system using recursive Datomic child rules, but you will find the eager Datalog engine too slow and unable to handle all the grounding cases. The first version of EACL was implemented with Datalog rules, but it was simply too slow and materialized all intermediate results. Correct cursor-pagination is also non-trivial, because parallel paths through the permission graph can yield duplicate resources. EACL does this for you with good performance.

## Performance

- EACL recursively traverses the ReBAC permission graph via low-level Datomic `d/index-range` & `d/seek-datoms` calls to efficiently yield cursor-paginated resources in the order they are stored at-rest. Results are _always_ returned in the order they stored in at-rest, which are internal Datomic eids.
  - I have investigated implementing custom Sort Keys, but they are not currently feasible without adding a lot of storage & write costs.
- EACL is fast, but makes no strong performance claims at this time. For typical workloads, EACL should be as fast as, or faster than, SpiceDB. EACL is not meant for hyperscalers.
- EACL is internally benchmarked against ~800k permissioned resources with good latency (5-30ms per query). You can scale Datomic Peers horizontally and dedicate peers to EACL as needed.
- The performance goal for EACL is to handle 10M permissioned entities with real-time performance.
- EACL does not support all SpiceDB features. Please refer to the [limitations section](#limitations-deficiencies--gotchas) to decide if EACL is right for you.
- Presently, EACL has _no cache_ because graph traversal is fast enough over Datomic's aggressive datom caching even for ~1M permissioned resources. A cache is planned and once it lands, should bring query latency down to ~1-2ms per API call, even for large pages.
- Performance should scale roughly with permission graph complexity * `O(logN)` for `N` resources in terminal resource Relationship indices. Parallel paths through the graph that return the same resources will slow EACL down, because these resources need to be deduplicated in stable order. In a simple graph, performance should approach `O(logN)` for N permissioned resources. Subjects are typically sparse compared to resources, i.e. 1k users will have access to 1M resources – rarely the other way around.

*Note* that to retain future compatibility with the SpiceDB gRPC, the EACL Datomic client calls `(d/db conn)` on each API call, which means that if your DB changes inbetween EACL queries, you may see inconsistent results when cursor paginating. You can pass a stable `db` basis and shave off a few milliseconds by calling the internals in `eacl.datomic.impl.indexed` directly – these functions take `db` as an argument directly instead of `conn`. If you do this, you will need to coerce internal Datomic eids to/from your desired external IDs yourself.

## Project Status

> [!WARNING]
> Even though EACL is used in production at CloudAfrica, it is under *active* development.
> I try hard not to introduce breaking changes, but if data structures change, the major version will increment.
> v6 is the current version of EACL. Releases are not tagged yet, so pin the Git SHA.

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

To create a Relationship, first define valid `Relations` to describe how subjects & resources can be related, e.g.

```clojure
; definition account {
;   relation owner: user
;   relation viewer: user
; }
(Relation :account :owner :user)      ; an :account can have :owner(s) of type :user
(Relation :account :viewer :user)     ; an :account can have :viewer(s) of type :user

; definition product {
;   relation account: account
; }
(Relation :product :account :account) ; a :product has an :account of type :account.
```

Given that an `<account>` has an `:owner`, and a `<product>` can have an `:account`, we can now define a permission schema that grants the `:edit` permission to all owners of the account, and the `:view` permission to all viewers of the account:

```clojure
; definition account {
;   permission admin = owner
; }
(Permission :account :admin {:relation :owner})

; definition account {
;   permission edit = account->admin
; }
(Permission :account :edit {:arrow :account :permission :admin})

; definition  product {
;   permission view = admin + account->viewer
; }
(Permission :product :view {:arrow :account :permission :admin})
(Permission :product :view {:arrow :account :relation :viewer})
```

In EACL, multiple permission definitions with the same resource_type & name, but different permission spec, imply Unification or OR-logic. EACL does not support negation yet, only Unification.

## EACL API

The `IAuthorization` protocol in [src/eacl/core.clj](src/eacl/core.clj) defines an idiomatic Clojure interface that maps to and extends the [SpiceDB gRPC API](https://buf.build/authzed/api/docs/main:authzed.api.v1):

### Queries

- `(eacl/can? acl subject permission resource) => true | false`
- `(eacl/lookup-subjects acl filters) => {:data [subjects...], cursor 'next-cursor}`
- `(eacl/lookup-resources acl filters) => {:data [resources...], :cursor 'next-cursor}`.
- `(eacl/count-resources acl filters) => {:keys [count limit cursor]}` supports limit & cursor for iterative counting. Use sparingly with `:limit -1` for all results.
- `(eacl/count-subjects acl filters) => {:keys [count limit cursor]}` supports limit & cursor for iterative counting. Use sparingly with `:limit -1` for all results.

### Relationship Maintenance

- `(eacl/read-relationships acl filters) => [relationships...]`
- `(eacl/write-relationships! acl updates) => {:zed/token 'db-basis}`,
  - where `updates` is a collection of `[operation relationship]`, and `operation` is one of `:create`, `:touch` or `:delete`.
- `(eacl/create-relationships! acl relationships)` simply calls `write-relationships!` with `:create` operation.
- `(eacl/delete-relationships! acl relationships)` simply calls `write-relationships!` with `:delete` operation.

### Schema Maintenance

- `(eacl/write-schema! acl schema-string)` is not implemented yet because schema lives in Datomic. Use `d/transact` to write schema for now. This is a high priority to suport.
- `(eacl/read-schema acl) => "schema-string"` is not implemented because schema lives in Datomic. This is a high priority to support.
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
     :limit         2 ; defaults to 1000.
     :cursor        nil})) ; pass nil for 1st page.
page1
=> {:cursor 'next-cursor
    :data [{:type :server :id "server-1"}
           {:type :server :id "server-2"}]}
```

To query the next page, simply pass the `cursor` from page1 into the next query:  

```clojure
(eacl/lookup-resources acl
  {:subject       (->user "alice")
   :permission    :view
   :resource/type :server
   :limit         3
   :cursor        (:cursor page1)})
=> {:cursor 'next-cursor
    :data [{:type :server :id "server-3"}
           {:type :server :id "server-4"}
           {:type :server :id "server-5"}]}
```

The return order of resources from `lookup-resources` is stable and sorted by internal resource ID.

## Quickstart

The following example is contained in [eacl-example](https://github.com/theronic/eacl-example).

Add the EACL dependency to your `deps.edn` file:

```clojure
{:deps {cloudafrica/eacl {:git/url "git@github.com:cloudafrica/eacl.git" 
                          :git/sha "884a1d0e08049cbf55fd59e9f235945fc6f732e4"}}}
```

```clojure
(ns my-eacl-project
  (:require [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.core]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.impl :refer [Relation Permission]]))

; Create an in-memory Datomic database:
(def datomic-uri "datomic:mem://eacl")
(d/create-database datomic-uri)

; Connect to it:
(def conn (d/connect datomic-uri))

; Install the latest EACL Datomic Schema:
@(d/transact conn schema/v6-schema)

; Transact your permission schema (details below).
@(d/transact conn
  [; Account:
    ; account { relation owner: user }
    (Relation :account :owner :user)
    
    ; account {
    ;   permission admin = owner
    ;   permission update = admin
    ; }
    (Permission :account :admin {:relation :owner})
    (Permission :account :update {:permission :admin})
   
    ; product { relation account: account }
    (Relation :product :account :account)

    ; product { permission edit = account->admin }
    (Permission :product :edit {:arrow :account :permission :admin})])

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
            :entid->object-id (fn [db eid] (:eacl/id (d/entity db eid)))})) ; optional. to internal IDs to your external IDs.
 
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
   :limit         1000
   :cursor        nil})
; => {:data [{:type :product, :id "product-1"}]
;     :cursor 'cursor}
```

## EACL Schema

### Modelling Relations

Let's model the following SpiceDB schema in EACL:

```
definition user {}

definition account {
  relation owner: user
}
```

We define two resource types, `user` & `account`, where any `user` subject can be the `:owner` of an `account` resource. 

In EACL we use:
```
 (Relation resource-type relation-name subject-type)
```
e.g.
```
(Relation :account :owner :user)
```

This means a `user` subject can have  an `:owner` relation to an `account`, via a Relationship:
```
(Relationship (->user 123) :owner (->account 456))
```

A Relationship is just a 3-tuple of `[subject relation resource]`.

### Permission Schema: Direct Relations

Let's add a direct permission to the schema for `account` resources:

```
definition user {}

definition account {
  relation owner: user
  permission update = owner
}
```

In EACL, **Direct Permissions** use `(Permission resource-type permission {:relation relation_name)`,
 - e.g. `(Permission :account :update {:relation :owner})`
 - This means any `<user>` who is an `:owner` of an `<account>`, will have the `update` permission for that account.

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

Arrow permissions impply a graph hop. Arrows are designated by `->`. Let's look at arrow permissions in the SpiceDB schema DSL:
```
definition user {}

definition account {
  relation owner: user
  
  permission admin = owner
  permission update = owner
}

definition product {
  relation account: account
  
  permission edit = account->admin  ; (this is an arrow permission)
}
```

Here, `permission edit = account->admin` states that subjects are granted the `edit` permission _if, and only if_ they have the `admin` permission on the related account for that product. Only account owners have the `admin` permission on the related account. So given that,
 1. `(->user "alice")` is the `:owner` of `(->account "acme")`, and
 2. `(->account "acme")` is the `:account` for `(->product "SKU-123")`,
 3. EACL can traverse the permission graph from user -> account -> product to derive that Alice has the `:edit` permission on product `SKU-123`.

Here is the equivalent schema in the current EACL syntax:
```clojure
(require '[eacl.datomic.impl :refer [Relation Permission]])

[; Account:
 ; definition account {
 ;   relation owner: user
 ; 
 ;   permission admin = owner
 ;   permission update = admin
 ; } 

 ; account { relation owner: user }
 (Relation :account :owner :user)

 ; account { permission admin = owner }
 (Permission :account :admin {:relation :owner})
 ; account { permission update = owner }
 (Permission :account :update {:permission :admin})

 ; Product with an arrow permission:
 ; definition product {
 ;   relation account: account
 ;   permission edit = account->admin
 ; }
 (Relation :product :account :account)
 (Permission :product :edit {:arrow :account :permission :admin})
]
```

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

`eacl.datomic.core/make-client` accepts a Datomic conn and a config map of `{:keys [entid->object-id object-id->ident]}`, which are functions to convert between internal to/from external IDs.

It is common to attach a unique UUID to permissioned entities for exposing them externally, or you can convert external->internal at your call sites. Here is how you can configure EACL to convert to/from a unique attribute named `:your/id`:

```clojure
(def acl (eacl.datomic.core/make-client conn
           {:entid->object-id (fn [db eid] (:your/id (d/entity db eid)))
            :object-id->ident (fn [obj-id] [:your/id obj-id])}))
```

Note that this attribute should have property `:db/unique :db.unique/identity`. 

The default options are to use the built-in EACL string attr `:eacl/id`, but you can use the internal Datomic eids with the following "identity" functions:
```clojure
(def acl (eacl.datomic.core/make-client conn
           {:entid->object-id (fn [_db eid] eid)
            :object-id->ident (fn [obj-id] obj-id)}))
```

## Schema Syntax

Your EACL schema lives in Datomic. The following functions correspond to SpiceDB schema and return Datomic entity maps:

- `(Relation resource-type relation-name subject-type)`
- `(Permission resource-type permission-to-grant spec)`, where spec has  `{:keys [arrow relation permission]}`.
- `(Relationship user1 relation-name server1)` confers `permission` to subject `user1` on server1.

`Permission` supports the following syntax: 
- `(Permission resource-type permission {:relation some_relation})` ; the missing `:arrow` implies `:self`.
- `(Permission resource-type permission {:permisison some_permission})` ; the missing `:arrow` implies `:self`.
- `(Permission resource-type permission {:arrow source :permission via_permission})`
- `(Permission resource-type permission {:arrow source :relation via_relation})`

Internally everything is an arrow permission, but omitted `:arrow` means `:self` (reserved word). 

e.g.
```
(Permission :server :admin {:arrow :account :relation :owner})
```

Which you can read as follows:

```
definition account {
  relation owner: user
  permission admin = owner
}

definition server {
  relation account: account
  permission admin = account->admin
}

```

## Example Schema Translation

Given the following SpiceDB schema,

```
definition user {}

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
}
```

How to model this in EACL?

```clojure
(require '[datomic.api :as d])
(require '[eacl.datomic.impl :as impl :refer [Relation Permission Relationship]])

@(d/transact conn
             [; definition platform {
              ;   relation super_admin: user
              ; } 
              (Relation :platform :super_admin :user)
              
              ; definition account {
              ;   relation platform: platform
              ;   relation owner: user
              ; }
              (Relation :account :platform :platform)
              (Relation :account :owner :user)
              
              ; definition account {
              ;   permission admin = owner + platform->super_admin
              ; }
              (Permission :account :admin {:relation :owner})
              (Permission :account :admin {:arrow :platform :relation :super_admin})
              
              ; definition server {
              ;   relation account: account
              ;   relation shared_admin: user
              ; 
              ;   permission reboot = account->admin + shared_admin
              ; }
              (Relation :server :account :account)
              (Relation :server :shared_admin :user)
              
              (Permission :server :reboot {:arrow :account :permission :admin})
              (Permission :server :reboot {:relation :shared_admin})])
```

Now you can transact relationships:

```clojure
@(d/transact conn
  [{:db/id     "platform-tempid"
    :eacl/id   "my-platform"}
   
   {:db/id     "user1-tempid"
    :eacl/id   "user1"}

   {:db/id     "account1-tempid"
    :eacl/id   "account1"}

   (Relationship "platform-tempid" :platform "account1-tempid")
   (Relationship "user1-tempid" :owner "account1-tempid")])
```

(I'm using tempids in example because entities are defined in same tx as relationships)

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
- `read-schema` & `write-schema!` are not supported yet because schema lives in Datomic, but this is high priority to validate schema changes.
- *No cache:* EACL does not presently have a cache, because Datomic Peers cache datoms aggressively and queries so far are fast enough. A cache is planned.
- *Return order:* Unlike SpiceDB, EACL enumerates subjects & resources in the order they are stored in at-rest, which is always by Datomic eid (note: not external ID). SpiceDB returns results in order of discovery or schema order. SpiceDB guarantees stable order, but the order is non-deterministic. You should not rely on this order when using EACL or SpiceDB.

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
