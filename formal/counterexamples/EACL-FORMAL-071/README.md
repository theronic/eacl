# EACL-FORMAL-071 — boolean false was confused with an omitted value

EACL accepts every non-nil public object ID so applications can install custom
identity codecs. The boolean value `false` therefore names a real object when a
codec resolves it. Authorization checks honored relationships for that object,
but relationship filters and resumed cursors used Clojure truthiness to decide
whether the ID was present. A live grant could consequently disappear from an
audit or revocation scan while continuing to authorize the subject.

The same host-language mistake silently defaulted explicit `false` evaluation,
timeout, and cancellation controls. Those control cases do not create a grant,
but can weaken an application's intended work and cancellation limits when it
forwards caller-controlled request options.

All public identity branches now test non-nil presence, and malformed false
execution controls receive typed rejection. The real DataScript regression
checks the active permission, subject-filtered inspection, relationship cursor,
and stable authorization-result cursor. The Dafny models distinguish presence
from truthiness, and five portable mutation controls restore the former
behavior one boundary at a time.
