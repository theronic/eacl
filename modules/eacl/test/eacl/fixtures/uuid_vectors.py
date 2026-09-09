"""Independent wire oracle for secure-format-test and uuid-lifecycle-test.

Run with Python 3 and OpenSSL; no EACL implementation is imported. The authored
payloads and fixed nonce are test fixtures, never production key material.
"""
import base64
import hashlib
import hmac
import json
import subprocess
import uuid


class Keyword(str):
    pass


def canonical(value):
    if isinstance(value, Keyword):
        return ":" + value
    if isinstance(value, uuid.UUID):
        return '#uuid "' + str(value) + '"'
    if value is None:
        return "nil"
    if value is True:
        return "true"
    if isinstance(value, str):
        return json.dumps(value)
    if isinstance(value, int):
        return str(value)
    if isinstance(value, list):
        return "[" + " ".join(map(canonical, value)) + "]"
    return "{" + ", ".join(canonical(k) + " " + canonical(v)
                            for k, v in sorted(value.items(), key=lambda kv: canonical(kv[0]))) + "}"


def fields(**values):
    return {Keyword(k.replace("_", "-")): v for k, v in values.items()}


def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def mac(key, value):
    return hmac.new(key, value.encode(), hashlib.sha256).digest()


def derive(key, domain):
    return mac(key, "eacl/secure-format/key/v1\n" + domain)


def authenticated(domain, prefix, key, payload, kid=Keyword("current")):
    signed = fields(v=2, kid=kid, payload=b64(canonical(payload).encode()))
    tag = mac(derive(key, domain), domain + "\n" + canonical(signed))
    return prefix + b64(canonical(dict(signed, **{}) | fields(tag=b64(tag))).encode())


def cursor(payload):
    domain = "eacl/cursor/envelope/v7"
    key = derive(bytes(range(32, 64)), domain)
    encryption = derive(key, "eacl/cursor/aead/encryption/v1")
    authentication = derive(key, "eacl/cursor/aead/authentication/v1")
    nonce = bytes(range(12))
    plaintext = canonical(fields(version=7, cursor=payload, issued_at=100, expires_at=105))
    ciphertext = subprocess.run(
        ["openssl", "enc", "-aes-256-ctr", "-K", encryption.hex(),
         "-iv", (nonce + bytes([0, 0, 0, 1])).hex()], input=plaintext.encode(),
        stdout=subprocess.PIPE, check=True).stdout
    body = ".".join([b64(b":current"), b64(nonce), b64(ciphertext)])
    return "eacl_c7_" + body + "." + b64(mac(authentication, domain + "\n" + body))


if __name__ == "__main__":
    native = uuid.UUID("854e138f-b8a4-42ee-a8f9-49c01ac19fc1")
    cursor_payload = fields(v=9, kind=Keyword("relationships"),
                            scope=[Keyword("read"), {Keyword("subject/id"): "u1"}], offset=2)
    cache_payload = fields(version=3, portable_version=1,
                           key=fields(semantic_key=[Keyword("can?"), "u1"]),
                           kind=Keyword("boolean"), computed_at=fields(graph=7),
                           validated_at=fields(graph=7),
                           dependency_scope=fields(schema=[[Keyword("document"), Keyword("view")]], relations=[11]),
                           proof=fields(schema="s1", relations={11: "r1"}), value=True)
    causal_payload = fields(version=5, backend=Keyword("datascript"),
                            source_id=fields(connection_id="one"), branch=None,
                            source_lifecycle=native, revision=7, exact_locator=None,
                            issued_at=100, expires_at=200)
    print(json.dumps({
        "portable-cursor-vector": cursor(cursor_payload),
        "portable-cache-vector": authenticated("eacl/cache-entry/envelope/v3", "eacl_ce3_", bytes(range(32, 64)), cache_payload),
        "native-causal-vector": authenticated("eacl/zed-token/envelope/v5", "eacl_z5_", b"uuid-test-fixture-key-00000000000", causal_payload, Keyword("test")),
        "native-cursor-vector": cursor(fields(source_lifecycle=native)),
    }, indent=2))
