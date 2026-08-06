# EACL-FORMAL-053 — token consistency descriptors admitted unknown fields

The formal public-input model rejects malformed consistency descriptors, and
the strict runtime boundary documents unknown-field rejection. Production
nevertheless accepted a token descriptor with the required mode and token plus
any number of additional fields, because it checked only the required values.

The shared descriptor now requires exactly the two documented fields,
`:consistency/mode` and `:zed/token`. It uses map cardinality and membership
checks instead of allocating a set of all keys. Valid at-least-as-fresh and
at-exact-snapshot descriptors are unchanged; unknown fields fail with
`:eacl/unsupported-consistency` before token authentication or snapshot
selection.

`ConsistencyDecision.dfy` explicitly proves that its malformed public-input
class normalizes to rejection. The shared CLJC regression failed before the
source change and covers both token modes in Clojure and ClojureScript.
