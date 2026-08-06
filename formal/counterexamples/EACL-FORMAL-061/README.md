# EACL-FORMAL-061 — DataScript and Datahike dropped continuation state

The shared v8 engine already accepted a private continuation context and
Datomic provided one. DataScript and Datahike called only the two-argument
list functions, disconnecting the cache from the executable path. Tokens still
authenticated the result boundary, so pages were correct, but every adjacent
page reconstructed traversal state from indexed scans.

The fix moves the continuation contract into shared core and gives every
client its own bounded store. Its key commits to backend, source, adapter and
identity contracts, normalized schema proof, snapshot, operation, and query
identity. The generated continuation decision permits resume only for a valid
matching private entry; a miss or eviction replays the authenticated public
boundary and returns the same page.

Regression tests require continuation hits after page one, flat logical page
work, cross-client isolation, safe eviction replay, and no private traversal
payload in decoded public cursors.
