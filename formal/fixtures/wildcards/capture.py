#!/usr/bin/env python3
"""Captures SpiceDB's answers to requests.json for the wildcard golden.

Start the pinned image first (see README.md), then run from this directory:

    python3 capture.py [http://127.0.0.1:18443]

It writes schema.zed and relationships.txt to a fresh serve-testing
datastore (a random bearer token), sends every request in requests.json in
order, and records

- raw-responses-v1.56.0-docker.ndjson: each request and the exact response
  text, one JSON object per line;
- spicedb-results.edn: the normalized results that
  eacl.datascript.wildcard-spicedb-golden-test compares with EACL.

Only the Python standard library is used.
"""
import json
import re
import sys
import urllib.error
import urllib.request
import uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:18443"
TOKEN = "eacl-wildcard-golden-" + uuid.uuid4().hex
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


def object_ref(text):
    object_type, object_id = text.split(":", 1)
    return {"objectType": object_type, "objectId": object_id}


RELATIONSHIP = re.compile(
    r"^(?P<rt>[^:]+):(?P<rid>[^#]+)#(?P<rel>[^@]+)@(?P<st>[^:]+):(?P<sid>[^\[#]+)"
    r"(?:#(?P<srel>[^\[]+))?(?:\[(?P<caveat>[^\]:]+)\])?$")


def relationship(text):
    match = RELATIONSHIP.match(text.strip())
    if not match:
        raise ValueError("unparseable relationship: " + text)
    subject = {"object": {"objectType": match["st"], "objectId": match["sid"]}}
    if match["srel"]:
        subject["optionalRelation"] = match["srel"]
    result = {"resource": {"objectType": match["rt"], "objectId": match["rid"]},
              "relation": match["rel"],
              "subject": subject}
    if match["caveat"]:
        result["optionalCaveat"] = {"caveatName": match["caveat"]}
    return result


def lines(text):
    return [json.loads(line) for line in text.splitlines() if line.strip()]


class Keyword(str):
    pass


def permissionship(value):
    for prefix in ("LOOKUP_PERMISSIONSHIP_", "PERMISSIONSHIP_"):
        if value.startswith(prefix):
            return Keyword(value[len(prefix):].lower().replace("_", "-"))
    raise ValueError(value)


def error_of(body):
    details = body.get("details") or []
    reason = next((d.get("reason") for d in details if d.get("reason")), None)
    return {Keyword("error"): {Keyword("code"): body.get("code"),
                               Keyword("reason"): reason}}


def missing_fields(item):
    fields = (item.get("partialCaveatInfo") or {}).get("missingRequiredContext")
    return sorted(set(fields)) if fields else None


def normalize(request, status, text):
    op = request["op"]
    items = lines(text)
    errors = [i.get("error", i) for i in items
              if "error" in i or ("code" in i and "message" in i)]
    if status != 200 or errors:
        return error_of(errors[0] if errors else items[0])
    if op == "check":
        body = items[0]
        result = {Keyword("permissionship"): permissionship(body["permissionship"])}
        if missing_fields(body):
            result[Keyword("missing-fields")] = missing_fields(body)
        return result
    if op == "lookup-resources":
        resources = {}
        for item in items:
            result = item["result"]
            resources[result["resourceObjectId"]] = permissionship(result["permissionship"])
        return {Keyword("resources"): resources}
    if op == "lookup-subjects":
        subjects = []
        for item in items:
            found = item["result"]["subject"]
            entry = {Keyword("id"): found["subjectObjectId"],
                     Keyword("permissionship"): permissionship(found["permissionship"])}
            if missing_fields(found):
                entry[Keyword("missing-fields")] = missing_fields(found)
            excluded = item["result"].get("excludedSubjects") or []
            if excluded:
                entry[Keyword("excluded")] = sorted(
                    ({Keyword("id"): e["subjectObjectId"],
                      Keyword("permissionship"): permissionship(e["permissionship"])}
                     for e in excluded),
                    key=lambda e: e[Keyword("id")])
            subjects.append(entry)
        return {Keyword("subjects"): sorted(subjects, key=lambda s: s[Keyword("id")])}
    if op == "read":
        found = []
        for item in items:
            r = item["result"]["relationship"]
            text = "%s:%s#%s@%s:%s" % (
                r["resource"]["objectType"], r["resource"]["objectId"], r["relation"],
                r["subject"]["object"]["objectType"], r["subject"]["object"]["objectId"])
            if r.get("optionalCaveat"):
                text += "[%s]" % r["optionalCaveat"]["caveatName"]
            found.append(text)
        return {Keyword("relationships"): sorted(found)}
    if op in ("write", "write-schema"):
        return {Keyword("written"): True}
    raise ValueError(op)


