# EACL-FORMAL-090 — a Caveat could be admitted and then fail every evaluation

`m.name` in the EACL CEL profile indexes the map `m` with the string literal
`"name"`. The parser admitted quoted string literals against the profile's
4096-byte string bound, but it built the member form's literal without that
check, and identifiers have no length limit of their own. Every evaluation
re-validates the stored plan with `validate-plan`, which rejects the over-long
literal. `write-schema!` therefore accepted

```
caveat longfield(m map<bool>) {
 m.aaaa…(4100 × a)… == true
}
```

and a relationship using it could be written, but every check through it then
failed with `:eacl.authorization/evaluation-failure
{:faults [[:eacl.caveat/evaluation :resource-limit]]}`. The quoted spelling
`m["aaaa…"]` was rejected at admission.

An admitted schema must execute. The member name now goes through the same
admission as a quoted literal, at the member's offset, so the schema write
fails with `:eacl.caveat/invalid :reason :resource-limit`. A member at the
bound compiles, passes `validate-plan`, and evaluates.

The finite Caveat oracle (`formal/caveats/model.clj`) already typed such a
literal as invalid (`plan-type` requires `valid-text?`); the production parser
diverged from it. Found by the eacl-rust port (BUGS.md EACL-RS-102).

Reproduce with `EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay`,
or run `eacl.caveats.plan-test/member-literals-are-admitted-like-string-literals`.
