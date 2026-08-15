# EACL stable-engine audit: bugs, discrepancies, dead code, optimizations

Date: 2026-08-15

Scope: `core/` at `ac3cbac` (branch `agent/stable-discovery-enumeration`, the
head of [PR #116](https://github.com/theronic/eacl/pull/116)). Read in this
order: the formal corpus (`formal/stable-discovery/*.dfy|*.tla`, the
refinement bridges, `formal/README.md`), then the engine
(`eacl.engine.sealed-plan`, `stable-reducer`, `stable-page`, `stable-route`,
`physical`, `v8`), the shared client surfaces (`eacl.backend.v8`,
`eacl.client.orchestration`, `eacl.relay`, `eacl.cursor`,
`eacl.continuation`, `eacl.cache`, `eacl.execution`, `eacl.secure-format`),
and the three backend modules (Datomic Pro, Datahike, DataScript). Findings
were checked against the running nREPL where a REPL check was cheaper than
an argument; the CI-equivalent battery
(`cognitect.test-runner` over the four module test roots with
`:excludes [:benchmark :formal-artifact]`) was green before the cleanup
in this branch (621 tests, 25,983 assertions, 0 failures) and after it on a
fresh JVM (621 tests, 25,976 assertions, 0 failures; the seven fewer
assertions are the ledger roots and mutant-detector targets that the
removed code carried). The stable-discovery release-assurance gate
(`formal/stable-discovery/verify-fast.sh`) was broken before this branch and
is green after it (§2.16).

Severity scale: **S1** wrong or missing authorization answer reachable
through a public entry point; **S2** wrong answer reachable only through an
internal/standalone API or an unusual configuration; **S3** contract or
documentation says one thing and the code does another, no wrong answer
today; **S4** hygiene.

Every item below states its status: **Fixed** items were repaired on this
branch (commit "Fix the audited defects", each with a regression test);
**Left as designed** items are behaviours the audit flagged but whose change
is a compatibility or design decision rather than a defect repair, with the
reasoning recorded; the rest name the smallest correct fix.

## 1. Formal models versus the implementation

The accepted engine is modelled by 41 Dafny leaves and two TLC families under
`formal/stable-discovery/` (506 obligations, `verify-fast.sh`). The table
lists each model family, the production code that claims to refine it, and
what the audit found.

| Model | Production refinement | Verdict |
|---|---|---|
| `StableReducer.dfy`, `HistoryFreeReducer.dfy`, `ReducerCompleteness.dfy` — DFS over a stack, exact admission, results are the pop order of result nodes, runtime keeps no result history | `stable-reducer/step`, `schedule`, `run-loop`, `finish`, `history-free` | Refines. Push order (residual first, fresh work reversed onto the right edge) reproduces `admission.newValues + stack[1..]`. Uniqueness by construction holds: forward emission is the first admission of `[:grant root eid]`, reverse the first admission of `[:reverse-subject type eid]`. One robustness gap, §2.9. |
| `AtomicLogicalAdmission.dfy` — a successor batch is admitted completely or not at all; caps checked before mutation | `schedule` (`:max-admissions`, `:max-stack` checked before `assoc`), `fetch-values` (`:max-values` checked before commit) | Refines. |
| `OneValueScanNormalization.dfy`, `LogicalScanCursor.dfy`, `ChunkedScan.dfy`, `BoundedSidecar.dfy` — one released value per transition, resume from the logical bound, bounded newest-first buffers | `release-one`, `retain-buffer`, `evict-to-cap`, residual `:bound-eid` | Refines. Physical width (`:physical-chunk-size`, default 64) is not an ordering input; the residual's `:bound-eid` is authoritative and eviction re-reads from it. Micro-inefficiency in §4.7. |
| `SealedVectorOrder.dfy`, `ReadRankCertificate.dfy`, `StaticDirectionIndex.dfy` — alternatives strictly sorted by `(rank, ordinal)`, 0/1 shortest-read-distance certificate with a linear checker, forward index by target node / reverse index by head | `sealed-plan/assign-ordinals`, `zero-one-distances`, `witness-arrays`, `valid-certificate?`, `indexes` | Refines. The checker is stricter than the model in one place (it demands `hops[node] = hops[to] + 1` where the model demands strict decrease), which is sound. `local-read-cost` and `order-contract` duplicate the same constants (§4.8). |
| `EaclForwardGrounding.dfy`, `EaclForwardProducer.dfy`, `EaclReverseProducer.dfy`, `GroundedPositiveProgram.dfy` — the four rule forms and their forward consequences / reverse predecessors | `sealed-plan/node-rules`, `stable-reducer/grant-successors`, `reverse-goal-work`, the eleven work kinds in `step` | Refines. Every scan descriptor's (subject-type, subject, relation, resource-type) binding matches the corresponding rule form; the reverse `:relation` and `:arrow-relation` arms filter to the requested subject type exactly as `RuntimeBaseOwner` binds the principal. |
| `LookaheadPagination.dfy`, `BoundedPageBuffer.dfy`, `StablePagination.dfy`, `RelayEdgePagination.dfy`, `EdgeBoundaryAuthentication.dfy`, `RelayCheckpointExecution.dfy` — page + exactly one lookahead, one-based edge ordinal + identity, forward `after` resumes at the ordinal, backward `before` runs forward through the edge as validation | `stable-page/edge-page`, `deliver-page`, `state-at-boundary`, `v8/stable-items`, `validate-stable-bound!` | Refines. `state.discovered = delivered + count(pending)` holds on every path; the backward window is `results[max(0, ordinal-1-size) .. ordinal-1)`. |
| `ReducerCheckpoint.dfy`, `RuntimeCheckpointComposition.dfy`, `WeightedCheckpointSlot.dfy`, `ProgressCheckpoint.tla` — latest-only, nonregressing, checkpoint is reducer state plus the undelivered lookahead, never an answer | `checkpoint-put!`, `checkpoint-hit`, the `:pending` segment | Refines for the routed client (its checkpoint key pins the native revision). The standalone `stable-page/page` API violates the "exact execution identity" premise: §2.2. |
| `ExactCountComposition.dfy` — the exhausted scalar discovered count is exact; a truncated run is not | `stable-route/count-resources`, `count-subjects` | **Does not refine when the run stops at the fixed target instead of exhausting**: §2.1. |
| `AtomicAttempt.tla`, `DescriptorIdentity.dfy`, the three-outcome adapter result, retry under the original deadline, service-edge admission, capability record (specs `bounded-physical-execution`, `remote-backend-enumeration-efficiency`) | `eacl.engine.physical` | Implemented and unit-tested, but **not installed on the routed public path**: §3.1. |

## 2. Bugs

### 2.1 S1/S2 — exhaustive counts, point checks and bare `:last` are silently capped at 1,000,000 — **Fixed**

`eacl.engine.stable-route/exhaustion-target` (1,000,000) is the reducer
target for `count-resources`/`count-subjects` without `:count-limit` and for
`check-eids`, and `stable-page/edge-page` uses
`reducer/default-max-admissions` (also 1,000,000) as the target for the
bare-`:last` window. The reducer stops when `discovered >= target` and the
routes read the discovered count as the exact answer without checking that
the stack is empty.

Reproduced on the nREPL with a synthetic single-relation plan and a fetch-fn
that yields 1,000,001 resources under raised limits:

```clojure
{:discovered 1000000, :stack-remaining 1, :true-count 1000001}
```

`count-resources` returns `{:count 1000000 :truncated? false}` for that
subject; `check-eids` returns `false` for a subject that is the 1,000,001st
reverse result; a bare `:last` page returns the wrong final window. Under
the default public limits (`:max-derived-grants 100000`) the run fails typed
before it can reach the cap, so this is S2 for default clients — but the
deployed demo raises the limits to count exactly one million resources
(`tasks.md` §10 records the 1,000,000 count), which makes the deployment one
extra resource away from a silently wrong exact count (S1 there).

Fix applied: `eacl.engine.stable-reducer/exhaustion-target` is positive
infinity and is the target of every exhaustive route (`stable-route`
counts and `check-eids`, `stable-page` bare `:last`), so a run ends only at
an empty stack or a typed `:max-admissions`/`:max-values` failure.
`physical_route_test/exhaustive-runs-are-unbounded-test` drives 1,000,001
results through `run-forward` and `run-reverse` and asserts the exact count
with an empty stack (3.5 s).

### 2.2 S2 — the standalone `stable-page/page` API can resume a checkpoint recorded at another basis — **Fixed**

`stable-page/checkpoint-key` is `(canonical-digest token-domain (dissoc binding
:basis))` and `checkpoint-hit` matches only `(ordinal, boundary)`. Sequence:
page 1 at basis B1 publishes checkpoint C1 (transitions t1); the DB advances
to B2; page 1 at B2 recomputes the same prefix and offers C2 with `t2 <= t1`,
which the nonregression rule rejects, so C1 stays under the key; a
continuation with the B2 token (same ordinal and boundary) hits C1 and resumes
B1's stack/admitted set against B2 data. Because admission is exact, a merge
point admitted at B1 through a branch that no longer exists at B2 suppresses
the same entity when B2 reaches it later through another branch — results are
dropped. `token-rejection-test` only covers the old token at the new basis,
not the new token against a stale checkpoint.

The routed client is not affected: `v8/stable-lookup-page` keys checkpoints on
`[fingerprint native-revision traversal subject-type anchor-eid size]`. Only
`stable-page/page` (used by `stable_page_test` and nothing else) has the gap.

Fix applied: `checkpoint-key` digests the whole execution binding, basis
included. `stable_page_test/checkpoint-identity-includes-the-basis-test`
publishes page 1 at two bases, asserts two distinct checkpoint identities
in the store, and that the new basis's continuation equals its own pure
replay.

### 2.3 S3 — spec/doc claim three-outcome reads, retry, bulkhead and capability qualification on the routed engine; the routed engine installs none of them — **Wired in the follow-up pull request `agent/wire-physical-execution`**

`eacl.engine.physical/classified-fetch-fn`, `retrying-fetch-fn`,
`make-service-admission`/`with-admission`/`with-replay-admission`,
`topology-capabilities`, `stable-discovery-qualified?` and `telemetry` are
implemented and covered by `physical_route_test`, but the only function the
routed engine uses is `execution-cut-point` (`v8/stable-cut-point`). The
reducer's `adapter-fetch-fn` calls `backend/invoke` directly, so an adapter
exception propagates raw, no read is ever retried, no enumeration slot is
held, and no adapter declares a capability record (task 7.7 said "per-adapter
declarations join the 9.1 splice"; 9.1 landed without them).
`docs/stable-discovery-engine.md` §Failure semantics and §Topology
qualification described the routed behaviour as if wired; this branch
rewords them (see §5). Left as a wiring decision, not repaired here: installing
`classified-fetch-fn`/`retrying-fetch-fn` on the routed path would wrap
every adapter exception — including the typed `:eacl/backend-contract-violation`
thrown by the runtime guards, which today surfaces unchanged — as
`:eacl.scan/failure` and retry it up to three times, a public error-shape
change that the pinned error contracts do not authorize; the bulkhead needs
a new client option. Both belong to a scoped change with their own tests.

### 2.4 S3 — `with-replay-admission` never removes exhausted keys — **Fixed**

`(update-in [:replays key] dec)` in the `finally` leaves a zero entry per
continuation key forever, so a long-lived admission atom grows with every
distinct key. Fix applied: the ledger dissocs a key when its count returns to zero.

### 2.5 S3 — the Datomic client still accepts retired cursor kinds in cached pages — **Fixed (dead-code sweep)**

`eacl.datomic.core/cursor-result` accepted `:lookup-eid` and
`:recursive-logical` (order-ABI 2) cursors, and `eacl.relay/transform-edge-ids`
listed them; nothing has minted either kind since the stable engine was
routed, and `validate-stable-bound!` rejects them with
`:eacl.pagination/wrong-cursor-kind`. A cached page carrying them would have
been served and then failed on continuation. Removed in this branch (§6);
`engine/recursive-cursor-version` and `recursive-order-abi` went with them.

### 2.6 S4 — `engine-version` did not change with the public order ABI — **Fixed**

The answer-cache semantic key (`:engine-version 8`) is unchanged although the
public order changed from global entity-id order to stable first-discovery
order. Caches are in-process today, so nothing survives a restart, but a
future external cache store would serve pre-stable pages under the same key.
Fix applied: the answer-cache semantic keys of the Datomic client and the
shared client carry `:order-abi engine/stable-order-abi`.

### 2.7 S4 — `Datahike :object-id->internal` and Datomic `object-eid` accept raw numbers as external ids — **Left as designed**

`(if (number? object-id) object-id …)` / `(d/entid db object-id)`: a numeric
external id is taken as an internal entity id without checking that the
entity exists or carries `:eacl/id`. This is the documented v7 behaviour
("EACL ID Configuration") and the engine tolerates unknown eids (empty
scans), so it is not a wrong answer; it is listed because a caller that
mistakes a database id for an object id gets an empty answer instead of the
schema-name error the other id shapes receive. Left as designed (the README
documents numeric ids as internal ids).

### 2.8 S4 — Datomic `impl.indexed/evict-permission-paths-cache!` resets caches that no longer exist — **Fixed (dead-code sweep)**

It `some->`s `:traversal-analysis` and `:recursive-plans`, keys the schema
cache map stopped carrying when the routing analysis was retired. Dead
branches; the function's remaining resets are still correct.

### 2.9 Robustness note — `schedule` does not deduplicate inside one successor batch — **Fixed**

`StableReducer.Admit` folds `seen` through the batch, so two equal successors
in one batch admit once. `schedule` filters the whole batch against the
admitted set *before* adding any of it, so two equal work-ids in one
`new-work` vector would both push and both process. Today no batch can
contain two equal ids: forward consumers of a grant are distinct rules
(distinct ordinals, or distinct `(node, eid)` for self-permissions), and
`assign-ordinals` fails closed on byte-identical rules. Fix applied: `schedule` threads a per-batch set so equal work-ids inside
one batch admit once and nil items are skipped without truncating the batch
(`stable_reducer_test/schedule-admits-each-work-id-once-per-batch-test`).

### 2.10 S2 — Datomic `expand-permission-tree` ignores a custom object-id codec — **Fixed**

`eacl.datomic.core/make-client` builds its snapshot adapter with
`:entid->object-id` but without `:object-eid-fn`, so the adapter's
`:object-id->internal` is always `eacl.datomic.db/object-eid` (hardwired
`[:eacl/id id]` for strings, `d/entid` otherwise). Every other operation
internalizes ids first through the client's `object-id->entid`
(`:object-id->lookup-ref` / `:object-id->ident`), but
`spiceomic-expand-permission-tree` hands the external `(:resource query)` to
`eacl.permission-tree/expand`, which resolves the root through the adapter.
With a custom codec the root resolves to nothing (an "absent resource"
topology with no subjects) or to a different entity whose `:eacl/id` happens
to equal the id. DataScript/Datahike pass the client codec into the adapter
and are unaffected. No test exercises expansion with a custom codec.
Fix applied: `make-client` passes `:object-eid-fn` to the adapter (numbers
pass through as internal ids; everything else resolves through the client's
`object-id->entid`). Note the `:db/ident` codec used by the existing config
test happens to work by accident because `d/entid` accepts idents, which is
why nothing caught this; `config_test/expand-permission-tree-uses-the-client-id-codec-test`
uses a lookup-ref codec whose external ids differ from `:eacl/id` and
asserts the codec client's tree equals the default client's tree.

### 2.11 S2 — Datahike temporal-fallback snapshots misreport their identity — **Fixed**

`select-exact` may select `(d/as-of (d/db conn) revision)`; Datahike's
`AsOfDB` carries only `[origin-db time-point]`, and the adapter reads
`(:store (:config db))`/`(:attribute-refs? (:config db))` for `:snapshot-id`
and `direct-writer?`/`temporal-history?` from `(:config db)`, which are nil on
the wrapper. The exact-snapshot proof digest minted from such a snapshot
therefore differs from the one minted on the live db, so an exact
continuation on the temporal fallback can be rejected as stale, and
`:cache-basis` carries a bogus database id. (The commit-as-db path used when
retained commits exist is unaffected.) Fix applied: `eacl.datahike.backend` reads configuration through Datahike's
`IDB/-config` protocol (`db-config`), as `eacl.datahike.db` already did, and
bounds the published store identity to `{:backend :id}` (`store-identity`).
`consistency_v3_test/temporal-fallback-adapter-keeps-its-source-identity-test`
selects the exact snapshot through the temporal fallback and asserts equal
store identity, attribute representation, and consistency capabilities.

### 2.12 S2 — Datomic exact cursors read a `noHistory` stamp through `as-of` — **Fixed (identity-based exact acceptance)**

`:eacl/relation-version` is declared `:db/noHistory` yet the exact-cursor
path selects `(d/as-of …)` and reads that stamp for the proof frame. Once
Datomic's index job discards superseded noHistory values, the historical read
returns nil, the proof frame is incomplete, and a valid cursor is rejected
with `:eacl.consistency/history-divergence`. In-memory test databases never
run the index job, which is why nothing has caught it. The effect cannot be reproduced on `datomic:mem` (`request-index` there
leaves the historical value visible), but Datomic documents `as-of` reads of
`noHistory` attributes as unreliable, so the exact fallback no longer
depends on them: `eacl.datomic.core/exact-fallback-decision` accepts
`:exact` when the generated decision reports divergence purely because the
exact snapshot's dependency proof was unreadable while its native revision
and execution identity are the cursor's own (a snapshot's proof is, by
identity, the proof recorded at minting). Readable stamps that differ still
diverge, and another revision or source is never rescued;
`consistency_v3_test/exact-fallback-tolerates-unreadable-historical-stamps-test`
covers all four cases against the real decision kernel.

### 2.13 S3 — Datomic writes fail open when the schema generation is unstamped — **Left as designed (confirmed by the repository's own tests)**

`tx-schema-version-guard` adds no CAS when no `:eacl/schema-version` exists,
so relationship writes on a database whose schema generation was never
published proceed unguarded; DataScript and Datahike throw
`:eacl.cache/generation-unprepared` in the same state. Left as designed: on
Datomic the stamp is written by `write-schema!`, and databases whose
definitions were installed as data (the module's own fixtures and every v7
database that never ran `write-schema!`) have no stamp; failing closed would
reject their writes. The unstamped state is exact-cache-only (the proof frame
is incomplete), so no cached answer can be wrong; the missing guard only
narrows protection against a schema removal racing a delayed relationship
write. Making the stamp mandatory is a v8 upgrade decision. A fail-closed variant
was tried during the fix round: `schema_basis_test`'s
`unstamped-client-does-not-latch-schema-test` and
`unstamped-client-does-not-cache-lookup-results-test` deliberately write on
an unstamped database and assert the unlatched, exact-cache-only regime, so
the code was restored and the regime is now documented on
`tx-schema-version-guard` itself.

### 2.14 S3 — DataScript/Datahike `:create` conflicts are not serialized and `delete-object!` is one unbounded transaction — **Delete semantics documented; create serialization see §2.19**

Two concurrent `:create`s of one relationship both succeed on DataScript and
Datahike (only a schema-fence CAS guards the transaction), where Datomic
reports `:eacl/relationship-conflict` to the loser; a fence-CAS failure on
DataScript surfaces raw `{:error :transact/cas}`. `delete-object!` on both
realizes every retraction into one transaction (Datomic streams batches of
1,000), which is a heap and latency cliff for high-degree objects on
persistent Datahike stores. Left as designed: serializing creates needs a
per-relation CAS and retry loop on the shared client (a concurrency-model
change whose end state — the relationship exists once — is already correct),
and batching the delete trades the single transaction's atomicity for
Datomic's non-atomic batches; the delete's per-backend semantics (Datomic: batches of 1,000, a reader can
observe a partially deleted object between batches; DataScript/Datahike: one
atomic transaction) are now stated in the README's relationship-maintenance
section, and create serialization is tracked as its own change (§2.19).

### 2.15 S3 — Datahike `select-exact` maps `:read-failed` to "unavailable" — **Fixed** (and the store-config exposure)

A transient store read failure is reported as a permanently expired snapshot
(nil) instead of the classified retryable failure the surrounding code
documents. Also, Datahike's `:snapshot-id` embeds the raw `:store` config,
which for `:jdbc`/`:s3` stores can contain credentials and is returned to
callers as `:cache-basis`. Fix applied: `:read-failed` is no longer treated
as absence (a GC'd commit already surfaces as `commit-as-db` returning nil,
and the versions in use raise no `:read-failed` type at all), and
`:snapshot-id` carries only the store's `:backend` and `:id`.

### 2.16 S3 — the stable-discovery release-assurance gate was broken — **Fixed**

`formal/stable-discovery/verify-fast.sh` still loaded
`source_refinement_bridge.clj`, whose `ns-resolve` calls bind to
`eacl.engine.v8/compile-recursive-rules` and four other vars the 9.2
excision removed, so the gate died with a NullPointerException after the
Dafny and TLC stages; and its Dafny escape-hatch scan ran through `rg`, so on
a machine without ripgrep the scan was silently skipped instead of failing.
Nothing runs this script in CI. This branch removed the retired bridge, moved
the scan to `grep -rE`, and re-ran the gate: 506 obligations, all mutation
controls killed, four bridges green, 6 s wall time.

### 2.17 S4 — cross-backend error-shape drift — **Fixed**

Same condition, different shapes: missing relation on write carries no
`:type`/`:eacl/error` on any backend; `:eacl.basis/selection-failure` carries
no `:type`; freshness timeouts differ in `:reason` and key names between the
Datomic client and the shared adapters; unknown object is `{:object {:type
:id}}` in three places and `{:object-id id}` in `eacl.datomic.impl`; concurrent
schema writes report `:expected-generation` (DS/DH) versus
`:expected-version` (Datomic); invalid entity ids are typed on Datahike, raw
DataScript/Datomic exceptions elsewhere. Partially fixed: the
missing-relation write error now carries the same
`:type`/`:eacl/error :eacl/unknown-relation-or-permission` shape (plus
`:operation :write-relationships`, `:definition`, `:relation`) on all three
backends and `:eacl.basis/selection-failure` carries `:type`; the second fix round
completed the harmonization additively: the Datomic client's freshness
errors carry `:reason :freshness-timeout`/`:head-behind`/`:sync-failed` plus
`:requested-order-hint`/`:observed-order-hint` beside their `:requested-t`/
`:observed-t`; every `:eacl/unknown-object`, `:eacl/relationship-conflict`,
`:eacl.schema/concurrent-write` (now also `:expected-generation`/
`:actual-generation`/`:backend-error` on Datomic) and
`:eacl.schema/relation-in-use` error carries `:eacl/error` equal to its
`:type`.

### 2.18 S3 — untyped validation errors on writes, schema writes and page requests — **Fixed**

Adopted from the 2026-08-15 SpiceDB differential report (its T4, T8, T9, S6,
S7, R-3, R-4, R-5). Relationship writes now validate their schema names
before any endpoint resolves, with the read side's typed taxonomy
(`eacl.schema.errors/validate-relationship-write!`, called by both clients):
an unknown definition is `:eacl/unknown-definition`, an unknown relation or a
subject type the relation does not declare is
`:eacl/unknown-relation-or-permission` (the latter with `:reason
:subject-type-not-declared`); the backends' own "Missing Relation" throws
carry the same category as a fallback. Schema writes: reference-validation
failures are `:eacl.schema/invalid-reference` (with the `:errors` vector),
unsupported features are `:eacl.schema/unsupported-feature`, and every
already-typed parser error (`duplicate-relation`, `duplicate-permission`,
`duplicate-definition`, `name-collision`, `parse-error`, `paren-arrow`)
carries `:eacl/error` too. `validate-schema-references` also rejects a
relation whose subject type is not a defined definition (SpiceDB rejects
`relation reader: nobody`) whenever the schema carries its definition list,
i.e. on every `write-schema!`; data-installed schemas that pass relations and
permissions alone are unaffected. Page-request errors are
`:eacl.pagination/invalid-page-size` (out-of-range `:first`/`:last`, with
`:size`/`:max`) or `:eacl.pagination/invalid-page-request` (both directions,
both bounds, a bound without its direction, `:cursor`/`:limit`, list keys on
a count); a nil bound keeps `:eacl.pagination/invalid-cursor`. Datomic's
private copy of `validate-schema-references` (§3.4) is replaced by the shared
one. Tests: `schema_error_contract_test/relationship-writes-share-the-schema-taxonomy-test`
and `schema-and-page-request-errors-are-typed-test`.

### 2.19 S3 — DataScript/Datahike concurrent `:create` of one relationship both succeed — **Tracked**

SpiceDB and the Datomic client report `:eacl/relationship-conflict` to the
loser; on DataScript and Datahike the plan is computed outside the
transaction, so two concurrent creates of the same relationship both commit
(the second add is a redundant datom) and both callers see success. The end
state is correct (one relationship, both halves, one stamp); only the
duplicate-create semantics under a race differ. The repair — moving the
existence check into the transaction (`:db.fn/call`) or a per-relation
stamp CAS with the retry loop the Datomic client already runs — is a write
path change on the shared client with its own interleaving tests, opened as
its own change rather than folded into this branch (see the pull request
description for its status).

### 2.20 S4 — the published `eacl-datomic` POM pinned `com.datomic/peer 1.0.7622` — **Fixed**

`src-build/eacl/build/config.clj` declared `1.0.7622` while the module
requires `1.0.7705` (the deployed transactor is 7705 and the older peer
cannot read its databases); aligned. From the differential report's R-7.

### 2.21 S4 — README claims corrected from the differential report — **Fixed**

The `write-relationships!` "collection of `[operation relationship]`"
sentence (both EACL and SpiceDB reject that shape; records or
`{:operation … :relationship …}` maps work), the SpiceDB-differences section
(EACL evaluates cycles as a fixed point and has no dispatch depth limit for
checks and lookups; identifier and name grammars are broader than SpiceDB's;
a relation name is accepted in the permission slot only by
`expand-permission-tree`), and the per-backend `delete-object!` semantics.

## 3. Discrepancies between specification, documentation and code

### 3.1 Section 7 of `adopt-stable-discovery-enumeration` (see §2.3)

Tasks 7.1, 7.3, 7.5, 7.7 and 7.8 are ticked; their deliverables exist as
library functions and tests only. Task 7.7's "per-adapter declarations" do
not exist in any backend module.

### 3.2 Two implementations of the permission-schema closure

`eacl.engine.v8/calc-permission-paths` + `calc-permission-relationship-eids`
(feeding cache dependency sets and Relay cursor dependency proofs) and
`eacl.engine.sealed-plan/reachable-rules` (feeding traversal) walk the same
definitions independently. They agree on validated schemas (write-time
`validate-schema-references` rejects dangling arrow targets on any subject
type), but they disagree on failure: the path walker warns and yields an
empty path set, the plan compiler fails closed with `:eacl.plan/compile-error`.
Deriving `permission-relationship-eids` from the sealed plan
(`(distinct (keep #(or (:relation-eid %) (:via-relation-eid %) …) rules))`)
would delete ~200 lines and one compile per request.

### 3.3 Two client orchestrations

`eacl.datomic.core` (2,978 lines, its own AEAD `eacl4_` page tokens,
`internal-page?`, `cursor-result`, consistency selection) and
`eacl.client.orchestration` + `eacl.relay` + `eacl.cursor` (Datahike,
DataScript) implement the same public contract twice. The shared path
already targets the v8 adapter SPI that Datomic implements
(`eacl.datomic.backend/snapshot-adapter`); porting the Datomic client onto it
is the single largest simplification available. `PORTING.md` in the Datahike
module records the shape of that port.

### 3.4 Datomic keeps a private copy of `validate-schema-references`

`eacl.datomic.schema/validate-schema-references` (lines 353–470) is the same
algorithm as `eacl.schema.model/validate-schema-references`, which Datahike
and DataScript alias. Only comments differ.

### 3.5 Continuation context callbacks the stable engine never calls

`eacl.continuation/private-context` still exposes `:get-page`, `:put-page!`,
`:get-heads`, `:put-heads!`, `:evict!` (the recursive-page and
acyclic-frontier kinds of the retired engines) and `validate-context!`
requires them; the stable engine uses only `:get`/`:put!`
(`stable-page/context-store?`, `v8/stable-checkpoints`). Left in place in this
branch because the contract check is part of the tested public shape;
removing the five callbacks and the `:acyclic-continuation` kind is a
one-namespace change.

### 3.6 `relay/cursor-emission-order-version` docstring

The value (2) is load-bearing in the scope digest; the docstring still
described "bounded, ordered scan waves" of the merge engine. Reworded in this
branch.

### 3.7 `:limit` never reaches an adapter, and `:direct-match?` is production-dead

The reducer puts `:limit` in the read-demand descriptor, but
`adapter-fetch-fn` forwards only `:direction`/`:bound-eid`/`:inclusive-bound?`;
every backend scan is a lazy ascending seek from which the reducer takes the
first 64 values, so no adapter honours or ignores a limit. The
`:direct-match?` adapter operation is exercised only by certification tests
(`can?` runs the reverse traversal), which is why §4.2 can adopt it without a
new adapter obligation.

### 3.8 The formal ledgers still describe the retired engines

`formal/verification/manifest.edn` records `:indexed-recursive-public-engine`
as routed authority; `assurance-matrix.edn` maps `AcyclicEngine`, `Indexed*`,
`OrderedMerge` and `CursorCost` to `eacl.engine.v8` consumers that no longer
exist (`enumeration-route`, `run-generated-traversal`,
`compile-recursive-plan`, …); `verified-authority-suites.edn` still lists the
routing/acyclic/indexed decisions as required although the runner requires
only `:consistency-plan :current-cache-decision :cursor-continuation
:relationship-page`; and the counterexample replay test pins regression vars
that live in the indexed smoke sections. All of this is the inventory of the
formal cut recorded as task 9.2 of `adopt-stable-discovery-enumeration`; the
dead-code sweep in this branch deliberately stops short of it because
deleting a `.dfy` leaf requires re-pinning `manifest.edn`,
`bin/validate-verification-manifest` (which hard-codes the theorem keys and
recomputes obligation counts), `src-build/eacl/build/module.clj` (jar entries
for `AcyclicEngine/__default.class` and `IndexedTraversal/__default.class`),
`eacl.formal.production-kernel` imports, `verified_kernel.cljc`'s
`IndexedTraversalKernel` protocol and validators, `portable_indexed.cljc`,
the CI `formal.yml` steps that gate the indexed CLJS traversal, and the smoke
suites — a change that must be verified with `bin/formal verify` (~46 solver
minutes) and the full formal workflow, not with the unit battery. Sizes:
`AcyclicEngine` 2,701 lines, `Indexed*` 26,252 (ten files), `OrderedMerge`
1,478, `CursorCost` 78 — 30,509 of the 45,063 Dafny lines under `formal/dafny`.

## 4. Optimizations (all keep the proven semantics)

### 4.1 Key the sealed-plan cache on the schema generation, not the native revision

`v8/stable-plan` keys plans on `[backend-id source-scope source-lifecycle
native-revision root]`, so every transaction — including ones that touch no
schema — invalidates every plan and the next request of each root re-reads
its permission/relation definitions, re-encodes every rule canonically
(`secure-format/encode-canonical` calls `edn/read-string` per keyword during
validation), re-runs the 0/1 BFS, and re-digests the plan. Plan compilation
"consults no relationship data" by construction, so the plan is a pure
function of the schema generation. Key on the proof-frame schema stamp
(`v8/schema-version`) when it is complete, falling back to the native revision
when it is not. Cursor fingerprints are unaffected (they never included the
basis).

### 4.2 Point checks (`can?`) scan every subject at the leaf level

`stable-route/check-eids` answers "does subject S hold P on R" by reverse
enumeration from R until S is emitted. For a resource with many
subjects of that type (a public folder, an organisation), the reverse leaf
scans (`resource->subjects`) stream every subject before S. The plan already
knows the leaf rules; at the leaf the check only needs existence of
`(R, relation, S)` — the adapter has `:direct-match?` for exactly that — and
for `:arrow-relation` leaves the existence of `(intermediate, target-relation,
S)`. Replacing leaf enumeration by an existence probe keeps the traversal
over intermediates and turns the leaf cost from O(#subjects) to O(1) per
intermediate. Because a probe returns at most one value, the change is a
different `fetch` for two work kinds, not a new reducer.

### 4.3 Let dependency-equal continuations hit checkpoints

The datomic and shared clients build a `:proof-equivalent` continuation
scope (schema stamp + scalar dependency frontier, `ScalarFrontierCoherence.dfy`)
precisely so a write outside the query's relationship closure keeps
continuation state reusable, but the stable engine's checkpoint key pins the
native revision, so any write between two page requests forces a replay of
the whole prefix. When the request proof is complete, the checkpoint key can
carry the proof digest instead of the native revision: equal proof implies
equal relationship data in the closure implies an identical reducer state.
Cursor validation stays where it is (the token still pins the basis and the
Relay decision still selects the adapter); only the private store's key
changes.

### 4.4 Backward pages replay from the origin

`edge-page`'s `before` branch always runs from scratch to the boundary
ordinal. A `:last/:before` walk of an N-result set costs O(N²/page-size)
reads. Two cheap improvements that keep the proven backward semantics: reuse
the client's answer/visited-page cache for the previous page (the Relay
page-navigation cache already learns the opposite-direction alias), and let
`state-at-boundary` accept the latest checkpoint whose ordinal is `<=` the
requested boundary and resume forward from it (the checkpoint proves the
prefix; the run still validates the boundary before publishing).

### 4.5 Counting materialises and re-checks results

`finish` builds a persistent vector of every emitted id and runs
`(count (distinct results))` on it, and `run-loop` records every result even
when the caller only wants `:discovered`. For an exhaustive count that is one
million conj!s, one million-element hash set, and the invariant walk. A
`:collect-results? false` option (results stay a scalar) removes both; the
uniqueness invariant is already enforced by admission.

### 4.6 `execution/check!` per transition calls `System/nanoTime`

`stable-cut-point` installs a per-transition deadline/cancellation check;
each call is a `nanoTime`. Checking every 64 transitions (or when a physical
command is issued, which is where time actually passes) is indistinguishable
for a 30 s deadline and removes ~1 M syscalls from a million-transition run.

### 4.7 Sidecar bookkeeping on the hot path

`retain-buffer` recomputes `maximum-sidecar-values` by summing every
retained buffer on every release (O(cap) per released value) and retains an
empty buffer purely to carry `:more-physical? true`, which behaves exactly
like an absent entry (both re-fetch from the bound) but occupies a slot.
Track the value total incrementally and skip retention when `values` is
empty.

### 4.8 One source of truth for the rank costs

`sealed-plan/order-contract :rank-costs`, `local-read-cost`, and the literal
`(if (= :self-permission …) 0 1)` in `permission-edges` encode the same
constants three times; only the first is fingerprinted, so a change to the
other two would silently keep the fingerprint. Derive both from
`order-contract`.

### 4.9 Datomic `find-permission-defs` pulls `[*]` per definition

Called on every plan compile (§4.1 multiplies it) and by
`permission-root-defined?`. Pull the six named attributes.

### 4.10 Backend request overheads worth removing

- `read-schema` is executed on every public request (Datomic: two `d/q`
  plus pulls; the shared client does the equivalent) only so
  `schema-errors/validate-permission-request!` can check name sets; cache the
  catalog by schema generation (the derived-schema registry already keys by
  it).
- Datomic resolves each relationship write's relation through a three-clause
  `d/q` (`find-relation-eid`) and resolves both endpoints twice; use the
  unique tuple lookup as the other backends do.
- Datomic `all-permission-nodes` is a `d/q` join instead of an AVET tuple
  scan; `find-relations` pulls every relation and filters in memory per
  `read-relationships`.
- Datahike resolves `attr-repr` with a `d/entity` per scan under
  `:attribute-refs?`; memoise per db config. Its temporal-wrapper scan path
  realises and sorts the whole endpoint per command (an upstream 0.8.1759
  seek workaround) — a paginated walk on a temporal fallback is quadratic in
  the endpoint degree.
- Every relationship write performs an entity lookup per endpoint for the
  identity guard when no guard is supplied.

## 5. Documentation corrected in this branch

Statements that presented retired mechanisms as the current contract were
rewritten against the source; historical records were labelled, not
rewritten. Verified as stale and corrected:

- `README.md` — Performance and pagination sections described the acyclic
  `d/index-range` path, Datomic-eid order for "acyclic" lookups, per-path
  cursor frontiers, and 16,384-eid frontier counts; three `eacl_z3_` token
  examples (`eacl_z3_` is rejected with `:eacl/zed-token-upgrade-required`;
  tokens are `eacl_z4_`); the traversal-quantum cancellation wording; the
  Datomic-only 5-minute page-token default stated as universal.
- `AGENTS.md` — the `dev/restart-backend!` sequence (no such namespace
  exists), the five-namespace "run all tests" form (CI runs the
  `cognitect.test-runner` battery), the `test/eacl/bench/` path; `.rules/AGENTS.md`
  linked `clojure-rules.mdc` (the file is `.md`).
- `docs/stable-discovery-engine.md` — presented the standalone `eacl_sd1.`
  token and its `:eacl.page/*` errors as the public cursor contract; described
  three-outcome reads, retries, the bulkhead and topology qualification as
  installed on the routed path (§2.3).
- `docs/cache.md`, `docs/v8-subproblem-cache.md` — described relationship
  projection and completed-denotation tiers that no engine path publishes
  into; the subproblem-cache guide is rewritten around the live `:answer`
  tier and identity projections.
- `docs/formal-verification.md`, `formal/README.md`,
  `formal/verification/trusted-boundary.md`,
  `formal/verification/production-decision-inventory.md`,
  `formal/verification/temporal-model.md`,
  `formal/verification/final-assurance-audit.md`,
  `formal/verification/integration-spike.md`,
  `docs/formal-verification-corrections.md` — stated the generated indexed
  kernel as the JVM traversal authority, Kosaraju routing, the ordered merge
  and `can-uncached*` as production; quoted stale ledger counts (1,404
  definitions / 58 files / 21 keys / 63 roots) and `eacl.engine.v8/complete-logical-page`
  and a `cloudafrica/eacl` coordinate; the decision-inventory table now names
  the sealed-plan/reducer/page decisions and the retired-engine paragraphs are
  labelled historical.
- `docs/release-notes-v8.0.md` — the six-function SPI, `:coherence-authority`
  bullets and upgrade advice, projection/denotation managed reuse, the
  per-relation generation *vector* (the descriptor is a scalar frontier),
  cursor payload v10 (it is v11), route-specific ordering, the routing
  certificate/merge dispatch, and 64-command scan waves.
- `docs/v8-backend-adapter-boundary.md`, `docs/v8-backend-modules-and-upgrade.md`,
  module READMEs, `modules/eacl-datahike/PORTING.md` — "populated relation
  checks" and "cursor frontier identity" (not adapter operations), SCC /
  fixed-point engine wording, the missing `safe_retraction.clj` row.
- `formal/stable-discovery/README.md` — the "quarantined pending deletion"
  list named seven files that no longer exist; task-status sentences that
  predated sections 4–6; the bridge count.
- `docs/index.md` links the engine doc and the reports; `docs/adr/` and
  `docs/plans/` gained READMEs marking them historical, the stray
  `007-… copy.md` is renamed, ADRs 008/009 carried the 007 header, and the
  2026-08-07 exploration prompt is moved from `adr/` to `plans/`.
- `docs/benchmarks/v6-vs-v8.0.md` named a `:live-results` option that does
  not exist (`:remember-answers`) and is labelled historical;
  `docs/bug-fix-arrow-to-relation-v7.md` names v7 functions and paths and is
  labelled historical.
- Source docstrings: `eacl.relay/cursor-emission-order-version` described the
  merge engine's "scan waves"; `eacl.engine.v8/*recursive-traversal-stats*`
  described the routing invariant.

Not changed, by decision: `docs/reports/*` (dated records),
`docs/plans/*` and `docs/adr/*` bodies (historical, now labelled),
`formal/verification/*.edn` evidence records and `manifest.edn` (re-pinned by
the formal cut, §3.8), and the archived OpenSpec changes.

## 6. Dead code removed in this branch

Every removal was checked against `modules/*/src`, `modules/*/test`,
`formal/`, `bin/`, `src-build/`, the workflows and the two demo repositories
(clj-kondo analysis plus grep), then the CI-equivalent battery was re-run.
The `public-source-closure.json` ledger is regenerated (`61` roots, `1,290`
definitions).

- `eacl.impl.spicedb` — a four-line empty stub namespace.
- `eacl.engine.v8` — the inert acyclic observation vars `*acyclic-route?*`
  (never bound anywhere), `*acyclic-work-stats*` and `add-acyclic-work!`
  (only ever incremented under the never-true route flag),
  `*inactive-recursive-cycle-guards*` (never bound or read), `*count-stats*`
  (bound by the Datomic façade, never read), the retired cursor constants
  `recursive-cursor-version`/`recursive-order-abi`, `find-relation-def`
  ("retained for tests"; no test used it), `schema-version-stamp`, and
  `permission-schema-components` with its six private Kosaraju helpers
  (routing analysis of the retired engine). `all-permission-nodes` stays
  because the dispatch ledger requires every required adapter operation to
  have a dispatch site (§6, note below).
- `eacl.datomic.core/cursor-result` — the `:lookup-eid` and
  `:recursive-logical` branches; `eacl.relay/transform-edge-ids` — the same
  two kinds. `eacl.datomic.impl.indexed` — `all-permission-nodes`,
  `direct-match-datoms-in-relationship-index`, `schema-version-stamp`, the
  `*count-stats*` binding and the resets of cache keys that no longer exist;
  `eacl.datomic.impl` `*count-stats*` binding, `relationship-relation-id`,
  `affected-relation-ids`; `eacl.datomic.db/relation-populated?`
  (`:relation-populated?` left the adapter contract).
- `eacl.subproblem-cache` — `add-fetched-projection-values!`,
  `record-acyclic-denotation-hit!`, `record-recursive-component-hit!`
  (metric writers of the retired tiers), `lookup-layered-bound!`,
  `lookup-bound!` (declared closure roots with no callers; roots list
  updated), private `managed-cache-tier`.
- `eacl.formal.production-kernel` — dead bridge functions
  `typed-traversal-permission?`, `production-ordered-fold`,
  `acyclic-leapfrog-intersection`, `acyclic-arrow-path-decision`,
  `acyclic-path-fold`, `materialize-permission-paths` and their private
  helpers plus nine unused generated-class imports; the two type-hint targets
  those helpers carried are dropped from the reflection mutant detector.
- `eacl.spicedb.parser` — the REPL helpers `example-schema`,
  `pretty-print-tree`, `analyze-definition`, `demo`, the trailing `comment`
  block, `transform-expression`/`format-expression`/`extract-expr` (only the
  helpers used them) and the pprint require; three misplaced docstrings fixed.
- `eacl.engine.relationships/after-cursor?`; DataScript/Datahike `impl`
  `max-entid`, `find-relation-def`, `build-schema-catalog`; Datahike
  `schema/max-entid`, `db/avet-range`; DS/DH `schema/calc-set-deltas`
  aliases; DataScript `schema/schema-change-attrs`.
- Unused requires/imports (clj-kondo): `eacl.relationships.storage` in the
  three backend adapters, `eacl.datascript.db` in the DataScript adapter,
  `eacl.cache` in `eacl.datomic.cache`, `clojure.set` in
  `eacl.engine.portable-decisions`; unused destructured bindings in
  `sealed-plan`, `stable-page`, `stable-reducer`, `stable-route`.
- `formal/stable-discovery/source_refinement_bridge.clj` and its two lines
  in `verify-fast.sh` (§2.16).

Dead or test-only surface deliberately **not** removed here, with the reason:

- The retired generated indexed traversal (`IndexedTraversalKernel` and its
  validators in `eacl.verified-kernel`, the indexed half of
  `eacl.formal.production-kernel`, `eacl.engine.portable-indexed`, thirteen
  of the nineteen `verified-kernel/operations`, the twelve retired Dafny
  leaves and their generated Java, the indexed smoke suites) — the formal
  cut of §3.8; it needs the manifest/validator/build/CI re-pins and a full
  `bin/formal` run.
- `eacl.datomic.cache` provider stores (`LocalStore`, `local-store`,
  `local-continuation-store`, `safe-*`, ~500 lines) and
  `eacl.datomic.cache-store-contract` (a `src` file that requires
  `clojure.test`) — production builds its stores from `eacl.continuation` and
  `eacl.cache`; two vars are pinned present by `trusted_surface_audit_test`.
- `eacl.engine.physical` retry/admission/capability functions and
  `telemetry` — specified deliverables awaiting the wiring decision (§2.3).
- `eacl.continuation/private-context`'s `:get-page`/`:put-page!`/`:get-heads`/
  `:put-heads!`/`:evict!` callbacks and the `:acyclic-continuation` kind
  (§3.5); `eacl.backend.v8` `traversal-execution`,
  `maximum-concurrent-snapshot-read-width`, `deterministic?` accessors (the
  stored profile is the SPI seam for the concurrency change);
  `eacl.datahike.core`/`eacl.datascript.core` `datahike-*`/`datascript-*`
  operation wrappers and `cursor->token`/`default-*-cursor->*` (declared
  public roots with no callers — a public-surface decision); documented
  public API with test-only callers (`integrity`, `safe-retraction`,
  `migrations`, `prepare-cache-coherence!`, `expire-cache!`, `cache-stats`).
- `:all-permission-nodes` is a required adapter operation whose only engine
  consumer is the wrapper kept for the ledger; whether it should leave
  `required-snapshot-operations` (three backend implementations, adapter
  certification, permission-tree fixtures) is a contract decision.

## 7. Backend findings

Summarized here; details are the S2/S3 items in §2 and the optimizations in
§4.10. The three adapters share one physical layout (forward and reverse
endpoint tuples on the subject and resource entities, cardinality-many,
indexed) and their `subject->resources`/`resource->subjects` scans are
strictly ordered, unique and bound-exact on all three (DataScript and Datahike
seek at the bound and post-filter; Datomic seeks and drops the equal row),
including `:desc`, so the reducer's adapter obligations hold. Cross-backend
divergences that matter: identity of temporal-fallback snapshots on Datahike
(§2.11); Datomic `expand-permission-tree` and the client codec (§2.10);
Datomic's `noHistory` stamp on the exact path (§2.12); fail-open writes on an
unstamped Datomic schema (§2.13); create-conflict serialization and
`delete-object!` batching on DataScript/Datahike (§2.14); Datahike's
`:read-failed` mapping and store-config leak (§2.15); error-shape drift
(§2.17); text-only schema rewrites are persisted by DataScript/Datahike but
not by Datomic (`stamp-schema?` is false when no structural delta exists);
`count-relationships-using-relation` counts forward tuples only on Datomic
(its transactor guard checks both halves, so only the preflight message
differs); and the Datomic client cannot pass `:allow-empty-schema?` through
`write-schema!`. Verified sound: both halves are always written and retracted
together, `:touch` repairs half-pairs, duplicates are impossible, object
deletion covers peer halves and stamps every affected relation, safe
retraction refuses control entities on all three, and no lazy sequence
escapes into a cache.

## 8. OpenSpec archive record

Seventeen implemented changes were archived on 2026-08-15 with the
`openspec` CLI (`--yes`), in chronological order so that MODIFIED/REMOVED
targets existed when applied; only `adopt-stable-discovery-enumeration`
remains active (44/61 tasks; its task 10.7 forbids archiving before the
cleanup and release evidence are complete). Main specs grew from one
capability to 43.

- Folded: fix-audit-root-causes, upgrade-datascript-datahike-to-v8,
  redesign-cross-backend-freshness-cache,
  optimize-datascript-relationship-storage, formally-verify-eacl-engine,
  add-verified-subproblem-cache, restore-v8-enumeration-performance,
  eacl-v8-root-fixes, demand-bounded-authorization-execution,
  publish-modular-clojars-artifacts,
  add-optional-safe-retract-entity-functions, simplify-cache-coherence,
  remove-unknown-cache-coherence, implement-expand-permission-tree.
- Archived without folding (`--skip-specs`): add-intelligent-eacl-cache
  (PR #80 closed unmerged), add-consistency-aware-eacl-cache and
  harden-v7-4-cache-consistency (v7-era Datomic-only cache designs whose
  premise — coordinator, Zed tokens, pluggable stores — the v8 chain
  replaced entirely).
- Hand merges where the CLI refused to drop scenarios:
  `modular-backend-workspace` "Shared backend contract" (upgrade renamed the
  DataScript scenario to cover Datahike) and "Upgrade documentation" (the
  publish text is kept and the three still-true upgrade scenarios retained),
  "Graph-independent coherence adapter contract" and
  `backend-native-revision-consistency` "Backend capability honesty"
  (remove-unknown replaces the exact-current-only and unknown-authority
  scenarios).
- Pruned from main because an already-archived newer change contradicts
  them: `dependency-validated-authorization-cache` "Fast proof requires
  explicit writer authority", "Full-content proof is the conservative
  oracle", "Mutation identities do not collide with transaction positions";
  `portable-authorization-cache` "Exact cache validity proof",
  "Adapter-specific proof optimization"; `cross-backend-revision-consistency`
  "Authenticated causal revision tokens", "At-least freshness requires causal
  dominance", "Mutation anchors have bounded retention", "Causal modes
  require complete writer authority"; `modular-backend-workspace`
  "Backend-neutral causal snapshot contract", "Existing engine primitive
  compatibility is capability-limited"; `safe-entity-retraction-function`
  "Cache and consistency proofs advance atomically";
  `single-flight-coordination` "Deduplication preserved";
  `managed-reuse-certification` "Explicit coherence-authority posture";
  `permission-path-resolution` "Cache keys are derived from the schema
  history of the queried db value" (reverted inside its own change, issue
  #74). The stale "Backend-neutral six-function SPI" requirement is replaced
  by the shipped adapter operation contract.
- Declared as `## REMOVED Requirements` deltas on the active
  stable-discovery change (so its archive folds correctly instead of editing
  main under an active change): the routing/acyclic requirements of
  `schema-aware-traversal-routing` and `verified-enumeration-performance`,
  the route-specific order and fuel requirements of
  `keyset-recursive-pagination` and `incremental-recursive-pagination`,
  `cursor-dependency-validity` "One authenticated-and-confidential token
  codec", the four `kernel-boundary-efficiency` requirements,
  `recursion-performance-gates` "Matched-v7 latency bound", three
  `verified-subproblem-cache` generated-authority requirements,
  `nonblocking-cache-coordination` "Projection cache stores exact command
  responses", and `formally-verified-authorization-engine` "Differential
  cutover evidence without a production rollback engine".
- Residual open tasks recorded at the top of the archived `tasks.md`:
  formally-verify-eacl-engine 13.10 and add-verified-subproblem-cache 8.13
  (the independent security/formal-methods review — an external gate already
  encoded in `manifest.edn`); add-verified 8.6–8.12 (superseded or done
  elsewhere); demand-bounded 12.11–12.15, 13.1, 13.6 (certification
  ledger/bundle, tracked as unmet manifest obligations and aimed at the
  replaced evaluator), 3.1/5.7/12.4/11.4 (superseded), and 1.3, 2.7, 9.6,
  9.7, 12.8 (partially implemented: adversarial cache-candidate trace tests,
  the bounded-blocking adapter seam, idempotent writer retries, DataScript/
  Datahike concurrent-schema interleavings, demo count labels) — candidates
  for one small follow-up change.
