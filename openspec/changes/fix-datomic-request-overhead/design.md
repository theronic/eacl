# Design: Per-Request Overhead Fixes

## Plan cache

`stable-plan-key` = `[backend-id source-scope source-lifecycle K root]` with
`K = [:schema identity]` when `plan-schema-identity` is known (client
generation cache `:plan-schema-identity`/`:schema-version`, or the raw
facade's `request-schema-cache` `:schema-identity`), else
`[:basis native-revision]`. Capacity 256 FIFO. `expire-plans!` clears it and
is invoked from `expire-cache!` in the Datomic client and the shared
Datahike/DataScript orchestration.

Why the lifecycle stays: real adapters never alias a source scope across
distinct stores (Datomic database id, Datahike store id, DataScript
connection identity), but test adapters do (`{:source-id :test}` at a
constant stamp); dropping the lifecycle let a global cache serve one test's
plan to another. The raw facade's random per-call lifecycle was the thrash
— it now mints one process-stable lifecycle for its engine adapters (that
lifecycle affects nothing but the plan key on the raw path).

Why no proof-frame op: `:raw-can` op-count envelopes pin zero proof-frame
reads for raw checks and the exact-hit contract performs no generation proof
reads; the identities used are already known to the caller.

## seal-plan

`sort-by-canonical` maps values to `[encoding value]` once and sorts by the
encoding string with `compare` — the same total order `sort-by
encode-canonical` produced, without O(n log n) re-encoding. Fingerprint and
plan equality before/after were asserted in the REPL for three roots.

## Request validation

`request-schema` returns `(:parsed-schema impl.indexed/*schema-cache*)`
when a stamped generation is bound, else reads the schema. Validation moved
inside the cached computation (`compute` / `evaluate`, where the generation
is bound) and into the unknown-object short-circuits, so a hit performs no
schema work. Argument: a request that fails validation never produces an
entry (validation precedes evaluation), and every tier keys by an identity
that fixes the schema generation (exact: snapshot; snapshot-exact: exact
snapshot; managed: schema stamp), so a hit implies validity under an equal
schema. Typed errors are unchanged (verified for unknown permission, unknown
type, unknown subject + unknown permission, on checks and pages).

## kernel?

`ConcurrentHashMap<Class,Boolean>` of classes known to satisfy
`DecisionKernel`; negatives are not memoized (a later `extend` could make
them stale); CLJS unchanged.
