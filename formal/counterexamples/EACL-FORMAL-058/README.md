# EACL-FORMAL-058 — replay entrypoint loaded a removed production namespace

The generated-only cleanup moved `eacl.lazy-merge-sort` from production to the
formal smoke source tree. The ordinary CI parity nREPL deliberately excludes
that tree, but `bin/formal counterexample-replay` still eagerly required the
namespace. Corpus replay therefore stopped before running any regression.

This was an assurance-workflow availability defect, not an authorization
defect. Local replay through a broader `:formal-smoke` classpath masked it.
That classpath difference is why a second, isolated CI configuration remains
useful even after a full local suite passes.

The entrypoint no longer eagerly loads any of the retained test-only former
engine namespaces. Individual regressions already resolve their evidence
namespaces lazily and skip an oracle that is not on the selected classpath.
The exact ordinary CI alias set now runs 42 tests with 2,923 assertions
successfully, while the formal-smoke classpath replays the complete corpus.
