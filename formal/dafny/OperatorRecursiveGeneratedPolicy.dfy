// Small executable command/state model for recursive operator scheduling.
// The proof-heavy conjunction and exclusion models remain outside generated
// runtimes; OperatorRecursiveGeneratedPolicyRefinement connects this policy to
// those abstract models.
module OperatorRecursiveGeneratedPolicy {
  datatype TypedExpressionFact = TypedExpressionFact(
    expression: nat,
    entityType: nat,
    entityEid: nat
  )

  datatype PositiveConsumerEdge = PositiveConsumerEdge(
    childExpression: nat,
    parentExpression: nat,
    slot: nat
  )

  datatype PositiveRule = PositiveRule(
    parentExpression: nat,
    width: nat,
    intersection: bool,
    anchorSlot: nat
  )

  datatype ExpressionStratum = ExpressionStratum(
    expression: nat,
    stratum: nat
  )

  datatype ExclusionRule = ExclusionRule(
    parentExpression: nat,
    leftExpression: nat,
    negativeExpression: nat
  )

  datatype LowerStratumQuestion = LowerStratumQuestion(
    parentExpression: nat,
    negativeExpression: nat,
    entityType: nat,
    entityEid: nat,
    parentStratum: nat,
    negativeStratum: nat
  )

  datatype AnchorState = AnchorState(
    parentExpression: nat,
    entityType: nat,
    entityEid: nat,
    satisfiedSlots: seq<bool>,
    satisfiedCount: nat
  )

  datatype RecursiveState = RecursiveState(
    facts: seq<TypedExpressionFact>,
    anchorStates: seq<AnchorState>,
    completedStrata: seq<nat>,
    pendingLowerQuestions: seq<LowerStratumQuestion>
  )

  datatype RecursiveCommand =
    | AdmitTypedFact(fact: TypedExpressionFact)
    | CompleteStratum(stratum: nat)
    | ResolveExactLowerStratum(question: LowerStratumQuestion)

  datatype RecursiveAction =
    | ScheduleTypedFact(fact: TypedExpressionFact)
    | AskExactLowerStratum(question: LowerStratumQuestion)

  datatype RecursiveCommandFailure =
    | IncompleteLowerStratum(question: LowerStratumQuestion)
    | InvalidLowerStratum(question: LowerStratumQuestion)

  datatype RecursiveTransition =
    | RecursiveTransitionAccepted(
        state: RecursiveState,
        actions: seq<RecursiveAction>,
        duplicateFact: bool
      )
    | RecursiveTransitionRejected(failure: RecursiveCommandFailure)

  datatype OptionalNat = MissingNat | PresentNat(value: nat)

  function ContainsNat(values: seq<nat>, value: nat): bool
    decreases |values|
  {
    |values| != 0 &&
    (values[0] == value || ContainsNat(values[1..], value))
  }

  function ContainsFact(
    facts: seq<TypedExpressionFact>,
    fact: TypedExpressionFact
  ): bool
    decreases |facts|
  {
    |facts| != 0 &&
    (facts[0] == fact || ContainsFact(facts[1..], fact))
  }

  function ContainsQuestion(
    questions: seq<LowerStratumQuestion>,
    question: LowerStratumQuestion
  ): bool
    decreases |questions|
  {
    |questions| != 0 &&
    (questions[0] == question ||
     ContainsQuestion(questions[1..], question))
  }

  function AppendNatIfMissing(values: seq<nat>, value: nat): seq<nat> {
    if ContainsNat(values, value) then values else values + [value]
  }

  function AppendFactIfMissing(
    facts: seq<TypedExpressionFact>,
    fact: TypedExpressionFact
  ): seq<TypedExpressionFact> {
    if ContainsFact(facts, fact) then facts else facts + [fact]
  }

  function AppendQuestionIfMissing(
    questions: seq<LowerStratumQuestion>,
    question: LowerStratumQuestion
  ): seq<LowerStratumQuestion> {
    if ContainsQuestion(questions, question) then questions
    else questions + [question]
  }

