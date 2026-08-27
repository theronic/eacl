include "OperatorRecursiveGeneratedPolicy.dfy"
include "AnchorGatedConjunction.dfy"
include "StratifiedExclusion.dfy"

// Proof-only bridge from the small generated recursive command model to the
// Phase-A conjunction and strict-exclusion semantics.
module OperatorRecursiveGeneratedPolicyRefinement {
  import Generated = OperatorRecursiveGeneratedPolicy
  import Anchor = AnchorGatedConjunction
  import Exclusion = StratifiedExclusion
  import opened CandidateCover

  function TypedEntity(entityType: nat, entityEid: nat): Entity {
    Entity(entityType, entityEid)
  }

  function RequiredSlots(width: nat): set<nat> {
    if width == 0 then {} else RequiredSlots(width - 1) + {width - 1}
  }

  lemma RequiredSlotsMembership(width: nat, slot: nat)
    ensures slot in RequiredSlots(width) <==> slot < width
    decreases width
  {
    if width != 0 {
      RequiredSlotsMembership(width - 1, slot);
    }
  }

  function AbstractSlotFacts(
    decisions: seq<bool>,
    entity: Entity
  ): set<Anchor.SlotFact> {
    set slot: nat | slot < |decisions| && decisions[slot] ::
      Anchor.SlotFact(entity, slot)
  }

  lemma GeneratedAllTrueContains(
    decisions: seq<bool>,
    slot: nat
  )
    requires slot < |decisions|
    requires Generated.AllTrue(decisions)
    ensures decisions[slot]
    decreases |decisions|
  {
    if slot != 0 {
      GeneratedAllTrueContains(decisions[1..], slot - 1);
    }
  }

  lemma EverySlotMeansGeneratedAllTrue(decisions: seq<bool>)
    requires forall slot | 0 <= slot < |decisions| :: decisions[slot]
    ensures Generated.AllTrue(decisions)
    decreases |decisions|
  {
    if |decisions| != 0 {
      assert forall slot | 0 <= slot < |decisions[1..]| ::
          decisions[1..][slot] == decisions[slot + 1];
      EverySlotMeansGeneratedAllTrue(decisions[1..]);
    }
  }

  lemma GeneratedAllTrueMeansEverySlot(decisions: seq<bool>)
    ensures Generated.AllTrue(decisions) <==>
            (forall slot | 0 <= slot < |decisions| :: decisions[slot])
  {
    if Generated.AllTrue(decisions) {
      forall slot | 0 <= slot < |decisions|
        ensures decisions[slot]
      {
        GeneratedAllTrueContains(decisions, slot);
      }
    } else if forall slot | 0 <= slot < |decisions| :: decisions[slot] {
      EverySlotMeansGeneratedAllTrue(decisions);
    }
  }

  lemma GeneratedIntersectionSlotsRefineAnchorModel(
    decisions: seq<bool>,
    entityType: nat,
    entityEid: nat
  )
    ensures Generated.AllTrue(decisions) <==>
            Anchor.AllRequiredSlotsPresent(
              AbstractSlotFacts(
                decisions,
                TypedEntity(entityType, entityEid)
              ),
              TypedEntity(entityType, entityEid),
              RequiredSlots(|decisions|)
            )
  {
    GeneratedAllTrueMeansEverySlot(decisions);
    if Generated.AllTrue(decisions) {
      forall slot | slot in RequiredSlots(|decisions|)
        ensures Anchor.SlotFact(
                  TypedEntity(entityType, entityEid),
                  slot
                ) in AbstractSlotFacts(
                       decisions,
                       TypedEntity(entityType, entityEid)
                     )
      {
        RequiredSlotsMembership(|decisions|, slot);
      }
    } else if Anchor.AllRequiredSlotsPresent(
        AbstractSlotFacts(
          decisions,
          TypedEntity(entityType, entityEid)
        ),
        TypedEntity(entityType, entityEid),
        RequiredSlots(|decisions|)
      ) {
      forall slot | 0 <= slot < |decisions|
        ensures decisions[slot]
      {
        RequiredSlotsMembership(|decisions|, slot);
        assert slot in RequiredSlots(|decisions|);
        assert Anchor.SlotFact(
            TypedEntity(entityType, entityEid),
            slot
          ) in AbstractSlotFacts(
                 decisions,
                 TypedEntity(entityType, entityEid)
               );
      }
    }
  }

