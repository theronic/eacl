# EACL 8.0.0-SNAPSHOT (stable-discovery engine) versus SpiceDB v1.56.0: differential findings

Date: 2026-08-15

Companion to the [2026-08-11 verification discrepancies report](../../../eacl-spicedb/docs/reports/2026-08-11-spicedb-v1.56.0-verification-discrepancies.md)
in the private adapter repository and to today's
[stable-engine audit](2026-08-15-eacl-stable-engine-audit.md). The private
OpenSpec change `upgrade-eacl-v8-snapshot-differential`
(`eacl-spicedb/openspec/changes/…`) carries the specs, design, tasks, and the
archived probe evidence for everything below.

## Scope and method

Compared: the public EACL Datomic client from the Clojars artifacts
`dev.eacl/eacl 8.0.0-20260814.204359-4` and
`dev.eacl/eacl-datomic 8.0.0-20260814.204406-4` (advertised as
`8.0.0-SNAPSHOT`; built from `release/v8.0` head `6cce96f`, which is
source-identical for the shipped modules to local `core` head `ac3cbac`),
against SpiceDB v1.56.0 `serve-testing` at image digest `c8a558a6…`, reached
through the private adapter (Authzed Java 1.6.0, gRPC 1.83.1, protobuf-java
4.33.5). Runtime: Temurin 26.0.2, Apple M4 Max, Docker 29.5.3 (SpiceDB in a
Linux VM over loopback), Clojure 1.12.5, paranoid Netty leak detection.

How: the private repository was **not** modified. Its unit, integration,
differential, and extended suites were executed with a command-line
`:override-deps` alias pointing both EACL modules at the Clojars build. Because
all of them passed, three observation-only probe scripts (`probe.clj`,
`probe2.clj`, `probe3.clj`, archived with redacted logs under the change's
`evidence/` directory) then ran ~90 additional side-by-side scenarios with
minimal fixtures, printing each backend's result or typed error without
asserting. Result **order** was excluded from every comparison by design; the
existing harness already compares unique sets and order-normalized trees.

Everything below is an observation of the pinned versions. "EACL" means the
Datomic client with `datomic-cache/no-cache` (the differential harness
setting) unless stated; "SpiceDB" means the pinned server as seen through the
adapter, with raw gRPC statuses captured where the adapter's mapping hid them.

## Summary

- **The existing black-box matrix is green against the new engine**: unit
  27 tests / 122 assertions, integration 4 / 61, differential 3 / 408,
  extended 22 seeds 1 / 1,556; no Netty leak; ~30 s wall clock for the whole
  live matrix. The stable-discovery order change is invisible to the
  order-normalized harness, exactly as intended, and the 2026-08-11 seed-5
  page-size-dependent stale-cursor bug is structurally gone (order is fixed
  at logical width one).
- **The green matrix hides gaps.** Targeted probes found **eleven semantic
  divergences** (relation names in the permission slot; cyclic graphs; deep
  chains at 48 hops; object-ID grammar; schema-name grammar; three
  schema-validation asymmetries; write-validation typing; page-info nuance;
  the README's `[operation relationship]` tuple shape rejected by both;
  minimize-latency read-your-write staleness, now quantified), **nine
  error-taxonomy drifts** (deadline, cursor, token, page-size, depth-limit,
  protobuf nesting, `:eacl/relationship-conflict` missing `:eacl/error`,
  untyped EACL schema/write errors, `:resource/relation` naming a
  permission), and **two adapter contract gaps** against the current public
  protocol (`:cancellation-token` everywhere; `:timeout-ms` on
  `read-relationships`).
- **Runtime baseline conflict.** The published `dev.eacl/eacl` jar contains
  generated Java classes at class-file version 70 (Java 26). Every bundled
  EACL backend loads them; the adapter's own closure does not. The private
  differential suite therefore needs Java 26 while the adapter keeps its
  Java 17 promise. The published `eacl-datomic` POM also declares
  `com.datomic/peer 1.0.7622` although the source module was bumped to
  1.0.7705.
- **Performance.** On 1,001-result and 300-way fan-out fixtures EACL is 3–6×
  faster than SpiceDB-through-the-adapter for enumeration and counting
  (count 4.1 ms vs 15.1 ms; first page of 100: 1.9 ms vs 11.3 ms; full
  11-page walk 40.8 ms vs 118.6 ms) and roughly equal on point checks and
  small subject lookups (0.9 vs 1.6 ms; 1.5 vs 1.1 ms). Two known costs
  explain most of the adapter gap: fully-consistent revision discovery plus
  reopen on page one, and offset-proportional replay on later pages.
  EACL's own per-page growth in the same walk (2.2 → 4.6 ms) is the harness's
  `no-cache` setting forcing governed replay, not the engine.

