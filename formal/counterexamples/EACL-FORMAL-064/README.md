# EACL-FORMAL-064 — default DataScript cursor proof scanned the graph

A small demand page should perform work proportional to the requested page and
its lookahead. Instead, cursor construction supplied the compiled relationship
closure in every proof mode. DataScript's default content proof hashed all
matching forward and reverse relationship records, so `:cache? false` did not
avoid the dominant graph-linear work. On the Explorer 10k fixture, the first
page took hundreds of milliseconds while the exact-snapshot proof path took a
few milliseconds.

The corrected strategy uses the supported-writer ordered-generation proof
frame. Cursor minting reads the complete dependency relation generations and
stores their scalar frontier; it never scans relationship content. An
unrelated later transaction preserves that proof, while a relevant relation
write advances the frontier and rejects the cursor. This remains safe and
keeps cursor proof identity constant-size.
