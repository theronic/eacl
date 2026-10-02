# EACL-FORMAL-079 — canonicalization merged members that render alike

The canonical codec encodes a record as its field map. That is intended: a
record and an equal-field map are the same portable value, and the identity
boundaries where the difference matters already reject records (cursor
identities, see `cache/cursor-cache-data?`) or admit only one representation
(object IDs).

One map or set could still hold both, because Clojure treats a record and a
map with the same fields as different values:

```clojure
(canonicalize {(->PortableRecord 1) :record {:a 1} :map})
;=> {{:a 1} :map}                       ; one entry silently dropped
(encode-canonical {(->PortableRecord 1) :record {:a 1} :map})
;=> "{{:a 1} :record, {:a 1} :map}"     ; decode-canonical rejects this
```

Both functions now reject such a collection with `:duplicate-key` or
`:duplicate-member`.

Reproduce through nREPL:

```clojure
(do
  (require 'eacl.secure-format-test :reload)
  (clojure.test/test-var
   #'eacl.secure-format-test/records-project-to-maps-and-colliding-members-are-rejected-test))
```

Found by the eacl-rust port: `EACL-RS-105` (suspected) in its `BUGS.md`. Its
literal claim, that a record and an equal-field map encode identically, is the
codec's documented projection rather than a defect; this entry records the
part that was one.
