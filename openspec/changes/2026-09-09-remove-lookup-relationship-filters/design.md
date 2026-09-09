> **Superseded relationship-read requirements (2026-09-09):** The later `2026-09-09-remove-relationship-read-authorization` change replaces every requirement below to retain, construct, validate or migrate to `read-relationships :authorization`. Nested demo branches use plain indexed reads with the exact parent/type/relation, pagination, consistency and cache controls, and perform no endpoint permission checks. Removed authorization fields are rejected on presence, including nil/null. Viewing subject and permission remain inputs to root lookups, the access inspector and permission checks only. Library callers needing endpoint authorization compose direct reads with `can?` or `check-permissions` on one snapshot and own filtered pagination. Historical completed tasks and measurements below describe the earlier release, not the new contract. All unrelated requirements remain in force. Apply the companion delta before archiving; do not restore the superseded clauses.

## Context

See proposal.md for motivation and scope. This is a removal proposal, not an implementation or release. Inspection was performed on 2026-09-09; unrelated work is present in the core checkout and must not be overwritten.

### Provenance and the original amplification

Git commit `f0812b6c8589af93b292526746937b2e365ab46c` (2026-08-23, “Eliminate authorization request amplification”) introduced both public lookup clauses. Its proposal identifies a Datalevin permission-filtered demo endpoint that repeatedly called the scalar API, repeating schema/plan preparation and fixed request orchestration. The retained benchmark in `modules/eacl-datalevin/test/eacl/bench/authorization_amplification_test.clj` contains `scalar-loop-page`, `measured-scalar-loop`, `scan-route-page` and `enumerate-route-page`; it separates repeated public calls from shared aggregate execution. Recorded historical timings are not current production measurements and are not used as acceptance thresholds here.

The change bundled multiple independent improvements: schema-generation reuse, request context, batch checks, authorized relationship reads, confidential cursors, and the extra lookup predicates. The predicates are not prerequisites for the other improvements. The original proposal explicitly targets the Datalevin demo; that establishes demo motivation, not proof that no external consumer ever adopted the API.

The unified demo before `831fb10face9f3ed2b47577a676e0ce0e478beb6` similarly fetched a reverse-relationship page and awaited public checks sequentially in `apps/explorer-main/src/profile-api.ts`. DataScript's reverse reader filtered its fixture collection. That is a concrete consumer amplification problem, not justification for keeping the added public lookup surface. During the subsequent audit, local demo commit `1693f04` (merged as `77bf776`) changed nested branches to authorized relationship reads. This is source evidence only, not verification of deployment or complete cleanup; obsolete lookup constructors, wire fields and a Jank exemplar remain.

### Current call graph and dependencies

| Area | Observed use | Removal implication |
| --- | --- | --- |
| `authorization/filters.cljc` | Closed lookup envelopes admit both keys and validate anchor clauses | Reject obsolete keys explicitly and consistently before any context/cache work |
| `client/orchestration.cljc` | Public reader and operation validation; schema scopes; `relationship-filtered-lookup-page`; alternate compute/cache continuation branches | Remove the optional public path, preserve ordinary lookup and authorized-read paths |
| `schema/errors.cljc` | `validate-lookup-relationship!` validates the extra direct relationship | Remove only this validation, not ordinary relationship or permission validation |
| `relay.cljc` | Normalizes nested anchor wrappers; includes clause-sensitive aggregate cursor scope | Remove dead normalization; ensure old filtered cursors cannot become plain lookup cursors |
| `engine/v8.cljc` | Accepts external predicates, but also filters recursive structural covers and handles qualified evidence | Trace reachability before pruning; internal filtering is not synonymous with the removed API |
| Core test/benchmark callers | Shared contracts, DataScript batch/qualified tests, Datalevin and Datomic operator tests, Datalevin benchmark | Retire positive filter cases, retain mixed tests and add rejection tests |
| Formal consumers | Mutation registry, qualified mutation gates, filtered-pagination assurance mappings | Remove/replace only obsolete arms; preserve proofs/gates still covering scan or recursive execution |
| Unified demo | Nested frontend route now uses reverse-relationships/authorized reads; browser CLJS and JVM lookup handlers still admit the resource filter | Verify the new route and remove remaining obsolete wire/handler support; do not redo completed migration work |
| Vendored Jank demo | `store.jank/verify-filter-exemplar!` uses the resource filter; vendored validation admits both | Update executable exemplar and vendored contract; do not assume copying the JVM adapter is sufficient |

