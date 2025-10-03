# **EACL**: Enterprise Access ControL

EACL is an embedded [ReBAC](https://en.wikipedia.org/wiki/Relationship-based_access_control) authorization library based on [SpiceDB](https://authzed.com/spicedb), built in Clojure and backed by Datomic. EACL is used at [CloudAfrica](https://cloudafrica.net/).

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

To create a relationship, define a potential `Relation`, like so:

```clojure
; Account Resource:
(Relation :account :owner :user)      ; an :account can have :owner(s)
(Relation :account :viewer :user)     ; an :account can have :viewer(s)
; Product Resource:
(Relation :product :account :account) ; a :product has an :account
```

Given that an `<account>` has an `:owner`, and a `<product>` can have an `:account`, we can define a schema that grants `:edit` permission to owners, and `:view` permissions to viewers:

```clojure
; account { permission admin = owner }
(Permission :account :admin {:relation :owner})

; account { permission edit = account-.admin }
(Permission :account :edit {:arrow :account :permission :admin})

; product { permission view = admin + account->viewer }
(Permission :product :view {:arrow :account :permission :admin})
(Permission :product :view {:arrow :account :relation :viewer})
; (multiple permissions mean 'OR'. EACL does not support negation.)
```

## EACL API

The `IAuthorization` protocol in [src/eacl/core.clj](src/eacl/core.clj) defines an idiomatic Clojure interface that maps to and extends the [SpiceDB gRPC API](https://buf.build/authzed/api/docs/main:authzed.api.v1):

- `(eacl/can? client subject permission resource) => true | false`
- `(eacl/lookup-subjects client filters) => {:data [subjects...], cursor 'next-cursor}`
- `(eacl/lookup-resources client filters) => {:data [resources...], :cursor 'next-cursor}`.
- `(eacl/count-resources client filters) => {:keys [count limit cursor]}` supports limit & cursor for iterated counting. Use sparingly with `:limit -1` for all results.
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

However, at this point, all permissions checks will return false because there are no Relationships defined:

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

Now that we have a Relationship betweee a user and an account, we can call `eacl/can?` to check if a user has the permission to update the ACME account:
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

Let's model an indirect Arrow Permission:

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

The arrow permission implies that any subject who has the `admin` permission on the related account for that product, will also have the `edit` permission for that product.

Given that,
 1. `(->user "alice")` is the `:owner` of `(->account "acme")`, and
 2. `(->account "acme")` is the `:account` for `(->product "SKU-123")`
EACL can traverse the permission graph from user->account->product to calculate that Alice can `edit` product `SKU-123`, but not for any other products.

Here is the equivalent EACL schema (these are just Datomic tx-data):
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
- `read-schema` & `write-schema!` are not supported yet because schema lives in Datomic, but needs to be added soon to validate schema changes.
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
