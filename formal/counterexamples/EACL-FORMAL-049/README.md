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

Both host fixtures now start at two adapter chunks and span fourfold sizes:
8,192–32,768 identities for JVM/Java and 32,768–131,072 for
JavaScript. The gates reject fixtures outside that multi-chunk domain. The JVM
gate starts in a fresh 1 GiB process and prints its complete result before any
failure. No threshold was relaxed; the one-million-identity gate is retained.
