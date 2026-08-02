include "AcyclicEngine.dfy"

module RecursiveEngine {
  import Semantics
  import AcyclicEngine

  datatype TraversalDirection = Forward | Reverse

  datatype LimitKind =
    | DerivedGrants
    | AdvancedDatoms
    | QueuedWork

  datatype TraversalLimits = TraversalLimits(
    maxDerivedGrants: nat,
    maxAdvancedDatoms: nat,
    maxQueuedWork: nat
  )

  datatype WorkCounters = WorkCounters(
    derivedGrants: nat,
    advancedDatoms: nat,
    queuedWork: nat
  )

  datatype Consumer = Consumer(rule: Semantics.NormalizedRule)

  datatype StreamChunk = StreamChunk(grants: set<Semantics.Grant>)

  datatype ReverseGoal = ReverseGoal(
    node: Semantics.PermissionNode,
    resource: Semantics.ObjectRef
  )

  datatype ReverseGoalState = ReverseGoalState(
    pendingGoals: set<ReverseGoal>,
    consumers: seq<Consumer>,
    derivedGrants: set<Semantics.Grant>
  )

  datatype WorklistState = WorklistState(
    direction: TraversalDirection,
    grants: set<Semantics.Grant>,
    queue: set<Semantics.Grant>,
    consumers: seq<Consumer>,
    chunk: StreamChunk,
    counters: WorkCounters
  )

  datatype ClosureOutcome =
    | ClosureComplete(
        grants: set<Semantics.Grant>,
        counters: WorkCounters
      )
    | ClosureLimitExceeded(
        kind: LimitKind,
        counters: WorkCounters
      )

  datatype SequenceOutcome =
    | SequenceComplete(
        items: seq<Semantics.ObjectRef>,
        counters: WorkCounters
      )
    | SequenceLimitExceeded(
        kind: LimitKind,
        counters: WorkCounters
      )

  datatype OrdinalItem = OrdinalItem(
    ordinal: nat,
    value: Semantics.ObjectRef
  )

  datatype RetainedContinuation =
    | MissingContinuation
    | Retained(
        direction: TraversalDirection,
        items: seq<OrdinalItem>
      )

  datatype ContinuationOutcome =
    | ContinuationComplete(items: seq<OrdinalItem>)
    | ContinuationStale

  function AttachOrdinals(
    values: seq<Semantics.ObjectRef>,
    start: nat
  ): seq<OrdinalItem>
    ensures |AttachOrdinals(values, start)| == |values|
    ensures forall index | 0 <= index < |values| ::
              AttachOrdinals(values, start)[index].ordinal ==
              start + index
    decreases |values|
  {
    if |values| == 0 then
      []
    else
      [OrdinalItem(start, values[0])] +
      AttachOrdinals(values[1..], start + 1)
  }

  lemma AttachedOrdinalValuesArePreserved(
    values: seq<Semantics.ObjectRef>,
    start: nat,
    index: nat
  )
    requires index < |values|
    ensures AttachOrdinals(values, start)[index].value ==
            values[index]
    decreases |values|
  {
    if index != 0 {
      AttachedOrdinalValuesArePreserved(
        values[1..],
        start + 1,
        index - 1
      );
    }
  }

  predicate StrictOrdinals(items: seq<OrdinalItem>) {
    forall left, right | 0 <= left < right < |items| ::
      items[left].ordinal < items[right].ordinal
  }

  lemma AttachedOrdinalsAreStrict(
    values: seq<Semantics.ObjectRef>,
    start: nat
  )
    ensures StrictOrdinals(AttachOrdinals(values, start))
    decreases |values|
  {
    if |values| != 0 {
      AttachedOrdinalsAreStrict(values[1..], start + 1);
    }
  }

  function ResumeOrReplay(
    direction: TraversalDirection,
    recomputed: seq<OrdinalItem>,
    continuation: RetainedContinuation,
    nextOrdinal: nat
  ): ContinuationOutcome
    requires StrictOrdinals(recomputed)
    requires nextOrdinal <= |recomputed|
    requires continuation.Retained? ==>
               continuation.direction == direction &&
               continuation.items == recomputed
    ensures ResumeOrReplay(
              direction,
              recomputed,
              continuation,
              nextOrdinal
            ).ContinuationComplete?
    ensures ResumeOrReplay(
              direction,
              recomputed,
              continuation,
              nextOrdinal
            ).items == recomputed[nextOrdinal..]
  {
    if continuation.Retained? then
      ContinuationComplete(
        continuation.items[nextOrdinal..]
      )
    else
      ContinuationComplete(recomputed[nextOrdinal..])
  }