## 1. Existing matrix against the Clojars build

| Suite | Alias | Result | Notes |
|---|---|---|---|
| unit (no RPC) | `:test` | 27 / 122 green | `eacl.spicedb.parser`, `eacl.cursor`, `eacl.secure-format`, `eacl.core` APIs unchanged for the adapter |
| integration (SpiceDB only) | `:integration` | 4 / 61 green | includes the 1,001-result page/count boundaries |
| differential (Datomic vs SpiceDB) | `:differential` | 3 / 408 green | curated fixture, PR-112 permission-tree golden, three generated seeds |
| extended | `:extended` | 1 / 1,556 green | seeds 3–24 |

The differential classpath was verified to contain
`~/.m2/…/dev/eacl/eacl/8.0.0-SNAPSHOT/eacl-8.0.0-SNAPSHOT.jar` and the
`eacl-datomic` jar (SHA-1 `4de22a0c…` and `c7055919…`), not the Git or local
coordinates. Single warmed paired timings printed by the existing suite on the
seven-document fixture: `lookup-resources` drain 6.7 ms EACL vs 9.0 ms
SpiceDB, `can?` 1.9 vs 1.8, `check-permission` 2.1 vs 1.8,
`lookup-subjects` 3.1 vs 2.5, `count-resources` 2.6 vs 2.8,
`expand-permission-tree` 1.8 vs 1.7 — parity within noise on tiny data.

## 2. Semantic divergences

Each item gives the minimal reproduction (all on the differential schema
`user; folder{reader, parent, view = reader + parent->view}; document{reader,
parent, view, parent_reader = parent->reader, inherited_view = parent->view,
access = view}` unless stated), what each side did, the cause, and the
recommendation. Identifiers (D-numbers) match the change's design table and
the new pinned tests.

### S1 (D1). A relation name in the permission slot

`can? alice :reader doc-1`, `lookup-resources {:permission :reader}`,
`lookup-subjects doc-1 :reader`, `count-resources :reader`,
`check-permission alice :parent doc-1`, `can? folder-1 :parent doc-1`.

- SpiceDB: evaluates the relation — `true`, `{doc-1 … doc-5}`, `{alice bob}`,
  `{:count 5}`, `false`, `true`.
- EACL: `:eacl/unknown-relation-or-permission` ("Unknown permission :reader
  on definition :document") for all six.
- Yet `expand-permission-tree` on `:reader` and on `:parent` **works on
  both** and yields identical leaves.

SpiceDB's `permission` field is "permission or relation" throughout its API;
checking a relation is idiomatic. EACL accepts relations only in expansion.
Recommendation (upstream, R-1): accept relation names in `can?`,
`check-permission`, `lookup-*`, and `count-*` — the sealed plan already
compiles a relation as a single direct scan — or document the restriction and
make expansion consistent with it. Until then the adapter is *more* capable
than public EACL here; the differential suite pins both behaviours.

### S2 (D2). Cyclic relationship graphs

Fixture: `fa parent fb`, `fb parent fa`, `fa parent doc-c`, `alice reader
fb`; and a self-loop `fs parent fs`, `fs parent doc-s` with no grant.

| Call | EACL | SpiceDB v1.56.0 |
|---|---|---|
| `can? alice :view doc-c` | true | true (grant found before the depth limit) |
| `lookup-resources alice :view document` | `{doc-c}` | `FAILED_PRECONDITION` `ERROR_REASON_MAXIMUM_DEPTH_EXCEEDED` |
| `lookup-subjects doc-c :view user`, `count-resources` | `{alice}`, 1 | same depth error |
| `expand doc-c :view` | `:eacl.permission-tree/cycle-detected` | same depth error |
| self-loop `can? alice :view doc-s` (no grant) | false | depth error |
| self-loop `lookup-subjects doc-s` / `expand doc-s` | `{}` / `cycle-detected` | depth error / depth error |

Cause: SpiceDB dispatches recursively with a default maximum depth of 50 and
has no fixpoint on relationship cycles; a check whose grant lies within the
depth window short-circuits, otherwise it errors — so SpiceDB's answer on
cyclic data is grant-dependent. EACL's stable reducer computes a fixpoint;
only expansion refuses cycles, with a typed error. Server text: "max depth
exceeded: this usually indicates a recursive or too deep data dependency. See
https://spicedb.dev/d/debug-max-depth". Recommendation: the adapter maps
`ERROR_REASON_MAXIMUM_DEPTH_EXCEEDED` to a specific typed category (today it
is the generic `:eacl.spicedb/failed-precondition`, see T6); the differential
suite pins EACL's fixpoint answers and SpiceDB's failure; the public README's
SpiceDB-compatibility notes should state that EACL is strictly more permissive
on cyclic data (R-10).

### S3 (D3, D3e). Deep parent chains

Fixture: `alice reader f0`, `f(i) parent f(i+1)` for i < n, `f(n) parent
doc`.

- Checks, lookups, subject lookups: EACL correct at every n tried (20, 21,
  24, 30, 40, 44–50, 60). SpiceDB correct through **n = 47** and
  `ERROR_REASON_MAXIMUM_DEPTH_EXCEEDED` from **n = 48** (dispatch depth 50).
- Expansion (tree depth = 2n + 4): EACL returns the tree at n = 21 (69 nodes,
  depth 46) and fails from n = 24 with `:eacl.permission-tree/limit-exceeded`
  "maximum depth exceeded" (default `:permission-tree-limits :max-depth 50`).
  Through the adapter, SpiceDB's tree decodes at n = 22 (depth 48) and fails
  from **n = 23 (depth 50)** with `CANCELLED: Failed to read message` whose
  cause chain is `InvalidProtocolBufferException: Protocol message had too
  many levels of nesting. May be malicious. Use setRecursionLimit()` —
  protobuf-java's default recursion limit of 100 nested messages (each tree
  level is two messages). From n = 48 the server itself fails with the depth
  error.

