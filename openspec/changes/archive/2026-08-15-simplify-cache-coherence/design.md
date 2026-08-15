## Context

See `proposal.md` for motivation and the three new delta specs for behavior. The current implementation already has the useful cache shape: one exact generation per selected immutable database and one managed generation keyed by schema plus complete relation dependencies. However, mutation-mode proofs and consistency tokens are coupled to a portable v3 mutation graph consisting of random mutation records, a singleton graph head/order, schema/relation mutation IDs, expiry, pruning, and a global head CAS.

The cache does not read `:eacl.graph/head-id` when validating a completed answer. Datomic managed proofs prefer the current `:eacl/relation-version` datom and use `:eacl.relation/mutation-id` only for an initialized relation with no relationship write. Datahike and DataScript currently use random relation mutation IDs as their primary proof. All three already expose a backend current-transaction value suitable for an atomic native relation generation.

The accepted assurance boundary is narrower than the v3 graph design:

- one request selects and retains one immutable database value;
- ordinary transactions move one source forward;
- restore, reset, force, reseed, history replacement, and arbitrary database views are lifecycle operations outside managed reuse;
- the operator expires or recreates clients around those operations; and
- raw unstamped authorization writes are outside managed authority.

A local Datomic mem smoke validated the underlying stamp mechanics without graph schema: an unrelated relation write preserved another relation's proof, relevant add and retract transactions changed it, and a schema version change rotated the global proof. In a directional 100-transaction comparison, the minimal shape emitted 3 logical datoms per one-datom payload transaction versus 15 for the current graph/journal shape; mean latency was 0.184 ms versus 0.252 ms. These timings are not production evidence, but the twelve-datom difference is structural.

Two adversarial experiments also constrain the design. First, a relationship addition prepared before endpoint deletion can commit afterward and resurrect a tuple on the now identity-less endpoint unless the planned transaction carries commit-time endpoint identity guards. Relation stamps make the resulting cache state visible; they do not prevent the invalid database state. Second, native `retractEntity` follows component references. Cleaning only the root target leaves peer relationship halves for deleted component descendants, so exact native semantics require discovery and cleanup of the complete component-deletion closure.

## Goals / Non-Goals

**Goals:**

- Make the proof required for cache reuse explicit and independent of causal ancestry.
- Use the smallest database-visible state that distinguishes relevant forward mutations.
- Preserve exact-current speed, relation-scoped managed reuse, fail-safe unknown authority, and one-snapshot request semantics.
- Remove global graph-head contention and envelope/journal work from relationship maintenance and safe retraction.
- Preserve public freshness modes using honest backend-native capabilities and a versioned authenticated token.
- Provide a quiesced upgrade path that tolerates old persisted graph data and
  prevents mixed-writer stamp gaps without a temporary dual-write protocol.

**Non-Goals:**

- Keep caches or tokens valid across restore, reset, branch force, reseed, or history rewrite without an explicit lifecycle rotation.
- Cache arbitrary `as-of`, history, filtered, `since`, speculative, or caller-supplied database values.
- Make managed caching sound for raw unstamped writers.
- Provide one ancestry algorithm spanning unrelated backend history models.
- Delete legacy graph/journal datoms destructively during upgrade.
- Treat Datomic mem latency as a production benchmark.

## Decisions

### 1. Prove dependency equivalence, not causal ancestry

For request `q`, let `S` be the selected immutable snapshot and `C` the snapshot where candidate answer `A` was computed. Let `D(q)` be the complete authorization dependency closure and `V(X, r)` the version of relation `r` in snapshot `X`.

Managed reuse is allowed when:

```text
source-lifecycle(C) = source-lifecycle(S)
schema-generation(C) = schema-generation(S)
D(q, C) = D(q, S) and D is complete
for every r in D: V(C, r) = V(S, r)
semantic/configuration identities match
cached value shape is valid
```

`D` may be the empty complete set. It must never be an incomplete set. The schema generation then carries the entire managed dependency proof for a relationship-free authorization decision.

Under the managed-writer invariant that every relevant forward mutation atomically writes a new affected generation, equal generations imply that no authorization-relevant dependency changed between the represented states. The engine's query-local dependency-frame theorem then gives `Authorize(C, q) = Authorize(S, q)`. This theorem does not assume that the complete object universes at `C` and `S` are equal: unrelated object churn is allowed. It proves equality only for the built-in selected-internal/current-external identity frame and the relations reachable from the compiled request. Mutable custom identity conversion remains exact-only until it declares its own complete generation.