No ordinary core engine operation was found constructing a public relationship-filtered lookup request to implement another operation. Source matches outside callers primarily implement, validate or transport this optional API. No caller was found in the searched sibling `eacl-edrive` checkout. This is a local search result, not an ecosystem-wide guarantee.

### Execution and performance limits

The optional public path resolves the anchor/relation, then passes a candidate predicate to ordinary lookup discovery. Each candidate is tested with a direct-match invoker, or with direct-edge evidence and qualification when qualification is active. Cost includes discovery plus membership work over examined candidates, which can substantially exceed returned rows. Operator and recursive paths may evaluate candidates internally in different orders; this is not a guarantee of one global materialized authorized list. It is also not an adaptive planner that seeks a selective parent range first.

The fixture generator (`../eacl-demo/packages/fixture-generator/generator.mjs`) stores `platform:platform` as the subject of relation `platform` to every account. Consequently Platforms → Accounts spans all fixture accounts, not all objects. A streaming check of the generated 10,000-object fixture found 11 accounts, 11 platform-account relationships, 38,613 relationship records, zero duplicate relationship identities and zero qualified records. The checked-in million-object manifest records 226 accounts. These small account sets make the earlier hypothetical concern about scanning millions of accounts in this demo inapplicable; they do not measure index I/O or authorization latency. Other branches have different selectivity. Arbitrary applications and larger browser seed sizes can have larger fanout.

## Goals / Non-Goals

**Goals:** Make unsupported lookup inputs fail closed; remove their implementation burden; preserve ordinary authorization; identify a complete consumer and verification migration without reintroducing public per-row calls.

**Non-Goals:** Removing `read-relationships` authorization or `check-permissions`; changing permission schemas, fixtures or v8 storage; adding an adaptive planner or replacement lookup filter; deleting shared engine predicates indiscriminately; claiming complete SpiceDB API parity; publishing or deploying as part of this proposal.

## Decisions

### 1. Reject presence, then remove implementation

Both keys are unsupported on both public lookup operations. Use the existing `:eacl.pagination/unsupported-filter` error convention (already used for unsupported `:subject/relation`), identify the offending `:filter`, and define a deterministic priority if both keys are supplied: `:resource/relationship` first. Presence includes nil, false and malformed values. Apply the same narrow removed-key rejection to `count-resources` and `count-subjects`: their inspected paths do not implement these filters and do not perform the lookup envelope validation. A caller reusing a lookup query must not receive an apparently filtered count. This is not a redesign of all count input validation.

Perform admission before snapshot selection, conversion, cache access, lookahead or execution. Inspect both the public `eacl.core` facade and library-owned reader wrappers: the facade currently delegates to protocols, and Snapshot methods currently prepare snapshot-read options before reaching orchestration validation. Place the guard early enough on valid supported reader targets, including retained/speculative views. Do not promise precedence over malformed-target, closed-snapshot, ownership or non-map errors, and do not suppress those lifecycle guards. A pre-existing snapshot need not be released by a rejected read. Private engine/SPI entry points are not an alternative supported public filter API.

Do not silently strip the filter, interpret nil as absent, or translate it into another API. Stripping changes the requested authorization result set; implicit translation changes row shape/order and qualified semantics. A dedicated error provides an actionable breaking change rather than accidental broader results.

### 2. Remove only public-filter-specific execution

Delete `relationship-filtered-lookup-page` and its conditional dispatch, extra clause schema validation and scope normalization. Ordinary lookups use their existing compute/continuation path. Audit every filter-specific helper and imported invoker before deletion.

`filtered-lookup-page`, candidate windows, vectorized acceptance, operator cover evaluation and qualification evidence have retained internal consumers. Keep them where ordinary recursive/qualified lookups depend on them; prune external filter parameters only when their remaining internal uses are accounted for. Direct-match backend operations remain if point checks or other supported paths use them. In particular, `relationship-filter-relation-eids` and the relationship-dependency branch in `cursor-options` also support relationship-read cursor/cache proofs: retaining permission dependencies alone would miss branch membership changes. Keep the required union of branch and authorization dependencies, or existing exact-basis fallback where the backend cannot certify it. A broad revert of the August commit is rejected because it would remove independent request-sharing fixes and correctness changes.

### 3. Retain cursor and cache isolation