  function StratumFor(
    strata: seq<ExpressionStratum>,
    expression: nat
  ): OptionalNat
    decreases |strata|
  {
    if |strata| == 0 then
      MissingNat
    else if strata[0].expression == expression then
      PresentNat(strata[0].stratum)
    else
      StratumFor(strata[1..], expression)
  }

  function ChildForSlot(
    edges: seq<PositiveConsumerEdge>,
    parentExpression: nat,
    slot: nat
  ): OptionalNat
    decreases |edges|
  {
    if |edges| == 0 then
      MissingNat
    else if edges[0].parentExpression == parentExpression &&
            edges[0].slot == slot then
      PresentNat(edges[0].childExpression)
    else
      ChildForSlot(edges[1..], parentExpression, slot)
  }

  function SlotSatisfied(
    edges: seq<PositiveConsumerEdge>,
    facts: seq<TypedExpressionFact>,
    parentExpression: nat,
    slot: nat,
    entityType: nat,
    entityEid: nat
  ): bool {
    var child := ChildForSlot(edges, parentExpression, slot);
    child.PresentNat? &&
    ContainsFact(
      facts,
      TypedExpressionFact(child.value, entityType, entityEid)
    )
  }

  function SlotDecisions(
    edges: seq<PositiveConsumerEdge>,
    facts: seq<TypedExpressionFact>,
    parentExpression: nat,
    width: nat,
    entityType: nat,
    entityEid: nat
  ): seq<bool>
    ensures |SlotDecisions(
              edges,
              facts,
              parentExpression,
              width,
              entityType,
              entityEid
            )| == width
    decreases width
  {
    if width == 0 then
      []
    else
      SlotDecisions(
        edges,
        facts,
        parentExpression,
        width - 1,
        entityType,
        entityEid
      ) +
      [SlotSatisfied(
         edges,
         facts,
         parentExpression,
         width - 1,
         entityType,
         entityEid
       )]
  }

  function CountTrue(values: seq<bool>): nat
    ensures CountTrue(values) <= |values|
    decreases |values|
  {
    if |values| == 0 then
      0
    else
      (if values[0] then 1 else 0) + CountTrue(values[1..])
  }

  function AnyTrue(values: seq<bool>): bool
    decreases |values|
  {
    |values| != 0 && (values[0] || AnyTrue(values[1..]))
  }

  function AllTrue(values: seq<bool>): bool
    decreases |values|
  {
    |values| == 0 || (values[0] && AllTrue(values[1..]))
  }

  function RuleReady(
    edges: seq<PositiveConsumerEdge>,
    facts: seq<TypedExpressionFact>,
    rule: PositiveRule,
    entityType: nat,
    entityEid: nat
  ): bool {
    var slots := SlotDecisions(
                   edges,
                   facts,
                   rule.parentExpression,
                   rule.width,
                   entityType,
                   entityEid
                 );
    0 < rule.width &&
    (if rule.intersection then
       rule.anchorSlot < rule.width &&
       slots[rule.anchorSlot] &&
       AllTrue(slots)
     else
       AnyTrue(slots))
  }

  function PositiveActions(
    rules: seq<PositiveRule>,
    edges: seq<PositiveConsumerEdge>,
    facts: seq<TypedExpressionFact>,
    entityType: nat,
    entityEid: nat
  ): seq<RecursiveAction>
    decreases |rules|
  {
    if |rules| == 0 then
      []
    else
      var parentFact := TypedExpressionFact(
                          rules[0].parentExpression,
                          entityType,
                          entityEid
                        );
      (if RuleReady(edges, facts, rules[0], entityType, entityEid) &&
          !ContainsFact(facts, parentFact) then
         [ScheduleTypedFact(parentFact)]
       else
         []) +
      PositiveActions(
        rules[1..],
        edges,
        facts,
        entityType,
        entityEid
      )
  }