  function NegativeDenotation(
    state: Generated.RecursiveState,
    question: Generated.LowerStratumQuestion
  ): set<Entity> {
    var negativeFact := Generated.TypedExpressionFact(
                          question.negativeExpression,
                          question.entityType,
                          question.entityEid
                        );
    if Generated.ContainsFact(state.facts, negativeFact) then
      {TypedEntity(question.entityType, question.entityEid)}
    else
      {}
  }

  predicate SchedulesParent(
    transition: Generated.RecursiveTransition,
    question: Generated.LowerStratumQuestion
  ) {
    transition.RecursiveTransitionAccepted? &&
    Generated.ScheduleTypedFact(
      Generated.TypedExpressionFact(
        question.parentExpression,
        question.entityType,
        question.entityEid
      )
    ) in transition.actions
  }

  lemma CompletedGeneratedLowerQuestionRefinesExactExclusion(
    state: Generated.RecursiveState,
    positiveRules: seq<Generated.PositiveRule>,
    positiveEdges: seq<Generated.PositiveConsumerEdge>,
    strata: seq<Generated.ExpressionStratum>,
    exclusions: seq<Generated.ExclusionRule>,
    question: Generated.LowerStratumQuestion
  )
    requires question.negativeStratum < question.parentStratum
    requires Generated.ContainsNat(
               state.completedStrata,
               question.negativeStratum
             )
    requires !Generated.ContainsFact(
               state.facts,
               Generated.TypedExpressionFact(
                 question.parentExpression,
                 question.entityType,
                 question.entityEid
               )
             )
    ensures Generated.ApplyCommand(
              state,
              positiveRules,
              positiveEdges,
              strata,
              exclusions,
              Generated.ResolveExactLowerStratum(question)
            ).RecursiveTransitionAccepted?
    ensures SchedulesParent(
              Generated.ApplyCommand(
                state,
                positiveRules,
                positiveEdges,
                strata,
                exclusions,
                Generated.ResolveExactLowerStratum(question)
              ),
              question
            ) <==>
            Exclusion.EvaluateExclusionPoint(
              question.parentStratum,
              question.negativeStratum,
              Exclusion.ExactComplete(
                {TypedEntity(question.entityType, question.entityEid)}
              ),
              Exclusion.ExactComplete(
                NegativeDenotation(state, question)
              ),
              TypedEntity(question.entityType, question.entityEid)
            ) == Exclusion.PointComplete(true)
  {
    Exclusion.CompletedLowerStratumExclusionIsExact(
      question.parentStratum,
      question.negativeStratum,
      {TypedEntity(question.entityType, question.entityEid)},
      NegativeDenotation(state, question)
    );
  }

  lemma IncompleteGeneratedLowerQuestionRefinesFailClosedExclusion(
    state: Generated.RecursiveState,
    positiveRules: seq<Generated.PositiveRule>,
    positiveEdges: seq<Generated.PositiveConsumerEdge>,
    strata: seq<Generated.ExpressionStratum>,
    exclusions: seq<Generated.ExclusionRule>,
    question: Generated.LowerStratumQuestion
  )
    requires question.negativeStratum < question.parentStratum
    requires !Generated.ContainsNat(
               state.completedStrata,
               question.negativeStratum
             )
    ensures Generated.ApplyCommand(
              state,
              positiveRules,
              positiveEdges,
              strata,
              exclusions,
              Generated.ResolveExactLowerStratum(question)
            ).RecursiveTransitionRejected?
    ensures Exclusion.EvaluateExclusionPoint(
              question.parentStratum,
              question.negativeStratum,
              Exclusion.ExactComplete(
                {TypedEntity(question.entityType, question.entityEid)}
              ),
              Exclusion.ActiveRecursion,
              TypedEntity(question.entityType, question.entityEid)
            ).PointFailed?
  {
    Generated.IncompleteLowerQuestionCannotScheduleAParent(
      state,
      positiveRules,
      positiveEdges,
      strata,
      exclusions,
      question
    );
    Exclusion.IncompleteNegativePremiseNeverAuthorizes(
      question.parentStratum,
      question.negativeStratum,
      Exclusion.ExactComplete(
        {TypedEntity(question.entityType, question.entityEid)}
      ),
      Exclusion.ActiveRecursion,
      TypedEntity(question.entityType, question.entityEid)
    );
  }

  lemma TypedFactIdentityDoesNotCollide(
    expression: nat,
    firstType: nat,
    secondType: nat,
    entityEid: nat
  )
    requires firstType != secondType
    ensures Generated.TypedExpressionFact(
              expression,
              firstType,
              entityEid
            ) !=
            Generated.TypedExpressionFact(
              expression,
              secondType,
              entityEid
            )
  {
  }
}
