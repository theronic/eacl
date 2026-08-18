# fix-datomic-request-overhead

Remove per-request overheads found in the 2026-08-18 review: sealed-plan cache thrash (random lifecycle), seal-plan re-encoding, per-request read-schema, kernel? satisfies?, adapter construction
