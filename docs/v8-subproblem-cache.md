# EACL answer and subproblem cache

Each EACL client owns three flat, independently bounded retention tiers:

- `:answer` stores completed point decisions, internal pages, counts, and
  permission trees;
- `:rendered-page` stores complete exact-basis transport pages for complete raw
  requests when cursor expiry is disabled;
- `:denotation` stores completed point decisions: an operator permission's
  decision for one candidate resource, and each operand decision it combined.

Two further client-private tiers sit beside them and answer no request on
their own: the scan-response tier keeps exact adapter scan prefixes per read
descriptor under a scope pinned by the scanned relation's generation, so a
later request that walks the same edges issues fewer adapter commands (see
`docs/cache.md`), and the range tier keeps the longest completed page per
query and start boundary so a shorter page of the same walk is derived from
it instead of traversed. Both are physical accelerators: results, order,
cursors, limits, and errors are identical with them disabled.

Clojure uses Caffeine's concurrent Window TinyLFU policy; ClojureScript uses
the pinned theronic `cljs-cache` LRU fork. EACL supplies only a small storage
adapter. Hits update frequency/recency policy, so a frequently used old entry
is not evicted merely because it arrived first.

## Resolution

Storage sees opaque complete keys and immutable completed values. For an
ordinary current request, completed-answer resolution is exact key, then one
proof-managed key, then independent computation. A managed answer hit may be
promoted under the exact key. Denotations are exact-basis only. Historical
bases use identical exact keys only. Speculative requests may read one
disjoint committed managed answer but publish nothing.

A denotation key names the decision (the sealed plan's fingerprint, the
subject and the resource) under the exact basis. An unqualified decision is a
Boolean. A qualified one is keyed by its certified scope: the request's
qualification identity without its evaluation time, which the value's
certificate replaces, and without its basis, which the exact storage key
carries. Its value records the computation time, the certificate's end and
completeness, the permissionship and the canonical evidence. A later request
reuses it only while `start <= time < end` and the certificate is complete (an
incomplete certificate only at its own time), and observes that interval on
its own qualification, so an answer built from reused decisions is certified
no longer than they are. A later computation replaces an entry it could not
reuse; an older one never replaces a newer one. The evaluators look up and
publish a batch of candidates at once, and the requests of one client on an
unchanged basis share one key constructor, so a later request matches a
resident key by identity.

Managed envelopes record the revision at which the value was computed. Reuse
requires both equal complete proof identity and
`computed-revision <= selected-revision`; equal proof never authorizes backward
reuse of a future value.

Missing, malformed, partial, oversized, or unavailable proof makes the managed
key absent. It does not alter authorization availability. Malformed or
operation-invalid values are rejected by publication or restore before they
can become resident.

Validated publication and validated off-side restore are the only supported
entry-installing transitions. An exact hit is consequently an ordinary
complete-key membership read plus the runtime library's access update, with no
repeated artifact, ABI, or operation validator. A managed hit adds only
`computed-revision <= selected-revision`. Exact denotation hits have no managed
subproblem path. Every live publisher must supply an
explicit callable artifact validator; no low-level publisher defaults to
trusting its value. Direct application mutation of the private runtime records
or backing atoms is outside the supported contract.

## Bounds

The public configuration uses positive safe-integer entry counts:

```clojure
{:cache
 {:max-entries            2048
  :denotation-max-entries 4096
  :telemetry?             true}}
```

The semantic answer and denotation tiers have independent capacities. The
outer `:max-entries` sizes answers, exact rendered pages, and the adjacent
continuation/cursor caches; all use 1,024 when it is omitted.
`:denotation-max-entries` is the only additional cache-capacity setting.
Logical weight estimators and byte
budget claims were removed. Physical operator chunks, direct Boolean probes,
and Relay identity conversion are request work rather than retained shared
artifacts. Engine traversal, chunk, service-admission, and expression limits
remain separate semantic or work bounds outside storage.

The common answer publication boundary retains completed pages only when they
contain at most 1,000 result items. Larger valid pages are returned unchanged
and not cached. Scalars, counts, and trees do not inherit that page rule.

## Concurrency and failure

Misses have no cache owner. Concurrent callers compute independently and race
an atomic absent-key insertion; a losing publisher still returns its own
answer. Validators and computations execute outside storage atomic scopes and
are never repeated by cache retries. A local cache exception is a miss or
failed publication, not an authorization error.

## Snapshot v2

Portable export is a deterministic flat entry sequence. It excludes
Caffeine/`cljs-cache` admission, priority, and recency state. Restore validates
complete keys, managed-answer proof keys, revisions, operation-specific completed value
contracts, duplicate keys, and count capacity before constructing fresh cache tiers
off-side and installing them atomically. A denotation's value must agree with
its key: a qualified key holds a point answer whose permissionship,
certificate end and completeness agree with its decoded evidence, and any
other key a Boolean. Process-local exact promotions are
not exported without their live validating transition; the corresponding
managed mapping remains portable.

The decoded value is a trusted API input. Hosts must authenticate and
encoded-size-bound external bytes before decoding. Snapshot v1 and malformed
v2 values are rejected without changing the live lifecycle.

## Evidence

`formal/dafny/SubproblemCache.dfy` models storage as a bounded partial map with
arbitrary eviction, independent computation, completed-only publication, page
retention eligibility, and lifecycle detachment. Causal and ordinary-only
managed eligibility are proved in `CurrentCache.dfy` and
`ScalarFrontierCoherence.dfy`. `CertifiedPointReuse.dfy` proves that a
qualified decision reused within its certificate is the decision a fresh
evaluation makes at the later time. The TLA model treats keys and validated values as
opaque mappings and checks publication, eviction, expiry, and orphaned
lifecycle interleavings.

The proof remains conditional on truthful adapter evidence, complete dependency
extraction, globally ordered atomic relation stamps, correct composite keys,
and the database engines. Cross-runtime and cross-backend differential tests
cover the implementation boundary.
