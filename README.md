# **EACL**: Enterprise Access ControL

EACL is an embedded [SpiceDB-compatible](https://authzed.com/spicedb)* [ReBAC](https://en.wikipedia.org/wiki/Relationship-based_access_control) authorization library built in Clojure and backed by Datomic, used at [CloudAfrica](https://cloudafrica.net/).

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

1. Situated permissions avoids network I/O to an external AuthZ system.
2. Accurate ReBAC model allows 1-for-1 syncing of Relationships to an external ReBAC system like SpiceDB in real-time.
3. Avoids complex diffing when creating, deleting or updating Relationships.
4. Queries are fully consistent, until you need the consistency semantics of SpiceDB.
5. EACL should outperform SpiceDB at small to medium-scale if you are already using Datomic.

## ReBAC: Relationship-based Access Control

In a ReBAC system like EACL, _Subjects_ & _Resources_ are related via _Relationships_.

A `Relationship` is just a 3-tuple of `[subject relation resource]`, e.g.
- `[user1 :owner account1]` means the subject `user1` owns the account resource `account1`.
- whereas `[account1 :account product1]` means subject `account1` is the account for resource `product1`.

By defining a permission schema, we can grant `:view` & `:edit` permissions to any user who is an `:owner` of an `<account>`.

A situated ReBAC system like EACL can traverse the graph of relationships between subjects and permissions to calculate permissions while avoiding network I/O to an external authorization system.

## EACL API

The `IAuthorization` protocol in [src/eacl/core.clj](src/eacl/core.clj) defines an idiomatic Clojure interface that maps to and extends the [SpiceDB gRPC API](https://buf.build/authzed/api/docs/main:authzed.api.v1):

- `(eacl/can? client subject permission resource) => true | false`
- `(eacl/lookup-subjects client filters) => {:data [subjects...], cursor 'next-cursor}`
- `(eacl/lookup-resources client filters) => {:data [resources...], :cursor 'next-cursor}`.
- `(eacl/count-resources client filters) => <count>` materializes full index, so can be slow. Use sparingly.
- `(eacl/read-relationships client filters) => [relationships...]`
- `(eacl/write-relationships! client updates) => {:zed/token 'db-basis}`,
  - where `updates` is just a coll of `[operation relationship]` where `operation` is one of `:create`, `:touch` or `:delete`.
- `(eacl/create-relationships! client relationships)` simply calls write-relationships! with `:create` operation.
- `(eacl/delete-relationships! client relationships)` simply calls write-relationships! with `:delete` operation.
- `(eacl/write-schema! client)` is not impl. yet because schema lives in Datomic. TODO.
- `(eacl/read-schema client)` is not impl. yet because schema lives in Datomic. TODO.
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

*Note:* the return order of resources from `lookup-resources` is stable, but undefined, because results are read directly from index for speed.

## Quickstart

The following example is contained in [eacl-example](https://github.com/theronic/eacl-example).

Add the EACL dependency to your `deps.edn` file:

```clojure
{:deps {cloudafrica/eacl {:git/url "git@github.com:cloudafrica/eacl.git" 
                          :git/sha "a1671e4c8d6abb9925f94a7a6dfe9dffc5173ed5"}}}
```

```clojure
(ns my-eacl-project
  (:require [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.core]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.impl :refer [Relation Permission]]))

; Connect to an in-memory Datomic database:
(def datomic-uri "datomic:mem://eacl")
(d/create-database datomic-uri)
(def conn (d/connect datomic-uri))

; Install the latest EACL Datomic Schema:
@(d/transact conn schema/v4-schema)

; Transact your permission schema (details below).
@(d/transact conn
  [; Account:
    (Relation :account :owner :user)                     ; relation owner: user
    (Permission :account :owner :admin)
    (Permission :account :owner :update_billing_details)
    
    ; Product:
    (Relation :product :account :account)                ; relation account: account
    (Permission :product :account :admin :update_sku)])    ; permission update_sku = account->admin

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
(def ->user (partial spice-object :user))
(def ->account (partial spice-object :account))
(def ->product (partial spice-object :product))
  
; Write some Relationships to EACL (you can also transact this with your entities):
(eacl/create-relationships! acl
  [(eacl/->Relationship (->user "user-1") :owner (->account "account-1"))
   (eacl/->Relationship (->account "account-1") :account (->product "product-1"))])

; Run some Permission Checks with `can?`:
(eacl/can? acl (->user "user-1") :update_billing_details (->account "account-1"))
; => true
(eacl/can? acl (->user "user-2") :update_billing_details (->account "account-1"))
; => false

(eacl/can? acl (->user "user-1") :update_sku (->product "product-1"))
; => true
(eacl/can? acl (->user "user-2") :update_sku (->product "product-1"))
; => false

; You can enumerate resources via `lookup-resources`:
(eacl/lookup-resources acl
  {:subject       (->user "user-1")
   :permission    :update_sku
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

### Modelling Relations

Let's model the following SpiceDB schema in EACL:

```
definition user {}

definition account {
  relation owner: user
}
```

We define two resource types, `user` & `account`, where a `user` subject can be an `owner` of an `account` resource. 

In EACL we use:
 - `(Relation resource-type relation-name subject-type)`, i.e.
 - `(Relation :account :owner :user)`

 This means that an `<account>` resource can be related to a `<user>` via an `:owner` Relationship.

### Modelling Direct Permissions

Let's add a direct permission to the schema for `account` resources:

```
definition user {}

definition account {
  relation owner: user
  permission update_billing_details = owner
}
```

In EACL, **Direct Permissions** use `(Permission resource-type relation-name permission-to-grant)`,
 - e.g. `(Permission :account :owner :update_billing_details)`
 - This means any `<user>` who is an `:owner` of an `<account>`, will have the `update_billing_details` permission for that account.

However, at this point, all permissions checks will return false because there are no Relationships defined:

```clojure
(eacl/can? acl (->user "alice") :update_billing_details (->account "acme"))
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

Now that we have a Relationship betweee a user and an account, we can call `eacl/can?` to check if a user has the permission to update the ACME account's billing details:
```clojure
(eacl/can? acl (->user "alice") :update_billing_details (->account "acme"))
=> true
```
However, Bob cannot, because he is not an `:owner` of the ACME account:
```clojure
(eacl/can? acl (->user "bob") :update_billing_details (->account "acme"))
=> false
```

### Arrow Permissions

Let's model an indirect Arrow Permission:

```
definition user {}

definition account {
  relation owner: user
  
  permission admin = owner
  permission update_billing_details = owner
}

definition product {
  relation account: account
  permission update_sku = account->admin  ; (this is an arrow permission)
}
```

The arrow permission implies that any subject who has the `admin` permission on the related account for that product, will also have the `update_sku` permission for that product.

Given that,
 1. `(->user "alice")` is the `:owner` of `(->account "acme")`, and
 2. `(->account "acme")` is the `:account` for `(->product "SKU-123")`
EACL can traverse the permission graph from user->account->product to calculate that Alice can `update_sku` for product `SKU-123`, but not for any other products.

Here is the equivalent EACL schema (these are just Datomic tx-data):
```
[; Account:
 (Relation :account :owner :user)                     ; relation owner: user
 (Permission :account :owner :admin)                  ; EACL requires this permission for arrow permission lower down.
 (Permission :account :owner :update_billing_details)

 ; Product:
 (Relation :product :account :account)                ; relation account: account
 (Permission :product :account :admin :update_sku)    ; permission update_sku = account->admin
]
```

Now you can use `can?` to check those arrow permissions:
```clojure
(eacl/can? acl (->user "alice") :update_sku (->product "SKU-123"))
=> true ; if Alice is an :owner of the Account for that Product.

(eacl/can? acl (->user "bob") :update_sku (->product "SKU-123"))
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

Your EACL schema lives in Datomic. The following functions correspond to SpiceDB schema and return Datomic entity maps:

- `(Relation resource-type relation-name subject-type)`
- `(Permission resource-type relation-name permission-to-grant)`
- `(Relationship user1 relation-name server1)` confers `permission` to subject `user1` on server1.

Additionally, Permission has a 4-arity syntax to define arrow permissions:
- `(Permission resource-type relation-name via-permission permission-to-grant)`

e.g.
```
(Permission :server :account :owner :admin)
```

Which you can read as follows:

```
definition account {
  relation owner: user
  permission admin = owner
}

definition server {
  relation account: account
  permission admin = account->admin # <-- 4 arity arrow syntax
}

```

## Example Schema Translation

Given the following SpiceDB schema,

```
definition user {
}

definition platform {
  relation super_admin: user
  pemission admin = super_admin
}

definition account {
  relation owner: user
  relation platform: platform
  permission admin = owner + platform->admin
}

definition server {
  relation account: account
  relation shared_admin: user
  
  permission reboot = shared_admin + account->admin
}
```

How to model this in EACL?

```clojure
(require '[datomic.api :as d])
(require '[eacl.datomic.impl :as impl :refer [Relation Permission Relationship]])

@(d/transact conn
             [; Platform:
              (Relation :platform :super_admin :user)
              (Permission :platform :super_admin :admin)

              ; Accounts:
              (Relation :account :super_admin :user)
              (Relation :account :platform :platform)
              
              (Permission :account :owner :admin)
              (Permission :account :platform :super_admin :admin)

              ; Servers:
              (Relation :server :account :account)
              (Relation :server :shared_admin :user)

              (Permission :server :shared_admin :reboot)
              (Permission :server :account :admin :reboot)])
```

Now you can transact relationships:

```clojure
@(d/transact conn
  [{:db/id     "user1-tempid"
    :eacl/id   "user1"}

   {:db/id     "account1-tempid"
    :eacl/id   "account1"}

   (Relationship "user1-tempid" :owner "account1-tempid")])
```

(I'm using tempids in example because entities are defined in same tx as relationships)

## Limitations, Deficiencies & Gotchas:

- No consistency semantics because all EACL queries are fully-consistent. Use SpiceDB if you need consistency semantics enabled by ZedTokens ala Zookies. SpiceDB is heavily optimised to maintain a consistent cache.
- EACL makes no strong performance claims. It should be good for <1M Datomic entities. Goal is 10M entities.
- Arrow syntax is limited to one level of nesting, e.g.
  - Supported: `permission arrow = relation->via-permission` is valid
  - Not supported: `permission arrow = relation->subrelation->permission` is not valid. Add multiple nesting levels with intermediate resources.
- Tail of Arrow syntax tail must be a permission on the relation resource, i.e. given `permission admin = account->admin`, `admin` must be a permission on `account` relation, not a relation on account.
- Only union permissions are supported:
  - Supported: `permission admin = owner + shared_admin`
  - Not supported: `permission admin = owner - shared_member`. Exclusion types require complex caching to avoid multiple `can?` queries.
- Specify a `Permission` for each relation in a sum-type permission.
- `subject.relation` not currently supported, which is useful for group memberships.
- `expand-permission-tree` not implemented yet.
- `read-schema` & `write-schema!` not supported yet because schema lives in Datomic. Hoping to add support for Spice schema soon.

## How to Run All Tests

```shell
clj -X:test
```
## Run Test for One Namespace

```bash
clj -M:test -n eacl.datomic.impl-test
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

- EACL switched from BSL to an Affero GPL licence on 2025-05-27.
- However, we are considering re-licensing under a more permissive open-source license.