No ancestry check is necessary. A delayed request may encounter a managed entry computed on a later snapshot after only unrelated transactions; complete dependency equality is enough. Managed entries contain semantic results, not public snapshot envelopes. Public IDs, `:cache-basis`, cursor/token context, and other snapshot metadata are always rebuilt from `S`; a computation basis may be retained only as private diagnostics.

The removed v3 causal graph encoded a different theorem. Its journal gave each supported write a mutation record; `head` selected the current record; `previous` anchors formed a causal chain; retained anchors let readers prove that an older token was still an ancestor of the head; and the global head CAS made competing writers choose a single successor rather than fork the chain. Schema/relation mutation IDs pointed into that journal. This was *causal graph authority*: all supported writers were assumed to participate in, serialize through, and retain enough of one database-resident lineage for token comparison. It was useful for detecting history replacement, but the cache itself needs only dependency equality within a declared source lifecycle. Native revision tokens keep causal freshness separate from managed cache authority.

Alternatives considered:

- **Graph-anchor membership:** protects histories that can be replaced without lifecycle rotation, but adds state and contention for a non-goal.
- **Global database revision only:** invalidates on every transaction and loses managed reuse.
- **Content hashing:** can be correct but makes proof reads proportional to relationship content instead of the compiled relation closure.
- **Listener invalidation:** adds eventual-consistency races and another distributed component.

### 2. Normalize the storage model to schema and relation generations

The adapter exposes two logical proofs:

```clojure
{:schema-generation <bounded native value>
 :relation-generations [[relation-id assertion-identity generation] ...]}
```

The complete vector is sorted by stable relation identity. The assertion identity is the backend-native transaction/commit that asserted the stored generation and the generation value is validated against the backend's current-transaction representation where applicable. It is not folded into a lossy hash or maximum in the shared contract.

Backend representation:

| Backend | Schema generation | Relation generation | Exact-current identity |
| --- | --- | --- | --- |
| Datomic | Current `:eacl/schema-version` assertion, including its datom tx and UUID value | `:eacl/relation-version` ref to `datomic.tx` | client lifecycle plus database id and selected logical `t` |
| Datahike | Schema entity stamped with `:db/current-tx` or an equivalent native commit generation | Relation entity stamped with `:db/current-tx` | lifecycle, store/branch, and immutable commit identity |
| DataScript | Schema entity stamped with `:db/current-tx` | Relation entity stamped with `:db/current-tx` | client/connection lifecycle plus immutable DB object identity |

Every declared relation receives a physical initial relation generation in the schema transaction. Therefore a missing generation is never interpreted as “empty” and is never synthesized as `[schema-generation relation-id :initial]`; it means the managed proof is unavailable and the request falls back to exact evaluation. This eliminates the `:eacl.relation/mutation-id` fallback without weakening fail-safe behavior.

Datomic keeps `:eacl/relation-version` `:db/noHistory true` because only its current value is needed. Repeating `[:db/add relation :eacl/relation-version "datomic.tx"]` in one transaction is idempotent and permits freely composed helpers. Datahike/DataScript use their current-transaction equivalent.

The existing random schema/relation/dependency mutation attributes and graph attributes are no longer installed for cache correctness. Existing attributes and datoms remain readable but inert.

Alternatives considered:

- **Retain random relation IDs but remove only the head:** avoids part of the migration but keeps envelopes/randomness and duplicates native generations.
- **Treat a missing relation version as zero:** cannot distinguish a legitimate empty relation from an unprepared or partially migrated database.
- **One global relationship version:** simpler storage but destroys reuse after unrelated relation writes.

### 3. Keep the two-tier local cache and its lifecycle isolation

The exact tier remains a client-private atomic pointer to one generation:

```text
ExactGeneration(snapshot identity, monotone installation order, bounded store)
```

Selection installs or reuses the exact generation for `S`. A newer generation detaches the old one. Work holding the old object may finish, but can publish only into that unreachable store. An older delayed request cannot replace a newer installed generation.

The managed tier remains partitioned by schema generation. A completed-answer key is logically:

```clojure
[source-lifecycle
 schema-generation
 semantic-key
 result-kind
 complete-relation-generation-vector]
```

Source lifecycle is implicit in a strictly client-private store and explicit in managed subproblem/cursor identities. The first exact miss at a new basis computes the bounded managed descriptor. A managed hit is promoted into the selected exact generation, so following identical reads do not repeat proof reads.

