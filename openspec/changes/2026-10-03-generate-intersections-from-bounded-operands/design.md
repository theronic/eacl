## Context

`eacl.operator.plan` seals one anchor per intersection node
(`select-intersection-anchor`). The cover of a plan (`eacl.operator.cover-plan`)
follows every child of a union, the left operand of an exclusion and the
anchor of an intersection; lookups and counts enumerate the cover and decide
the root per candidate. The anchor was the child with the smallest tuple
`[direct? sequence-compatible? depth work node-id]`:

- a reference to a permission has depth 0 and work 1 whatever it expands to,
  so two named operands tie and the canonical node id, which follows the
  operand's name, decides;
- a relation always wins, a wildcard relation included.

Forward enumeration of a relation that declares `T:*` reads the subject's
relationships and the wildcard subject's (`:wildcard-eid` rule variants). The
wildcard's relationships are the same for every subject.

Constraints that this change keeps:

- Generator selection is deterministic from sealed schema facts; cache
  contents, timing and "discarded semantic pilots" must not affect it
  (`design-demand-driven-set-algebra-engine`, requirement "Candidate selection
  is stable and non-observational"; control
  `:operator-cache-selected-generator`).
- A cursor authenticates the plan fingerprint and its anchors; a resumed
  request that seals another generator is refused with a typed error.
- Schemas without wildcards keep their plans, fingerprints and cursors
  (`2026-09-27-add-wildcard-subjects`).

## Goals / Non-Goals

**Goals:** a lookup or count of an intersection never enumerates through a
wildcard relation when an operand offers a cover that reads none, whatever
the operands are called and however they are written; no answer changes.

**Non-Goals:** choosing among operands by observed selectivity; changing the
anchor of any plan that reads no wildcard branch; the cost of an
intersection whose every operand reads a wildcard.

## Decisions

### D1. A wildcard cover is a schema fact

`wildcard-covers` computes, per permission of the closure, the node ids whose
cover reads a wildcard branch:

| node | has a wildcard cover when |
|---|---|
| relation | a partition declares `T:*` |
| arrow | a target relation declares `T:*`, or a target permission's root has one |
| permission reference | the target's root has one |
| union | some child has one |
| intersection | every child has one |
| exclusion | its left operand has one |

Permissions refer to each other, possibly in cycles, so the roots are the
least fixed point of these equations, reached by iteration from no root. The
equations are monotone. A cycle that reaches no wildcard relation has no
wildcard cover: `loop = owner + (subscriber & loop)` generates from `owner`
alone.

The intersection row states what D2 makes true: its cover is its anchor's.

### D2. The anchor avoids a wildcard cover

`select-intersection-anchor` sorts children by `[wildcard-cover? tuple]`. A
child without a wildcard cover precedes every child with one; within either
group the structural tuple decides as before.

This is the eacl-rust rule (`lookup.rs`, `root_anchor`: "an anchor of a root
`∩`/`−` is skipped when the wildcard subject holds relationships in it,
since the subject's own edges then no longer bound the operand"), with two
differences. It reads the declaration, not the stored relationships, so the
plan stays a function of the schema. And it follows the wildcard through
arrows, references, unions and exclusions at every intersection. The Rust
engine applies its rule to direct-relation operands of a root intersection,
where its keyset join also seeks the wildcard's edges instead of scanning
them. Otherwise it drives a discovery walk from the first operand of its
static cost order (arrows to relations before permission references, ties in
source order: `program.rs`, `stream.rs` `drivers`).

Measured on eacl-rust 89be1e1 through its public API (release build, the
fixture of EACL-FORMAL-100, a subject owning 5 of N ledgers, uncached, median
of 9 listings):

| `open =` | 200 ledgers | 2,000 | 20,000 |
|---|---:|---:|---:|
| `view & subscribed` | 17 µs | 12 µs | 57 µs |
| `subscribed & view` | 115 µs | 1.18 ms | 18.1 ms |
| `view & subscription->everyone` | 112 µs | 0.98 ms | 7.7 ms |
| `subscription->everyone & view` | 100 µs | 1.04 ms | 8.2 ms |
| `view & subscriber` (wildcard relation) | 8 µs | 8 µs | 8 µs |
| `subscriber & view` | 7 µs | 8 µs | 8 µs |

The smallest `max-fetched-values` limit under which the listing answers is 1
in the flat rows and grows with N in the others (16, 128, 2,048 and 16, 256,
2,048). A count's grows with N in every arrow form (8, 64, 1,024; 9 µs, 65 µs
and about 1 ms) and is 1 for the wildcard relation. The wildcard-cover rule
would bound those walks there as well.

