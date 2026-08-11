# Version-pinned SpiceDB shallow-expansion golden

This fixture records black-box behavior for the schema, relationships, and
request in this directory.

- Docker image: `ghcr.io/authzed/spicedb:v1.56.0`
- Image digest:
  `sha256:c8a558a6cc1f9379fcdcab0171b623d65e7e5f95c998ebb7f937ca00a7c1598c`
- Expansion mode: `SHALLOW`
- API request: `POST /v1/permissions/expand`
- Captured: 2026-08-11

The schema, relationships, and request were executed with `serve-testing` and
`POST /v1/permissions/expand`. `raw-response-v1.56.0-docker.protojson` is the
exact live response. Its datastore-specific `expandedAt.token` is retained as
capture metadata but excluded from topology comparison.

`raw-response.protojson` is the comparison fixture derived from that captured
response by omitting `expandedAt` and default-valued `optionalRelation` fields.
No tree node, subject, or collection is reordered or deduplicated.

Cross-implementation comparison recursively sorts `intermediate.children` and
`leaf.subjects` by their complete normalized value. Sorting a vector does not
deduplicate it: duplicate child and subject multiplicity, annotations, union
boundaries, and the leaf/intermediate oneof all remain significant.