`:coherence-authority :unknown` remains the default and disables managed reuse. `:managed` asserts only the stamped-writer/cache-frame contract; it no longer implicitly asserts portable causal-graph authority.

### 4. Make source lifecycle rotation the history-replacement boundary

Each request captures the source-lifecycle identity once in its selected snapshot adapter. The completed-answer/subproblem cache separately captures its atomically replaceable cache-lifecycle object at the public request boundary, before cache lookup. Every other retained namespace (continuations, page navigation, derived schema, revision/token/cursor context) is either keyed by that captured source lifecycle or contains it in its authenticated scope. `expire-cache!` first rotates the source lifecycle, atomically replaces the completed-cache lifecycle, and clears the bounded auxiliary stores. An in-flight request may publish into an old store after a clear, but its old lifecycle key is unreachable to new requests; work that had not yet entered cache lookup still holds the old captured cache-lifecycle object and cannot attach to the replacement. Recreating a client also creates a new lifecycle by default.

For a single process, the default lifecycle can be random and process-local. Deployments that require tokens across application instances configure a bounded canonical shared lifecycle identifier together with the signing key. They MUST rotate that configured identifier after restore or equivalent history replacement. Calling local `expire-cache!` is not cluster coordination; shared rotation requires quiescence or a persisted/configuration-distributed new incarnation before traffic resumes.

Operator sequence:

```text
quiesce authorization traffic
perform restore/reset/force/reseed
rotate configured source lifecycle or recreate clients
expire cache and cursor/continuation state
resume traffic
```

Automatic detection is an optimization only. A backend may compare a reliable native incarnation/commit lineage signal and rotate early, but correctness does not depend on every replacement being detectable.

Low-level arbitrary-DB engine calls and explicit historical selections bypass completed answers. Long-running operations do not need history acquisition: they already retain the immutable `S` captured at their boundary.

### 5. Replace v3 graph tokens with v4 native revision tokens

The authenticated token payload becomes conceptually:

```clojure
{:version 4
 :backend :datomic|:datahike|:datascript
 :source-id <stable native database/store identity>
 :source-lifecycle <configured or client lifecycle>
 :branch <native branch or nil>
 :revision <basis-t|max-tx|commit-id>
 :exact-locator <basis-t|commit-id|nil>
 :issued-at <seconds>
 :expires-at <seconds>}
```

The existing bounded canonical authenticated codec and key rotation remain. `graph-anchor` is removed.

- Datomic `:at-least-as-fresh` authenticates scope, performs bounded `d/sync conn t` when necessary, and validates the selected basis is at least `t`. `:fully-consistent` retains bounded zero-argument `d/sync`. Explicit exact selection uses retained `d/as-of` and bypasses completed answers.
- Datahike uses configured store/branch plus native commit identity only for store configurations whose stable commit acquisition and branch selection are certified by adapter tests. Other configurations advertise a smaller current-only capability. Numeric `:max-tx` may remain a wait hint but is not promoted beyond the configured lifecycle.
- DataScript accepts current-connection comparisons within one lifecycle and does not advertise arbitrary historical reconstruction.

Native token capability is independent of cache authority. `:coherence-authority :unknown` disables managed answer reuse, but it does not disable a backend-native token operation that is valid for the configured source lifecycle.

Old v3 tokens and cursors fail with a typed upgrade error. No compatibility fallback to their numeric hint is allowed because that would silently cross token semantics.

This change deliberately trades automatic restore/fork lineage detection for a smaller forward-history contract. It does not weaken ordinary same-lifecycle Datomic `t` ordering.

### 6. Separate optional write idempotency or audit from cache coherence

The mutation journal is removed from ordinary cache and token requirements. If callers need retry-stable request IDs or a durable audit stream, those are separate optional facilities with their own retention and transaction cost. Enabling them must not change whether an answer-cache key is valid.

This separation allows installations that need audit records to keep them without forcing every installation or transactor function to pay for global head serialization.

### 7. Reduce safe retraction to discovery, retractions, and relation stamps

The installed function accepts `[db target]`. Resolution is:

```text
numeric target  -> use directly, whether live or already retracted
lookup ref      -> resolve in transaction-start db; missing is a no-op
```

