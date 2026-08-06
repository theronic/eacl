# EACL-FORMAL-020 — nil sort key was a generic ordered-merge sentinel

The generic host specialization initialized `last-key` to `nil` and also
allowed callers to supply a key function whose legitimate result was `nil`.
The first such value was therefore treated as already emitted and silently
omitted.

The Dafny ordered merge already distinguishes `NoLast` from `Last(value)`.
The source now makes the same distinction with a separate `has-last?`
Boolean. The portable regression exercises both unique and duplicated nil
keys in CLJ and CLJS.
