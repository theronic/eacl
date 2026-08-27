include "CandidateCover.dfy"

// Arrival-order-independent anchor-gated conjunction state.
module AnchorGatedConjunction {
  import opened CandidateCover

  datatype SlotFact = SlotFact(entity: Entity, slot: nat)

  datatype JoinState = JoinState(
    childFacts: set<SlotFact>,
    anchoredEntities: set<Entity>,
    parentSlots: set<SlotFact>,
    derivedEntities: set<Entity>
  )

  function EntityUniverse(facts: set<SlotFact>): set<Entity> {
    set fact <- facts :: fact.entity
  }

  predicate AllRequiredSlotsPresent(
    facts: set<SlotFact>,
    entity: Entity,
    requiredSlots: set<nat>
  ) {
    forall slot <- requiredSlots :: SlotFact(entity, slot) in facts
  }

  function ExpectedAnchors(
    facts: set<SlotFact>,
    anchorSlot: nat
  ): set<Entity> {
    set fact <- facts | fact.slot == anchorSlot :: fact.entity
  }

  function FactsForAnchoredEntity(
    facts: set<SlotFact>,
    entity: Entity,
    requiredSlots: set<nat>
  ): set<SlotFact> {
    set fact <- facts |
        fact.entity == entity && fact.slot in requiredSlots
  }

  function ExpectedParentSlots(
    facts: set<SlotFact>,
    requiredSlots: set<nat>,
    anchorSlot: nat
  ): set<SlotFact> {
    var anchors := ExpectedAnchors(facts, anchorSlot);
    set fact <- facts |
        fact.entity in anchors && fact.slot in requiredSlots
  }

  function OrdinaryConjunctionFixedPoint(
    facts: set<SlotFact>,
    requiredSlots: set<nat>
  ): set<Entity> {
    set entity <- EntityUniverse(facts) |
        AllRequiredSlotsPresent(facts, entity, requiredSlots)
  }

  predicate NormalizedState(
    state: JoinState,
    requiredSlots: set<nat>,
    anchorSlot: nat
  ) {
    anchorSlot in requiredSlots &&
    state.anchoredEntities ==
    ExpectedAnchors(state.childFacts, anchorSlot) &&
    state.parentSlots ==
    ExpectedParentSlots(
      state.childFacts,
      requiredSlots,
      anchorSlot
    ) &&
    state.derivedEntities ==
    OrdinaryConjunctionFixedPoint(
      state.childFacts,
      requiredSlots
    )
  }

  function EmptyState(): JoinState {
    JoinState({}, {}, {}, {})
  }

  lemma EmptyStateIsNormalized(
    requiredSlots: set<nat>,
    anchorSlot: nat
  )
    requires anchorSlot in requiredSlots
    ensures NormalizedState(EmptyState(), requiredSlots, anchorSlot)
  {
  }

  function ApplyFact(
    state: JoinState,
    event: SlotFact,
    requiredSlots: set<nat>,
    anchorSlot: nat
  ): JoinState {
    var facts := state.childFacts + {event};
    var anchors := state.anchoredEntities +
                   (if event.slot == anchorSlot then {event.entity} else {});
    var parentSlots := state.parentSlots +
                       (if event.entity in anchors then
                          FactsForAnchoredEntity(facts, event.entity, requiredSlots)
                        else
                          {});
    var derived := state.derivedEntities +
                   (if AllRequiredSlotsPresent(
                         facts,
                         event.entity,
                         requiredSlots
                       ) then
                      {event.entity}
                    else
                      {});
    JoinState(facts, anchors, parentSlots, derived)
  }

  lemma ExpectedAnchorsAfterEvent(
    facts: set<SlotFact>,
    event: SlotFact,
    anchorSlot: nat
  )
    ensures ExpectedAnchors(facts + {event}, anchorSlot) ==
            ExpectedAnchors(facts, anchorSlot) +
            (if event.slot == anchorSlot then {event.entity} else {})
  {
  }

  lemma ExpectedParentSlotsAfterEvent(
    facts: set<SlotFact>,
    event: SlotFact,
    requiredSlots: set<nat>,
    anchorSlot: nat
  )
    ensures ExpectedParentSlots(
              facts + {event},
              requiredSlots,
              anchorSlot
            ) ==
            ExpectedParentSlots(facts, requiredSlots, anchorSlot) +
            (if event.entity in ExpectedAnchors(
                                  facts + {event},
                                  anchorSlot
                                ) then
               FactsForAnchoredEntity(
                 facts + {event},
                 event.entity,
                 requiredSlots
               )
             else
               {})
  {
    ExpectedAnchorsAfterEvent(facts, event, anchorSlot);
  }

