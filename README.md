# **EACL**: Enterprise Access Control

EACL is a [SpiceDB-compatible](https://authzed.com/spicedb) authorization system built in Clojure and Datomic, used at [CloudAfrica](https://cloudafrica.net/).

EACL aims to resolve the impedance mismatch between Datomic entities and SpiceDB Relationships to simplify synchronising relationships from Datomic to SpiceDB, while enabling later adoption of SpiceDB once you need the high-performance consistency semantics supported by Spice.

To make entities in Datomic compatible with EACL, you need the following two attributes defined:
- `:resource/type` (keyword), e.g. `:server` or `:account`
- `:entity/id` (string), e.g. `"unique-account-1"`

## Licence & Funding

- The open-source work to make EACL Spice-compatible was generously funded by my employer, [CloudAfrica](https://cloudafrica.net/). We are a Clojure shop, and sometimes we hire Clojure & Datomic experts.
- EACL switched from BSL to an Affero GPL licence on 2025-05-27. 

## Usage

Refer to the `IAuthorization` protocol in [eacl/protocols.clj](src/eacl/core.clj).

The primary API call is `can?`:

```clojure
(can? db subject permission resource) => true | false
```

E.g.
```clojure
(can? db (spice-object :user "user1") :edit_product (spice-object :product "product1")) => true | false
```

(Todo better docs for `write-relationships`.)

## Configuration

```clojure
(ns my-project
  (:require [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.core :as spiceomic]))

(def datomic-uri "datomic:mem://eacl-test")
(d/create-database datomic-uri)
(def conn (d/connect datomic-uri))

; Transact the EACL Datomic Attributes
@(d/transact conn schema/v3-schema) ; transact EACL schema attributes
; Transact your Spice-compatible EACL Schema (details below):
@(d/transact conn your-eacl-schema)

; Make an EACL client that supports IAuthorization protocol:
(def client (spiceomic/make-client conn)) ; make EACL client

; Ensure your resources have `:resource/type` & `:entity/id`:
@(d/transact conn
             [{:resource/type :user
               :entity/id     "user1"}

              {:resource/type :server
               :entity/id     "server1"}])

; Convenience Helpers to construct spice-object:
(def ->user (partial spice-object :user))
(def ->server (partial spice-object :server))

@(d/transact conn your-relationships) ; schema details below

(eacl/can? (d/db conn) (->user "user1") :view (->server "server1"))
=> true | false

(eacl/can! (d/db conn) (->user "user1" :view (->server "server1")
                               => throws if `can? `returns false for same arguments.

                               (eacl/lookup-resources (d/db conn) {:resource/type :server
                                                                   :permission    :view
                                                                   :subject/id    "user1"})
                               => [{:type :server :id "server1"},
                                   {:type :server :id "server2"}]
                               ; ^ collection of :server resources (spice-object) that user1 can :view.

                               (eacl/lookup-subjects (d/db conn) {:resource/type :server
                                                                  :permission    :view
                                                                  :resource/id   "server1"})
                               => [{:type :user
                                    :id   "user1"},
                                   {:type :account
                                    :id   "account1"}]
                               ; collection of subjects that can :view the server resource "server1".
```

## Schema

EACL schema lives in Datomic. The following functions correspond to SpiceDB schema and return Datomic entity maps:

- `(Relation :resource-type :relation-name :subject-type)`
- `(Permission :resource_type :relation_name :permission)`
- `(Relationship user1 :relation-name server1)` confers `:permission` to subject `user1` on server1.

Additionally, we support SpiceDB arrow syntax with a 4-arity call to Permission:
- `(Permission :resource-type :relation_name :relation_permission :admin)`

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
(require '[eacl.datomic.impl :as spiceomic :refer [Relation Permission Relationship]])

@(d/transact conn
             [(Relation :platform :super_admin :user)
              (Permission :platform :super_admin :admin)

              (Relation :account :super_admin :user)
              (Relation :account :platform :platform)

              (Permission :account :owner :admin)
              (Permission :account :owner :admin)
              (Permission :platform :super_admin :admin :admin)

              (Relation :server :account :account)
              (Relation :server :shared_admin :user)

              (Permission :server :shared_admin :reboot)
              (Permission :server :account :admin :reboot)])
```

Now you can transact relationships:

```clojure
@(d/transact conn
  [{:db/id         "user1"
    :resource/type :user
    :entity/id     "user1"}

   {:db/id         "account1"
    :resource/type :account
    :entity/id     "account1"}

   (Relationship "user1" :owner "account1")])
```

(I'm using tempids in example because entities are defined in same tx as relationships)

## Limitations, Deficiencies & Gotchas:
- EACL makes no performance-related claims about being fast. It should be fine for <10k entities.
- Arrow syntax requires an explicit permission on the related resource.
- Only "sum" permissions are supported, not negation, i.e. `permission admin = owner + shared_admin` is supported, but not `permission admin = owner - shared_member`. 
- Specify a `Permission` for each relation in a sum-type permission.
- EACL is fully consistent so does not support the SpiceDB consistency semantics enabled by ZedTokens or Zookies. However, you can simulate this by using an older cached `db` value.
  SpiceDB is heavily optimised to maintain a consistent cache.
- `subject.relation` is not currently supported, but can be added.

## How to Run All Tests

```shell
clj -X:test
```
## Run Test for One Namespace

```bash
clj -M:test -n eacl.core3-test
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

EACL is a Datomic-based authorization system based on .

- There are Subjects & Resources.
- Resources have Relations, e.g. `:product/owner`, which confers a set of permissions on a Subject, e.g. 
  `:product/view`, `:product/delete`.
- Subjects & Resources are connected via Relationships.
- Relationships have `{:keys [subject relation resource]}`, e.g. `(Relationship :user/joe :product/owner :test/product1)`.

If a Resource is reachable by a Subject via a Relation, the permissions from that Relation are conferred on the subject.

Todo better docs, but there's also:

- (lookup-subjects ...)
- (lookup-resources ...)

# Licence

I don't know what Licence to use for this, so I slapped a BSL on it.

Just ask if you want to use it – I'm generally happy to open-source it.