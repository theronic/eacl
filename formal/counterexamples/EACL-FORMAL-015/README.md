# EACL-FORMAL-015 — clear split recursive identity from flight identity

`resolve!` previously read the lifecycle before it entered the critical section
that selected an entry or installed a flight. `clear!` could advance the
lifecycle in between those operations.

That interleaving gave one logical resolution two addresses: its dynamic
recursive-self marker used the expired lifecycle, while its registered flight
used the new lifecycle. A same-key recursive call therefore did not recognize
the owning computation. It joined the new-lifecycle flight, which was its own
unresolved delay, and waited forever.

The corrected implementation linearizes lifecycle capture, recursive-self
detection, entry lookup, and flight selection under the same store lock.
Read-only lookup uses the same lifecycle-and-entry selection discipline.
The regression holds the store monitor, starts a resolver, clears re-entrantly,
and then releases selection; the recursive result must complete instead of
self-waiting.
