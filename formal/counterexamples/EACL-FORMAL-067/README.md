# EACL-FORMAL-067 — speculative scan waves changed recursive page order

The private SpiceDB differential campaign exposed an EACL failure before the
equivalent SpiceDB assertion ran: generated seed 5 drained a recursive resource
lookup with page sizes `[1 2 4]`, then EACL rejected its own continuation
because a one-page replay placed another resource at the authenticated ordinal.
The complete authorization set was correct, but the public order was not stable
across page sizes.

The indexed evaluator could issue several independent backend scans before
folding their responses. A short page reached its lookahead boundary while
that speculative wave was outstanding; a larger page folded the wave at a
later FIFO position. Thus requested page size accidentally became a traversal
scheduling input.

`IndexedBatching.RenderScanBatchSize` now makes scheduling part of generated
executable authority. `RenderPage` always admits one outstanding scan,
independent of requested size; Boolean and count renders remain batched at 64.
The generated production driver no longer accepts a host-supplied batch size.
Generated JVM/JavaScript tests cover the entry point, a mutation control guards
the routing boundary, and the reduced public Datomic fixture checks cached,
cacheless, and portable continuation against each implementation's one-page
logical order.
