// Guarded membership: recursion through linearly guarded operators.
//
// Production mapping: eacl.operator.plan/guarded-delegation flattens the
// expression of each member of a linearly guarded component into rules, and
// eacl.engine.stable-route/check-many-eids decides them with the leveled
// memoized search of LeveledMembership.dfy.
//
// For one subject at one snapshot, every operand outside the component has
// a fixed value. A state is a member at an entity, and its expression reads
// the component only through references to other states:
//
// - a leaf outside the component is a fixed truth value;
// - a reference is another state;
// - a union is either operand;
// - a guard is an intersection whose other operands are fixed, or an
//   exclusion whose subtracted operand is fixed. It keeps its one child when
//   open.
//
// Linearity is what makes this form exact: an intersection with two
// recursive operands has no representation here, and production does not
// classify it as guarded. A subtracted guard is open when plainly absent and
// closed when plainly present. Any other subtracted value can let access
// appear later, so production defers that resource to the exact evaluation.
//
// This leaf proves three things:
//
// - flattening an expression into guarded rules preserves its denotation;
// - reachability in the guarded graph built from those rules equals the
//   stratified least fixed point of the component, defined independently as
//   membership in every pre-fixed point;
// - a guarded rule with deadlines is a leveled edge whose deadline is the
//   earliest of its own and its guards', so LeveledMembership.dfy's level
//   sequence and certificates apply to the guarded graph unchanged.
include "LeveledMembership.dfy"

module GuardedMembership {
  import opened MemoizedMembership
  import opened LeveledMembership

  datatype Expr =
    | Leaf(holds: bool)
    | Ref(target: nat)
    | Union(left: Expr, right: Expr)
    | Guard(child: Expr, open: bool)

  // The expression's value when the component's states in `x` hold.
  predicate Eval(e: Expr, x: set<nat>) {
    match e
    case Leaf(h) => h
    case Ref(t) => t in x
    case Union(l, r) => Eval(l, x) || Eval(r, x)
    case Guard(c, o) => o && Eval(c, x)
  }

  // A flattened rule: whether every guard on its path is open, and its leaf.
  datatype Rule = WitnessRule(open: bool, holds: bool) | EdgeRule(open: bool, target: nat)

  function Rules(e: Expr, open: bool): seq<Rule> {
    match e
    case Leaf(h) => [WitnessRule(open, h)]
    case Ref(t) => [EdgeRule(open, t)]
    case Union(l, r) => Rules(l, open) + Rules(r, open)
    case Guard(c, o) => Rules(c, open && o)
  }

  predicate RuleHolds(r: Rule, x: set<nat>) {
    match r
    case WitnessRule(o, h) => o && h
    case EdgeRule(o, t) => o && t in x
  }

  // ---------------------------------------------------------------------
  // Flattening
  // ---------------------------------------------------------------------

  lemma FlatteningIsExact(e: Expr, open: bool, x: set<nat>)
    ensures (open && Eval(e, x)) <==>
            exists r :: r in Rules(e, open) && RuleHolds(r, x)
  {
    match e
    case Leaf(h) =>
      assert Rules(e, open) == [WitnessRule(open, h)];
      if open && h {
        assert RuleHolds(WitnessRule(open, h), x);
      }
    case Ref(t) =>
      assert Rules(e, open) == [EdgeRule(open, t)];
      if open && t in x {
        assert RuleHolds(EdgeRule(open, t), x);
      }
    case Union(l, r) =>
      FlatteningIsExact(l, open, x);
      FlatteningIsExact(r, open, x);
      if exists rule :: rule in Rules(e, open) && RuleHolds(rule, x) {
        var rule :| rule in Rules(e, open) && RuleHolds(rule, x);
        assert rule in Rules(l, open) || rule in Rules(r, open);
      }
      if open && Eval(l, x) {
        var rule :| rule in Rules(l, open) && RuleHolds(rule, x);
        assert rule in Rules(e, open);
      }
      if open && Eval(r, x) {
        var rule :| rule in Rules(r, open) && RuleHolds(rule, x);
        assert rule in Rules(e, open);
      }
    case Guard(c, o) =>
      FlatteningIsExact(c, open && o, x);
  }

