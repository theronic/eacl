# EACL-FORMAL-052 — map `can?` weakened false consistency to the default mode

The public consistency descriptor rejects unknown values. Positional `can?`
therefore rejected `false`, but all three backend records preprocessed the map
arity with `(or consistency :local-snapshot)`, the then-current pre-release
name for the default mode. Clojure treats `false` as falsey, so the same
malformed value silently became a valid default request only when supplied in
the map API.

The public methods now pass the raw value to the shared descriptor. Omitted and
explicit `nil` consistency default to `:minimize-latency`; `false` produces
`:eacl/unsupported-consistency` with the offending value preserved.
Cross-backend regressions failed before the source change and pass after it.

`ConsistencyDecision.dfy` now retains the public-input distinction explicitly.
It proves that false cannot default, that explicit valid modes are preserved,
and that minimize-latency acceptance arises only from omission, nil, or an
explicit minimize-latency mode.
