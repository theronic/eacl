// Exploratory proof that exact membership plus strict (rank, ordinal) order
// determines one unique sealed vector. Membership-only and rank-only checks
// admit distinct public discovery orders and are therefore insufficient.
module SealedVectorOrder {
  datatype Rule = Rule(rank: nat, ordinal: nat)

  function Less(left: Rule, right: Rule): bool {
    left.rank < right.rank ||
    (left.rank == right.rank && left.ordinal < right.ordinal)
  }

  function RuleSet(rules: seq<Rule>): set<Rule>
    decreases |rules|
  {
    if |rules| == 0 then {}
    else {rules[0]} + RuleSet(rules[1..])
  }

  function Ordinals(rules: seq<Rule>): set<nat>
    decreases |rules|
  {
    if |rules| == 0 then {}
    else {rules[0].ordinal} + Ordinals(rules[1..])
  }

  predicate UniqueOrdinals(rules: seq<Rule>)
    decreases |rules|
  {
    |rules| == 0 ||
    (rules[0].ordinal !in Ordinals(rules[1..]) &&
     UniqueOrdinals(rules[1..]))
  }

  predicate StrictlySorted(rules: seq<Rule>)
    decreases |rules|
  {
    |rules| <= 1 ||
    (Less(rules[0], rules[1]) &&
     StrictlySorted(rules[1..]))
  }

  lemma LessIsIrreflexive(rule: Rule)
    ensures !Less(rule, rule)
  {
  }

  lemma LessIsTransitive(left: Rule, middle: Rule, right: Rule)
    requires Less(left, middle)
    requires Less(middle, right)
    ensures Less(left, right)
  {
  }

  lemma LexicographicTrichotomy(left: Rule, right: Rule)
    ensures left == right || Less(left, right) || Less(right, left)
  {
  }

  lemma RuleSetHeadTail(rules: seq<Rule>)
    requires |rules| > 0
    ensures RuleSet(rules) == {rules[0]} + RuleSet(rules[1..])
  {
  }

  lemma SortedHeadPrecedesEveryTailRule(
    rules: seq<Rule>,
    rule: Rule
  )
    requires |rules| > 0
    requires StrictlySorted(rules)
    requires rule in RuleSet(rules[1..])
    ensures Less(rules[0], rule)
    decreases |rules|
  {
    if |rules| > 1 {
      RuleSetHeadTail(rules[1..]);
      if rule != rules[1] {
        assert rule in RuleSet(rules[2..]);
        SortedHeadPrecedesEveryTailRule(rules[1..], rule);
        LessIsTransitive(rules[0], rules[1], rule);
      }
    }
  }

  lemma SortedHeadDoesNotOccurInTail(rules: seq<Rule>)
    requires |rules| > 0
    requires StrictlySorted(rules)
    ensures rules[0] !in RuleSet(rules[1..])
  {
    if rules[0] in RuleSet(rules[1..]) {
      SortedHeadPrecedesEveryTailRule(rules, rules[0]);
      LessIsIrreflexive(rules[0]);
    }
  }

  lemma EqualSetsAndHeadsGiveEqualTailSets(
    left: seq<Rule>,
    right: seq<Rule>
  )
    requires |left| > 0
    requires |right| > 0
    requires StrictlySorted(left)
    requires StrictlySorted(right)
    requires left[0] == right[0]
    requires RuleSet(left) == RuleSet(right)
    ensures RuleSet(left[1..]) == RuleSet(right[1..])
  {
    RuleSetHeadTail(left);
    RuleSetHeadTail(right);
    SortedHeadDoesNotOccurInTail(left);
    SortedHeadDoesNotOccurInTail(right);
  }

  lemma SortedExactMembershipHasUniqueSequence(
    left: seq<Rule>,
    right: seq<Rule>
  )
    requires StrictlySorted(left)
    requires StrictlySorted(right)
    requires RuleSet(left) == RuleSet(right)
    ensures left == right
    decreases |left| + |right|
  {
    if |left| == 0 {
      if |right| > 0 {
        RuleSetHeadTail(right);
      }
    } else if |right| == 0 {
      RuleSetHeadTail(left);
    } else {
      RuleSetHeadTail(left);
      RuleSetHeadTail(right);
      if left[0] != right[0] {
        LexicographicTrichotomy(left[0], right[0]);
        if Less(left[0], right[0]) {
          assert left[0] in RuleSet(right[1..]);
          SortedHeadPrecedesEveryTailRule(right, left[0]);
          LessIsTransitive(left[0], right[0], left[0]);
          LessIsIrreflexive(left[0]);
        } else {
          assert right[0] in RuleSet(left[1..]);
          SortedHeadPrecedesEveryTailRule(left, right[0]);
          LessIsTransitive(right[0], left[0], right[0]);
          LessIsIrreflexive(right[0]);
        }
      }
      EqualSetsAndHeadsGiveEqualTailSets(left, right);
      SortedExactMembershipHasUniqueSequence(
        left[1..], right[1..]
      );
    }
  }

  predicate AcceptedVector(
    expected: set<Rule>,
    candidate: seq<Rule>
  ) {
    RuleSet(candidate) == expected &&
    StrictlySorted(candidate) &&
    UniqueOrdinals(candidate)
  }

  lemma TwoAcceptedVectorsAreEqual(
    expected: set<Rule>,
    left: seq<Rule>,
    right: seq<Rule>
  )
    requires AcceptedVector(expected, left)
    requires AcceptedVector(expected, right)
    ensures left == right
  {
    SortedExactMembershipHasUniqueSequence(left, right);
  }

  lemma MembershipOnlyAllowsOrderDrift()
    ensures
      var cheap := Rule(0, 0);
      var costly := Rule(1, 1);
      var left := [cheap, costly];
      var right := [costly, cheap];
      RuleSet(left) == RuleSet(right) && left != right
  {
  }

  lemma RankOnlyAllowsTieDrift()
    ensures
      var first := Rule(0, 0);
      var second := Rule(0, 1);
      var left := [first, second];
      var right := [second, first];
      RuleSet(left) == RuleSet(right) &&
      left[0].rank == right[0].rank &&
      left != right
  {
  }

  lemma DuplicateOrdinalCandidateIsRejected()
    ensures
      var first := Rule(0, 7);
      var second := Rule(1, 7);
      var candidate := [first, second];
      RuleSet(candidate) == {first, second} &&
      StrictlySorted(candidate) &&
      !AcceptedVector({first, second}, candidate)
  {
    var first := Rule(0, 7);
    var second := Rule(1, 7);
    var candidate := [first, second];
    RuleSetHeadTail(candidate);
    RuleSetHeadTail(candidate[1..]);
    assert candidate[2..] == [];
    assert RuleSet(candidate[2..]) == {};
    assert first.ordinal in Ordinals(candidate[1..]);
  }
}