Removed requests fail before any cache hit. An old filtered cursor replayed as an unfiltered query must fail typed cursor validation; it must never continue a different result set or be silently restarted. Test real cursors produced by a pre-removal build on a deterministic fixture, including both lookup directions and cache-enabled/bypassed admission.

Preserve ordinary-query cursor scopes and the current semantic ABI for this removal. The inspected `relay/cursor-scope` binds the canonical query (including a formerly supplied clause), operation and aggregate execution scope; decode compares the computed scope with the authenticated envelope. Successful-result cache identity likewise retains the clause. A bare removal does not require a global ABI bump. Tests must demonstrate this on pre-removal filtered and ordinary cursors, including zero-row progress cursors and populated caches. If tests contradict the expected isolation, fix that failure before release; a broad compatibility change requires an explicit plan revision rather than an incidental bump. Do not strip removed keys from historical cache/cursor identity to obtain a hit, and do not flush caches or migrate relationship data to hide a mismatch.

### 4. Migrate the demo through existing authorized relationship reads

The core-local change records this prerequisite; changes in `eacl-demo` require a companion change in that repository. For Platforms → Accounts, the existing schema gives this request:

```clojure
(eacl/read-relationships
 client
 {:subject/type :platform
  :subject/id "platform"
  :resource/type :account
  :resource/relation :platform
  :authorization {:subject selected-subject
                  :permission selected-permission
                  :on :resource}
  :first page-size
  :aggregate-limits {:candidate-window configured-window}
  ;; Forward current consistency/cache controls and the returned cursor.
  })
```

Other branches derive anchor/type/relation from the existing schema paths. Pass the resource type into the backend query; do not filter resource types only after pagination. Use indexed backend reads rather than the fixture collection. One page invocation shares snapshot, request setup and authorization work; no sequential public check-permission loop and no unbounded page-filling loop.

The result is relationship rows in relationship order, not lookup objects in lookup order. Map the resource endpoint for display and preserve the relationship page cursor. For the verified unqualified canonical fixture, an exact subject/type/id + resource/type + relation filter has one row per object: the generated small fixture has no duplicate relationship identities and no subject userset relation or qualifier fields. Preserve these properties at generation/seed/manifest qualification, including supported larger seed sizes; do not add an unbounded production scan just to re-certify them on every request. Unknown or newly qualified datasets do not inherit this proof. No hidden cross-page deduplication or changes to the canonical schema/data are allowed.

Qualification is a concrete non-equivalence, not merely a theoretical edge case. `relationships/inspection` defaults to stored rows; `:expiry-active` excludes expiration but does not evaluate a relationship's caveat as an authorization grant. `authorization-scan-page` asks the Boolean `engine/can?` about the endpoint. In contrast the removed qualified lookup path combines direct-edge evidence with permission evidence and supports detailed conditional results. Counterexample: an expired or caveat-false team edge remains stored while the subject has view permission through account ownership; a stored authorized read can return that team row, but the former qualified filter does not treat it as active membership. Another counterexample is a conditional-only permission under detailed lookup policy, which a Boolean authorization scan does not reproduce. Do not implement an implicit qualified conversion or claim these APIs equivalent. Retain existing qualified read and lookup contracts; migration by this plan is restricted to the canonical unqualified demo.

Complete wire admission is required. On authorized reverse reads, authorization subject type/id, permission and displayed resource type must be present and valid together. A fully absent authorization clause remains valid only for the separate raw relationship-inspection operation; the nested-tree request must never fall back to raw inspection if an authorization input is absent. Remove all three obsolete wire keys (`relationshipSubjectType`, `relationshipSubjectId`, `relationshipRelation`) from browser/HTTP/JVM/Jank lookup admission; reject even partial or nil forms. The current JavaScript HTTP validator accepts the old triplet and accepts authorization without resourceType; the core already rejects the latter, so this is an admission/consistency gap rather than evidence of unauthorized results. Keep JS and Clojure validators aligned.

The symmetric API removal does not imply a symmetric native replacement. `lookup-subjects` with `:subject/relationship` asks which candidate subjects both hold a direct relationship to an anchor resource and have a permission on a possibly different fixed resource. `read-relationships :authorization` instead fixes the authorization subject and checks a candidate endpoint as the resource; changing `:on` cannot invert this. No production demo caller of the symmetric filter was found. If another caller exists, the available unqualified composition is a bounded anchored relationship page followed by `check-permissions` with each candidate as `:subject` and the fixed permission target as `:resource`, all on one explicit snapshot. The application then owns accepted-row pagination/progress and bounded cross-call work. It must preserve errors as errors, not denial; it cannot call public point checks sequentially, reset work budgets in an unlimited fill loop or claim lookup ordering/qualified equivalence. This is migration guidance for external callers, not a new EACL endpoint or an obligation to build unused demo UI.

