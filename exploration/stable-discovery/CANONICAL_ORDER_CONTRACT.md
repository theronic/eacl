# Compositional stable-order contract

Status: exploration source-refinement requirement. This removes runtime
tie-break sorting from the minimum architecture.

## Principle

The semantic reducer consumes preordered successor vectors. It never compares
canonical bytes, sorts a dynamic batch, merges globally ordered streams, or
uses host collection iteration as an order source.

Stable order is constructed once at each producer boundary and then preserved
compositionally:

1. Portable canonical rule tuples are sorted once during plan construction.
   Their positions become unique dense canonical rule ordinals within that
   sealed plan.
2. The verified read-distance certificate assigns a static read rank. Seed,
   forward-consumer, and reverse-rule vectors are built once in
   `(read-rank, canonical-rule-ordinal)` order and sealed.
3. A linear plan validator checks unique canonical ordinals, exact vector
   membership, nondecreasing rank, and increasing ordinal within a rank.
4. Backend scan values retain the adapter's certified stable index order at
   the exact basis. Every value in one scan occurrence has the same static
   rule and rank. Physical chunks flatten to that order, but eagerly admitting
   a whole chunk can still change first-discovery order under overlap.
5. The reducer consumes exactly one logical scan value per transition.
   Value-derived work precedes the residual occurrence at its exact logical
   exclusive bound. A bounded physical chunk is a detachable request-side
   accelerator: changing its width, dropping it, or refilling it cannot alter
   the logical release sequence. The right-edge stack appends the abstract
   one-value sequence in reverse.
6. A reverse goal consumes its sealed rules-by-head vector in exact static
   order; predecessor goals and base subjects retain scan order.
7. Exact admission retains the first occurrence in the producer sequence.
   It removes duplicates but never permutes fresh work.
8. Read-ahead completion cannot affect the sequence because only the canonical
   reducer head integrates.
9. Cache residency cannot affect the sequence. A projection hit returns the
   exact ordered successor response for the same equality-complete physical
   occurrence and still passes through request-local admission. A flat
   subproblem denotation is never an enumeration producer.

Canonical bytes remain necessary for portable schema normalization, sealed
plan serialization, and the fingerprint. They are not a hot-path comparison
key. The public order ABI binds the normalization version, rank/certificate
version, static-vector construction version, scan-order ABI, one-value reducer
phase table, and plan fingerprint. It does not bind physical fetch width or
sidecar capacity/cache residency.

## Why this is simpler and faster

Runtime canonical-byte sorting would allocate encodings or comparison keys,
repeat work already done by the compiler, and introduce a second ordering
authority beside producer order. Stable sort would also make correctness
depend on the pre-sort input sequence it supposedly replaces.

The compositional contract reduces runtime scheduling to vector `peek`, `pop`,
indexed access, and append. Cost ordering remains where it matters: between
static alternative rules. Dynamic values from one rule have equal static
cost, so their certified backend order is the exact tie order required.

## Required source evidence

The independent compiler/source bridge must establish:

- canonical rule tuples and dense ordinals are identical in Clojure and
  ClojureScript;
- every direction index is the exact expected rule set in exact
  `(rank, ordinal)` order;
- map/set insertion permutations produce identical sealed vectors and plan
  fingerprints;
- every adapter returns identical flattened scan sequences across physical
  chunk sizes and deterministic replay at one basis;
- one-value normalization produces the identical admission trace for every
  qualified physical width and after arbitrary accelerator dematerialization;
  eager variable-width integration and resuming from the physical fetched-end
  instead of the logical bound fail focused overlap/skip controls;
- scan response integration, reverse static-rule cursors, and right-edge stack
  operations preserve their producer sequences;
- worker completion permutations do not change public pages;
- cold execution, ordered projection hits, and projection-cache residency
  permutations produce identical reducer traces and public pages;
- no runtime enumeration path calls sort, canonical encoding, or a comparator.

Required mutants use host map iteration for plan ties, swap equal-rank rule
ordinals, omit a direction-vector member, reorder a scan chunk, put the
physical residual before current values, reverse a static rule vector, append stack
successors without reversing them, and integrate a noncanonical completed
read. Cache mutants reorder an otherwise equal projection, inject a complete
fresh subtree denotation under an overlapping admission set, or bypass normal
admission on a cache hit. Each must fail a focused test.

## Qualification boundary

This contract proves determinism only when the adapter's scan-order ABI is
certified at an immutable basis. If a backend cannot provide stable index
order and exact continuation, that adapter is ineligible for stable discovery;
sorting only each returned chunk cannot repair a globally unstable scan.
