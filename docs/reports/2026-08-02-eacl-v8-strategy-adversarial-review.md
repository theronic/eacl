# EACL v8 cache-free, cache, and cursor strategy: adversarial review

Date: 2026-08-02

> **Superseded cache scope:** The later
> [single-database, current-snapshot design](2026-08-02-eacl-v8-single-db-current-cache-design.md)
> is authoritative for completed-answer caching. The loophole register and
> formal gaps below remain valid where applicable, but per-hot-key source
> identity and historical completed-answer caching are no longer part of the
> strategy.

## Verdict

Unconditional 100% confidence is unattainable for the proposed system.
Clojure, generated-code compilers, runtimes, storage engines, cryptography,
clocks, process memory, configuration, privileged writers, and destructive
database operations are outside the formal kernel. Claiming otherwise would
erase the trusted boundary rather than reduce it.

The strategy is viable only after the corrections in this review. Its strongest
form is a conditional refinement claim:

> For one adapter-certified immutable logical view, the generated cache-free
> evaluator implements the formal EACL semantics. Each enabled cache or cursor
> strategy is observationally equivalent to that evaluator under its explicitly
> enforced identity, writer, retention, and cryptographic contracts.

The initial recommendation was directionally right but insufficiently strict in
five places:

1. a backend revision is not always a complete logical-view identity;
2. a schema generation cannot be latched forever by one client;
3. Datahike and DataScript do not yet justify the same managed-epoch contract as
   forward-linear Datomic;
4. the existing Dafny cache theorem assumes the decisive proof contract rather
   than deriving it from EACL semantics; and
5. auxiliary inputs, publication coalescing, and semantic ABI changes need
   explicit generations.

## Evidence added by this review

`eacl.formal.cache-strategy-adversarial-test` builds an independent finite
recursive authorization fixture and exhausts every subset of nine possible
relationships:

- 512 graph states;
- direct, arrow-permission, recursive, cyclic, and unrelated relationships;
- equal complete dependency projections imply equal root authorization;
- omitting the target `group/member` dependency produces a counterexample;
- exhaustive bounded publication/invalidation traces cannot hit a new
  versioned key with an old value;
- exhaustive bounded single-flight traces cannot mix revisions when coalesced
  by the complete key;
- stable-key publication and query-only single-flight mutants both retain
  stale counterexamples;
- a managed semantic hit is rendered with selected-snapshot metadata, while a
  retained stale-envelope mutant exposes the computation snapshot;
- namespace/key mutation checks cover semantic ABI, adapter ABI, lifecycle,
  schema, identity, auxiliary inputs, query, dependency stamp, and result
  layer;
- an unstamped raw writer changes authorization without changing a managed key.
- exact-snapshot requests cannot consult a current completed-answer generation;
- finite forward-transaction checks prove that every relevant new transaction
  raises the scalar dependency maximum, while a rewind collision retains the
  lifecycle-expiry obligation.

The focused CLJ run passed 7 tests and 476 assertions. The test is also part of
the CLJS runner and the formal parity CI job.

A fresh nREPL run of the Datomic consistency/cache/schema-basis, DataScript
consistency, and Datahike consistency namespaces passed 46 tests and 233
assertions. The full DataScript CLJS runner, including the adversarial model,
passed 67 tests and 1,073 assertions.

This is bounded falsification evidence. It does not prove all schemas, graphs,
backends, or executions.

## Loophole register

### L1 — a formal reference can specify the wrong policy

**Failure:** A cache-free evaluator can be perfectly proved against a semantics
that does not match EACL's intended union, arrow, recursion, invalid-schema,
limit, or enumeration behavior.

**Fix:**

- keep the formal semantics small and inspectable;
- maintain an independently implemented executable oracle;
- map every public operation to explicit soundness and completeness theorems;
- retain characterization fixtures for intended compatibility;
- require independent authorization-domain review of the semantics, not merely
  review of proof scripts.

**Gate:** No release claim until the theorem statement and policy semantics have
independent review.

### L2 — the current cache proof stops at an assumption

