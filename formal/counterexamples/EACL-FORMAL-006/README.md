# EACL-FORMAL-006 — canonical authentication depends on the host printer

The authenticated wire format called `pr-str` after sorting portable EDN.
That is deterministic within one runtime, but it is not a cross-runtime byte
contract. JVM Clojure prints a single-namespace map as
`#:subject{:id "u1"}`; ClojureScript prints `{:subject/id "u1"}`.

The different inner payload bytes produce different HMAC tags and tokens.
Portable cursors, cache envelopes, and causal tokens issued in one runtime can
therefore fail authentication in the other runtime. Validation fails closed,
so the minimized witness is an availability/interoperability defect rather
than a false grant.

The retained regression is
`eacl.secure-format-test/authenticated-cross-runtime-vectors-test`. Run it in
both the CLJ and CLJS test targets; both runtimes must match the same literal
cursor and cache vectors.
