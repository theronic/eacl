# Active-change overlap and ownership

Source base: `e137dc55512d4eeebcc31cfbe5087d61ab04465b`

The sole execution plan for this branch is the workspace change
`/Users/petrus/code/eacl/openspec/changes/eliminate-eacl-performance-amplification`.
That workspace is deliberately not a Git repository. Normative contract edits
will therefore be made directly to Core's durable main specs in this branch;
the workspace deltas are a review and traceability surface, not a child change
that must later be archived into Core.

`openspec validate eliminate-eacl-performance-amplification --strict --json`
passed before production edits. `openspec list --json` in Core reports six
in-progress changes with direct or adjacent overlap. None is a prerequisite
for this branch, none will have its task list rewritten here, and this branch
will not wait for any of them to be archived.

| Existing Core change | Direct overlap | Ownership decision for this branch |
|---|---|---|
| `adopt-stable-discovery-enumeration` | Sealed plans, stable reducer, fingerprints, counters, CLJS parity, dead generated routing | Preserve its first-discovery semantics, public order, point-route separation, and remaining independent release work. This branch owns only the reproduced sink retention, completion uniqueness rebuild, physical-vector/suffix retention, bounded-sidecar bookkeeping, scheduler fast paths, and the narrowly specified acyclic execution-frontier alias repair. It does not complete that change's CLJS, remote-topology, deployment, or archival tasks. |
| `membership-probe-point-check` | Point routing and direct membership | Preserve point-check semantics and its normalized/aligned native batch ABI. This branch owns redundant singleton-plus-chunk normalization and checked internal representations only. Its outstanding randomized backend differential and release note remain independent. |
| `hierarchical-demand-segment-cache` | Stable physical fetch seam, subproblem tiers, terminal scan evidence, Datomic request overhead | Do not implement the proposed scan-response cache, hierarchical segments, or its cleanup program under this branch. Terminal evidence remains independently droppable until ledger finalization. This branch owns only reproduced live mechanisms and cannot cite that proposed change as prerequisite evidence. |
| `fix-datomic-request-overhead` | Plan/schema caching and request validation | Treat its implemented source as the baseline. This branch owns newly reproduced resident-hit delay allocation, shared-delay coordination, duplicate snapshot identity reads, and repeated remaining validation. Its three outstanding documentation/benchmark/follow-up tasks are not adopted wholesale. |
| `acyclic-keyset-pagination` | Least-path route, order/fingerprint inputs, acyclic planning | Preserve the shipped order regime and cursor semantics. This branch owns same-resource single-body alias normalization only in a separately rebuilt acyclic execution frontier. It does not change the semantic graph or recursive route, and it explicitly excludes that change's demo task. |
| `bidirectional-arrow-point-check` | Point route, per-command cancellation, sealed-plan reads | Preserve the bidirectional algorithm, typed failures, and command cut points. No recursive-arrow rewrite is imported. Any shared checked request representation must remain below the same point-route contract. |

Three complete-but-unarchived Core changes also explain live contradictions:

- `eliminate-authorization-request-amplification` established generation-owned
  derived caches, but resident hits still construct candidate delays and public
  scans still repeat validation.
- `add-authorization-views` established one selected request context and rewrote
  Datomic exact acquisition, but its current exact path bypasses the already
  present `await-basis-db` local-coverage helper and unconditionally syncs.
- `sync-datomic-exact-snapshots` supplied the durable local-covered rule now in
  `backend-native-revision-consistency`; current production source regressed
  from it. The durable main spec, not the old change directory, is authoritative.

The four cache main specs last changed together at
`81c0ee427f95baa15603c3972fbf42aa4405fd43`; their surviving flight/join text
contradicts the live independent-miss implementation. This branch directly
owns reconciliation of `nonblocking-cache-coordination`,
`single-flight-coordination`, `answer-cache-bounding`, and
`verified-subproblem-cache`, while preserving completed-value publication,
lifecycle, weight, attempt, and recency obligations.

The existing auxiliary Core worktrees were read-only audited:

- `/private/tmp/eacl-formal-ledger.0xlIGM`
- `/private/tmp/eacl-portable-cache.hXaH90`
- `/private/tmp/eacl-v8-release.Yg2l5S`
- `/private/tmp/eacl-v8-snapshot-release.zRfcTK`

All four were clean, and none differed from `main` in the engine, request,
cache, Datomic exact-acquisition, or named durable-spec paths owned here. No
cross-worktree commit or generated output will be imported.

This audit excludes demo, CORS, Lambda, EC2, and deployment configuration.
