# EACL-FORMAL-029 — the counterexample ledger schema was not enforced

The corpus test checked that every required field existed, but it did not
validate field values against `ledger-schema.edn`. Nine values in six retained
entries had drifted outside the committed discovery and impact taxonomies.
The underlying minimized fixtures and fixes were unaffected, but the
machine-readable evidence ledger was not actually guaranteed to satisfy its
declared schema.

The corpus replay now interprets the schema's keyword, string, integer,
relative-path, enumeration, union, and vector forms and validates every
required and present optional value. Existing entries were normalized to the
declared taxonomy; the schema was not expanded to bless accidental ad hoc
keywords.