  lemma RetainedAndReplayedTraversalAgree(
    direction: TraversalDirection,
    recomputed: seq<OrdinalItem>,
    nextOrdinal: nat
  )
    requires StrictOrdinals(recomputed)
    requires nextOrdinal <= |recomputed|
    ensures ResumeOrReplay(
              direction,
              recomputed,
              Retained(direction, recomputed),
              nextOrdinal
            ).items ==
            ResumeOrReplay(
              direction,
              recomputed,
              MissingContinuation,
              nextOrdinal
            ).items
  {
  }

  function Consumers(
    rules: seq<Semantics.NormalizedRule>
  ): seq<Consumer>
    ensures |Consumers(rules)| == |rules|
    decreases |rules|
  {
    if |rules| == 0 then
      []
    else
      [Consumer(rules[0])] + Consumers(rules[1..])
  }

  function ReverseGoalUniverse(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>
  ): set<ReverseGoal> {
    set grant <- Semantics.GrantUniverse(objects, permissions) ::
      ReverseGoal(grant.node, grant.resource)
  }

  function GrantsForGoals(
    goals: set<ReverseGoal>,
    closure: set<Semantics.Grant>
  ): set<Semantics.Grant> {
    set grant <- closure |
        ReverseGoal(grant.node, grant.resource) in goals
  }

  method DrainReverseGoals(
    state: ReverseGoalState,
    closure: set<Semantics.Grant>
  ) returns (result: set<Semantics.Grant>)
    requires state.derivedGrants <= closure
    ensures result ==
            state.derivedGrants +
            GrantsForGoals(state.pendingGoals, closure)
    decreases state.pendingGoals
  {
    if |state.pendingGoals| == 0 {
      return state.derivedGrants;
    }

    var goal :| goal in state.pendingGoals;
    var matching := GrantsForGoals({goal}, closure);
    var next := ReverseGoalState(
      state.pendingGoals - {goal},
      state.consumers,
      state.derivedGrants + matching
    );
    result := DrainReverseGoals(next, closure);

    forall grant
      ensures grant in
                state.derivedGrants +
                GrantsForGoals(state.pendingGoals, closure) <==>
              grant in
                next.derivedGrants +
                GrantsForGoals(next.pendingGoals, closure)
    {
    }
  }

  lemma ReverseGoalsCoverClosure(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    closure: set<Semantics.Grant>
  )
    requires closure <=
             Semantics.GrantUniverse(objects, permissions)
    ensures GrantsForGoals(
              ReverseGoalUniverse(objects, permissions),
              closure
            ) == closure
  {
  }

  lemma ConsumersPreserveRules(
    rules: seq<Semantics.NormalizedRule>,
    index: nat
  )
    requires index < |rules|
    ensures Consumers(rules)[index].rule == rules[index]
    decreases |rules|
  {
    if index != 0 {
      ConsumersPreserveRules(rules[1..], index - 1);
    }
  }

  function DependencyReachableWithin(
    paths: seq<AcyclicEngine.CompiledPath>,
    permissions: seq<Semantics.PermissionNode>,
    current: set<Semantics.PermissionNode>,
    fuel: nat
  ): set<Semantics.PermissionNode>
    decreases fuel
  {
    if fuel == 0 then
      current
    else
      DependencyReachableWithin(
        paths,
        permissions,
        AcyclicEngine.DependencyStep(paths, permissions, current),
        fuel - 1
      )
  }

  lemma DependencyStepIsMonotone(
    paths: seq<AcyclicEngine.CompiledPath>,
    permissions: seq<Semantics.PermissionNode>,
    smaller: set<Semantics.PermissionNode>,
    larger: set<Semantics.PermissionNode>
  )
    requires smaller <= larger
    ensures AcyclicEngine.DependencyStep(
              paths,
              permissions,
              smaller
            ) <=
            AcyclicEngine.DependencyStep(
              paths,
              permissions,
              larger
            )
  {
  }

  function DependencyClosure(
    paths: seq<AcyclicEngine.CompiledPath>,
    permissions: seq<Semantics.PermissionNode>,
    current: set<Semantics.PermissionNode>
  ): set<Semantics.PermissionNode>
    requires current <= AcyclicEngine.PermissionUniverse(permissions)
    decreases AcyclicEngine.PermissionUniverse(permissions) - current
  {
    var next :=
      AcyclicEngine.DependencyStep(paths, permissions, current);
    if next == current then
      current
    else
      DependencyClosure(paths, permissions, next)
  }