So EACL's default expansion ceiling and the adapter's *decode* ceiling
coincide at tree depth 50, for unrelated reasons; SpiceDB's own check depth is
independent of both. Recommendation: type the decode failure
(`:eacl.spicedb/response-limit-exceeded`, T7) and document the ceiling;
`ProtoUtils/marshallerWithRecursionLimit` (present in gRPC 1.83.1) can raise
it if a consumer ever needs deeper trees than EACL's own default; pin the
thresholds (47/48 hops, 22/23 hops, 21/24 hops) in the suite so a SpiceDB or
protobuf upgrade is noticed.

### S4 (D4). Object-identifier grammar

`create-relationship!` with subject ids `"alice smith"`, `"café"`, `"a:b"`:
EACL accepts; SpiceDB rejects with `INVALID_ARGUMENT` (its object-id grammar
is `^(([a-zA-Z0-9/_|\-=+]{1,})|\*)$`, ≤ 1,024 bytes). `"*"` and a 1,025-char
id: EACL accepts (`can? user:"*"` → false, `*` is a plain id to EACL); the
adapter rejects both before any RPC. `"ok/id_1-2=+|"` (every legal SpiceDB
character) is accepted by both once the Datomic entity exists.
Recommendation (upstream, R-2): decide whether EACL's shared validators should
enforce SpiceDB's identifier grammar (portability) or explicitly document that
EACL identifiers are arbitrary non-empty strings and that a SpiceDB migration
requires an id mapping. Adapter: add the charset check to its pre-RPC
validation so the failure is local and typed instead of a server round trip.

### S5 (D5). Schema-name grammar

`definition ab {}`, `relation Reader: user`, `relation rd: user`,
`relation reader_: user`, and a 65-character relation name are all accepted
by EACL's parser and rejected by SpiceDB (`INVALID_ARGUMENT` schema parse
error; relation and permission names must match
`^[a-z][a-z0-9_]{1,62}[a-z0-9]$`, definitions similar with an optional
namespace prefix). 63- and 64-character names, digit-leading names (both
reject), a trailing `+` (both reject), alias chains with legal names, forward
references, multi-type relations (`reader: user | folder`), comments and
unused definitions agree. Recommendation (upstream, R-2): the shared parser is
called `eacl.spicedb.parser` and was already aligned with SpiceDB's
line-oriented declarations on 2026-08-11; aligning name grammar is the same
kind of fix and prevents schemas that work in EACL from being unportable.

### S6 (D6). Schema semantic validation asymmetries

| Schema | EACL (Datomic write) | Adapter local gate | SpiceDB |
|---|---|---|---|
| `permission view = missing` | rejected, **untyped** | accepts | `:eacl.schema/type-error` |
| `relation reader: nobody` | **accepted** | accepts | `:eacl.schema/type-error` |
| `permission view = parent->nope` | rejected, untyped | accepts | **accepted** |
| `definition ns/document` | rejected, untyped | `:eacl.spicedb/unsupported-schema` | — |
| duplicate relation; relation/permission name collision | typed on both sides (`:eacl.schema/duplicate-relation`, `:eacl.schema/name-collision`) | | |