For a live target, the function first computes the backend's native component-deletion closure rooted at the target. It rejects the operation if any member is an EACL schema singleton, relation, permission, installed function, or other protected control entity. For every closure member it reads the two endpoint attributes, validates tuple shapes, emits peer-half retractions, records distinct relation ids, and ends with native entity retraction of the root.

For a numeric eid with no local entity datoms, the function reuses the repair strategy: enumerate the relatively small stored relation definitions and issue exact peer-index probes for forward and reverse tuple values naming that eid. This is `O(schema relations + matching ghost degree)` and does not scan permissioned entities. Missing lookup refs cannot recover a lost eid and remain no-ops.

Each affected relation receives the backend current transaction generation. The function emits no mutation envelope, random ID, fingerprint, head CAS, journal record, expiry, or relation mutation ID. Multiple invocations see the same transaction-start database and may emit duplicate idempotent retractions or stamps; those compose declaratively in one transaction.

Combining safe retraction with separately supplied relationship additions involving a deleted target in the same transaction is unsupported: transaction functions discover from the transaction-start database and cannot validate ordering against sibling transaction data. Multiple safe-retraction invocations are supported. A peer-only corrupt ghost not represented by a live target's local tuples remains an explicit integrity-repair concern; numeric already-retracted-eid repair searches the schema-indexed peer values precisely because the local side is absent.

Live work is `O(component closure + closure-local relationship degree + affected relations)`. Known-retracted numeric repair is `O(stored relation schema + matching ghost degree)`. The existing bounded batch deletion/repair path remains the fallback for large closures or high-degree targets.

### 8. Fence stale endpoint writers and retain local concurrency controls only for mutation semantics

Cache correctness requires atomic version publication, not CAS. However, every client-calculated relationship addition also carries an old-equals-new CAS (or backend-equivalent transaction predicate) for the identity attribute of every distinct subject/resource endpoint observed while planning. If safe deletion removes an endpoint before commit, that predicate fails instead of allowing the stale plan to recreate tuple attributes on an identity-less eid. Batch writers deduplicate guards by endpoint. A transaction function that discovers all endpoint state inside the transaction may establish the same invariant directly.

Datomic's existing relation-version CAS for client-calculated relationship writes may remain to detect stale duplicate creates, schema races, and same-relation semantic conflicts. Every backend fences a schema replacement with the write-fence value from which its complete diff was calculated. Relation removal also fences the observed relation generation, so a relationship committed after the preflight unused check makes the removal fail. Conversely, every client-planned relationship mutation carries an old-equals-old schema-write-fence predicate, so a schema removal that commits first makes the stale tuple/stamp transaction fail before it can recreate the removed eid. Both endpoint indexes participate in the unused check, including reverse-only ghosts.

Datahike demonstrated an important representation trap: its successful old-equals-old CAS reasserts the guarded datom with a new physical assertion transaction. The managed cache deliberately includes physical schema-generation assertion identity, so using that assertion as the predicate would invalidate all schema-derived entries on every relationship write. Datahike and DataScript therefore store a separate small `:eacl/schema-write-fence` current-transaction ref. A schema replacement CASes and advances the fence together with `:eacl/schema-generation`; a relationship writer only CASes the fence. Predicate reassertion may rotate the fence's physical assertion but cannot alter the cache generation. This is faster and more bounded than carrying the complete schema string as a CAS value.

These old-equals-old CAS operations are linearization predicates for mutation semantics, not cache invalidators and not a graph head. Unrelated relationship transactions can validate the same stored fence value; their predicate reassertions do not change that value. Only a schema replacement changes the stored schema fence and schema generation; only a relationship mutation changes its affected relation generations.

The graph-head CAS is removed. Consequently unrelated relation writers no longer retry solely because another EACL mutation advanced a global head. Tests distinguish these concerns so cache documentation does not claim that CAS is an invalidation mechanism.

### 9. Verify structure and races before latency

Correctness tests cover:

- exact generation rotation and late-publication detachment;
- managed hit after unrelated mutation;
- misses after relevant additions, retractions, ghost repair, and schema changes;
- missing/incomplete stamp failover;
- initial empty relation proof;
- concurrent writers and reads selecting either complete pre- or post-transaction state;
- explicit lifecycle expiry and arbitrary-DB bypass;
- cross-backend native token capability matrices; and
- multiple safe-retraction invocations in one transaction.

Structural performance gates assert:

