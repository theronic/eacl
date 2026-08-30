# 🦅 **EACL**: Enterprise Access ControL

EACL is a situated [ReBAC](https://en.wikipedia.org/wiki/Relationship-based_access_control) authorization library inspired by [SpiceDB](https://authzed.com/spicedb), built in Clojure and backed by [Datomic Pro](https://www.datomic.com/), [Datahike](https://datahike.io/), [DataScript](https://github.com/tonsky/datascript/), or a qualified embedded [Datalevin](https://datalevin.org/) deployment.

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

## Is it any good?

Yes.

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
> EACL is [available on Clojars](https://clojars.org/dev.eacl/). Use the `8.0.0-SNAPSHOT`.
> An official v8.0.0 release should be available by end-August 2026.

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
(eacl/can? acl (->user "alice") :view (->server "server1") consistency/fully-consistent)
=> true | false
```
If you need cache provenance, use `check-permission` instead of `can?`, otherwise they are equivalent:

```clojure
(eacl/check-permission acl
  {:subject     subject
   :permission  permission
   :resource    resource
   :consistency consistency/fully-consistent})
=> {:allowed? true, :cached? boolean, :cache-basis ...}
```

### Lookups

"Which `<resources>` does `<subject>` have `<permission>` on, as-of `<10 seconds ago, or newer>`?"

```clojure
(eacl/lookup-resources acl
  {:subject       subject
   :permission    permission
   :resource/type resource-type
   :first         page-size N ; or :last N
   :cursor
   :consistency   (consistency/at-least-as-fresh token-10s-ago)})
=> {:data [{:type :product :id "product-1"}
           {:type :product :id "product-7"}
           ...
           {:type :product :id "product-63"}]
    :page-info ...
    :cached? true|false>
    ...}
```

The `:consistency` argument is optional. The default is `minimize-latency`,
which means _locally-consistent_ to the Peer. `at-least-as-fresh` selects an
appropriate backend basis directly and requires no cache-checkpoint option.


```clojure
(def token-10s-ago (datomic/zed-token-at-least-seconds-ago acl 10))

(eacl/lookup-resources acl
  {:subject       (->user "alice")
   :permission    :view
   :resource/type :product
   :first         50
   :consistency   (consistency/at-least-as-fresh token-10s-ago)})
=> {:data [{:type :product :id "product-1"}
           {:type :product :id "product-7"}
           ...
           {:type :product :id "product-63"}]
    :page-info ...
    :cached? true|false>
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

An EACL `acl` is a live source and, unless configured read-only, a writer. An
EACL snapshot is a retained immutable authorization target. Every public read
accepts either target; writes require a writable `acl`.

Capture current once when several reads must share one basis:

```clojure
(eacl/with-snapshot [s (eacl/snapshot acl)]
  [(eacl/can? s alice :view document)
   (eacl/lookup-resources s
     {:subject alice :permission :view :resource/type :document})])
```

Capture performs one source acquisition. Reads through `s` perform zero source
acquisitions, and `with-snapshot` releases the basis in `finally`. Manual
retention uses `eacl/release!`; release is idempotent, and any subsequent read
fails with `:eacl/snapshot-released`. Datalevin snapshots hold native LMDB read
transactions and are thread-affine, so keep them bounded and release them on
the acquiring platform thread. Datalevin's optional
`:maximum-snapshot-retention-ms` client setting fails closed on the next
snapshot access, releases an owned reader, and reports
`:eacl/snapshot-retention-exceeded`.

Select through an `acl` with an explicit descriptor:

```clojure
(def s (eacl/snapshot acl (consistency/at-exact-snapshot token)))
```

On a retained snapshot, consistency is an assertion, never a request to find a
different database value. `minimize-latency` evaluates immediately;
`at-least-as-fresh` evaluates only when the snapshot satisfies the authenticated
floor; `at-exact-snapshot` evaluates only when the token names that basis; and
`fully-consistent` fails with `:eacl.consistency/selection-required`. Select a
new snapshot through the `acl` when an assertion fails.

Caller-owned native database values are deliberately outside the public
authorization boundary. Datomic cannot distinguish a committed database from
one returned by `d/with` when database id, basis `t`, and `:db/txInstant`
collide. Consequently there is no public backend constructor that wraps a raw
`d/with`, `d/filter`, `d/as-of`, `d/since`, or `d/history` value. Calling EACL
implementation namespaces to inject one forfeits cache-coherence guarantees.

Use explicit EACL speculation instead. Relationship helpers preserve the
committed writer's validation, paired tuples, guards, and relation stamp:

```clojure
(eacl/with-snapshot [base (eacl/snapshot acl)]
  (let [tx (eacl/tx-relationship
             base :delete alice :banned document)]
    (eacl/with-snapshot [prospective (eacl/with base tx)]
      (eacl/can? prospective alice :view document))))
```

`eacl/with` is composable and accepts backend-native transaction data. Its
actual emitted transaction datoms determine the cumulative relationship
effects, including transaction-function expansion:

```clojure
(eacl/with-snapshot [s1 (eacl/with base tx-1)]
  (eacl/with-snapshot [s2 (eacl/with s1 tx-2)]
    (eacl/check-permission s2 demand)))
```

Prospective permission-schema changes use the same replacement planner as
committed `write-schema!`:

```clojure
(eacl/with-snapshot [prospective
                     (eacl/with-schema base candidate-schema)]
  (eacl/can? prospective alice :view document))
```

The default orphan policy is `:error`. A large in-memory test may retain tuple
data without counting or retracting it; removed relation definitions make the
tuples semantically inert, and bounded warnings are available explicitly:

```clojure
(eacl/with-snapshot [prospective
                     (eacl/with-schema
                      base candidate-schema
                      {:orphan-policy :retain-inert})]
  (eacl/speculative-diagnostics prospective))
```

`:retain-inert` is speculative-only. Committed `write-schema!` always preserves
the no-orphan invariant. Speculative snapshots may read validated committed
proofs for wholly disjoint dependencies, but never read the native exact tier
and never publish computed values to any persistent cache. Use `eacl/basis`
for public basis metadata and `eacl/basis-token` for the authenticated committed
root token.

For reader-Peer session pinning, let the writer return a basis token with its
mutation response, select that exact basis once on the reader, and retain the
snapshot for the session. Subsequent authorization reads then make no current
head request. Datomic, Datahike, and DataScript default to the portable source
lifecycle `"eacl/initial"`; rotate it explicitly with `expire-cache!` after a
restore, reset, force-move, or history replacement. Datalevin has no universal
safe default and requires an externally persisted `:source-lifecycle` plus
shared token key material at `make-client`.

Construct a source-only deployment with `{:read-only? true}`. Reads and
snapshot selection remain available; every mutation fails before planning or
submission with `:eacl/unsupported-capability` and `:capability :write`.

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

Note that EACL has [Limitations](#limitations-deficiencies--gotchas) compared to SpiceDB, mainly:
- No [Caveats](https://authzed.com/docs/spicedb/concepts/caveats) yet (needed for ABAC),
- and a few other minor differences.

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
(def token-10s-ago (datomic/zed-token-at-least-seconds-ago acl 10))

(eacl/can? acl (->user "alice") :view (->video "my-video")
  (consistency/at-least-as-fresh token-10s-ago))
```

This also works for lookups, like listing video resources:
```clojure
(eacl/lookup-resources acl
  {:subject       (->user "alice")
   :permission    :view
   :resource/type :video
   :consistency   (consistency/at-least-as-fresh token-10s-ago)})
```

However, for destructive actions, e.g. permanently deleting a video, we will want to make 100% sure the user is allowed to do that. For that we can use `fully-consistent`:

```clojure
(eacl/can? acl (->user "alice") :delete (->video "my-video")
  consistency/fully-consistent) ; this will block on (d/sync conn)
```

Most of the time, we will use `consistency/minimize-latency`, which uses what is locally-consistent to the Peer:

```clojure
(eacl/can? acl (->user "alice") :view (->video "my-video")
  consistency/minimize-latency)
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
- Forward subject->resource tuple: `:eacl.v7.relationship/subject-type+relation+resource-type+resource`
- Reverse resource->subject tuple: `:eacl.v7.relationship/resource-type+relation+subject-type+subject`

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

- `:eacl/id` uniquely identifies Relations & Permissions. It's a string to match SpiceDB IDs, but you can also use it for external ID. Like in SpiceDB, Some IDs are reserved by EACL internals (todo: document EACL ID prefixes).
- `:eacl/schema-string` stores a valid schema string was written via `eacl/write-schema!`.
- `:eacl/schema-version` track the schema revision in Datomic Pro.
- `:eacl/schema-generation` and `:eacl/schema-write-fence` track schema writes in Datahike and DataScript. Datalevin uses scalar `:eacl.datalevin/schema-generation` and `:eacl.datalevin/schema-write-fence` values in its native `max-tx` domain.
- `:eacl/storage-version` identifies Datomic's current Relationship storage model, e.g. version 7 (current).
- `:eacl/permission-storage-version` identifies Datomic's canonical permission representation (version 8).
- `:eacl.fn/assert-relation-unused` is a Transactor function in Datomic that guards removing Relations with active Relationships (to avoids orphaned Relationships).

## Performance

- EACL is not meant for hyperscalers.
- The goal for EACL is to handle 100M Relationships with good performance and remain suitable for real-time UIs, due to its situated nature.
- EACL is internally benchmarked against 1M Relationships, tested against a real-world, recursive schema with e2e latency @ ~1-40ms per query (incl. hydration), depending on schema & query complexity.
- EACL makes no strong performance claims at this time, but EACL should be as fast as, or faster than, SpiceDB, for small-to-medium workloads.
- As load increases, you can scale Datomic Peers horizontally and even dedicate Peers to EACL as-needed.
- EACL does not support all SpiceDB features (yet). Please refer to the [limitations section](#limitations-deficiencies--gotchas) to decide if EACL is right for you.
- The EACL [cache](#caching) is stored in memory per client with standard LRU
  eviction. Custom cache providers are intentionally unsupported because they
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
{:deps {dev.eacl/eacl-datomic {:mvn/version "8.0.0-SNAPSHOT"}}}

;; Datahike
{:deps {dev.eacl/eacl-datahike {:mvn/version "8.0.0-SNAPSHOT"}}}

;; DataScript
{:deps {dev.eacl/eacl-datascript {:mvn/version "8.0.0-SNAPSHOT"}}}

;; Datalevin (coordinate reserved; publication remains gated)
{:deps {dev.eacl/eacl-datalevin {:mvn/version "8.0.0-SNAPSHOT"}}}

;; Core-only consumers and backend authors (you typically won't need this)
{:deps {dev.eacl/eacl {:mvn/version "8.0.0-SNAPSHOT"}}}
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

Pass the same `:java-release` to `prep` and `jar` or `install`. The default is
Java 26; source builds may target Java 8 through Java 26, subject to their
backend and application dependencies. See [formal/README.md](formal/README.md)
for tool versions and the full verification commands.

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
=> {:data [relationships...]
    :page-info {...}
    :cached? boolean
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

- Schema names are validated: unknown definitions, relations or bad subject types will fail with `:eacl/unknown-definition` / `:eacl/unknown-relation-or-permission`.

Relationship Conflicts?
 
- `:create` will fail with `:eacl/relationship-conflict` if the same if the same Relationship already exists.
  - Datomic: a transactor-side relation stamp CAS with re-planning; DataScript and Datahike with the default in-process writer: a transaction function,
  - Two racing `:create`s of one relationship produce exactly one success.
  - A Datahike remote writer cannot transport a transaction function and keeps the plan-time check only
  - `:touch` is idempotent. Repeating one operation for the same relationship inside a batch has the same outcome as submitting it once (`:create` still conflicts when the relationship existed before the batch); mixing different operations for the same resolved relationship throws `:eacl/invalid-relationship-update-batch` before submission.
- `(eacl/create-relationships! acl relationships)` simply calls `write-relationships!` with `:create` operation.
- `(eacl/delete-relationships! acl relationships)` simply calls `write-relationships!` with `:delete` operation.
- `(eacl/delete-object! acl object) => {:zed/token "eacl_z4_...", :retracted-datoms n}` is a convenience helper that removes every relationship touching `object`, in both directions. `n` counts relationship datoms actually retracted by the committed transactions. On Datomic the retractions are committed in batches of 1,000 (a concurrent reader can observe a partially deleted object between batches); on DataScript and Datahike they are one atomic transaction. Consumers are expected to delete relationships before retracting a secured entity — see [Deleting a Secured Entity](#deleting-a-secured-entity).

All list APIs use the v8 Relay pagination contract:

- Forward: pass `:first` and optionally `:after`.
- Backward: pass `:last` and optionally `:before`.
- Responses include `:page-info` with `:start-cursor`, `:end-cursor`, `:has-next-page?`, and `:has-previous-page?`.
- Lookup cursors paginate in the sealed plan's stable first-discovery order; a page size change is rejected as an incompatible cursor rather than silently re-windowed.

### Aggregate authorization

Use `eacl/check-permissions` for an ordered vector of point decisions that must
share one snapshot and one request budget. For permission-filtered relationship
pages, use `read-relationships` with `:authorization` when the relationship set
is smaller, or `lookup-resources`/`lookup-subjects` with a direct
`:relationship` filter when the authorized set is smaller. Both routes use
bounded candidate windows, so a valid page may be short with
`:has-next-page? true` and `:bounded? true`; continue with its confidential,
query-scoped cursor.

See [Aggregate authorization](docs/aggregate-authorization.md) for the batch
contract, complete query examples, route-selection cost table, window and
cursor rules, cache provenance, schema-generation reuse, and performance
qualification. The checked-in host-class measurements are not a universal
sub-millisecond SLA.

### Deadlines and cooperative cancellation

Every bounded read accepts an optional per-request `:cancellation-token` in
addition to `:timeout-ms`. Create and cancel the token through the public EACL
API:

```clojure
(let [token (eacl/cancellation-token)]
  ;; Pass `token` to the HTTP/request owner before starting the read.
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
backend's explicit permission-only v7-to-v8 migration before constructing an
ordinary v8 client. Both reuse the existing relationship tuple attributes and
datoms without a relationship rebuild. See the
[v7-to-v8 migration guide](docs/migration-v7-to-v8.md).

### Permission-tree expansion

Expansion accepts exactly `:resource`, `:permission`, and the optional `:consistency`, `:timeout-ms`, and `:cancellation-token` keys:

```clojure
(eacl/expand-permission-tree
 acl
 {:resource (eacl/spice-object :document "readme")
  :permission :view
  :consistency consistency/fully-consistent
  :timeout-ms 5000})
=>
 {:expanded-at "eacl_z4_..."
  :tree-root
  {:expanded-object {:type :document :id "readme"}
   :expanded-relation :view
   :intermediate
   {:operation :union
    :children
    [{:expanded-object {:type :document :id "readme"}
      :expanded-relation :viewer
      :leaf {:subjects [{:type :user :id "alice"}]}}]}}}
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
deadlines, unknown root relations or permissions, cycles, codec failures,
adapter-contract failures,
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
(def page1
  (eacl/lookup-resources acl
    {:subject       (->user "alice")
     :permission    :view
     :resource/type :server
     :first         2})) ; defaults to 1000.
page1
=> {:data [{:type :server :id "server-1"}
           {:type :server :id "server-2"}]
    :page-info {:start-cursor "..."
                :end-cursor "..."
                :has-next-page? true
                :has-previous-page? false}
    :cached? boolean
    :cache-basis ...}
```

To query the next page, pass the `:end-cursor` from page1 as `:after`:

```clojure
(def page2
  (eacl/lookup-resources acl
    {:subject       (->user "alice")
     :permission    :view
     :resource/type :server
     :first         2
     :after         (get-in page1 [:page-info :end-cursor])}))
page2
=> {:data [{:type :server :id "server-3"}
           {:type :server :id "server-4"}]
    :page-info {:start-cursor "..."
                :end-cursor "..."
                :has-next-page? true
                :has-previous-page? true}
    :cached? boolean
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

## Quickstart

### Datomic Pro

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

; Install EACL's current Datomic Relationship schema:
@(d/transact conn schema/v8-schema)

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
; `eacl.core/spice-object` constructs a SpiceObject from `type`, `id`, and an
; optional subject relation. EACL queries do not support subject relations.

(def ->user (partial spice-object :user))
(def ->account (partial spice-object :account))
(def ->product (partial spice-object :product))

; Write some Relationships to EACL. For same-transaction entity and
; Relationship creation, use the explicit tx-relationship example below:
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
;                 :has-previous-page? false}
;     :cached? false
;     :cache-basis ...}
```

### Datahike Quickstart

For Clojure/JVM applications backed by Datahike, add the Datahike adapter dependency to your `deps.edn` file:

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

EACL-created Datahike databases enable `:keep-history? true` by default so
exact tokens and cursors survive ordinary commit-record cutoff collection.
Pass `{:keep-history? false}` to `create-conn` only when lower write/storage
amplification is worth making exact reconstruction conditional on retained
commit records.

### DataScript Quickstart

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
           {:entid->object-id (fn [db eid] (:your/uuid (d/entity db eid)))
            :object-id->lookup-ref (fn [obj-id] [:your/uuid obj-id])}))
```

The default options are to use the built-in EACL string attr `:eacl/id`, but you can use the internal Datomic eids with the following "identity" functions:
```clojure
(def acl (eacl.datomic.core/make-client conn
           {:entid->object-id (fn [_db eid] eid)
            :object-id->lookup-ref (fn [obj-id] obj-id)}))
```

`make-client` rejects unknown options with `{:type :eacl/invalid-config}`.

Expression admission limits are immutable client configuration. Overrides are
merged with EACL's calibrated defaults and checked against portable hard
ceilings:

```clojure
(def acl
  (eacl.datomic.core/make-client
   conn
   {:expression-limits
    {:maximum-source-nodes 32768
     :maximum-source-depth 64
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
pagination age; cache TTL and capacity remain independent of cursor age.

## Caching

EACL relationship generations and canonical dependency proofs determine whether
a completed value remains valid after an unrelated write. Storage itself is
ordinary keyed LRU: exact snapshot identity or a complete forward-valid proof
is part of the key, rather than hidden in a custom generation-aware backend.

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

``` 
token floor T = 100
selected basis B = 120
```
The first lookup checks the exact composite key for basis `B=120`. Exact and
managed mode are fields in complete keys stored in flat, count-bounded standard
LRUs; EACL has no nested retained-generation registry. Clojure uses
`org.clojure/core.cache`, and ClojureScript uses the pinned theronic
`cljs-cache` fork. Hits update the local LRU atom, so frequently used old
entries remain hot instead of being evicted FIFO-style.

I suspect SpiceDB does not support counting for the same reason. EACL currently supports unbounded counts because queries run on the Peer, but typically you want to pass a `:count-limit`.

`at-exact-snapshot` semantics always call `(d/as-of conn T)`. Historical bases
reuse only an identical exact composite key; managed proof reuse is
ordinary-current and forward-only.

Unlike SpiceDB, EACL cursors do not expire by default. History-capable
backends can reconstruct exact continuations; current-only DataScript and
Datalevin continuations fail closed after their selected snapshot is no longer
available.

### Cache Coherence

Cache coherence is only guaranteed as long as authorization mutations use EACL's supported APIs:

- Change schema with `eacl/write-schema!`.
- Add/retract relationships via EACL relationship APIs
- Retract secured entities via
  [:eacl.fn/retractEntity](#deleting-a-permissioned-entity).

Ordinary application datoms that do not affect authorization are unrestricted. If an application changes EACL schema or relationship storage directly, splits EACL transaction data, changes the identity of a secured object outside the documented contract, or leaves relationships behind during deletion, cached authorization results may be stale.

To recover after an unsupported authorization mutation:

1. Stop affected authorization traffic in every process.
2. Repair the schema, identity, or relationship data through a supported EACL path.
3. Expire or recreate every affected EACL client in every process.
4. Resume traffic only after repair and cache rotation are complete.

Cache expiry removes remembered answers; it does not repair ghost relationships. Rewriting an unchanged schema is not a cache flush.

Most applications need no cache configuration. You can disable caching for one client with `eacl.cache/no-cache`, but this is not recommended because recursive traversal will be slow:

```clojure
(require '[eacl.cache :as eacl-cache])

(def acl (eacl.datomic.core/make-client conn {:cache eacl-cache/no-cache}))
```

Cache capacities are entry counts, not byte estimates:

```clojure
(def acl
  (eacl.datomic.core/make-client
   conn
   {:cache
    {:max-entries 2048
     :denotation-max-entries 4096}}))
```

Completed pages above 1,000 result items are returned normally but are not
retained. The public page-size maximum remains 10,000.

Pass `:cache? false` to bypass the cache on a request:

```clojure
(eacl/can? acl
  {:subject    (->user "alice")
   :permission :view
   :resource   (->document "doc1")
   :cache?     false})
=> true|false
```

Use `eacl/check-permission` if you want cache provenance:

```clojure
(eacl/check-permission acl
 {:subject    (->user "alice")
  :permission :view
  :resource   (->document "doc1")})
;; => {:allowed? true, :cached? false, :cache-basis ...}
```

Inspect or expire a client through its backend API:

```clojure
(eacl.datomic.core/cache-stats acl)
(eacl.datomic.core/expire-cache! acl)
(eacl.datomic.core/refresh-metrics! acl)

(eacl.datahike.core/cache-stats acl)
(eacl.datahike.core/expire-cache! acl)
(eacl.datahike.core/refresh-metrics! acl)

(eacl.datascript.core/cache-stats acl)
(eacl.datascript.core/expire-cache! acl)
(eacl.datascript.core/refresh-metrics! acl)

(eacl.datalevin.core/cache-stats acl)
(eacl.datalevin.core/expire-cache! acl)
(eacl.datalevin.core/refresh-metrics! acl)
```

Datomic and Datahike can export the reusable authorization cache for a durable
host such as a Lambda deployment:

```clojure
(def bounds {:max-entries 5000})
(def revision (eacl.datahike.core/cache-content-revision acl))
(def snapshot (eacl.datahike.core/export-cache-snapshot acl bounds))

;; After loading and authenticating the external envelope:
(eacl.datahike.core/restore-cache-snapshot! acl snapshot bounds)
```

Snapshot v2 is deterministic flat process-neutral data. It excludes database
values, library-private LRU state, cursors, continuations, metrics, and
process-local identity tokens. The restore API accepts trusted, already decoded
immutable data: a host that persists bytes must authenticate the envelope and
enforce an encoded-byte limit before decoding it. Restore validates complete
keys, operation-specific values, proof envelopes, revisions, and entry capacity
while building fresh LRUs off-side, then atomically replaces the visible cache
lifecycle. Malformed, incompatible, or v1 snapshots leave the current cache
intact.
`cache-content-revision` is a conservative dirty hint that advances on
answer/denotation mapping changes but not on continuation, cursor, or
derived-schema retention, hits, or LRU touches. It never misses a portable
change, but may also advance for a process-local managed-to-exact promotion
that export omits; compare the deterministic export when suppressing every
redundant upload matters. The equivalent Datomic functions live in
`eacl.datomic.core`.

`refresh-metrics!` drops currently resident derived structural artifacts and
performs no relationship scan. The reset is point-in-time under concurrent
requests; a validated in-flight derivation may repopulate the LRU immediately.
Pass `{:eager? true}` to reread the bounded permission schema and repopulate
those artifacts deliberately.

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

Datomic exact selection treats an authentic same-source token ahead of the
local Peer as replica lag: it performs bounded `(d/sync conn T)` when needed,
verifies the returned basis, and always evaluates `(d/as-of db T)`. A locally
available `T` skips synchronization. Ordinary unreplaced Datomic history has
no EACL cursor-retention window.

EACL-created Datahike databases retain temporal history by default. External
history-enabled Datahike stores can reconstruct exact revisions after commit
record collection; history-disabled stores advertise only conditional exact
selection while a named commit is retained. DataScript and Datalevin do not
provide general historical snapshot reconstruction. If a backend cannot satisfy the
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

Portable cursors use the confidential `eacl_c5_` envelope: independently
derived AES-256-CTR and HMAC-SHA-256 keys, a random 96-bit nonce, and
authentication before payload parsing. Rotate a cursor authenticated-encryption
key before 2^32 cursor encryptions. Install the new key id for issuance first,
retain old keys for verification through the intended token lifetime, and then
retire them. EACL does not count per-key encryptions for you. The default keys
are client-local, so default cursors and tokens do not survive restarts or load
balancing.

See the [backend guide](docs/v8-backend-modules-and-upgrade.md) for exact
capabilities, synchronization timeouts, checkpoints, key rotation, and
recursive traversal controls.

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
  cursors require the backend to reconstruct the selected database value.
  Ordinary Datomic history and history-enabled Datahike do not age-expire.
  History-disabled Datahike can lose a conditionally retained commit and then
  returns snapshot-unavailable rather than silently using a newer value.
- *History destruction is a lifecycle boundary:* Datomic excision and
  Datahike purge/cutoff, branch force, reset, restore, or equivalent destructive
  replacement require quiescing affected traffic, completing the operation,
  rotating the shared source lifecycle and affected clients/caches, and then
  resuming with deliberate token/cursor key-version policy.
- SpiceDB `subject#relation` subject sets are not supported. Model group membership with explicit group Relationships and arrow permissions when that expresses the required semantics.
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
  proof. A cursor walk stays on that exact snapshot. If the backend cannot
  reconstruct it, EACL fails closed. A relevant write does not silently change
  page membership midway through a cursor walk.
- Omitted consistency means `:minimize-latency`. EACL selects the current
  immutable database value visible to the local backend connection. SpiceDB may use
  an optimized cached revision, so freshness can differ. Use each backend's
  own causal token with `at-least-as-fresh` or `at-exact-snapshot` when the
  distinction matters; tokens and cursors are backend-local.
- EACL provides `count-resources`, `count-subjects`, a controllable EACL result
  cache, and `delete-object!`, which removes both stored Relationship halves.
  Datomic commits high-degree deletion in batches of 1,000; Datahike and
  DataScript use one atomic transaction. These do not have direct SpiceDB API
  equivalents.
- EACL currently supports a smaller schema subset: unions, intersections,
  exclusions, and its documented arrow forms, but not caveats, wildcard
  subjects, expiration, or subject relations.
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
