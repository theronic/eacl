# 🦅 **EACL**: Enterprise Access ControL

EACL is a situated [ReBAC](https://en.wikipedia.org/wiki/Relationship-based_access_control) authorization library inspired by [SpiceDB](https://authzed.com/spicedb), built in Clojure and backed by [Datomic Pro](https://www.datomic.com/), [Datahike](https://datahike.io/), [DataScript](https://github.com/tonsky/datascript/), or a qualified embedded [Datalevin](https://datalevin.org/) deployment.

## Is it any good?

Yes.

## Demos

Try the [EACL Demo](https://demo.eacl.dev/) at [`demo.eacl.dev`](https://demo.eacl.dev/) with options for:
- **Backend:** Datomic Pro, Datahike, Datalevin or DataScript (in-browser)
- **Storage:** S3 or DynamoDB
- **Execution:** AWS Lambda or EC2 (t3.small instance)

[EACL Drive](https://drive.eacl.dev/) is a toy clone of Google Drive that shows how easy it is to add fine-grained permissions to your app.

## Overview

| Authentication (AuthN)                     | Authorization (AuthZ)    |
|--------------------------------------------|--------------------------|
| Who are you?, i.e. who is the `<subject>`? | What can `<subject>` do? |

EACL is concerned with fast & correct authorization, i.e. permissions.

EACL permissions are [just data](#data-structures) that co-exist with your application data – hence, _situated_, which offers [several benefits](#the-benefits-of-situated-authorization), notably:

1. Reduced network latency,
2. Strong local [consistency](#consistency-semantics),
3. Horizontal scaling because queries run in-process on the Peer, and
4. Real-time [UI view maintenance](#real-time-ui-maintenance) with near-optimal deltas.

🦅 EACL is pronounced "EE-kəl", like "eagle" with a `k` because as a situated AuthZ system, EACL _monitors the situation_ 🥁.

### Next Steps

- To get started with EACL, see [Quickstart →](#quickstart)  
- To see the supported backends, see [Supported Backends →](#supported-backends)
- To learn how to use EACL, see [EACL API →](#eacl-api)
- To understand the data structures, see [Data Structures →](#data-structures)
- To understand the consistency modes, see [Consistency Semantics →](#consistency-semantics)
- To understand how the EACL cache works, see [Caching →](#caching)

## Quickstart

### Datomic Pro

You need the Clojure CLI and Java 25 or newer for this published build. The
Datomic Peer dependency is included; this memory example needs no separate
Datomic server or account.

Create a directory with this `deps.edn`:

```clojure
{:deps {dev.eacl/eacl-datomic {:mvn/version "8.0.0-RC-2026-09-12"}}}
```

This is a release candidate (a preview release). See
[Clojars](https://clojars.org/dev.eacl/eacl-datomic) for published versions.
Start a REPL in that directory with `clojure -M`, then evaluate:

```clojure
(require '[datomic.api :as d]
         '[eacl.core :as eacl]
         '[eacl.datomic.core :as eacl.datomic]
         '[eacl.datomic.schema])

(def uri (str "datomic:mem://eacl-" (random-uuid)))
(d/create-database uri)
(def conn (d/connect uri))

;; Install EACL's schema, then your application's schema.
(eacl.datomic.schema/install! conn)
@(d/transact conn
   [{:db/ident       :app/id
     :db/valueType   :db.type/string
     :db/cardinality :db.cardinality/one
     :db/unique      :db.unique/identity}
    {:db/ident       :document/title
     :db/valueType   :db.type/string
     :db/cardinality :db.cardinality/one}])

(def acl (eacl.datomic/make-client conn
           {:object-id->lookup-ref (fn [id] [:app/id id])
            :entid->object-id      (fn [db eid] (:app/id (d/entity db eid)))}))

(eacl/write-schema! acl
  "definition user {}
   definition document {
     relation viewer: user
     relation owner: user
     permission view = viewer + owner
     permission share = owner
   }")

@(d/transact conn
   [{:app/id "alice"}
    {:app/id "bob"}
    {:app/id "report" :document/title "Report"}])

(def alice (eacl/spice-object :user "alice"))
(def bob (eacl/spice-object :user "bob"))
(def report (eacl/spice-object :document "report"))

(eacl/create-relationship! acl alice :viewer report)
(eacl/can? acl alice :view report) ; true
(eacl/can? acl bob :view report)   ; false

(mapv :id (:data (eacl/lookup-resources acl
                   {:subject alice :permission :view :resource/type :document :first 10})))
;; => ["report"]
```

Give each user and document a unique, stable ID owned by your application.
This example uses `:app/id`; you do not need to put application IDs in EACL's
internal schema.

The default token keys are process-local. For keys that survive restarts or
work across load-balanced Peers, see [security keys](docs/security-keyrings.md).
Leave cache options out to use the defaults.

To delete a secured entity in Datomic, use `:eacl.fn/retractEntity`. Install
the function during database setup; it removes the entity and its relationships
in one transaction:

```clojure
(require '[eacl.datomic.safe-retraction])
(eacl.datomic.safe-retraction/install! conn)
@(d/transact conn [[:eacl.fn/retractEntity [:app/id "report"]]])
(eacl/can? acl alice :view report) ; false
```

Do not use ordinary entity retraction while EACL relationships still refer to
the entity. See [safe deletion](#deleting-a-secured-entity).

- [Run the complete consumer checks](docs/examples/datomic-consumer/).
- [Combine application data and relationships in one transaction](docs/atomic-writes.md).
- [Set an expiration date on a share](docs/caveats.md#expiring-access).
- [Upgrade an existing application](docs/v8-backend-modules-and-upgrade.md#upgrading-an-application).
- [Datahike quickstart](#datahike-quickstart) or [DataScript quickstart](#datascript-quickstart).

## Supported Backends

| Database                                            | Module                                                          | Storage                                                                  |
|-----------------------------------------------------|-----------------------------------------------------------------|--------------------------------------------------------------------------|
| [Datomic Pro](https://www.datomic.com/)             | [eacl-datomic](https://clojars.org/dev.eacl/eacl-datomic)       | DynamoDB (recommended), Cassandra or SQL                                 |
| [Datahike](https://datahike.io/)                    | [eacl-datahike](https://clojars.org/dev.eacl/eacl-datahike)     | DynamoDB, S3 (cheaper, but slower), LMDB, SQL, Redis, GCS or IndexedDB.  |
| [DataScript](https://github.com/tonsky/datascript/) | [eacl-datascript](https://clojars.org/dev.eacl/eacl-datascript) | In-memory, but can persist to disk or add a SQL adapter. No time-travel. |
| [Datalevin](https://datalevin.org/)                 | [`eacl-datalevin`](modules/eacl-datalevin) — implemented, publication pending | Embedded LMDB; storage-enforced write policy and ordered generations.      |


S3-backed Datahike is attractive for infrequently-accessed apps, because you can trade latency for reduced storage cost, and it supports [serverless](https://github.com/replikativ/datahike-serverless) to reduce running Peer / Transactor costs.

*Note:* DataScript and Datalevin have no `at-exact-snapshot` semantics. Datahike requires a retained commit graph or temporal history to support exact snapshots. Datalevin uses scalar ordered-generation proofs from the maintained fork, so completed answers may reuse across unrelated forward revisions without historical selection.

## Overview of EACL

- EACL is inspired by [SpiceDB](https://authzed.com/spicedb):
    - SpiceDB is the most faithful open-source implementation of [Google Zanzibar](https://authzed.com/zanzibar).
    - Zanzibar powers Google Drive, YouTube, Gmail and Google Calendar, serving billions of authorization requests per day over billions of Relationships.
    - SpiceDB is used at [OpenAI](https://openai.com), [Reddit](https://www.reddit.com/) and [Netflix](https://www.netflix.com/).
- EACL operates at a different scale from SpiceDB:
  - Spice is benchmarked against 100B Relationships
  - EACL aims for ~10M Relationships or less in a situated environment – potentially 100M
- By adopting a [ReBAC](https://en.wikipedia.org/wiki/Relationship-based_access_control) data model, you can avoid a rewrite later and easily migrate to SpiceDB when you achieve hyperscale.
- EACL uses [formal verification techniques](#formal-verification) to enforce correctness.
- EACL is [fast](#performance) with a [cache](#caching) that benefits from a strong concept of time (snapshot basis) in immutable, single-writer ACID databases like Datomic or Datahike, i.e. we leverage monotonic basis derived from `t` in `[e a v t]`.
- EACL supports SpiceDB's [consistency semantics](#consistency-semantics), with some backend-specific limitations.
- EACL is black-box tested against SpiceDB, but with a different lookup return order (by design) because EACL is a different implementation.
- The cost of building EACL over 18+ months by an experienced engineer is estimated at ~\$80k–\$120k.
  - The first versions of EACL were tradcoded (pre-AI).
  - Using 2026+ frontier models, you may be able to rebuild EACL for <$50k, but good luck finding a cleanroom model, because every model since has been trained on EACL's & SpiceDB's source code.
  - If you decide to roll your own AuthZ, you will have to go through the same optimizations that EACL already has, but do not attempt to use Datalog child rules, because they do not scale (that was EACL v1).

This README is too long & too technical, so I am working to simplify it and break it out into area-specific documents. Despite my best attempts, EACL has become a large project.

[Continue to Project Status →](#project-status)

## Table of Contents

<!-- TOC -->
* [🦅 **EACL**: Enterprise Access ControL](#-eacl-enterprise-access-control)
    * [Next Steps](#next-steps)
  * [Is it any good?](#is-it-any-good)
  * [Supported Backends](#supported-backends)
  * [Overview of EACL](#overview-of-eacl)
  * [Table of Contents](#table-of-contents)
  * [Project Status](#project-status)
  * [Real-Time UI Maintenance](#real-time-ui-maintenance)
  * [EACL API](#eacl-api)
    * [Checking Permissions](#checking-permissions)
    * [Lookups](#lookups)
    * [Counting](#counting)
  * [Snapshots](#snapshots)
  * [Rationale](#rationale)
  * [The Benefits of Situated Authorization](#the-benefits-of-situated-authorization)
  * [ReBAC: Relationship-based Access Control](#rebac-relationship-based-access-control)
  * [Consistency Semantics](#consistency-semantics)
    * [Consistency Examples](#consistency-examples)
    * [Consistency Modes](#consistency-modes)
  * [Data Structures](#data-structures)
    * [Relationships](#relationships)
    * [Relations:](#relations)
    * [Permissions](#permissions)
      * [Permission Tuples (indices):](#permission-tuples-indices)
    * [Schema Tracking](#schema-tracking)
  * [Performance](#performance)
  * [Formal Verification](#formal-verification)
  * [Example Schema](#example-schema)
  * [Modules](#modules)
    * [Development from source](#development-from-source)
    * [Schema & Relationships](#schema--relationships)
    * [Relationship Maintenance](#relationship-maintenance)
    * [Deadlines and cooperative cancellation](#deadlines-and-cooperative-cancellation)
    * [Schema Maintenance](#schema-maintenance)
    * [Permission-tree expansion](#permission-tree-expansion)
    * [Example Queries](#example-queries)
  * [Quickstart](#quickstart)
    * [Datomic Pro](#datomic-pro)
    * [Datahike Quickstart](#datahike-quickstart)
    * [DataScript Quickstart](#datascript-quickstart)
  * [EACL Schema](#eacl-schema)
    * [Schema Validation](#schema-validation)
    * [Schema Updates](#schema-updates)
    * [Modelling Relations](#modelling-relations)
    * [Permission Schema: Direct Relations](#permission-schema-direct-relations)
    * [Creating Relationships](#creating-relationships)
    * [Permission Checks](#permission-checks)
    * [Arrow Permissions](#arrow-permissions)
  * [EACL ID Configuration](#eacl-id-configuration)
  * [Caching](#caching)
    * [Cache Coherence](#cache-coherence)
    * [Consistency and Zed tokens](#consistency-and-zed-tokens)
    * [Unknown object IDs](#unknown-object-ids)
    * [Deleting a Secured Entity](#deleting-a-secured-entity)
  * [Schema Syntax](#schema-syntax)
  * [Example Schema](#example-schema-1)
  * [Limitations, Deficiencies & Gotchas:](#limitations-deficiencies--gotchas)
  * [Differences from SpiceDB](#differences-from-spicedb)
  * [Funding](#funding)
* [Licence](#licence)
<!-- TOC -->

## Project Status

> [!WARNING]
> EACL is used in production, but under active development.
> The examples use `8.0.0-RC-2026-09-12`, a release candidate. See [Clojars](https://clojars.org/dev.eacl/eacl) for published versions.

## Real-Time UI Maintenance

Consider 10,000 online users: how often should clients re-query to keep their UIs up-to-date?
- If they poll on a schedule, clients are always out-of-date (eventually consistent).
- Refreshing each user's view on every DB write does not scale. In a single-writer system, more users means more frequent writes, i.e. `tx_rate(num_users, tx_rate_per_user)`.
- More frequent writes means more queries, so `num_users * tx_rate(num_users, tx_rate_per_user) * queries_per_view` quickly becomes a Read Amplification problem that can dramatically lower Peer performance, or require horizontal scaling.

What if you could compute exactly which users are affected by every DB write and notify only those clients, in real-time?
- Well, EACL already knows which resources each subject (or principal) can access based on your [permission schema](#schema--relationships).
- The EACL backends support transaction listeners via `d/listen`, so you can inspect `tx-data` for every transaction, and call EACL's efficient `eacl/lookup-subjects` to retrieve a list of users who can see the affected resource, filter it down to online users and notify them. Alternatively, call `eacl/can?` for each online user in parallel.
- To compute perfect viewership for complex nested queries would require [Different Dataflow](https://timelydataflow.github.io/differential-dataflow/), but you can get 80-90% of the way there by leveraging the permission graph – as long as mutations touch a resource that the principal can see.
- EACL cursors do not expire, because we can leverage time in our supported backends and ensure cache coherence.

## EACL API

EACL implements an idiomatic `IAuthorization` [protocol](modules/eacl/src/eacl/core.cljc) for each supported backend, which extends the [SpiceDB gRPC API](https://buf.build/authzed/api/docs/main:authzed.api.v1).

### Checking Permissions

EACL can efficiently answer questions like, "Can `<subject>` do `<permission>` on `<resource>`?"

```clojure
(eacl/can? acl subject permission resource ?consistency)
=> true | false

; e.g.
(eacl/can? acl (->user "alice") :view (->server "server1") eacl.spicedb.consistency/fully-consistent)
=> true | false
```
If you need cache provenance, use `check-permission` instead of `can?`, otherwise they are equivalent:

```clojure
(eacl/check-permission acl
  {:subject     subject
   :permission  permission
   :resource    resource
   :consistency eacl.spicedb.consistency/fully-consistent})
=> {:allowed? true, :cached? boolean, :cache-basis ...}
```

### Lookups

"Which `<resources>` does `<subject>` have `<permission>` on, as-of `<10 seconds ago, or newer>`?"

```clojure
(eacl/lookup-resources acl
  {:subject       subject
   :permission    permission
   :resource/type resource-type
   :first         page-size ; or :last page-size
   :consistency   (eacl.spicedb.consistency/at-least-as-fresh token-10s-ago)})
=> {:data      [{:type :product :id "product-1"}
                {:type :product :id "product-7"}
                ...
                {:type :product :id "product-63"}]
    :page-info ...
    :cached?   true|false>
    ...}
```

The `:consistency` argument is optional. The default is `minimize-latency`,
which means _locally-consistent_ to the Peer. `at-least-as-fresh` selects an
appropriate backend basis directly and requires no cache-checkpoint option.


```clojure
(def token-10s-ago (eacl.datomic/zed-token-at-least-seconds-ago acl 10))

(eacl/lookup-resources acl
  {:subject       (->user "alice")
   :permission    :view
   :resource/type :product
   :first         50
   :consistency   (eacl.spicedb.consistency/at-least-as-fresh token-10s-ago)})
=> {:data      [{:type :product :id "product-1"}
                {:type :product :id "product-7"}
                ...
                {:type :product :id "product-63"}]
    :page-info ...
    :cached?   true|false>
    ...}
``` 

```clojure
(eacl/lookup-resources acl query)
=> {:data [resources...] :page-info {...} :cached? boolean :cache-basis ...}
```

```clojure
(eacl/lookup-subjects acl query)
=> {:data [subjects...] :page-info {...} :cached? boolean :cache-basis ...}
```

### Counting

SpiceDB does not support counting (you must traverse in pages), but EACL does.

```clojure
(eacl/count-resources acl query)
=> {:count 42, :limit -1, :cached? boolean, :cache-basis ...}
```

```clojure
(eacl/count-subjects acl query)
=> {:count 7, :limit -1, :cached? boolean, :cache-basis ...}
```

Without `:count-limit`, `:limit` is `-1` and the count operation exhausts the
result set. Pass `:count-limit n` to bound work. The result then includes
`:truncated?`; `true` means at least one additional result exists.

Note: the default `:limit` will soon change to 50k instead of -1 (infinite), because high count-limits can exhaust Peers and trigger costly I/O from storage, esp. in recursive schemas.

## Snapshots

An EACL client reads the current database. A snapshot holds one database
version and one evaluation time, so several reads see the same state.

```clojure
(eacl/with-snapshot [s (eacl/snapshot acl)]
  [(eacl/can? s alice :view report)
   (eacl/lookup-resources s
     {:subject alice :permission :view :resource/type :document})])
```

`with-snapshot` releases the snapshot when the body finishes, including when
it throws. If you retain one manually, call `eacl/release!` when finished.
A read after release raises `:eacl/snapshot-released`. Datalevin snapshots
must be used and released on the platform thread that acquired them.

A retained snapshot keeps its captured time. An expiring share can therefore
still grant access in an old snapshot after its deadline. Use the client, or
acquire a new snapshot, when checking current access.

To select a historical version, pass a consistency descriptor to
`eacl/snapshot`. The backend must support that selection. A consistency
option on an existing snapshot only checks that it meets the requested
condition; it cannot move the snapshot to another version.

You can also preview changes without committing them:

```clojure
(eacl/with-snapshot [base (eacl/snapshot acl)]
  (let [tx (eacl/tx-relationship base :delete alice :viewer report)]
    (eacl/with-snapshot [preview (eacl/with base tx)]
      (eacl/can? preview alice :view report))))
```

Use `eacl/with-schema` to preview a permission schema. Preview results do not
become shared cached answers. Use these public helpers instead of wrapping a
raw Datomic `d/with` or `d/filter` value in an implementation-level client.

See [atomic writes](docs/atomic-writes.md) and the
[backend guide](docs/v8-backend-modules-and-upgrade.md) for supported snapshot
operations and backend limits.

## Rationale

I spent the better half of 2024 integrating [SpiceDB](https://authzed.com/spicedb) at [CloudAfrica](https://cloudafrica.net/).

- Keeping permission data synced to an external authorization system is non-trivial, especially if there is an impedance mismatch between your data model and SpiceDB's permission schema (3-tuple Relationships).
- SpiceDB write operations such as `WriteRelationships` return _ZedToken_ strings, which you can store alongside entities in your database to use the [SpiceDB cache](https://authzed.com/docs/spicedb/concepts/consistency#consistency-in-spicedb) with `at_least_as_fresh` and `at_exact_snapshot` consistency semantics.
- If you need to hit the DB (or cache) anyway to query Spice, you might as well situate your permission data in Datomic and avoid an external network hop as well as complex diffing & syncing operations – this is the promise of EACL.

Worried about load? You can horizontally scale Datomic Peers dedicated to authorization and even expose the EACL API to external consumers.

## The Benefits of Situated Authorization

EACL's situated philosophy aligns with that of Datomic: if Data is local and Query is local, perception can scale, so why wait for an external AuthZ system to compute permissions?

As long as the DB basis is recent enough for our consistency demands, we can avoid a network hop. This yields several benefits:

1. **Reduced Latency**: EACL avoids a network hop to an external AuthZ system, but depending on consistency semantics, we can await new data from the Transactor if the Peer has fallen behind.

   Consider that to leverage `at_least_as_fresh` [consistency semantics](https://authzed.com/docs/spicedb/concepts/consistency#consistency-in-spicedb) in SpiceDB for `LookupResources`, you need to:
    1. Hit the DB or cache for the latest ZedToken pertaining to an entity,
    2. Pass the ZedToken to SpiceDB to retrieve a consistent page of object IDs,
    3. Hydrate entities from your database using those IDs.

    In EACL, since Peers are locally-consistent, as long as database snapshot _S_ (valid @ time _T_) is locally available, we can query immediately without a network hop, or reuse cached answers derived from _S_ or newer.

    **Bonus:** Relationships are [just data](#data-structures), so permission graph traversal can improve database cache locality for faster entity hydration before display.

     Since you have to hit the DB anyway to show anything useful, we might as well compute permissions on the Peer, which has the database – which is what EACL does.

2. **Time Travel**: Unlike Spice cursors, EACL cursors do not expire (and are encrypted for UI exposure) unless you specify a TTL, so we can reconstruct selected snapshots if the backend retains it.
   - DataScript and the initial Datalevin adapter do not support `at-exact-snapshot`.

3. **Consistency:** Syncing to an external system introduces eventual consistency. With situated AuthZ, queries are at least locally-consistent as-of time `T`.
    - In **single-Peer environments**, EACL reads from the database currently visible to the local Peer.
    - In **multi-Peer environments**, depending on consistency semantics, EACL may block to catch up to the Transactor if the Peer has fallen behind.

4. **Simple Syncing**: Relationships are just 3-tuples of `[subject relation resource]`, so there is no impedance mismatch when syncing to SpiceDB at scale.

5. **Real-time UI updates** for materialized views: it is cheap to compute the subset of online clients that need to re-query while avoiding query amplification due to a busy Transactor.

6. **Situated is faster** for small (~1k-100k relationships) to medium-applications (~1M-10M Relationships):
    - Authorization runs in-process, so entities can be hydrate without a network hop.
    - End-to-end queries don't need to block on network I/O when data is local to the Peer.

7. Application & Authorization Data live together in harmony. In my testing with _small to medium-sized workloads_, EACL is as good, or faster than SpiceDB, owing to reduced latency from its situated design, but no EACL benchmarks are published at this time (benchmarks are a tricky business).

8. **One less thing** to deploy & sync Relationships to.

Note that EACL has [Limitations](#limitations-deficiencies--gotchas) compared to SpiceDB.

## ReBAC: Relationship-based Access Control

In a [ReBAC](https://en.wikipedia.org/wiki/Relationship-based_access_control) system like EACL, _Subjects_ & _Resources_ are related via _Relationships_.

A `Relationship` is just a 3-tuple of `[subject relation resource]`, e.g.
- `[user1 :owner account1]` means subject `user1` is the `:owner` of resource `account1`, and
- `[account1 :account product1]` means subject `account1` is the `:account` for resource `product1`.

EACL models the 3 core concepts in its permission graph:

1. Objects (Subjects & Resources),
2. Schema (Relation & Permission), and
3. Relationships.

What do they look like?

1. Subjects & Resources are just maps of `{:keys [type id]}`, e.g.
   - `(spice-object :user "user-1") => {:type :user,    :id "user-1"}`,
   - `(spice-object :product "product-5") => {:type :product, :id "product-5"}`
   - Typically, you'll have helpers like `(def ->user (partial spice-object :user))`, so that  
   `(->user "user-1") => {:type :user, :id "user-1"}`.
2. _Schema_ has `Relations` and `Permissions`:
    - `Relation` defines how a Subject & a Resource can be related via a `Relationship`.
    - `Permission` defines which permissions are granted to a Subject via a chain of `Relationships` between subjects & resources.
        - Permissions can be _Direct Permissions_ or indirect, known as _Arrow Permissions_. An arrow implies a graph traversal.
2. A _Relationship_ is a 3-tuple that defines how a Subject and a Resource are related via a named `Relation`, i.e. `(Relationship subject relation resource)`
    - e.g. `(Relationship (->user alice) :owner (->account "acme"))` means that,
      - `(->user "alice")` is the Subject,
      -  `:owner` is the name of the `Relation` (as defined by schema)
      - `(->account "acme")` is the Resource

## Consistency Semantics

EACL supports four consistency modes named after SpiceDB's [Consistency Semantics](https://authzed.com/docs/spicedb/concepts/consistency).

These modes allow us to trade consistency for speed (reduced latency), by enabling cache reuse. These modes affect which database snapshot _S_ and in-memory cache segments _C_ are allowed to participate in answering a permission query:

1. `minimize-latency` means locally-consistent to the Peer as-of now, i.e. `(d/db conn)` + valid cache segments.
2. `at-least-as-fresh` uses query against a local snapshot _S_ that is as fresh or fresher than `T`, i.e. `(d/sync conn T)` which will only block if Peer is behind _S_.
3. `at-exact-snapshot` always calls `(d/as-of db T)` and reuses cache segments valid for basis B >= _S_.
4. `fully-consistent` means locally-consistent after a `(d/sync conn)` (blocking call), so behaves like `minimize-latency` but Peer is fully up-to-date.

For detailed descriptions, [Continue to Consistency Modes →](#consistency-modes)

Core model:
- Consistency selects a backend-native immutable snapshot _S_ for time _T_.
- Cache proofs may reuse an answer proven equivalent on snapshot _S_.
- Cursors may continue on a proof-equivalent current snapshot _S_ or fall back to their authenticated exact snapshot.

### Consistency Examples

Consider that in a fast-moving database, `(d/db conn)` is always moving, so a naive cache would invalidate after every write. EACL uses dependency-proofs to reuse cached segments that are proven to be unaffected by unrelated writes.

For read-only actions, we might be fine with reusing cached answers that are a few seconds old and avoid expensive computation.

For example, when a YouTube video with millions of views is unpublished, it is probably fine to keep serving it for a few seconds instead of recomputing access on every view. Here's how to do it in EACL:

```clojure
(def token-10s-ago (eacl.datomic/zed-token-at-least-seconds-ago acl 10))

(eacl/can? acl (->user "alice") :view (->video "my-video")
           (eacl.spicedb.consistency/at-least-as-fresh token-10s-ago))
```

This also works for lookups, like listing video resources:
```clojure
(eacl/lookup-resources acl
  {:subject       (->user "alice")
   :permission    :view
   :resource/type :video
   :consistency   (eacl.spicedb.consistency/at-least-as-fresh token-10s-ago)})
```

However, for destructive actions, e.g. permanently deleting a video, we will want to make 100% sure the user is allowed to do that. For that we can use `fully-consistent`:

```clojure
(eacl/can? acl (->user "alice") :delete (->video "my-video")
           eacl.spicedb.consistency/fully-consistent) ; this will block on (d/sync conn)
```

Most of the time, we will use `eacl.spicedb.consistency/minimize-latency`, which uses what is locally-consistent to the Peer:

```clojure
(eacl/can? acl (->user "alice") :view (->video "my-video")
           eacl.spicedb.consistency/minimize-latency)
```

### Consistency Modes

- _S_ means a database snapshot _S_ with a basis _B_, valid at some point-in-time, _T_.
- _C_ means cached segments valid for snapshot _S_.
- Functionally, a snapshot _S_ is a `db` value as if derived from `(d/as-of (d/db conn) T)`, where
    - _T_ could be in the Present as seen from the local Peer, `(d/db conn)`,
    - _T_ could be in the Present but fully consistent across Peers, after `(d/sync conn)`.
    - _T_ could be in the Past, i.e. a few seconds ago, e.g. `(d/as-of db <T-10 seconds>)`, or
    - _T_ could be in the near Future `T+2 seconds`, which is relevant in multi-Peer systems.

Even in a fast-moving DB, EACL will reuse cache segments that are unaffected by unrelated mutations.

1. `minimize-latency` (the default and simplest) is fast and locally-consistent to the Peer, i.e. `(d/db conn)`.
    - EACL will reuse any cache segments that are valid for snapshot _S_ @ basis _B_ for time _T_, where _T_ is _now_.
2. `at-least-as-fresh` uses `@(d/sync conn token-revision)` which can block if the Peer is behind. It does not block if the Peer has revision snapshot _S_.
    - EACL will reuse cache segments that are valid for snapshot _S_, e.g. `<10 seconds ago>` or fresher.
    - This mode offers the greatest cache reuse. It's what YouTube uses for videos e.g. "can `<subject>` view this `<video>` as-of `<30 seconds ago, or fresher>`?"
    - If `T` is newer than the central Transactor has seen, EACL will return an error.
3. `fully-consistent` _blocks_ on `@(d/sync conn)` before taking a snapshot _S_ from `(d/db conn)`, because the Peer could be behind, so it can be slow:
    - Use fully-consistent for destructive actions, e.g. "can `<subject>` delete this `<video>` as-of `<right now>`?"
4. `at-exact-snapshot` always time-travels with `(d/as-of db T)`: 
    - Note: `d/as-of` can be expensive, so you will typically only use this if you need historical data.
    - Unlike SpiceDB, EACL allows arbitrary point-in-time queries over full history (if supported by the backend).
    - If `T` is in the future (relative to Peer), EACL may block on `(d/sync conn T)` before `(d/as-of db T)`.
       - EACL will throw if `T` is newer than the Transactor has seen.
       - EACL will only reuse cache segments that are valid for `T`.
    - Supported by Datahike if history is enabled.
    - `at-exact-snapshot` is not supported by DataScript or Datalevin. Both fail closed instead of emulating history.

The EACL engine and cache benefit from monotonic `txId` (transaction IDs), i.e. the `t` in `[e a v t]`, so cache segments are keyed by basis `T`. The engine will only use cache segments valid @ `T`.

EACL cursors encode DB basis, schema version and related cache proofs.

Not every mode is supported by every backend, because some modes rely on time travel over full history.

Unsupported modes by backend will return an error. Refer [Consistency and ZedTokens](#consistency-and-zed-tokens).

## Data Structures

EACL co-exists with your data in Datomic, Datahike, DataScript, or Datalevin. As a result, EACL installs and maintains some attributes in your data store, all of which are prefixed by `:eacl*`.

Presently, EACL Relationships are stored in history to support auditability, `d/as-of` & `at-exact-snapshot` semantics, but in a future version of EACL, history could be optional to save on storage, but then you lose time travel & auditability. For many applications that only care about permissions as-of "now", this would be acceptable.

The EACL-specific attributes are detailed below.

### Relationships

EACL Relationships are light by virtue of being stored directly on entities as two tuples:
- Forward subject->resource tuple: `:eacl.v8.relationship/subject-type+relation+resource-type+resource+qualifier`
- Reverse resource->subject tuple: `:eacl.v8.relationship/resource-type+relation+subject-type+subject+qualifier`

To retract an entity and its Relationships, use `:eacl.fn/retractEntity`, an optional Transactor function you can install.

If you only want to retract Relationships, call `eacl/delete-relationships!`.

To retract an entity and its Relationships, you may call `eacl/delete-relationships!` followed by `:db.fn/retractEntity`, but this will write two transactions to the tx-log, which is fine, but not ideal and adds noise to the Transactor, so prefer `:eacl.fn/retractEntity` for full entity retractions.

If you call `:db.fn/retractEntity` without `eacl/delete-relationships!`, you *will* leave ghost Relationship tuples lying around on the contra-object (subject or resource), so it's safer to use `:eacl.fn/retractEntity` for secured entities and reduce transactor noise.

EACL's contract with you is that you *MUST* use the EACL APIs to maintain Relationships so we can guarantee cache coherence and a clean database. If you mess with EACL's data structures, it becomes your problem.

But you will probably forget one day, so there are helpers to fined & clean up ghost tuples :). Refer [Deleting a Secured Entity](#deleting-a-secured-entity).

### Relations:

- `:eacl.relation/resource-type`
- `:eacl.relation/relation-name`
- `:eacl.relation/subject-type`
- `:eacl.relation/resource-type+relation-name+subject-type`
- `:eacl/relation-version` tracks cache coherence in Datomic, Datahike, and DataScript. Datalevin uses the scalar `:eacl.datalevin/relation-generation`; each is advanced by relevant Relationship writes.

### Permissions

- `:eacl/id`
- `:eacl.permission/resource-type`
- `:eacl.permission/permission-name`
- `:eacl.permission/expression-payload`

#### Permission Tuples (indices):

- `:eacl.permission/resource-type+permission-name`

The canonical payload contains its expression-format version. EACL does not
store a second format field, a content digest, a policy digest, admission
limits, or expression/DAG metrics. Those values either duplicate the payload
or describe one client's resource-admission policy rather than permission
meaning. Node counts, depth, fan-in, encoded size, normalized DAG counts, word
counts, and checkpoint weights are derived from the payload and may be cached
inside the client.

### Schema Tracking

- `:eacl/id` identifies EACL's internal Relations and Permissions. Use a separate application attribute, such as `:app/id`, for your users and resources.
- `:eacl/schema-string` stores a valid schema string was written via `eacl/write-schema!`.
- `:eacl/schema-version` track the schema revision in Datomic Pro.
- `:eacl/schema-generation` and `:eacl/schema-write-fence` track schema writes in Datahike and DataScript. Datalevin uses scalar `:eacl.datalevin/schema-generation` and `:eacl.datalevin/schema-write-fence` values in its native `max-tx` domain.
- `:eacl/storage-version` identifies Relationship storage ABI 8 across the bundled backends (five-slot endpoint pairs).
- `:eacl/permission-storage-version` identifies Datomic's canonical permission representation (version 8).
- `:eacl.fn/assert-relation-unused` is a Transactor function in Datomic that guards removing Relations with active Relationships (to avoids orphaned Relationships).

## Performance

- EACL is not meant for hyperscalers.
- The goal for EACL is to handle 100M Relationships with good performance and remain suitable for real-time UIs, due to its situated nature.
- EACL is internally benchmarked against 1M Relationships, tested against a real-world, recursive schema with e2e latency @ ~1-40ms per query (incl. hydration), depending on schema & query complexity.
- EACL makes no strong performance claims at this time, but EACL should be as fast as, or faster than, SpiceDB, for small-to-medium workloads.
- As load increases, you can scale Datomic Peers horizontally and even dedicate Peers to EACL as-needed.
- EACL does not support all SpiceDB features (yet). Please refer to the [limitations section](#limitations-deficiencies--gotchas) to decide if EACL is right for you.
- The EACL [cache](#caching) is stored in memory per client. JVM clients use
  Caffeine 3.2.4; CLJS/DataScript uses the theronic `cljs-cache` LRU. Custom
  cache providers are intentionally unsupported because they
  cannot participate in EACL's private lifecycle and proof contracts.

## Formal Verification

- EACL is [formally verified](https://en.wikipedia.org/wiki/Formal_verification) using Dafny, TLA+/TLC, and Apalache, but has not been independently audited or certified.
- The EACL kernel (decision engine + cache) is generated from [formal models](formal/README.md). The engine is heavily tested to never say "yes" when it should say "no". The cache will only use cached answers that are proven to be valid for snapshot _S_ with basis _B_ at time _T_ to satisfy your requested consistency semantics.
- The Clojure/ClojureScript backend implementations are internally certified, but not generated from proofs.
- EACL does not attempt to verify the correctness of its supported backends – that responsibility lies with the database authors.

## Example Schema

Let's design a simple permission schema for a Google Drive clone using the [SpiceDB schema DSL](https://authzed.com/docs/spicedb/concepts/schema):

```spicedb
definition user {}

definition folder {
  relation owner: user                             ; a folder as an ownerh
  relation viewer: user                            ; a folder can have viewers, who may not be the owner
  relation parent: folder                          ; a folder can have parent, i.e. nested file system
  
  permission view = owner + viewer + parent->view  ; can view a folder as the owner, or view its parent 
  permission edit = owner + parent->edit           ; you can edit a folder if you are the owner, or can edit its parent
}

definition document {
  relation owner: user                             ; a document has an owner 
  relation viewer: user                            ; a document can have viewers who aren't the owner
  relation folder: folder                          ; a document belongs in a folder

  permission view = owner + viewer + folder->view  ; you can view a document if you own it, ar a viewer or can view the parent folder 
  permission edit = owner + folder->edit           ; you can edit a document if own it, or if you can edit the folder
}
```

Basically, a user owns folders & documents; documents go in a folder and folders can nest, i.e. folders have children – it's a file system:

- You can `:view` a document if you are the owner, a viewer, or if you can view the folder it's in.
- You can `:view` a folder if you can view a parent folder (recursive schema).
- You can `:edit` a document if you are the owner, or if you can edit the folder (or any of its parents).

You can share documents or folders with other users by making them a `viewer`, i.e. by adding `(Relationship (->user "bob") :viewer (->folder "my-folder"))`, to share `my-folder` with user "bob".

Because of the recursive `view` permission, user `bob` will be able to `:view` any nested files or folders under the folder, `my-folder`, that you shared with them.

## Modules

EACL supports multiple backends. Each adapter will bring in the shared EACL engine:

```clojure
;; Datomic Pro
{:deps {dev.eacl/eacl-datomic {:mvn/version "8.0.0-RC-2026-09-12"}}}

;; Datahike
{:deps {dev.eacl/eacl-datahike {:mvn/version "8.0.0-RC-2026-09-12"}}}

;; DataScript
{:deps {dev.eacl/eacl-datascript {:mvn/version "8.0.0-RC-2026-09-12"}}}

;; Datalevin (coordinate reserved; publication remains gated)
{:deps {dev.eacl/eacl-datalevin {:mvn/version "8.0.0-SNAPSHOT"}}}

;; Core-only consumers and backend authors (you typically won't need this)
{:deps {dev.eacl/eacl {:mvn/version "8.0.0-RC-2026-09-12"}}}
```

### Development from source

For source development, clone the full repository, prepare the generated core  runtime as described below, then keep the same library coordinate and use :local/root`. The backend module resolves the sibling core module:

```clojure
{:deps {dev.eacl/eacl-datomic
        {:local/root "/absolute/path/to/eacl/core/modules/eacl-datomic"}}}
```

Source consumers who compile the EACL kernel locally need the Clojure CLI, Node.js, and the repository-pinned Dafny, Apalache, and TLA+ tools. Prepare the generated JVM and browser runtimes before using a `:local/root` dependency:

```bash
cd modules/eacl

# Default Java target
clojure -T:build prep

# Example Java 17 target
clojure -T:build prep :java-release 17
clojure -T:build jar :java-release 17
```

Pass the same `:java-release` to `prep` and `jar` or `install`. The generated
kernel defaults to Java 25 bytecode and may be compiled for Java 8 through 26,
but the complete JVM EACL module requires Java 11 or newer because its Caffeine
cache dependency does. Java 17 is EACL's supported production runtime floor.
See [formal/README.md](formal/README.md) for tool versions and the full
verification commands.

For permission operators, precedence, stratification, limits, ordering, cursors,
cache behavior, and measured performance, see the [permission set-algebra
guide](docs/permission-set-algebra.md). For module selection, current
capability differences, cache mutation rules, and recursive controls, see the
[backend guide](docs/v8-backend-modules-and-upgrade.md). Datalevin setup,
mandatory lifecycle/watermark inputs, write-policy boundary, and publication
status are documented in the [`eacl-datalevin` module
README](modules/eacl-datalevin/README.md). Backend authors should also read the
[adapter boundary](docs/v8-backend-adapter-boundary.md) and [basis-source
migration guide](docs/v8-snapshot-provider-migration.md).

### Schema & Relationships

To create a Relationship, first define your schema using `eacl/write-schema!`:

```clojure
(eacl/write-schema! acl
  "definition user {}

   definition account {
     relation owner: user
     relation viewer: user
     relation active: user
     relation banned: user

     permission admin = owner - banned
     permission view = (owner + viewer) & active
   }

   definition product {
     relation account: account

     permission edit = account->admin
     permission view = account->view
   }")
```

This schema defines:
- An `account`, which can have owner, viewer, active, and banned users, with
  `admin` permission granted to non-banned owners.
- A `product` belongs to an `account`, with `edit` permission for account
  admins and `view` permission for active account owners or viewers.

In the schema DSL, `+` is union, `&` is intersection, and `-` is directed set
exclusion. Parentheses are supported. `+` binds more tightly than `&`, which
binds more tightly than `-`; repeated exclusion associates from the left.

### Relationship Maintenance

```clojure
(eacl/read-relationships acl filters)
=> {:data        [relationships...]
    :page-info   {...}
    :cached?     boolean
    :cache-basis ...}
```

```clojure
(eacl/write-relationships! acl updates)
=> {:zed/token "eacl_z4_..."}
```
where `updates` is a collection of `RelationshipUpdate` records:
  - `(eacl/->RelationshipUpdate operation relationship)`,
  - or, maps `{:operation op :relationship rel}`, and
  - `operation` is one of `:create`, `:touch` or `:delete`.
  - A bare `[operation relationship]` vector is rejected as an unsupported update.
  - Mutation envelopes are closed: unknown keys, nil/non-sequential updates,
    and a single update map passed to a plural API are rejected rather than
    treated as an empty successful write.

- Schema names are validated: unknown definitions, relations or bad subject types will fail with `:eacl/unknown-definition` / `:eacl/unknown-relation-or-permission`.

Relationship Conflicts?
 
- `:create` will fail with `:eacl/relationship-conflict` if the same if the same Relationship already exists.
  - Datomic: a transactor-side relation stamp CAS with re-planning; DataScript and Datahike with the default in-process writer: a transaction function,
  - Two racing `:create`s of one relationship produce exactly one success.
  - A Datahike remote writer cannot transport a transaction function and keeps the plan-time check only
  - `:touch` is idempotent. Repeating one operation for the same relationship inside a batch has the same outcome as submitting it once (`:create` still conflicts when the relationship existed before the batch); mixing different operations for the same resolved relationship throws `:eacl/invalid-relationship-update-batch` before submission.
- `(eacl/create-relationships! acl relationships)` simply calls `write-relationships!` with `:create` operation.
- The map form of `write-relationship!` is closed too: a misspelled qualifier
  such as `:valid-until-mss` is rejected instead of creating a relationship
  without the intended expiry.
- `(eacl/delete-relationships! acl relationships)` simply calls `write-relationships!` with `:delete` operation.
- `delete-relationships!` also accepts a `read-relationships` page containing
  sequential `:data`; one bare `Relationship` map/record is rejected so a
  revocation cannot silently become a no-op.
- `(eacl/delete-object! acl object) => {:zed/token "eacl_z4_...", :retracted-datoms n}` is a convenience helper that removes every relationship touching `object`, in both directions. `n` counts relationship datoms actually retracted by the committed transactions. On Datomic the retractions are committed in batches of 1,000 (a concurrent reader can observe a partially deleted object between batches); on DataScript and Datahike they are one atomic transaction. Consumers are expected to delete relationships before retracting a secured entity — see [Deleting a Secured Entity](#deleting-a-secured-entity).
- `delete-object!` rejects malformed objects, including a missing or nil ID,
  rather than returning a successful zero-retraction cleanup response.
- `(eacl/delete-object-by-eid! acl native-eid)` is the explicit ghost-repair form for an entity whose public identity has already been retracted. Numeric IDs passed to `delete-object!` remain public IDs and are never reinterpreted as backend entity IDs.
- Public request maps are closed. Unknown fields—including misspelled
  consistency controls—raise `:eacl/invalid-request` before snapshot selection
  or writer dispatch.
- Pagination is stable-basis only. `:page/basis :stable` is accepted;
  reserved or malformed alternatives such as `:live` are rejected instead of
  being silently ignored.
- The public schema writer does not expose the lower-level
  `:allow-empty-schema?` escape hatch. Intentional low-level schema wipes must
  use a backend schema API and its cache-recovery obligations.
- Snapshot callers may choose a documented consistency mode, but cannot
  supply protocol-level runtime options. Trusted identity codecs, clocks,
  caches, and security configuration always come from `make-client`.

All list APIs use the v8 Relay pagination contract:

- Forward: pass `:first` and optionally `:after`.
- Backward: pass `:last` and optionally `:before`.
- Responses include `:page-info` with `:start-cursor`, `:end-cursor`, `:has-next-page?`, and `:has-previous-page?`.
- Lookup cursors paginate in the sealed plan's stable first-discovery order; a page size change is rejected as an incompatible cursor rather than silently re-windowed.

### Aggregate authorization

Use `eacl/check-permissions` for several decisions on one snapshot. To combine
a permission check with a direct relationship filter, choose:

- `read-relationships` with `:authorization` when the relationship set is smaller.
- `lookup-resources` with `:resource/relationship`, or `lookup-subjects` with
  `:subject/relationship`, when the authorized set is smaller.

Both routes can return a short or empty page with `:has-next-page? true` and
`:bounded? true`. Continue with the returned cursor. Applications must also
authorize access to sharing metadata.

See [aggregate authorization](docs/aggregate-authorization.md) for examples.

### Deadlines and cooperative cancellation

Every bounded read accepts an optional per-request `:cancellation-token` in
addition to `:timeout-ms`. Create and cancel the token through the public EACL
API:

```clojure
(let [token (eacl/cancellation-token)]
  ;; Pass `token` to the HTTP/request owner before starting the read.
  (future
    (eacl/lookup-resources acl
      {:subject            (eacl/spice-object :user "alice")
       :permission         :view
       :resource/type      :document
       :first              100
       :cancellation-token token}))
  (eacl/cancel! token))
```

Cancellation is cooperative and best-effort. EACL checks it at the same
orchestration, cursor, cache, and reducer-transition boundaries (one check per
engine step, which covers each adapter command) as the absolute deadline and, when observed before completion, throws
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

Datomic and Datahike consumers upgrading a released v7 database must run the
backend's explicit permission-only v7-to-v8 migration, followed by the
[Relationship storage 7-to-8 migration](docs/relationship-storage-v7-to-v8.md), before
constructing an ordinary v8 client. Permission storage remains version 8.
Storage 8 uses a nullable qualifier reference in slot five for Caveats and
expiring Relationships.

### Permission-tree expansion

Expansion accepts exactly `:resource`, `:permission`, and the optional `:consistency`, `:timeout-ms`, and `:cancellation-token` keys:

```clojure
(eacl/expand-permission-tree acl
                             {:resource    (eacl/spice-object :document "readme")
                              :permission  :view
                              :consistency eacl.spicedb.consistency/fully-consistent
                              :timeout-ms  5000})
=>
{:expanded-at                                          "eacl_z4_..."
 :tree-root
 {:expanded-object                                    {:type :document :id "readme"}
  :expanded-relation                                  :view
  :intermediate
  {:operation                                        :union
   :children
   [{:expanded-object   {:type :document :id "readme"}
     :expanded-relation :viewer
     :leaf              {:subjects [{:type :user :id "alice"}]}}]}}}
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
`(eacl.spicedb.consistency/at-exact-snapshot (:expanded-at response))` only on a backend
that advertises exact historical selection; otherwise use it as an
at-least-as-fresh causal floor. Unsupported consistency, unavailable history,
deadlines, unknown root relations or permissions, cycles, codec failures,
adapter-contract failures,
and structural limits produce typed all-or-error failures—no lazy or partial
tree is returned.

Clients accept positive exact-integer `:permission-tree-limits` overrides.
They are configuration-only, not request keys:

```clojure
(eacl.datascript.core/make-client conn
  {:permission-tree-limits
   {:max-depth               50
    :max-schema-components   100000
    :max-relationship-values 100000
    :max-tree-nodes          100000
    :max-leaf-subjects       100000}})
```

Every bundled backend uses the same portable expansion kernel. Expected
backend differences include supported consistency modes, historical retention,
native scan order, and configured identity conversion.

### Example Queries

The primary API call is `can?`, e.g.

```clojure
(eacl/can? acl subject permission resource)
=> true | false
```

The other primary API call is `lookup-resources`, e.g.

```clojure
(def page1 (eacl/lookup-resources acl
             {:subject       (->user "alice")
              :permission    :view
              :resource/type :server
              :first         2})) ; defaults to 1000.
page1
=> {:data        [{:type :server :id "server-1"}
                  {:type :server :id "server-2"}]
    :page-info   {:start-cursor       "..."
                  :end-cursor         "..."
                  :has-next-page?     true
                  :has-previous-page? false}
    :cached?     boolean
    :cache-basis ...}
```

To query the next page, pass the `:end-cursor` from page1 as `:after`:

```clojure
(def page2 (eacl/lookup-resources acl
             {:subject       (->user "alice")
              :permission    :view
              :resource/type :server
              :first         2
              :after         (get-in page1 [:page-info :end-cursor])}))
page2
=> {:data        [{:type :server :id "server-3"}
                  {:type :server :id "server-4"}]
    :page-info   {:start-cursor       "..."
                  :end-cursor         "..."
                  :has-next-page?     true
                  :has-previous-page? true}
    :cached?     boolean
    :cache-basis ...}
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
and authenticated cursor walk. Permission lookups use the sealed plan's stable
first-discovery order, and relationship reads use backend tuple-index order.
These are pagination orders, not a global, cross-backend, or domain sort order.
Backward pagination returns the previous window; it does not reverse the result
order.

## Other backend quickstarts

### Datahike Quickstart

For Clojure/JVM applications backed by Datahike, add the Datahike adapter dependency to your `deps.edn` file:

```clojure
{:deps {dev.eacl/eacl-datahike {:mvn/version "8.0.0-RC-2026-09-12"}}}
```

```clojure
(ns my-eacl-datahike-project
  (:require [datahike.api :as d]
            [eacl.core :as eacl]
            [eacl.datahike.core :as eacl.datahike]))

; Create an in-memory Datahike database and install EACL's Datahike schema:
(def conn (eacl.datahike/create-conn
           [{:db/ident       :app/id             :db/valueType :db.type/string
             :db/cardinality :db.cardinality/one :db/unique    :db.unique/identity}]))

; Make an EACL client that satisfies the `IAuthorization` protocol:
(def acl (eacl.datahike/make-client conn
           {:object-id->lookup-ref (fn [id] [:app/id id])
            :entid->object-id      (fn [db eid] (:app/id (d/entity db eid)))}))

; Write your permission schema using SpiceDB schema DSL:
(eacl/write-schema! acl
  "definition user {}

   definition account {
     relation owner: user
     permission admin = owner
   }")

; Transact application entities with unique `:app/id` values:
(d/transact conn
  [{:app/id "user-1"}
   {:app/id "account-1"}])

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

EACL-created Datahike databases enable `:keep-history? true` by default so
exact tokens and cursors survive ordinary commit-record cutoff collection.
Pass `{:keep-history? false}` to `create-conn` only when lower write/storage
amplification is worth making exact reconstruction conditional on retained
commit records.

### DataScript Quickstart

For server-side or browser demos, use the DataScript adapter:

```clojure
{:deps {dev.eacl/eacl-datascript {:mvn/version "8.0.0-RC-2026-09-12"}}}
```

The example below runs on the JVM. For ClojureScript, also add the cache fork
to your application's dependencies; Maven cannot declare this Git dependency:

```clojure
com.github.theronic/cljs-cache
{:git/url "https://github.com/theronic/cljs-cache.git"
 :git/sha "4143cc036446a47f0c6dfd9f8dde90363835051c"}
```

```clojure
(ns my-eacl-datascript-demo
  (:require [datascript.core :as ds]
            [eacl.core :as eacl]
            [eacl.datascript.core :as eacl.datascript]))

(def conn (eacl.datascript/create-conn {:app/id {:db/unique :db.unique/identity}}))
(def acl (eacl.datascript/make-client conn
           {:object-id->lookup-ref (fn [id] [:app/id id])
            :entid->object-id      (fn [db eid] (:app/id (ds/entity db eid)))}))

(ds/transact! conn
  [{:db/id -1 :app/id "user-1"}
   {:db/id -2 :app/id "account-1"}])

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

EACL parses a documented subset of the SpiceDB schema DSL to define your authorization model. Use `eacl/write-schema!` to parse, validate, and transact your schema:

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
- **Empty-schema guard**: the public `eacl/write-schema!` rejects replacing a non-empty schema with zero definitions. The backend schema namespaces expose a lower-level `{:allow-empty-schema? true}` option for an intentional wipe; direct use must also follow the cache-recovery rules because it bypasses the EACL client.
- **Unsupported feature detection**: rejects SpiceDB features unsupported by EACL (see [Limitations](#limitations-deficiencies--gotchas))

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

SpiceDB uses strings for subject and resource IDs. Internally, EACL uses backend-native entity IDs, but you can configure EACL to convert internal IDs to external, and vice versa.

*Note*: Internal Datomic eids should not be exposed to consumers, because those eids are not guaranteed to be stable after a DB rebuild.

Every adapter's `make-client` takes config `:entid->object-id` & `:object-id->lookup-ref`, which are functions that convert between internal entity IDs and external object IDs.

It is common to attach a unique UUID to secured entities for external use, e.g. `[:your/uuid "554dbf64-70cc..."]`, but you can use internal eids and convert them at the call-site. This attribute should have the property `:db/unique :db.unique/identity`.

Here is how to configure that translation when construction an ACL client via `make-client`, when using Datomic Pro:

```clojure
(def acl (eacl.datomic.core/make-client conn
           {:entid->object-id      (fn [db eid] (:your/uuid (d/entity db eid)))
            :object-id->lookup-ref (fn [obj-id] [:your/uuid obj-id])}))
```

The default options are to use the built-in EACL string attr `:eacl/id`, but you can use the internal Datomic eids with the following "identity" functions:
```clojure
(def acl (eacl.datomic.core/make-client conn
           {:entid->object-id      (fn [_db eid] eid)
            :object-id->lookup-ref (fn [obj-id] obj-id)}))
```

`make-client` rejects unknown options with `{:type :eacl/invalid-config}`.

Expression admission limits are immutable client configuration. Overrides are
merged with EACL's calibrated defaults and checked against portable hard
ceilings:

```clojure
(def acl (eacl.datomic.core/make-client conn
           {:expression-limits
            {:maximum-source-nodes     32768
             :maximum-source-depth     64
             :maximum-expression-bytes 262144}}))
```

The profile applies to schema reads and writes performed by that client. It is
also accepted by direct schema writers and the explicit Datomic v7-to-v8
permission migration. Two Peers may deliberately use different profiles: a
stricter Peer can reject a schema accepted by a looser Peer, but schemas
accepted by both have identical permission meaning. The profile is never
written to the database and never coordinates Peers.

All backends issue non-expiring cursors by default. Configure a positive
`:cursor-ttl-seconds` only when the application deliberately wants a maximum
pagination age; cache capacity is independent of cursor age.

## Caching

EACL relationship generations and canonical dependency proofs determine whether
a completed value remains valid after an unrelated write. Storage itself is
ordinary keyed retention: exact snapshot identity or a complete forward-valid
proof is part of the key, rather than hidden in a custom generation-aware backend.

Recursive traversal must retain deduplication and continuation state that is
too large and too private to place in a public cursor. The shared caches provide:

1. Faster traversal of cyclic paths with continuation checkpoints (they are memory-intensive), and
2. Reuse exact valid answers on hot queries, which lowers compute, memory and latency.

How the EACL cache works:

1. Select snapshot _S_, with basis `B >= T`.
2. Look up one flat composite key for exactly `B`.
3. For an ordinary current basis only, look for a proof-equivalent managed
   answer computed at an earlier or equal revision.
4. Otherwise, compute against `S`.

Suppose:

```text
token floor T = 100
selected basis B = 120
```

EACL first looks for an answer computed at database version 120. For a current
database read, it can also reuse an earlier answer if the permission schema
and all relationships that answer depends on are unchanged. Otherwise it
computes the answer at version 120.

Historical reads only reuse answers from the selected historical version.
Eviction affects performance; it does not turn an allowed request into a
denied one. Cache retention also does not control when a share expires.

### Cache Coherence

Use EACL's APIs, or submit EACL-produced transaction data intact, when changing
relationships or permission schemas. Use the supported deletion helpers for
secured entities. These writes give the cache the information it needs to
recognize changes. Ordinary application data can use normal database writes.

Start with the default cache. To bound retained entries:

```clojure
{:cache {:max-entries            2048
         :denotation-max-entries 4096}}
```

These are entry counts, not byte limits. To bypass cached authorization results
for a request, pass `:cache? false`.

If you bypass EACL to change authorization data, stop affected requests,
repair the data, and expire every affected client before serving again.
After a database restore or history replacement, coordinate that reset across
all Peers. Expiring a cache does not repair dangling relationships.

See the [cache guide](docs/cache.md) for configuration, statistics, persistence,
and recovery.

### Consistency and Zed tokens

A Zed token identifies a database revision. EACL returns one from a mutation
so another request can ask to see that write.

```clojure
(require '[eacl.spicedb.consistency])

(def write-result (eacl/create-relationship! acl alice :owner report))
(eacl/can? acl alice :view report
           (eacl.spicedb.consistency/at-least-as-fresh (:zed/token write-result)))
```

| Mode | Use it to |
| --- | --- |
| `minimize-latency` (default) | Read the current database visible to this Peer. |
| `fully-consistent` | Synchronize before selecting a database version, where the backend supports it. |
| `at-least-as-fresh` | Read a version that includes an earlier write. |
| `at-exact-snapshot` | Read the exact historical version named by a token. |

Treat tokens as opaque strings. A token applies only to its original database
and lifecycle. For tokens returned by a browser, the server should normally
choose `at-least-as-fresh`; letting a caller select old authorization state
requires a separate application policy.

Datomic can reconstruct historical versions from retained history. Datahike
needs temporal history or a retained commit. DataScript and Datalevin do not
support arbitrary historical selection. An unavailable version causes an
error; EACL does not silently choose another one.

Peers accepting the same cursors or tokens need shared keys. See
[security keys](docs/security-keyrings.md) for setup and rotation, and the
[backend guide](docs/v8-backend-modules-and-upgrade.md) for consistency limits.

### Unknown object IDs

EACL's bundled situated backends require object IDs to resolve to application
entities:

- **Reads** (`can?`, `lookup-resources`, `lookup-subjects`, `count-resources`, `count-subjects`, `read-relationships`) treat unknown IDs as matching nothing: `can?` returns `false`, lookups and reads return empty pages.
- **Writes** (`write-relationships!` and friends) throw `ex-info {:type :eacl/unknown-object, :object {:type … :id …}}` — a relationship to a nonexistent entity is unsatisfiable, and failing loudly beats minting ghost entities or raw Datomic errors.

If a lookup result has no external ID in the selected database,
`lookup-resources` and `lookup-subjects` raise
`{:type :eacl/unresolvable-object}` and identify every offending internal ID
instead of silently omitting authorized objects. This usually indicates a
dangling relationship left by retracting an entity before its relationships.
`read-relationships` still returns the damaged relationship half with a nil
ID so it can be repaired.

### Deleting a Secured Entity

> [!IMPORTANT]
> Do not call the backend's ordinary entity-retraction operation on a
> secured entity before removing its EACL relationships.

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
entity. It is idempotent. The Datomic implementation batches high-degree
cleanup; the Datahike, DataScript, and Datalevin implementations use one
transaction in their certified in-process topologies.

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
| Datalevin qualified embedded writer | Direct in-process function |

Datomic example:

```clojure
(require '[datomic.api :as d]
         '[eacl.datomic.safe-retraction])

;; Privileged, idempotent deployment step.
(eacl.datomic.safe-retraction/install! conn)

@(d/transact conn [[:eacl.fn/retractEntity [:app/id "acme"]]])
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

Use the backend's `support-descriptor` before choosing a
Datahike or DataScript deployment mode. Installation, direct-mode examples,
restore behavior, integrity reports, and repair tools are documented in the
adapter guides:

- [Datomic](modules/eacl-datomic/README.md#optional-atomic-entity-retraction)
- [Datahike](modules/eacl-datahike/README.md#optional-atomic-entity-retraction)
- [DataScript](modules/eacl-datascript/README.md#optional-atomic-entity-retraction)

## Schema Syntax

EACL parses a documented subset of the SpiceDB schema DSL. Use
`eacl/write-schema!` to define your schema.
EACL's parser requires each `relation` or `permission` declaration to end at a
newline; put the next declaration and the definition's closing brace on a later
line. Empty definitions may still use the compact `definition user {}` form.

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

Create relationships between existing entities with
`eacl/create-relationships!`. To combine relationship changes with application
data, see [atomic writes](docs/atomic-writes.md), including the published
release's limitation for new entities and tempids.

## Limitations, Deficiencies & Gotchas:

- Caveats use a bounded CEL subset. JVM clients need the optional
  `eacl-caveats-jvm` evaluator; ClojureScript clients must supply a compatible
  evaluator. See [supported expressions and limits](docs/caveats.md).
- When relationships expire, stale cursors are invalidated and you'll get an
  `:eacl.pagination/restart-required` error. Start the lookup again without the
  expired cursor. When using an explicit EACL snapshot, including one selected
  with `at-exact-snapshot`, cursors keep working against relationships that are
  valid at the snapshot's captured evaluation time.
- *Exact snapshots require backend history:* `at-exact-snapshot` and continued
  cursors require the backend to reconstruct the selected database value.
  Ordinary Datomic history and history-enabled Datahike do not age-expire.
  History-disabled Datahike can lose a conditionally retained commit and then
  returns snapshot-unavailable rather than silently using a newer value.
- *History destruction is a lifecycle boundary:* Datomic excision and
  Datahike purge/cutoff, branch force, reset, restore, or equivalent destructive
  replacement require quiescing affected traffic, completing the operation,
  rotating the shared source lifecycle and affected clients/caches, and then
  resuming with deliberate token/cursor key-version policy.
- SpiceDB `subject#relation` subject sets are not supported. Public operations
  reject any object with a non-nil `:relation` as
  `:eacl/unsupported-subject-relation`; EACL never silently treats it as the
  base `type:id` object. Model group membership with explicit group
  Relationships and arrow permissions when that expresses the required
  semantics.
- *Expansion is structural, not a membership proof:* permission trees preserve
  relation, permission, union, intersection, directed exclusion, and arrow
  boundaries. Use `can?` for an authorization decision.
- *Cache coherence requires EACL authorization writers:* Bypassing EACL for
  schema, relationship, secured identity, or deletion mutations can
  leave cached answers stale. Stop affected traffic, repair the data, and
  expire every affected client before resuming.
- *Deleting entities:* Native entity retraction does not remove the
  relationship stored at the other endpoint. Delete relationships first with
  `delete-object!`, or use the optional safe-retraction function — see
  [Deleting a Secured Entity](#deleting-a-secured-entity).
- *Recursive permissions have safety limits:* use `:count-limit` to bound
  counts, and raise recursive traversal limits only after load testing. If a
  cached continuation is unavailable, EACL may replay earlier traversal work
  to continue a cursor.
- *Return order:* EACL makes no global, lexical, or cross-backend ordering
  promise. For a fixed query and authenticated cursor walk, permission lookups
  use the sealed plan's stable first-discovery order and relationship reads
  use backend tuple-index order. This stability is sufficient for a cursor
  walk with no movement or duplicates; sort by a domain key after reading if
  presentation order matters.

## Differences from SpiceDB

EACL follows SpiceDB's schema vocabulary and shared authorization semantics,
but it is not a byte-for-byte or operational clone:

- Result order is backend-defined. Compare lookup and relationship results as
  sets unless your application explicitly sorts them; never compare EACL and
  SpiceDB page membership or cursor bytes.
- EACL cursors bind the selected native revision and its dependency/order
  proof. A cursor walk stays on that database snapshot. Qualified client-targeted
  cursors capture fresh time and require restart when their temporal certificate
  ends; explicit snapshots pin historical time. Neither mode silently rebases
  its page boundary. Unavailable native history fails closed.
- Omitted consistency means `:minimize-latency`. EACL selects the current
  immutable database value visible to the local backend connection. SpiceDB may use
  an optimized cached revision, so freshness can differ. Use each backend's
  own causal token with `at-least-as-fresh` or `at-exact-snapshot` when the
  distinction matters; tokens and cursors are backend-local.
- EACL provides `count-resources`, `count-subjects`, a controllable EACL result
  cache, and `delete-object!`, which removes both stored Relationship halves.
  Qualified deletion uses bounded native transactions; each transaction removes
  both endpoint values and their owned qualifier together. These operations do
  not have direct SpiceDB API equivalents.
- V8 supports [Caveats and expiring Relationships](docs/caveats.md), including
  conditional results and an exclusive UTC-millisecond expiry. Its bounded CEL
  profile is a subset of SpiceDB's expression language; wildcard subjects and
  subject relations remain unsupported. Qualified activation requires upgrading
  every serving Peer first.
- EACL evaluates relationship cycles as a fixed point and has no separate
  dispatch-depth limit for checks, lookups, and counts. These operations remain
  subject to configured traversal work limits. SpiceDB uses a configurable
  dispatch-depth limit, which defaults to 50 and can return a maximum-depth
  error for deep or cyclic data, so the two systems can differ on those graphs.
  Only `expand-permission-tree` refuses cycles (`:eacl.permission-tree/cycle-detected`)
  and depth beyond `:permission-tree-limits` (`:max-depth 50` by default).
- Object identifiers are arbitrary non-empty strings and schema names follow
  the parser's grammar rather than SpiceDB's exact identifier and name
  grammars. A schema or dataset that must also load into SpiceDB should follow
  SpiceDB's stricter identifier and schema-name rules rather than relying on
  EACL's broader parser.
- A relation name is accepted only in the `:permission` slot of
  `expand-permission-tree`; `can?`, `check-permission`, the lookups and the
  counts require a permission (SpiceDB accepts either).
- A relationship filter containing `:subject/id` must also contain
  `:subject/type`. This fails closed instead of interpreting one external ID
  across every subject definition.

## Funding

Some of this open-source work was generously funded by my former employer, [CloudAfrica](https://cloudafrica.net/).

# Licence

- EACL is free and open-source, licensed under the Eclipse Public License v2.0.

See [Caveats and expiring Relationships](docs/caveats.md) for the v8 public APIs, optional JVM evaluator, trusted-clock and cursor semantics, and coordinated rollout.
