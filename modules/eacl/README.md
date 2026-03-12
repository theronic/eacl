# `eacl`

Core, backend-neutral EACL module.

Responsibilities:

- `eacl.core` protocol and public records
- cursor/token helpers and consistency semantics
- schema IR, parser, validation, and diffing
- backend SPI and shared authorization engine
- contract test fixtures and backend-neutral tests

This module must not depend on Datomic or a logging backend.