- no graph/journal attributes or global graph CAS in ordinary relationship tx data;
- two endpoint datoms per healthy relationship plus `O(r)` distinct relation stamps;
- safe-retraction index operations bounded by live degree or schema-plus-ghost repair work;
- exact hits perform no generation reads; and
- one managed descriptor read per dependency set and selected exact generation.

The managed-read optimization is expected to win only when `p(managed hit) * C(evaluation)` exceeds `C(proof) + cache overhead`; exact-first lookup avoids proof work on exact hits and the proof remains `O(|D|)`. Structural write savings are definitive, while read latency is measured rather than assumed.

Timing benchmarks report warmed expansion and commit distributions for representative batch sizes and unrelated-writer concurrency. CI does not gate fragile absolute milliseconds. The Datomic mem smoke is retained as design evidence, not a threshold.

## Risks / Trade-offs

- **[History is replaced without lifecycle rotation]** → Document the operation as mandatory, expose one full-lifecycle expiry API, make client recreation safe, and automatically rotate only when reliable backend evidence exists.
- **[Mixed old and new writers omit native relation stamps or endpoint guards]**
  → Do not support a rolling mixed-writer cutover. Quiesce all writers, deploy
  the new writer set, prepare and verify every generation, rotate the shared
  source lifecycle, and only then enable managed v4 traffic. Database
  diagnostics prove stored preparation, not the absence of an old process;
  that part is an operator-controlled quiescence invariant.
- **[Old graph tokens are still in flight]** → Version the token/cursor formats, provide a deployment drain window, and return a typed upgrade error rather than guessing.
- **[Removing the journal loses ambiguous-retry recovery]** → Keep request-id idempotency as an optional orthogonal writer feature where consumers require it.
- **[Custom identity conversion or future caveat data changes without a declared generation]** → Keep custom mutable dependencies exact-only until their adapter declares a complete generation contract and stable fingerprint.
- **[One hot relation still causes relation-local contention]** → Measure it separately; relation-local serialization protects actual shared semantics and is not worsened by unrelated relations.
- **[Stale planned writer races endpoint deletion]** → Require commit-time endpoint identity guards on every client-calculated relationship addition and test the prepare/delete/commit interleaving on every backend.
- **[Component cascade expands safe-retraction work]** → Compute the exact native component closure, clean all closure endpoints, benchmark by closure size and degree, and retain bounded deletion/repair for exceptional cases.
- **[Already-retracted-eid repair enumerates a large schema]** → Keep exact index probes, benchmark by relation count, and retain the bounded repair API for exceptional databases.
- **[Safe retraction targets EACL schema entities]** → Reject protected control entities and require schema changes through the supported schema writer so schema generations remain authoritative.
- **[Existing graph schema consumes storage]** → Leave it inert by default; destructive schema/data cleanup is optional operational maintenance, not an upgrade prerequisite.

## Migration Plan

This change is landing before the v8 writer/token protocol is released, so the
selected migration is a quiesced cutover. A dual-write bridge would add code
and an intermediate correctness state without serving a released compatibility
requirement.

1. Quiesce every process and job capable of schema, relationship, object,
   integrity-repair, or safe-retraction writes. Disable managed readers too.
2. Deploy the new binaries everywhere while traffic remains quiesced. Replace
   the installed safe-retraction function before any target-only caller can run;
   legacy three-argument and target-only invocations must never overlap.
3. Run each backend's idempotent `prepare-cache-coherence!`. Require its
   diagnostics to show a physical schema generation and a physical
   `:eacl/relation-version` for every persisted relation. A missing or malformed
   generation keeps managed proof unavailable; it is never synthesized.
4. Rotate the configured shared source lifecycle (or recreate strictly local
   clients), clear cache/cursor/continuation state, and deploy v4 signing/token
   configuration. V3 tokens remain typed upgrade failures and are never used as
   numeric freshness hints.
5. Attest that only the new stamped writers and endpoint guards can resume,
   then enable `:coherence-authority :managed` and restore traffic. The
   attestation is essential: database inspection cannot prove an old process is
   absent or unable to write.
6. Leave old graph and journal datoms inert. New installations omit their
   schema. Retain the legacy retry journal only as a separately installed,
   explicitly invoked compatibility facility; it is outside cache validity.

Rollback is also quiesced: stop traffic, restore the prior binaries, re-establish
whatever graph baseline their v3 protocol requires, rotate signing/token and
cache lifecycles, and only then resume. Rolling binaries backward without a
lifecycle rotation is unsupported because v3 graph state did not advance during
v4-only writes.
