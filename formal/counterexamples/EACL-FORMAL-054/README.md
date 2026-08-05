# EACL-FORMAL-054 — immutable DataScript adapter claimed an authoritative head

DataScript's snapshot adapter advertised `:fully-consistent` even when it had
no live connection. In that configuration `:select-authoritative` could only
return the immutable database value captured by the adapter, so a caller could
request an authoritative-head read and silently receive an older snapshot.

The adapter now removes `:fully-consistent` from its capabilities when `:conn`
is absent. It still advertises `:minimize-latency`, because reading the supplied
immutable snapshot is exactly that mode's contract. Managed clients retain
`:fully-consistent`: they supply a connection, and authoritative selection
captures its current database value.

The regression failed before the capability change and now matches the Datomic
and Datahike rule that an adapter must not advertise a head-selection guarantee
without the backend state needed to discharge it.
