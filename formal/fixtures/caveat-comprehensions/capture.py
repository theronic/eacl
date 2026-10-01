#!/usr/bin/env python3
"""Captures SpiceDB's answers to requests.json for the Caveat corpus golden.

Start the pinned image first (see README.md), then run from this directory:

    python3 capture.py [http://127.0.0.1:18443]

requests.json holds one request per case of the shared Caveat corpus
(modules/eacl-caveats-jvm/test/eacl/caveats/corpus.edn), derived by
eacl.caveats.portable.spicedb-test/write-requests!. On a fresh serve-testing
datastore (a random bearer token), this script

1. writes, for each case EACL rejects, a schema declaring only its Caveat;
2. writes one schema declaring every other case's Caveat, with a relation
   `r_<case>: user with c_<case>` and a permission `p_<case> = r_<case>` on
   `definition doc`;
3. touches `doc:<case>#r_<case>@user:u[c_<case>]`, with the case's bound
   context as the relationship's Caveat context;
4. checks `p_<case>` on `doc:<case>` for `user:u` with the case's request
   context;

and records

- raw-responses-v1.56.0-docker.ndjson: each request and the exact response
  text, one JSON object per line;
- spicedb-results.edn: the normalized results that
  eacl.caveats.portable.spicedb-test compares with EACL.

Only the Python standard library is used.
"""
import json
import sys
import urllib.error
import urllib.request
import uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18443"
TOKEN = "eacl-caveat-golden-" + uuid.uuid4().hex
FULLY_CONSISTENT = {"fullyConsistent": True}


def post(path, body):
    request = urllib.request.Request(
        BASE + path,
        data=json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + TOKEN,
                 "Content-Type": "application/json"},
        method="POST")
    try:
        with urllib.request.urlopen(request) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


class Keyword(str):
    pass


def schema(requests):
    text = ""
    for r in requests:
        text += "caveat %s(%s) {\n  %s\n}\n\n" % (r["caveat"], r["parameters"], r["source"])
    text += "definition user {}\n\ndefinition doc {\n"
    for r in requests:
        name = r.get("resource") or r["caveat"][2:]
        text += "  relation r_%s: user with %s\n  permission p_%s = r_%s\n" % (
            name, r["caveat"], name, name)
    return text + "}\n"


def permissionship(value):
    return Keyword(value[len("PERMISSIONSHIP_"):].lower().replace("_", "-"))


def error_of(body):
    details = body.get("details") or []
    reason = next((d.get("reason") for d in details if d.get("reason")), None)
    result = {Keyword("code"): body.get("code"), Keyword("reason"): reason}
    message = body.get("message") or ""
    marker = "evaluation error for caveat "
    if message.startswith(marker):
        # "evaluation error for caveat c_name: no such key: zz"
        result[Keyword("detail")] = message.split(": ", 1)[1]
    return {Keyword("error"): result}


def normalize(request, status, text):
    body = json.loads(text)
    if status != 200:
        return error_of(body)
    if request["op"] == "write-schema":
        return {Keyword("written"): True}
    result = {Keyword("permissionship"): permissionship(body["permissionship"])}
    fields = (body.get("partialCaveatInfo") or {}).get("missingRequiredContext")
    if fields:
        # SpiceDB can name a field more than once; keep each field once.
        result[Keyword("missing-fields")] = sorted(set(fields))
    return result


def edn_flat(value):
    if isinstance(value, Keyword):
        return ":" + value
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return "nil"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, (list, tuple)):
        return "[" + " ".join(edn_flat(v) for v in value) + "]"
    if isinstance(value, dict):
        return "{" + ", ".join(edn_flat(k) + " " + edn_flat(v) for k, v in value.items()) + "}"
    raise TypeError(type(value))


def edn(value, indent=0):
    """EDN text; a collection that does not fit in 100 columns is broken
    one element per line."""
    flat = edn_flat(value)
    if indent + len(flat) <= 100 or not isinstance(value, (dict, list, tuple)):
        return flat
    pad = "\n" + " " * (indent + 1)
    if isinstance(value, dict):
        entries = []
        for k, v in value.items():
            key = edn_flat(k)
            entries.append(key + " " + edn(v, indent + 1 + len(key) + 1))
        return "{" + pad.join(entries) + "}"
    return "[" + pad.join(edn(v, indent + 1) for v in value) + "]"


def edn_request(request):
    """The request as the EDN data eacl.caveats.portable.spicedb-test derives."""
    return {Keyword(k): v for k, v in request.items()}


def main():
    with open("requests.json") as f:
        requests = json.load(f)
    rejected = [r for r in requests if r["op"] == "write-schema"]
    checks = [r for r in requests if r["op"] == "check"]

    raw, answers = [], {}
    for r in rejected:
        body = {"schema": schema([r])}
        status, text = post("/v1/schema/write", body)
        raw.append({"id": r["id"], "path": "/v1/schema/write", "request": body,
                    "status": status, "response": text})
        answers[r["id"]] = normalize(r, status, text)

    status, text = post("/v1/schema/write", {"schema": schema(checks)})
    raw.append({"id": "setup: write the checked Caveats", "path": "/v1/schema/write",
                "status": status, "response": text})
    assert status == 200, text
    updates = []
    for r in checks:
        caveat = {"caveatName": r["caveat"]}
        if r.get("bound"):
            caveat["context"] = r["bound"]
        updates.append({"operation": "OPERATION_TOUCH", "relationship": {
            "resource": {"objectType": "doc", "objectId": r["resource"]},
            "relation": "r_" + r["resource"],
            "subject": {"object": {"objectType": "user", "objectId": "u"}},
            "optionalCaveat": caveat}})
    status, text = post("/v1/relationships/write", {"updates": updates})
    raw.append({"id": "setup: write one relationship per checked case",
                "path": "/v1/relationships/write", "status": status, "response": text})
    assert status == 200, text

    for r in checks:
        body = {"consistency": FULLY_CONSISTENT,
                "resource": {"objectType": "doc", "objectId": r["resource"]},
                "permission": "p_" + r["resource"],
                "subject": {"object": {"objectType": "user", "objectId": "u"}},
                "context": r["context"]}
        status, text = post("/v1/permissions/check", body)
        raw.append({"id": r["id"], "path": "/v1/permissions/check", "request": body,
                    "status": status, "response": text})
        answers[r["id"]] = normalize(r, status, text)

    with open("raw-responses-v1.56.0-docker.ndjson", "w") as f:
        for entry in raw:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    with open("spicedb-results.edn", "w") as f:
        f.write(";; Generated by capture.py from raw-responses-v1.56.0-docker.ndjson.\n")
        f.write("[")
        f.write("\n ".join(edn({Keyword("request"): edn_request(r),
                                Keyword("spicedb"): answers[r["id"]]}, 1)
                           for r in requests))
        f.write("]\n")
    print("captured", len(requests), "requests")


if __name__ == "__main__":
    main()
