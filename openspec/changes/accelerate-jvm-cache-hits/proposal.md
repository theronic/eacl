## Why

Warm completed-answer hits on deployed JVM demos take roughly 4–15 ms of
service time, including about 9–11 ms for a 64-result Datomic/EC2 page. A local
resident hit should avoid authorization, backend, proof, plan, and repeated
result-construction work and complete below 1 ms when uncontended; the current
path therefore contains unmeasured redundant work or an unsuitable JVM storage
boundary.

## What Changes

- Attribute the full JVM hit latency from public API entry through basis
  selection, semantic-key construction, completed lookup, causal eligibility,
  page/cursor externalization, and telemetry instead of treating storage time
  as the whole cache path.
- Add allocation and stage-timing benchmarks that distinguish raw store lookup,
  completed-answer reuse, count reuse, and fully realized page responses.
- Remove every repeated computation, validation, traversal, conversion, or
  observation step that is not required to serve a trusted resident value.
- For deterministic adapters whose identity contract promises immutable,
  injective external IDs, probe exact public semantic keys before backend ID
  internalization for point decisions, both count operations, and permission
  trees. Noncanonical or unbounded IDs keep the ordinary internal path.
- Retain the public data portion of a completed page under its exact immutable
  basis so lookup-resource, lookup-subject, and relationship-read hits do not
  repeat one backend identity read per result. For the default non-expiring
  cursor policy, retain the complete authenticated transport page so an exact
  hit also skips cursor authentication and signing; the old
  route/boundary/alias cache is not restored.
- Evaluate Caffeine against the existing `core.cache` atom/CAS implementation
  with representative EACL keys, values, capacities, hit rates, and contention.
  Use Caffeine for the JVM only if the measured result and lifecycle semantics
  are superior; retain a separate `cljs-cache` implementation for CLJS.
- Enforce an uncontended warmed JVM completed-hit target below 1 ms at the Core
  API boundary, with separate disclosure for backend basis acquisition and HTTP
  transport that Core cannot eliminate.
- Redeploy the canonical demos and compare identical warm-hit and bypass
  results, service time, and wall time on every JVM platform.

## Capabilities

### New Capabilities

- `submillisecond-jvm-cache-hits`: Defines measurable stage attribution,
  sub-millisecond resident-hit behavior, allocation bounds, and deployed
  validation for JVM authorization caches.

### Modified Capabilities

- `implementation-simplicity-and-performance`: Makes an absolute uncontended
  completed-hit ceiling mandatory rather than accepting only relative or
  deterministic-work gates.
- `portable-authorization-cache`: Separates the semantic cache contract from
  runtime-specific internal storage so JVM Caffeine and CLJS `cljs-cache` may
  implement the same correctness behavior without a shared data structure.
- `answer-cache-bounding`: Specifies hot-key retention and bounded adaptive
  eviction without requiring JVM and CLJS to select the identical victim or
  serialize a cache library's policy state.

## Impact

Affected areas include the Core completed-answer and subproblem hit path,
cache-key construction, result externalization, metrics, JVM cache adapter,
CLJC cache dispatch, dependency configuration, cache snapshots/lifecycle, and
demo performance evidence. Caffeine replaces `core.cache` on the JVM and raises
the executable JVM dependency floor to Java 11 (EACL's production floor remains
Java 17); the public authorization API and portable cache-entry snapshots remain
compatible.