Short or empty bounded pages remain legitimate progress. Carry `bounded` through backend responses, wire schemas, TypeScript types and UI; the inspected wire-page and frontend adapter currently drop it. A bounded empty page has a progress cursor and Next, not an exhausted-branch claim. Preserve inclusive sentinel/progress cursors as opaque bytes: a window can consume an N+1st accepted sentinel which must be included on the next page. Test page size 1, budget 1, budget equal to page size, dense/sparse/all-rejected windows, exact exhaustion and reverse traversal. UI Prev can replay stored forward cursors, but that is not an independent exact snapshot guarantee under live consistency modes. Maintain selected consistency behavior and source-mutation tests rather than requiring immutable results across legitimate live changes.

Latency/cache badges measure the complete foreground authorized read, not just its initial relationship fetch. Keep optional lookahead separately accounted: the engine can submit additional page requests after the foreground result, with its own configured depth/concurrency and deadline. Run foreground work tests with lookahead disabled, then separately validate bounded background work, cache-bypass behavior and cancellation. A candidate budget bounds predicate examinations, not every storage datom: the relationship iterator permits a predicate-free exhaustion probe and each point check can read additional data. Report existing aggregate/backend counters rather than falsely asserting total I/O <= candidate-window. Do not change retained lookahead semantics as part of this removal.

Count/range labels must describe accepted results and actual count queries, not candidates guessed from page size; fixed `(page-number - 1) * page-size` offsets are wrong after short/empty pages. Preserve a cumulative accepted-row offset in UI navigation state if global ranges are displayed, and do not substitute root-wide `count-resources` for a branch count. Changing routes requires explicit old-cursor recovery while preserving selected subject/resource and other inputs. Late responses from superseded principal/permission/cache/backend scopes must not overwrite the current branch.

### 5. Keep verification for retained behavior

Replace positive public-filter tests with rejection tests across supported clients, snapshot views and backend runtimes. Split mixed qualified lookup tests to keep their unfiltered forward/backward, recursive, evidence, cache and range assertions. Preserve scan and batch contract tests, snapshot ownership and deadline/cancellation checks. The shared aggregate contract and Datalevin benchmark contain both routes: remove only enumerate-specific expectations and thresholds.

The formal qualified mutation currently references `qualified-relationship-filters-compose-with-whole-permission-evidence`; replace that consumer with a retained-operation evidence test if the mutation still targets reachable semantics. Remove a mutation only if its production behavior is genuinely deleted. Keep filtered pagination models used by relationship scans or recursive lookup. Run source-closure after implementation; do not paste observed source hashes into maintained artifacts.

### 6. Supersede conflicting live documentation

Update `docs/aggregate-authorization.md` to document batches and authorized reads without presenting two public route choices. Reconcile the unarchived `eliminate-authorization-request-amplification` change's enumerate-route requirements/tasks and relevant active qualified/operator changes so later archive cannot reintroduce the API. Preserve historical reports as historical. The demo's `redesign-resource-first-explorer` clause requiring filtered lookups must be superseded in its companion change. Existing SpiceDB absence is supporting API context, not a claim that this removal makes every EACL extension SpiceDB-compatible.

## Risks / Trade-offs

- **Large parent fanout with sparse access** → Preserve bounded windows and measure candidate work; do not promise the authorized-read replacement always beats the removed path.
- **Shared recursive/qualified machinery deleted by name match** → Reachability inventory plus ordinary forward/reverse, operator, recursive and qualified regression coverage.
- **Rows, ordering and duplicate semantics differ** → Validate the fixed demo fixture; document unsupported general automatic conversion and cursor reset at route changes.
- **Old cursor/cache scope broadens results** → Test pre-removal cursor replay and rejection before cache access; bump semantic ABI only if required by the verified encoding behavior.
- **Caller/core version skew breaks nested expansion** → Migrate callers against a core version supporting authorized reads before switching to the removal build; verify packaged browser/JVM/Jank artifacts.
- **Removing all amplification work by reverting a commit** → Retain request context, schema reuse, batches and authorized scans; preserve their deterministic work counters.
- **Concurrent work in the shared repository** → Planning edits stay in this change directory; implementation must rebase its inventory against current source and preserve unrelated edits.