  lemma OrdinaryConjunctionAfterEvent(
    facts: set<SlotFact>,
    event: SlotFact,
    requiredSlots: set<nat>
  )
    ensures OrdinaryConjunctionFixedPoint(
              facts + {event},
              requiredSlots
            ) ==
            OrdinaryConjunctionFixedPoint(facts, requiredSlots) +
            (if AllRequiredSlotsPresent(
                  facts + {event},
                  event.entity,
                  requiredSlots
                ) then
               {event.entity}
             else
               {})
  {
  }

  lemma ApplyFactPreservesNormalization(
    state: JoinState,
    event: SlotFact,
    requiredSlots: set<nat>,
    anchorSlot: nat
  )
    requires NormalizedState(state, requiredSlots, anchorSlot)
    ensures NormalizedState(
              ApplyFact(state, event, requiredSlots, anchorSlot),
              requiredSlots,
              anchorSlot
            )
  {
    ExpectedAnchorsAfterEvent(state.childFacts, event, anchorSlot);
    ExpectedParentSlotsAfterEvent(
      state.childFacts,
      event,
      requiredSlots,
      anchorSlot
    );
    OrdinaryConjunctionAfterEvent(
      state.childFacts,
      event,
      requiredSlots
    );
  }

  function Run(
    events: seq<SlotFact>,
    state: JoinState,
    requiredSlots: set<nat>,
    anchorSlot: nat
  ): JoinState
    decreases |events|
  {
    if |events| == 0 then
      state
    else
      Run(
        events[1..],
        ApplyFact(state, events[0], requiredSlots, anchorSlot),
        requiredSlots,
        anchorSlot
      )
  }

  function EventSet(events: seq<SlotFact>): set<SlotFact> {
    set event <- events :: event
  }

  lemma EventSetDecomposes(events: seq<SlotFact>)
    requires |events| != 0
    ensures EventSet(events) == {events[0]} + EventSet(events[1..])
  {
    forall event: SlotFact
      ensures event in EventSet(events) <==>
              event in {events[0]} + EventSet(events[1..])
    {
      if event in events && event != events[0] {
        assert event in events[1..];
      }
      if event in events[1..] {
        assert event in events;
      }
    }
  }

  lemma RunAccumulatesExactChildFacts(
    events: seq<SlotFact>,
    state: JoinState,
    requiredSlots: set<nat>,
    anchorSlot: nat
  )
    ensures Run(
              events,
              state,
              requiredSlots,
              anchorSlot
            ).childFacts == state.childFacts + EventSet(events)
    decreases |events|
  {
    if |events| != 0 {
      EventSetDecomposes(events);
      RunAccumulatesExactChildFacts(
        events[1..],
        ApplyFact(state, events[0], requiredSlots, anchorSlot),
        requiredSlots,
        anchorSlot
      );
      assert ApplyFact(
          state,
          events[0],
          requiredSlots,
          anchorSlot
        ).childFacts == state.childFacts + {events[0]};
    }
  }

  lemma RunPreservesNormalization(
    events: seq<SlotFact>,
    state: JoinState,
    requiredSlots: set<nat>,
    anchorSlot: nat
  )
    requires NormalizedState(state, requiredSlots, anchorSlot)
    ensures NormalizedState(
              Run(events, state, requiredSlots, anchorSlot),
              requiredSlots,
              anchorSlot
            )
    decreases |events|
  {
    if |events| != 0 {
      ApplyFactPreservesNormalization(
        state,
        events[0],
        requiredSlots,
        anchorSlot
      );
      RunPreservesNormalization(
        events[1..],
        ApplyFact(state, events[0], requiredSlots, anchorSlot),
        requiredSlots,
        anchorSlot
      );
    }
  }

  lemma EveryArrivalOrderDerivesTheOrdinaryFixedPoint(
    events: seq<SlotFact>,
    requiredSlots: set<nat>,
    anchorSlot: nat
  )
    requires anchorSlot in requiredSlots
    ensures var result := Run(
                            events,
                            EmptyState(),
                            requiredSlots,
                            anchorSlot
                          );
            result.derivedEntities ==
            OrdinaryConjunctionFixedPoint(
              EventSet(events),
              requiredSlots
            )
  {
    EmptyStateIsNormalized(requiredSlots, anchorSlot);
    RunPreservesNormalization(
      events,
      EmptyState(),
      requiredSlots,
      anchorSlot
    );
    RunAccumulatesExactChildFacts(
      events,
      EmptyState(),
      requiredSlots,
      anchorSlot
    );
  }