  // ---------------------------------------------------------------------
  // The guarded graph and the component's least fixed point
  // ---------------------------------------------------------------------

  // Each state's expression; every reference names a state.
  ghost predicate ValidProgram(p: seq<Expr>) {
    forall s, r | 0 <= s < |p| && r in Rules(p[s], true) && r.EdgeRule? :: r.target < |p|
  }

  function OpenTargets(rules: seq<Rule>): seq<nat>
    decreases |rules|
  {
    if |rules| == 0 then []
    else (if rules[0].EdgeRule? && rules[0].open then [rules[0].target] else []) +
         OpenTargets(rules[1..])
  }

  ghost function GuardedGraph(p: seq<Expr>): Graph {
    Graph(
      seq(|p|, s requires 0 <= s < |p| => OpenTargets(Rules(p[s], true))),
      set s | 0 <= s < |p| && WitnessRule(true, true) in Rules(p[s], true)
    )
  }

  lemma OpenTargetsMember(rules: seq<Rule>, t: nat)
    ensures t in OpenTargets(rules) <==> EdgeRule(true, t) in rules
    decreases |rules|
  {
    if |rules| > 0 {
      OpenTargetsMember(rules[1..], t);
      if EdgeRule(true, t) in rules[1..] {
        assert EdgeRule(true, t) in rules;
      }
      if EdgeRule(true, t) in rules && rules[0] != EdgeRule(true, t) {
        var i :| 0 <= i < |rules| && rules[i] == EdgeRule(true, t);
        assert rules[1..][i - 1] == rules[i];
      }
    }
  }

  lemma GuardedGraphValid(p: seq<Expr>)
    requires ValidProgram(p)
    ensures ValidGraph(GuardedGraph(p))
  {
    var g := GuardedGraph(p);
    forall s, t | 0 <= s < |g.succ| && t in g.succ[s]
      ensures t < |g.succ|
    {
      OpenTargetsMember(Rules(p[s], true), t);
      assert EdgeRule(true, t) in Rules(p[s], true);
    }
  }

  // A set closed under the component's rules; the least fixed point is the
  // set of states in every one of them.
  ghost predicate PreFixed(p: seq<Expr>, x: set<nat>) {
    forall s | 0 <= s < |p| && Eval(p[s], x) :: s in x
  }

  ghost predicate InLeastFixedPoint(p: seq<Expr>, s: nat) {
    s < |p| && forall x: set<nat> | PreFixed(p, x) :: s in x
  }

  lemma WalkIntoPreFixed(p: seq<Expr>, x: set<nat>, path: seq<nat>)
    requires ValidProgram(p) && PreFixed(p, x)
    requires Walk(GuardedGraph(p), path)
    requires path[|path| - 1] in GuardedGraph(p).grants
    ensures path[0] in x
    decreases |path|
  {
    var g := GuardedGraph(p);
    var s := path[0];
    if |path| == 1 {
      assert WitnessRule(true, true) in Rules(p[s], true);
      assert RuleHolds(WitnessRule(true, true), x);
      FlatteningIsExact(p[s], true, x);
    } else {
      var t := path[1];
      assert path[1..][|path[1..]| - 1] == path[|path| - 1];
      WalkIntoPreFixed(p, x, path[1..]);
      OpenTargetsMember(Rules(p[s], true), t);
      assert EdgeRule(true, t) in Rules(p[s], true);
      assert RuleHolds(EdgeRule(true, t), x);
      FlatteningIsExact(p[s], true, x);
    }
  }

  lemma PositiveIsInLeastFixedPoint(p: seq<Expr>, s: nat)
    requires ValidProgram(p) && s < |p| && Positive(GuardedGraph(p), s)
    ensures InLeastFixedPoint(p, s)
  {
    var path :| Walk(GuardedGraph(p), path) && path[0] == s &&
                path[|path| - 1] in GuardedGraph(p).grants;
    forall x: set<nat> | PreFixed(p, x)
      ensures s in x
    {
      WalkIntoPreFixed(p, x, path);
    }
  }

