# exact-scan-response-cache Specification

## Purpose

Client-private, elide-only reuse of exact adapter scan-response prefixes at the
routed physical read seam shared by the stable-discovery reducer, the
least-path evaluator, and the point-probe route, on every bundled backend and
runtime. Operator-engine scans and batched membership probes use separate
seams and are outside this capability. A
request-local memo removes repeated commands inside one request; a bounded
cross-request tier removes repeated commands across requests that read the
same relation slice. The cache removes adapter commands it can reproduce
exactly and nothing else: it never substitutes reducer-level artifacts, never
widens demand, and is invisible to order, limits, cursors, checkpoints,
answers, and errors.

## ADDED Requirements

### Requirement: Cached values are exact scan-response prefixes

The scan-response cache SHALL store, per read-demand descriptor (operation,
anchor type and internal id, relation id, target type, and scan direction),
only a strictly ordered prefix of the adapter's complete scan sequence for that
descriptor, starting at the scan's first value, together with an `exhausted?`
flag that is true only when the prefix is the complete sequence. The cache
MUST NOT store reducer emissions, plan-node segments, composed multi-hop
results, traversal prefixes, or any value derived from the request's admission
state.

#### Scenario: Prefix from the start

- **WHEN** a request fetches the first physical chunk of a scan and no entry exists
- **THEN** the cache stores exactly the returned values as the prefix, marked exhausted only when fewer than the requested limit were returned

#### Scenario: Fragment without its start

- **WHEN** a request fetches a chunk after a non-nil bound and no entry containing that bound exists
- **THEN** the cache stores nothing for that descriptor

#### Scenario: Negative scan

- **WHEN** a scan returns no values from its start
- **THEN** the cache stores an empty exhausted prefix and later serves it without an adapter command

### Requirement: Served replies equal the adapter's reply

For a fetch with exclusive bound `b` and physical limit `L`, the cache SHALL
serve a reply only when its prefix contains at least `L` values strictly
beyond `b` in the scan's direction, or when the prefix is exhausted; the reply
MUST be the first `L` such values (all of them when exhausted). Any other case
MUST be a miss.

#### Scenario: Full hit

- **WHEN** the prefix holds at least `L` values beyond `b`
- **THEN** the reply is exactly those first `L` values and no adapter command is issued

#### Scenario: Exhausted short hit

- **WHEN** the prefix is exhausted and holds fewer than `L` values beyond `b`
- **THEN** the reply is all of them and no adapter command is issued

#### Scenario: Short non-exhausted prefix

- **WHEN** the prefix is not exhausted and holds fewer than `L` values beyond `b`
- **THEN** the cache misses and the original command is issued unchanged

### Requirement: The cache is elide-only