  lemma EqualEventSetsProduceEqualDerivedFacts(
    leftEvents: seq<SlotFact>,
    rightEvents: seq<SlotFact>,
    requiredSlots: set<nat>,
    anchorSlot: nat
  )
    requires anchorSlot in requiredSlots
    requires EventSet(leftEvents) == EventSet(rightEvents)
    ensures Run(
              leftEvents,
              EmptyState(),
              requiredSlots,
              anchorSlot
            ).derivedEntities ==
            Run(
              rightEvents,
              EmptyState(),
              requiredSlots,
              anchorSlot
            ).derivedEntities
  {
    EveryArrivalOrderDerivesTheOrdinaryFixedPoint(
      leftEvents,
      requiredSlots,
      anchorSlot
    );
    EveryArrivalOrderDerivesTheOrdinaryFixedPoint(
      rightEvents,
      requiredSlots,
      anchorSlot
    );
  }

  lemma DuplicateFactsAreIdempotent(
    state: JoinState,
    event: SlotFact,
    requiredSlots: set<nat>,
    anchorSlot: nat
  )
    requires NormalizedState(state, requiredSlots, anchorSlot)
    ensures ApplyFact(
              ApplyFact(state, event, requiredSlots, anchorSlot),
              event,
              requiredSlots,
              anchorSlot
            ) ==
            ApplyFact(state, event, requiredSlots, anchorSlot)
  {
    ApplyFactPreservesNormalization(
      state,
      event,
      requiredSlots,
      anchorSlot
    );
  }

  lemma LateAnchorInitializesEveryExistingPremise(
    state: JoinState,
    entity: Entity,
    requiredSlots: set<nat>,
    anchorSlot: nat
  )
    requires NormalizedState(state, requiredSlots, anchorSlot)
    requires entity !in state.anchoredEntities
    ensures FactsForAnchoredEntity(
              state.childFacts,
              entity,
              requiredSlots
            ) <=
            ApplyFact(
              state,
              SlotFact(entity, anchorSlot),
              requiredSlots,
              anchorSlot
            ).parentSlots
  {
  }

  lemma ParentJoinStateExistsOnlyForAnchoredEntities(
    state: JoinState,
    requiredSlots: set<nat>,
    anchorSlot: nat
  )
    requires NormalizedState(state, requiredSlots, anchorSlot)
    ensures forall fact <- state.parentSlots ::
              fact.entity in state.anchoredEntities
  {
  }

  datatype RecursiveConjunctionRule = RecursiveConjunctionRule(
    headSlot: nat,
    requiredSlots: set<nat>,
    anchorSlot: nat
  )

  predicate WellFormedRecursiveConjunctionRule(
    rule: RecursiveConjunctionRule
  ) {
    0 < |rule.requiredSlots| && rule.anchorSlot in rule.requiredSlots
  }

  predicate WellFormedRecursiveConjunctionRules(
    rules: set<RecursiveConjunctionRule>
  ) {
    forall rule <- rules :: WellFormedRecursiveConjunctionRule(rule)
  }

  datatype RecursiveParentSlot = RecursiveParentSlot(
    rule: RecursiveConjunctionRule,
    entity: Entity,
    slot: nat
  )

  function MaterializedRecursiveParentSlots(
    rules: set<RecursiveConjunctionRule>,
    facts: set<SlotFact>
  ): set<RecursiveParentSlot> {
    set fact <- facts, rule <- rules |
        fact.slot in rule.requiredSlots &&
        SlotFact(fact.entity, rule.anchorSlot) in facts ::
      RecursiveParentSlot(rule, fact.entity, fact.slot)
  }

  // This is the retained recursive join state, rather than a denotational
  // reconstruction used only after evaluation.  The normalization invariant
  // says that a retained slot exists exactly when both its child fact and the
  // rule's anchor fact have already been admitted.
  datatype RecursiveJoinState = RecursiveJoinState(
    facts: set<SlotFact>,
    parentSlots: set<RecursiveParentSlot>
  )

  predicate NormalizedRecursiveJoinState(
    state: RecursiveJoinState,
    rules: set<RecursiveConjunctionRule>
  ) {
    state.parentSlots ==
    MaterializedRecursiveParentSlots(rules, state.facts)
  }

  function EmptyRecursiveJoinState(): RecursiveJoinState {
    RecursiveJoinState({}, {})
  }

  lemma EmptyRecursiveJoinStateIsNormalized(
    rules: set<RecursiveConjunctionRule>
  )
    ensures NormalizedRecursiveJoinState(
              EmptyRecursiveJoinState(),
              rules
            )
  {
  }

