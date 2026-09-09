## Why

The optional `read-relationships :authorization` clause adds permission evaluation, accepted-result pagination and maintenance surface to an operation intended to read stored relationships. The owner has explicitly rejected this feature: applications can compose relationship reads with `can?` or `check-permissions`, and the demo does not need authorized nested expansion.

## What Changes

- **BREAKING**: remove `:authorization` from `read-relationships`; reject its presence, including nil or malformed values, through the existing closed-filter error contract instead of silently returning unfiltered rows.
- Delete the authorized scan path and exclusively associated validation, schema checks, relay handling, cursor/cache branches, tests and benchmarks. Preserve shared machinery still used by supported operations.
- Preserve ordinary indexed relationship reads, pagination, consistency, cache controls, qualified relationship inspection and existing resource bounds.
- Preserve `can?`, `check-permission`, `check-permissions`, ordinary lookups/counts and all permission operators, including exclusion. No schema, fixture or storage changes.
- Document explicit application composition without adding another convenience API or compatibility implementation.
- Supersede the requirement to preserve authorized relationship reads in `2026-09-09-remove-lookup-relationship-filters` and the shipped aggregate-read documentation. Removal of the separate lookup relationship filters remains that change's responsibility.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `backend-unification`: relationship reads uniformly reject the removed clause, perform no endpoint permission filtering, and retain the supported read and authorization contracts across bundled backends.

## Impact

Core public request validation and orchestration, relay, schema validation, backend scan integration, aggregate fixtures/tests/benchmarks, documentation and release notes. The companion demo change is `/Users/petrus/code/eacl/eacl-demo/openspec/changes/2026-09-09-use-direct-relationship-expansion`; it removes caller fields and keeps nested branches as direct reads. These proposals do not modify running services, implement code or deploy artifacts.
