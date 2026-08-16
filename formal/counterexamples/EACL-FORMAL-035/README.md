# EACL-FORMAL-035 — reflective generated-Java calls inflated query cost

The generated indexed state remained opaque between adapter round trips, so
the suspected whole-state conversion was not present. JFR instead showed that
the handwritten Clojure FFI boundary reflectively resolved generated Java
variant and destructor methods on the recursive traversal hot path. A
complete compile-time audit found the same defect class elsewhere in the
generated-Java boundary.

Concrete generated-class type hints now make every generated-Java boundary
call statically dispatched. On the minimized 64-document/16-group fixture, the
measured p95 allocation premium fell from 3,677,688 to 343,576 bytes for a
recursive page and from 4,283,960 to 706,496 bytes for cursor continuation.
Those figures are host measurements, not formal memory bounds.

The generated-only heavy suite retains direct, acyclic, recursive, cursor,
cache, latency, caller-thread allocation, and backend-operation gates. A
separate generated-only post-full-GC fixture measures retained live heap.
The obsolete runtime engine comparison was removed with the v8 fallback
engine; its measurements remain historical evidence, not a runnable v8 mode.
