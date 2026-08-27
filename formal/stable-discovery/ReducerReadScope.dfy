// Read-scope bridge for proof-equivalent cursor continuation.
//
// A sealed plan is accepted only when every relation named by every compiled
// rule belongs to its certified dependency closure. Any deterministic reducer
// schedule may issue only descriptors derived from those rules. Therefore two
// immutable bases with equal slices for the closure produce equal read traces;
// deterministic transitions, emissions, order, and boundary positions are
// equal as a consequence. ScalarFrontierCoherence supplies equality of the
// closure slices; the pagination leaves consume the equal boundary stream.
module ReducerReadScope {
  datatype Rule =
    | Direct(relation: nat)
    | Arrow(viaRelation: nat, targetRelation: nat)

  datatype ScanDescriptor = ScanDescriptor(relation: nat, binding: nat)

  datatype Plan = Plan(closure: set<nat>, rules: seq<Rule>)

  function RuleRelations(rule: Rule): set<nat> {
    match rule
    case Direct(relation) => {relation}
    case Arrow(viaRelation, targetRelation) =>
      {viaRelation, targetRelation}
  }

  ghost function RuleDescriptors(rule: Rule): iset<ScanDescriptor> {
    match rule
    case Direct(relation) =>
      iset binding: nat | true :: ScanDescriptor(relation, binding)
    case Arrow(viaRelation, targetRelation) =>
      (iset binding: nat | true :: ScanDescriptor(viaRelation, binding)) +
      (iset binding: nat | true :: ScanDescriptor(targetRelation, binding))
  }

  predicate WellScoped(plan: Plan) {
    forall i: int ::
      0 <= i < |plan.rules| ==>
        RuleRelations(plan.rules[i]) <= plan.closure
  }

  ghost predicate IssuedByPlan(
    plan: Plan,
    descriptor: ScanDescriptor
  ) {
    exists i: int ::
      0 <= i < |plan.rules| &&
      descriptor in RuleDescriptors(plan.rules[i])
  }

  ghost function IssuedDescriptors(plan: Plan): iset<ScanDescriptor> {
    iset descriptor: ScanDescriptor |
      IssuedByPlan(plan, descriptor)
  }

  lemma DescriptorNamesRuleRelation(
    rule: Rule,
    descriptor: ScanDescriptor
  )
    requires descriptor in RuleDescriptors(rule)
    ensures descriptor.relation in RuleRelations(rule)
  {
    match rule
    case Direct(relation) =>
    case Arrow(viaRelation, targetRelation) =>
  }

  lemma EveryIssuedDescriptorNamesAClosureRelation(
    plan: Plan,
    descriptor: ScanDescriptor
  )
    requires WellScoped(plan)
    requires descriptor in IssuedDescriptors(plan)
    ensures descriptor.relation in plan.closure
  {
    var i: int :|
      0 <= i < |plan.rules| &&
      descriptor in RuleDescriptors(plan.rules[i]);
    DescriptorNamesRuleRelation(plan.rules[i], descriptor);
  }

  type Slices = map<nat, seq<nat>>

  function ReadDescriptor(
    slices: Slices,
    descriptor: ScanDescriptor
  ): seq<nat> {
    if descriptor.relation in slices
    then slices[descriptor.relation]
    else []
  }

  predicate EqualClosureSlices(
    closure: set<nat>,
    left: Slices,
    right: Slices
  ) {
    forall relation: nat :: relation in closure ==>
      (relation in left <==> relation in right) &&
      (relation in left ==> left[relation] == right[relation])
  }

  function ReadTrace(
    descriptors: seq<ScanDescriptor>,
    slices: Slices
  ): seq<nat>
    decreases |descriptors|
  {
    if |descriptors| == 0 then []
    else ReadDescriptor(slices, descriptors[0]) +
         ReadTrace(descriptors[1..], slices)
  }

  predicate DescriptorsWithin(
    descriptors: seq<ScanDescriptor>,
    closure: set<nat>
  ) {
    forall i: int ::
      0 <= i < |descriptors| ==>
        descriptors[i].relation in closure
  }

  lemma EqualClosureSlicesGiveEqualReadTrace(
    descriptors: seq<ScanDescriptor>,
    closure: set<nat>,
    left: Slices,
    right: Slices
  )
    requires DescriptorsWithin(descriptors, closure)
    requires EqualClosureSlices(closure, left, right)
    ensures ReadTrace(descriptors, left) ==
            ReadTrace(descriptors, right)
    decreases |descriptors|
  {
    if |descriptors| > 0 {
      assert descriptors[0].relation in closure;
      assert ReadDescriptor(left, descriptors[0]) ==
             ReadDescriptor(right, descriptors[0]);
      assert DescriptorsWithin(descriptors[1..], closure);
      EqualClosureSlicesGiveEqualReadTrace(
        descriptors[1..], closure, left, right
      );
    }
  }

  lemma IssuedScheduleStaysWithinClosure(
    plan: Plan,
    descriptors: seq<ScanDescriptor>
  )
    requires WellScoped(plan)
    requires forall i: int ::
      0 <= i < |descriptors| ==>
        descriptors[i] in IssuedDescriptors(plan)
    ensures DescriptorsWithin(descriptors, plan.closure)
  {
    forall i: int |
      0 <= i < |descriptors|
      ensures descriptors[i].relation in plan.closure
    {
      EveryIssuedDescriptorNamesAClosureRelation(plan, descriptors[i]);
    }
  }

  // The concrete reducer is deterministic in its sealed plan and read trace.
  // These projections make the composition boundary explicit without
  // introducing a second cursor kernel or another semantic certificate.
  function TransitionTrace(readTrace: seq<nat>): seq<nat> { readTrace }
  function EmissionOrder(transitions: seq<nat>): seq<nat> { transitions }
  function BoundaryPositions(emissions: seq<nat>): seq<nat> {
    seq(|emissions|, index => index)
  }

