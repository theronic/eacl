# Version-pinned SpiceDB Caveat golden

This fixture records SpiceDB's black-box answer for every case of the shared
Caveat corpus (`modules/eacl-caveats-jvm/test/eacl/caveats/corpus.edn`),
including the `exists` and `all` cases of EACL CEL profile 2.
`eacl.caveats.portable.spicedb-test` compares them with the answers of both
evaluators, `eacl.caveats.jvm` and `eacl.caveats.portable`.

- Docker image: `ghcr.io/authzed/spicedb:v1.56.0` (`spicedb version` prints
  `spicedb v1.56.0`)
- Image digest:
  `sha256:c8a558a6cc1f9379fcdcab0171b623d65e7e5f95c998ebb7f937ca00a7c1598c`
- Command: `serve-testing --skip-release-check --http-enabled`, HTTP gateway
  published on `127.0.0.1:18443`
- Consistency: `fullyConsistent` on every check
- Captured: 2026-09-27

| File | Content |
| --- | --- |
| `requests.json` | 59 requests, one per corpus case: a check for each case EACL admits, a schema write for each case it rejects |
| `capture.py` | Writes the rejected cases' schemas, then one schema with every admitted case's Caveat and one relationship per case, sends each check and writes the two files below (Python standard library only) |
| `raw-responses-v1.56.0-docker.ndjson` | Each request body and SpiceDB's exact response text, one JSON object per line, with the two setup writes |
| `spicedb-results.edn` | The normalized results the test reads, each with its request |

Each admitted case `c` becomes `caveat c_<c>` with the case's parameters and
source, `relation r_<c>: user with c_<c>` and `permission p_<c> = r_<c>` on
`definition doc`, and the relationship `doc:<c>#r_<c>@user:u[c_<c>]` whose
Caveat context is the case's bound context. The check asks for `p_<c>` on
`doc:<c>` with the case's request context. Timestamps are RFC 3339 strings.

To recapture, regenerate `requests.json` from the corpus through an nREPL,
start the image and run the script in this directory:

```sh
clj-nrepl-eval -p <port> "(do (require 'eacl.caveats.portable.spicedb-test) (eacl.caveats.portable.spicedb-test/write-requests!))"
docker run -d --rm --name eacl-caveat-golden -p 127.0.0.1:18443:8443 \
  ghcr.io/authzed/spicedb:v1.56.0 serve-testing --skip-release-check --http-enabled
python3 capture.py
docker stop eacl-caveat-golden
```

The test derives each request from the corpus again and fails if the
recorded request differs, so a changed corpus needs a new capture.

## Normalization

`spicedb-results.edn` keeps, per request, the request and one of:

- `:permissionship`, and sorted, distinct `:missing-fields` when conditional,
  for a check (`missingRequiredContext` can name a field more than once);
- `:error` with the gRPC code, the first `ErrorInfo` reason and, for a Caveat
  evaluation error, the message after the Caveat name (`no such key: zz`);
- `:written true` for a schema SpiceDB accepts.

## How EACL is compared

For each case and each evaluator, EACL's `:true`, `:false` and
`:conditional` outcomes must equal SpiceDB's permissionship and missing
fields, a `:missing-map-key` fault must be SpiceDB's `no such key`
evaluation error, and a rejected definition must be a rejected schema write.
The residual is EACL's own and is not compared.

These cases differ, deliberately or because of a SpiceDB defect, and are
listed in the test, which also fails if one of them stops differing:

| Case | EACL | SpiceDB | Why |
| --- | --- | --- | --- |
| `fault-before-missing-field` | `:missing-map-key` | conditional, `b` | Without a deciding element, EACL's fold prefers a fault to a missing field, as its `&&` and `\|\|` do; cel-go prefers the unknown |
| `variable-shadows-parameter` | false | conditional, `x` | cel-go marks the absent parameter `x` unknown, and with it the comprehension variable `x` that hides it, although `exists` is false for every `x` |
| `absent-list-charged-per-element` | `:resource-limit` | conditional, `ys` | The work preflight charges an absent `list<string>` at its declared maximum size for each element of the range |
| `exists-one-excluded`, `map-macro-excluded` | rejected | accepted | Profile 2 admits only `exists` and `all` |
| `accumulator-parameter-rejected` | rejected | accepted | cel-go reads a parameter named `__result__` inside a comprehension as the macro's accumulator |
| `wrong-supplied-type` | `:context-type` | has permission | EACL admits only the declared type; SpiceDB converts `"2"` to `int` |
| `bad-overload`, `regex-excluded`, `arithmetic-excluded` | rejected | accepted | Profile 1 exclusions |

SpiceDB also rejects `repeated-not-excluded`, but because its Caveat has no
parameters. The answers were probed by hand beyond the corpus too: with an
absent map, `xs.exists(x, m[x])` over a supplied list is conditional on `m` in
EACL and an evaluation error (`no such attribute(s): x`) in SpiceDB, another
effect of cel-go's unknown marking.
