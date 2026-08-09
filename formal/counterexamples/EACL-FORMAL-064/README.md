# EACL-FORMAL-064 — default DataScript cursor proof scanned the graph

A small demand page should perform work proportional to the requested page and
its lookahead. Instead, cursor construction supplied the compiled relationship
closure in every proof mode. DataScript's default content proof hashed all
matching forward and reverse relationship records, so `:cache? false` did not
avoid the dominant graph-linear work. On the Explorer 10k fixture, the first
page took hundreds of milliseconds while the exact-snapshot proof path took a
few milliseconds.

The corrected strategy reserves dependency-scoped cursor proofs for explicit
managed mutation-stamp mode, where the writer contract maintains bounded
stamps. Content and no-proof modes bind the exact selected immutable snapshot
identity and issue no relationship-proof command. Because DataScript has no
historical selection, any later basis is stale in those modes. This is both
safer and cheaper: it cannot mix pages across bases and cannot scan the graph
merely to mint a cursor.