## Migration Plan

1. Implement and validate core rejection/removal in isolation; publish no artifact yet. Establish package/version and cursor compatibility from the actual build history.
2. Create the companion demo change covering wire shapes, all adapters, browser pagination and Jank verification. Validate it against the currently compatible core using authorized reads.
3. Run shared backend, CLJS, retained formal and source-closure checks; measure broad/sparse/narrow branches without changing the fixture or schema. Record candidate/probe/backend work and cold/warm/cache-bypass latency separately.
4. Coordinate package publication and consumer upgrades only after both sides pass. Test old UI/new service, new UI/old compatible service and new UI/new service, including already-open browser sessions. Version-skew errors must be explicit and preserve authorization, never trigger an unfiltered fallback. Migrate nested calls against a compatible older core first, then remove the obsolete wire surface and adopt the removal core. A release record must state which artifacts no longer emit removed keys and which reject them. Production deployment requires the separate release task; this proposal is not deployment authorization.
5. If validation fails, leave the existing deployed version in place. If a later rollout fails, roll back the compatible consumer/core artifact pair, not the relationship data; keep this removal decision and diagnose the failed migration.

## Open Questions

- First published module versions containing the feature and external adopters have not been established. Inventory these before release to write accurate migration notes; they do not change the rejection/removal contract.
- Production latency and candidate budgets for the platform-wide sparse-user case are unmeasured. Select and record operational budgets before the comparative run; do not derive a passing threshold from the result.

## Adversarial Review Record

The review used three passes: public admission and ownership; execution/qualification/cursor semantics; and consumer transport/fixture/release behavior. Source-level findings have proposed remedies and test obligations. This is not proof of an unimplemented change or an exhaustive claim over all possible executions.

| Finding | Evidence obtained | Strategy correction |
| --- | --- | --- |
| Count requests can reuse unsupported keys | Source trace: count paths do not validate lookup clauses and route on ordinary type/permission/anchor | Add narrow removed-key rejection to both count operations |
| Snapshot preparation precedes orchestration validation | Source trace in Snapshot and public facade | Move owned public admission early; preserve lifecycle-error rules |
| A shared helper's name suggests it is removable when it is not | `cursor-options` also uses branch relation dependencies for read pages | Retain dependency union and test independent membership/permission mutations |
| Qualified read is not equivalent to qualified lookup | `inspection/window-options`, `engine/can?`, qualified direct-edge filter | Restrict automatic migration to certified unqualified fixture; document expired/caveated/conditional counterexamples |
| Demo state changed while planning | Local `1693f04`/`77bf776` now uses authorized reads | Verify current work rather than overwrite it; clean remaining consumers |
| Wire still admits obsolete filters | Executed current JS validator: old triplet accepted | Reject old wire keys uniformly across transports |
| Authorized wire request omits endpoint type | Executed current JS validator: accepted; core validation requires resource type | Require complete authorization/type group at transport boundary |
| Bounded metadata is dropped | Source trace of wire-page and frontend page mapping | Propagate the flag and test short/empty windows and sentinel continuations |
| One foreground request hides more work | Source trace of lookahead plus predicate-free exhaustion probe | Separate foreground/background and candidate/backend accounting |
| Account cardinality was overstated by generic examples | Executed generator: 11 accounts at 10k; manifest: 226 at 1m | Ground performance cases in real fixture fanout; no production latency claim |
| Uniqueness/qualification assumption lacked evidence | Executed 10k generator: 38,613 rows, zero duplicate identities and zero qualified records | Carry fixture admission/seed invariant and test larger supported cuts |
| Arbitrary version pairing can break or broaden behavior | Old/new consumer composition analysis | Add mixed-version tests and prohibit unfiltered fallback |
| Symmetric filter has no symmetric native scan replacement | `:authorization` fixes the checking subject and varies the checked endpoint | Document bounded relationship read + batch checks on one snapshot for actual reverse-filter consumers; do not invert `:on` or add a new API |

The fixture JavaScript oracle additionally returned 11 platform accounts for super-user, four for user-1 and four for user-2 for both view/admin at 10k. This is an expected-result oracle, not execution of EACL or a backend benchmark. None of these checks proves the removal implementation, old-cursor replay behavior on a changed build, full backend/CLJS conformance or production S3 latency. Those remain explicit implementation/release gates in tasks.md.