  // Only slots whose materialization can have changed because of this event
  // are considered: the event is either the newly arrived premise or the
  // newly arrived anchor that releases already retained child facts.
  function RecursiveParentSlotDelta(
    rules: set<RecursiveConjunctionRule>,
    facts: set<SlotFact>,
    event: SlotFact
  ): set<RecursiveParentSlot> {
    set fact <- facts + {event}, rule <- rules |
        fact.entity == event.entity &&
        fact.slot in rule.requiredSlots &&
        (fact == event || event.slot == rule.anchorSlot) &&
        SlotFact(event.entity, rule.anchorSlot) in facts + {event} ::
      RecursiveParentSlot(rule, event.entity, fact.slot)
  }

  function ApplyRecursiveFact(
    state: RecursiveJoinState,
    rules: set<RecursiveConjunctionRule>,
    event: SlotFact
  ): RecursiveJoinState {
    RecursiveJoinState(
      state.facts + {event},
      state.parentSlots +
      RecursiveParentSlotDelta(rules, state.facts, event)
    )
  }

  lemma RecursiveParentSlotDeltaIsExact(
    rules: set<RecursiveConjunctionRule>,
    facts: set<SlotFact>,
    event: SlotFact
  )
    ensures MaterializedRecursiveParentSlots(
              rules,
              facts + {event}
            ) ==
            MaterializedRecursiveParentSlots(rules, facts) +
            RecursiveParentSlotDelta(rules, facts, event)
  {
    forall retained: RecursiveParentSlot
      ensures retained in MaterializedRecursiveParentSlots(
                            rules,
                            facts + {event}
                          ) <==>
              retained in MaterializedRecursiveParentSlots(rules, facts) +
                          RecursiveParentSlotDelta(rules, facts, event)
    {
      if retained in MaterializedRecursiveParentSlots(
                       rules,
                       facts + {event}
                     ) &&
         retained !in MaterializedRecursiveParentSlots(rules, facts) {
        var sourceFact := SlotFact(retained.entity, retained.slot);
        var anchorFact := SlotFact(
          retained.entity,
          retained.rule.anchorSlot
        );
        assert sourceFact in facts + {event};
        assert anchorFact in facts + {event};
        if sourceFact != event && anchorFact != event {
          assert sourceFact in facts;
          assert anchorFact in facts;
          assert retained in MaterializedRecursiveParentSlots(
                               rules,
                               facts
                             );
          assert false;
        }
        assert sourceFact == event ||
               event.slot == retained.rule.anchorSlot;
        assert retained.entity == event.entity;
        assert retained in RecursiveParentSlotDelta(
                             rules,
                             facts,
                             event
                           );
      }
      if retained in RecursiveParentSlotDelta(rules, facts, event) {
        assert retained in MaterializedRecursiveParentSlots(
                             rules,
                             facts + {event}
                           );
      }
      if retained in MaterializedRecursiveParentSlots(rules, facts) {
        assert retained in MaterializedRecursiveParentSlots(
                             rules,
                             facts + {event}
                           );
      }
    }
  }

  lemma ApplyRecursiveFactPreservesNormalization(
    state: RecursiveJoinState,
    rules: set<RecursiveConjunctionRule>,
    event: SlotFact
  )
    requires NormalizedRecursiveJoinState(state, rules)
    ensures NormalizedRecursiveJoinState(
              ApplyRecursiveFact(state, rules, event),
              rules
            )
  {
    RecursiveParentSlotDeltaIsExact(rules, state.facts, event);
  }

  lemma DuplicateRecursiveFactIsIdempotent(
    state: RecursiveJoinState,
    rules: set<RecursiveConjunctionRule>,
    event: SlotFact
  )
    requires NormalizedRecursiveJoinState(state, rules)
    ensures ApplyRecursiveFact(
              ApplyRecursiveFact(state, rules, event),
              rules,
              event
            ) == ApplyRecursiveFact(state, rules, event)
  {
    ApplyRecursiveFactPreservesNormalization(state, rules, event);
    RecursiveParentSlotDeltaIsExact(rules, state.facts, event);
    RecursiveParentSlotDeltaIsExact(
      rules,
      state.facts + {event},
      event
    );
  }

  function RunRecursiveFactEvents(
    events: seq<SlotFact>,
    state: RecursiveJoinState,
    rules: set<RecursiveConjunctionRule>
  ): RecursiveJoinState
    decreases |events|
  {
    if |events| == 0 then
      state
    else
      RunRecursiveFactEvents(
        events[1..],
        ApplyRecursiveFact(state, rules, events[0]),
        rules
      )
  }

