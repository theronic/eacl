# EACL-FORMAL-077 — page-request errors echoed the decrypted cursor

`lookup-resources` and `lookup-subjects` validated page keys only inside the
engine. By then `relay/prepare-page-query` had authenticated each `:after` and
`:before` cursor and replaced it with its decrypted edge. A request such as
`{:first 1 :before cursor}` therefore failed with
`:eacl.pagination/invalid-page-request` whose data held the plaintext edge:
internal coordinates, plan fingerprints and, on operator routes, cover
fingerprints and semantic scope. Cursors are encrypted so that this stays
private. The error also stopped being a function of the request.

The same late placement let a lookup whose subject or resource does not
resolve return an empty page before its page keys were checked, so
`{:first 0}` was accepted for an unknown anchor.

The public lookups now validate the page keys of the caller's query, with the
same generated page decision, before snapshot selection and cursor decoding.
Errors echo the caller's cursor strings. `PageWindow.dfy` proves that
normalization depends only on whether each boundary is absent, nil or present,
so the engine's later check of the decoded query reaches the same decision.

Reproduce through nREPL:

```clojure
(do
  (require 'eacl.datascript.contract-test :reload)
  (clojure.test/test-var
   #'eacl.datascript.contract-test/page-request-errors-echo-the-callers-cursor-strings-test))
```

Found by the eacl-rust differential campaign: `EACL-RS-006` in its `BUGS.md`.
The unknown-anchor case is its known defect 06-B1.