def spicedb_request(request):
    op = request["op"]
    if op == "check":
        body = {"consistency": FULLY_CONSISTENT,
                "resource": object_ref(request["resource"]),
                "permission": request["permission"],
                "subject": {"object": object_ref(request["subject"])}}
        path = "/v1/permissions/check"
    elif op == "lookup-resources":
        body = {"consistency": FULLY_CONSISTENT,
                "resourceObjectType": request["resourceType"],
                "permission": request["permission"],
                "subject": {"object": object_ref(request["subject"])}}
        path = "/v1/permissions/resources"
    elif op == "lookup-subjects":
        body = {"consistency": FULLY_CONSISTENT,
                "resource": object_ref(request["resource"]),
                "permission": request["permission"],
                "subjectObjectType": request["subjectType"]}
        path = "/v1/permissions/subjects"
    elif op == "read":
        relationship_filter = {"resourceType": request["resourceType"]}
        if request.get("subjectType"):
            subject_filter = {"subjectType": request["subjectType"]}
            if request.get("subjectId"):
                subject_filter["optionalSubjectId"] = request["subjectId"]
            relationship_filter["optionalSubjectFilter"] = subject_filter
        body = {"consistency": FULLY_CONSISTENT,
                "relationshipFilter": relationship_filter}
        path = "/v1/relationships/read"
    elif op == "write":
        body = {"updates": [{"operation": "OPERATION_TOUCH",
                             "relationship": relationship(request["relationship"])}]}
        path = "/v1/relationships/write"
    elif op == "write-schema":
        body = {"schema": request["schema"]}
        path = "/v1/schema/write"
    else:
        raise ValueError(op)
    if "context" in request:
        body["context"] = request["context"]
    return path, body


def eacl_request(request):
    """The request as EDN data for the EACL test."""
    result = {Keyword("op"): Keyword(request["op"])}
    for key in ("subject", "resource"):
        if key in request:
            result[Keyword(key)] = request[key].split(":", 1)
    for key, edn_key in (("permission", "permission"), ("resourceType", "resource-type"),
                         ("subjectType", "subject-type"), ("subjectId", "subject-id"),
                         ("relationship", "relationship"), ("schema", "schema"),
                         ("context", "context")):
        if key in request:
            result[Keyword(edn_key)] = request[key]
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
    """EDN text; a collection that does not fit in 80 columns is broken
    one element per line."""
    flat = edn_flat(value)
    if indent + len(flat) <= 80 or not isinstance(value, (dict, list, tuple)):
        return flat
    pad = "\n" + " " * (indent + 1)
    if isinstance(value, dict):
        entries = []
        for k, v in value.items():
            key = edn_flat(k)
            entries.append(key + " " + edn(v, indent + 1 + len(key) + 1))
        return "{" + pad.join(entries) + "}"
    return "[" + pad.join(edn(v, indent + 1) for v in value) + "]"


def main():
    with open("schema.zed") as f:
        schema = f.read()
    with open("relationships.txt") as f:
        relationships = [line.strip() for line in f if line.strip()]
    with open("requests.json") as f:
        requests = json.load(f)

    raw = []
    status, text = post("/v1/schema/write", {"schema": schema})
    raw.append({"id": "setup: write schema.zed", "path": "/v1/schema/write",
                "status": status, "response": text})
    assert status == 200, text
    body = {"updates": [{"operation": "OPERATION_TOUCH", "relationship": relationship(r)}
                        for r in relationships]}
    status, text = post("/v1/relationships/write", body)
    raw.append({"id": "setup: write relationships.txt", "path": "/v1/relationships/write",
                "status": status, "response": text})
    assert status == 200, text

    results = []
    for request in requests:
        path, body = spicedb_request(request)
        status, text = post(path, body)
        raw.append({"id": request["id"], "path": path, "request": body,
                    "status": status, "response": text})
        results.append({Keyword("id"): request["id"],
                        Keyword("request"): eacl_request(request),
                        Keyword("spicedb"): normalize(request, status, text)})

    with open("raw-responses-v1.56.0-docker.ndjson", "w") as f:
        for entry in raw:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    with open("spicedb-results.edn", "w") as f:
        f.write(";; Generated by capture.py from raw-responses-v1.56.0-docker.ndjson.\n")
        f.write("[")
        f.write("\n ".join(edn(r, 1) for r in results))
        f.write("]\n")
    print("captured", len(results), "requests")


if __name__ == "__main__":
    main()
