# EACL-FORMAL-018 — flight removal escaped the selection lock

The cache selected lifecycle, recursive-self identity, represented entry, and
coordinator flight under the store lock. Flight installation and lifecycle
replacement used that lock too, but the compute delay's `finally` removed the
flight directly from the coordinator atom.

That race did not corrupt an authorization value: a selector that observed the
flight retained its immutable delay even if the registry entry disappeared.
It did invalidate the stronger proof/refinement statement that the complete
lookup state was observed at one linearization point.

Completion removal now acquires the store lock. The regression holds that lock
while a callback returns and proves the registered flight remains observable
until selection releases the lock, after which completion removes it.