  lemma DependencyClosureIsFixed(
    paths: seq<AcyclicEngine.CompiledPath>,
    permissions: seq<Semantics.PermissionNode>,
    current: set<Semantics.PermissionNode>
  )
    requires current <= AcyclicEngine.PermissionUniverse(permissions)
    ensures current <= DependencyClosure(paths, permissions, current)
    ensures DependencyClosure(paths, permissions, current) <=
            AcyclicEngine.PermissionUniverse(permissions)
    ensures AcyclicEngine.DependencyStep(
              paths,
              permissions,
              DependencyClosure(paths, permissions, current)
            ) ==
            DependencyClosure(paths, permissions, current)
    decreases AcyclicEngine.PermissionUniverse(permissions) - current
  {
    var next :=
      AcyclicEngine.DependencyStep(paths, permissions, current);
    if next != current {
      DependencyClosureIsFixed(paths, permissions, next);
    }
  }

  lemma DependencyClosureIsLeast(
    paths: seq<AcyclicEngine.CompiledPath>,
    permissions: seq<Semantics.PermissionNode>,
    current: set<Semantics.PermissionNode>,
    fixed: set<Semantics.PermissionNode>
  )
    requires current <= fixed
    requires fixed <= AcyclicEngine.PermissionUniverse(permissions)
    requires AcyclicEngine.DependencyStep(
               paths,
               permissions,
               fixed
             ) == fixed
    ensures DependencyClosure(paths, permissions, current) <= fixed
    decreases AcyclicEngine.PermissionUniverse(permissions) - current
  {
    var next :=
      AcyclicEngine.DependencyStep(paths, permissions, current);
    DependencyStepIsMonotone(
      paths,
      permissions,
      current,
      fixed
    );
    if next != current {
      DependencyClosureIsLeast(
        paths,
        permissions,
        next,
        fixed
      );
    }
  }

  function BoundedReachability(
    root: Semantics.PermissionNode,
    paths: seq<AcyclicEngine.CompiledPath>,
    permissions: seq<Semantics.PermissionNode>
  ): set<Semantics.PermissionNode> {
    DependencyClosure(
      paths,
      permissions,
      if root in AcyclicEngine.PermissionUniverse(permissions)
      then {root}
      else {}
    )
  }

  lemma BoundedReachabilityIsExactLeastClosure(
    root: Semantics.PermissionNode,
    paths: seq<AcyclicEngine.CompiledPath>,
    permissions: seq<Semantics.PermissionNode>
  )
    ensures BoundedReachability(root, paths, permissions) <=
            AcyclicEngine.PermissionUniverse(permissions)
    ensures AcyclicEngine.DependencyStep(
              paths,
              permissions,
              BoundedReachability(root, paths, permissions)
            ) ==
            BoundedReachability(root, paths, permissions)
    ensures root in AcyclicEngine.PermissionUniverse(permissions) ==>
              root in BoundedReachability(root, paths, permissions)
    ensures forall fixed |
              (root in AcyclicEngine.PermissionUniverse(permissions) ==>
                 root in fixed) &&
              fixed <= AcyclicEngine.PermissionUniverse(permissions) &&
              AcyclicEngine.DependencyStep(
                paths,
                permissions,
                fixed
              ) == fixed ::
              BoundedReachability(root, paths, permissions) <= fixed
  {
    var initial :=
      if root in AcyclicEngine.PermissionUniverse(permissions)
      then {root}
      else {};
    DependencyClosureIsFixed(paths, permissions, initial);
    forall fixed |
      (root in AcyclicEngine.PermissionUniverse(permissions) ==>
         root in fixed) &&
      fixed <= AcyclicEngine.PermissionUniverse(permissions) &&
      AcyclicEngine.DependencyStep(
        paths,
        permissions,
        fixed
      ) == fixed
      ensures BoundedReachability(root, paths, permissions) <= fixed
    {
      DependencyClosureIsLeast(
        paths,
        permissions,
        initial,
        fixed
      );
    }
  }

  function StrongComponent(
    node: Semantics.PermissionNode,
    paths: seq<AcyclicEngine.CompiledPath>,
    permissions: seq<Semantics.PermissionNode>
  ): set<Semantics.PermissionNode> {
    set candidate <- AcyclicEngine.PermissionUniverse(permissions) |
        candidate in BoundedReachability(node, paths, permissions) &&
        node in BoundedReachability(candidate, paths, permissions)
  }