Cause: reference validation lives in the Datomic write path
(`eacl.datomic.schema/validate-schema-references`, a private copy of
`eacl.schema.model/validate-schema-references` per audit §3.4) and is not
applied by `eacl.spicedb.parser/->eacl-schema`, which the adapter uses as its
pre-RPC gate — so the gate admits schemas EACL itself rejects, and the errors
that EACL does raise carry no `:type`. Undefined subject types are validated
by neither. Recommendation (upstream, R-3/R-4): run reference validation in
the shared conversion, reject undefined subject types, and give every schema
rejection a typed category; the arrow-to-unknown case is a genuine policy
difference (SpiceDB tolerates it) worth documenting.

### S7 (D7). Write validation typing

Writing `alice :nope doc-3` (unknown relation), `alice :reader ghost:g`
(unknown definition, entity pre-created), `alice :parent doc-3` (subject type
not allowed), and `alice :view doc-3` (permission name as relation): SpiceDB
via the adapter yields `:eacl/unknown-relation-or-permission`,
`:eacl/unknown-definition {:definition :ghost}`,
`:eacl.spicedb/invalid-argument {:definition :document}`, and
`:eacl.spicedb/invalid-argument`; EACL rejects all four with an ex-info whose
data has **no `:type` or `:eacl/error`** ("Missing Relation: :nope on
resource type :document for subject type :user."). Recommendation (upstream,
R-4): reuse the read-side structured taxonomy on writes.

### S8 (D8c). README update shape

