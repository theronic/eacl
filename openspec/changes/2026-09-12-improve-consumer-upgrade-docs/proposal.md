## Why

A consumer upgrade of eDrive to `8.0.0-RC-2026-09-12` exposed startup failures and scattered, conflicting advice. Readers should be able to install EACL, use their own object IDs, and add expiring access without reading the library source.

## What Changes

- Put a complete Datomic memory example near the top of README.md, using the published release and an application-owned `:app/id` attribute.
- Use that same unique ID schema in creation, permission checks, listing, atomic writes, and safe deletion examples. Explain the existing `:eacl/id` default only as compatibility information.
- Add a short upgrade checklist covering dependency overrides, fresh versus retained databases, schema installation, removed cache options, and safe deletion function updates.
- Add a simple expiration recipe before the more advanced conditional access guide. Cover setting, changing, clearing, displaying, and reaching a deadline.
- Remove outdated query options and resolve conflicting transaction examples by checking them against the published artifact.
- Write recommendations and instructions in Plain English. Move internal design and contributor setup out of the getting-started path.
- Verify consumer examples against Maven dependencies without local checkout overrides or formal build tools.

## Capabilities

### New Capabilities

None. This change improves documentation and example verification.

### Modified Capabilities

None. The library's behavior and public API remain unchanged. This documentation-only proposal sets `skip_specs: true`.

## Impact

Affected documents include README.md, modules/eacl-datomic/README.md, docs/v8-backend-modules-and-upgrade.md, docs/caveats.md, docs/aggregate-authorization.md, and relevant examples and release notes. Other backend quickstarts need their own supported custom-ID examples; do not copy Datomic options blindly.

The consumer evidence is in [eDrive's upgrade catalogue](https://github.com/theronic/eacl-edrive/blob/573e5adab928e161b18c83459f364f2b85e8d305/docs/eacl-v8-upgrade.md). eDrive's application upgrade and sharing inputs are delivered separately from this proposed library documentation work.

## Non-goals

Do not remove the existing ID default, change storage, add new APIs, or rewrite historical release records. Do not promise atomic tempid behavior that the released API does not support.
