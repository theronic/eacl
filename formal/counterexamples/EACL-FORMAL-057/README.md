# EACL-FORMAL-057 — stale page-window FFI shape

The v8 API cleanup removed the deprecated `:limit` and `:cursor` fields from
the generated `RawPageRequest` datatype, but the Clojure-to-Java formal smoke
bridge still supplied two Boolean presence flags for them. The bridge
therefore failed to load after rebuilding the generated Java kernel.

This was not a production authorization defect or a Dafny semantic defect. It
was an assurance-harness/source-refinement defect: the runtime bridge no
longer represented the generated datatype it claimed to test. Dafny
verification cannot detect a stale handwritten FFI caller.

The bridge now constructs the current four-field datatype. Deprecated public
pagination keys remain rejected by the public host-boundary characterization
and generated input campaigns; they are not part of the v8 Dafny datatype.
The complete CLJ generated-runtime bridge suite loads the rebuilt target and
passes 47 tests with 15,628 assertions.
