# EACL-FORMAL-015 — clear split recursive identity from flight identity

`resolve!` previously read the lifecycle before it entered the critical section
that selected an entry or installed a flight. `clear!` could advance the
lifecycle in between those operations.

That interleaving gave one logical resolution two addresses: its dynamic
recursive-self marker used the expired lifecycle, while its registered flight
used the new lifecycle. A same-key recursive call therefore did not recognize
the owning computation. It joined the new-lifecycle flight, which was its own
unresolved delay, and waited forever.

The final v8 simplification has no flight selection and no store monitor.
Independent work captures one opaque lifecycle; `clear!` replaces it, and
bounded compare-and-set publication from the detached lifecycle is rejected.
The old split flight identity and self-wait interleaving no longer exist.
