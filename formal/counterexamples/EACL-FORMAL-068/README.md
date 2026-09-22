# EACL-FORMAL-068 — host-equal public IDs crossed semantic identity boundaries

Manual review of the public operation matrix found that the formal models
started after external object IDs had already become typed semantic identities.
That abstraction omitted a security-relevant host-language fact: Clojure can
consider two different representations equal while a supported deterministic,
injective custom codec resolves them to different internal objects.

The minimized witness is a list and vector containing the same value. Before
the fix, ordered batch checks could reuse the first representation's decision
for the second, and relationship writes could coalesce the two unresolved
updates. The same audit found that public numeric deletion could implicitly
fall back to a native EID and that resolver failure was indistinguishable from
not-found.

`PublicIdentityBoundary.dfy` now makes representation, host equality, resolved
identity, memo eligibility, relationship coalescing order, and the two deletion
entry points explicit. The assurance matrix names batch checking, relationship
mutation, and object deletion as public operations. Executed mutants kill
canonicality removal and pre-resolution coalescing, while backend contract
tests cover the complete public paths.
