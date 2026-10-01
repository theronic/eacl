# EACL-FORMAL-092 — schema error precedence depended on position modulo 32

`transform-schema` extracted Caveats and definitions through
`(->> items (filter …) (map build) (reduce check-duplicates …))`. `map` is
lazy and realizes its chunked input 32 items at a time, so the first `reduce`
step built every declaration of the chunk — raising any declaration's own
error — before that chunk's duplicate checks ran. All Caveats were also built
before any definition. For `caveat c; caveat c; caveat d(invalid)` the error
was `:eacl.caveat/invalid` for `d`, but shifting the same three declarations
across a chunk boundary with leading definitions reported
`:eacl.schema/duplicate-caveat` instead. Duplicate definitions and a
duplicate relation inside a later definition behaved the same way.

Declarations are now read once, in source order. Each one is built and checked
against the earlier ones before the next is read, so the first failing
declaration determines the error whatever its kind or position. Within one
declaration, its own errors still precede its duplicate-name check. The
regression shifts each sequence across the first and second chunk boundaries.
Found by the eacl-rust port (BUGS.md EACL-RS-108).

Reproduce with `EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay`,
or run `eacl.schema.expression-resolver-test/declaration-errors-follow-source-order-test`.
