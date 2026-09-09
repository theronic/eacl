## Context

`cleanup-plan` performs `proof-input` before `report` limits its candidates.
`proof-input` explicitly scans the complete database view and materializes
qualifier facts, versions, references, and selected definitions. Calling the
single-batch function repeatedly reconstructs that proof after every deletion.
Source: [modules/eacl/src/eacl/relationships/qualifier_integrity.cljc — proof-input, cleanup-plan, cleanup-orphans!](https://github.com/theronic/eacl/blob/6c3f33f2449ea10ba56b88b3e9d9f076b1ab2d56/modules/eacl/src/eacl/relationships/qualifier_integrity.cljc).

For Q=mB, the qualifier visits are `B*m*(m+1)/2`, even without active qualifiers.
At Q=1,000,000 and B=100 this is 5,000,500,000 visits. These are arithmetic lower
bounds under a quiescent drain workload, not measured runtime figures.

## Goals / Non-Goals

Amortize one valid global proof over the sweep's own bounded cleanup commits.
Preserve orphan ownership safety, source isolation, exact head guards, native
entity-fact assertions, and maximum transaction size. Make partial progress and
restart requirements explicit. Preserve the existing single-batch API.

Do not remove guards, introduce stale read-side repair, assume all native
transaction reports have the same shape, or add a per-qualifier owner sidecar in
this change. Do not claim constant memory simply because commit batches are small.

## Decisions

### 1. Separate capture from draining

Introduce a dedicated sweep runner or an opaque in-process sweep session. At
capture, select one native snapshot, validate healthy qualifier data, and produce
plain candidate ids plus the facts needed for conditional deletion. Retain a
complete source/lifecycle identity and exact selected revision.

Do not retain a borrowed backend snapshot beyond its allowed lifetime. Materialize
only the necessary plain proof/candidate data or keep a legitimately owned lease.
Expose and enforce an explicit sweep memory/candidate budget while scanning;
checking only after full allocation is not a bound. Large captures can use a
bounded spool or a separately designed streaming strategy. The first implementation
must state its retained-memory complexity rather than hiding it behind batch size.

### 2. Carry authority forward only through self commits

Let the initial proof certify candidate set C as unattached at exact revision t0.
For each candidate chunk D:

1. Select a current native snapshot and require its exact source and revision to
   equal the sweep certificate's expected source and revision.
2. Submit the same exact-head and candidate-fact guards as the existing safe
   cleanup, plus deletion of D, within the configured transaction-size limit.
3. Obtain an authoritative commit result identifying the exact revision produced
   by **this** transaction. The sweep may replace its expected revision only with
   that result, not a later `current-db` sample.
4. Remove D from C. All remaining candidates remain unattached because the only
   intervening certified transition was deletion of unattached qualifier data.

A foreign write, failed guard, unexpected source/lifecycle, unsupported commit
revision evidence, or transport ambiguity invalidates the sweep certificate.
Return explicit restart-required/partial-progress state or capture a new proof.
Never blindly rebase an old candidate list onto a new current revision.

If a backend cannot provide a trustworthy own-commit revision, retain its current
single-batch behavior until that evidence is implemented; do not claim the
optimized contract for that backend.

### 3. Preserve successful partial progress

A sweep consists of several individually atomic commits, not one giant atomic
transaction. On interruption, already committed orphan deletions remain valid.
Return their count and explicit continuation/restart status. Cancelled or failed
chunks must not be counted as committed. Release any owned snapshot/session
resources. Restart after process loss requires fresh ownership evidence, not an
unauthenticated serialized candidate list.

### 4. Use operation-count acceptance before timing

Instrument complete proof captures, both endpoint scan streams, qualifier entity
and fact reads, candidate materialization, and transaction sizes. On an otherwise
quiescent sweep within configured budgets, the number of global captures is one
rather than proportional to Q/B. Total qualifying/candidate work should be
O(A+Q) plus O(Q/B) guarded commits, where A is the stable scanned active data.
Any chosen sort adds its separately reported term.

Exercise several increasing Q values at a fixed B and a fixed nonzero active
background. Repeated single-batch behavior is the baseline. Use actual native
adapters; a counter over returned candidate counts is not enough. Author latency
and allocation budgets before sampling. Keep raw outputs under ignored target.

## Formal invariant

At each certified sweep state, all remaining candidate qids are unattached in the
exact expected snapshot. A successful own cleanup step only deletes a subset of
those qids and cannot attach the remainder. A non-cleanup transition requires a
new global absence proof or an independently justified per-candidate proof.

Retain hostile traces: publish a candidate between batches; prepare a new qualifier;
change a source/lifecycle; fail a head guard; lose the commit result; cancel after a
successful batch; restore an old session against a new database. No trace may
delete a qualifier attached to a surviving Relationship.

## Alternatives considered

Increasing B only reduces the coefficient and can violate transaction budgets.
Dropping head guards permits deleting concurrently attached qualifiers. Rescanning
only requested qid facts does not prove global absence of endpoint references.
A new ownership index could make point proofs possible, but would expand storage,
writer, and formal obligations and is intentionally not the release-local remedy.

## Open questions to resolve during implementation

Choose a synchronous sweep runner versus an opaque resumable session based on
actual writer/lease capabilities. Establish memory/spool budgets and exact commit
revision evidence per backend. Treat these as implementation decisions, not
reasons to ship a guardless optimization or advertise unmeasured performance.
