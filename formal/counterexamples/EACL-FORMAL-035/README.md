# EACL-FORMAL-035 — reflective generated-Java calls inflated query cost

The generated indexed state remained opaque between adapter round trips, so
the suspected whole-state conversion was not present. JFR instead showed that
the handwritten Clojure FFI boundary reflectively resolved generated Java
variant and destructor methods on the recursive traversal hot path.

Concrete generated-class type hints now make those calls statically
dispatched. On the minimized 64-document/16-group fixture, the measured p95
allocation premium fell from 3,677,688 to 343,576 bytes for a recursive page
and from 4,283,960 to 706,496 bytes for cursor continuation. Those figures are
host measurements, not formal memory bounds.

The five-trial representative gate uses 512 documents and 64 recursive groups.
It compares direct, acyclic, recursive, cursor, and hot-cache public operations
between legacy and verified authority. Exact public results are mandatory;
latency, caller-thread allocation, and backend operations are measured and
gated as separate dimensions.
