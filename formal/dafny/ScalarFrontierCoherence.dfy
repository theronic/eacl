module ScalarFrontierCoherence {
  type Relation = nat
  type Generation = nat
  type Transaction = nat

  datatype RelationState = RelationState(
    generation: Generation,
    authorizationSlice: set<nat>
  )

  // `lifecycle` abstracts the runtime lineage key: the complete source scope
  // (backend, persisted or per-live-source id, branch) paired with the
  // operator-rotated source lifecycle. Equality is required before two
  // snapshots can inhabit one supported history.
  datatype Snapshot = Snapshot(
    lifecycle: nat,
    transaction: Transaction,
    schemaGeneration: Generation,
    schemaSemantics: set<nat>,
    relations: seq<RelationState>,
    stampedRelations: set<Relation>
  )

  function NatMax(left: nat, right: nat): nat {
    if left < right then right else left
  }

  function MaxGeneration(values: seq<Generation>): Generation
    decreases |values|
  {
    if |values| == 0 then
      0
    else
      NatMax(values[0], MaxGeneration(values[1..]))
  }

  lemma ValueIsAtMostMaximum(
    values: seq<Generation>,
    index: nat
  )
    requires index < |values|
    ensures values[index] <= MaxGeneration(values)
    decreases |values|
  {
    if index == 0 {
    } else {
      ValueIsAtMostMaximum(values[1..], index - 1);
    }
  }

  lemma MaximumBelowStrictBound(
    values: seq<Generation>,
    bound: Generation
  )
    requires 0 < bound
    requires forall index | 0 <= index < |values| ::
               values[index] < bound
    ensures MaxGeneration(values) < bound
    decreases |values|
  {
    if |values| != 0 {
      assert forall index | 0 <= index < |values[1..]| ::
          values[1..][index] < bound;
      MaximumBelowStrictBound(values[1..], bound);
    }
  }

  lemma MaximumMonotone(
    before: seq<Generation>,
    after: seq<Generation>
  )
    requires |before| == |after|
    requires forall index | 0 <= index < |before| ::
               before[index] <= after[index]
    ensures MaxGeneration(before) <= MaxGeneration(after)
    decreases |before|
  {
    if |before| != 0 {
      MaximumMonotone(before[1..], after[1..]);
    }
  }

  // A checked witness for the loophole in a scalar frontier backed only by
  // independent per-relation monotonicity. Relation 1 changes from 5 to 7,
  // yet relation 0's older value 10 hides that mutation from the maximum.
  lemma IndependentMonotonicityHasAFrontierCollision()
    ensures forall index | 0 <= index < 2 ::
              [10, 5][index] <= [10, 7][index]
    ensures [10, 5][1] < [10, 7][1]
    ensures MaxGeneration([10, 5]) == MaxGeneration([10, 7])
  {
  }

  predicate DependencySlotsValid(
    snapshot: Snapshot,
    dependencies: seq<Relation>
  ) {
    (forall position | 0 <= position < |dependencies| ::
       dependencies[position] < |snapshot.relations|) &&
    (forall left, right |
       0 <= left < right < |dependencies| ::
       dependencies[left] != dependencies[right])
  }

  function RelationAt(
    snapshot: Snapshot,
    relation: Relation
  ): RelationState {
    if relation < |snapshot.relations| then
      snapshot.relations[relation]
    else
      RelationState(0, {})
  }

  lemma ValidDependencyPosition(
    snapshot: Snapshot,
    dependencies: seq<Relation>,
    position: nat
  )
    requires DependencySlotsValid(snapshot, dependencies)
    requires position < |dependencies|
    ensures dependencies[position] < |snapshot.relations|
    ensures RelationAt(snapshot, dependencies[position]) ==
            snapshot.relations[dependencies[position]]
  {
  }

  predicate DependencySlotsValidForHistory(
    history: seq<Snapshot>,
    dependencies: seq<Relation>
  ) {
    forall snapshot <- history ::
      DependencySlotsValid(snapshot, dependencies)
  }

  lemma DependencySlotsValidSuffix(
    snapshot: Snapshot,
    dependencies: seq<Relation>
  )
    requires DependencySlotsValid(snapshot, dependencies)
    requires 0 < |dependencies|
    ensures DependencySlotsValid(snapshot, dependencies[1..])
  {
    forall position | 0 <= position < |dependencies[1..]|
      ensures dependencies[1..][position] < |snapshot.relations|
    {
      assert dependencies[1..][position] == dependencies[position + 1];
    }
    forall left, right |
      0 <= left < right < |dependencies[1..]|
      ensures dependencies[1..][left] != dependencies[1..][right]
    {
      assert dependencies[1..][left] == dependencies[left + 1];
      assert dependencies[1..][right] == dependencies[right + 1];
    }
  }

  lemma DependencySlotsValidAcrossEqualLayout(
    before: Snapshot,
    after: Snapshot,
    dependencies: seq<Relation>
  )
    requires |before.relations| == |after.relations|
    requires DependencySlotsValid(before, dependencies)
    ensures DependencySlotsValid(after, dependencies)
  {
  }

  lemma DependencySlotsValidThroughoutHistory(
    history: seq<Snapshot>,
    dependencies: seq<Relation>
  )
    requires OrderedSupportedHistory(history)
    requires DependencySlotsValid(history[0], dependencies)
    ensures DependencySlotsValidForHistory(history, dependencies)
    decreases |history|
  {
    if |history| > 1 {
      var prefix := history[..|history| - 1];
      assert OrderedSupportedHistory(prefix);
      DependencySlotsValidThroughoutHistory(prefix, dependencies);
      DependencySlotsValidAcrossEqualLayout(
        history[|history| - 2],
        history[|history| - 1],
        dependencies
      );
    }
  }

  function DependencyGenerations(
    snapshot: Snapshot,
    dependencies: seq<Relation>
  ): seq<Generation>
    ensures |DependencyGenerations(snapshot, dependencies)| ==
            |dependencies|
    decreases |dependencies|
  {
    if |dependencies| == 0 then
      []
    else
      [RelationAt(snapshot, dependencies[0]).generation] +
      DependencyGenerations(snapshot, dependencies[1..])
  }

  function DependencyFrontier(
    snapshot: Snapshot,
    dependencies: seq<Relation>
  ): Generation
  {
    MaxGeneration(DependencyGenerations(snapshot, dependencies))
  }

  function DependencyProjection(
    snapshot: Snapshot,
    dependencies: seq<Relation>
  ): seq<set<nat>>
    ensures |DependencyProjection(snapshot, dependencies)| ==
            |dependencies|
    decreases |dependencies|
  {
    if |dependencies| == 0 then
      []
    else
      [RelationAt(snapshot, dependencies[0]).authorizationSlice] +
      DependencyProjection(snapshot, dependencies[1..])
  }

  lemma DependencyGenerationAtPosition(
    snapshot: Snapshot,
    dependencies: seq<Relation>,
    position: nat
  )
    requires DependencySlotsValid(snapshot, dependencies)
    requires position < |dependencies|
    ensures DependencyGenerations(snapshot, dependencies)[position] ==
            RelationAt(snapshot, dependencies[position]).generation
    decreases |dependencies|
  {
    if position != 0 {
      DependencySlotsValidSuffix(snapshot, dependencies);
      DependencyGenerationAtPosition(
        snapshot,
        dependencies[1..],
        position - 1
      );
    }
  }

  lemma DependencyGenerationIsAtMostFrontier(
    snapshot: Snapshot,
    dependencies: seq<Relation>,
    position: nat
  )
    requires DependencySlotsValid(snapshot, dependencies)
    requires position < |dependencies|
    ensures RelationAt(snapshot, dependencies[position]).generation <=
            DependencyFrontier(snapshot, dependencies)
  {
    DependencyGenerationAtPosition(snapshot, dependencies, position);
    ValueIsAtMostMaximum(
      DependencyGenerations(snapshot, dependencies),
      position
    );
  }

  // One supported committed transaction supplies the generation for every
  // relation it stamps. Its native transaction is globally later than every
  // relation and schema generation visible in the immutable prior snapshot.
  predicate OrderedSupportedStep(before: Snapshot, after: Snapshot) {
    before.lifecycle == after.lifecycle &&
    before.transaction < after.transaction &&
    |before.relations| == |after.relations| &&
    (forall relation <- after.stampedRelations ::
       relation < |after.relations|) &&
    before.schemaGeneration < after.transaction &&
    (forall relation | 0 <= relation < |before.relations| ::
       before.relations[relation].generation < after.transaction) &&
    (if before.schemaSemantics == after.schemaSemantics then
       after.schemaGeneration == before.schemaGeneration
     else
       after.schemaGeneration == after.transaction) &&
    (forall relation | 0 <= relation < |before.relations| ::
       if relation in after.stampedRelations then
         after.relations[relation].generation == after.transaction
       else
         after.relations[relation] == before.relations[relation])
  }

  predicate OrderedSupportedHistory(history: seq<Snapshot>) {
    0 < |history| &&
    (forall index | 0 <= index < |history| - 1 ::
       OrderedSupportedStep(history[index], history[index + 1]))
  }

  lemma StepRelationGenerationMonotone(
    before: Snapshot,
    after: Snapshot,
    relation: Relation
  )
    requires OrderedSupportedStep(before, after)
    requires relation < |before.relations|
    ensures before.relations[relation].generation <=
            after.relations[relation].generation
  {
  }

  lemma StepSchemaGenerationMonotone(
    before: Snapshot,
    after: Snapshot
  )
    requires OrderedSupportedStep(before, after)
    ensures before.schemaGeneration <= after.schemaGeneration
  {
  }

  lemma StepSchemaEqualityPreservesSemantics(
    before: Snapshot,
    after: Snapshot
  )
    requires OrderedSupportedStep(before, after)
    requires before.schemaGeneration == after.schemaGeneration
    ensures before.schemaSemantics == after.schemaSemantics
  {
  }

  lemma DependencyGenerationsMonotoneAcrossStep(
    before: Snapshot,
    after: Snapshot,
    dependencies: seq<Relation>
  )
    requires OrderedSupportedStep(before, after)
    requires DependencySlotsValid(before, dependencies)
    requires DependencySlotsValid(after, dependencies)
    ensures forall position | 0 <= position < |dependencies| ::
              DependencyGenerations(before, dependencies)[position] <=
              DependencyGenerations(after, dependencies)[position]
  {
    forall position | 0 <= position < |dependencies|
      ensures DependencyGenerations(before, dependencies)[position] <=
              DependencyGenerations(after, dependencies)[position]
    {
      ValidDependencyPosition(before, dependencies, position);
      ValidDependencyPosition(after, dependencies, position);
      DependencyGenerationAtPosition(before, dependencies, position);
      DependencyGenerationAtPosition(after, dependencies, position);
      StepRelationGenerationMonotone(
        before,
        after,
        dependencies[position]
      );
    }
  }

  lemma FrontierMonotoneAcrossStep(
    before: Snapshot,
    after: Snapshot,
    dependencies: seq<Relation>
  )
    requires OrderedSupportedStep(before, after)
    requires DependencySlotsValid(before, dependencies)
    requires DependencySlotsValid(after, dependencies)
    ensures DependencyFrontier(before, dependencies) <=
            DependencyFrontier(after, dependencies)
  {
    DependencyGenerationsMonotoneAcrossStep(before, after, dependencies);
    MaximumMonotone(
      DependencyGenerations(before, dependencies),
      DependencyGenerations(after, dependencies)
    );
  }

  lemma FrontierBeforeStepIsBelowTransaction(
    before: Snapshot,
    after: Snapshot,
    dependencies: seq<Relation>
  )
    requires OrderedSupportedStep(before, after)
    requires DependencySlotsValid(before, dependencies)
    ensures DependencyFrontier(before, dependencies) < after.transaction
  {
    assert 0 < after.transaction;
    assert forall position |
        0 <= position <
        |DependencyGenerations(before, dependencies)| ::
        DependencyGenerations(before, dependencies)[position] <
        after.transaction by {
      forall position | 0 <= position < |dependencies|
        ensures DependencyGenerations(before, dependencies)[position] <
                after.transaction
      {
        ValidDependencyPosition(before, dependencies, position);
        DependencyGenerationAtPosition(before, dependencies, position);
      }
    }
    MaximumBelowStrictBound(
      DependencyGenerations(before, dependencies),
      after.transaction
    );
  }

  lemma FrontierAfterStepIsAtMostTransaction(
    before: Snapshot,
    after: Snapshot,
    dependencies: seq<Relation>
  )
    requires OrderedSupportedStep(before, after)
    requires DependencySlotsValid(before, dependencies)
    requires DependencySlotsValid(after, dependencies)
    ensures DependencyFrontier(after, dependencies) <= after.transaction
  {
    assert forall position |
        0 <= position <
        |DependencyGenerations(after, dependencies)| ::
        DependencyGenerations(after, dependencies)[position] <=
        after.transaction by {
      forall position | 0 <= position < |dependencies|
        ensures DependencyGenerations(after, dependencies)[position] <=
                after.transaction
      {
        var relation := dependencies[position];
        ValidDependencyPosition(before, dependencies, position);
        ValidDependencyPosition(after, dependencies, position);
        DependencyGenerationAtPosition(after, dependencies, position);
        if relation !in after.stampedRelations {
          assert after.relations[relation] == before.relations[relation];
        }
      }
    }
    if after.transaction == 0 {
      assert false;
    }
    MaximumBelowStrictBound(
      DependencyGenerations(after, dependencies),
      after.transaction + 1
    );
  }

  lemma StampedDependencySetsTheNewFrontier(
    before: Snapshot,
    after: Snapshot,
    dependencies: seq<Relation>,
    position: nat
  )
    requires OrderedSupportedStep(before, after)
    requires DependencySlotsValid(before, dependencies)
    requires DependencySlotsValid(after, dependencies)
    requires position < |dependencies|
    requires dependencies[position] in after.stampedRelations
    ensures DependencyFrontier(after, dependencies) == after.transaction
    ensures DependencyFrontier(before, dependencies) <
            DependencyFrontier(after, dependencies)
  {
    DependencyGenerationIsAtMostFrontier(after, dependencies, position);
    FrontierAfterStepIsAtMostTransaction(before, after, dependencies);
    FrontierBeforeStepIsBelowTransaction(before, after, dependencies);
  }

  lemma StampedDependencyAdvancesEvenWhenSliceIsUnchanged(
    before: Snapshot,
    after: Snapshot,
    dependencies: seq<Relation>,
    position: nat
  )
    requires OrderedSupportedStep(before, after)
    requires DependencySlotsValid(before, dependencies)
    requires DependencySlotsValid(after, dependencies)
    requires position < |dependencies|
    requires dependencies[position] in after.stampedRelations
    requires RelationAt(
               before,
               dependencies[position]
             ).authorizationSlice ==
             RelationAt(
               after,
               dependencies[position]
             ).authorizationSlice
    ensures DependencyFrontier(before, dependencies) <
            DependencyFrontier(after, dependencies)
  {
    StampedDependencySetsTheNewFrontier(
      before,
      after,
      dependencies,
      position
    );
  }

  lemma SeveralStampedDependenciesShareOneFrontier(
    before: Snapshot,
    after: Snapshot,
    dependencies: seq<Relation>,
    first: nat,
    second: nat
  )
    requires OrderedSupportedStep(before, after)
    requires DependencySlotsValid(before, dependencies)
    requires DependencySlotsValid(after, dependencies)
    requires first < |dependencies|
    requires second < |dependencies|
    requires dependencies[first] in after.stampedRelations
    requires dependencies[second] in after.stampedRelations
    ensures RelationAt(
              after,
              dependencies[first]
            ).generation == after.transaction
    ensures RelationAt(
              after,
              dependencies[second]
            ).generation == after.transaction
    ensures DependencyFrontier(after, dependencies) == after.transaction
  {
    ValidDependencyPosition(after, dependencies, first);
    ValidDependencyPosition(after, dependencies, second);
    StampedDependencySetsTheNewFrontier(
      before,
      after,
      dependencies,
      first
    );
  }

  lemma RepeatedRelationStampIsIdempotent(
    stamped: set<Relation>,
    relation: Relation
  )
    ensures stamped + {relation} + {relation} == stamped + {relation}
  {
  }

  lemma SchemaMutationAdvancesItsGeneration(
    before: Snapshot,
    after: Snapshot
  )
    requires OrderedSupportedStep(before, after)
    requires before.schemaSemantics != after.schemaSemantics
    ensures after.schemaGeneration == after.transaction
    ensures before.schemaGeneration < after.schemaGeneration
  {
  }

  lemma EqualFrontierStepPreservesDependencyStates(
    before: Snapshot,
    after: Snapshot,
    dependencies: seq<Relation>
  )
    requires OrderedSupportedStep(before, after)
    requires DependencySlotsValid(before, dependencies)
    requires DependencySlotsValid(after, dependencies)
    requires DependencyFrontier(before, dependencies) ==
             DependencyFrontier(after, dependencies)
    ensures forall position | 0 <= position < |dependencies| ::
              RelationAt(before, dependencies[position]) ==
              RelationAt(after, dependencies[position])
  {
    forall position | 0 <= position < |dependencies|
      ensures RelationAt(before, dependencies[position]) ==
              RelationAt(after, dependencies[position])
    {
      ValidDependencyPosition(before, dependencies, position);
      ValidDependencyPosition(after, dependencies, position);
      if dependencies[position] in after.stampedRelations {
        StampedDependencySetsTheNewFrontier(
          before,
          after,
          dependencies,
          position
        );
        assert false;
      } else {
        assert after.relations[dependencies[position]] ==
               before.relations[dependencies[position]];
      }
    }
  }

  lemma FrontierMonotoneAcrossHistory(
    history: seq<Snapshot>,
    dependencies: seq<Relation>
  )
    requires OrderedSupportedHistory(history)
    requires DependencySlotsValidForHistory(history, dependencies)
    ensures DependencyFrontier(history[0], dependencies) <=
            DependencyFrontier(history[|history| - 1], dependencies)
    decreases |history|
  {
    if |history| > 1 {
      var prefix := history[..|history| - 1];
      assert OrderedSupportedHistory(prefix);
      assert DependencySlotsValidForHistory(prefix, dependencies);
      FrontierMonotoneAcrossHistory(prefix, dependencies);
      FrontierMonotoneAcrossStep(
        history[|history| - 2],
        history[|history| - 1],
        dependencies
      );
    }
  }

  lemma EqualFrontierHistoryPreservesDependencyStates(
    history: seq<Snapshot>,
    dependencies: seq<Relation>
  )
    requires OrderedSupportedHistory(history)
    requires DependencySlotsValidForHistory(history, dependencies)
    requires DependencyFrontier(history[0], dependencies) ==
             DependencyFrontier(history[|history| - 1], dependencies)
    ensures forall position | 0 <= position < |dependencies| ::
              RelationAt(history[0], dependencies[position]) ==
              RelationAt(
                history[|history| - 1],
                dependencies[position]
              )
    decreases |history|
  {
    if |history| > 1 {
      var prefix := history[..|history| - 1];
      assert OrderedSupportedHistory(prefix);
      assert DependencySlotsValidForHistory(prefix, dependencies);
      FrontierMonotoneAcrossHistory(prefix, dependencies);
      FrontierMonotoneAcrossStep(
        history[|history| - 2],
        history[|history| - 1],
        dependencies
      );
      assert DependencyFrontier(history[0], dependencies) <=
             DependencyFrontier(history[|history| - 2], dependencies) <=
             DependencyFrontier(history[|history| - 1], dependencies);
      assert DependencyFrontier(history[0], dependencies) ==
             DependencyFrontier(history[|history| - 2], dependencies);
      assert DependencyFrontier(history[|history| - 2], dependencies) ==
             DependencyFrontier(history[|history| - 1], dependencies);
      EqualFrontierHistoryPreservesDependencyStates(prefix, dependencies);
      EqualFrontierStepPreservesDependencyStates(
        history[|history| - 2],
        history[|history| - 1],
        dependencies
      );
    }
  }

  lemma SchemaGenerationMonotoneAcrossHistory(history: seq<Snapshot>)
    requires OrderedSupportedHistory(history)
    ensures history[0].schemaGeneration <=
            history[|history| - 1].schemaGeneration
    decreases |history|
  {
    if |history| > 1 {
      var prefix := history[..|history| - 1];
      assert OrderedSupportedHistory(prefix);
      SchemaGenerationMonotoneAcrossHistory(prefix);
      StepSchemaGenerationMonotone(
        history[|history| - 2],
        history[|history| - 1]
      );
    }
  }

  lemma EqualSchemaGenerationPreservesSemantics(history: seq<Snapshot>)
    requires OrderedSupportedHistory(history)
    requires history[0].schemaGeneration ==
             history[|history| - 1].schemaGeneration
    ensures history[0].schemaSemantics ==
            history[|history| - 1].schemaSemantics
    decreases |history|
  {
    if |history| > 1 {
      var prefix := history[..|history| - 1];
      assert OrderedSupportedHistory(prefix);
      SchemaGenerationMonotoneAcrossHistory(prefix);
      StepSchemaGenerationMonotone(
        history[|history| - 2],
        history[|history| - 1]
      );
      assert history[0].schemaGeneration <=
             history[|history| - 2].schemaGeneration <=
             history[|history| - 1].schemaGeneration;
      assert history[0].schemaGeneration ==
             history[|history| - 2].schemaGeneration;
      assert history[|history| - 2].schemaGeneration ==
             history[|history| - 1].schemaGeneration;
      EqualSchemaGenerationPreservesSemantics(prefix);
      StepSchemaEqualityPreservesSemantics(
        history[|history| - 2],
        history[|history| - 1]
      );
    }
  }

  lemma EqualDependencyStatesPreserveProjection(
    before: Snapshot,
    after: Snapshot,
    dependencies: seq<Relation>
  )
    requires DependencySlotsValid(before, dependencies)
    requires DependencySlotsValid(after, dependencies)
    requires forall position | 0 <= position < |dependencies| ::
               RelationAt(before, dependencies[position]) ==
               RelationAt(after, dependencies[position])
    ensures DependencyProjection(before, dependencies) ==
            DependencyProjection(after, dependencies)
    decreases |dependencies|
  {
    if |dependencies| != 0 {
      DependencySlotsValidSuffix(before, dependencies);
      DependencySlotsValidSuffix(after, dependencies);
      EqualDependencyStatesPreserveProjection(
        before,
        after,
        dependencies[1..]
      );
    }
  }

  lemma EqualDependencyStatesPreserveGenerations(
    before: Snapshot,
    after: Snapshot,
    dependencies: seq<Relation>
  )
    requires DependencySlotsValid(before, dependencies)
    requires DependencySlotsValid(after, dependencies)
    requires forall position | 0 <= position < |dependencies| ::
               RelationAt(before, dependencies[position]) ==
               RelationAt(after, dependencies[position])
    ensures DependencyGenerations(before, dependencies) ==
            DependencyGenerations(after, dependencies)
    decreases |dependencies|
  {
    if |dependencies| != 0 {
      DependencySlotsValidSuffix(before, dependencies);
      DependencySlotsValidSuffix(after, dependencies);
      EqualDependencyStatesPreserveGenerations(
        before,
        after,
        dependencies[1..]
      );
    }
  }

  datatype Demand =
    | AllowedDemand
    | CountDemand(limit: nat)
    | PageDemand(limit: nat, direction: bool, internalBound: nat)

  datatype NormalizedRequest = NormalizedRequest(
    operation: nat,
    semanticQuery: nat,
    subject: nat,
    resource: nat,
    selectedIdentityFrame: seq<nat>,
    demand: Demand
  )

  datatype AuthorizationInput = AuthorizationInput(
    schemaSemantics: set<nat>,
    dependencyProjection: seq<set<nat>>,
    request: NormalizedRequest
  )

  datatype AuthorizationAnswer = AuthorizationAnswer(
    allowed: bool,
    count: nat,
    values: seq<nat>
  )

  datatype DependencyPlanInput = DependencyPlanInput(
    schemaSemantics: set<nat>,
    request: NormalizedRequest
  )

  lemma EqualPlanInputsSelectEqualDependencies(
    extractor: DependencyPlanInput -> seq<Relation>,
    cachedInput: DependencyPlanInput,
    selectedInput: DependencyPlanInput
  )
    requires cachedInput == selectedInput
    ensures extractor(cachedInput) == extractor(selectedInput)
  {
  }

  function InputAt(
    snapshot: Snapshot,
    dependencies: seq<Relation>,
    request: NormalizedRequest
  ): AuthorizationInput
    requires DependencySlotsValid(snapshot, dependencies)
  {
    AuthorizationInput(
      snapshot.schemaSemantics,
      DependencyProjection(snapshot, dependencies),
      request
    )
  }

  // This is the scalar refinement theorem consumed by the existing verified
  // evaluator frame: any deterministic authorization semantics sees identical
  // schema, relevant relationship slices, identity frame, and normalized
  // demand when the scalar managed proof matches.
  lemma EqualScalarProofPreservesAuthorizationInput(
    history: seq<Snapshot>,
    dependencies: seq<Relation>,
    cachedRequest: NormalizedRequest,
    selectedRequest: NormalizedRequest
  )
    requires OrderedSupportedHistory(history)
    requires DependencySlotsValidForHistory(history, dependencies)
    requires history[0].lifecycle == history[|history| - 1].lifecycle
    requires history[0].schemaGeneration ==
             history[|history| - 1].schemaGeneration
    requires DependencyFrontier(history[0], dependencies) ==
             DependencyFrontier(history[|history| - 1], dependencies)
    requires cachedRequest == selectedRequest
    ensures InputAt(history[0], dependencies, cachedRequest) ==
            InputAt(
              history[|history| - 1],
              dependencies,
              selectedRequest
            )
  {
    EqualSchemaGenerationPreservesSemantics(history);
    EqualFrontierHistoryPreservesDependencyStates(history, dependencies);
    EqualDependencyStatesPreserveProjection(
      history[0],
      history[|history| - 1],
      dependencies
    );
  }

  lemma EqualScalarProofPreservesEveryDeterministicDenotation(
    history: seq<Snapshot>,
    dependencies: seq<Relation>,
    cachedRequest: NormalizedRequest,
    selectedRequest: NormalizedRequest,
    evaluator: AuthorizationInput -> AuthorizationAnswer
  )
    requires OrderedSupportedHistory(history)
    requires DependencySlotsValidForHistory(history, dependencies)
    requires history[0].lifecycle == history[|history| - 1].lifecycle
    requires history[0].schemaGeneration ==
             history[|history| - 1].schemaGeneration
    requires DependencyFrontier(history[0], dependencies) ==
             DependencyFrontier(history[|history| - 1], dependencies)
    requires cachedRequest == selectedRequest
    ensures evaluator(
              InputAt(history[0], dependencies, cachedRequest)
            ) ==
            evaluator(
              InputAt(
                history[|history| - 1],
                dependencies,
                selectedRequest
              )
            )
  {
    EqualScalarProofPreservesAuthorizationInput(
      history,
      dependencies,
      cachedRequest,
      selectedRequest
    );
  }

  lemma EqualScalarProofAlsoPreservesAnOlderSelectedSnapshot(
    history: seq<Snapshot>,
    dependencies: seq<Relation>,
    newerCachedRequest: NormalizedRequest,
    olderSelectedRequest: NormalizedRequest,
    evaluator: AuthorizationInput -> AuthorizationAnswer
  )
    requires OrderedSupportedHistory(history)
    requires DependencySlotsValidForHistory(history, dependencies)
    requires history[0].lifecycle == history[|history| - 1].lifecycle
    requires history[0].schemaGeneration ==
             history[|history| - 1].schemaGeneration
    requires DependencyFrontier(history[0], dependencies) ==
             DependencyFrontier(history[|history| - 1], dependencies)
    requires newerCachedRequest == olderSelectedRequest
    ensures evaluator(
              InputAt(
                history[|history| - 1],
                dependencies,
                newerCachedRequest
              )
            ) ==
            evaluator(
              InputAt(
                history[0],
                dependencies,
                olderSelectedRequest
              )
            )
  {
    EqualScalarProofPreservesEveryDeterministicDenotation(
      history,
      dependencies,
      olderSelectedRequest,
      newerCachedRequest,
      evaluator
    );
  }

  lemma EmptyDependencyScalarProofUsesInitialFrontier(
    snapshot: Snapshot
  )
    ensures DependencySlotsValid(snapshot, [])
    ensures DependencyFrontier(snapshot, []) == 0
    ensures DependencyProjection(snapshot, []) == []
  {
  }

  lemma UnrelatedSupportedStepPreservesFrontierAndProjection(
    before: Snapshot,
    after: Snapshot,
    dependencies: seq<Relation>
  )
    requires OrderedSupportedStep(before, after)
    requires DependencySlotsValid(before, dependencies)
    requires DependencySlotsValid(after, dependencies)
    requires forall relation <- dependencies ::
               relation !in after.stampedRelations
    ensures DependencyFrontier(before, dependencies) ==
            DependencyFrontier(after, dependencies)
    ensures DependencyProjection(before, dependencies) ==
            DependencyProjection(after, dependencies)
  {
    assert forall position | 0 <= position < |dependencies| ::
        RelationAt(before, dependencies[position]) ==
        RelationAt(after, dependencies[position]);
    EqualDependencyStatesPreserveGenerations(
      before,
      after,
      dependencies
    );
    EqualDependencyStatesPreserveProjection(before, after, dependencies);
  }

  datatype ProofStatus =
    | CompleteProof
    | MissingProof
    | MalformedProof
    | OversizedProof
    | FailedProof

  datatype DependencyClass =
    | SchemaDependency
    | RelationshipDependency
    | IdentityDependency
    | EntityLivenessDependency
    | FutureDependency

  datatype ProofFrame = ProofFrame(
    adapterIdentity: nat,
    lifecycle: nat,
    snapshotIdentity: nat,
    schemaGeneration: Generation,
    dependencies: seq<Relation>,
    generations: seq<Generation>,
    provedClasses: set<DependencyClass>,
    status: ProofStatus
  )

  predicate CanonicalFrame(frame: ProofFrame) {
    |frame.dependencies| == |frame.generations| &&
    (forall left, right |
       0 <= left < right < |frame.dependencies| ::
       frame.dependencies[left] < frame.dependencies[right])
  }

  predicate CompleteFrame(
    frame: ProofFrame,
    requiredClasses: set<DependencyClass>
  ) {
    frame.status.CompleteProof? &&
    CanonicalFrame(frame) &&
    requiredClasses <= frame.provedClasses
  }

  predicate FrameValidForSnapshot(
    frame: ProofFrame,
    snapshot: Snapshot,
    adapterIdentity: nat,
    snapshotIdentity: nat,
    requiredClasses: set<DependencyClass>,
    request: NormalizedRequest,
    extractor: DependencyPlanInput -> seq<Relation>
  ) {
    CompleteFrame(frame, requiredClasses) &&
    frame.adapterIdentity == adapterIdentity &&
    frame.lifecycle == snapshot.lifecycle &&
    frame.snapshotIdentity == snapshotIdentity &&
    frame.schemaGeneration == snapshot.schemaGeneration &&
    frame.dependencies == extractor(
      DependencyPlanInput(snapshot.schemaSemantics, request)
    ) &&
    DependencySlotsValid(snapshot, frame.dependencies) &&
    frame.generations ==
    DependencyGenerations(snapshot, frame.dependencies)
  }

  function FrameFrontier(frame: ProofFrame): Generation {
    MaxGeneration(frame.generations)
  }

  predicate FramesCanMatch(
    cached: ProofFrame,
    selected: ProofFrame,
    requiredClasses: set<DependencyClass>
  ) {
    CompleteFrame(cached, requiredClasses) &&
    CompleteFrame(selected, requiredClasses) &&
    cached.adapterIdentity == selected.adapterIdentity &&
    cached.lifecycle == selected.lifecycle &&
    cached.schemaGeneration == selected.schemaGeneration &&
    FrameFrontier(cached) == FrameFrontier(selected)
  }

  lemma IncompleteFrameCannotMatch(
    cached: ProofFrame,
    selected: ProofFrame,
    requiredClasses: set<DependencyClass>
  )
    requires !CompleteFrame(cached, requiredClasses) ||
             !CompleteFrame(selected, requiredClasses)
    ensures !FramesCanMatch(cached, selected, requiredClasses)
  {
  }

  lemma CrossAdapterFrameCannotMatch(
    cached: ProofFrame,
    selected: ProofFrame,
    requiredClasses: set<DependencyClass>
  )
    requires cached.adapterIdentity != selected.adapterIdentity
    ensures !FramesCanMatch(cached, selected, requiredClasses)
  {
  }

  lemma CrossLifecycleFrameCannotMatch(
    cached: ProofFrame,
    selected: ProofFrame,
    requiredClasses: set<DependencyClass>
  )
    requires cached.lifecycle != selected.lifecycle
    ensures !FramesCanMatch(cached, selected, requiredClasses)
  {
  }

  lemma DifferentSchemaGenerationCannotMatch(
    cached: ProofFrame,
    selected: ProofFrame,
    requiredClasses: set<DependencyClass>
  )
    requires cached.schemaGeneration != selected.schemaGeneration
    ensures !FramesCanMatch(cached, selected, requiredClasses)
  {
  }

  lemma DifferentDependencyFrontierCannotMatch(
    cached: ProofFrame,
    selected: ProofFrame,
    requiredClasses: set<DependencyClass>
  )
    requires FrameFrontier(cached) != FrameFrontier(selected)
    ensures !FramesCanMatch(cached, selected, requiredClasses)
  {
  }

  predicate SubproblemCovered(
    frame: ProofFrame,
    subproblemDependencies: set<Relation>
  ) {
    subproblemDependencies <= set relation | relation in frame.dependencies
  }

  predicate SubproblemCanReuse(
    cached: ProofFrame,
    selected: ProofFrame,
    requiredClasses: set<DependencyClass>,
    subproblemDependencies: set<Relation>
  ) {
    FramesCanMatch(cached, selected, requiredClasses) &&
    cached.dependencies == selected.dependencies &&
    SubproblemCovered(cached, subproblemDependencies) &&
    SubproblemCovered(selected, subproblemDependencies)
  }

  lemma OutOfFrameSubproblemCannotReuse(
    cached: ProofFrame,
    selected: ProofFrame,
    requiredClasses: set<DependencyClass>,
    subproblemDependencies: set<Relation>
  )
    requires !SubproblemCovered(cached, subproblemDependencies) ||
             !SubproblemCovered(selected, subproblemDependencies)
    ensures !SubproblemCanReuse(
              cached,
              selected,
              requiredClasses,
              subproblemDependencies
            )
  {
  }

  lemma FutureUnprovedDependencyCannotMatch(
    cached: ProofFrame,
    selected: ProofFrame
  )
    requires FutureDependency !in cached.provedClasses ||
             FutureDependency !in selected.provedClasses
    ensures !FramesCanMatch(
              cached,
              selected,
              {FutureDependency}
            )
  {
  }

  lemma MatchingSnapshotFramesPreserveAuthorizationInput(
    history: seq<Snapshot>,
    cachedFrame: ProofFrame,
    selectedFrame: ProofFrame,
    cachedRequest: NormalizedRequest,
    selectedRequest: NormalizedRequest,
    extractor: DependencyPlanInput -> seq<Relation>,
    adapterIdentity: nat,
    cachedSnapshotIdentity: nat,
    selectedSnapshotIdentity: nat,
    requiredClasses: set<DependencyClass>
  )
    requires OrderedSupportedHistory(history)
    requires FrameValidForSnapshot(
               cachedFrame,
               history[0],
               adapterIdentity,
               cachedSnapshotIdentity,
               requiredClasses,
               cachedRequest,
               extractor
             )
    requires FrameValidForSnapshot(
               selectedFrame,
               history[|history| - 1],
               adapterIdentity,
               selectedSnapshotIdentity,
               requiredClasses,
               selectedRequest,
               extractor
             )
    requires FramesCanMatch(
               cachedFrame,
               selectedFrame,
               requiredClasses
             )
    requires cachedRequest == selectedRequest
    ensures InputAt(
              history[0],
              cachedFrame.dependencies,
              cachedRequest
            ) ==
            InputAt(
              history[|history| - 1],
              selectedFrame.dependencies,
              selectedRequest
            )
  {
    EqualSchemaGenerationPreservesSemantics(history);
    var cachedPlan := DependencyPlanInput(
      history[0].schemaSemantics,
      cachedRequest
    );
    var selectedPlan := DependencyPlanInput(
      history[|history| - 1].schemaSemantics,
      selectedRequest
    );
    assert cachedPlan == selectedPlan;
    EqualPlanInputsSelectEqualDependencies(
      extractor,
      cachedPlan,
      selectedPlan
    );
    assert cachedFrame.dependencies == selectedFrame.dependencies;
    DependencySlotsValidThroughoutHistory(
      history,
      cachedFrame.dependencies
    );
    EqualScalarProofPreservesAuthorizationInput(
      history,
      cachedFrame.dependencies,
      cachedRequest,
      selectedRequest
    );
  }

  lemma MatchingSnapshotFramesPreserveEveryDeterministicDenotation(
    history: seq<Snapshot>,
    cachedFrame: ProofFrame,
    selectedFrame: ProofFrame,
    cachedRequest: NormalizedRequest,
    selectedRequest: NormalizedRequest,
    extractor: DependencyPlanInput -> seq<Relation>,
    evaluator: AuthorizationInput -> AuthorizationAnswer,
    adapterIdentity: nat,
    cachedSnapshotIdentity: nat,
    selectedSnapshotIdentity: nat,
    requiredClasses: set<DependencyClass>
  )
    requires OrderedSupportedHistory(history)
    requires FrameValidForSnapshot(
               cachedFrame,
               history[0],
               adapterIdentity,
               cachedSnapshotIdentity,
               requiredClasses,
               cachedRequest,
               extractor
             )
    requires FrameValidForSnapshot(
               selectedFrame,
               history[|history| - 1],
               adapterIdentity,
               selectedSnapshotIdentity,
               requiredClasses,
               selectedRequest,
               extractor
             )
    requires FramesCanMatch(
               cachedFrame,
               selectedFrame,
               requiredClasses
             )
    requires cachedRequest == selectedRequest
    ensures evaluator(
              InputAt(
                history[0],
                cachedFrame.dependencies,
                cachedRequest
              )
            ) ==
            evaluator(
              InputAt(
                history[|history| - 1],
                selectedFrame.dependencies,
                selectedRequest
              )
            )
  {
    MatchingSnapshotFramesPreserveAuthorizationInput(
      history,
      cachedFrame,
      selectedFrame,
      cachedRequest,
      selectedRequest,
      extractor,
      adapterIdentity,
      cachedSnapshotIdentity,
      selectedSnapshotIdentity,
      requiredClasses
    );
  }

  datatype ComputationState<T> =
    | InProgress
    | Completed(value: T)

  datatype ManagedCandidate<T> = ManagedCandidate(
    request: NormalizedRequest,
    proof: ProofFrame,
    computation: ComputationState<T>
  )

  predicate CandidateCanPublish<T>(
    candidate: ManagedCandidate<T>,
    requiredClasses: set<DependencyClass>
  ) {
    candidate.computation.Completed? &&
    CompleteFrame(candidate.proof, requiredClasses)
  }

  predicate CandidateCanReuse<T>(
    candidate: ManagedCandidate<T>,
    selectedRequest: NormalizedRequest,
    selectedProof: ProofFrame,
    requiredClasses: set<DependencyClass>
  ) {
    CandidateCanPublish(candidate, requiredClasses) &&
    candidate.request == selectedRequest &&
    FramesCanMatch(candidate.proof, selectedProof, requiredClasses)
  }

  lemma InProgressCandidateCannotPublish<T>(
    candidate: ManagedCandidate<T>,
    requiredClasses: set<DependencyClass>
  )
    requires candidate.computation.InProgress?
    ensures !CandidateCanPublish(candidate, requiredClasses)
  {
  }

  lemma DifferentDemandCannotReuse<T>(
    candidate: ManagedCandidate<T>,
    selectedRequest: NormalizedRequest,
    selectedProof: ProofFrame,
    requiredClasses: set<DependencyClass>
  )
    requires candidate.request.demand != selectedRequest.demand
    ensures !CandidateCanReuse(
              candidate,
              selectedRequest,
              selectedProof,
              requiredClasses
            )
  {
  }

  lemma DifferentSemanticRequestCannotReuse<T>(
    candidate: ManagedCandidate<T>,
    selectedRequest: NormalizedRequest,
    selectedProof: ProofFrame,
    requiredClasses: set<DependencyClass>
  )
    requires candidate.request != selectedRequest
    ensures !CandidateCanReuse(
              candidate,
              selectedRequest,
              selectedProof,
              requiredClasses
            )
  {
  }

  predicate PublicationReachable(
    capturedLifecycle: nat,
    activeLifecycle: nat
  ) {
    capturedLifecycle == activeLifecycle
  }

  lemma LatePublicationCannotReachRotatedLifecycle(
    capturedLifecycle: nat,
    activeLifecycle: nat
  )
    requires capturedLifecycle != activeLifecycle
    ensures !PublicationReachable(capturedLifecycle, activeLifecycle)
  {
  }
}