`CacheKernel.dfy` proves that the decision kernel returns an accepted candidate
only under `CompleteProofContract`. That contract states, in effect, that equal
accepted proofs make the candidate equal recomputation. The proof does not yet
derive that fact from the least-fixed-point ReBAC semantics and concrete
dependency projection.

**Fix:** Prove a frame theorem:

1. the calculated permission-node closure contains every rule that can
   contribute to the queried root;
2. the relation closure contains every relationship projection read by those
   rules;
3. equal relevant schema, relationship, identity, and auxiliary projections
   make the immediate-consequence operators equal on reachable grants;
4. their least fixed points are equal at the query root;
5. therefore equal complete dependency versions imply equal public results.

Digest collision resistance and adapter version truthfulness remain named
assumptions after that theorem; result equality itself must stop being the
assumption.

### L3 — Datomic excision is outside the supported live-operation contract

Datomic excision removes datoms across all history outside the ordinary
transaction timeline. The request is transactional, but application occurs
asynchronously during indexing. An `as-of` view at the same database id and
cutoff can therefore have different contents before and after excision.

Including the excision-request `t` in a key is insufficient: a result can be
computed after the request but before background application, then reused
after application under the same key.

The v8 strategy does not need to support concurrent excision. The operational
contract is:

1. stop authorization traffic that can observe the affected source;
2. stop cache publication;
3. submit excision and complete the indexing operation;
4. wait for `sync-excise`;
5. rotate the external source incarnation/cache-and-cursor namespace;
6. flush shared caches;
7. restart/recreate clients and resume traffic.

Clearing only completed answers is insufficient because old cursors,
continuations, and exact handles can also refer to altered history. No
excision generation is required on ordinary hot-path keys when this lifecycle
is an explicit precondition. If the lifecycle is not enforced, the operation
is outside the assurance claim.

### L4 — restore can reuse a database lineage and numeric revisions

A restore can move a logical database back to an earlier point. Process-local
caches normally disappear because Datomic requires peers/transactors to stop,
but a remote cache, signing key, or surviving token may not. Later transactions
may create a lineage that is not safely identified by an old
`[database-id, t]` cache namespace.

**Fix:** Rotate a source-incarnation identifier and cache/cursor namespace on
every restore, clone adoption, destructive reseed, or storage replacement. The
incarnation must live outside the restored data. Refuse old tokens.

### L5 — basis `t` does not identify every Datomic view

`d/basis-t` returns the greatest transaction reachable through the backing DB
value. An `as-of` DB retains the backing basis while `d/as-of-t` names the
historical cutoff. `since`, `history`, `filter`, and `with` add other semantics.

**Fix:** Only adapter-owned current-unfiltered and `as-of` values participate
in exact caching. Normalize them to one effective ordinary-history revision:

```text
effective-t = as-of-t(db) if present, otherwise basis-t(db)
exact-view = [:datomic-effective-t effective-t]
```

Under ordinary forward history, a current unfiltered DB at `t` and an
EACL-created `as-of t` DB have the same authorization contents. The backing
basis of the wrapper is therefore not part of semantic identity. This
equivalence must be an adapter theorem/certification condition, not inferred
for an arbitrary caller-supplied DB. Filtered, history, since, and speculative
values remain uncached unless a future adapter supplies and certifies a stable
complete view identity. Operations that can rewrite historical contents are
outside this model and require the lifecycle reset in L3/L4 before EACL
traffic resumes.

### L6 — source identifiers can be reused

Store names, database URIs, Datahike store ids, branch names, DataScript
connections, and cache namespaces can be deleted and recreated.

**Fix:** Include an unambiguous source incarnation. Remote cache keys and
tokens use both stable logical source and incarnation. Creation, restore, clone
adoption, reset to another lineage, and destructive rebuild rotate it.

### L7 — DataScript `:max-tx` is not a lineage identity

Two independent DataScript values can have the same `:max-tx`. `reset-conn!`
can replace the entire immutable DB and emits a synthetic transaction report.
An in-database journal head is not an unknown-writer identity.

