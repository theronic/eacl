# Plan: Implement SpiceDB Schema Support

**Status**: In Progress
**Date**: 2026-01-31
**Report**: [2026-01-31-eacl-status.md](../reports/2026-01-31-eacl-status.md)

## Goal
Implement `eacl/write-schema!` to support SpiceDB schema DSL strings, enabling schema definition, validation, and safe updates.

## Steps

### 1. Parser Implementation
- [x] Update `src/eacl/datomic/spice_parser.clj`:
    - [x] Implement `->eacl-schema` to transform the parse tree into a map of `{:relations [...] :permissions [...]}`.
    - [x] Ensure the output format matches what `eacl.datomic.schema/read-schema` produces (or is compatible for comparison).
    - [x] Handle all supported SpiceDB constructs (definitions, relations, direct permissions, arrow permissions, unions).
    - [x] Add tests in `test/eacl/datomic/parser_test.clj` to verify transformation.

### 2. Schema Logic & Validation
- [x] Update `src/eacl/datomic/schema.clj`:
    - [x] Review `compare-schema` to ensure it correctly identifies additions and retractions.
    - [x] Implement `validate-schema!` to check for:
        - [x] Invalid references (permissions referring to non-existent relations/permissions).
        - [x] Unsupported features (if any remain in the parser).
    - [x] Implement `check-orphans` logic in `write-schema!` (or a helper) to prevent deleting relations that are in use.

### 3. Write Schema Implementation
- [x] Implement `eacl.datomic.schema/write-schema!`:
    - [x] Parse the input string using `spice-parser/parse-schema` and `spice-parser/->eacl-schema`.
    - [x] Read existing schema using `read-schema`.
    - [x] Compute deltas using `compare-schema`.
    - [x] Perform validation (orphans, valid references).
    - [x] Construct transaction data for additions and retractions.
    - [x] Add the schema string to the transaction (entity with `:eacl/id "schema-string"`).
    - [x] Transact to Datomic.

### 4. Core Integration
- [x] Update `src/eacl/datomic/core.clj`:
    - [x] Update `write-schema!` implementation to call `eacl.datomic.schema/write-schema!`.
    - [x] Implement `read-schema` to retrieve the stored schema string (or reconstruct it if missing).

### 5. Testing & Verification
- [x] Create `test/eacl/datomic/schema_test.clj` (or update if exists):
    - [x] Test full lifecycle: write schema -> read schema.
    - [x] Test schema updates (add/remove relations/permissions).
    - [x] Test orphan protection (try to delete a used relation).
    - [x] Test invalid schema rejection.
- [x] Run all tests to ensure no regressions.

## Notes
- Use `clojure-mcp` tools for all edits.
- Evaluate code in REPL frequently.
- Follow the ADR guidelines.
