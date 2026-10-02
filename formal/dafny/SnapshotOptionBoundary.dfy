module SnapshotOptionBoundary {
  datatype SnapshotOption =
    | IdentityResolverOverride
    | CacheStoreOverride
    | EvaluationClockOverride
    | SecurityConfigurationOverride

  datatype SnapshotOptionDecision =
    | CaptureTrustedRuntime
    | CaptureCallerOverride
    | RejectOptions

  function OpenSnapshotProtocol(
    options: set<SnapshotOption>
  ): SnapshotOptionDecision {
    if options == {} then CaptureTrustedRuntime else CaptureCallerOverride
  }

  function ClosedSnapshotProtocol(
    options: set<SnapshotOption>
  ): SnapshotOptionDecision {
    if options == {} then CaptureTrustedRuntime else RejectOptions
  }

  lemma OpenProtocolCanReplaceIdentityResolver()
    ensures OpenSnapshotProtocol({IdentityResolverOverride}) ==
            CaptureCallerOverride
  {
  }

  lemma EmptyPublicSnapshotCapturesTrustedRuntime()
    ensures ClosedSnapshotProtocol({}) == CaptureTrustedRuntime
  {
  }

  lemma ClosedProtocolRejectsEveryCallerOption(
    options: set<SnapshotOption>
  )
    requires options != {}
    ensures ClosedSnapshotProtocol(options) == RejectOptions
  {
  }
}