**Fix:**

- completed caches remain process-local;
- assign each EACL client/connection an external incarnation;
- issue an opaque identity handle for each retained immutable DB value;
- update the current handle on every DataScript transaction report, including
  `reset-conn!`;
- treat direct atom mutation that bypasses the DataScript API as unsupported;
- do not offer a cross-process shared answer cache.

### L8 — Datahike `:max-tx` is not branch or commit identity

Branches evolve independently, can merge, and can be force-moved. Current
Datahike versioning documentation labels the API beta. GC can remove retained
intermediate snapshots.

**Fix:**

- exact identity uses store incarnation, branch, and commit id;
- missing or collected commits produce a typed snapshot-expired error;
- never infer ancestry from numeric `:max-tx`;
- initially support exact-revision answer caching only;
- defer managed cross-revision epochs until merge, force-branch, distributed
  synchronization, and upgrade behavior have adapter-specific proofs and
  certification.

### L9 — Datahike has no universal freshness barrier

A direct writer connection can identify its own branch head. Distributed
readers may access immutable indices without communicating with the writer and
can remain behind. Polling a connection is not a proof that a remote commit will
arrive.

**Fix:** Advertise `:at-least-as-fresh` only when the configured adapter exposes
a certified acquisition mechanism for the named commit/anchor. Otherwise
return `unsupported-capability`; do not poll indefinitely and call it
consistency.

### L10 — `fully-consistent` is an overclaim

No live distributed system has a permanently latest value. Datomic zero-arg
`sync` establishes a barrier and can block; another transaction may commit
after it returns.

**Fix:** Use precise names:

- `:local-snapshot`;
- `:at-least-as-fresh`;
- `:at-exact-snapshot`;
- `:synchronized-head` or backend-specific `:writer-head`.

### L11 — a selected snapshot can be torn by orchestration

Reading schema generation from one DB, dependencies from another, resolving
objects against current state, or lazily scanning through a connection after
selection creates a mixed snapshot.

**Fix:** Snapshot selection happens exactly once. Query conversion, schema
read, epoch read, traversal, rendering, and error construction receive that
immutable adapter explicitly. Adapter callbacks close over the DB value, never
the connection. Cached values are fully realized immutable data.

### L12 — a latched schema generation is stale across processes

One client can cache a schema generation while another valid client calls
`write-schema!`. Removing the current content-proof path without changing this
lifecycle would leave the old client evaluating an old plan.

**Fix:** Read the schema generation from the selected immutable snapshot on
every request. This is one indexed entity/attribute lookup, not a schema scan.
Look up the compiled plan by that value. A late old compilation publishes under
the old generation and is harmless.

### L13 — a no-op schema write must be semantically no-op

Keeping a generation hot after a textual no-op is safe only if normalization
proves the old and new semantics equal. Parser version, defaults, relation
ordering, custom callbacks, or engine changes can alter behavior without schema
text changing.

**Fix:** Key compiled plans and answer caches by a precise semantic ABI/build
identifier in addition to schema generation. A no-op keeps the generation only
after comparing normalized semantic IR.

### L14 — dependency closure can omit a reachable relation

Any omitted direct, via, arrow-target, self-permission target, recursive SCC,
identity, or custom input allows a stale hit.

**Fix:**

- prove closure completeness against normalized schema;
- include both via and target relations for arrows;
- compute closure over permission SCCs and the condensed DAG;
- retain missing-dependency mutants;
- exhaust small coherent schemas and differential-test larger generated ones;
- treat empty or unavailable dependency sets as “do not cache.”

### L15 — schema equality alone does not fix object identity

Changing an external-id mapping can change query resolution or public output
without changing relationships or permission definitions.

**Fix:** Require immutable external ids or include an identity generation.
Object-to-internal and internal-to-object conversions use the selected
snapshot. Deletes, remaps, restores, or custom identity-provider changes bump
the generation.

### L16 — future semantic inputs will escape relation epochs

Caveats, request context, time windows, group claims, custom predicates,
feature flags, tenant routing, or attribute-based conditions can affect
authorization without touching a relationship.

