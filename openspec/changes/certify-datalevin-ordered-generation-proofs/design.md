## Context

See `proposal.md`; the governing frame contract is `introduce-proof-carrying-semantic-equivalence`, the backend roles are `add-authorization-views`. Facts about the current Datalevin integration that shape this design:

- The fork's contribution so far is the read side: explicit, thread-affine, cache-bypassing immutable read snapshots (`open-read-snapshot`, `read-snapshot-revision-info`, reader contexts, three-phase close). There is no pre-commit hook; transaction entry points are `with`, `transact!`, `transact-async`, `transact`, `with-transaction`, and `transact-tx-data`, all ungated. The only write rejection is the read-only guard on snapshot-wrapped storage.
- `:db/current-tx` resolves at preparation to `(inc (:max-tx db-before))`; the commit independently calls `advance-max-tx` on a volatile store field and persists `:max-tx` in the meta DBI in the same LMDB batch as the datoms. The two agree under the connection lock. `:db/current-tx` is substituted only in entity position or in a ref-typed value position, and substitution calls `allocate-eid`.
- Relationship tuples live on the endpoint entities as cardinality-many tuple attributes `[type relation type endpoint]`; the relation entity id is tuple position 1 in both the forward and reverse attribute. `:db/retractEntity` and `:db.fn/retractEntity` expand to the entity's own datoms, incoming reference datoms, and component children before the store commit; tuple components are not incoming references, so the peer half of a relationship is never implicitly retracted.
- `make-client` requires `:source-lifecycle`, a derefable `:revision-watermark`, `:advance-revision-watermark!`, and `:datalevin-topology` equal to `certified-topology-declaration`; it rejects `:wal?`, `:ha-mode`, and the LMDB flags `:nosync :nometasync :mapasync :writemap`; it persists a source UUID and advances the watermark before readiness. `expire-cache!` throws because the lifecycle is shared persisted configuration.
- Safe retraction is direct-invocation only (`:db.fn/call` with an inline function), because Datalevin cannot persist Clojure functions.
- Two connections to one directory in one process share one store (`shared-local-stores`); a second process opening the same directory has its own volatile `max-tx`.

## Goals / Non-Goals

**Goals:**

- Datalevin supplies a frame under the shared contract, read from the owned snapshot with one probe per requested relation plus one for the schema generation.
- Mutation completeness is a property of the storage engine, not of who holds a connection: the store refuses an unstamped protected change and an unadmitted protected write.
- The committed generation written into a stamp is the commit's `max-tx` by construction, and a foreign writer is detected at commit.
- Lineage is durable: cursors, tokens, and managed state survive provider restart exactly as on Datomic.
- The fork remains EACL-agnostic and the policy mechanism is reusable by any application that needs guarded attributes.

**Non-Goals:**

- Arbitrary historical selection (`at-exact-snapshot`), transaction-log scanning, or hidden snapshot retention.
- Multi-process writers. LMDB serializes processes, but each Datalevin process allocates `max-tx` from its own volatile field; the continuity check rejects the second writer rather than coordinating it.
- Protecting against raw KV writes to the datom DBIs, direct file modification, or a process that opens the directory with upstream Datalevin instead of the fork. These are outside the trusted boundary and are documented as such.
- Guarding `:eacl/id`. Applications assert public identity on their own entities; identity mutation is neutralized by re-resolution and re-rendering, not by stamps.

## Decisions

### 1. Scalar stamps with commit-generation materialization

Physical schema (replacing the ref attributes):

```clojure
{:eacl.datalevin/schema-generation  {:db/valueType :db.type/long :db/cardinality :db.cardinality/one}
 :eacl.datalevin/schema-write-fence {:db/valueType :db.type/long :db/cardinality :db.cardinality/one}
 :eacl.datalevin/relation-generation {:db/valueType :db.type/long :db/cardinality :db.cardinality/one}}
```

The writer emits `[:db/add relation-id :eacl.datalevin/relation-generation :db/current-tx]` exactly as today. The fork extends substitution: when the attribute is `:db.type/long` and the value is `:db/current-tx`, the prepared generation is substituted *without* `allocate-eid`, so `:max-eid` is untouched. At commit the store verifies that the generation it allocates (`advance-max-tx`) equals the prepared generation carried by those datoms and aborts the transaction otherwise. Equality is therefore enforced, not inferred from the connection lock.