  predicate HasSelfDependency(
    node: Semantics.PermissionNode,
    paths: seq<AcyclicEngine.CompiledPath>,
    permissions: seq<Semantics.PermissionNode>
  ) {
    exists path <- paths ::
      AcyclicEngine.PathHead(path) == node &&
      node in AcyclicEngine.PathDependencies(path, permissions)
  }

  predicate RecursiveComponent(
    component: set<Semantics.PermissionNode>,
    paths: seq<AcyclicEngine.CompiledPath>,
    permissions: seq<Semantics.PermissionNode>
  ) {
    |component| > 1 ||
    exists node <- component ::
      HasSelfDependency(node, paths, permissions)
  }

  function ReachableRecursiveComponents(
    root: Semantics.PermissionNode,
    paths: seq<AcyclicEngine.CompiledPath>,
    permissions: seq<Semantics.PermissionNode>
  ): set<set<Semantics.PermissionNode>> {
    set node <- BoundedReachability(root, paths, permissions) |
        RecursiveComponent(
          StrongComponent(node, paths, permissions),
          paths,
          permissions
        ) ::
      StrongComponent(node, paths, permissions)
  }

  lemma RecursiveComponentDetectionIsExact(
    root: Semantics.PermissionNode,
    paths: seq<AcyclicEngine.CompiledPath>,
    permissions: seq<Semantics.PermissionNode>,
    component: set<Semantics.PermissionNode>
  )
    ensures component in ReachableRecursiveComponents(
                           root,
                           paths,
                           permissions
                         ) <==>
            exists node <- BoundedReachability(
                             root,
                             paths,
                             permissions
                           ) ::
              component ==
              StrongComponent(node, paths, permissions) &&
              RecursiveComponent(component, paths, permissions)
  {
  }

  function CompileRecursiveRules(
    definitions: seq<Semantics.RuleDefinition>,
    reachable: set<Semantics.PermissionNode>
  ): seq<Semantics.NormalizedRule> {
    AcyclicEngine.CompiledReachableRules(
      AcyclicEngine.CompilePaths(definitions),
      reachable
    )
  }

  lemma RecursiveRuleCompilationPreservesDenotation(
    definitions: seq<Semantics.RuleDefinition>,
    reachable: set<Semantics.PermissionNode>
  )
    ensures CompileRecursiveRules(definitions, reachable) ==
            AcyclicEngine.NormalizedReachableRules(
              definitions,
              reachable
            )
  {
    AcyclicEngine.ReachableCompilationPreservesDenotation(
      definitions,
      reachable
    );
  }

  ghost predicate FixedPoint(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    rules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    grants: set<Semantics.Grant>
  ) {
    grants <= Semantics.GrantUniverse(objects, permissions) &&
    Semantics.ImmediateConsequences(
      objects,
      permissions,
      rules,
      relationships,
      grants
    ) == grants
  }

  ghost predicate WorklistInvariant(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    rules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    state: WorklistState
  ) {
    var universe := Semantics.GrantUniverse(objects, permissions);
    state.grants <= universe &&
    state.queue <= universe &&
    state.grants !! state.queue &&
    state.queue ==
    Semantics.ImmediateConsequences(
      objects,
      permissions,
      rules,
      relationships,
      state.grants
    ) - state.grants &&
    state.chunk.grants == state.queue &&
    |state.consumers| == |rules| &&
    (forall fixed |
       FixedPoint(
         objects,
         permissions,
         rules,
         relationships,
         fixed
       ) ::
       state.grants + state.queue <= fixed)
  }

  lemma PendingConsequencesBelongToEveryFixedPoint(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    rules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    current: set<Semantics.Grant>,
    fixed: set<Semantics.Grant>
  )
    requires current <= fixed
    requires FixedPoint(
               objects,
               permissions,
               rules,
               relationships,
               fixed
             )
    ensures Semantics.ImmediateConsequences(
              objects,
              permissions,
              rules,
              relationships,
              current
            ) <= fixed
  {
    Semantics.ImmediateConsequencesAreMonotone(
      objects,
      permissions,
      rules,
      relationships,
      current,
      fixed
    );
  }

