## 1. Verify consumer recipes

- [x] 1.1 Add a standalone Datomic memory example using the dated Maven release and `:app/id`; run it through nREPL and verify allow, deny, lookup, and deletion results without local overrides.
- [x] 1.2 Verify the released options for atomic writes with existing endpoints and new tempids; deliver separate working recipes or a clearly stated limitation for each case.
- [x] 1.3 Add an expiration-only example; verify access before and at the deadline, renewal, clearing the deadline, and another grant preserving access.

## 2. Rewrite the getting-started path

- [x] 2.1 Move the short Datomic quickstart near the start of README.md; verify a reader can follow every step with only the stated prerequisites.
- [x] 2.2 Replace application-facing internal ID examples in current docs with supported application-owned IDs; search for remaining `:eacl/id` recommendations and explain each intentional compatibility reference.
- [x] 2.3 Add the upgrade symptom table and fresh-versus-retained database instructions; check each fix against the eDrive catalogue and relevant migration guide.
- [x] 2.4 Update the adapter README and current dependency snippets; verify they match the published release and link to the consumer example.

## 3. Explain everyday sharing

- [x] 3.1 Put the standalone expiration recipe before conditional access details in docs/caveats.md; verify it needs no evaluator dependency and explains local time, UTC storage, and the exclusive deadline.
- [x] 3.2 Document changing roles, renewing expired rows, clearing expiration, inherited access, and refreshing without a write; verify all examples against the release.
- [x] 3.3 Remove obsolete query options from README.md and docs/aggregate-authorization.md; run the replacement examples and verify sharing reads are protected by an application permission check.

## 4. Review and publish documentation

- [x] 4.1 Review all new prose for Plain English; explain unavoidable terms on first use and link advanced details instead of inserting them into setup steps.
- [x] 4.2 Check links, complete code blocks, dependency resolution, and every catalogue issue; deliver a short record of the passing consumer checks and any remaining limitation.