Why not a second transaction that patches stamps after the content commit: an intervening snapshot would read changed content with old evidence. Why not reinterpret the ref attributes: they alias entity ids and are retractable through `retractEntity` of an unrelated entity.

### 2. `max-tx` continuity

Inside the write transaction, before allocating, the store reads the persisted `:max-tx` from the meta DBI and requires it to equal the in-memory `max-tx` it is about to advance. LMDB's single-writer lock makes that read consistent; a second process that committed in between moves the persisted value and is detected with one KV get per commit. On mismatch the transaction aborts with `:datalevin/max-tx-continuity-violation` and the connection is marked unusable for writes until reopened. This replaces `:datalevin-topology` equality as the single-writer-process guarantee and costs nothing the commit was not already doing in the same batch.

### 3. One write policy, enforced after expansion

The fork gains a persisted, EACL-agnostic **write policy** in the meta DBI:

```clojure
{:guarded-attributes #{...}   ; datoms require the admission token
 :frozen-attributes  #{...}   ; update-schema, clear-dbi, drop-dbi, re-index rejected for these
 :commit-generation-attributes #{...} ; long attributes where :db/current-tx materializes
 :stamp-rules
 [{:when-attribute forward-tuple-attr :stamp-attribute relation-generation :stamp-entity [:tuple-position 1]}
  {:when-attribute reverse-tuple-attr :stamp-attribute relation-generation :stamp-entity [:tuple-position 1]}
  {:when-attribute :eacl.relation/... :stamp-attribute schema-generation :stamp-entity [:constant schema-eid]}
  ...]}
```

Installation (`install-write-policy!`) is itself a guarded operation once a policy exists. Each open mints a random **admission token**; `transact!` callers supply it as `tx-meta {:datalevin/write-token token}`. `tx-meta` rather than a dynamic binding because it travels with the transaction through every entry point, including queued and asynchronous ones, and through transaction functions. Synchronous administrative APIs do not accept transaction metadata, so `with-write-policy-token` validates the same token and establishes a dynamic scope only around `update-schema`, `clear-dbi`, `drop-dbi`, and `re-index`. The dynamic scope is not used to admit datom transactions.

Enforcement runs at the single point where every entry converges after expansion and before the store commit, over the complete datom list:

1. any datom on a guarded attribute without the token → reject `:datalevin/guarded-attribute-write`;
2. for each stamp rule, any datom on `:when-attribute` whose required `[stamp-entity stamp-attribute committing-generation]` datom is absent from the transaction → reject `:datalevin/missing-stamp`;
3. any added datom on a commit-generation attribute whose value is not the committing generation → reject `:datalevin/stale-generation`.

Definition rules use the exact persisted schema-singleton eid. A looser
same-entity or wildcard selector would allow an admitted transaction to stamp
an unrelated entity while changing the effective schema, creating a forged
proof frame.

Because expansion precedes enforcement, an application `retractEntity` of a permissioned object that would retract its tuple datoms is rejected (it carries no token), which is the desired outcome: the supported path is `delete-object!`, which retracts both halves and stamps every affected relation. A transaction that changes no protected datom requires nothing. A stamp without a matching change is allowed (over-stamping).

Why a policy in the store rather than connection custody: custody constrains who may hold a handle, which another handle to the same directory ignores, and it forces a path-owning constructor that situated applications cannot use. Why not have the store derive stamps itself: the rule table is the minimum EACL-specific knowledge (which attributes, which tuple position); deriving is one more branch than verifying and would hide writer bugs rather than reject them.

### 4. Writer role

The Datalevin writer (`add-authorization-views` writer role) registers the policy in `ensure-physical-schema!`, holds the admission token for the lifetime of the connection, and passes it on every `transact!`. Planning is unchanged: endpoint identity guards, the write-fence CAS, `create-relationship-at-commit`, `delete-object-at-commit`, and `stamp-relation-versions` continue to produce the stamp set; the store now verifies it. Safe retraction remains the inline `:db.fn/call` form, but callers submit it through `eacl.datalevin.safe-retraction/transact-retract-entity!`, which uses the certified writer token and advances the external revision watermark.

### 5. Frame and capabilities

The basis adapter, bound to an owned read snapshot, implements:

- `:schema-generation` — one EAVT probe on `[:eacl/id "schema-string"]` for `:eacl.datalevin/schema-generation`, returning `:v` (already memoized per adapter);
- `:proof-frame` — for each requested relation id one EAVT probe for `:eacl.datalevin/relation-generation`, returning `[relation-id (:v datom)]`, realized eagerly; a missing datom yields `nil` (unavailable under the shared contract).