  method RunWorklist(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    rules: seq<Semantics.NormalizedRule>,
    relationships: seq<Semantics.Relationship>,
    limits: TraversalLimits,
    state: WorklistState
  ) returns (outcome: ClosureOutcome)
    requires WorklistInvariant(
               objects,
               permissions,
               rules,
               relationships,
               state
             )
    ensures outcome.ClosureComplete? ==>
              FixedPoint(
                objects,
                permissions,
                rules,
                relationships,
                outcome.grants
              )
    ensures outcome.ClosureComplete? ==>
              forall fixed |
                FixedPoint(
                  objects,
                  permissions,
                  rules,
                  relationships,
                  fixed
                ) ::
                outcome.grants <= fixed
    decreases Semantics.GrantUniverse(objects, permissions) -
              state.grants
  {
    if |state.queue| == 0 {
      assert Semantics.ImmediateConsequences(
          objects,
          permissions,
          rules,
          relationships,
          state.grants
        ) == state.grants;
      return ClosureComplete(state.grants, state.counters);
    }

    var derived := |state.queue|;
    var advanced := |relationships| + |rules|;
    var queued := |state.queue|;

    if state.counters.derivedGrants + derived >
       limits.maxDerivedGrants {
      return ClosureLimitExceeded(DerivedGrants, state.counters);
    }
    if state.counters.advancedDatoms + advanced >
       limits.maxAdvancedDatoms {
      return ClosureLimitExceeded(AdvancedDatoms, state.counters);
    }
    if state.counters.queuedWork + queued >
       limits.maxQueuedWork {
      return ClosureLimitExceeded(QueuedWork, state.counters);
    }

    var current := state.grants + state.queue;
    var consequence := Semantics.ImmediateConsequences(
      objects,
      permissions,
      rules,
      relationships,
      current
    );
    var nextQueue := consequence - current;
    var nextCounters := WorkCounters(
      state.counters.derivedGrants + derived,
      state.counters.advancedDatoms + advanced,
      state.counters.queuedWork + queued
    );
    var nextState := WorklistState(
      state.direction,
      current,
      nextQueue,
      state.consumers,
      StreamChunk(nextQueue),
      nextCounters
    );

    assert state.grants < current;
    assert Semantics.GrantUniverse(objects, permissions) - current <
           Semantics.GrantUniverse(objects, permissions) - state.grants;
    assert current <= Semantics.GrantUniverse(objects, permissions);
    assert consequence <= Semantics.GrantUniverse(objects, permissions);
    assert current !! nextQueue;
    forall fixed |
      FixedPoint(
        objects,
        permissions,
        rules,
        relationships,
        fixed
      )
      ensures current + nextQueue <= fixed
    {
      assert current <= fixed;
      PendingConsequencesBelongToEveryFixedPoint(
        objects,
        permissions,
        rules,
        relationships,
        current,
        fixed
      );
    }
    assert WorklistInvariant(
        objects,
        permissions,
        rules,
        relationships,
        nextState
      );
    outcome := RunWorklist(
      objects,
      permissions,
      rules,
      relationships,
      limits,
      nextState
    );
  }

  method EvaluateClosureWithLimits(
    direction: TraversalDirection,
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    limits: TraversalLimits
  ) returns (outcome: ClosureOutcome)
    ensures outcome.ClosureComplete? ==>
              AcyclicEngine.LeastFixedPoint(
                objects,
                permissions,
                definitions,
                relationships,
                outcome.grants
              )
  {
    var rules := Semantics.Normalize(definitions);
    var initialGrants: set<Semantics.Grant> := {};
    var initialQueue :=
      Semantics.ImmediateConsequences(
        objects,
        permissions,
        rules,
        relationships,
        initialGrants
      ) - initialGrants;
    var state := WorklistState(
      direction,
      initialGrants,
      initialQueue,
      Consumers(rules),
      StreamChunk(initialQueue),
      WorkCounters(0, 0, 0)
    );

    forall fixed |
      FixedPoint(
        objects,
        permissions,
        rules,
        relationships,
        fixed
      )
      ensures initialGrants + initialQueue <= fixed
    {
      PendingConsequencesBelongToEveryFixedPoint(
        objects,
        permissions,
        rules,
        relationships,
        initialGrants,
        fixed
      );
    }
    assert WorklistInvariant(
        objects,
        permissions,
        rules,
        relationships,
        state
      );
    outcome := RunWorklist(
      objects,
      permissions,
      rules,
      relationships,
      limits,
      state
    );
  }

