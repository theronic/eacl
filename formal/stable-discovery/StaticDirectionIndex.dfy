// Exploratory proof that one immutable sealed rule vector can be filtered into
// exact forward-consumer and reverse-producer indexes. Both directions share
// one small cursor theorem; no dynamic grant/consumer join is required.
module StaticDirectionIndex {
  datatype Direction = Forward | Reverse

  datatype Rule = Rule(id: nat, target: nat, head: nat)

  function RuleSet(rules: seq<Rule>): set<Rule>
    decreases |rules|
  {
    if |rules| == 0 then {}
    else {rules[0]} + RuleSet(rules[1..])
  }

  function RuleIds(rules: seq<Rule>): set<nat>
    decreases |rules|
  {
    if |rules| == 0 then {}
    else {rules[0].id} + RuleIds(rules[1..])
  }

  predicate UniqueRuleIds(rules: seq<Rule>)
    decreases |rules|
  {
    |rules| == 0 ||
    (rules[0].id !in RuleIds(rules[1..]) &&
     UniqueRuleIds(rules[1..]))
  }

  function IndexNode(rule: Rule, direction: Direction): nat {
    match direction
    case Forward => rule.target
    case Reverse => rule.head
  }

  function IndexedRules(
    rules: seq<Rule>,
    direction: Direction,
    node: nat
  ): seq<Rule>
    decreases |rules|
  {
    if |rules| == 0 then []
    else if IndexNode(rules[0], direction) == node then
      [rules[0]] + IndexedRules(rules[1..], direction, node)
    else
      IndexedRules(rules[1..], direction, node)
  }

  lemma RuleSetHeadTail(rules: seq<Rule>)
    requires |rules| > 0
    ensures RuleSet(rules) == {rules[0]} + RuleSet(rules[1..])
  {
  }

  lemma StaticIndexMembershipExact(
    rules: seq<Rule>,
    direction: Direction,
    node: nat,
    rule: Rule
  )
    ensures rule in RuleSet(IndexedRules(rules, direction, node)) <==>
            rule in RuleSet(rules) &&
            IndexNode(rule, direction) == node
    decreases |rules|
  {
    if |rules| > 0 {
      RuleSetHeadTail(rules);
      StaticIndexMembershipExact(
        rules[1..], direction, node, rule
      );
      if IndexNode(rules[0], direction) == node {
        RuleSetHeadTail(IndexedRules(rules, direction, node));
      }
    }
  }

  lemma IndexedRuleIdsAreSourceIds(
    rules: seq<Rule>,
    direction: Direction,
    node: nat
  )
    ensures RuleIds(IndexedRules(rules, direction, node)) <=
            RuleIds(rules)
    decreases |rules|
  {
    if |rules| > 0 {
      IndexedRuleIdsAreSourceIds(
        rules[1..], direction, node
      );
    }
  }

  lemma IndexedRulesPreserveUniqueRuleIds(
    rules: seq<Rule>,
    direction: Direction,
    node: nat
  )
    requires UniqueRuleIds(rules)
    ensures UniqueRuleIds(IndexedRules(rules, direction, node))
    decreases |rules|
  {
    if |rules| > 0 {
      IndexedRulesPreserveUniqueRuleIds(
        rules[1..], direction, node
      );
      IndexedRuleIdsAreSourceIds(
        rules[1..], direction, node
      );
    }
  }

  datatype RuleCursor = RuleCursor(offset: nat)

  predicate ValidCursor(rules: seq<Rule>, cursor: RuleCursor) {
    cursor.offset <= |rules|
  }

  function Remaining(
    rules: seq<Rule>,
    cursor: RuleCursor
  ): seq<Rule>
    requires ValidCursor(rules, cursor)
  {
    rules[cursor.offset..]
  }

  function Advance(cursor: RuleCursor): RuleCursor {
    RuleCursor(cursor.offset + 1)
  }

  lemma AdvanceIsValid(
    rules: seq<Rule>,
    cursor: RuleCursor
  )
    requires ValidCursor(rules, cursor)
    requires cursor.offset < |rules|
    ensures ValidCursor(rules, Advance(cursor))
  {
  }

  lemma OneStepPreservesExactRemainder(
    rules: seq<Rule>,
    cursor: RuleCursor
  )
    requires ValidCursor(rules, cursor)
    requires cursor.offset < |rules|
    ensures Remaining(rules, cursor) ==
            [rules[cursor.offset]] +
            Remaining(rules, Advance(cursor))
  {
  }

  function RunCursor(
    rules: seq<Rule>,
    cursor: RuleCursor,
    fuel: nat
  ): seq<Rule>
    requires ValidCursor(rules, cursor)
    decreases fuel
  {
    if fuel == 0 || cursor.offset == |rules| then []
    else [rules[cursor.offset]] +
         RunCursor(rules, Advance(cursor), fuel - 1)
  }

  lemma RunCursorReturnsExactPrefix(
    rules: seq<Rule>,
    cursor: RuleCursor,
    fuel: nat
  )
    requires ValidCursor(rules, cursor)
    ensures RunCursor(rules, cursor, fuel) ==
            rules[
              cursor.offset..
              if cursor.offset + fuel <= |rules|
              then cursor.offset + fuel
              else |rules|
            ]
    decreases fuel
  {
    if fuel > 0 && cursor.offset < |rules| {
      AdvanceIsValid(rules, cursor);
      RunCursorReturnsExactPrefix(
        rules,
        Advance(cursor),
        fuel - 1
      );
    }
  }

  lemma ExhaustedCursorReturnsEveryIndexedRuleExactlyOnce(
    rules: seq<Rule>,
    direction: Direction,
    node: nat
  )
    requires UniqueRuleIds(rules)
    ensures RunCursor(
              IndexedRules(rules, direction, node),
              RuleCursor(0),
              |IndexedRules(rules, direction, node)|
            ) == IndexedRules(rules, direction, node)
    ensures UniqueRuleIds(
              RunCursor(
                IndexedRules(rules, direction, node),
                RuleCursor(0),
                |IndexedRules(rules, direction, node)|
              )
            )
  {
    RunCursorReturnsExactPrefix(
      IndexedRules(rules, direction, node),
      RuleCursor(0),
      |IndexedRules(rules, direction, node)|
    );
    IndexedRulesPreserveUniqueRuleIds(
      rules, direction, node
    );
  }

  lemma ExhaustedStaticIndexIsComplete(
    rules: seq<Rule>,
    direction: Direction,
    node: nat,
    rule: Rule
  )
    requires UniqueRuleIds(rules)
    ensures rule in RuleSet(
              RunCursor(
                IndexedRules(rules, direction, node),
                RuleCursor(0),
                |IndexedRules(rules, direction, node)|
              )
            ) <==>
            rule in RuleSet(rules) &&
            IndexNode(rule, direction) == node
  {
    ExhaustedCursorReturnsEveryIndexedRuleExactlyOnce(
      rules, direction, node
    );
    StaticIndexMembershipExact(
      rules, direction, node, rule
    );
  }

  lemma ForwardIndexUsesTarget(
    rule: Rule
  )
    ensures IndexNode(rule, Forward) == rule.target
  {
  }

  lemma ReverseIndexUsesHead(
    rule: Rule
  )
    ensures IndexNode(rule, Reverse) == rule.head
  {
  }
}
