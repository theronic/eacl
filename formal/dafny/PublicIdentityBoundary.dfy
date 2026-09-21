module PublicIdentityBoundary {
  // Public IDs are host values.  Distinct representations can compare equal
  // in Clojure even though an injective custom codec is allowed to distinguish
  // them.  The aliases below model list/vector and BigInt/Long equality.
  datatype PublicId =
    | Text(textValue: string)
    | Vector(vectorValue: seq<int>)
    | ListAlias(listValue: seq<int>)
    | Number(numberValue: int)
    | BigNumberAlias(bigNumberValue: int)
    | BooleanFalse

  datatype InternalId =
    | TextInternal(internalTextValue: string)
    | VectorInternal(internalVectorValue: seq<int>)
    | ListInternal(internalListValue: seq<int>)
    | NumberInternal(internalNumberValue: int)
    | BigNumberInternal(internalBigNumberValue: int)
    | BooleanFalseInternal

  predicate HostEqual(left: PublicId, right: PublicId) {
    if left.Text? then
      right.Text? && left.textValue == right.textValue
    else if left.Vector? then
      (right.Vector? && left.vectorValue == right.vectorValue) ||
      (right.ListAlias? && left.vectorValue == right.listValue)
    else if left.ListAlias? then
      (right.Vector? && left.listValue == right.vectorValue) ||
      (right.ListAlias? && left.listValue == right.listValue)
    else if left.Number? then
      (right.Number? && left.numberValue == right.numberValue) ||
      (right.BigNumberAlias? &&
       left.numberValue == right.bigNumberValue)
    else if left.BigNumberAlias? then
      (right.Number? && left.bigNumberValue == right.numberValue) ||
      (right.BigNumberAlias? &&
       left.bigNumberValue == right.bigNumberValue)
    else
      right.BooleanFalse?
  }

  predicate CanonicalPublicIdentity(id: PublicId) {
    id.Text? || id.Vector? || id.Number? || id.BooleanFalse?
  }

  function Resolve(id: PublicId): InternalId {
    match id
    case Text(value) => TextInternal(value)
    case Vector(value) => VectorInternal(value)
    case ListAlias(value) => ListInternal(value)
    case Number(value) => NumberInternal(value)
    case BigNumberAlias(value) => BigNumberInternal(value)
    case BooleanFalse => BooleanFalseInternal
  }

  datatype MemoKey = NoMemo | PublicMemo(memoId: PublicId)

  function PublicMemoKey(id: PublicId): MemoKey {
    if CanonicalPublicIdentity(id) then PublicMemo(id) else NoMemo
  }

  lemma HostEqualityHasRepresentationSensitiveCounterexamples()
    ensures HostEqual(Vector([1]), ListAlias([1]))
    ensures Resolve(Vector([1])) != Resolve(ListAlias([1]))
    ensures HostEqual(Number(7), BigNumberAlias(7))
    ensures Resolve(Number(7)) != Resolve(BigNumberAlias(7))
  {
  }

  lemma CanonicalHostEqualityIsResolverCongruent(
    left: PublicId,
    right: PublicId
  )
    requires CanonicalPublicIdentity(left)
    requires CanonicalPublicIdentity(right)
    requires HostEqual(left, right)
    ensures Resolve(left) == Resolve(right)
  {
  }

  lemma RepresentationAliasesBypassPublicMemoization(value: seq<int>)
    ensures PublicMemoKey(ListAlias(value)) == NoMemo
  {
  }

  lemma NumericRepresentationAliasesBypassPublicMemoization(value: int)
    ensures PublicMemoKey(BigNumberAlias(value)) == NoMemo
  {
  }

  lemma EqualPublicMemoKeysHaveEqualInternalIdentity(
    left: PublicId,
    right: PublicId
  )
    requires PublicMemoKey(left).PublicMemo?
    requires PublicMemoKey(left) == PublicMemoKey(right)
    ensures Resolve(left) == Resolve(right)
  {
  }

  datatype PublicRelationship = PublicRelationship(
    subject: PublicId,
    resource: PublicId
  )

  datatype ResolvedRelationship = ResolvedRelationship(
    subject: InternalId,
    resource: InternalId
  )

  function ResolveRelationship(
    relationship: PublicRelationship
  ): ResolvedRelationship {
    ResolvedRelationship(
      Resolve(relationship.subject),
      Resolve(relationship.resource)
    )
  }

  predicate HostRelationshipEqual(
    left: PublicRelationship,
    right: PublicRelationship
  ) {
    HostEqual(left.subject, right.subject) &&
    HostEqual(left.resource, right.resource)
  }

  lemma ResolveBeforeCoalescingPreservesDistinctRelationships()
    ensures HostRelationshipEqual(
              PublicRelationship(ListAlias([1]), Text("document")),
              PublicRelationship(Vector([1]), Text("document"))
            )
    ensures ResolveRelationship(
              PublicRelationship(ListAlias([1]), Text("document"))
            ) != ResolveRelationship(
                   PublicRelationship(Vector([1]), Text("document"))
                 )
  {
  }

  datatype ResolutionResult =
    | Resolved(resolvedId: InternalId)
    | Missing
    | ResolutionFailed

  datatype DeletePlan =
    | DeleteResolved(resolvedDeleteId: InternalId)
    | DeleteNoop
    | DeleteRejected
    | DeleteNative(nativeEid: nat)

  function PlanPublicDelete(result: ResolutionResult): DeletePlan {
    match result
    case Resolved(id) => DeleteResolved(id)
    case Missing => DeleteNoop
    case ResolutionFailed => DeleteRejected
  }

  function PlanNativeDelete(eid: nat): DeletePlan {
    DeleteNative(eid)
  }

  lemma MissingNumericPublicIdNeverFallsBackToNativeEid(eid: nat)
    ensures PlanPublicDelete(Missing) == DeleteNoop
    ensures PlanPublicDelete(Missing) != PlanNativeDelete(eid)
  {
  }

  lemma NativeEidDeletionRequiresTheExplicitNativePath(eid: nat)
    ensures PlanNativeDelete(eid) == DeleteNative(eid)
  {
  }

  lemma PublicResolutionFailureIsNotNotFound()
    ensures PlanPublicDelete(ResolutionFailed) == DeleteRejected
    ensures PlanPublicDelete(ResolutionFailed) != PlanPublicDelete(Missing)
  {
  }

  datatype OptionalPublicId =
    | MissingPublicId
    | PresentPublicId(publicId: PublicId)

  datatype FilterResolution =
    | FilterOmitted
    | FilterResolved(internalId: InternalId)

  // Models the former host implementation: boolean false was admitted as a
  // public ID but then used as the branch condition for filter/cursor work.
  function TruthinessBasedFilterResolution(
    anchor: OptionalPublicId
  ): FilterResolution {
    match anchor
    case MissingPublicId => FilterOmitted
    case PresentPublicId(id) =>
      if id.BooleanFalse? then FilterOmitted
      else FilterResolved(Resolve(id))
  }

  function PresenceBasedFilterResolution(
    anchor: OptionalPublicId
  ): FilterResolution {
    match anchor
    case MissingPublicId => FilterOmitted
    case PresentPublicId(id) => FilterResolved(Resolve(id))
  }

  lemma TruthinessDropsAnAdmittedFalseIdentity()
    ensures TruthinessBasedFilterResolution(
              PresentPublicId(BooleanFalse)
            ) == FilterOmitted
    ensures PresenceBasedFilterResolution(
              PresentPublicId(BooleanFalse)
            ) == FilterResolved(BooleanFalseInternal)
  {
  }

  lemma PresencePreservesEveryAdmittedIdentity(id: PublicId)
    ensures PresenceBasedFilterResolution(PresentPublicId(id)) ==
            FilterResolved(Resolve(id))
  {
  }

  // Native entity IDs and public numeric IDs occupy different trust domains.
  // The former mixed adapter callback selected the native branch solely from
  // the host value's numeric shape, even for an authenticated public cursor.
  datatype IdentitySelection =
    | CodecSelection(codecIdentity: InternalId)
    | NativeSelection(nativeIdentity: int)

  function MixedDomainCursorResolution(id: PublicId): IdentitySelection {
    if id.Number? then NativeSelection(id.numberValue)
    else CodecSelection(Resolve(id))
  }

  function PublicDomainCursorResolution(id: PublicId): IdentitySelection {
    CodecSelection(Resolve(id))
  }

  lemma NumericPublicCursorHasANativeAliasCounterexample()
    ensures MixedDomainCursorResolution(Number(0)) == NativeSelection(0)
    ensures PublicDomainCursorResolution(Number(0)) ==
            CodecSelection(NumberInternal(0))
    ensures MixedDomainCursorResolution(Number(0)) !=
            PublicDomainCursorResolution(Number(0))
  {
  }

  lemma PublicCursorResolutionNeverSelectsNativeIdentity(id: PublicId)
    ensures PublicDomainCursorResolution(id).CodecSelection?
    ensures PublicDomainCursorResolution(id) == CodecSelection(Resolve(id))
  {
  }
}
