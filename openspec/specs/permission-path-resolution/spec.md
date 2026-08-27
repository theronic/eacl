# permission-path-resolution Specification

## Purpose

Define complete relation lookup for every legal type keyword used by EACL
permission planning.

## Requirements

### Requirement: Relation lookup supports all legal keyword type names

Relation lookup and every permission-planning operation built on it SHALL
return relation entities for a resource type and relation name regardless of
how the subject-type keyword collates, including uppercase-initial,
`z`-prefixed, and namespaced keywords.

#### Scenario: Subject type sorting after z

- **WHEN** the schema defines an `:owner` relation from `:zone` to
  `:zebra` and a zebra subject owns a zone resource
- **THEN** permission checks and resource lookup include that relationship

#### Scenario: Uppercase and namespaced subject types

- **WHEN** relations use subject types `:Admin` and `:my.app/user`
- **THEN** relation lookup returns their definitions and permission planning
  includes them

#### Scenario: Prefix scan remains exact

- **WHEN** relations exist for both `(:zone :owner)` and
  `(:zone :ownerx)`
- **THEN** lookup for `(:zone :owner)` returns only the exact relation