### D3. The mark is sealed where it holds

A cost entry carries `:wildcard-cover? true` only for a marked node, so the
`:costs` of a plan that reads no wildcard branch are byte-identical and its
fingerprint is unchanged. A marked node changes the fingerprint whether or
not an anchor moved; `validate-plan` rejects a plan without the mark.

### D4. Compatibility identity

`compiler-plan-compatibility` gains
`:intersection-anchor :wildcard-cover-last-v1`. Completed pages are keyed by
that identity, not by the plan fingerprint; without the new key a page
computed under the earlier anchor would be served with a cursor the new plan
refuses.

### D5. Reverse lookups

`wildcard-touch-route?` takes the touch cover only when the positive cover
holds a wildcard rule for the subject type and the wildcard reaches the
resource through it. A plan whose anchor moved off the wildcard operand has
no such rule and lists the anchor cover's subjects, each decided exactly.
`WildcardSubjects.dfy` proves that this listing is exact
(`AnchorListingDenotesExactly`, `DefiniteAnchorListingDenotesExactly`): the
wildcard holds nothing through a cover without a wildcard
(`CoverWithoutWildcardDeniesTheWildcard`), and every granted subject holds a
relationship of its own in it (`GrantedSubjectIsAnchored`).
`WildcardAnchorOmitsAGrantedSubject` is the witness that an anchor with a
wildcard cover does not have this property.

## Alternatives rejected

- **Cost a permission reference by its closure.** `subscribed` (one arrow)
  then looks cheaper than `view` (a union of relations and arrows), so
  structural depth and work alone still choose the wildcard operand; and it
  moves anchors of plans without wildcards, with no evidence that the new
  anchor is cheaper.
- **Choose by the snapshot** (edge counts of the subject, or a bounded probe
  of each operand, as eacl-rust's `ANCHOR_THRESHOLD` and `REL_DRIVER` do).
  It answers what a static rule cannot, but the choice then depends on data:
  it contradicts the non-observational requirement above, the cursor must
  carry the choice, and every plan has one cover per candidate anchor. It
  belongs with the structural set evaluators of
  `2026-10-01-backport-rust-engine-performance` (D5, D8), not with the sealed
  cover.

## Risks / Trade-offs

- **Declared, not stored.** A relation that declares `user:*` beside concrete
  subjects and holds few wildcard relationships is no longer the anchor when
  another operand has no wildcard cover. If that operand is large for the
  subject, the earlier anchor was cheaper. The new cost is that operand's own
  listing for the subject, bounded by the subject's relationships; the
  earlier cost was bounded by the platform's wildcard relationships.
- **Every operand reads a wildcard.** `subscriber & public` still enumerates
  one of them, chosen structurally.
- **Per relation, not per subject type.** A relation with `group:*` marks its
  node for lookups of users too.
- **Result order.** A plan whose anchor moved lists its results in the new
  generator's order.

## Migration Plan

No data migration. A cursor that 8.0.0-RC-2026-10-02 issued for an operator
plan that reads a wildcard branch is refused with
`:eacl.pagination/invalid-cursor` (`:operator-scope-mismatch`); clients
restart the walk. Cache snapshots of that release fail to restore with
`:eacl/incompatible-cache-snapshot`.

## Open Questions

- Selection by observed selectivity among operands on the same side of this
  rule (owner decision; see the rejected alternative).
- Whether a permission reference should cost what its closure costs, which
  would make the remaining ties independent of names.