  lemma RunRecursiveFactEventsAccumulatesExactly(
    events: seq<SlotFact>,
    state: RecursiveJoinState,
    rules: set<RecursiveConjunctionRule>
  )
    requires NormalizedRecursiveJoinState(state, rules)
    ensures NormalizedRecursiveJoinState(
              RunRecursiveFactEvents(events, state, rules),
              rules
            )
    ensures RunRecursiveFactEvents(events, state, rules).facts ==
            state.facts + EventSet(events)
    decreases |events|
  {
    if |events| != 0 {
      ApplyRecursiveFactPreservesNormalization(
        state,
        rules,
        events[0]
      );
      RunRecursiveFactEventsAccumulatesExactly(
        events[1..],
        ApplyRecursiveFact(state, rules, events[0]),
        rules
      );
      EventSetDecomposes(events);
    }
  }

  lemma EqualRecursiveArrivalSetsProduceEqualRetainedState(
    leftEvents: seq<SlotFact>,
    rightEvents: seq<SlotFact>,
    rules: set<RecursiveConjunctionRule>
  )
    requires EventSet(leftEvents) == EventSet(rightEvents)
    ensures RunRecursiveFactEvents(
              leftEvents,
              EmptyRecursiveJoinState(),
              rules
            ) ==
            RunRecursiveFactEvents(
              rightEvents,
              EmptyRecursiveJoinState(),
              rules
            )
  {
    EmptyRecursiveJoinStateIsNormalized(rules);
    RunRecursiveFactEventsAccumulatesExactly(
      leftEvents,
      EmptyRecursiveJoinState(),
      rules
    );
    RunRecursiveFactEventsAccumulatesExactly(
      rightEvents,
      EmptyRecursiveJoinState(),
      rules
    );
  }

  predicate MaterializedRecursiveRuleReady(
    rule: RecursiveConjunctionRule,
    entity: Entity,
    parentSlots: set<RecursiveParentSlot>
  ) {
    forall slot <- rule.requiredSlots ::
      RecursiveParentSlot(rule, entity, slot) in parentSlots
  }

  predicate RecursiveRuleReady(
    rule: RecursiveConjunctionRule,
    entity: Entity,
    facts: set<SlotFact>
  ) {
    AllRequiredSlotsPresent(facts, entity, rule.requiredSlots)
  }

  predicate AnchorGatedRuleReady(
    rule: RecursiveConjunctionRule,
    entity: Entity,
    facts: set<SlotFact>
  ) {
    SlotFact(entity, rule.anchorSlot) in facts &&
    AllRequiredSlotsPresent(facts, entity, rule.requiredSlots)
  }

  function OrdinaryImmediateConsequences(
    rules: set<RecursiveConjunctionRule>,
    universe: set<SlotFact>,
    facts: set<SlotFact>
  ): set<SlotFact> {
    facts +
    set candidate <- universe |
        exists rule <- rules ::
          candidate.slot == rule.headSlot &&
          RecursiveRuleReady(rule, candidate.entity, facts) :: candidate
  }

  function AnchorGatedImmediateConsequences(
    rules: set<RecursiveConjunctionRule>,
    universe: set<SlotFact>,
    facts: set<SlotFact>
  ): set<SlotFact> {
    facts +
    set candidate <- universe |
        exists rule <- rules ::
          candidate.slot == rule.headSlot &&
          AnchorGatedRuleReady(rule, candidate.entity, facts) :: candidate
  }

  function MaterializedAnchorImmediateConsequences(
    rules: set<RecursiveConjunctionRule>,
    universe: set<SlotFact>,
    facts: set<SlotFact>
  ): set<SlotFact> {
    var parentSlots := MaterializedRecursiveParentSlots(rules, facts);
    facts +
    set candidate <- universe |
        exists rule <- rules ::
          candidate.slot == rule.headSlot &&
          MaterializedRecursiveRuleReady(
            rule,
            candidate.entity,
            parentSlots
          ) :: candidate
  }

  lemma MaterializedParentStateExistsOnlyForAnchoredEntities(
    rules: set<RecursiveConjunctionRule>,
    facts: set<SlotFact>
  )
    ensures forall retained <-
                     MaterializedRecursiveParentSlots(rules, facts) ::
              retained.rule in rules &&
              retained.slot in retained.rule.requiredSlots &&
              SlotFact(
                retained.entity,
                retained.rule.anchorSlot
              ) in facts &&
              SlotFact(retained.entity, retained.slot) in facts
  {
  }

  lemma RecursiveLateAnchorMaterializesEveryExistingPremise(
    rules: set<RecursiveConjunctionRule>,
    facts: set<SlotFact>,
    rule: RecursiveConjunctionRule,
    entity: Entity
  )
    requires rule in rules
    ensures forall slot <- rule.requiredSlots ::
              SlotFact(entity, slot) in facts ==>
                RecursiveParentSlot(rule, entity, slot) in
                  MaterializedRecursiveParentSlots(
                    rules,
                    facts + {SlotFact(entity, rule.anchorSlot)}
                  )
  {
  }

