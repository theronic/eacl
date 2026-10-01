# EACL-FORMAL-095 — `;` statement terminators were a parse error

```
definition user {}
definition doc { relation viewer: user; permission view = viewer; }
```

SpiceDB v1.56.0 accepts this schema. EACL rejected it with a parse error at
line 2, column 39: the Instaparse grammar was line-oriented, so a relation
or permission ended only at a line break, and valid SpiceDB schemas that use
`;` could not be written.

The schema language is now lexed and parsed as SpiceDB's `schemadsl` does
(docs/spicedb-schema-compatibility.md). A statement ends at `;` or at a line
end after a name, keyword, `)`, `}` or `*`, where SpiceDB's lexer inserts a
synthetic semicolon. A top-level declaration takes at most one terminator
(`definition user {};;` is still a parse error, as in SpiceDB), a definition
body accepts empty statements, and the last statement before `}` needs a
terminator. The compatibility corpus records SpiceDB's verdict on each of
these forms. Found by the eacl-rust port (BUGS.md EACL-RS-001).

Reproduce with `EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay`,
or run `eacl.spicedb.compatibility-corpus-test/semicolon-terminators-test`.
