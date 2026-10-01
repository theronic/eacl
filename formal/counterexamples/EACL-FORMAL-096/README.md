# EACL-FORMAL-096 — wrapped permission expressions were a parse error

```
definition user {}
definition doc {
 relation viewer: user
 relation editor: user
 permission view = viewer +
   editor
}
```

SpiceDB v1.56.0 accepts this schema. EACL rejected it with a parse error at
line 5, column 28: the Instaparse grammar ended every statement at a line
break, so long expressions wrapped across lines, as SpiceDB schemas commonly
write them, could not be written.

EACL now applies SpiceDB's synthetic-semicolon rule: a line end ends a
statement only after a name, keyword, `)`, `}` or `*`. After `+`, `&`, `-`,
`|`, `->`, `:` or `=` the statement continues on the next line. A line that
starts with an operator is still a parse error (`viewer⏎+ editor`), and so is
an opening brace on the line after its definition name, both as in SpiceDB.
Found by the eacl-rust port (BUGS.md EACL-RS-002).

Reproduce with `EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay`,
or run `eacl.spicedb.compatibility-corpus-test/continuation-lines-test`.
