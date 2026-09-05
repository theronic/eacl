# Qualified authorization model gate

This Phase 3 gate runs before production engine, cache, or cursor changes.
`EACL_NREPL_PORT=<dev port> bin/formal fast` runs the Phase 2 foundation gate
first, then this gate. Models are verification tools and are never request-path
dependencies. Passing this gate does not activate qualified authorization.

`QualifiedEvidence.dfy` models a residual as a set of Boolean completions of
the selected request's remaining Caveat atoms. This is a denotation, not the
production residual representation. Union, intersection, exclusion, and arrow
composition are pointwise Boolean operations. Any encountered authoritative
fault propagates before Boolean absorbers; CEL's internal expression-error
rules remain the separate Phase 2 contract. Positive recursion has monotone,
finite, least-fixed-point rules; negative dependencies are already stratified.

`QualifiedTemporal.dfy` models sparse references, inert preparation, atomic
pair publication, one captured time, the exclusive expiry boundary, and
decisive witness certificates. Structural qualifier faults precede expiry;
expiry precedes Caveat work. With fixed context and immutable data, time cannot
introduce a new leaf fault, but can remove an evaluator fault by expiring its
edge. This property justifies witness pruning without masking a newly arising
fault. The operator theorem composes over arbitrary finite evidence trees.
Recursive false evidence must retain completeness through the fixed point;
stopping when membership alone stabilizes is deliberately killed.

`QualifiedReuse.dfy` adds canonical (collision-checked) context/evaluator/query
identity, source/schema/Relation/qualifier dependencies, result kind, causal
basis compatibility, and half-open temporal intervals. Missing completeness
allows only the identical time and immutable basis. Live continuation requires
complete retained-state evidence for cross-time reuse. Pinned continuation
keeps basis/time while independently checking token lifetime and key
availability. Decode reuse requires exact basis, certified immutable writer
and Relation/version evidence, or complete native content proof.

The finite Clojure oracle uses completion sets so a production symbolic
encoding can be compared independently. It exercises all 16 residual sets for
two Boolean atoms, all operator outcome combinations, 512 three-node directed
graphs, expiry on recursive grant and subtracting inputs, incomplete
certificates, scope changes, and cursor skipped/frontier evidence. Recursive
membership is checked against a separate per-completion reachability closure.
The Phase 2 lifecycle model supplies preparation/publication transitions.

The production evidence bridge compares reduced ordered decision diagrams with
the independent completion sets across all four operators, all 16 residuals,
two authoritative faults, and complete/incomplete temporal certificates. Its
46,726 assertions cover denotation, deadlines, completeness, missing fields,
and canonical wire round trips. The qualification bridge adds 3,024 assertions
over expiry boundaries, bound/request contexts, malformed facts, allowance,
version, one-fetch request memoization, and evaluator suppression. The combined
finite gate also compares the existing scalar and vector operator machines across 93,312
assertions for demanded intersection, exclusion, and arrow evidence, including
faults and incomplete certificates. Incremental join tests add 1,341 assertions against independent completion sets and bound updates to one ancestor path. The recursive production bridge adds 12,288 assertions across all 512 three-node graphs, conditional grants, expiring-ban bases, and future validity of complete certificates. The stable-route bridge adds 88,064 assertions covering bidirectional arms at two chunk widths, all bounded completion sets, and cyclic path prefixes against an independent positive fixed point. Both bridges check certificates against future expiry states. The native seekable bridge adds 28,672 assertions for both direct generators, both traversal and order directions, two chunk widths, emitted evidence, and certificates for exhausted walks. The general-cover bridge adds 36,864 assertions for exact composed membership, count policies, ascending/descending resume coordinates, and temporal certificates. The arrow bridge adds 79,875 assertions for known-binding reuse, ordered pages and counts through both target kinds, future validity, and the physical shorter-side work bound under an expired prefix. Fifty-four executable mutations at production seams add 109 assertions: ignored qualifier refs, expiry retention, fault/conditional coercion, incorrect horizons, stale reuse, omitted scan shape or qualifier flag, omitted context/time scope, recursive certificate convergence, skipped evidence-witness validation, a regressing clock, snapshot resampling, and a cached grant hiding an already encountered witness fault, coercion of conditional scan heads, lost emission expiry, conditional lookup grants, and count lookahead included in reported categories, coercion of general-cover child evidence, and discarded exact witnesses causing redundant probes, omitted arrow-witness scope validation, and repeated known arrow bindings, lost joint residuals, lost resumed binding evidence, and filtering an entire expired prefix before alternating witness sides. Four native-data controls additionally remove the assertion version, hide unknown fields, omit fetched-fact accounting, or refetch one entity across semantic roles. Each mapped gate must pass before mutation and fail under mutation. A fifth control omits data reads from aggregate resource budgets. Bounded data and request-cache conformance add 220 assertions. Known stable-path refinement adds 12,288 assertions across direct rules, child permissions, and both arrow target kinds; three controls remove its scope check or repeat known paths/bindings. Legacy union lookups add 28,672 assertions over native plans, both directions, resumed coordinates, bounded chunk widths, and future validity of emitted evidence. Three controls activate an inactive path, treat one partial path as the whole node, or force qualified context work on ordinary paths. The combined gate has 580,091 assertions.
Portable evidence tests additionally reject malformed/noncanonical encodings
and enforce node, depth, work, missing-field, and serialized-size bounds.