  method RecursiveForward(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    subject: Semantics.ObjectRef,
    node: Semantics.PermissionNode,
    limits: TraversalLimits
  ) returns (outcome: SequenceOutcome)
    requires AcyclicEngine.UniqueObjects(objects)
    ensures outcome.SequenceComplete? ==>
              AcyclicEngine.UniqueObjects(outcome.items)
    ensures outcome.SequenceComplete? ==>
              forall resource ::
                resource in outcome.items <==>
                            resource in objects &&
                            AcyclicEngine.SemanticallyAuthorized(
                              objects,
                              permissions,
                              definitions,
                              relationships,
                              Semantics.Grant(subject, node, resource)
                            )
  {
    var closure := EvaluateClosureWithLimits(
      Forward,
      objects,
      permissions,
      definitions,
      relationships,
      limits
    );
    if closure.ClosureLimitExceeded? {
      return SequenceLimitExceeded(closure.kind, closure.counters);
    }

    var items := AcyclicEngine.ForwardProjection(
      objects,
      closure.grants,
      subject,
      node
    );
    AcyclicEngine.ForwardProjectionIsUnique(
      objects,
      closure.grants,
      subject,
      node
    );
    forall resource
      ensures resource in items <==>
              resource in objects &&
              AcyclicEngine.SemanticallyAuthorized(
                objects,
                permissions,
                definitions,
                relationships,
                Semantics.Grant(subject, node, resource)
              )
    {
      AcyclicEngine.ForwardProjectionMembership(
        objects,
        closure.grants,
        subject,
        node,
        resource
      );
      AcyclicEngine.LeastFixedPointMembership(
        objects,
        permissions,
        definitions,
        relationships,
        closure.grants,
        Semantics.Grant(subject, node, resource)
      );
    }
    return SequenceComplete(items, closure.counters);
  }

  method RecursiveReverse(
    objects: seq<Semantics.ObjectRef>,
    permissions: seq<Semantics.PermissionNode>,
    definitions: seq<Semantics.RuleDefinition>,
    relationships: seq<Semantics.Relationship>,
    resource: Semantics.ObjectRef,
    node: Semantics.PermissionNode,
    limits: TraversalLimits
  ) returns (outcome: SequenceOutcome)
    requires AcyclicEngine.UniqueObjects(objects)
    ensures outcome.SequenceComplete? ==>
              AcyclicEngine.UniqueObjects(outcome.items)
    ensures outcome.SequenceComplete? ==>
              forall subject ::
                subject in outcome.items <==>
                           subject in objects &&
                           AcyclicEngine.SemanticallyAuthorized(
                             objects,
                             permissions,
                             definitions,
                             relationships,
                             Semantics.Grant(subject, node, resource)
                           )
  {
    var closure := EvaluateClosureWithLimits(
      Reverse,
      objects,
      permissions,
      definitions,
      relationships,
      limits
    );
    if closure.ClosureLimitExceeded? {
      return SequenceLimitExceeded(closure.kind, closure.counters);
    }

    var reverseState := ReverseGoalState(
      ReverseGoalUniverse(objects, permissions),
      Consumers(Semantics.Normalize(definitions)),
      {}
    );
    var reverseGrants := DrainReverseGoals(
      reverseState,
      closure.grants
    );
    ReverseGoalsCoverClosure(
      objects,
      permissions,
      closure.grants
    );
    assert reverseGrants == closure.grants;
    var items := AcyclicEngine.ReverseProjection(
      objects,
      reverseGrants,
      resource,
      node
    );
    AcyclicEngine.ReverseProjectionIsUnique(
      objects,
      reverseGrants,
      resource,
      node
    );
    forall subject
      ensures subject in items <==>
              subject in objects &&
              AcyclicEngine.SemanticallyAuthorized(
                objects,
                permissions,
                definitions,
                relationships,
                Semantics.Grant(subject, node, resource)
              )
    {
      AcyclicEngine.ReverseProjectionMembership(
        objects,
        reverseGrants,
        resource,
        node,
        subject
      );
      AcyclicEngine.LeastFixedPointMembership(
        objects,
        permissions,
        definitions,
        relationships,
        closure.grants,
        Semantics.Grant(subject, node, resource)
      );
    }
    return SequenceComplete(items, closure.counters);
  }

  lemma LimitOutcomeContainsNoPartialSequence(
    outcome: SequenceOutcome
  )
    requires outcome.SequenceLimitExceeded?
    ensures !outcome.SequenceComplete?
  {
  }
}