Values are in the `max-tx` domain, the same as `:native-revision`, so the shared ceiling assertion applies unchanged. Capabilities add `:ordered-generations`; `:at-exact-snapshot` stays absent. No Datalevin-private comparison exists.

### 6. Lineage and construction

Lineage is `{:source-id <persisted UUID> :branch nil}` plus the required configured lifecycle — durable, as on Datomic. The revision watermark stays: a restored store whose `max-tx` is below the watermark fails construction. Construction first validates the public option shape, fork capabilities, unsafe flags/WAL/HA, and the pre-bootstrap watermark. Checking the watermark before any bootstrap mutation prevents a write from masking a rollback. It then installs or validates physical schema, source identity, schema singleton, policy, and complete initial generations; validates the resulting source identity; advances the watermark through every bootstrap commit; and only then becomes ready. `:datalevin-topology` is removed from the option map. The rejected LMDB flag set gains `:nolock`: without the environment writer lock, the continuity check's consistent read of the persisted `max-tx` is not guaranteed across processes.

Fresh stores bootstrap in a deliberate order. Source identity and the schema
singleton are written while no policy exists. The policy is then installed,
and the first scalar generation datoms are submitted with its admission token.
An existing protected store with missing generation evidence fails closed; it
is never silently repaired because no operation can prove what an old
unstamped mutation affected.

Provider restart keeps every cursor and token valid and keeps managed lifting enabled; the sticky disablement from a contract violation (shared contract) is cleared by provider restart, since Datalevin's `expire-cache!` remains unavailable by design.

### 7. Shared-store state adoption and bounded stale-wrapper recovery

Several connection atoms in one process share a Datalevin `Store` but retain
immutable DB wrappers. Every successful commit path, including the blind-map
fast path, must adopt committed schema, giant-id, `max-tx`, and state-sync data
into the original shared Store. Returning a wrapper over a detached transferred
Store makes subsequent connections falsely appear foreign and breaks both
performance and continuity.

If another same-process connection commits after EACL prepared a transaction,
the fork rejects the stale prepared generation before commit. The module
refreshes that connection wrapper and retries the complete semantic operation
at most eight times, and only for the typed
`:eacl.datalevin/stale-connection-generation` condition. CAS contention,
continuity violations, policy failures, foreign exceptions, deadlines, and
cancellation are never included in that retry class.

## Rejected alternatives

- **Provider-owned path constructor with exclusive custody and alias rejection.** Does not prevent a second connection in the same or another process; forbids the situated topology (application writes its own data through its own connection); redundant once the store enforces the policy.
- **Session-scoped lineage.** Strictly weaker than the persisted source id plus watermark Datalevin already has; would make every restart a cursor-invalidation event for an embedded database whose history is linear across restarts.
- **Quiescent v2 migration keeping legacy ref stamps.** Nothing to migrate.
- **Exact-vector and global-frontier "profiles".** The shared contract has one frame; Datalevin's scalar commit generation satisfies its global-ordering premise by construction (`advance-max-tx` under the writer lock plus the continuity check).
- **A closed mutation algebra as a separate validator.** The writer's operations are the algebra; the store's stamp rules are the validator.

## Risks / Trade-offs

- **[Applications that today `retractEntity` permissioned objects directly will be rejected]** → the rejection names `delete-object!`; the README documents that Datalevin enforces what the other backends only document.
- **[Policy enforcement adds per-transaction work]** → one set lookup per datom plus one meta read per commit; measured by the existing Datalevin write benchmark with a regression budget.
- **[Upstream Datalevin can open the directory without the policy]** → documented as outside the boundary, with the same standing as direct file modification; the fork artifact is the only supported Datalevin.
- **[Over-stamping on no-op writes invalidates equal-frame entries]** → unchanged from the other backends; telemetry reports stamp advances without content change.
- **[Fork divergence from upstream grows]** → the policy and materialization are small, generic, and covered by fork-level contract tests; the build already pins `Datalevin-Upstream-Version`.
- **[The maintained fork is bypassable below its public datom/admin APIs]** → raw KV writes, direct file mutation, and opening the directory with upstream Datalevin remain outside the certified boundary and are stated explicitly; no cache proof can compensate for that bypass.
