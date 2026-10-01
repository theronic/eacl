## Context

A SpiceDB relation may allow a concrete subject type (`user`), its wildcard
(`user:*`), or both, each optionally `with` a Caveat. A relationship whose
subject is `user:*` makes every user a member of that relation on that
resource. EACL v8 stores each relationship as two five-slot endpoint tuples on
the subject and resource entities and evaluates permissions with sealed rule
plans (union and arrows) and operator plans (intersection and exclusion).

SpiceDB v1.56.0 (the version EACL's differential fixtures pin) was probed
through its HTTP gateway on 2026-09-27. It:

- rejects a wildcard on the left side of an arrow: "wildcard relations cannot
  be used on the left side of arrows";
- rejects `user:*` on a relation that does not declare it, and suggests the
  Caveated branch when only `user:* with c` is declared;
- rejects `*` as a resource ID, and a wildcard subject in CheckPermission and
  LookupResources;
- returns the wildcard from LookupSubjects beside concrete subjects that have
  their own relationships, and reports subjects removed by exclusion under
  `excluded_subjects` (for example `(viewer - banned) + editor` with
  `viewer: *`, `banned: bob, carol` and `editor: bob` returns `bob` and `*`
  excluding `carol`; `viewer & editor` with `viewer: *` returns only the
  concrete editors);
- evaluates a Caveated wildcard as conditional until context is supplied.

## Goals / Non-Goals

**Goals:** SpiceDB's wildcard semantics for schema admission, writes, checks,
resource lookups and subject lookups; unchanged behavior, fingerprints and
cost for schemas without wildcards; every public backend.

**Non-Goals:** subject relations (`group#member`), `.all()`, a
`wildcard_option` request flag, and SpiceDB's exact representation of
conditionally excluded subjects.

## Decisions

### D1. One Relation entity per subject type, with wildcard allowances

A Relation entity stays unique per `[resource-type relation subject-type]`.
Its wildcard branch is recorded by two additive attributes:

- `:eacl.relation/allows-unqualified-wildcard?` (boolean), present exactly
  when the relation declares a `T:*` branch; true when a bare `T:*` is
  allowed.
- `:eacl.relation/wildcard-caveats` (refs to Caveat definitions), present
  when a `T:* with c` branch exists.

The concrete branch keeps its encoding. A relation with only a wildcard
branch (`relation anyone: user:*`) stores
`:eacl.relation/allows-unqualified? false` without `:eacl.relation/caveats`,
the one previously invalid concrete state, so a Peer from before this change
fails closed on it. A separate Relation entity for the wildcard branch was
rejected: it would need a distinct relation name or subject type in the
unique identity tuple, which leaks into every read, filter and error.

### D2. One wildcard subject entity per database

Every `T:*` subject is the EACL-owned entity `{:eacl/id
"eacl.wildcard-subject"}` (W). A wildcard relationship is an ordinary endpoint
pair whose subject is W: the forward tuple `[T relation RT resource q]` is on
W and the reverse tuple `[RT relation T W q]` is on the resource. The subject
type slot keeps `user:*` and `group:*` apart, so one entity serves all types
and the public ID `*` resolves without knowing the type. Schema writes upsert
W when the new schema declares any wildcard branch; EACL never retracts it.

The public ID `"*"` is reserved. Client identity conversion maps `"*"` to W
and W to `"*"`; a concrete ID that would resolve to W is treated as unknown,
and a concrete object whose external ID is `"*"` is rejected with
`:eacl/reserved-object-id`. Request validation rejects `*` as a resource
anywhere and as the subject of `can?`, `check-permission(s)`,
`lookup-resources` and `count-resources` with `:eacl/wildcard-not-allowed`.

### D3. Writes validate the subject form

`validate-relationship-write!` checks the concrete or wildcard form of the
subject against the relation (`:reason :wildcard-subject-not-allowed` or
`:concrete-subject-not-allowed`). The qualified writer selects the branch's
Caveat alternatives from the subject form, so `user:* with c` requires `c`.
A schema replacement that removes a wildcard branch, or a Caveat from it,
while a stored relationship still uses it fails like the existing Caveat
allowance check.

### D4. Forward evaluation: wildcard variants of relation rules

Membership of a concrete subject `s` of type `T` in relation `X` on `r` is
`tuple(s, X, r) ∨ (X allows T:* ∧ tuple(W, X, r))`. Each backend's
`:relation-defs` row gains `:wildcard-eid W` when `X` allows `T:*`. The sealed
plan compiler then emits, next to each `:relation` rule and each
`:arrow-relation` rule whose target relation allows the wildcard, a variant
rule carrying `:wildcard-eid`. A variant is an independent derivation whose
subject-side scans and probes use W instead of the subject. Forward
enumeration (least-path and first-discovery), the membership-probe check and
the bidirectional arrow arms therefore union the subject's own evidence with
the wildcard's, including separate Caveat and expiry evidence for each
tuple. Operator relation partitions carry the same `:wildcard-eid`, and the
scalar, vector and recursive operator evaluators probe W beside the subject.
Seekable direct specializations are not selected for partitions with a
wildcard. Plans without wildcards contain no variants and keep their
fingerprints.

The recursive operator routes beneath this change take the same variants.
The membership search (`stable-route/check-many-eids`: leveled expiring
search and guarded programs) holds the wildcard subject's relation slices
beside the subject's own, including in guards and subtracted guards; and the
delegated operand oracle decides union operands through their sealed union
plans. The touch route passes that oracle to the exact evaluator. Closures
whose wildcard branch declares a Caveat use the ordered evaluators, as for
concrete branches.

### D5. Reverse evaluation: the wildcard is one subject

Reverse traversal enumerates the subjects that hold a permission through
their own tuples; W is such a subject whenever a wildcard tuple reaches the
resource, so it is emitted and rendered as `{:type T :id "*"}`. Variant rules
contribute nothing in reverse, and every derivation test used as a reverse
witness runs with wildcard variants disabled. For union-only permissions this
already equals SpiceDB: nothing can exclude a subject that the wildcard
admits.

With intersection or exclusion a subject can be granted through the wildcard
while holding no positive tuple of its own (`viewer & editor` above), or be
excluded from it. When a wildcard relation for the requested subject type is
in the permission's positive relation closure, the reverse operator lookup
enumerates the permission's touch set: the relaxed cover in which every
intersection and exclusion becomes a union of all its operands, without
intermediate filtering. Each candidate is then decided by the exact,
wildcard-aware predicate. A subject outside the touch set has exactly W's
leaf memberships, so W's decision stands for it.

`lookup-subjects` returns, in candidate order: every touch-set subject whose
decision grants, and the `*` entry when W's decision grants. The `*` entry
lists under `:excluded-subjects` every touch-set subject whose decision is
not a definite grant, under both result policies; under `:detailed`, a
conditional subject is also returned as its own conditional entry. A subject
holds the permission through its own entry or, unless `*` excludes it,
through `*`, which is SpiceDB's reading. Excluding only denied subjects would
let `*` complete a conditional subject's entry to a definite grant; the Dafny
model rejects that rule. EACL lists a granted touch-set subject even when `*`
would cover it, a superset of SpiceDB's listing with the same meaning.

For union-only permissions the reverse enumeration lists each subject's own
derivation beside `*`. Joined, the entries denote every subject's permission
exactly. The definite listing omits a subject that only its own conditional
derivation and a conditional wildcard grant together, as SpiceDB's
representation also leaves those two entries separate.

`count-subjects` counts result entries; the `*` entry counts once and
excluded subjects are not counted.

### D6. Caching and cursors

Wildcard tuples live under their Relation's eid, so relationship writes and
`delete-object!` stamp the same relation generations and every dependency
frame, scan cache key, checkpoint and cursor keeps its meaning. Wildcard
plans differ from non-wildcard plans in rules and fingerprints, so cursors
cannot cross a schema change that adds or removes a wildcard.

### D7. delete-object!

Deleting a concrete object removes each wildcard relationship on it through
the existing exact peer-half cleanup. `delete-object!` of `T:*` removes every
relationship whose subject is `T:*` and no relationship of another type's
wildcard.

### D8. Verification

- `formal/fixtures/wildcards/` records SpiceDB v1.56.0's answers to 72
  requests; `eacl.datascript.wildcard-spicedb-golden-test` compares checks,
  resource lookups, permission trees and reads for equality, subject lookups
  by what they grant to every known subject, and rejections by error class.
- `formal/dafny/WildcardSubjects.dfy` proves the representative (touch-set)
  theorem, exactness of both touch-set listings, the union-only split and
  soundness of its definite listing, with a witness of the conditional
  corner above.
- `eacl.wildcard-reference` evaluates one wildcard schema independently;
  `eacl.datascript.wildcard-differential-test` compares every read API over
  seeded stores in CLJ and CLJS.

## Risks / Trade-offs

- A Peer from before this change does not read the new attributes. It fails
  closed on wildcard-only relations but would treat W as a concrete subject
  elsewhere. Every serving Peer must be upgraded before a wildcard schema is
  written (the same rollout rule as Caveats).
- `*` becomes reserved. An application object whose external ID is `*` can no
  longer be used with EACL.
- A subject lookup whose permission combines a wildcard with intersection or
  exclusion reads the whole touch set of the resource to produce the
  exclusions of the `*` entry, as SpiceDB does. Traversal limits bound it.
- Pre-existing behavior found while testing, unchanged here: expanding a
  relation that holds a Caveated relationship fails with
  `:eacl.permission-tree/adapter-contract-violation`, and a Caveat that tests
  `in` on a missing `list<string>` parameter fails the work preflight
  (`:resource-limit`) instead of returning a conditional result.
