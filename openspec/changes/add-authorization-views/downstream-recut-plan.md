# Downstream Recut Plan

This change does not mutate either downstream repository. Their recuts start
only after this branch's complete JVM/CLJS/backend matrix passes and a new v8
development artifact is published or its coordinates are recorded.

## 1. `eacl-spicedb`

1. Pin the first development artifact containing `IAuthorizationReader` and
   `IAuthorizationWriter`.
2. Replace the removed `IAuthorization`/`IDetailedAuthorization` implementation
   with the eight request-map reader methods and three request-map writer
   methods. `check-permission` is canonical; `can?` remains EACL's projection.
3. Do not add a fake local database value, basis adapter, source, snapshot,
   cache, or release lifecycle. The remote endpoint is a reader/writer only.
4. Refuse snapshot capture, basis inspection, release, batching, or another
   unsupported extension with dual-classified `:eacl/unsupported-capability`
   and the exact `:capability`; never permit a missing protocol method or
   `AbstractMethodError`.
5. Run the clean-consumer gate against the pinned artifact, then retarget its
   API-contract and transport suites before publishing the recut.

## 2. `eacl-datahike-demo`

1. Pin the same development artifact and delete
   `eacl-datahike-demo.eacl-adapter`. Core now projects every Datahike store to
   the bounded portable identity `{:backend ... :id <string>}`; nested tiered
   LMDB/S3 configs, paths, buckets, credentials, and UUID objects cannot enter
   tokens, cursor digests, or cache keys.
2. Replace `make-tiered-client` with `eacl.datahike.core/make-client`; retain
   the existing read-only/source lifecycle and shared key configuration.
3. At the Lambda/session boundary, capture or exactly select once with
   `eacl/snapshot`, keep request reads on that snapshot, and release it with
   `eacl/with-snapshot` or `eacl/release!`. Do not restore the orchestration
   bridge or issue a branch-head read per authorization operation.
4. Change integration assertions from bridge-specific adapter identity to the
   public basis/token identity and acquisition counts: construction zero,
   capture one, snapshot read zero, exact commit locator zero branch-head.
5. Deploy only after the demo's tiered LMDB/S3, direct-S3 reader, and serverless
   suites pass against the pinned artifact.

The two recuts are independent and should be separate PRs. Neither blocks the
other after the core artifact exists.