  lemma MaterializedAnchorStateDoesNotChangeImmediateConsequences(
    rules: set<RecursiveConjunctionRule>,
    universe: set<SlotFact>,
    facts: set<SlotFact>
  )
    requires WellFormedRecursiveConjunctionRules(rules)
    ensures MaterializedAnchorImmediateConsequences(
              rules,
              universe,
              facts
            ) == OrdinaryImmediateConsequences(
                   rules,
                   universe,
                   facts
                 )
  {
    forall candidate: SlotFact
      ensures candidate in MaterializedAnchorImmediateConsequences(
                             rules,
                             universe,
                             facts
                           ) <==>
              candidate in OrdinaryImmediateConsequences(
                             rules,
                             universe,
                             facts
                           )
    {
      if candidate !in facts {
        if candidate in OrdinaryImmediateConsequences(
                          rules,
                          universe,
                          facts
                        ) {
          var rule :|
            rule in rules &&
            candidate.slot == rule.headSlot &&
            RecursiveRuleReady(rule, candidate.entity, facts);
          assert SlotFact(candidate.entity, rule.anchorSlot) in facts;
          assert MaterializedRecursiveRuleReady(
              rule,
              candidate.entity,
              MaterializedRecursiveParentSlots(rules, facts)
            );
        } else if candidate in MaterializedAnchorImmediateConsequences(
                                 rules,
                                 universe,
                                 facts
                               ) {
          var rule :|
            rule in rules &&
            candidate.slot == rule.headSlot &&
            MaterializedRecursiveRuleReady(
              rule,
              candidate.entity,
              MaterializedRecursiveParentSlots(rules, facts)
            );
          assert RecursiveRuleReady(rule, candidate.entity, facts);
        }
      }
    }
  }

  lemma WellFormedAnchorGateDoesNotChangeImmediateConsequences(
    rules: set<RecursiveConjunctionRule>,
    universe: set<SlotFact>,
    facts: set<SlotFact>
  )
    requires WellFormedRecursiveConjunctionRules(rules)
    ensures AnchorGatedImmediateConsequences(rules, universe, facts) ==
            OrdinaryImmediateConsequences(rules, universe, facts)
  {
    forall candidate: SlotFact
      ensures candidate in AnchorGatedImmediateConsequences(
                             rules,
                             universe,
                             facts
                           ) <==>
              candidate in OrdinaryImmediateConsequences(
                             rules,
                             universe,
                             facts
                           )
    {
      if candidate !in facts {
        if candidate in OrdinaryImmediateConsequences(
                          rules,
                          universe,
                          facts
                        ) {
          var rule :|
            rule in rules &&
            candidate.slot == rule.headSlot &&
            RecursiveRuleReady(rule, candidate.entity, facts);
          assert rule.anchorSlot in rule.requiredSlots;
          assert SlotFact(candidate.entity, rule.anchorSlot) in facts;
        }
      }
    }
  }

  lemma AllRequiredSlotsRemainPresent(
    smaller: set<SlotFact>,
    larger: set<SlotFact>,
    entity: Entity,
    requiredSlots: set<nat>
  )
    requires smaller <= larger
    requires AllRequiredSlotsPresent(smaller, entity, requiredSlots)
    ensures AllRequiredSlotsPresent(larger, entity, requiredSlots)
  {
  }

  lemma AnchorImmediateConsequencesAreMonotone(
    rules: set<RecursiveConjunctionRule>,
    universe: set<SlotFact>,
    smaller: set<SlotFact>,
    larger: set<SlotFact>
  )
    requires smaller <= larger
    ensures AnchorGatedImmediateConsequences(
              rules,
              universe,
              smaller
            ) <=
            AnchorGatedImmediateConsequences(
              rules,
              universe,
              larger
            )
  {
    forall fact |
      fact in AnchorGatedImmediateConsequences(rules, universe, smaller)
      ensures fact in AnchorGatedImmediateConsequences(
                        rules,
                        universe,
                        larger
                      )
    {
      if fact !in smaller {
        var rule :|
          rule in rules &&
          fact.slot == rule.headSlot &&
          AnchorGatedRuleReady(rule, fact.entity, smaller);
        AllRequiredSlotsRemainPresent(
          smaller,
          larger,
          fact.entity,
          rule.requiredSlots
        );
      }
    }
  }