**Fix:** Every semantic input is either:

- contained structurally in the normalized query;
- immutable for the engine ABI;
- or represented by a dependency generation.

The cache is disabled for unversioned callbacks.

### L17 — writer discipline is not enforceable by a cache

If relationship content changes without its epoch changing, the managed key is
unchanged. No validation algorithm can infer the missing write from the epoch.

**Fix:**

- managed mode is explicit and off by default;
- additions, retractions, object deletion, repair, bulk import, and migrations
  update affected relation epochs atomically;
- every privileged writer is part of the operational trusted boundary;
- raw writes, `retractEntity`, branch force/merge, direct atom reset, and data
  imports either use approved helpers or rotate the source incarnation;
- periodically audit physical relationship halves and epoch coverage.

A post-commit listener can detect some violations but cannot close the race
before a stale answer is served. It is diagnostic, not a proof.

### L18 — epoch equality can suffer ABA or branch collisions

Recycled counters, branch-local transaction numbers, restore, or force-move can
produce equal version values for different content.

**Fix:** The required invariant is:

```text
equal source/branch/incarnation and equal dependency version
    => equal dependency content
```

Datomic's globally increasing transaction ids satisfy a useful forward-linear
variant until restore/incarnation rotation. Datahike and DataScript require
backend-specific treatment. Random values require collision assumptions;
content hashes require collision resistance and content-reading cost.

### L19 — Datomic's scalar max epoch has a restricted proof

Taking the maximum last-change transaction over all dependencies is safe only
because a new Datomic transaction id is greater than every existing stamp.
Any relevant write therefore raises the maximum. It is not a general
cross-backend construction.

**Fix:** Retain scalar max only for forward-linear Datomic under the source
incarnation and atomic-stamp contract. Use explicit dependency vectors or exact
commit identity elsewhere.

### L20 — negative results need the same invalidation strength

A newly added relationship can turn denial into allow. Caching denials under a
weaker key creates false denials and can become a security problem when callers
interpret denial as an invariant.

**Fix:** Positive and negative semantic answers use identical dependencies.
Both additions and retractions bump epochs.

### L21 — the semantic key can omit observable behavior

Subject, resource, permission, operation, result type, ordering, direction,
page/count limit, traversal limit, engine semantics, adapter behavior, tenant,
and rendering contract can all matter.

**Fix:** Define and prove a normalized semantic request IR. Cache either:

- the complete semantic result before pagination/rendering, in which case page
  options are not part of that entry; or
- the public operation result, in which case every observable option is keyed.

Do not cache transient provider, timeout, retention, limit, or resource errors.

### L22 — a major version is not a semantic ABI

Two rolling v8 deployments can contain behavior changes while sharing cache
keys and token formats.

**Fix:** Use an explicit semantic/cache ABI digest changed whenever parsing,
normalization, traversal, ordering, error, identity, or rendering semantics
change. Rolling deployments may share entries only when the ABI is equal.

### L23 — invalidation deletion has a late-publication race

Deleting a stable cache key does not stop an old computation from publishing
after deletion.

**Fix:** Versioned keys. Old work always publishes under old identity. Deletion
is asynchronous capacity management, never correctness.

### L24 — single-flight can reintroduce the same race

Coalescing computations by query alone can cause an `S1` request to await and
receive an `S0` computation.

**Fix:** Single-flight keys are the complete exact or managed cache key,
including source, incarnation, schema, dependencies, identity, query, and ABI.
Waiters validate that the returned computation key equals their selected key.

### L25 — provider or epoch failure can change authorization

Cache lookup, decode, epoch reads, publication, telemetry, and eviction can
throw.

**Fix:** All accelerator failures become misses followed by independent
cache-free evaluation. If evaluation itself fails, return a typed error. Do not
convert infrastructure failure to allow or denial.

### L26 — local memory is trusted only if it is actually private

Removing HMAC from an in-process cache assumes no untrusted plugin or public
mutable reference can alter entries.

