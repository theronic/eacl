# EACL-FORMAL-074 — incomplete authorization demands reached readers

The public wrapper treated a request map as closed when all of its *present*
keys were known, even when a mandatory key was absent. For example,
`{:permission :view :resource document}` reached `IAuthorizationReader` without
a subject.

Bundled EACL backends ordinarily denied or rejected the malformed request later,
so this was not a false grant in their standard path. The protocol is public,
however, and remote or third-party readers could interpret an absent subject as
a default principal or wildcard. An attacker can reach that behavior when an
application constructs the request map from optional attacker-controlled input.
That is also incorrect usage: the omitted fields are documented as required.

The shared boundary now rejects missing keys before dispatch and applies the
complete batch, relationship-scan, authorization-clause, and nested lookup
shape checks there as well. The Dafny model requires all three point-demand
identities, and the production mutation control proves that removing validation
makes the unsafe reader grant again.
