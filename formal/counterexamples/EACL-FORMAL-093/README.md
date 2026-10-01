# EACL-FORMAL-093 — a Caveat's stored source depended on its comment's contents

The schema grammar parsed a Caveat body as `'{' caveat-source '}'`.
Instaparse inserts its comment-aware auto-whitespace before every terminal,
including before `caveat-source`, and keeps the first parse that succeeds.
A leading block comment was therefore kept in the stored source when the body
parsed that way, and the CEL profile then rejected it (`/` is not a profile
operator). When the comment contained `}`, the body expression stopped at that
brace, the parse failed, and Instaparse retried with the comment consumed as
schema whitespace: `caveat c(x int) { /* } */ x == 1 }` was admitted with
source `" x == 1 "`, while `{ /* c */ x == 1 }` was rejected and
`{ x == 1 /* } */ }` was a parse error. Admission, the stored source and the
Caveat's identity all depended on what the comment said.

The body is now one terminal from `{` through its closing `}`, so no
whitespace slot exists inside it and the stored source is always the exact
text between the braces, comments included. The body ends at the first `}`
outside a string literal, a `//` line comment or a `/* */` block comment.
`//` comments remain part of the profile; block comments do not, so a body
containing one is rejected with `:eacl.caveat/invalid :unsupported-operation`
wherever it appears and whatever it contains. A comment before the opening
brace is still schema whitespace. Error source spans cover exactly the text
between the braces.

SpiceDB v1.56.0 accepts block comments in Caveat bodies and stores normalized
CEL rather than source text; accepting them would be a separate extension of
the EACL CEL profile. Found by the eacl-rust port (BUGS.md EACL-RS-109).

Reproduce with `EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay`,
or run `eacl.caveats.definition-test/caveat-source-is-the-verbatim-body-text`.