**Fix:** Keep the local cache private, typed, immutable, bounded, and outside
consumer mutation. If arbitrary code in the process is outside the trust
boundary, local HMAC does not solve the larger compromise; use process
isolation.

### L27 — a remote cache has confidentiality as well as integrity risk

Raw keys can expose subject/resource ids, tenant names, policy shape, and hit
patterns. HMAC on values does not hide keys.

**Fix:** Partition by tenant/source, use a keyed digest for remote keys, bind
domain/source/ABI in authenticated data, enforce strict sizes, and threat-model
timing leakage. Treat every malformed or unauthenticated value as a miss.

### L28 — old tokens can cross lifecycle resets

Accepting an old signing key for a grace period can make a cursor from a prior
source incarnation authenticate after a restore, clone adoption, or an
out-of-scope history rewrite.

**Fix:** Source incarnation is inside the signed payload, not merely implied
by the signing key. Lifecycle reset rotates the incarnation and rejects prior
incarnations even while old keys remain available for unrelated token classes.

### L29 — TTL is not a correctness proof

Clock skew, backward clocks, or long TTLs cannot make an invalid key safe.

**Fix:** Validity follows only from snapshot/dependency identity. TTL controls
capacity and replay exposure. Expiry comparisons use one injected clock and
reject at `now >= expires-at`.

### L30 — exact cursors fail under retention or lifecycle changes

Datahike GC and DataScript registry eviction can make the original logical
view unavailable. Restore, clone adoption, or an out-of-scope history rewrite
can invalidate the source incarnation.

**Fix:** Resume only if the exact logical view identity is still certified.
Otherwise return a typed snapshot-expired/source-incarnation-changed error.
Never silently fall forward.

### L31 — cursor scope can omit ordering or rendering semantics

A cursor reused with another query, engine ABI, adapter ordering, identity
generation, incompatible page mode, or tenant can omit/duplicate values. Relay
edge positions themselves must remain direction-neutral: a start cursor can be
used as a `before` bound and an end cursor as an `after` bound.

**Fix:** Authenticate the complete normalized non-page query scope, ordering
ABI, source incarnation, exact view, result kind, and boundary/frontier. Keep
direction-specific traversal frontier data inside the authenticated edge where
needed, but do not bind the physical position to the request direction.

### L32 — continuation state can be swapped

Private continuation entries are opaque executable state. A query-only key or
cross-client reuse can resume the wrong frontier.

**Fix:** Key by client capability, exact logical view, complete query, direction,
engine ABI, and frontier identity. On any mismatch or eviction, replay from the
exact view.

### L33 — exact pagination conflicts with advancing freshness

A caller cannot both retain page-one membership and demand that page two
include new writes without defining a live-pagination algorithm.

**Fix:** Exact cursor walks reject an incompatible newer freshness floor or
require a new walk. A future rebase mode needs a separate observational
equivalence theorem.

### L34 — physical relationship corruption is outside logical semantics

EACL stores forward/reverse halves. A half-write can make forward and reverse
operations disagree even when the abstract relationship set is well formed.

**Fix:** Approved writers transact both halves and their relation epoch
atomically. Adapter certification checks forward/reverse equivalence. Integrity
repair stamps every affected relation. Corruption produces an integrity error
or conservative miss, never silent proof.

### L35 — generated code can be called through an unsound FFI

Unknown tags, numeric truncation, duplicate fields, invalid ids, lazy callbacks,
or result-variant confusion can violate proved preconditions.

**Fix:** Complete strict Java and JavaScript conversions, callback
postconditions, exact integer bounds, fail-closed result decoding, and
cross-runtime vectors remain release blockers.

### L36 — adapter tests sample rather than prove storage engines

Certification fixtures cannot prove all index, history, branch, GC, sync, and
upgrade behavior.

**Fix:** Pin backend versions, define supported configurations, run contract
tests against every supported configuration, and keep adapter correctness a
named assumption. Unsupported configurations fail construction.

### L37 — resource limits can masquerade as denial

Traversal, count, memory, stack, or continuation limits can stop before finding
a grant.

