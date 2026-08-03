# Counterexample corpus

Every model-checker violation or differential mismatch is minimized before the
implementation or formal model is changed. Store each witness in a directory
named `<bug-id>/` with:

- `entry.edn` — bug-ledger record conforming to `ledger-schema.edn`;
- `fixture.edn` — smallest schema, graph, request, and state trace;
- `expected.edn` — semantic result or invariant violation;
- `README.md` — human impact and reproduction command.

Security-significant witnesses must remain permanently reproducible even after
the affected legacy path is removed. Do not store credentials, production
object identifiers, or unredacted traffic.
