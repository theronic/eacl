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

The body is now lexed by the schema lexer, as SpiceDB v1.56.0 lexes it, and
the stored source is the CEL expression SpiceDB compiles: the text from the
body's first token to its last. A line end after the expression's last name,
literal, `)`, `}` or `*` is a token (SpiceDB's synthetic semicolon), so it is
part of the expression. Comments before the first token or after the last are
outside the expression whatever they contain: all six bodies of the fixture
are admitted with source `x == 1`. A comment inside the expression is CEL's:
`//` comments are accepted, `/* */` comments are rejected with
`:eacl.caveat/invalid :syntax-error`, as SpiceDB rejects them. That includes
`{\n x == 1 /* c */\n}`, where the line end after the comment ends the
expression. A comment before the opening brace is schema whitespace. Error
source spans cover the stored expression.

The first fix for this entry stored the text between the braces verbatim and
rejected every block comment; the SpiceDB v1.56.0 container showed that
SpiceDB accepts block comments outside the expression, so the stored source
now follows SpiceDB's rule. The compatibility corpus
(`modules/eacl/test/eacl/spicedb/fixtures/spicedb-1.56-corpus.edn`, section
`caveat-comments`) records SpiceDB's verdict on each position. Found by the
eacl-rust port (BUGS.md EACL-RS-109).

Reproduce with `EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay`,
or run `eacl.caveats.definition-test/caveat-source-is-the-spicedb-cel-expression`.
