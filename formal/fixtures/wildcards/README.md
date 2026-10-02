# Version-pinned SpiceDB wildcard golden

This fixture records SpiceDB's black-box answers for wildcard subjects
(`user:*`), which `eacl.datascript.wildcard-spicedb-golden-test` compares with
EACL's.

- Docker image: `ghcr.io/authzed/spicedb:v1.56.0` (`spicedb version` prints
  `spicedb v1.56.0`)
- Image digest:
  `sha256:c8a558a6cc1f9379fcdcab0171b623d65e7e5f95c998ebb7f937ca00a7c1598c`
- Command: `serve-testing --skip-release-check --http-enabled`, HTTP gateway
  published on `127.0.0.1:18443`
- Consistency: `fullyConsistent` on every read
- Captured: 2026-09-27

| File | Content |
| --- | --- |
| `schema.zed` | Wildcard branches beside and instead of concrete ones, with union, intersection, exclusion, arrows to a relation and to a permission that hold a wildcard, recursion, a Caveated wildcard (the first consumer's `nothing_sensitive` exit) and a Caveated exclusion |
| `relationships.txt` | Relationships in SpiceDB's text form; `[name]` names a Caveat without saved context |
| `requests.json` | 72 requests: checks, `LookupResources`, `LookupSubjects`, `ExpandPermissionTree`, relationship reads, and writes and a schema that SpiceDB rejects |
| `capture.py` | Writes the schema and relationships to a fresh datastore, sends each request in order and writes the two files below (Python standard library only) |
| `raw-responses-v1.56.0-docker.ndjson` | Each request body and SpiceDB's exact response text, one JSON object per line, beginning with the two setup writes |
| `spicedb-results.edn` | The normalized results the test reads |

To recapture, start the image and run `python3 capture.py` in this directory:

```sh
docker run -d --rm --name eacl-wildcard-golden -p 127.0.0.1:18443:8443 \
  ghcr.io/authzed/spicedb:v1.56.0 serve-testing --skip-release-check --http-enabled
python3 capture.py
docker stop eacl-wildcard-golden
```

## Normalization

`spicedb-results.edn` keeps, per request, the request as EDN and one of:

- `:permissionship` (and sorted, distinct `:missing-fields` when conditional)
  for a check;
- `:resources`, a map of resource ID to permissionship, for `LookupResources`;
- `:subjects`, sorted by ID, each with its permissionship, missing fields and
  `:excluded` subjects (sorted by ID) for `LookupSubjects`;
- `:tree`, the permission tree in SpiceDB's order, for an expansion;
- `:relationships`, sorted, in the text form above, for a read;
- `:error` with the gRPC code and the first `ErrorInfo` reason for a rejected
  request.

Datastore revision tokens and the order of streamed results are not
compared. `missingRequiredContext` can name a field more than once;
normalization keeps each field once.

## How EACL is compared

Checks, resource lookups and reads must be equal, and permission trees must
have the same topology, with union and intersection children and leaf
subjects compared unordered. A rejected request must raise the EACL error
class the test maps from SpiceDB's reason. The expansions avoid relations
that hold Caveated relationships, which EACL's permission trees do not expand.

Subject lookups are compared by what they grant. A subject holds the
permission through its own entry or, unless the `*` entry excludes it, through
`*`. For every subject ID of the requested type in the fixture, and one
existing subject that no relationship names, the permissionship read this way
must be equal. The `*` entry must be present exactly when SpiceDB returns it,
and every concrete subject SpiceDB lists must be listed by EACL. EACL may also
list a granted subject that holds a relationship of its own although `*`
covers it (for example `frank` for `guarded` on a Sunday), a superset with the
same meaning. SpiceDB reports a conditionally excluded subject only in
`excluded_subjects`; EACL's `:detailed` lookups exclude it from `*` and also
return it as its own conditional entry, and its default lookups exclude it.

SpiceDB grants a wildcard to any subject ID. EACL grants it to the objects that
exist, so the test creates every subject it asks about; an ID that names no
object remains unknown to EACL.