On a miss the cache SHALL forward exactly the evaluator's command: same
descriptor, same exclusive bound, same limit, same direction. It MUST NOT
issue any additional, widened, moved, or speculative command. It MAY use the
reply to extend the stored prefix when the reply is contiguous with it (the
bound is the prefix's last value or lies within the prefix); the extended
prefix MUST remain a prefix of the scan sequence and MUST NOT exceed the
configured per-entry cap.

#### Scenario: Command multiset is a subset

- **WHEN** the same request runs with the cross-request tier enabled and with `:cache? false` on equal snapshots
- **THEN** every command issued with the tier enabled is also issued without it, with an equal reply, and results, cursors, counts, decisions, and typed errors are identical

#### Scenario: Memo-free execution is the command oracle

- **WHEN** the same request runs with the request-local memo disabled through the internal test seam
- **THEN** its command multiset is a superset of the memoized run's, replies are equal, and every public outcome is identical

#### Scenario: Contiguous extension

- **WHEN** a miss occurs after bound `b` that is the last value of the stored prefix
- **THEN** the stored prefix becomes the old prefix followed by the reply, exhausted iff the reply was short

#### Scenario: Concurrent extensions

- **WHEN** two requests concurrently deposit different-length prefixes for one key
- **THEN** either result is a valid prefix of the same sequence and the longer one is retained

### Requirement: A request-local memo serves repeated commands inside one request

Within one request on one immutable basis, the cache SHALL serve a repeated
command for an already fetched descriptor from the request's own memo
without consulting validity scope, because every read of one request observes
the same immutable snapshot. The memo is part of ordinary execution: it
touches no shared store, so `:cache? false` and a disabled client cache MUST
NOT turn it off; only an internal test seam disables it to establish the
command oracle. The memo MUST be released with the request, MUST NOT be
published across requests, and MUST be bounded by a fixed number of
descriptors and the per-entry prefix cap so that an exhaustive traversal
cannot retain more than that bound; beyond the bound repeated commands are
issued to the adapter as they are today.

#### Scenario: Batch of point checks shares the subject side

- **WHEN** a `check-permissions` batch decides many resources for one subject and the probe route scans the subject's holdings once per demand
- **THEN** every repeated holdings scan after the first is served from the request-local memo and issues no adapter command

#### Scenario: Chunked lookup re-enumerates a witness child

- **WHEN** a filtered lookup processes several candidate chunks that each re-enumerate the same arrow-permission child scan
- **THEN** the repeated scans are served from the request-local memo and the emitted page is identical

#### Scenario: Exhaustive traversal exceeds the memo bound

- **WHEN** an exhaustive count touches more distinct descriptors than the memo bound
- **THEN** the memo stops retaining new descriptors, the count completes with the same result, and retained memory stays within the bound

### Requirement: Validity scope of the cross-request tier is the singleton relation frontier

Cross-request reuse SHALL require equality of the complete scope: backend id,
source scope, source lifecycle, adapter fingerprint and identity contract,
order ABI and plan-domain version, schema generation, the scanned relation id,
and that relation's generation as derived from the request's complete proof
frame. A relation outside the proved closure, an incomplete or unavailable
proof, or a non-ordinary database value MUST disable both cross-request lookup
and deposit for that scan while leaving the request-local memo in effect.

#### Scenario: Unrelated write

- **WHEN** a supported mutation stamps a relation other than the scanned one
- **THEN** entries for the scanned relation remain reusable

#### Scenario: Relevant write

- **WHEN** a supported mutation stamps the scanned relation
- **THEN** entries under the previous generation are never served for the new one

#### Scenario: Schema change

- **WHEN** `write-schema!` advances the schema generation
- **THEN** no entry from the previous generation is served

#### Scenario: Time-travel, filtered, or speculative value

- **WHEN** the request selects an `as-of`, `since`, filtered, speculative, or caller-supplied database value
- **THEN** the cross-request tier neither serves nor deposits for that request

#### Scenario: Proof frame unavailable

- **WHEN** the selected snapshot cannot supply a complete ordered-generation proof for the scanned relation
- **THEN** the scan runs against the adapter with only the request-local memo, and the request completes without error

### Requirement: The cache is a physical accelerator outside every semantic identity

Cached prefixes MUST NOT appear in cursors, checkpoints, completed answers,
proof descriptors, or public results; MUST hold internal ids only; MUST NOT
change admission, order, discovered counts, deadline observation points, or
limit accounting (served values still count toward every fetched-value and
command limit exactly as adapter-fetched values do). The cross-request tier
MUST be bypassable per request with `:cache? false` and per client with a
disabled cache, because it reads from and writes to the shared store; the
request-local memo is unaffected by either.

#### Scenario: Limits unaffected

- **WHEN** a request that would exceed a fetched-value or command limit without the cache runs with every scan served from cache
- **THEN** it fails at the same point with the same typed error

#### Scenario: Bypass

- **WHEN** a request passes `:cache? false` or the client's cache is disabled
- **THEN** the shared tier performs no lookup or deposit for that request, while repeated commands inside the request are still served from its memo

#### Scenario: Deadline observed identically

- **WHEN** a request's deadline expires during a run whose scans are all served from cache
- **THEN** the run stops at the same check point with the same typed cancellation as an uncached run

### Requirement: The store is bounded, shared-runtime, and cheap on the hit path

The cross-request tier SHALL use the client's bounded cross-runtime cache
store with an entry bound and a per-entry prefix cap; a hit MUST NOT perform a
compare-and-set on shared state; one request SHALL deposit at most the memo
bound of distinct descriptors so that an exhaustive traversal cannot churn the
tier; the tier's meters (`hits`, `misses`, `elided-commands`, `extensions`,
`deposits`, `scope-unavailable`) MUST be observable through the client's cache
statistics; the tier MUST be excluded from cache snapshot export and restore;
and expiring the client's cache MUST make the entire tier unreachable.

#### Scenario: Entry pressure

- **WHEN** a deposit would exceed the entry bound
- **THEN** the store evicts by its retention policy and the depositing request is unaffected

#### Scenario: Oversized prefix

- **WHEN** an extension would exceed the per-entry cap
- **THEN** the existing shorter prefix is retained unchanged

#### Scenario: Cache expiry

- **WHEN** the client's cache is expired
- **THEN** no previously stored prefix is served afterwards

### Requirement: Backend-neutral adoption is gated by paired measurement

The cache SHALL be available on every bundled backend and both runtimes. The
cross-request tier SHALL ship enabled by default only for backends whose
paired same-process gate shows cache-versus-bypass oracle equality on
randomized graphs with interleaved supported writes, at least 90 percent of
adapter commands elided on the sparse high-sharing forward-page workload after
warm-up, p50 page latency with the tier warm at least 5 percent below the
tier-disabled run, and no more than 3 percent p50 regression with the tier
enabled and empty. A backend that fails the gate SHALL keep the tier disabled
by default while remaining opt-in.

#### Scenario: Gate passes on a backend

- **WHEN** the paired gate passes for a backend
- **THEN** that backend's clients enable the cross-request tier unless configured otherwise

#### Scenario: Gate refused on a backend

- **WHEN** any threshold or the oracle equality fails for a backend
- **THEN** that backend's default remains disabled and the verification evidence records the refusal
