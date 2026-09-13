# EACL cache

EACL caches completed authorization results to avoid repeating work. Each
client has its own bounded cache. Leave `:cache` out of `make-client` to use
the defaults.

## How cached answers stay current

Every read selects a database version. EACL first looks for an answer from
that exact version. For a current read, it may reuse an earlier answer when
the backend can prove that the permission schema and all relevant relationships
are unchanged. If it cannot establish that, it evaluates the request again.

This relies on using EACL APIs, or submitting EACL-produced transaction data
intact, for authorization changes. Ordinary application writes do not need to
go through EACL. Changes to secured IDs, relationships, permission schemas,
and secured-entity deletion do.

A missing or evicted cache entry causes recomputation. It does not change
permissions. Time-dependent answers also have a validity interval: reaching a
share's deadline makes an old answer unusable even if it remains in memory.
See [expiration](caveats.md#expiring-access).

## Capacity and page retention

Pass cache configuration to your backend's `make-client`:

```clojure
{:cache {:max-entries            2048
         :denotation-max-entries 4096}}
```

| Option | Meaning |
| --- | --- |
| `:max-entries` | Capacity for each completed-answer, rendered-page, continuation, and cursor store; default 1,024. |
| `:denotation-max-entries` | Capacity for cached Boolean sub-results used during evaluation. |
| `:telemetry?` | Whether to collect cache counters; default `true`. |

Capacities are positive entry counts, not byte limits or one combined memory
budget. A large result takes more memory than a small one. Pages of up to
1,000 results can be cached; larger pages are returned normally but are not
retained as completed pages.

To disable the client authorization cache:

```clojure
(require '[eacl.cache]
         '[eacl.datomic.core :as eacl.datomic])

(def acl (eacl.datomic/make-client conn
           {:cache                 eacl.cache/no-cache
            :object-id->lookup-ref (fn [id] [:app/id id])
            :entid->object-id      (fn [db eid] (:app/id (datomic.api/entity db eid)))}))
```

This uses the quickstart's `:app/id` mapping. Add your security options when needed.
Unknown options are errors. `:remember-answers` and `:ttl-ms` are not supported.
Use relationship expiration to end a share and `:cursor-ttl-seconds` to limit
the age of newly issued cursors.

## Per-request controls

```clojure
(eacl/check-permission acl
  {:subject alice :permission :view :resource report :cache? false})
```

| Option | Effect |
| --- | --- |
| `:cache? false` | Bypass shared authorization-result reads and writes for this request. |
| `:populate-cache? false` | Read existing entries, but do not publish new authorization results. |

EACL can still reuse work within the request. Parsed-schema and cursor-codec
caches are separate infrastructure. Failed or incomplete evaluations are not
cached as denials.

## Continuations and cursors

A cursor identifies where to continue one query at its selected database
version. EACL may retain traversal work in memory to make the next page faster.
If that work is evicted, it may need to replay earlier traversal steps.

- Eviction does not authorize switching to a newer database version.
- If the required history or continuation guarantee is unavailable, EACL
  returns an error.
- Cursors do not expire by age unless you configure `:cursor-ttl-seconds`.
- Expiring relationships can require a new lookup even when the cursor has no
  age limit; see [qualified cursors](caveats.md#qualified-cursors).

Keep cursor keys available for as long as you need to accept their cursors.
See [security keys](security-keyrings.md).

## Lifecycle and clearing

The Datomic, Datahike, and DataScript backend namespaces provide these operations:

| Operation | Use it to |
| --- | --- |
| `expire-cache!` | Replace the whole cache and rotate the source lifecycle. |
| `cache-stats` | Inspect current entry counts and cache counters. |
| `refresh-metrics!` | Drop derived schema artifacts and their metrics; pass `{:eager? true}` to rebuild from the permission schema. |

An in-flight request may finish using its old cache. `refresh-metrics!` does
not scan relationships or repair data.

A source lifecycle distinguishes a database history from a replacement of that
history. When multiple Peers exchange cursors or tokens, pass the same new
lifecycle UUID to every affected `expire-cache!` call. Datalevin requires a
durable lifecycle change and a replacement client instead of process-local
expiry; see [backend recovery](v8-backend-modules-and-upgrade.md#cache-and-mutation-rules).

## Coherence and recovery

After an unsupported authorization write, database restore, reset, or history
replacement:

1. Stop affected authorization traffic on every Peer.
2. Repair the data through supported APIs or follow the database recovery procedure.
3. Expire or replace every affected client. Coordinate the lifecycle when
   Peers exchange tokens, cursors, or cache snapshots.
4. Resume traffic.

Cache expiry does not repair dangling relationships. Rewriting an unchanged
permission schema is not a cache flush.

Custom ID converters stay local to one client unless you configure a stable
`:adapter-fingerprint` and `:adapter-deterministic? true`. Only make that
promise when every participating Peer uses the same deterministic, unambiguous
mapping. Proof-based cursor reuse also requires `:identity-immutable? true`.
See [ID configuration](../README.md#eacl-id-configuration).

## Authenticated cache snapshots (v8)

Persisted cache snapshots can reduce repeated work after a restart, for example
in a serverless host. They are optional; the database remains authoritative.

```clojure
(require '[eacl.datomic.core :as eacl.datomic])
(def bounds {:max-entries 5000})
(def saved (eacl.datomic/export-authenticated-cache-snapshot acl bounds))
;; Store `saved`, then load it in a process configured with the same keys.
(eacl.datomic/restore-authenticated-cache-snapshot! acl saved bounds)
```

The envelope authenticates the data but does not encrypt it. Protect its
storage if the contents are confidential. `:maximum-size` can lower the
16 MiB encoded-size ceiling.

Unknown keys, retired keys, malformed data, and invalid authentication cause a
cache miss. Failed restore leaves the current cache intact. Successful restore
replaces it after validation. Imported entries depend on the verifying key;
retiring that key makes them unusable. Imported entries and results derived
from them are not re-exported as locally trusted answers.

## Portable cache snapshot v2

The decoded `export-cache-snapshot` and `restore-cache-snapshot!` APIs are also
available. Use them only when your application authenticates and size-bounds
stored bytes before decoding them. Prefer the authenticated APIs above when
EACL should handle that boundary.

Exports contain completed answers and Boolean sub-results. They omit database
values, rendered pages, traversal state, cursor state, metrics, and private
cache bookkeeping. Snapshot v1 is not accepted.

`cache-content-revision` is a process-local hint that exported content may have
changed. It can advance without a portable change; compare exports if you need
to suppress every redundant upload.

## Observability

`cache-stats` reports entry counts, capacities, hits, misses, publications,
bypasses, and failures to obtain reuse evidence. These are diagnostics, not
proof that an answer is valid. Concurrent requests can change the counts while
you read them.

## Storage strategy

The JVM uses Caffeine; ClojureScript uses the pinned `cljs-cache` fork. Their
eviction policies differ. Caffeine's capacity settles during maintenance, so
concurrent writes can briefly exceed it. Concurrent cache misses may compute
the same request independently.

For backend-specific consistency and recovery rules, see
[consistency and cache operations](v8-consistency-cache-operations.md).
