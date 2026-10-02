# Spec Delta

## Purpose

Lets later requests on one EACL client, and a cache restored from a persisted
snapshot on the same basis, reuse the operand and operator decisions that
set-algebra permissions compute, without changing any answer.

## ADDED Requirements

### Requirement: Operand decisions are reusable point decisions of their own permission
When an operator permission is evaluated, each operand decision made by the
union engine and each guarded-member decision SHALL be published to the
client's subproblem cache as a point decision of that operand permission. Its
key SHALL commit to the operand's own sealed plan or guarded program, the
subject, the resource, the selected basis and the qualification scope. The
same decisions SHALL be consulted before an operand or guarded member is
decided, both for many resources of one subject and for one point. A public
check of a union-only permission SHALL consult them too.

Only complete decisions of a resource SHALL be published. The search's
per-level memos, holdings, skip bounds and deferred markers SHALL NOT be; a
qualified publication of anything but evidence SHALL fail rather than store
it.

#### Scenario: Sibling operator permissions share an operand
- **WHEN** a client walks `delete = delete_granted & read_account` and then, on the same basis, walks `delete_top = deleter + (delete_granted & read_account)`
- **THEN** the second walk decides no `delete_granted` or `read_account` resource that the first walk decided, and its results equal an uncached walk's

#### Scenario: A check of the operand reuses an operator walk
- **WHEN** a client walks `delete` and then checks `read_account` for a resource the walk decided
- **THEN** the check is answered from the published operand decision, with the permissionship an uncached check returns

#### Scenario: Guarded members are reused
- **WHEN** a client walks `inherited = reader + (parent->inherited & eligible)` and then checks `inherited` or evaluates a permission that has it as a guard
- **THEN** the guarded member's decisions are reused rather than searched again

### Requirement: Qualified point decisions are reusable within their certificates
A qualified point decision, whether of an operator root or of an operand,
SHALL be stored with the interval its evidence certifies: the time it was
computed, the time its evidence stops holding, and whether that certificate is
complete. Its key SHALL NOT include the evaluation time. A later request SHALL
reuse it only at a time at or after it was computed and before its evidence
stops holding, and only when the certificate is complete. A decision with an
incomplete certificate SHALL be reused only at its own time. A decision computed at a time when an
entry cannot be reused SHALL replace that entry.

#### Scenario: A check after a walk at a later time
- **WHEN** a client walks `delete` at time t0 and checks `delete` for one of the walk's resources at a later t1, before any certificate the decision rests on ends
- **THEN** the check reuses the walk's decision and returns what an uncached check at t1 returns

#### Scenario: A certificate has ended
- **WHEN** a grant that rests on a share expiring at T is cached at t0 < T, and the same point is decided at t1 ≥ T
- **THEN** the cached decision is not reused, the point is decided again at t1, and the new decision replaces the entry

#### Scenario: A conditional decision
- **WHEN** a decision is conditional on a caveat and its certificate is complete
- **THEN** it is reused within that certificate under the same caveat context, with the residual an uncached check returns

#### Scenario: An incomplete certificate
- **WHEN** a cached decision's certificate is incomplete
- **THEN** it is reused only at the time it was computed, never at a later time

#### Scenario: Caveat context differs
- **WHEN** a decision was computed under one caveat context
- **THEN** a request under a different context does not reuse it

### Requirement: Reuse never changes an answer
Publishing or reusing set-algebra decisions SHALL NOT change any public value,
permissionship, conditional residual, missing-field list, order, cursor, page
flag, count or typed error. This SHALL hold after an entry is evicted,
replaced, exported and restored into another client on the same basis, or
imported under trust. `:cache? false` SHALL bypass every lookup and
publication this capability adds.

#### Scenario: Cache-on and cache-off differential
- **WHEN** random sequences of walks, counts and checks over operator and operand permissions run at non-decreasing times on one caching client, and each request is repeated with `:cache? false` at the same time
- **THEN** every pair of results is equal, including the residuals of detailed items

#### Scenario: A restored snapshot
- **WHEN** a client's cache is exported after a walk and restored into a fresh client on the same basis
- **THEN** the fresh client reuses the restored decisions, and its answers equal an uncached client's

#### Scenario: A restored value disagrees with its key
- **WHEN** a snapshot holds a Boolean under a qualified key, a point answer under an unqualified key, or a point answer whose interval, completeness or permissionship disagrees with its evidence
- **THEN** the restore fails and the client's cache is unchanged

### Requirement: Reuse meets its budgets
A `^:benchmark` gate SHALL measure reuse on the operator-shapes charts of 636
and 2,077 accounts. It uses a client clock that advances 60 seconds between a
setup request and the measured request. The measured request runs on a client
with compiled plans whose cache the setup populated. The reference runs the
same request on a client with compiled plans and no setup. The gate SHALL
require:

- a `delete` check after a `delete` walk: no root decision computed, and a
  median of at most 0.5 times the reference;
- a `read_account` check after a `delete` walk: no membership search, and at
  most 0.6 times;
- a `delete_top` walk after a `delete` walk: no operand membership search, and
  at most 0.75 times;
- an `inherited` check after an `inherited` walk: no search, and at most 0.5
  times;
- a `delete` check on a fresh client whose cache was restored from a snapshot
  exported after a `delete` walk: at most 0.5 times;
- the first cached `delete` walk on a client with compiled plans: at most 1.25
  times the same walk with `:cache? false`.

Every sample SHALL also assert its answer.

#### Scenario: Budgets fixed before sampling
- **WHEN** the gate is added
- **THEN** its budgets are the ones above, written before any sample of the implementation is taken

### Requirement: Reuse is certified
The repository SHALL certify this capability with:

- a Dafny model proving that a cached point decision reused within its
  certificate equals a fresh decision at that time;
- the cache-on/off differential campaign;
- mutation controls, killed on the JVM and in ClojureScript, for:
  - reuse at or after the certificate's end;
  - a key without the caveat context;
  - reuse of an incomplete certificate at a later time;
  - reuse without observing the reused decision's certificate on the request;
- an assurance contract entry.

#### Scenario: Mutated reuse
- **WHEN** any registered mutation is applied
- **THEN** the differential or its targeted detector reports a different answer
