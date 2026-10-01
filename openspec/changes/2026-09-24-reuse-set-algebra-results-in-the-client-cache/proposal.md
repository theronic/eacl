# Reuse set-algebra results in the client cache

## Why

Each EACL client owns a cache whose answer and denotation tiers hold completed
authorization results. Some deployments persist it across restarts; the
Datahike demo's Lambda reader writes it to S3 on shutdown. Set-algebra
permissions get less out of this cache than they could, for two reasons:

- **Time-keyed point decisions.** The recursive and vector operator evaluators
  publish one point decision per candidate. When relationships are qualified
  (any client with a clock and expiring or caveated relationships), the key
  includes the exact evaluation time in milliseconds. A later request, or a
  restored cache, never finds them. The answer tier already solves this for
  public answers: its key omits the time, and the value records the interval
  its evidence certifies.
- **Uncached operand decisions.** The union-only operands of a delegated plan
  (#199, #200) and the guarded members of a guarded plan (#202) are decided
  per request and discarded. A walk of `delete = delete_granted & read_account`
  decides both operands for every candidate. Yet `delete_top`, which shares
  those operands, recomputes them, and so does a later `can? read_account`.

Measured on the 636-account chart with caching on and a constant clock, a
`delete_top` walk that follows a `delete` walk costs 35 ms. A repeated walk
served from the answer tier costs about 2 ms. With a real clock, a check that
follows a walk also recomputes its root decision.

## What Changes

- **Certified cross-time point reuse.** Qualified point decisions in the
  denotation tier are keyed by a certified scope: the qualification's caveat
  context and evaluator, without the evaluation time, and without the basis,
  which the exact storage key already carries. They are stored as temporal
  point answers: start time, certificate end, completeness, permissionship and
  encoded evidence.
  - A lookup accepts an entry only where `temporal/reusable?` holds at the
    request's time: at or after its start, before its certificate's end, and
    with complete evidence. Otherwise it must be the entry's own time and
    basis.
  - A later computation replaces an entry it could not reuse.
  - This applies to the recursive and vector evaluators' existing root
    decisions. Unqualified requests keep Boolean values.
- **Operand decisions in the denotation tier.** Every union-only operand
  decision and every guarded-member decision is published as a point decision
  of that permission. It is keyed by the permission's own sealed plan (or
  guarded program) fingerprint, the subject and the resource. The same entries
  are consulted before deciding, by:
  - the batched memoized search (`check-many-eids`), which serves lookups,
    counts and guard evaluation;
  - the point check (`check-eids`), which serves operator checks through the
    point oracle and public checks of union-only permissions.

  Operator permissions that share an operand then share its decisions, and a
  later check of the operand itself reuses them. Only complete resource-level
  decisions are published, never the search's per-level memos, holdings or
  skip bounds.
- **Cheap enough to pay for itself.** Lookups and publications run once per
  batch of candidates. The requests of one client on an unchanged basis share
  one key constructor, so a later request's lookup matches a resident key by
  identity rather than comparing basis maps. Validation of certified values
  decodes the common scalar evidence without the generic reader.
- **No new cache, and no answer changes.** Everything goes into the client's
  existing subproblem store, which is bounded, private to the client and
  exported by `export-basis-snapshot`. Cache lookup, miss, publication or
  eviction never changes a value, residual, order, cursor or count.
  `:cache? false` bypasses all of it.
- **Certification and budgets.** The change adds:
  - a Dafny leaf built on `QualifiedTemporal.dfy`'s certificate soundness:
    a decision reused within its certificate is the fresh decision;
  - a randomized cache-on versus cache-off differential over advancing
    clocks;
  - mutation controls;
  - a `^:benchmark` reuse gate whose budgets are written before sampling.

## Capabilities

### New Capabilities

- `set-algebra-result-reuse`: reuse of set-algebra results in the client
  cache. It covers:
  - operand and guarded-member decisions as point denotations of their own
    permission, consulted by the batched and point membership checks;
  - cross-time reuse of qualified point decisions within their certificates;
  - persistence of both through the existing snapshot export;
  - the gates and certification that pin them.

### Modified Capabilities

- `verified-subproblem-cache`: the requirement that keys commit to every
  answer-affecting input gains a clause. A qualified point decision may leave
  the evaluation time out of its key when its value records the interval its
  evidence certifies, and is then eligible only within that interval.

## Impact

- **Code:**
  - `eacl.engine.stable-route`: `check-eids` and `check-many-eids` consult and
    publish operand decisions.
  - `eacl.operator.recursive` and `eacl.operator.vector-evaluator`: time-free
    qualified point keys and temporal point values.
  - `eacl.authorization.point-reuse` (new): batched reuse and publication of
    point decisions, and the value each key admits on restore.
  - `eacl.authorization.qualification`: the certified denotation scope.
  - `eacl.subproblem-cache`, `eacl.cache.key` and `eacl.cache`: batch lookup
    and publication, a shared key constructor per basis, and snapshot
    admission of the new entries.
  - `eacl.authorization.evidence` and `eacl.authorization.temporal`: a scalar
    decode path and allocation-free shape checks.
- **Cache contents:** more denotation entries per walk, up to one per operand
  decision, within the configured `:denotation-max-entries` bound. Old
  time-keyed entries become unreachable under the new key version and age out.
  No snapshot format change.
- **Formal:** a Dafny leaf, an assurance contract entry, mutation controls.
- **Tests:** a cross-time reuse suite, a cache-on/off differential campaign,
  and a `^:benchmark` gate in `modules/eacl-datahike/test/eacl/bench`.
- **Behaviour:** no public API change. Results are unchanged; later requests
  on the same client, and restored caches on the same basis, do less work.
- **Delivery:** one pull request stacked on #202.