The lock records 15 seconds and 5,000,000 resources per proof effort, the exact
proof/assertion inventories, and SHA-256 pins for the oracle and mutation
inputs. The whole-tree formal gate retains its stronger existing reporting
requirements. Production refinement, native conformance, mutation controls
against production, performance qualification, and semantic activation remain
Phase 3 implementation obligations. The repository's broader mechanized host
refinement and independent review obligations remain explicit in the assurance
manifest; model success does not discharge them.

Bundled adapters expose a bounded `:qualification-data` operation only with the paired `:bounded-snapshot-data-v1` capability. The read preserves unknown attributes, returns the qualifier marker assertion version from the same basis, and charges every consumed fact, including the overflow witness. Datalevin uses its native prefix limit within the selected owned read snapshot and returns no uncertified assertion version. Shared native fixtures cover the four adapters, Datahike attribute refs, ordinary zero-read behavior, per-request fetch reuse, and fact/command accounting.

Aggregate command and fetched-value budgets use the mandatory request ledger, including qualification data and operator probes. Queue transitions remain sourced from traversal work. Optional observations cannot omit qualified work from command, fact, or allocation-proxy limits.

Qualified acyclic union plans retain the existing sealed-rule order. Their local point checks seed the exact path already found and complete only the remaining alternatives. Has paths take the ordinary fast path; inactive paths cannot claim a boundary from another active rule. Local node evidence is bounded per raw page. Recursive first-discovery pagination and public temporal cursor certification remain separate pending obligations.

Public request context is bounded and canonicalized before snapshot selection or cache lookup, including empty batches and warm requests. Each Caveat receives its declared fields while the complete supplied context remains in the exact qualification scope. The direct Phase 2 evaluator keeps strict unknown-field validation. Three controls bypass context admission, pass unprojected fields, or omit unused fields from identity.

Qualified public point checks bind one request to the existing stable, vector, and recursive routes. Detailed results retain conditional residuals; only the Boolean compatibility boundary converts authoritative evaluation failure to false. Operational errors propagate. The point answer cache currently uses complete exact basis/time/context/evaluator scope and canonical evidence values; managed temporal reuse remains pending. Public integration tests exercise expiring bans, batch sharing, pinned snapshots, changed unused fields, and unstamped qualifier corruption. Three controls drop public qualification, grant conditional results, or erase operational errors.

Authorization schema readers in every bundled adapter validate permission structure without compiling named Caveat programs. Diagnostic schema reads retain complete Caveat validation. Expired public point checks exercise a cold schema path with program compilation disabled; a mutation restores eager full-schema compilation and must fail.

Weighted first-discovery propagation is modeled independently as completion-set reachability. Across 9,216 graph/seed traces it checks closure, bounded evidence growth, unique discoveries, and exact prefix/resume order. Targeted controls omit revisits or path conjunction. These 64,515 assertions precede changes to the production reducer.

The production first-discovery bridge adds 34,432 assertions across 3,072 graph cases in both directions, independently checking emitted completion sets, physical-width invariance, and resumed prefixes. Portable conformance adds 309 assertions for cyclic conditional paths, exact root completion, nil-eid fast paths, definite/detailed counts and windows, lookahead exclusion, bounded skipped prefixes, staged memory/work limits, and checkpoint scope. A temporal cycle can change a queued scan's certificate while a physical buffer remains active; its revision must rewind the prefix independently of chunk width. Five controls discard propagated evidence, claim a partial root, omit the evidence bound, repeat a known direct tuple, or reuse an earlier buffer revision. Qualified checkpoints currently require identical basis/time/context/evaluator scope; public temporal pagination remains pending.

First-discovery pages retain sparse result evidence through lookahead, replay, backward windows, and last windows. Pending lookahead has explicit evidence even for timeless true. Missing, faulty, or scope-incompatible checkpoint data becomes a replay miss. Checkpoint keys partition complete qualified request scope and result policy; standalone tokens bind a compact digest of the same scope. The 209 portable page assertions cover retained versus replayed equality, scope isolation, no-repeat lookahead, and malformed retained state. Four controls remove pending evidence, bypass its validation, omit checkpoint scope, or omit standalone token scope. These internal exact-scope tokens do not yet implement the public Relay live-time certificate contract.