  function AnchorStatesForFacts(
    facts: seq<TypedExpressionFact>,
    rule: PositiveRule,
    edges: seq<PositiveConsumerEdge>,
    allFacts: seq<TypedExpressionFact>
  ): seq<AnchorState>
    decreases |facts|
  {
    if |facts| == 0 then
      []
    else
      var anchorChild := ChildForSlot(
                           edges,
                           rule.parentExpression,
                           rule.anchorSlot
                         );
      var slots := SlotDecisions(
                     edges,
                     allFacts,
                     rule.parentExpression,
                     rule.width,
                     facts[0].entityType,
                     facts[0].entityEid
                   );
      (if rule.intersection &&
          rule.anchorSlot < rule.width &&
          anchorChild.PresentNat? &&
          facts[0].expression == anchorChild.value then
         [AnchorState(
            rule.parentExpression,
            facts[0].entityType,
            facts[0].entityEid,
            slots,
            CountTrue(slots)
          )]
       else
         []) +
      AnchorStatesForFacts(facts[1..], rule, edges, allFacts)
  }

  function RebuildAnchorStates(
    rules: seq<PositiveRule>,
    edges: seq<PositiveConsumerEdge>,
    facts: seq<TypedExpressionFact>
  ): seq<AnchorState>
    decreases |rules|
  {
    if |rules| == 0 then
      []
    else
      AnchorStatesForFacts(facts, rules[0], edges, facts) +
      RebuildAnchorStates(rules[1..], edges, facts)
  }

  function QuestionsForLeftFact(
    exclusions: seq<ExclusionRule>,
    strata: seq<ExpressionStratum>,
    fact: TypedExpressionFact
  ): seq<LowerStratumQuestion>
    decreases |exclusions|
  {
    if |exclusions| == 0 then
      []
    else
      var parentStratum := StratumFor(
                             strata,
                             exclusions[0].parentExpression
                           );
      var negativeStratum := StratumFor(
                               strata,
                               exclusions[0].negativeExpression
                             );
      (if exclusions[0].leftExpression == fact.expression &&
          parentStratum.PresentNat? &&
          negativeStratum.PresentNat? then
         [LowerStratumQuestion(
            exclusions[0].parentExpression,
            exclusions[0].negativeExpression,
            fact.entityType,
            fact.entityEid,
            parentStratum.value,
            negativeStratum.value
          )]
       else
         []) +
      QuestionsForLeftFact(exclusions[1..], strata, fact)
  }

  function PartitionQuestions(
    questions: seq<LowerStratumQuestion>,
    completedStrata: seq<nat>,
    ready: seq<RecursiveAction>,
    pending: seq<LowerStratumQuestion>
  ): (seq<RecursiveAction>, seq<LowerStratumQuestion>)
    decreases |questions|
  {
    if |questions| == 0 then
      (ready, pending)
    else if questions[0].negativeStratum < questions[0].parentStratum &&
            ContainsNat(completedStrata, questions[0].negativeStratum) then
      PartitionQuestions(
        questions[1..],
        completedStrata,
        ready + [AskExactLowerStratum(questions[0])],
        pending
      )
    else
      PartitionQuestions(
        questions[1..],
        completedStrata,
        ready,
        AppendQuestionIfMissing(pending, questions[0])
      )
  }

  function ReadyPendingQuestions(
    questions: seq<LowerStratumQuestion>,
    completedStratum: nat
  ): seq<RecursiveAction>
    decreases |questions|
  {
    if |questions| == 0 then
      []
    else
      (if questions[0].negativeStratum == completedStratum &&
          questions[0].negativeStratum < questions[0].parentStratum then
         [AskExactLowerStratum(questions[0])]
       else
         []) +
      ReadyPendingQuestions(questions[1..], completedStratum)
  }