  lemma EqualClosureSlicesPreserveStreamAndBoundaries(
    plan: Plan,
    descriptors: seq<ScanDescriptor>,
    left: Slices,
    right: Slices
  )
    requires WellScoped(plan)
    requires forall i: int ::
      0 <= i < |descriptors| ==>
        descriptors[i] in IssuedDescriptors(plan)
    requires EqualClosureSlices(plan.closure, left, right)
    ensures TransitionTrace(ReadTrace(descriptors, left)) ==
            TransitionTrace(ReadTrace(descriptors, right))
    ensures EmissionOrder(TransitionTrace(ReadTrace(descriptors, left))) ==
            EmissionOrder(TransitionTrace(ReadTrace(descriptors, right)))
    ensures BoundaryPositions(
              EmissionOrder(TransitionTrace(ReadTrace(descriptors, left)))) ==
            BoundaryPositions(
              EmissionOrder(TransitionTrace(ReadTrace(descriptors, right))))
  {
    IssuedScheduleStaysWithinClosure(plan, descriptors);
    EqualClosureSlicesGiveEqualReadTrace(
      descriptors, plan.closure, left, right
    );
  }

  // The prior theorem covers a concrete descriptor trace after it has been
  // issued. A reducer is adaptive, however: the next descriptor may depend on
  // values returned by earlier descriptors. Model that dependency explicitly
  // so equal closure slices establish equal schedules rather than assuming
  // the schedules equal.
  datatype ReducerState = ReducerState(
    ruleIndex: nat,
    takeTarget: bool,
    binding: nat,
    transitions: seq<nat>,
    emissions: seq<nat>,
    halted: bool
  )

  datatype ReducerCommand = Stop | Read(descriptor: ScanDescriptor)

  function SelectedRelation(rule: Rule, takeTarget: bool): nat {
    match rule
    case Direct(relation) => relation
    case Arrow(viaRelation, targetRelation) =>
      if takeTarget then targetRelation else viaRelation
  }

  function NextCommand(plan: Plan, state: ReducerState): ReducerCommand {
    if state.halted || |plan.rules| == 0 then Stop
    else
      var rule := plan.rules[state.ruleIndex % |plan.rules|];
      Read(ScanDescriptor(
        SelectedRelation(rule, state.takeTarget),
        state.binding
      ))
  }

  function Step(
    state: ReducerState,
    values: seq<nat>
  ): ReducerState {
    ReducerState(
      state.ruleIndex + (if |values| == 0 then 1 else values[0] + 1),
      if |values| == 0 then state.takeTarget else !state.takeTarget,
      state.binding + |values|,
      state.transitions + values,
      state.emissions + (if |values| == 0 then [] else [values[0]]),
      state.halted || |values| == 0
    )
  }

  function Run(
    plan: Plan,
    slices: Slices,
    state: ReducerState,
    fuel: nat
  ): ReducerState
    decreases fuel
  {
    if fuel == 0 then state
    else
      match NextCommand(plan, state)
      case Stop => state
      case Read(descriptor) =>
        Run(
          plan,
          slices,
          Step(state, ReadDescriptor(slices, descriptor)),
          fuel - 1
        )
  }

  lemma NextCommandNamesAClosureRelation(
    plan: Plan,
    state: ReducerState,
    descriptor: ScanDescriptor
  )
    requires WellScoped(plan)
    requires NextCommand(plan, state) == Read(descriptor)
    ensures descriptor.relation in plan.closure
  {
    assert |plan.rules| > 0;
    var index := state.ruleIndex % |plan.rules|;
    assert 0 <= index < |plan.rules|;
    var rule := plan.rules[index];
    assert RuleRelations(rule) <= plan.closure;
    match rule
    case Direct(relation) =>
    case Arrow(viaRelation, targetRelation) =>
  }

  lemma EqualClosureSlicesPreserveAdaptiveRun(
    plan: Plan,
    state: ReducerState,
    left: Slices,
    right: Slices,
    fuel: nat
  )
    requires WellScoped(plan)
    requires EqualClosureSlices(plan.closure, left, right)
    ensures Run(plan, left, state, fuel) ==
            Run(plan, right, state, fuel)
    decreases fuel
  {
    if fuel > 0 {
      match NextCommand(plan, state) {
      case Stop =>
      case Read(descriptor) =>
        NextCommandNamesAClosureRelation(plan, state, descriptor);
        assert ReadDescriptor(left, descriptor) ==
               ReadDescriptor(right, descriptor);
        EqualClosureSlicesPreserveAdaptiveRun(
          plan,
          Step(state, ReadDescriptor(left, descriptor)),
          left,
          right,
          fuel - 1
        );
      }
    }
  }

  lemma EqualClosureSlicesPreserveAdaptiveStreamAndBoundaries(
    plan: Plan,
    initial: ReducerState,
    left: Slices,
    right: Slices,
    fuel: nat
  )
    requires WellScoped(plan)
    requires EqualClosureSlices(plan.closure, left, right)
    ensures Run(plan, left, initial, fuel).transitions ==
            Run(plan, right, initial, fuel).transitions
    ensures Run(plan, left, initial, fuel).emissions ==
            Run(plan, right, initial, fuel).emissions
    ensures BoundaryPositions(
              Run(plan, left, initial, fuel).emissions) ==
            BoundaryPositions(
              Run(plan, right, initial, fuel).emissions)
  {
    EqualClosureSlicesPreserveAdaptiveRun(
      plan, initial, left, right, fuel
    );
  }
}
