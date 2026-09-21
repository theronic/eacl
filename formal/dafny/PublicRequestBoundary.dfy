module PublicRequestBoundary {
  datatype RequestKey =
    | Subject
    | Permission
    | Resource
    | Consistency
    | CacheControl
    | ExecutionControl
    | MisspelledConsistency
    | UnknownRequestKey

  datatype Validation = Accepted | Rejected

  predicate KnownPointRequestKey(key: RequestKey) {
    key.Subject? || key.Permission? || key.Resource? ||
    key.Consistency? || key.CacheControl? || key.ExecutionControl?
  }

  predicate RequiredPointRequestKeysPresent(keys: set<RequestKey>) {
    Subject in keys && Permission in keys && Resource in keys
  }

  function ClosedPointRequest(keys: set<RequestKey>): Validation {
    if (forall key :: key in keys ==> KnownPointRequestKey(key)) &&
       RequiredPointRequestKeysPresent(keys)
    then Accepted
    else Rejected
  }

  // This models the former open-map behavior at the public boundary.
  function OpenPointRequest(keys: set<RequestKey>): Validation {
    Accepted
  }

  lemma OpenRequestAcceptsMisspelledConsistency()
    ensures OpenPointRequest({Subject, Permission, Resource,
                              MisspelledConsistency}) == Accepted
  {
  }

  lemma OpenRequestAcceptsMissingRequiredIdentity()
    ensures OpenPointRequest({Permission, Resource}) == Accepted
  {
  }

  lemma ClosedRequestRejectsMissingRequiredIdentity()
    ensures ClosedPointRequest({Permission, Resource}) == Rejected
    ensures ClosedPointRequest({Subject, Resource}) == Rejected
    ensures ClosedPointRequest({Subject, Permission}) == Rejected
  {
  }

  lemma ClosedRequestRejectsMisspelledConsistency()
    ensures ClosedPointRequest({Subject, Permission, Resource,
                                MisspelledConsistency}) == Rejected
  {
  }

  lemma ClosedRequestRejectsEveryUnknownKey(
    keys: set<RequestKey>,
    unknown: RequestKey
  )
    requires unknown in keys
    requires !KnownPointRequestKey(unknown)
    ensures ClosedPointRequest(keys) == Rejected
  {
  }

  datatype ExecutionControlValue =
    | OmittedControl
    | NilControl
    | ValidControl
    | ExplicitFalseControl
    | MalformedControl

  function TruthinessBasedExecutionControl(
    value: ExecutionControlValue
  ): Validation {
    if value.MalformedControl? then Rejected else Accepted
  }

  function ClosedExecutionControl(
    value: ExecutionControlValue
  ): Validation {
    if value.OmittedControl? || value.NilControl? || value.ValidControl?
    then Accepted
    else Rejected
  }

  lemma TruthinessAcceptsExplicitFalseExecutionControl()
    ensures TruthinessBasedExecutionControl(ExplicitFalseControl) == Accepted
  {
  }

  lemma ClosedExecutionControlRejectsExplicitFalse()
    ensures ClosedExecutionControl(ExplicitFalseControl) == Rejected
    ensures ClosedExecutionControl(MalformedControl) == Rejected
  {
  }

  datatype PageBasis = StableBasis | LiveBasis | MalformedBasis

  function ClosedPageBasis(basis: PageBasis): Validation {
    if basis.StableBasis? then Accepted else Rejected
  }

  lemma ReservedLivePageBasisIsRejected()
    ensures ClosedPageBasis(LiveBasis) == Rejected
    ensures ClosedPageBasis(MalformedBasis) == Rejected
  {
  }

  datatype SchemaWriteOption = OrphanPolicy | AllowEmptySchema

  function PublicSchemaWriteOption(option: SchemaWriteOption): Validation {
    if option.OrphanPolicy? then Accepted else Rejected
  }

  lemma BackendOnlyEmptySchemaEscapeHatchIsRejected()
    ensures PublicSchemaWriteOption(AllowEmptySchema) == Rejected
  {
  }

  datatype EndpointSubjectRelation =
    | NoSubjectRelation
    | UnsupportedSubjectRelation

  // EACL deliberately does not implement SpiceDB subject#relation usersets.
  // The old boundary admitted the field, after which identity resolution used
  // only type and id and thereby authorized a different, base object.
  function OpenEndpointSubjectRelation(
    relation: EndpointSubjectRelation
  ): Validation {
    Accepted
  }

  function StrictEndpointSubjectRelation(
    relation: EndpointSubjectRelation
  ): Validation {
    if relation.NoSubjectRelation? then Accepted else Rejected
  }

  lemma OpenEndpointAcceptedUnsupportedSubjectRelation()
    ensures OpenEndpointSubjectRelation(UnsupportedSubjectRelation) == Accepted
  {
  }

  lemma StrictEndpointRejectsUnsupportedSubjectRelation()
    ensures StrictEndpointSubjectRelation(NoSubjectRelation) == Accepted
    ensures StrictEndpointSubjectRelation(UnsupportedSubjectRelation) == Rejected
  {
  }

  datatype RelationshipWriteKey =
    | WriteOperation
    | WriteSubject
    | WriteRelation
    | WriteResource
    | WriteCaveat
    | WriteCaveatContext
    | WriteExpiry
    | MisspelledWriteExpiry

  predicate KnownRelationshipWriteKey(key: RelationshipWriteKey) {
    key.WriteOperation? || key.WriteSubject? || key.WriteRelation? ||
    key.WriteResource? || key.WriteCaveat? || key.WriteCaveatContext? ||
    key.WriteExpiry?
  }

  function ClosedRelationshipWrite(
    keys: set<RelationshipWriteKey>
  ): Validation {
    if forall key :: key in keys ==> KnownRelationshipWriteKey(key)
    then Accepted
    else Rejected
  }

  function OpenRelationshipWrite(
    keys: set<RelationshipWriteKey>
  ): Validation {
    Accepted
  }

  lemma OpenRelationshipWriteDropsMisspelledExpiry()
    ensures OpenRelationshipWrite(
              {WriteOperation, WriteSubject, WriteRelation, WriteResource,
               MisspelledWriteExpiry}) == Accepted
  {
  }

  lemma ClosedRelationshipWriteRejectsMisspelledExpiry()
    ensures ClosedRelationshipWrite(
              {WriteOperation, WriteSubject, WriteRelation, WriteResource,
               MisspelledWriteExpiry}) == Rejected
  {
  }

  datatype RelationshipBatchShape =
    | SequentialUpdates
    | SequentialMalformedUpdates
    | PageWithSequentialData
    | SingleRelationshipRecord
    | UpdateMapWithoutEnvelope
    | NilUpdates
    | UnknownEnvelopeField

  datatype MutationOutcome =
    | MutationPlanned
    | MutationRejected
    | MutationSucceededWithoutWork

  predicate ValidRelationshipBatch(shape: RelationshipBatchShape) {
    shape.SequentialUpdates? || shape.PageWithSequentialData?
  }

  function StrictMutation(shape: RelationshipBatchShape): MutationOutcome {
    if ValidRelationshipBatch(shape) then MutationPlanned
    else MutationRejected
  }

  // The former plural-delete coercion treated a Relationship record as a
  // page, read its absent data field as nil, and reported an empty success.
  function OpenDeleteMutation(
    shape: RelationshipBatchShape
  ): MutationOutcome {
    if shape.SingleRelationshipRecord? then MutationSucceededWithoutWork
    else if ValidRelationshipBatch(shape) then MutationPlanned
    else MutationRejected
  }

  lemma SingleRelationshipPreviouslyProducedEmptySuccess()
    ensures OpenDeleteMutation(SingleRelationshipRecord) ==
            MutationSucceededWithoutWork
  {
  }

  // The old shared wrapper checked only that the outer collection was
  // sequential. A protocol implementation could therefore receive a nested
  // update whose misspelled expiry was ignored as a permanent grant.
  function OpenNestedMutation(
    shape: RelationshipBatchShape
  ): MutationOutcome {
    if shape.SequentialMalformedUpdates? then MutationPlanned
    else StrictMutation(shape)
  }

  lemma OpenNestedMutationAcceptsMalformedUpdate()
    ensures OpenNestedMutation(SequentialMalformedUpdates) == MutationPlanned
  {
  }

  lemma StrictMutationRejectsMalformedNestedUpdate()
    ensures StrictMutation(SequentialMalformedUpdates) == MutationRejected
  {
  }

  lemma InvalidRelationshipBatchesNeverSucceed(
    shape: RelationshipBatchShape
  )
    requires !ValidRelationshipBatch(shape)
    ensures StrictMutation(shape) == MutationRejected
  {
  }

  datatype DeleteEntryPoint = PublicObjectDelete | ExplicitNativeDelete

  datatype DeleteEnvelope =
    | PublicObjectOnly
    | MalformedPublicObject
    | NativeEidOnly
    | PublicAndNativeIdentity
    | NoIdentity
    | UnknownDeleteField

  datatype DeleteDispatch =
    | ResolvePublicObject
    | DeleteNativeEntity
    | RejectDelete

  function StrictDeleteDispatch(
    entryPoint: DeleteEntryPoint,
    envelope: DeleteEnvelope
  ): DeleteDispatch {
    if entryPoint.PublicObjectDelete? && envelope.PublicObjectOnly? then
      ResolvePublicObject
    else if entryPoint.ExplicitNativeDelete? && envelope.NativeEidOnly? then
      DeleteNativeEntity
    else
      RejectDelete
  }

  lemma PublicDeleteCannotSelectNativeIdentity()
    ensures StrictDeleteDispatch(PublicObjectDelete, NativeEidOnly) ==
            RejectDelete
    ensures StrictDeleteDispatch(PublicObjectDelete,
                                 PublicAndNativeIdentity) == RejectDelete
  {
  }

  lemma NativeDeleteRequiresItsExplicitEntryPoint()
    ensures StrictDeleteDispatch(ExplicitNativeDelete, NativeEidOnly) ==
            DeleteNativeEntity
    ensures StrictDeleteDispatch(ExplicitNativeDelete, PublicObjectOnly) ==
            RejectDelete
  {
  }

  lemma AmbiguousDeleteIdentityIsAlwaysRejected(
    entryPoint: DeleteEntryPoint
  )
    ensures StrictDeleteDispatch(entryPoint, PublicAndNativeIdentity) ==
            RejectDelete
  {
  }

  lemma MalformedPublicObjectDeleteIsAlwaysRejected(
    entryPoint: DeleteEntryPoint
  )
    ensures StrictDeleteDispatch(entryPoint, MalformedPublicObject) ==
            RejectDelete
  {
  }
}
