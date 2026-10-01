# EACL-FORMAL-078 — maximum-entries did not bound validation work

`eacl.secure-format/validate-value` counted a collection's entries by
reducing every child first and compared the total with `:maximum-entries`
afterwards. A collection far larger than the bound was walked completely
before it was rejected. An unbounded lazy sequence was never rejected:
`encode-canonical`, `canonicalize`, `capture-portable` and the digests built
on them did not return. Decoding was not affected, because decoded input is
bounded by its size; the inputs at risk are values an application passes in.

Validation now carries one running count through the whole value and checks
it before each value is examined, so at most `:maximum-entries` + 1 values are
visited before `:too-many-entries`.

Reproduce through nREPL:

```clojure
(do
  (require 'eacl.secure-format-test :reload)
  (clojure.test/test-var
   #'eacl.secure-format-test/validation-work-is-bounded-by-maximum-entries-test))
```

Found by the eacl-rust port: `EACL-RS-103` (suspected, reproduced here) in its
`BUGS.md`.
