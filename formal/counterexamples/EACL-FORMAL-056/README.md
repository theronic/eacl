# EACL-FORMAL-056 — relationship paging lost the generated kernel

The generated-only cutover removed the handwritten fallback and exposed a
host-orchestration defect. `execute-page` normalized the request with the
dynamic default kernel, but its later keyset-page decision received the
function's original `nil` argument. Direct DataScript and Datahike
implementation calls therefore failed at the strict generated boundary.

This was not a Dafny semantic error. Both pagination decisions were correct
when invoked. The defect was in Clojure wiring between two proved decisions,
which is why generated-kernel proofs alone could not find it. The ordinary
public client supplied a private kernel selection and hid the path; the direct
relationship query matrix exposed it after shadow fallback removal.

`execute-page` now resolves one generated selection at entry and uses it for
normalization and lookahead classification. The direct DataScript query matrix
and the corresponding Datahike relationship filter matrix retain the
regression.
