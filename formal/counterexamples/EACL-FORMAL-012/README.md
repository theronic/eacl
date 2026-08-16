# EACL-FORMAL-012 — scan response was not bound to its traversal

Generated traversal request IDs start at zero for each independent state.
Before this fix, a response echoed only that local ID. A response produced for
another traversal—even one evaluating the same projection—could therefore be
accepted when its values also happened to satisfy the pending command.

Each live traversal now has a globally unique safe-natural request scope.
Responses echo that scalar and the local request ID. Dafny validates both
before inspecting response values, and both generated runtimes expose the
typed `:mismatched-request-scope` rejection. The complete projection remains
owned by pending generated state and does not cross the response boundary.

This closes command-scope confusion without the allocation and conversion
cost of echoing a complete projection for every chunk. It does not claim that
a host-echoed token can prove which database the adapter actually scanned.
Production adapters execute each generated command synchronously against the
selected immutable database value; that execution and scan completeness
remain explicit trusted adapter obligations.