**Fix:** Exhaustion is a typed `LimitExceeded`/resource error. It is not
`Denied`, is not answer-cached, and is included in shadow comparison.

### L38 — safety proofs do not prove liveness or performance

An engine can be sound and never terminate, or spend more validating a cache
than evaluating authorization.

**Fix:** Prove finite termination under explicit bounds, benchmark adversarial
schemas, bound continuations/caches, and retain ratio-based performance gates.

### L39 — cache admission creates denial-of-service and side channels

Attacker-controlled unique queries can thrash memory and CPU. Shared hit timing
can reveal relationship or tenant activity.

**Fix:** Weight bounds, per-source quotas, maximum key/value sizes, two-hit
admission, no caching of oversized enumerations, keyed remote identifiers, and
metrics that do not expose raw authorization data.

### L40 — exact-revision caching can have nearly zero hit rate

A busy Datomic database changes basis for unrelated application writes.

**Fix:** Exact caching is a correctness baseline, not a universal performance
solution. Bypass cheap operations and use managed Datomic epochs only where
avoided work exceeds dependency-read cost.

### L41 — managed validation can still be too expensive

Reading many dependency stamps, allocating key structures, remote lookup, and
lock contention can exceed recomputation.

**Fix:** Precompile dependency order, use Datomic's scalar maximum where its
restricted proof holds, use native local keys, avoid per-hit writes, admit only
expensive plans, and benchmark by ratios under concurrency and churn.

### L42 — cache storage can retain sensitive or obsolete results

Generation-keyed entries remain physically present until eviction.

**Fix:** Strict weight/TTL bounds, source-scoped purge, encrypted remote storage
where required, no raw public identifiers in remote keys, and an explicit
destructive-operation flush procedure.

### L43 — source incarnation can itself be inconsistent

A random per-process incarnation prevents stale reuse but also destroys shared
cache hits and invalidates cursors on every harmless restart. An incarnation
stored inside the restored database can roll back with the data. Two peers
configured with different incarnations silently partition the cache; reusing
an old value can do worse and admit old tokens.

**Fix:** Provision one external source incarnation from deployment/control
plane state shared by all peers for the logical source. Keep it stable across
ordinary restarts, rotate it before traffic resumes after a lifecycle reset,
and never source it from restorable application data. Construction validates
that the configured source and incarnation are present. Shared cache and token
formats bind both values.

### L44 — future policy operators can invalidate the frame theorem

The current least-fixed-point argument relies on positive, monotone ReBAC
rules. Negation, exclusion, cardinality thresholds, time-varying caveats, or
arbitrary predicates can introduce new reads or non-monotone behavior.

**Fix:** The semantic ABI enumerates supported operators. Adding an operator is
a proof change: extend normalization, dependency extraction, fixed-point or
alternative semantics, generated conversions, mutants, and the frame theorem
before answer caching is enabled for schemas using it. Unknown operators fail
schema compilation.

### L45 — relation-stamp initialization and retargeting are easy to miss

A scalar maximum is unsound if a relation has no durable stamp, if the stamp
entity is deleted with the last relationship, or if an in-place relationship
change stamps only its new relation definition. Retargeting can change both the
old and new dependency projections.

**Fix:** Relation stamps are durable metadata keyed by the full relation
definition, independent of relationship entity lifetime. An absent stamp has
one explicit initial sentinel and is valid only for an empty relation under
the managed-writer invariant. Add, retract, delete, repair, bulk import, and
retarget transactions stamp every old and new affected relation atomically.
Missing/corrupt metadata disables managed caching or returns an integrity
error; it never defaults to a reusable ordinary value.

### L46 — “cache failure becomes a miss” can hide semantic failure

Provider lookup, decode, and publication are optional acceleration. Snapshot
selection, schema reads, identity resolution, relationship scans, and resource
limit enforcement are part of evaluation. Catching every exception and
calling it a cache miss can continue with incomplete semantic inputs.

