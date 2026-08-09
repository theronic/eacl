# EACL-FORMAL-049 — cursor scaling crossed an operation-shape boundary

The cursor-rebase theorem, semantic comparison, absolute allocation ceiling,
and one-million-identity recovery all passed. The normalized JVM allocation
ratio nevertheless failed after the consistency ClojureScript compiler ran.

The old fixture used 1,024, 4,096, and 16,384 identities while the JVM
generated-adapter chunk limit is 4,096. A successful tail lookup at the first
two sizes performs one generated successful decision. The largest lookup
performs three generated `:restarted` decisions before its successful fourth
chunk. Those are intentionally different operation shapes. Dividing their
allocations by identity count and comparing the endpoints does not isolate
asymptotic growth.

The exact CI ordering reproduced a `2.493x` normalized allocation ratio while
absolute work remained about 144 allocated bytes per identity and the
one-million-identity cases completed in about 114–115 ms. A fresh JVM happened
to pass the invalid fixture because its small first result allocated more.

The intermediate harness was corrected to compare one multi-chunk operation
shape. Final v8 later removed cursor rebase/restart from production, so this
gate is retired and retained only as historical counterexample evidence. It is
not an active performance or certification claim.
