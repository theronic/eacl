# EACL-FORMAL-091 — the codec ceiling pre-empted the typed expression byte limit

Schema admission bounds each permission's canonical payload by
`:maximum-expression-bytes`, 131,072 bytes by default, and reports
`:eacl.schema/expression-limit {:dimension :encoded-byte-size}`. The measurement
rendered the payload with `expression/encode`, which applies the canonical
codec's own ceilings first: 262,144 entries before rendering and 1 MiB after.
A permission whose arrows or relations resolve over many subject types passes
both of those only while it is under about 1 MiB, so with default limits a
12 KB schema — 256 subject types and `permission p = r->x + …` with 128
arrows — failed with `:eacl.format/invalid {:reason :too-large}`, and grouping
the arrows in pairs failed with `:too-many-entries`. The eacl-rust port's
360 KB `r + r + r` schema failed the same way. The expression comment that the
codec ceilings sit "deliberately above the calibrated admission policy" did
not hold.

`expression/encoded-byte-size` computes the exact UTF-8 size of the canonical
encoding from the closed v1 value domain without rendering it, so admission
compares the true size with the configured limit and reports it in `:actual`.
For every value the codec can render, the measured size equals the rendered
byte count. Found by the eacl-rust port (BUGS.md EACL-RS-107).

Reproduce with `EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay`,
or run `eacl.schema.expression-limits-test/encoded-byte-limit-precedes-codec-ceilings-test`.
