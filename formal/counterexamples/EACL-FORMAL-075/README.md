# EACL-FORMAL-075 — nested relationship updates bypassed the shared wrapper

The generic plural mutation wrappers checked that `:updates` was sequential but
did not validate each update before invoking a protocol implementation. A
misspelled nested expiry such as `:valid-until-mss` could therefore reach a
remote writer. If that writer ignored unknown fields, it could persist a grant
without the intended deadline.

Bundled backends already repeated this validation and failed closed. The
exploitable case requires a remote or third-party writer extension plus an
application that forwards attacker-controlled relationship mutation payloads;
the typo itself is also incorrect API usage.

The shared wrapper now validates every nested update before dispatch, including
single preparation and speculative planning paths. The Dafny model represents
malformed contents separately from a valid sequential batch, and an executed
mutation proves the production guard is observable.
