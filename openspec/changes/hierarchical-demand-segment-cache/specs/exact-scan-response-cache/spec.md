# exact-scan-response-cache Specification

## Purpose

Client-private, elide-only reuse of exact adapter scan-response prefixes at the
stable-discovery engine's physical read seam. The cache removes adapter
commands it can reproduce exactly; it never substitutes reducer-level
artifacts, never widens demand, and is invisible to order, limits, cursors,
checkpoints, and answers.

## ADDED Requirements

### Requirement: Cached values are exact scan-response prefixes

The scan-response cache SHALL store, per read-demand descriptor (operation,
anchor type and internal id, relation id, target type), only a strictly
ascending prefix of the adapter's complete scan sequence for that descriptor,
starting at the scan's first value, together with an `exhausted?` flag that
is true only when the prefix is the complete sequence. The cache MUST NOT
store reducer emissions, plan-node segments, composed multi-hop results,
traversal prefixes, or any value derived from the request's admission state.

#### Scenario: Prefix from the start

- **WHEN** a request fetches the first physical chunk of a scan and no entry exists
- **THEN** the cache may store exactly the returned values as the prefix, marked exhausted only when fewer than the requested limit were returned

#### Scenario: Fragment without its start

- **WHEN** a request fetches a chunk after a non-nil bound and no entry containing that bound exists
- **THEN** the cache stores nothing for that descriptor

#### Scenario: Negative scan

- **WHEN** a scan returns no values from its start
- **THEN** the cache may store an empty exhausted prefix and later serve it

### Requirement: Served replies equal the adapter's reply

For a fetch with exclusive bound `b` and physical limit `L`, the cache SHALL
serve a reply only when its prefix contains at least `L` values strictly
greater than `b`, or when the prefix is exhausted; the reply MUST be the
first `L` such values (all of them when exhausted). Any other case MUST be a
miss.

#### Scenario: Full hit

- **WHEN** the prefix holds at least `L` values greater than `b`
- **THEN** the reply is exactly those first `L` values and no adapter command is issued

#### Scenario: Exhausted short hit

- **WHEN** the prefix is exhausted and holds fewer than `L` values greater than `b`
- **THEN** the reply is all of them and no adapter command is issued

#### Scenario: Short non-exhausted prefix

- **WHEN** the prefix is not exhausted and holds fewer than `L` values greater than `b`
- **THEN** the cache misses and the original command is issued unchanged

### Requirement: The cache is elide-only

On a miss the cache SHALL forward exactly the reducer's command — same
descriptor, same exclusive bound, same limit — and MUST NOT issue any
additional, widened, moved, or speculative command. It MAY use the reply to
extend the stored prefix when the reply is contiguous with it (the bound is
the prefix's last value or lies within the prefix); the extended prefix MUST
remain a prefix of the scan sequence and MUST NOT exceed the configured
per-entry cap.

#### Scenario: Command multiset is a subset

- **WHEN** the same request runs with the cache enabled and with `:cache? false` on equal snapshots
- **THEN** every command issued with the cache enabled is also issued without it, with an equal reply, and results are identical

#### Scenario: Contiguous extension

- **WHEN** a miss occurs after bound `b` that is the last value of the stored prefix
- **THEN** the stored prefix may become the old prefix followed by the reply, exhausted iff the reply was short

#### Scenario: Concurrent extensions

- **WHEN** two requests concurrently deposit different-length prefixes for one key
- **THEN** either result is a valid prefix of the same sequence and the longer one is retained

### Requirement: Validity scope is the singleton relation frontier

Reuse SHALL require equality of the complete scope: backend id, source scope,
source lifecycle, adapter fingerprint and identity contract, order ABI and
plan-domain version, schema generation, the scanned relation id, and that
relation's generation as derived from the request's complete proof frame.
A relation outside the proved closure, an incomplete or unavailable proof, or
a non-ordinary database value MUST disable both lookup and deposit for that
scan.

#### Scenario: Unrelated write

- **WHEN** a supported mutation stamps a relation other than the scanned one
- **THEN** entries for the scanned relation remain reusable

#### Scenario: Relevant write

- **WHEN** a supported mutation stamps the scanned relation
- **THEN** entries under the previous generation are never served for the new one

#### Scenario: Schema change

- **WHEN** `write-schema!` advances the schema generation
- **THEN** no entry from the previous generation is served

#### Scenario: Time-travel or filtered value

- **WHEN** the request selects an `as-of`, `since`, filtered, speculative, or caller-supplied database value
- **THEN** the cache neither serves nor deposits

### Requirement: The cache is a physical accelerator outside every semantic identity

Cached prefixes MUST NOT appear in cursors, checkpoints, completed answers, or
proof descriptors; MUST hold internal ids only; MUST NOT change reducer
admission, order, discovered counts, or limit accounting (served values still
count toward `:max-advanced-datoms` exactly as fetched values do); and MUST be
bypassable per request with `:cache? false` and per client with a disabled
cache.

#### Scenario: Limits unaffected

- **WHEN** a request that would exceed `:max-advanced-datoms` without the cache runs with every scan served from cache
- **THEN** it exceeds the limit at the same transition and fails with the same typed error

#### Scenario: Bypass

- **WHEN** a request passes `:cache? false`
- **THEN** no scan lookup or deposit occurs for that request

### Requirement: The store is bounded and lock-free on the hit path

The store SHALL be weight-bounded with a per-entry prefix cap and approximate
recency eviction; a hit MUST NOT perform a compare-and-set on shared state;
metrics MUST be observable (`hits`, `misses`, `elided-commands`,
`extensions`, `deposits`, `evictions`, `weight`, `stamp-unavailable`).
`expire-cache!` MUST make the entire store unreachable.

#### Scenario: Weight pressure

- **WHEN** a deposit would exceed the weight budget
- **THEN** entries are evicted by approximate recency until the budget holds and the request itself is unaffected

#### Scenario: Oversized prefix

- **WHEN** an extension would exceed the per-entry cap
- **THEN** the existing shorter prefix is retained unchanged

### Requirement: Adoption is gated by measured benefit

The cache SHALL ship disabled by default until a reproducible gate shows, in
one process and fixture after the sealed-plan cache fix: cache-vs-bypass
oracle equality under concurrency and interleaved supported writes on every
bundled backend; at least 90 percent of adapter commands elided on the sparse
high-sharing forward-page and hot-resource `can?` workloads after warm-up;
p50 miss-page latency at least 25 percent lower on Datahike and 15 percent
lower on Datomic than with the cache disabled; and at most 1 percent p50
regression with the cache enabled and empty.

#### Scenario: Gate refused

- **WHEN** any threshold or the oracle equality fails
- **THEN** the default remains disabled and the verification manifest records the refusal