  // Recursive evaluation consumes the retained state directly.  This keeps
  // the performance-relevant parent-slot representation inside the
  // transition relation whose least fixed point is proved, rather than
  // proving only a denotational anchor predicate and reconstructing state
  // afterwards.
  function OperationalRecursiveImmediateConsequences(
    rules: set<RecursiveConjunctionRule>,
    universe: set<SlotFact>,
    state: RecursiveJoinState
  ): set<SlotFact> {
    state.facts +
    set candidate <- universe |
        exists rule <- rules ::
          candidate.slot == rule.headSlot &&
          MaterializedRecursiveRuleReady(
            rule,
            candidate.entity,
            state.parentSlots
          ) :: candidate
  }

  lemma NormalizedOperationalStepEqualsOrdinaryStep(
    rules: set<RecursiveConjunctionRule>,
    universe: set<SlotFact>,
    state: RecursiveJoinState
  )
    requires NormalizedRecursiveJoinState(state, rules)
    requires WellFormedRecursiveConjunctionRules(rules)
    ensures OperationalRecursiveImmediateConsequences(
              rules,
              universe,
              state
            ) ==
            OrdinaryImmediateConsequences(
              rules,
              universe,
              state.facts
            )
  {
    MaterializedAnchorStateDoesNotChangeImmediateConsequences(
      rules,
      universe,
      state.facts
    );
  }

  method IterateOperationalRecursiveJoinState(
    rules: set<RecursiveConjunctionRule>,
    universe: set<SlotFact>,
    current: RecursiveJoinState
  ) returns (completed: RecursiveJoinState)
    requires WellFormedRecursiveConjunctionRules(rules)
    requires NormalizedRecursiveJoinState(current, rules)
    requires current.facts <= universe
    ensures NormalizedRecursiveJoinState(completed, rules)
    ensures current.facts <= completed.facts <= universe
    ensures OperationalRecursiveImmediateConsequences(
              rules,
              universe,
              completed
            ) == completed.facts
    ensures OrdinaryImmediateConsequences(
              rules,
              universe,
              completed.facts
            ) == completed.facts
    ensures forall closed: set<SlotFact> |
              current.facts <= closed <= universe &&
              OrdinaryImmediateConsequences(
                rules,
                universe,
                closed
              ) == closed ::
              completed.facts <= closed
    ensures forall retained <- completed.parentSlots ::
              SlotFact(
                retained.entity,
                retained.rule.anchorSlot
              ) in completed.facts &&
              SlotFact(retained.entity, retained.slot) in completed.facts
    decreases universe - current.facts
  {
    var nextFacts := OperationalRecursiveImmediateConsequences(
      rules,
      universe,
      current
    );
    NormalizedOperationalStepEqualsOrdinaryStep(
      rules,
      universe,
      current
    );
    assert current.facts <= nextFacts <= universe;

    if nextFacts == current.facts {
      completed := current;
      MaterializedParentStateExistsOnlyForAnchoredEntities(
        rules,
        completed.facts
      );
      return;
    }

    assert current.facts < nextFacts;
    assert universe - nextFacts < universe - current.facts;
    var next := RecursiveJoinState(
      nextFacts,
      MaterializedRecursiveParentSlots(rules, nextFacts)
    );
    assert NormalizedRecursiveJoinState(next, rules);
    completed := IterateOperationalRecursiveJoinState(
      rules,
      universe,
      next
    );

    forall closed: set<SlotFact> |
      current.facts <= closed <= universe &&
      OrdinaryImmediateConsequences(
        rules,
        universe,
        closed
      ) == closed
      ensures completed.facts <= closed
    {
      WellFormedAnchorGateDoesNotChangeImmediateConsequences(
        rules,
        universe,
        current.facts
      );
      WellFormedAnchorGateDoesNotChangeImmediateConsequences(
        rules,
        universe,
        closed
      );
      AnchorImmediateConsequencesAreMonotone(
        rules,
        universe,
        current.facts,
        closed
      );
      assert nextFacts <= closed;
    }
  }

  method EvaluateOperationalRecursiveConjunction(
    events: seq<SlotFact>,
    rules: set<RecursiveConjunctionRule>,
    universe: set<SlotFact>
  ) returns (completed: RecursiveJoinState)
    requires EventSet(events) <= universe
    requires WellFormedRecursiveConjunctionRules(rules)
    ensures EventSet(events) <= completed.facts <= universe
    ensures NormalizedRecursiveJoinState(completed, rules)
    ensures OrdinaryImmediateConsequences(
              rules,
              universe,
              completed.facts
            ) == completed.facts
    ensures forall closed: set<SlotFact> |
              EventSet(events) <= closed <= universe &&
              OrdinaryImmediateConsequences(
                rules,
                universe,
                closed
              ) == closed ::
              completed.facts <= closed
    ensures forall retained <- completed.parentSlots ::
              SlotFact(
                retained.entity,
                retained.rule.anchorSlot
              ) in completed.facts
  {
    EmptyRecursiveJoinStateIsNormalized(rules);
    var admitted := RunRecursiveFactEvents(
      events,
      EmptyRecursiveJoinState(),
      rules
    );
    RunRecursiveFactEventsAccumulatesExactly(
      events,
      EmptyRecursiveJoinState(),
      rules
    );
    completed := IterateOperationalRecursiveJoinState(
      rules,
      universe,
      admitted
    );
  }