**Fix:** Use typed boundaries. Only failures wholly inside optional cache
storage/transport become misses. A failed managed-stamp read may fall back to
cache-free evaluation on the already selected `S`; if that independent
evaluation cannot obtain complete semantic inputs, return a typed error.
Never catch an adapter or evaluator failure as a denial.

### L47 — a correct value can carry stale snapshot metadata

A managed entry computed on `C` can contain a boolean or internal result set
that is equal on selected snapshot `S`, while also containing a cursor, causal
token, selected-snapshot field, issue/expiry time, or provenance anchored to
`C`. Returning that whole public object violates `S`'s freshness contract even
when authorization membership is unchanged.

**Fix:** Cross-revision caches store only a proved snapshot-independent
semantic layer: boolean decisions, canonical internal result sets/sequences,
or counts whose complete semantics are covered by the frame theorem.
Resolution/rendering uses selected `S`. Snapshot identities and freshness
tokens name `S`; page cursors are newly constructed for `S`; cache provenance
may name the computation source only in a separate non-authoritative
diagnostic field. Never answer-cache an already signed cursor or clock-bearing
public envelope.

### L48 — set equality is weaker than public-operation equivalence

Proving equal authorization membership does not by itself prove equal order,
page flags, truncation, counts, typed errors, external IDs, or resource-limit
behavior.

**Fix:** State one refinement theorem per cached result layer. A set cache is
rendered and paginated by separately proved deterministic code on selected
`S`. A sequence cache includes the ordering ABI and proves sequence equality.
A bounded/partial result is not promoted to a complete value. The public
shadow gate compares the entire observable result, not merely allow/deny or
set membership.

## Hardened strategy

### Cache-free reference

The reference operation is not merely `Authorize(S,Q)`. The public refinement
boundary is:

```text
Select(mode, token) -> immutable logical view S | TypedError
ResolveQuery(S, public-query) -> semantic query IR | TypedError
Compile(S, semantic-ABI) -> plan | TypedError
Evaluate(S, plan, query-IR, limits) -> semantic result | TypedError
Render(S, result, rendering-ABI) -> public result | TypedError
```

Every stage after selection uses the same `S`.

### Exact local key

```text
[semantic-ABI
 adapter-ABI
 stable-source
 source-incarnation
 exact-logical-view
 normalized-semantic-query
 cached-result-layer]
```

`cached-result-layer` is a typed snapshot-independent semantic layer. It never
contains signed cursors, freshness tokens, selected-snapshot metadata, clocks,
or cache-provider errors.

`exact-logical-view` is backend-specific:

- Datomic adapter-owned current/as-of: the normalized effective transaction
  cutoff (`as-of-t` when present, otherwise `basis-t`);
- Datahike: store incarnation, branch, commit id;
- DataScript: client incarnation plus opaque retained DB handle.

### Managed Datomic key

```text
[semantic-ABI
 adapter-ABI
 stable-source
 source-incarnation
 schema-generation-from-selected-S
 identity-generation-from-selected-S
 auxiliary-generations-from-selected-S
 normalized-semantic-query
 max-relevant-relation-stamp-from-selected-S
 cached-result-layer]
```

The scalar maximum is Datomic-specific. It is valid only for forward-linear
operation without restore/excision and with atomic stamps on every relevant
write.

### Backend rollout

1. Datomic: cache-free, exact local, then managed epochs.
2. Datahike: cache-free and exact commit only; managed epochs wait for
   merge/force/distributed proofs.
3. DataScript: cache-free and exact process-local handles only; no distributed
   answer cache.
4. Remote authenticated caching: only after local strategies pass semantic and
   performance gates.

### Default

```clojure
{:consistency-default :local-snapshot
 :answer-cache :none
 :cursor-snapshot :exact}
```

Schema compilation, request-local memoization, and bounded private
continuations remain independent accelerators.

### Explicit lifecycle reset API

“Expire the EACL cache” must mean logical namespace rotation, not merely
deleting entries:

```clojure
(eacl/expire-source!
  client
  {:source-incarnation externally-provisioned-new-value})
```

