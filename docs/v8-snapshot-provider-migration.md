# v8 snapshot-provider migration

EACL v8 clients construct a long-lived `eacl.backend.snapshot-provider`
instead of reading a database value during client construction or at every
orchestration checkpoint. The provider publishes snapshot-free capability,
topology, traversal, ownership, and execution metadata. Each public request
then acquires one selected immutable adapter and releases it after proof,
cache, cursor, token, ID conversion, and response realization are complete.

## Compatibility path for immutable values

Backends whose database values are genuinely immutable may use
`borrowed-adapter-provider`. The static adapter is inspected but not retained;
every acquisition callback must return the adapter selected for that request.
The provider declares `:snapshot-ownership :borrowed`, and its release
operation is a no-op. Datomic, Datahike, and DataScript use this path without
changing their public client APIs or capability declarations.

The borrowed path is invalid for a mutable handle, an adapter that consults a
live connection after construction, or any value that owns a native reader,
cursor, transaction, file, socket, or lease. Object identity and
`identical?` are not snapshot equality.

## Required owned-provider behavior

An owned provider must:

1. declare the closed execution constraints and ownership policy at client
   construction without acquiring a request snapshot;
2. return exactly `{:adapter adapter :ownership :owned :release-token token}`
   from every successful acquisition;
3. ensure the adapter is immutable and all of its operations are bound to the
   selected native snapshot;
4. make every bounded scan finite, eager, strictly ordered, and free of native
   iterator escape;
5. close rejected candidates, including every at-least retry, on the required
   release thread;
6. keep release retryable when native close fails and make a completed release
   idempotent; and
7. reject unsupported runtimes and thread escape before native access.

Core attempts cleanup for every map returned by an acquisition callback,
including a malformed map that omitted `:release-token` (the cleanup callback
receives nil). A provider that throws before returning must clean any native
state it acquired internally because core has no release token.

## Semantic snapshot identity

Cache and cursor decisions use the captured semantic identity, not adapter or
database object identity. The identity contains exactly:

```clojure
{:backend ...
 :source-id ...
 :branch ...
 :source-lifecycle ...
 :revision ...
 :exact-locator ...
 :schema-identity ...
 :backend-snapshot-id ...}
```

The adapter's native revision must agree with both `:order-hint` and
`:exact-locator`. `:schema-identity` is required when schema can change without
the native revision changing; otherwise it may be nil. Independently acquired
snapshots may compare equal only when every EACL-visible dimension above is
equal. Mutable values, directory paths, credentials, and process-local object
identities must not appear in tokens or portable cache identity.

For at-least and exact modes, the selected adapter is compared directly with
the authenticated token's backend, source, branch, and lifecycle. A lifecycle
rotation racing selection therefore fails closed instead of producing a
hybrid request.

## Write boundary

Read planning and validation run inside an owned snapshot scope. That scope
must close before writer acquisition. The commit result—not a post-commit
head read—must supply the acknowledged native revision used for response
tokens, invalidation, and any external monotonic watermark hook.

## Migration checklist

- Replace client-construction DB retention with a provider constructor.
- Keep a compatibility provider only for certified immutable values.
- Count acquisitions and releases for every public operation and error path.
- Test cancellation immediately after acquisition and failure during context
  construction, proof, cache publication, cursor recovery, and response
  externalization.
- Test use-after-close, double close, foreign-thread access/release, release
  failure/retry, and unsupported runtime rejection.
- Test equal revision, changed revision, lifecycle/source/branch changes, and
  independent schema drift in semantic cache identity.
- Prove returned public values can be fully traversed after release without
  backend access.
- Do not advertise exact history, ordered proofs, concurrency, durability, or
  topology properties that are not independently certified.