  method IterateAnchorGatedRules(
    rules: set<RecursiveConjunctionRule>,
    universe: set<SlotFact>,
    current: set<SlotFact>
  ) returns (completed: set<SlotFact>)
    requires current <= universe
    ensures current <= completed <= universe
    ensures AnchorGatedImmediateConsequences(
              rules,
              universe,
              completed
            ) == completed
    ensures forall closed: set<SlotFact> |
              current <= closed <= universe &&
              AnchorGatedImmediateConsequences(
                rules,
                universe,
                closed
              ) == closed ::
              completed <= closed
    decreases universe - current
  {
    var next := AnchorGatedImmediateConsequences(
      rules,
      universe,
      current
    );
    assert current <= next <= universe;
    if next == current {
      return current;
    }
    assert current < next;
    assert universe - next < universe - current;
    completed := IterateAnchorGatedRules(
      rules,
      universe,
      next
    );
    forall closed: set<SlotFact> |
      current <= closed <= universe &&
      AnchorGatedImmediateConsequences(
        rules,
        universe,
        closed
      ) == closed
      ensures completed <= closed
    {
      AnchorImmediateConsequencesAreMonotone(
        rules,
        universe,
        current,
        closed
      );
      assert next <= closed;
    }
  }

  method EvaluateAnchorGatedRecursiveConjunction(
    events: seq<SlotFact>,
    rules: set<RecursiveConjunctionRule>,
    universe: set<SlotFact>
  ) returns (completed: set<SlotFact>)
    requires EventSet(events) <= universe
    requires WellFormedRecursiveConjunctionRules(rules)
    ensures EventSet(events) <= completed <= universe
    ensures AnchorGatedImmediateConsequences(
              rules,
              universe,
              completed
            ) == completed
    ensures OrdinaryImmediateConsequences(
              rules,
              universe,
              completed
            ) == completed
    ensures MaterializedAnchorImmediateConsequences(
              rules,
              universe,
              completed
            ) == completed
    ensures forall closed: set<SlotFact> |
              EventSet(events) <= closed <= universe &&
              OrdinaryImmediateConsequences(
                rules,
                universe,
                closed
              ) == closed ::
              completed <= closed
  {
    var operational := EvaluateOperationalRecursiveConjunction(
      events,
      rules,
      universe
    );
    completed := operational.facts;
    WellFormedAnchorGateDoesNotChangeImmediateConsequences(
      rules,
      universe,
      completed
    );
    MaterializedAnchorStateDoesNotChangeImmediateConsequences(
      rules,
      universe,
      completed
    );
    forall closed: set<SlotFact> |
      EventSet(events) <= closed <= universe &&
      OrdinaryImmediateConsequences(
        rules,
        universe,
        closed
      ) == closed
      ensures completed <= closed
    {
      WellFormedAnchorGateDoesNotChangeImmediateConsequences(
        rules,
        universe,
        closed
      );
    }
  }

  method EqualArrivalSetsProduceEqualRecursiveLeastFixedPoints(
    leftEvents: seq<SlotFact>,
    rightEvents: seq<SlotFact>,
    rules: set<RecursiveConjunctionRule>,
    universe: set<SlotFact>
  ) returns (
      leftCompleted: set<SlotFact>,
      rightCompleted: set<SlotFact>
    )
    requires EventSet(leftEvents) == EventSet(rightEvents)
    requires EventSet(leftEvents) <= universe
    requires WellFormedRecursiveConjunctionRules(rules)
    ensures leftCompleted == rightCompleted
  {
    leftCompleted := EvaluateAnchorGatedRecursiveConjunction(
      leftEvents,
      rules,
      universe
    );
    rightCompleted := EvaluateAnchorGatedRecursiveConjunction(
      rightEvents,
      rules,
      universe
    );
    assert leftCompleted <= rightCompleted;
    assert rightCompleted <= leftCompleted;
  }

  lemma TypedEntityIdentityPreventsCrossTypeJoin(
    leftType: nat,
    rightType: nat,
    objectId: nat
  )
    requires leftType != rightType
    ensures Entity(leftType, objectId) != Entity(rightType, objectId)
    ensures forall slot: nat ::
              SlotFact(Entity(leftType, objectId), slot) !=
              SlotFact(Entity(rightType, objectId), slot)
  {
  }
}