The exact public name is an implementation choice, but its semantics are not:

1. stop new authorization/publication for the old client;
2. atomically switch to the new source/cache/cursor incarnation;
3. cancel or strand in-flight old work under the old namespace;
4. reject old cursors and freshness tokens;
5. clear local continuations and answers;
6. purge the old remote namespace asynchronously;
7. recreate/resume clients only after the underlying lifecycle operation is
   complete.

Multi-peer deployments provision the same new incarnation to every peer from
outside restorable application data. A per-process random value is correct but
needlessly destroys shared hits on ordinary restarts. Deletion under an
unchanged namespace is not a correctness operation because late publication
can repopulate it.

## Required proof and rollout gates

1. Prove the full cache-free public engine, including strict conversions.
2. Prove the dependency frame theorem rather than assuming result equality.
3. Prove exact-key and managed-key refinement separately.
4. Model late publication, single-flight, eviction, provider failure, schema
   race, relevant/unrelated writes, source-incarnation rotation, and lifecycle
   reset.
5. Retain mutants for every key field and writer-contract violation.
6. Certify each backend/version/configuration separately.
7. Shadow full public outputs: grants, denials, errors, ordering, page flags,
   counts, snapshot identities, cache provenance, and limits. Cross-revision
   hits must still issue metadata and cursors for the selected snapshot.
8. Require zero unexplained divergences and zero false grants.
9. Pass ratio-based latency, churn, concurrency, memory, and storage gates.
10. Obtain independent formal/security review.

## Confidence after falsification

| Claim | Confidence | Reason |
| --- | --- | --- |
| Cache-free generated reference is the correct foundation | High | Removes cache reasoning from base semantics and matches successful verified-engine practice |
| Snapshot selection must precede caching | High | Cache cannot establish backend freshness |
| Versioned keys defeat deletion/publication races | High, conditional | Follows directly when every computation and waiter uses the complete selected key |
| Exact Datomic local cache for ordinary transactions | High, conditional | Requires unfiltered/current or explicit as-of identity; concurrent excision is out of scope and restore rotates the source incarnation |
| Managed Datomic relation stamps | Moderate until the frame theorem is completed | The scalar-stamp argument is strong, but full dependency-to-public-result equivalence remains a stated proof gap |
| Managed Datahike epochs now | Low | Branch merge, force-move, distributed acquisition, and versioning stability remain insufficiently proved |
| Managed DataScript epochs now | Low | Reset/incarnation and process-local identity need redesign |
| Exact-snapshot cursors | High, conditional | Requires retention and source-incarnation lifecycle enforcement; otherwise typed expiry |
| Complete public EACL engine formally verified | False today | Production traversal conversion, rollout, performance, and independent review remain incomplete |
| Unconditional 100% correctness | Impossible | Trusted runtime/backend/crypto/operations remain outside the proof |

The strategy should proceed only in this hardened form. Any implementation that
silently relaxes an assumption must automatically fall back to exact caching or
no completed-answer cache.

## Primary sources

- Datomic synchronization:
  <https://docs.datomic.com/transactions/client-synchronization.html>
- Datomic Clojure API (`basis-t`, `as-of-t`, filters, `sync-excise`):
  <https://docs.datomic.com/clojure/index.html>
- Datomic filters:
  <https://docs.datomic.com/reference/filters.html>
- Datomic excision:
  <https://docs.datomic.com/operation/excision.html>
- Datomic backup and restore:
  <https://docs.datomic.com/operation/backup.html>
- Datahike versioning:
  <https://cljdoc.org/d/org.replikativ/datahike/0.7.1638/doc/core-features/versioning-beta->
- Datahike distributed architecture:
  <https://cljdoc.org/d/org.replikativ/datahike/0.8.1744/doc/core-features/distributed-architecture>
- Datahike garbage collection:
  <https://cljdoc.org/d/io.replikativ/datahike/0.7.1616/doc/core-features/garbage-collection>
- DataScript connection/reset source:
  <https://github.com/tonsky/datascript/blob/master/src/datascript/core.cljc>
