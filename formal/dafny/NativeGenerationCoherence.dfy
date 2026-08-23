module NativeGenerationCoherence {
  type Relation = nat
  type Entity = nat
  type Generation = nat

  datatype Edge = Edge(subject: Entity, relation: Relation, resource: Entity)
  datatype Query = Query(subject: Entity, resource: Entity)

  datatype Snapshot = Snapshot(
    lifecycle: nat,
    schemaGeneration: Generation,
    schemaSemantics: set<nat>,
    relationGenerations: map<Relation, Generation>,
    forward: set<Edge>,
    reverse: set<Edge>,
    objects: set<Entity>
  )

  datatype RelationSliceState = RelationSliceState(
    forward: set<Edge>,
    reverse: set<Edge>
  )

  function RelationSlice(
    snapshot: Snapshot,
    relation: Relation
  ): RelationSliceState {
    RelationSliceState(
      set edge | edge in snapshot.forward && edge.relation == relation,
      set edge | edge in snapshot.reverse && edge.relation == relation
    )
  }

  predicate ForwardStep(before: Snapshot, after: Snapshot) {
    before.lifecycle == after.lifecycle &&
    before.relationGenerations.Keys == after.relationGenerations.Keys &&
    before.schemaGeneration <= after.schemaGeneration &&
    (before.schemaSemantics != after.schemaSemantics ==>
       before.schemaGeneration < after.schemaGeneration) &&
    (forall relation <- before.relationGenerations.Keys ::
       before.relationGenerations[relation] <=
       after.relationGenerations[relation] &&
       (RelationSlice(before, relation) != RelationSlice(after, relation) ==>
          before.relationGenerations[relation] <
          after.relationGenerations[relation]))
  }

  predicate ForwardHistory(history: seq<Snapshot>) {
    0 < |history| &&
    (forall index | 0 <= index < |history| - 1 ::
       ForwardStep(history[index], history[index + 1]))
  }

  lemma RelationGenerationMonotone(
    history: seq<Snapshot>,
    relation: Relation
  )
    requires ForwardHistory(history)
    requires relation in history[0].relationGenerations
    requires relation in history[|history| - 1].relationGenerations
    ensures relation in history[|history| - 1].relationGenerations
    ensures history[0].relationGenerations[relation] <=
            history[|history| - 1].relationGenerations[relation]
    decreases |history|
  {
    if |history| > 1 {
      var prefix := history[..|history| - 1];
      assert ForwardHistory(prefix);
      RelationGenerationMonotone(prefix, relation);
      assert ForwardStep(history[|history| - 2], history[|history| - 1]);
    }
  }

  lemma EqualRelationGenerationPreservesSlice(
    history: seq<Snapshot>,
    relation: Relation
  )
    requires ForwardHistory(history)
    requires relation in history[0].relationGenerations
    requires relation in history[|history| - 1].relationGenerations
    requires history[0].relationGenerations[relation] ==
             history[|history| - 1].relationGenerations[relation]
    ensures RelationSlice(history[0], relation) ==
            RelationSlice(history[|history| - 1], relation)
    decreases |history|
  {
    if |history| > 1 {
      var prefix := history[..|history| - 1];
      assert ForwardHistory(prefix);
      RelationGenerationMonotone(prefix, relation);
      assert ForwardStep(history[|history| - 2], history[|history| - 1]);
      assert history[0].relationGenerations[relation] <=
             history[|history| - 2].relationGenerations[relation] <=
             history[|history| - 1].relationGenerations[relation];
      assert history[0].relationGenerations[relation] ==
             history[|history| - 2].relationGenerations[relation];
      EqualRelationGenerationPreservesSlice(prefix, relation);
      assert history[|history| - 2].relationGenerations[relation] ==
             history[|history| - 1].relationGenerations[relation];
      assert RelationSlice(history[|history| - 2], relation) ==
             RelationSlice(history[|history| - 1], relation);
    }
  }

  lemma SchemaGenerationMonotone(history: seq<Snapshot>)
    requires ForwardHistory(history)
    ensures history[0].schemaGeneration <=
            history[|history| - 1].schemaGeneration
    decreases |history|
  {
    if |history| > 1 {
      var prefix := history[..|history| - 1];
      assert ForwardHistory(prefix);
      SchemaGenerationMonotone(prefix);
      assert ForwardStep(history[|history| - 2], history[|history| - 1]);
    }
  }

  lemma EqualSchemaGenerationPreservesSemantics(history: seq<Snapshot>)
    requires ForwardHistory(history)
    requires history[0].schemaGeneration ==
             history[|history| - 1].schemaGeneration
    ensures history[0].schemaSemantics ==
            history[|history| - 1].schemaSemantics
    decreases |history|
  {
    if |history| > 1 {
      var prefix := history[..|history| - 1];
      assert ForwardHistory(prefix);
      SchemaGenerationMonotone(prefix);
      assert ForwardStep(history[|history| - 2], history[|history| - 1]);
      assert history[0].schemaGeneration <=
             history[|history| - 2].schemaGeneration <=
             history[|history| - 1].schemaGeneration;
      assert history[0].schemaGeneration ==
             history[|history| - 2].schemaGeneration;
      EqualSchemaGenerationPreservesSemantics(prefix);
      assert history[|history| - 2].schemaGeneration ==
             history[|history| - 1].schemaGeneration;
      assert history[|history| - 2].schemaSemantics ==
             history[|history| - 1].schemaSemantics;
    }
  }

  function RelevantForwardEdges(
    snapshot: Snapshot,
    dependencies: set<Relation>
  ): set<Edge> {
    set edge | edge in snapshot.forward && edge.relation in dependencies
  }

  function RelevantReverseEdges(
    snapshot: Snapshot,
    dependencies: set<Relation>
  ): set<Edge> {
    set edge | edge in snapshot.reverse && edge.relation in dependencies
  }

  datatype QueryFrame = QueryFrame(
    schema: set<nat>,
    forwardRelationships: set<Edge>,
    reverseRelationships: set<Edge>,
    subject: Entity,
    resource: Entity
  )

  function Frame(
    snapshot: Snapshot,
    query: Query,
    dependencies: set<Relation>
  ): QueryFrame {
    QueryFrame(
      snapshot.schemaSemantics,
      RelevantForwardEdges(snapshot, dependencies),
      RelevantReverseEdges(snapshot, dependencies),
      query.subject,
      query.resource
    )
  }

  // ForwardHistory orders the two immutable values so the proof can reason
  // about intervening commits. Reuse itself is direction-agnostic: equality
  // of the resulting frames is symmetric, so an answer produced at the last
  // snapshot is equally valid when the first snapshot is selected later.
  lemma DependencyEquivalentFramesAreEqual(
    history: seq<Snapshot>,
    query: Query,
    dependencies: set<Relation>
  )
    requires ForwardHistory(history)
    requires dependencies <= history[0].relationGenerations.Keys
    requires dependencies <=
             history[|history| - 1].relationGenerations.Keys
    requires history[0].schemaGeneration ==
             history[|history| - 1].schemaGeneration
    requires forall relation <- dependencies ::
               history[0].relationGenerations[relation] ==
               history[|history| - 1].relationGenerations[relation]
    ensures Frame(history[0], query, dependencies) ==
            Frame(history[|history| - 1], query, dependencies)
  {
    EqualSchemaGenerationPreservesSemantics(history);
    forall edge | edge in RelevantForwardEdges(history[0], dependencies)
      ensures edge in RelevantForwardEdges(
                        history[|history| - 1], dependencies
                      )
    {
      assert edge.relation in dependencies;
      assert edge in RelationSlice(history[0], edge.relation).forward;
      EqualRelationGenerationPreservesSlice(history, edge.relation);
      assert edge in RelationSlice(
                       history[|history| - 1], edge.relation
                     ).forward;
    }
    forall edge | edge in RelevantForwardEdges(
                            history[|history| - 1], dependencies
                          )
      ensures edge in RelevantForwardEdges(history[0], dependencies)
    {
      assert edge.relation in dependencies;
      assert edge in RelationSlice(
                       history[|history| - 1], edge.relation
                     ).forward;
      EqualRelationGenerationPreservesSlice(history, edge.relation);
      assert edge in RelationSlice(history[0], edge.relation).forward;
    }
    forall edge | edge in RelevantReverseEdges(history[0], dependencies)
      ensures edge in RelevantReverseEdges(
                        history[|history| - 1], dependencies
                      )
    {
      assert edge.relation in dependencies;
      assert edge in RelationSlice(history[0], edge.relation).reverse;
      EqualRelationGenerationPreservesSlice(history, edge.relation);
      assert edge in RelationSlice(
                       history[|history| - 1], edge.relation
                     ).reverse;
    }
    forall edge | edge in RelevantReverseEdges(
                            history[|history| - 1], dependencies
                          )
      ensures edge in RelevantReverseEdges(history[0], dependencies)
    {
      assert edge.relation in dependencies;
      assert edge in RelationSlice(
                       history[|history| - 1], edge.relation
                     ).reverse;
      EqualRelationGenerationPreservesSlice(history, edge.relation);
      assert edge in RelationSlice(history[0], edge.relation).reverse;
    }
  }

  // The empty complete dependency closure is valid: schema equivalence alone
  // determines an evaluator whose frame contains no relationship facts.
  lemma EmptyDependencyFrame(
    history: seq<Snapshot>,
    query: Query
  )
    requires ForwardHistory(history)
    requires history[0].schemaGeneration ==
             history[|history| - 1].schemaGeneration
    requires history[0].relationGenerations.Keys ==
             history[|history| - 1].relationGenerations.Keys
    ensures Frame(history[0], query, {}) ==
            Frame(history[|history| - 1], query, {})
  {
    DependencyEquivalentFramesAreEqual(history, query, {});
  }

  datatype EndpointState = EndpointState(
    identities: map<Entity, nat>,
    relationships: set<Edge>
  )

  datatype PlannedRelationshipWrite = PlannedRelationshipWrite(
    edge: Edge,
    subjectIdentity: nat,
    resourceIdentity: nat
  )

  predicate EndpointGuardsPass(
    state: EndpointState,
    planned: PlannedRelationshipWrite
  ) {
    planned.edge.subject in state.identities &&
    planned.edge.resource in state.identities &&
    state.identities[planned.edge.subject] == planned.subjectIdentity &&
    state.identities[planned.edge.resource] == planned.resourceIdentity
  }

  lemma DeletedEndpointCannotPassPreparedWriteGuard(
    state: EndpointState,
    planned: PlannedRelationshipWrite,
    deleted: Entity
  )
    requires deleted == planned.edge.subject ||
             deleted == planned.edge.resource
    requires deleted !in state.identities
    ensures !EndpointGuardsPass(state, planned)
  {
  }

  datatype SchemaRaceState = SchemaRaceState(
    schemaGeneration: Generation,
    schemaWriteFence: Generation,
    relationGenerations: map<Relation, Generation>,
    liveRelations: set<Relation>,
    forwardRelationships: set<Edge>,
    reverseRelationships: set<Edge>
  )

  datatype PlannedSchemaRemoval = PlannedSchemaRemoval(
    relation: Relation,
    expectedSchemaWriteFence: Generation,
    expectedRelationGeneration: Generation
  )

  predicate SchemaRemovalGuardsPass(
    state: SchemaRaceState,
    planned: PlannedSchemaRemoval
  ) {
    planned.relation in state.liveRelations &&
    planned.relation in state.relationGenerations &&
    state.schemaWriteFence == planned.expectedSchemaWriteFence &&
    state.relationGenerations[planned.relation] ==
    planned.expectedRelationGeneration
  }

  predicate RelationUsed(state: SchemaRaceState, relation: Relation) {
    exists edge <-
             state.forwardRelationships + state.reverseRelationships ::
      edge.relation == relation
  }

  predicate SchemaRemovalMayCommit(
    state: SchemaRaceState,
    planned: PlannedSchemaRemoval
  ) {
    SchemaRemovalGuardsPass(state, planned) &&
    !RelationUsed(state, planned.relation)
  }

  lemma RelationshipAdvanceBreaksRemovalGuard(
    state: SchemaRaceState,
    planned: PlannedSchemaRemoval
  )
    requires planned.relation in state.relationGenerations
    requires planned.expectedRelationGeneration <
             state.relationGenerations[planned.relation]
    ensures !SchemaRemovalGuardsPass(state, planned)
    ensures !SchemaRemovalMayCommit(state, planned)
  {
  }

  lemma ReverseOnlyGhostBlocksRelationRemoval(
    state: SchemaRaceState,
    planned: PlannedSchemaRemoval,
    ghostEdge: Edge
  )
    requires ghostEdge in state.reverseRelationships
    requires ghostEdge !in state.forwardRelationships
    requires ghostEdge.relation == planned.relation
    ensures RelationUsed(state, planned.relation)
    ensures !SchemaRemovalMayCommit(state, planned)
  {
  }

  datatype PlannedSchemaBoundWrite = PlannedSchemaBoundWrite(
    edge: Edge,
    expectedSchemaWriteFence: Generation
  )

  predicate RelationshipSchemaGuardPasses(
    state: SchemaRaceState,
    planned: PlannedSchemaBoundWrite
  ) {
    planned.edge.relation in state.liveRelations &&
    state.schemaWriteFence == planned.expectedSchemaWriteFence
  }

  lemma SchemaRotationBreaksPreparedRelationshipGuard(
    state: SchemaRaceState,
    planned: PlannedSchemaBoundWrite
  )
    requires state.schemaWriteFence != planned.expectedSchemaWriteFence
    ensures !RelationshipSchemaGuardPasses(state, planned)
  {
  }

  // A successful old==old predicate may reassert the fence's physical datom,
  // but its stored value is unchanged and the cache proof reads the distinct
  // schema generation. Predicate bookkeeping therefore cannot invalidate the
  // schema cache.
  predicate SchemaFencePredicateStep(
    before: SchemaRaceState,
    after: SchemaRaceState
  ) {
    before.schemaGeneration == after.schemaGeneration &&
    before.schemaWriteFence == after.schemaWriteFence
  }

  lemma SchemaFencePredicatePreservesCacheGeneration(
    before: SchemaRaceState,
    after: SchemaRaceState
  )
    requires SchemaFencePredicateStep(before, after)
    ensures before.schemaGeneration == after.schemaGeneration
  {
  }

  predicate SafeRetraction(
    before: Snapshot,
    after: Snapshot,
    closure: set<Entity>,
    affected: set<Relation>
  ) {
    after.lifecycle == before.lifecycle &&
    after.objects == before.objects - closure &&
    after.forward ==
    (set edge | edge in before.forward &&
                edge.subject !in closure && edge.resource !in closure) &&
    after.reverse ==
    (set edge | edge in before.reverse &&
                edge.subject !in closure && edge.resource !in closure) &&
    affected ==
    (set relation | relation in before.relationGenerations.Keys &&
                    (exists edge: Edge ::
                       edge in before.forward + before.reverse &&
                       edge.relation == relation &&
                       (edge.subject in closure || edge.resource in closure))) &&
    before.relationGenerations.Keys == after.relationGenerations.Keys &&
    (forall relation <- before.relationGenerations.Keys ::
       if relation in affected then
         before.relationGenerations[relation] <
         after.relationGenerations[relation]
       else
         before.relationGenerations[relation] ==
         after.relationGenerations[relation])
  }

  lemma SafeRetractionLeavesNoClosureGhosts(
    before: Snapshot,
    after: Snapshot,
    closure: set<Entity>,
    affected: set<Relation>
  )
    requires SafeRetraction(before, after, closure, affected)
    ensures forall edge ::
              (edge in after.forward || edge in after.reverse) ==>
                edge.subject !in closure && edge.resource !in closure
    ensures affected <= before.relationGenerations.Keys
    ensures before.relationGenerations.Keys ==
            after.relationGenerations.Keys
    ensures forall relation |
              relation in affected &&
              relation in before.relationGenerations.Keys &&
              relation in after.relationGenerations.Keys ::
              before.relationGenerations[relation] <
              after.relationGenerations[relation]
  {
    reveal SafeRetraction();
    assert affected ==
           set relation | relation in before.relationGenerations.Keys &&
                          (exists edge: Edge ::
                             edge in before.forward + before.reverse &&
                             edge.relation == relation &&
                             (edge.subject in closure || edge.resource in closure));
    assert affected <= before.relationGenerations.Keys;
    assert before.relationGenerations.Keys ==
           after.relationGenerations.Keys;
    forall edge | edge in after.forward || edge in after.reverse
      ensures edge.subject !in closure && edge.resource !in closure
    {
      if edge in after.forward {
        assert edge in
                 (set retained | retained in before.forward &&
                                 retained.subject !in closure &&
                                 retained.resource !in closure);
      } else {
        assert edge in after.reverse;
        assert edge in
                 (set retained | retained in before.reverse &&
                                 retained.subject !in closure &&
                                 retained.resource !in closure);
      }
    }
  }

  datatype LifecycleStore = LifecycleStore(identity: nat, entries: set<nat>)

  predicate Reachable(current: LifecycleStore, published: LifecycleStore) {
    current.identity == published.identity
  }

  lemma LatePublicationIntoRotatedLifecycleIsUnreachable(
    captured: LifecycleStore,
    current: LifecycleStore,
    published: LifecycleStore
  )
    requires captured.identity != current.identity
    requires published.identity == captured.identity
    ensures !Reachable(current, published)
  {
  }
}
