# Physical backend audit

Status: exploration evidence, 2026-08-14. This file is ignored by Git and does
not change a production adapter or deployment.

## Scope and source pins

- EACL/Datahike source: the current `agent/stable-discovery-enumeration`
  worktree and `org.replikativ/datahike` 0.8.1759 resolved by the module.
- Tiered-store reference: `replikativ/datahike-saas-starter` commit
  `4569c1e1fa003ad87cac0c5e051c432f1a0a8f70`, authored 2026-07-17.
- JDBC probe artifact: `org.replikativ/konserve-jdbc` 0.2.97. The auxiliary
  source checkout was commit `5917244da1b384b04db710de01e07a50a1709991`.
- DynamoDB probe artifact: `org.replikativ/konserve-dynamodb` 0.1.32. The
  auxiliary source checkout was commit
  `a077f982eb52bc81b4a1201230203220d60f872c`.
- Object-store fixture: disposable MinIO at `127.0.0.1:19003`; every Datahike
  store used a random UUID and was deleted after the measurement.
- JDBC fixtures were disposable H2 2.1.214 databases and a PostgreSQL 15
  container bound to loopback. DynamoDB used the pre-existing local emulator,
  one random table, dummy credentials, and strongly consistent reads. All
  probe databases/tables and the PostgreSQL container were removed afterward.
  No production S3 bucket, DynamoDB table, or AWS credential was used.

## Datahike index path used by EACL

The current Datahike default index is `:datahike.index/persistent-set`. A direct
current database implements `seek-datoms` and `rseek-datoms` through lazy PSS
`slice` and `rslice`. The EACL adapter uses native EAVT tuple seeks for both
subject-to-resource and resource-to-subject projections, guards the exact
entity, attribute, tuple arity, and tuple prefix, and filters the exclusive or
inclusive terminal EID. It does not issue a Datalog query or realize a global
relationship relation for the current hot path.

The historical/filter-wrapper fallback is physically different: it reads the
exact endpoint-local attribute datoms, filters, and sorts that bounded result.
It is correct but is not the target fast path. Performance claims for direct
current databases must not be silently generalized to historical wrappers.

Datahike 0.8.1759 has two count-bounded cache maps in this path:

1. `datahike.store/add-cache-and-handlers` wraps the raw Konserve store with a
   cache at `:store-cache-size`;
2. the PSS `CachedStorage` has its own LRU with the same threshold.

The same decoded node can be referenced by both maps. This duplicates keys,
entries, LRU bookkeeping, and retention references, but does not necessarily
duplicate the node payload because both maps can point to the same object.
The threshold is an entry count, not a byte bound. Node sizes vary, so it is not
a defensible heap limit by itself. The demo correctly leaves Datahike's search
cache at zero; that cache is separate and is not the node/store cache that made
the repeated EACL request fast.

## Direct S3 / MinIO measurements

A 2,048-server fixture, 64 fixed scan descriptors, store cache 16, and exact
sequential oracle produced:

| injected latency | width | GETs | elapsed |
|---|---:|---:|---:|
| none | 1 | 40 | 162 ms |
| none | 2 / 4 / 8 / 16 / 32 | 42 / 48 / 56 / 64 / 71 | no monotone improvement |
| 10 ms each direction | 1 | 40 | 1,171 ms |
| 10 ms each direction | 2 / 4 / 8 / 16 / 32 | 42 / 48 / 56 / 66 / 72 | 748 / 739 / 765 / 842 / 1,021 ms |

The useful cold direct-S3 width in this fixture was 2--4. Larger widths caused
read amplification and eventually increased latency.

For 32 simultaneous copies of one cold descriptor, widths 1 / 2 / 4 / 8 / 16 /
32 issued 3 / 5 / 7 / 11 / 19 / 35 GETs and took 87 / 109 / 113 / 234 / 498 /
943 ms under injected latency. Datahike/Konserve does not provide the exact
request-local single-flight EACL needs. Equality-complete descriptor
coalescing is therefore required before dispatch, not an optional cache
optimization.

## Exact-terminal probe

The fresh nREPL on port 65314 reran a 4,096-value direct-S3 fixture with store
cache 1. Six trials at page widths 1, 8, and 64 compared:

1. consume exactly `P` values;
2. consume `P`, then issue the empty terminal scan;
3. request `P+1` values to detect exhaustion eagerly.

All 54 measurements returned the same `P` values and issued exactly two S3
GETs. The empty terminal scan caused no additional GET in this exact-terminal
fixture because the necessary PSS nodes were already materialized. Observed
elapsed times were mostly 2.3--5.5 ms on local MinIO and were too noisy to rank
the three strategies.

A second probe stopped guessing physical boundaries from logical ordinals. It
streamed the same 4,096-value index once, recorded every position where the S3
GET counter advanced, and then reconnected to compare `P=1` with `P+1`
immediately before each observed boundary. After the initial value, PSS loaded
a new node at values 136, 392, 648, and every 256 values thereafter through
3,720. All fifteen tested interior boundaries had the same result: `P` used two
GETs and `P+1` used three. Eager lookahead therefore performs one unnecessary
remote read whenever the undelivered value starts the next leaf.

Consequences:

- retain demand-lazy `P` for direct S3 initially;
- do not put a `P+1` choice into the public discovery-order ABI;
- allow a physical topology to choose `P+1` only after proving it saves a
  database round trip without speculatively loading another node or value;
- the interior probe closes the direct-S3 PSS loophole for this fixture, but
  does not establish terminal behavior on JDBC, DynamoDB, or real S3.

## LMDB over S3

Against the same delayed MinIO store, an initially empty LMDB frontend produced
the following cold curve for widths 1 / 2 / 4 / 8 / 16 / 32:

- elapsed: 1,168 / 749 / 599 / 416 / 249 / 193 ms;
- S3 GETs: 39 / 40 / 42 / 44 / 45 / 49.

After those exact nodes persisted in LMDB, repeat scans issued zero S3 GETs and
completed in roughly 0.7--1.7 ms median. Wider warm runs had scheduler outliers
and no established advantage over width one. Thirty-two identical cold
descriptors against the empty tier issued only 2--4 GETs, but concurrency did
not improve wall time. EACL descriptor coalescing is still required: the tier
reduces amplification after a cache fill; it is not an exact single-flight
contract.

The starter distinguishes two valid topologies that must not be conflated:

### Single authoritative writer/app

The demo's `:s3-lmdb` configuration is a tiered LMDB frontend over S3 with
`:write-policy :write-through`, `:read-policy :frontend-first`, and the same
store UUID on both layers. A sole `:self` writer updates its in-memory branch
head and writes both layers. On reconnect, Datahike's tiered `ready-store`
synchronizes the frontend from the backend. Under the single-writer invariant,
this can accelerate the combined app without caching a foreign mutable head.

### Streamed read replica

The starter's Tier 4 writer uses S3 directly. A peer uses
`{LMDB frontend, S3 backend}`, `:write-policy :frontend-only`, and receives the
mutable branch head plus new immutable nodes through the Kabel/Konserve stream.
The peer never writes shared S3. A non-streaming tiered reader is invalid: it
can cache the mutable branch head and serve a frozen snapshot indefinitely.

The reference tests explicitly guard active cache warming, handshake delivery
of the head, and restart onto an existing LMDB. They also document two prior
failure modes: a reconnect deadlock and an LMDB close/warm race that caused a
native crash. This stack is still described by its source as experimental or
beta.

A fresh streamed peer is not cheap. Its connect handshake walks reachability
and pushes every reachable node missing from its LMDB before exposing the head.
At this demo's scale that is a deployment/restart operation, not a request-path
optimization. The LMDB tier has no automatic eviction; it accumulates streamed
immutable history until the frontend is swept against the writer's reachable
set or wiped and rewarmed.

## JDBC measurements

The JDBC probe created 4,096 indexed tuple values, fixed the Datahike store
cache at one entry, captured one immutable database value per campaign, and ran
32 unique or 32 identical logical scan descriptors against a sequential oracle.
The injected-latency campaign slept 10 ms immediately before every backing
`SELECT`; it is a controlled read-amplification experiment, not a model of a
particular network distribution.

The PostgreSQL 15 loopback results were:

| campaign | width 1 | width 2 | width 4 | width 8 | width 16 |
|---|---:|---:|---:|---:|---:|
| unique, local: SELECTs / ms | 16 / 16.8 | 17 / 18.6 | 21 / 28.5 | 31 / 46.8 | 32 / 45.3 |
| unique, +10 ms: SELECTs / ms | 16 / 237.0 | 24 / 286.8 | 32 / 245.0 | 34 / 167.5 | 35 / 96.4 |
| identical, +10 ms: SELECTs / ms | 1 / 15.7 | 2 / 38.4 | 4 / 89.7 | 8 / 161.5 | 16 / 301.2 |

H2 showed the same shape rather than an anomalous PostgreSQL-specific result:

| campaign | width 1 | width 2 | width 4 | width 8 | width 16 |
|---|---:|---:|---:|---:|---:|
| unique, local: SELECTs / ms | 16 / 9.8 | 18 / 31.4 | 19 / 27.0 | 25 / 27.9 | 31 / 29.7 |
| unique, +10 ms: SELECTs / ms | 16 / 243.4 | 24 / 268.7 | 34 / 245.3 | 33 / 126.0 | 33 / 82.2 |
| identical, +10 ms: SELECTs / ms | 1 / 17.9 | 2 / 41.6 | 4 / 88.4 | 8 / 151.5 | 16 / 288.9 |

Every result at every width matched the oracle. Concurrency hid artificial
latency only by increasing speculative backing reads. It was actively harmful
for identical descriptors: backing reads grew exactly with width. Descriptor
coalescing must therefore precede JDBC dispatch. Width one is the safe default;
a deployment with sufficiently high read latency may calibrate a wider bounded
window after coalescing, but these data do not justify a universal JDBC width.

Both engines exposed fifteen interior PSS leaf boundaries. At every boundary,
requesting one demanded value issued one `SELECT`, while requesting `P+1`
issued two. JDBC must use demand-lazy page consumption initially, just like
direct S3.

An injected `SQLTransientConnectionException` escaped after one physical
attempt. Neither Datahike nor konserve-jdbc supplied a semantic retry loop in
this path. The JDBC source also has a global c3p0 pool lifecycle defect relevant
to test/reconnect operations: release closes a datasource that remains in the
global pool, so an immediate reconnect can reuse a closed datasource. The probe
had to clear that package-global pool between disposable connections. This is
not an application workaround recommendation; the backend lifecycle should be
fixed or independently qualified before EACL relies on repeated cold connects.

## DynamoDB measurements and failure classification

The corresponding strongly-consistent DynamoDB-local campaign produced:

| campaign | width 1 | width 2 | width 4 | width 8 | width 16 |
|---|---:|---:|---:|---:|---:|
| unique, local: GetItems / ms | 16 / 95.9 | 22 / 51.9 | 27 / 65.9 | 32 / 67.2 | 33 / 37.1 |
| unique, +10 ms: GetItems / ms | 16 / 295.5 | 24 / 325.1 | 33 / 267.9 | 34 / 131.9 | 32 / 80.5 |
| identical, +10 ms: GetItems / ms | 1 / 21.5 | 2 / 30.8 | 4 / 112.4 | 8 / 185.3 | 16 / 351.2 |

All scan results matched the sequential oracle. The local emulator timings are
not AWS latency evidence, but the physical read counts are decisive: wider
unique scans trade latency for amplification, and identical descriptors again
amplify exactly with width. The same fifteen interior boundaries required one
`GetItem` for `P` and two for `P+1`.

The 0.1.32 backend declares `PReadMissSafe`, so a PSS node cache miss is one
`GetItem`. Its `get-item` function catches every `Exception` and returns `nil`.
That conflates an exhausted SDK retry, throttling, network failure,
authentication failure, and a truly absent key. The PSS layer then raises
`:node-not-found`; the logical EACL scan fails rather than returning an empty
authorization range, which is fail-closed, but the original cause and retry
classification are irretrievably lost. The backend also defaults
`:consistent-read?` to false. A production Datahike writer/reader topology must
either establish that immutable-node publication is safely visible before the
head is consumed or enable strong reads; EACL cannot repair a storage layer that
reports a newly referenced node as absent.

