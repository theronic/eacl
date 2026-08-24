## Context

See `proposal.md`. Relevant current facts:

- `stable-page/state-at-boundary`: a hit requires `(= ordinal (:ordinal entry))` and `(= boundary (:boundary entry))`; otherwise `governed-replay` runs fresh to the ordinal and rejects with `:eacl.page/invalid-cursor` when the replayed boundary does not match. Publication is nonregressing on `:transitions` and latest-only; over-weight entries are dropped silently.
- `stable-reducer/history-free` keeps `[:stack :admitted :admissions :transitions :commands :fetched-values :discovered :maximum-stack]`; `finish` removes `:fetch-fn`; delivered results and buffers are never stored. `resume` rebuilds fresh limits and merges the semantic keys, so cumulative counters are enforced against configured ceilings. `:admitted` is a set of `AdmissionKey` values (a JVM `deftype` with a cached hash; a vector on CLJS); `:stack` holds work items referencing sealed-plan rules, which are shared by fingerprint within a schema generation.
- The standalone `eacl_sd1.` token path hashes an `execution-binding` that includes the exact basis; its docstring argues a checkpoint from one basis resumed at another with a coincidentally equal boundary could drop results — true for a *different* state, which frame equality excludes.
- `eacl.continuation/private-context` exposes `:get :evict! :put! :get-page :put-page! :get-heads :put-heads!`; only `:get` and `:put!` have callers.
- `continuation_reuse_test`, `stable_page_test`, `stable_reducer_test`, and `stable_discovery_gate_test` are JVM-only; no CLJS test exercises checkpoints.

## Goals / Non-Goals

**Goals:**

- Checkpoint hits survive unrelated writes on every backend that advertises ordered generations.
- Correctness stays where it is: the public cursor and deterministic replay; a checkpoint can only accelerate.
- The state's independence from the basis is asserted by a test, and cumulative limits are shown to carry across resume.
- CLJS parity for the checkpoint layer.

**Non-Goals:**

- Authenticating or bounding checkpoint entries beyond the existing weight bounds. The store is a client-private in-process atom; the process that could forge an entry is the process that reads it.
- A separate checkpoint certificate or generated acceptance kernel. Acceptance is key equality plus boundary equality, as today.
- Serializing checkpoints or sharing them across processes or clients.
- Changing the standalone `eacl_sd1.` token path, which keeps exact-basis semantics.

## Decisions

### 1. Key

```clojure
[lineage frame (:fingerprint plan) traversal subject-type anchor-eid page-size]
```

where `lineage` and `frame` come from the request context. The continuation scope digest drops `:snapshot-identity` and keeps backend, adapter fingerprint, identity contract, operation, and query identity; the Datomic `:proof-equivalent` construction is deleted because every client now uses the same key. The hit condition (ordinal and boundary identity equal) is unchanged.

### 2. Why this is sound

For a sealed plan `P`, closure `D`, and boundary `B`, the history-free state after consuming through `B` is `R(P, slices(D), B)` — a deterministic function (`ReducerCheckpoint.dfy`, `HistoryFreeReducer.dfy`) of the plan and the closure's slices, because the reducer issues scans only for closure relations (`ReducerReadScope.dfy`, from the cursor change). Equal frames in one lineage give equal slices (`EqualScalarProofPreservesEveryDeterministicDenotation`). Therefore the state checkpointed at basis `E` equals the state replay would reach at basis `S`, and resuming it at `S` equals replaying at `S` (`RuntimeCheckpointComposition.dfy`) — page values, next state, next boundary, and cumulative counters. The standalone-token docstring's hazard (an admitted merge point from a branch that no longer exists) requires a changed slice, which frame equality rules out.

The plan fingerprint is in the key, and the runtime registry keys plans by schema generation, so a resumed `:stack`'s rule references belong to the same plan object that the new request compiles to.

### 3. State closure and counters

A structural test pins `history-free` output: exactly the semantic keys; `:stack` items and `:admitted` members are vectors, keywords, integers, or `AdmissionKey` values; no `fn?`, no database value, no reader, no lazy sequence, no `:results`, no `:pending` beyond the lookahead vector of internal ids. A second test resumes a checkpoint whose counters are near a configured ceiling and asserts the ceiling is enforced cumulatively, exactly as replay enforces it. No runtime validator is added: the producer and consumer are the same engine in the same process.

`AdmissionKey` stays a `deftype` on the JVM (its cached hash is why it exists) and a vector on CLJS; both are closed data.

### 4. Pipeline order and failure

Authenticate → select → accept continuation (equal frame, or exact fallback by identity) → validate boundary → checkpoint lookup → resume or replay. The checkpoint is consulted only with a key built from the *accepted* basis's lineage and frame, so an invalid, stale, wrong-lineage, or changed-frame cursor never reaches the store. Every checkpoint failure — absent, evicted, wrong ordinal or boundary, over-weight, wrong plan — is a replay from the public boundary with a counted reason; replay exhaustion keeps its existing `:eacl.page/resource-exhausted` classification.

### 5. What stays exact

The visited-page cache stores externalized pages, whose public ids are rendered from the basis; identity is outside the frame, so it stays keyed by exact basis. The projection tier is per basis for the same reason. The standalone `eacl_sd1.` token binds an exact basis by design and is unchanged.

### 6. Publication controls and telemetry

Publication happens only after a fully realized successful page at its committed boundary, as today; `:populate-cache? false` suppresses it without mutating retained state. `checkpoint-put!`/`checkpoint-hit` report hits, misses by reason (`:absent :evicted :boundary-mismatch :overweight :plan-mismatch`), publications, and replacements. An overweight replacement is dropped without deleting an older valid frontier under the same latest-only key. The unused `:evict! :get-page :put-page! :get-heads :put-heads!` functions are removed from the private context.

## Rejected alternatives

- **A checkpoint-state certificate with its own cryptographic domain and generated kernel.** The store is in-process and client-private; acceptance is equality on a key the same process built; the theorem is a composition of existing leaves.
- **Runtime admission validator over a closed data algebra.** Worth a test, not a per-publication cost; there is no untrusted producer.
- **Keeping native revision in the key and trusting the outer scope.** That is the current Datomic configuration and it never hits after a write.
- **Frame-keying visited pages.** Unsound: rendered identity is not in the frame.

## Risks / Trade-offs

- **[A reducer change that reads outside the closure would break checkpoint soundness silently]** → the compile-time closure guard, the read-scope bridge, and its mutation control are the gate; the conformance matrix compares every hit with replay.
- **[Latest-only retention still loses on non-adjacent navigation]** → unchanged; replay is bounded and governed.
- **[CLJS representation of `:admitted` differs]** → both are closed data; the new CLJS tests run the same fixtures through publication and resume.