Schema admission now checks a matching evaluator for every Caveated Relation,
including empty and unvisited Relations, and a certified native publication
strategy before entering qualified answer-cache routes. Named alternatives
share one typed Relation identity. Schema replacement validates both stored
endpoint streams and the retained qualifier values before tightening those
alternatives, keeps the Relation eid, and fences concurrent Relationship
changes. Datalevin derives validation and commit guards inside one owned read
snapshot, releases it before submission, and scans large Relation prefixes in
bounded native batches without truncation. Six production controls omit the
evaluator, schema or request publication admission, retained-data validation,
Relation identity retention, or the schema replacement's Relation fence. The
combined gate has 583,204 assertions and 77 detected production mutations.

Physical Relationship inspection shares the bounded qualifier decoder and native
endpoint scan. Stored pages retain expired rows and canonical qualifier metadata;
expiry-active pages apply the exclusive boundary without executing Caveat
programs. The same contract covers all four adapters and both Datahike attribute
modes, both directions and partial/exact anchors, cache-disabled equality, and
renewal/shortening/removal through touch. Six production controls make expiry
inclusive, demand definite Caveat evidence during inspection, drop the aligned
qid or rendered metadata, remove the candidate-work limit, or admit noncanonical
cached metadata. Qualification deadline and cancellation tests stop immediately
after a native data read and preserve the operational error on retry. The combined
gate has 584,088 assertions and 83 detected production mutations.

Optional qualifier decode retention now uses exact-basis or complete native
content proof. The latter includes source lifecycle, qid, assertion version,
format, all qualifier fields, the owning Relation and named Caveat definition.
It does not infer a source-wide immutable-writer contract from publication
capability. Portable and public native tests add 93 assertions for same-basis
fetch omission, cross-basis decode reuse with content reads, fresh per-request
context/expiry evaluation, unchanged-marker mutation, deletion, source reset,
capacity, and read-only/disabled cache controls. Five controls omit native
content, definition content, lifecycle, Relation proof or exact native basis.
The combined gate has 584,191 assertions and 88 detected production mutations.

Completed point answers now carry their evidence, original evaluation time,
exclusive deadline, completeness and result kind. Exact-basis reuse checks time
on every resident hit; completeness is required for a later time. Five production
controls bypass resident interval validation, claim missing completeness, detach
the certificate from evidence, allow older publication to displace a newer
interval, or erase an expiring-ban witness. The independent reuse-model bridge
adds 432 comparisons; portable acceptance/retention tests add 886 assertions.
Native checks cover ban expiry, a permanent decisive witness, unchanged context
scope and pinned evaluation. A cache publication race test verifies conditional
replacement cannot overwrite a concurrent winner. The combined qualified gate
has 586,029 assertions and 99 detected production mutations. Cross-basis
managed qualified result proof integration remains pending.

Public qualified cursors now refine the pinned/live time model through the
production temporal bridge. The request ledger observes examined expiry bounds
and imports accepted retained proofs. Internal collection values, range
segments and stable checkpoints preserve their certificates; six additional
mutation controls cover skipped-ban deadlines, live time acceptance, incomplete
proof, context/evaluator scope, range retention and closed cursor envelopes.
Public tests include forward/backward continuation, both lookup directions and
evaluation modes, pinned historical time, active/stored inspection and typed
restart without extra qualification work.

Cache coherence conformance compares cold, warm, repeated and read-only-cache
results at eight temporal/write/lifecycle states on Datomic, DataScript, both
Datahike modes and Datalevin (the latter in its native module gate). The trace
covers three contexts, five permissions, both lookup directions/result policies,
and capped/exact counts. Healthy traces also assert that results contain no
fault. Unknown-writer subtracting faults and restored DataScript identities
have a separate trace. Two controls omit shared-denotation time or whole request
context; both fail only their mutated runs. The combined qualified gate now has
598,592 assertions and 101 detected production mutations.

Qualified object deletion is checked after every transaction with a forced
12-operation cap, on all native backends and both Datahike attribute modes.
The contract includes expired edges, self-edges, inert preparation preservation,
and stale-plan rejection after an unstamped identity change or qualifier touch.
DataScript additionally checks surviving-peer cleanup and schema orphan release.
Three controls remove qualifier cleanup, split peer retractions, or omit the
native selected-basis guard. Each passes its unmodified gate and fails mutated.

The combined qualified gate now has 598,713 assertions and 104 detected
production mutations, with 71 verified model obligations.