  lemma PositiveStatesArePreFixed(p: seq<Expr>)
    requires ValidProgram(p)
    ensures PreFixed(p, set s | 0 <= s < |p| && Positive(GuardedGraph(p), s))
  {
    var g := GuardedGraph(p);
    GuardedGraphValid(p);
    var positive := set s | 0 <= s < |p| && Positive(g, s);
    forall s | 0 <= s < |p| && Eval(p[s], positive)
      ensures s in positive
    {
      FlatteningIsExact(p[s], true, positive);
      var rule :| rule in Rules(p[s], true) && RuleHolds(rule, positive);
      match rule
      case WitnessRule(o, h) =>
        assert rule == WitnessRule(true, true);
        assert s in g.grants;
        assert Walk(g, [s]);
      case EdgeRule(o, t) =>
        assert rule == EdgeRule(true, t);
        OpenTargetsMember(Rules(p[s], true), t);
        assert t in g.succ[s];
        assert Positive(g, t);
        var right :| Walk(g, right) && right[0] == t && right[|right| - 1] in g.grants;
        assert Walk(g, [s, t]);
        WalkConcat(g, [s, t], right);
    }
  }

  lemma LeastFixedPointIsPositive(p: seq<Expr>, s: nat)
    requires ValidProgram(p) && InLeastFixedPoint(p, s)
    ensures Positive(GuardedGraph(p), s)
  {
    PositiveStatesArePreFixed(p);
  }

  // The theorem the guarded search rests on: for one subject at one
  // snapshot, a state holds in the component's stratified least fixed point
  // exactly when it reaches a witness in the guarded graph, the graph the
  // memoized search of MemoizedMembership.dfy decides.
  lemma GuardedReachabilityIsTheLeastFixedPoint(p: seq<Expr>, s: nat)
    requires ValidProgram(p) && s < |p|
    ensures InLeastFixedPoint(p, s) <==> Positive(GuardedGraph(p), s)
  {
    if InLeastFixedPoint(p, s) {
      LeastFixedPointIsPositive(p, s);
    }
    if Positive(GuardedGraph(p), s) {
      PositiveIsInLeastFixedPoint(p, s);
    }
  }

  // ---------------------------------------------------------------------
  // Deadlines: a guarded rule is a leveled edge
  // ---------------------------------------------------------------------

  function Earlier(a: Deadline, b: Deadline): Deadline {
    if a.Forever? then b
    else if b.Forever? then a
    else Until(if a.at < b.at then a.at else b.at)
  }

  function EarliestOf(ds: seq<Deadline>): Deadline
    decreases |ds|
  {
    if |ds| == 0 then Forever else Earlier(ds[0], EarliestOf(ds[1..]))
  }

  lemma EarlierLasts(a: Deadline, b: Deadline, level: Level)
    ensures Lasts(Earlier(a, b), level) <==> Lasts(a, level) && Lasts(b, level)
  {
  }

  // A rule's edge lasts at a level exactly when the rule's own evidence and
  // every guard on its path last there. So the guarded graph at a level is
  // AtLevel of the leveled graph whose edges carry EarliestOf over the
  // rule's deadlines, and LeveledMembership.dfy's theorems hold for it.
  lemma GuardedRuleLastsWhenAllItsEvidenceLasts(ds: seq<Deadline>, level: Level)
    ensures Lasts(EarliestOf(ds), level) <==>
            forall i | 0 <= i < |ds| :: Lasts(ds[i], level)
    decreases |ds|
  {
    if |ds| > 0 {
      GuardedRuleLastsWhenAllItsEvidenceLasts(ds[1..], level);
      EarlierLasts(ds[0], EarliestOf(ds[1..]), level);
      if Lasts(ds[0], level) && forall i | 0 <= i < |ds[1..]| :: Lasts(ds[1..][i], level) {
        forall i | 0 <= i < |ds|
          ensures Lasts(ds[i], level)
        {
          if i > 0 {
            assert ds[i] == ds[1..][i - 1];
          }
        }
      }
      if forall i | 0 <= i < |ds| :: Lasts(ds[i], level) {
        forall i | 0 <= i < |ds[1..]|
          ensures Lasts(ds[1..][i], level)
        {
          assert ds[1..][i] == ds[i + 1];
        }
      }
    }
  }
}