`write-relationships!` with `[[:create relationship]]` — the shape the public
README documents ("`updates` is a collection of `[operation relationship]`")
— is rejected by both: EACL `:eacl/unsupported-operation` ("nil relationship
update is not supported"), adapter `:eacl.spicedb/invalid-argument`.
`RelationshipUpdate` records and `{:operation … :relationship …}` maps work
on both, as does `delete-relationships!` given a `read-relationships` page
map. Recommendation (upstream, R-6): fix the README sentence.

### S9 (D9). Page information after an exhausted cursor

First, middle, last, and empty pages carry identical flags on both backends.
Reusing the end cursor of the final page returns an empty page with
`:has-next-page? false` on both, but `:has-previous-page?` is `false` on EACL
and `true` on the adapter (`(boolean (:after query))`). Relay leaves this
implementation-defined; pinned as a nuance, not changed.

### S10 (D11, D11b). Pagination features neither side offers the same way

`lookup-resources … :last 2`: EACL `:eacl.pagination/complete-evaluation-required`
("This recursive page shape requires :evaluation :complete-denotation"); adapter
`unsupported-argument [:last]`. `lookup-subjects … :first 1`: EACL now pages
subjects (one result, next page true); the adapter still rejects subject
pagination because v1.56.0 offers no usable concrete-subject cursor contract.
Both documented; pinned.

### S11 (D14). Minimize-latency read-your-write staleness, quantified

Forty create-then-`can?` pairs and forty delete-then-`can?` pairs under
omitted consistency: EACL 0 stale / 0 stale; SpiceDB **12 / 40** stale after
create and **23 / 40** stale after delete. With each backend's own write token
as `at-least-as-fresh`: 0 stale on both. This is the optimized-revision
behaviour documented on 2026-08-11 (item 5); it is now a measured diagnostic
in the suite, and the causal-token variant is an assertion.

Related but not a divergence: EACL's Datomic backend resolves object ids to
pre-existing entities (`:eacl/id`), so a write naming an unknown entity fails
with `:eacl/unknown-object`; SpiceDB has no object registry. Two early probe
rows were exactly this fixture artifact and were re-run with the entity
present (both accept).

## 3. Error taxonomy drift

Same condition, different portable category (`:eacl/error`) or `:type`.

| Id | Condition | EACL | Adapter today | Decision |
|---|---|---|---|---|
| T1 | `:timeout-ms 1` on `count-resources` | `:eacl.execution/deadline-exceeded` (both keys) | `:type :eacl.spicedb/deadline-exceeded`, `:eacl/error :eacl/deadline-exceeded` | adapter adopts the public category |
| T2 | malformed / query-mismatched / consistency-mismatched / cross-backend cursor | `:eacl.pagination/invalid-cursor` (both keys) | `:eacl/error` same, `:type :eacl.spicedb/invalid-cursor` | adapter uses the public `:type` (it already does for unknown-schema and relationship-conflict) |
| T3 | foreign or malformed causal token | `:eacl/invalid-zed-token` (`eacl_z3_` prefix → `:eacl/zed-token-upgrade-required`) | `:eacl.spicedb/invalid-token` | adapter adopts `:eacl/invalid-zed-token` |
| T4 | `:first 10001` | **untyped** "Page size exceeds configured maximum." | `:eacl.spicedb/invalid-argument` | upstream: type it (R-4) |
| T5 | `read-relationships {:resource/type :document :resource/relation :view}` (permission name) | `:eacl/unknown-relation-or-permission` | `:eacl.schema/type-error` (server `FAILED_PRECONDITION`) | documented; no stable reason distinguishes it |
| T6 | SpiceDB `ERROR_REASON_MAXIMUM_DEPTH_EXCEEDED` | n/a (`:eacl.permission-tree/cycle-detected` / `limit-exceeded`) | generic `:eacl.spicedb/failed-precondition` | adapter: `:eacl.spicedb/depth-limit-exceeded` |
| T7 | protobuf nesting overflow decoding a tree | n/a | generic `:eacl.spicedb/rpc-error` `:grpc/code :cancelled` | adapter: `:eacl.spicedb/response-limit-exceeded` |
| T8 | duplicate create | `:eacl/relationship-conflict` as `:type` only | both keys | upstream: add `:eacl/error` (R-5) |
| T9 | schema reference errors, namespaced definitions, write validation | untyped `{}` ex-data | typed | upstream (R-4) |

Convention worth stating in both repositories: `:eacl/error` is the portable
category and, for shared categories, `:type` equals it; backend-private detail
goes under additional keys.

## 4. Adapter contract gaps against the current public protocol

- **G1 `:cancellation-token`** — public EACL accepts it on every bounded read
  and expansion (`lookup-resources … :cancellation-token (eacl/cancellation-token)`
  → normal page); the adapter throws `unsupported-argument
  [:cancellation-token]`. gRPC supports cancellation natively; the change
  maps it to `cancel!` between stream reads and throws
  `:eacl.execution/cancelled`.
- **G2 `read-relationships :timeout-ms`** — now a public filter key; EACL
  accepts, adapter rejects. The change accepts it and applies the existing
  absolute deadline.

Both are one-line allowlist changes plus digest exclusion; both were invisible
to the differential suite because it never sends the new keys.

## 5. Consistency and continuation behaviour that agrees

Continuation after an unrelated write (resource and relationship pages)
returns each backend's pre-write set; schema-write tokens work as
`at-least-as-fresh` and `at-exact-snapshot` on both; an `:expanded-at` token
replays as an exact snapshot for a lookup on both; arrow-intermediate objects
are never subjects on either side (`can? folder-1 :view doc-1` → false;
`lookup-subjects … :subject/type :folder` → `{}`); `read-relationships` by
`:resource/id` alone, by `:subject/type :folder`, and by
`:resource/type :folder :resource/relation :parent` agree; a batch containing
one conflicting relationship writes nothing on either side; `:touch` of an
absent relationship creates it on both; 1,001-result counts, bounded counts,
default page (1,000 + next), single 10,000 page, and drains at
`[1 2 1000]`/`[500]` agree.

## 6. Performance measurements

Non-gating; medians of 7 after one warm-up; ms; JDK 26 on M4 Max; SpiceDB in
Docker over loopback; EACL Datomic in-memory with `no-cache`.

**1,001 direct grants (`alice reader b-0…b-1000`)**

| Operation | EACL | SpiceDB via adapter | Faster |
|---|---|---|---|
| `count-resources` (unbounded) | 4.1 | 15.1 | EACL 3.7× |
| first page `:first 100` | 1.9 | 11.3 | EACL 5.9× |
| first page `:first 1000` | 5.1 | 20.5 | EACL 4.0× |
| single page `:first 10000` | 5.0 | 22.1 | EACL 4.4× |
| per-page median at `:first 100`, 11 pages | 2.2, 2.4, 2.6, 2.7, 3.6, 3.8, 3.8, 4.7, 5.6, 4.8, 4.6 (Σ 40.8) | 9.9, 5.5, 7.8, 8.7, 10.2, 10.0, 11.4, 12.2, 15.7, 13.2, 14.1 (Σ 118.6) | EACL 2.9× on the walk |
| `can?` (one of 1,001) | 0.9 | 1.6 | ≈ (EACL) |
| `lookup-subjects` (one subject) | 1.5 | 1.1 | ≈ (SpiceDB) |

**Fan-out: one folder with 300 child documents read via the arrow, plus 300
direct readers on one document**

| Operation | EACL | SpiceDB via adapter | Faster |
|---|---|---|---|
| `count-resources` (300 via arrow) | 1.8 | 5.7 | EACL 3.2× |
| first page `:first 100` | 1.3 | 6.4 | EACL 4.9× |
| full drain at 100 (3 pages) | 9.0 | 20.8 | EACL 2.3× |
| `lookup-subjects` (301 subjects) | 3.0 | 5.1 | EACL 1.7× |
| `count-subjects` | 2.2 | 5.2 | EACL 2.4× |
| `expand-permission-tree` (301-subject tree) | 1.7 | 1.4 | ≈ (SpiceDB) |
| `can?` via arrow | 1.1 | 0.8 | ≈ (SpiceDB) |

Reading the numbers:

- Enumeration and counting favour EACL by 3–6× at these sizes; single checks
  and small subject lookups are within a millisecond, sometimes favouring
  SpiceDB.
- The adapter's first page always costs two RPCs (fully-consistent revision
  discovery, cancel, reopen at that exact revision), and later pages replay
  the exact stream from the beginning to the authenticated offset — this is
  the correctness-first design from 2026-08-11 and shows as the rising
  per-page cost. EACL's rise in the same walk (2.2 → 4.6 ms) is the harness's
  `no-cache` client: with no checkpoint store every continuation is a
  governed replay. The change's timing pass measures both cache
  configurations so the checkpointed cost is on record.
