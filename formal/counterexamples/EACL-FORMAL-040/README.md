# EACL-FORMAL-040 — the abstract merge omitted production control state

The earlier `OrderedMerge.dfy` proved the canonical sorted union and an
abstract pairwise balanced fold. Production carries more control state:
`has-last?`, a separate last value, `drop-while` when one input is exhausted,
empty-stream filtering before every fold round, and concrete adjacent pairing.

Those details are redundant for finite strictly ordered EID streams, but that
was previously an informal inference supported only by output differentials.
An error in one of the omitted branches could therefore remain outside the
theorem statement while the abstract theorem continued to pass.

The Dafny model now mirrors the production recursion and fold schedule. It
proves that the exact source recursion equals the canonical ascending or
descending merge, that filtered balanced folds remain strictly ordered and
contain the complete union, that strict order plus set equality determines the
exact sequence and hence the source fold equals the canonical fold, and that
one two-stream merge performs at most `|left| + |right|` comparison iterations.
Generated Java and JavaScript execute
the source model against the actual CLJ/CLJS implementations over every pair
of subsets of a six-value safe-natural domain in both directions, plus
multi-stream folds containing empty streams.
