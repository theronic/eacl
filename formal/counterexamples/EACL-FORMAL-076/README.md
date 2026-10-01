# EACL-FORMAL-076 — issued tokens and cursors had several accepted spellings

EACL encodes Zed tokens, cache entries and cursors as canonical EDN inside
Base64URL. Both decoding layers accepted more than one spelling, and
authentication covers the decoded values, so an issued string could be
respelled and still authenticate. The payload could not be forged, but a
token or cursor string was not unique: anything that keyed, logged, or
compared them by string could be bypassed.

**Canonical EDN.** `eacl.secure-format/decode-canonical` wrapped its input as
`"[" + input + "]"` and read one form. An early `]` ended that wrapper, so the
reader never saw the rest of the input, even when it was unbalanced:

```clojure
(decode-canonical "{:allowed 1}] [{:forged 2}") ;=> {:allowed 1}
(decode-canonical "{:allowed 1}] )))(((")       ;=> {:allowed 1}
```

It also accepted any other text the host reader read into an admissible
value: commas, extra whitespace, another member order, list syntax, `1N`,
`+1`, `010`, `0x10`, `\u` escapes, and metadata. A character literal outside a
string (`\"`) looked like a string delimiter to the hidden-input scanner,
which then let a following `#_` discard, `;` comment, or `#:ns{}` map through.
Appending `] TAMPERED (((` inside a Zed token's envelope gave a token that
still authenticated.

**Base64URL.** `eacl.secure-format/b64url-decode` passed its input to the host
decoder. The JVM URL decoder accepts `=` padding and ignores the unused low
bits of the last character, so `AA`, `AA==`, `AB` and `AP` all decode to the
byte `0`; JavaScript's `atob` also discards whitespace. Every Base64URL layer
had this slack, including the tag segment of `eacl_c7_` cursors, which the
eacl-rust report recorded as unaffected.

Both layers now accept only the canonical spelling: the EDN input must equal
the canonical rendering of its value, and Base64URL must be the unpadded
spelling `b64url-encode` emits. The hidden-input scanner also rejects metadata
and character literals outside strings.

Reproduce through nREPL:

```clojure
(do
  (require 'eacl.datascript.contract-test :reload)
  (clojure.test/test-var
   #'eacl.datascript.contract-test/zed-tokens-accept-only-their-issued-spelling-test))
```

Found by the eacl-rust port (`EACL-RS-101`, verified; `EACL-RS-104`,
suspected and reproduced here, in its `BUGS.md`). The Base64URL respelling was
found while building the every-bit tamper regression for this entry.