`BatchGetItem` is not used by the measured single-node PSS hot path. Its source
nevertheless ignores `UnprocessedKeys`, so future multi-read use is not
qualified for exact EACL projection caching. The AWS SDK owns any underlying
HTTP retry policy; the backend does not set or expose one, and this audit does
not claim an exact real-AWS attempt count.

The artifact's synchronous delete-store path also failed locally by trying to
take from a Boolean. The random probe table was deleted explicitly through the
local DynamoDB API. This does not alter query measurements, but it is a second
backend lifecycle qualification failure.

## Initial execution policy by topology

| topology | semantic width capability | initial physical policy |
|---|---|---|
| DataScript CLJS | one event loop | width 1; no read-ahead shell |
| DataScript JVM | immutable values, local CPU | width 1 by default; widen only from CPU benchmark evidence |
| Datahike memory/file | immutable captured DB, local | width 1 by default |
| Datahike direct S3 | immutable captured DB, remote misses | request-local coalescing; width 2--4 |
| Datahike write-through LMDB/S3 single writer | immutable nodes local when warm, S3 fallback when cold | start at width 4 only if cold-fallback tail dominates; measure width 1 for warm steady state |
| Datahike streamed frontend-only LMDB/S3 peer | stream-fresh head, local immutable nodes | width 1 warm; bounded widening only for proven cold fallback |
| Datahike JDBC | captured-basis concurrent scans were exact; wider cold scans amplify SELECTs | coalesce first; width 1 default; calibrate a bounded wider window only against the actual database latency |
| Datahike DynamoDB | captured-basis local-emulator scans were exact; failures lose cause; eventual consistency is the default | strong-read/publication contract required; coalesce first; width 1 until real DynamoDB calibration and failure repair |
| Datomic Pro/DynamoDB | immutable peer index segments; remote cache misses | conservative width 4 only as a benchmark candidate, not a certified default |

The semantic adapter capability should say whether concurrent reads of one
captured basis are safe. The deployment profile should choose width. The two
must not be represented by the current single
`strict-sequential-traversal-execution` field.

## Remaining qualification gaps

1. Measure the current demo's write-through LMDB/S3 topology in fresh JVMs:
   first connect, first EACL page, repeat page, restart, and frontend sweep.
2. Repeat DynamoDB calibration against a disposable real-AWS table after fixing
   failure-cause preservation and exact publication visibility. Repeat JDBC
   calibration against each production engine rather than generalizing from
   PostgreSQL/H2.
3. Measure a real Datomic peer backed by DynamoDB, including Valcache when
   present; memory-peer correctness does not qualify physical request width.
4. Verify that the eventual EACL descriptor contains exact store, branch,
   immutable basis, operation/index, bounds, projection, physical position,
   limit, and chunk ABI before enabling coalescing.
5. Keep backend calls physically charged after HTTP cancellation until the call
   actually returns; none of the audited Datahike paths exposes a certified
   interrupt/acknowledgement contract.
6. Add byte-weighted observability around Datahike's entry-count caches before
   treating `:store-cache-size` as a memory budget.

## Confidence

- **High:** current Datahike adapter uses native tuple index seeks correctly on
  direct current databases; captured-basis reads are immutable; direct-S3
  identical cold reads amplify; warm LMDB eliminates repeated S3 GETs in the
  measured fixture; the terminal empty probe added no GET in the exact-terminal
  fixture; eager `P+1` adds one GET at every measured interior PSS leaf boundary.
- **High:** JDBC and DynamoDB-local concurrency preserved the captured-basis
  oracle in these fixtures; identical cold scans amplify with width; eager
  `P+1` crosses every measured interior JDBC/DynamoDB PSS leaf; JDBC transient
  failures escape; DynamoDB storage exceptions lose their cause but surface to
  Datahike as node-not-found rather than a logical empty range.
- **Moderate:** direct-S3 width 2--4 and a cold-fallback LMDB width near 4 are
  good initial candidates. They remain fixture- and latency-dependent. Width
  one is the conservative JDBC/DynamoDB release default, not a claim that it is
  throughput-optimal.
- **Unknown:** production S3 and DynamoDB distributions, real DynamoDB retry and
  consistency behavior under this backend, Datomic Pro/DynamoDB width and
  retries, and the best adaptive policy across warm LMDB hits and cold S3
  fallbacks.
