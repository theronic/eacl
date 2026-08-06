# EACL-FORMAL-001 — benchmark schema initialization order

The Datomic heavy pagination suite could not exercise traversal, cursor, or
cache behavior because both reusable seeders called `make-client` against an
empty database. Client construction initializes the mutation journal, which
stores its graph entity through `:eacl/id`; the attribute was only installed
after the call.

This is a benchmark-harness availability defect, not evidence of a false grant,
false denial, or backend correctness failure.

Reproduce the focused regression through the running project nREPL:

```clojure
(do
  (require 'eacl.bench.pagination-test :reload)
  (clojure.test/test-var
   #'eacl.bench.pagination-test/benchmark-seeders-initialize-empty-database-test))
```

The original suite-level witness was seven errors with
`:db.error/not-an-entity` and `Unable to resolve entity: :eacl/id`.

Fixed by installing `schema/v7-schema` before client construction in both
seeders. The focused regression now passes from a genuinely empty database.
