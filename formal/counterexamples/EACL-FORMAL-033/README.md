# EACL-FORMAL-033 — exact locator was mistaken for graph identity

The first complete-public shadow trace compared two DataScript clients reading
the same immutable graph. Every authorization result, cache provenance field,
snapshot identity, source scope, graph anchor, and order hint agreed, but the
comparison still reported `:selected-graph` divergence.

Each DataScript client owns an exact-snapshot registry and mints its own opaque
locator for the same database value. That locator is a reconstruction
capability, not graph identity. Graph comparison now excludes the locator while
the independent exact-selection boundary continues to prove that resolving a
locator must return the authenticated graph anchor.

Replay through the formal nREPL suite:

```text
EACL_NREPL_PORT=<port> bin/formal counterexample-replay
```
