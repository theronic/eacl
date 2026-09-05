# EACL 9.0 release notes

V9 enables Caveats and expiring Relationships across the shared authorization
engine. The v8 qualifier-EID prerequisite is landed separately. Follow the
[migration guide](migration-v7-to-v9.md) for storage and runtime compatibility.

Live `SecurityKeyring` controllers let running clients share externally supplied
primary or dedicated Zed-token key updates. Full replacements use generation
compare-and-set; add, activate, and retire operations preserve atomic state.
Key IDs cannot change material or revive after retirement. Static options still
construct private controllers. Key updates never rotate database lifecycle,
Relation/qualifier generations, or authorization proofs.

Unknown or retired caller-supplied keys return
`:eacl.pagination/invalid-cursor` or `:eacl/invalid-zed-token`, with reason
`:security-key-unavailable`. Cursor age expiry remains a separate error.
Authenticated optional cache inputs miss and recompute against the selected
snapshot. Every backend now exposes authenticated cache snapshot export/restore.

**Default cursors do not expire. Lossless resume requires indefinite old-key
retention.** A configured finite TTL applies only to new cursors. Distribute
inactive keys to every Peer before activation, then retain overlap before
retirement. The [security-key guide](security-keyrings.md) documents the public
API, external secret ownership, rollback, partial rollout recovery, and limits.

Datalevin's existing unpublished-artifact release guard remains in force; these
changes do not publish its embedded Maven dependency.