- The upstream known issue "uncached exhaustive counts are 6–10× slower than
  v7" is real relative to v7 but does not make EACL slower than SpiceDB here:
  the adapter's count consumes a full `LookupResources` stream.
- Loopback Docker adds transport cost to every SpiceDB number; the same
  comparison against a co-located native SpiceDB would narrow the enumeration
  gap somewhat and would not change the shape (two RPCs + linear replay).
- None of this is a benchmark: single host, in-memory backends, tiny data.
  Its purpose is to make cost *shapes* visible; the deterministic
  stable-counter envelopes and the Explorer gate in `core` remain the
  authority for EACL regressions.

## 7. Dependency, runtime, and CI findings

1. **Java 26 bytecode in the published artifact.** `eacl-8.0.0-…-4.jar`
   contains the Dafny-generated runtime (`EaclKernel`, `WireFormat`,
   `CurrentCache`, `IndexedTraversal`, …) at class-file major version 70;
   `release.yml` builds with `EACL_JAVA_RELEASE=26` (the "pinned default";
   `minimum-java-release` is 8). `eacl.formal.generated-runtime` does
   `Class/forName` at load and is required by every bundled backend, so
   `dev.eacl/eacl-datomic` (and datahike/datascript) cannot load on Java 17
   or 21 from Clojars. The adapter's closure into public EACL — `eacl.core`,
   `eacl.execution`, `eacl.cursor`, `eacl.secure-format`,
   `eacl.schema.model`, `eacl.spicedb.parser`, `eacl.spicedb.consistency` —
   never reaches it (verified statically; unit suite and clean consumer will
   verify it on a Java 17 CI leg with a class-load guard). The private
   repository therefore splits: adapter Java 17, verification backends Java
   26. The [2026-08-09 multi-JVM report](2026-08-09-multi-jvm-artifacts-and-runtime-performance.md)
   already recommends "publish one lower-baseline JAR if ordinary Clojars
   consumption must work on older JVMs"; this is the first concrete consumer
   hit by the policy (R-8).
2. **Published POM lags source.** `src-build/eacl/build/config.clj` still
   declares `com.datomic/peer 1.0.7622` for `dev.eacl/eacl-datomic`; the
   module's `deps.edn` and the root `deps.edn` were bumped to 1.0.7705 on
   2026-08-14 ("older peer can't read the 1M db"). The comment "single source
   of truth" on that file is no longer true (R-7).
3. **Snapshot cache aliasing.** tools.deps resolves the timestamped builds,
   but Maven's local repository stores unique snapshots under the
   base-version file name; a later `-SNAPSHOT` resolution overwrites the file
   the pin returns. The private repository therefore records jar SHA-1s and
   verifies them in `check-deps`.
4. **Private CI has never been green.** `compatibility.edn :verified` is
   `nil`; the `verify.yml` differential job checks out `theronic/eacl` at
   `EACL_REF` and uses `:dev-local`, which needs `clojure -T:build prep`
   (generated classes) that the workflow never runs. Moving to Clojars
   artifacts removes both the checkout and the prep step.