  function RetainPendingQuestions(
    questions: seq<LowerStratumQuestion>,
    completedStratum: nat
  ): seq<LowerStratumQuestion>
    decreases |questions|
  {
    if |questions| == 0 then
      []
    else
      (if questions[0].negativeStratum == completedStratum &&
          questions[0].negativeStratum < questions[0].parentStratum then
         []
       else
         [questions[0]]) +
      RetainPendingQuestions(questions[1..], completedStratum)
  }

  function ApplyCommand(
    state: RecursiveState,
    positiveRules: seq<PositiveRule>,
    positiveEdges: seq<PositiveConsumerEdge>,
    strata: seq<ExpressionStratum>,
    exclusions: seq<ExclusionRule>,
    command: RecursiveCommand
  ): RecursiveTransition {
    match command
    case AdmitTypedFact(fact) =>
      if ContainsFact(state.facts, fact) then
        RecursiveTransitionAccepted(state, [], true)
      else
        var facts := state.facts + [fact];
        var questions := QuestionsForLeftFact(exclusions, strata, fact);
        var partitioned := PartitionQuestions(
                             questions,
                             state.completedStrata,
                             [],
                             state.pendingLowerQuestions
                           );
        RecursiveTransitionAccepted(
          RecursiveState(
            facts,
            RebuildAnchorStates(positiveRules, positiveEdges, facts),
            state.completedStrata,
            partitioned.1
          ),
          PositiveActions(
            positiveRules,
            positiveEdges,
            facts,
            fact.entityType,
            fact.entityEid
          ) + partitioned.0,
          false
        )

    case CompleteStratum(stratum) =>
      RecursiveTransitionAccepted(
        RecursiveState(
          state.facts,
          state.anchorStates,
          AppendNatIfMissing(state.completedStrata, stratum),
          RetainPendingQuestions(state.pendingLowerQuestions, stratum)
        ),
        ReadyPendingQuestions(state.pendingLowerQuestions, stratum),
        false
      )

    case ResolveExactLowerStratum(question) =>
      if question.negativeStratum >= question.parentStratum then
        RecursiveTransitionRejected(InvalidLowerStratum(question))
      else if !ContainsNat(
                state.completedStrata,
                question.negativeStratum
              ) then
        RecursiveTransitionRejected(IncompleteLowerStratum(question))
      else
        var negativeFact := TypedExpressionFact(
                              question.negativeExpression,
                              question.entityType,
                              question.entityEid
                            );
        var parentFact := TypedExpressionFact(
                            question.parentExpression,
                            question.entityType,
                            question.entityEid
                          );
        RecursiveTransitionAccepted(
          state,
          if !ContainsFact(state.facts, negativeFact) &&
             !ContainsFact(state.facts, parentFact) then
            [ScheduleTypedFact(parentFact)]
          else
            [],
          false
        )
  }

  lemma IncompleteLowerQuestionCannotScheduleAParent(
    state: RecursiveState,
    positiveRules: seq<PositiveRule>,
    positiveEdges: seq<PositiveConsumerEdge>,
    strata: seq<ExpressionStratum>,
    exclusions: seq<ExclusionRule>,
    question: LowerStratumQuestion
  )
    requires question.negativeStratum < question.parentStratum
    requires !ContainsNat(state.completedStrata, question.negativeStratum)
    ensures ApplyCommand(
              state,
              positiveRules,
              positiveEdges,
              strata,
              exclusions,
              ResolveExactLowerStratum(question)
            ).RecursiveTransitionRejected?
  {
  }

  lemma DuplicateFactIsIdempotent(
    state: RecursiveState,
    positiveRules: seq<PositiveRule>,
    positiveEdges: seq<PositiveConsumerEdge>,
    strata: seq<ExpressionStratum>,
    exclusions: seq<ExclusionRule>,
    fact: TypedExpressionFact
  )
    requires ContainsFact(state.facts, fact)
    ensures ApplyCommand(
              state,
              positiveRules,
              positiveEdges,
              strata,
              exclusions,
              AdmitTypedFact(fact)
            ) == RecursiveTransitionAccepted(state, [], true)
  {
  }
}
