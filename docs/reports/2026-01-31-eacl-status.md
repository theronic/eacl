# EACL Status Report - 2026-01-31

## Current State

EACL is a ReBAC authorization library for Clojure backed by Datomic. It currently supports defining `Relation` and `Permission` entities directly in Datomic. The goal is to support SpiceDB's schema DSL for defining these entities via `eacl/write-schema!`.

### Codebase Analysis

- **`src/eacl/datomic/schema.clj`**:
    - Defines the Datomic schema for `Relation` and `Permission`.
    - Contains a stub `write-schema!` function that currently throws an exception.
    - Has `read-schema`, `compare-schema`, and `calc-set-deltas` functions which are partially implemented or ready to be used for diffing.
    - `read-schema` returns a map `{:relations [...], :permissions [...]}`.

- **`src/eacl/datomic/spice_parser.clj`**:
    - Uses `instaparse` to parse SpiceDB schema DSL.
    - `spicedb-parser` grammar seems mostly complete for basic definitions, relations, and permissions (union and arrow).
    - `parse-schema` returns a parse tree.
    - `transform-schema` and extraction functions exist but `->eacl-schema` is a WIP stub.
    - Needs to convert the parse tree into EACL's internal map representation (compatible with `schema/read-schema` output) or directly to Datomic transaction data.

- **`src/eacl/datomic/core.clj`**:
    - Implements `IAuthorization` protocol.
    - `write-schema!` throws "not impl.".

- **`test/eacl/datomic/parser_test.clj`**:
    - Tests for the parser.
    - Verifies that the parser can handle basic schema strings.

### Implementation Gaps

1.  **Parser Completion**: `eacl.datomic.spice-parser/->eacl-schema` needs to be implemented to convert the parsed tree into EACL's internal data structures (`Relation` and `Permission` maps/vectors).
2.  **Schema Writing Logic**: `eacl.datomic.schema/write-schema!` needs to be fully implemented to:
    -   Accept a schema string.
    -   Parse it using `spice-parser`.
    -   Read the current schema from the DB.
    -   Calculate the diff (additions, retractions).
    -   Validate retractions (check for orphaned relationships).
    -   Transact the changes.
    -   Store the schema string.
3.  **Validation**: Need to ensure that the new schema is valid (e.g., arrows refer to existing relations/permissions).
4.  **Integration**: `eacl.datomic.core` needs to use the implemented `schema/write-schema!`.

### Bugs / Issues Spotted

-   `src/eacl/datomic/schema.clj`: `write-schema!` has a `TODO` to throw for invalid Relation not present in schema in `count-relationships-using-relation`.
-   `src/eacl/datomic/spice_parser.clj`: `extract-permissions` comment asks "why was this (second perm-name)?". The parser structure needs to be carefully checked against the grammar.
-   `src/eacl/datomic/impl/indexed.clj`: `find-permission-defs` uses `d/datoms` with `:avet` which is good, but we need to ensure all indices are correctly set up in `schema.clj`.

## Plan

The plan will focus on:
1.  Finishing the `spice-parser` to produce EACL schema maps.
2.  Implementing the diffing and validation logic in `schema.clj`.
3.  Connecting everything in `write-schema!`.
4.  Adding comprehensive tests.
