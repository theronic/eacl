# EACL-FORMAL-097 — a keyword glued to a name was accepted

```
definition user {}
definition doc {
 relationviewer: user
}
```

SpiceDB v1.56.0 rejects this schema ("Expected end of statement or
definition, found: TokenTypeIdentifier"). EACL accepted it and stored a
relation `viewer` on `doc`: the Instaparse grammar matched keywords as string
literals with no word boundary, so `relationviewer` read as `relation viewer`.
`permissionview`, `definitionuser`, `caveatcav` and `withcav` behaved the
same way.

Names are now lexed as SpiceDB lexes them: a word is a maximal run of `_`,
letters and digits, and it is a keyword only when the whole word is one. A
keyword glued to a name is therefore one identifier, so each of those schemas
is a parse error, and `permission view = nilviewer` names an undefined
relation (`:eacl.schema/expression-resolution-failed`), all as in SpiceDB.
Found by the eacl-rust port (BUGS.md EACL-RS-003).

Reproduce with `EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay`,
or run `eacl.spicedb.compatibility-corpus-test/glued-keywords-test`.