5. **Public jar hygiene.** The jar ships `eacl/impl/spicedb.clj`, a 2-line
   TODO stub ("transfer SpiceDB gRPC Clojure implementation from
   closed-source"). Separately, the private adapter's namespaces
   (`eacl.spicedb`, `eacl.spicedb.{errors,security,validation,wire}`) share
   the prefix of public `eacl.spicedb.{parser,consistency}`; a future public
   namespace with one of those names would shadow silently depending on
   classpath order (R-9).

## 8. Recommendations

**A. Private adapter (implemented by the change `upgrade-eacl-v8-snapshot-differential`)**

1. Pin `dev.eacl/eacl 8.0.0-20260814.204359-4` and
   `dev.eacl/eacl-datomic 8.0.0-20260814.204406-4` with jar SHA-1s; keep
   `8.0.0-SNAPSHOT` behind a probing alias; verify checksums in `check-deps`;
   drop `EACL_REF` and the `core` checkout from CI.
2. Run unit/deps/clean-consumer on Temurin 17 and 26 (with a generated-class
   load guard on 17) and the live suites on 26; document the split.
3. Accept `:cancellation-token` (cooperative cancel between reads →
   `:eacl.execution/cancelled`) and `read-relationships :timeout-ms`.
4. Adopt public error categories: `:eacl.execution/deadline-exceeded`,
   `:eacl.pagination/invalid-cursor` / `expired-cursor`, `:eacl/invalid-zed-token`;
   add `:eacl.spicedb/depth-limit-exceeded` for
   `ERROR_REASON_MAXIMUM_DEPTH_EXCEEDED` and
   `:eacl.spicedb/response-limit-exceeded` for protobuf nesting overflow;
   validate the SpiceDB object-id charset locally.
5. Add the pinned-divergence suite (D1–D16) and the median/per-page timing
   pass (both EACL cache configurations); keep it under `:differential` and
   `:extended`.

**B. Public EACL (`core`) — recorded here, each to become its own change or issue**

- R-1 Accept relation names in the permission slot for `can?`,
  `check-permission`, `lookup-*`, `count-*` (SpiceDB parity; expansion
  already does).
- R-2 Enforce SpiceDB's relation/permission/definition name grammar and,
  by explicit decision, its object-id grammar in the shared parser and
  validators — or document that EACL identifiers/names are broader and
  unportable.
- R-3 Reject relations whose subject type is undefined at schema write.
- R-4 Type every schema/write/page-size validation failure
  (`:eacl.schema/unknown-reference`, `:eacl/unknown-relation-or-permission`
  and `:eacl/unknown-definition` on writes, `:eacl.pagination/invalid-page-size`
  or similar, namespaced-type rejection) and apply
  `validate-schema-references` in `eacl.spicedb.parser/->eacl-schema` so
  backends and the adapter's pre-RPC gate agree (ties to audit §3.2/§3.4).
- R-5 Add `:eacl/error` to `:eacl/relationship-conflict`.
- R-6 Fix the README `write-relationships!` "collection of
  `[operation relationship]`" sentence (records or `{:operation …
  :relationship …}` maps).
- R-7 Align `src-build/eacl/build/config.clj` `com.datomic/peer` with the
  module (1.0.7705) or derive published dependencies from module `deps.edn`.
- R-8 Publish generated classes at `--release 17` (or 8) so Java 17/21
  consumers can use the bundled backends from Clojars, per the 2026-08-09
  recommendation.
- R-9 Retire the `eacl.impl.spicedb` stub; note the `eacl.spicedb.*` prefix
  is shared with the private adapter.
- R-10 Document in the SpiceDB-compatibility notes that EACL evaluates
  relationship cycles as fixpoints and has no dispatch-depth limit for checks
  and lookups, while SpiceDB (default `--dispatch-max-depth 50`) fails from
  48 arrow hops and on any cycle it cannot short-circuit; and that EACL's
  expansion `:max-depth 50` coincides with the Java client's protobuf decode
  ceiling.

**C. Process**

- Every EACL coordinate bump in the private repository produces a dated
  report like this one plus a "Verification record"; pinned divergences that
  start failing are moved from *pinned* to *parity* rows rather than deleted.
- The audit report's §7 "Backend findings" can link here for the SpiceDB
  side.

## 9. Black-box gaps closed by the new tests

The existing suite compares sets, trees, counts, error categories for unknown
names, consistency modes, and continuation after a *related* write. It never
sends relation names as permissions, cycles, chains longer than six, illegal
identifiers or names, invalid writes, the new public request keys, backward or
subject pagination, cursor edge cases, batch conflicts, page-info flags,
`:timeout-ms` failures, or more than seven results on the Datomic side; and
its only timing is a single warmed sample per operation. Section 6 of the
change's design lists the sixteen fixture groups (D1–D16), each with the
minimal data above and the documented outcome per side, and the tasks put them
in `test-differential/eacl/spicedb/differential/gaps_test.clj` plus a timing
pass under the extended alias.

## 10. Verification record

To be filled by the change: CI run links for the Java 17 and 26 unit legs,
the Java 26 differential/extended runs (twice, no leak), the checksum-verified
coordinate, and the timing pass under both EACL cache configurations. Until
then the only executed evidence is the 2026-08-15 override-deps run above and
the archived probe logs.

Reproduce today's observations from the private repository without editing
it:

```bash
cd eacl-spicedb && clojure -Sdeps '{:aliases {:snapshot {:override-deps {dev.eacl/eacl {:mvn/version "8.0.0-SNAPSHOT"} dev.eacl/eacl-datomic {:mvn/version "8.0.0-SNAPSHOT"}}}}}' -X:snapshot:test
```

and run the live suites with the same alias prefix (a copy of
`bin/run-live-tests` that adds `-Sdeps … :snapshot` is archived as
`evidence/run-live-snapshot.sh`; the probes as `evidence/probe*.clj` with
`evidence/probe.sh`).

## Appendix A. Divergence table (compact)

| Id | Scenario | EACL | SpiceDB via adapter | Class |
|---|---|---|---|---|
| D1 | relation as permission (check/lookup/count) | typed unknown-permission error | evaluated | semantic (R-1) |
| D1e | expand a relation | leaf | leaf | parity |
| D2 | cycle: check with reachable grant | true | true | parity |
| D2b–d | cycle: lookup/count/expand/no-grant check | answers; expand `cycle-detected` | `MAXIMUM_DEPTH_EXCEEDED` | semantic |
| D3 | chain n hops, check/lookup | true ∀ n | true ≤ 47, depth error ≥ 48 | semantic |
| D3e | chain expand | ok ≤ 21, `limit-exceeded` ≥ 24 | ok ≤ 22, decode limit 23–47, depth ≥ 48 | semantic + taxonomy |
| D4 | ids with space/unicode/colon; `*`; 1,025 chars | accepted | rejected (server / local) | semantic (R-2) |
| D5 | 2-char, uppercase, trailing `_`, 65-char names | accepted | parse error | semantic (R-2) |
| D6 | undefined relation ref / undefined subject type / arrow to unknown / namespaced | untyped reject / accept / untyped reject / untyped reject | type error / type error / accept / unsupported-schema | semantic (R-3, R-4) |
| D7 | invalid writes | untyped | typed | taxonomy (R-4) |
| D8 | conflicting batch, touch-creates, update shapes | atomic, creates, records+maps ok, tuples rejected | same | parity (+R-5, R-6) |
| D9 | page-info after exhausted cursor | has-prev false | has-prev true | nuance |
| D9b | cursor rejections | `:eacl.pagination/invalid-cursor` | same `:eacl/error`, private `:type` | taxonomy (T2) |
| D10 | `:cancellation-token`, `read-relationships :timeout-ms` | accepted | rejected | contract gap |
| D10c | deadline category | `:eacl.execution/deadline-exceeded` | `:eacl/deadline-exceeded` | taxonomy (T1) |
| D11 | `:last`; subject `:first` | complete-evaluation-required; supported | unsupported-argument; unsupported-argument | documented |
| D12 | schema/expanded-at tokens; foreign token | ok / `:eacl/invalid-zed-token` | ok / `:eacl.spicedb/invalid-token` | parity / taxonomy (T3) |
| D13 | 1,001-grant boundaries; `:first 10001` | parity; untyped page-size error | parity; typed | parity (+T4) |
| D14 | read-your-write, omitted consistency | 0 stale | 12/40, 23/40 stale; 0 with own token | documented |
| D15 | arrow-intermediate as subject | never a subject | same | parity |
| D16 | relationship filters; `:resource/relation` = permission | parity; `unknown-relation-or-permission` | parity; `:eacl.schema/type-error` | parity (+T5) |

## Appendix B. Raw SpiceDB statuses captured

- Depth: `FAILED_PRECONDITION`, description "max depth exceeded: this
  usually indicates a recursive or too deep data dependency. See
  https://spicedb.dev/d/debug-max-depth", `ErrorInfo` reason
  `ERROR_REASON_MAXIMUM_DEPTH_EXCEEDED`, empty metadata.
- Deep tree decode: `CANCELLED` "Failed to read message." ←
  `StatusRuntimeException INTERNAL: Invalid protobuf byte sequence` ←
  `InvalidProtocolBufferException: Protocol message had too many levels of
  nesting. May be malicious. Use setRecursionLimit() to increase the
  recursion depth limit.`
- Object-id and schema-name violations: `INVALID_ARGUMENT` (schema:
  `ERROR_REASON_SCHEMA_PARSE_ERROR`); undefined references and subject types:
  `FAILED_PRECONDITION` `ERROR_REASON_SCHEMA_TYPE_ERROR`; unknown relation on
  write: `ERROR_REASON_UNKNOWN_RELATION_OR_PERMISSION` with
  `definition_name` and `relation_or_permission_name`; unknown definition on
  write: `ERROR_REASON_UNKNOWN_DEFINITION`.
