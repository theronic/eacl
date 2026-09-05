// Proof-only permission evidence. Worlds are Boolean completions of the fixed
// request's residual Caveat atoms, not database entities or a runtime encoding.
module QualifiedEvidence {
  datatype Outcome = Value(worlds: set<nat>) | Fault(reasons: set<nat>)
  datatype Operator = Union | Intersection | Exclusion | Arrow
  datatype Kind = Has | No | Conditional | Failure

  function Classify(universe: set<nat>, x: Outcome): Kind {
    if x.Fault? then Failure
    else if x.worlds == {} then No
    else if x.worlds == universe then Has else Conditional
  }

  function Errors(x: Outcome): set<nat> {
    if x.Fault? then x.reasons else {}
  }

  function Compose(op: Operator, a: Outcome, b: Outcome): Outcome {
    if a.Fault? || b.Fault? then Fault(Errors(a) + Errors(b))
    else Value(if op.Union? then a.worlds + b.worlds
               else if op.Exclusion? then a.worlds - b.worlds
               else a.worlds * b.worlds)
  }

  predicate NoNewFaults(before: Outcome, after: Outcome) {
    after.Fault? ==> before.Fault? && after.reasons <= before.reasons
  }

  lemma PointwiseBooleanAlgebra(op: Operator, a: set<nat>, b: set<nat>, w: nat)
    ensures w in Compose(op, Value(a), Value(b)).worlds <==>
            (if op.Union? then w in a || w in b
             else if op.Exclusion? then w in a && w !in b
             else w in a && w in b)
  {}

  lemma FaultAlwaysPropagates(op: Operator, a: Outcome, b: Outcome)
    requires a.Fault? || b.Fault?
    ensures Compose(op, a, b).Fault?
    ensures Errors(a) + Errors(b) == Compose(op, a, b).reasons
  {}

  lemma ConditionalCannotGrant(universe: set<nat>, x: Outcome)
    requires Classify(universe, x) == Conditional || Classify(universe, x) == Failure
    ensures Classify(universe, x) != Has
  {}

  lemma FaultsCannotAppearByComposition(op: Operator, a: Outcome, b: Outcome, laterA: Outcome, laterB: Outcome)
    requires NoNewFaults(a, laterA) && NoNewFaults(b, laterB)
    ensures NoNewFaults(Compose(op, a, b), Compose(op, laterA, laterB))
  {}

  lemma DecisiveUnionWitness(universe: set<nat>, b: Outcome, laterB: Outcome)
    requires b.Value? && b.worlds <= universe
    requires laterB.Value? && laterB.worlds <= universe
    ensures Compose(Union, Value(universe), b) == Compose(Union, Value(universe), laterB)
    ensures Compose(Union, b, Value(universe)) == Compose(Union, laterB, Value(universe))
  {}

  lemma DecisiveIntersectionWitness(b: Outcome, laterB: Outcome)
    requires b.Value? && laterB.Value?
    ensures Compose(Intersection, Value({}), b) == Compose(Intersection, Value({}), laterB)
    ensures Compose(Arrow, b, Value({})) == Compose(Arrow, laterB, Value({}))
  {}

  lemma DecisiveExclusionWitness(universe: set<nat>, a: set<nat>, b: set<nat>, laterA: set<nat>, laterB: set<nat>)
    requires a <= universe && laterA <= universe
    ensures Compose(Exclusion, Value({}), Value(b)) == Compose(Exclusion, Value({}), Value(laterB))
    ensures Compose(Exclusion, Value(a), Value(universe)) == Compose(Exclusion, Value(laterA), Value(universe))
  {}

  // Positive recursion is a least fixed point over finite (node, world) facts.
  // Negative dependencies are evaluated in earlier strata, never in this SCC.
  datatype Fact = Fact(node: nat, world: nat)
  datatype Rule = Rule(head: Fact, body: set<Fact>)

  function Step(base: set<Fact>, rules: set<Rule>, prior: set<Fact>): set<Fact> {
    base + prior + set r | r in rules && r.body <= prior :: r.head
  }

  lemma PositiveStepIsMonotone(base: set<Fact>, rules: set<Rule>, a: set<Fact>, b: set<Fact>)
    requires a <= b
    ensures a <= Step(base, rules, a)
    ensures Step(base, rules, a) <= Step(base, rules, b)
  {}

  function Iterate(base: set<Fact>, rules: set<Rule>, n: nat): set<Fact> {
    if n == 0 then {} else Step(base, rules, Iterate(base, rules, n - 1))
  }

  lemma IterationIsAscending(base: set<Fact>, rules: set<Rule>, n: nat)
    ensures Iterate(base, rules, n) <= Iterate(base, rules, n + 1)
  {
    if n > 0 {
      IterationIsAscending(base, rules, n - 1);
      PositiveStepIsMonotone(base, rules, Iterate(base, rules, n - 1), Iterate(base, rules, n));
    }
  }

  lemma IterationIsLeast(base: set<Fact>, rules: set<Rule>, closed: set<Fact>, n: nat)
    requires Step(base, rules, closed) <= closed
    ensures Iterate(base, rules, n) <= closed
  {
    if n > 0 {
      IterationIsLeast(base, rules, closed, n - 1);
      PositiveStepIsMonotone(base, rules, Iterate(base, rules, n - 1), closed);
    }
  }

  lemma FixedPointRemainsFixed(base: set<Fact>, rules: set<Rule>, n: nat, extra: nat)
    requires Iterate(base, rules, n) == Iterate(base, rules, n + 1)
    ensures Iterate(base, rules, n + extra) == Iterate(base, rules, n)
  {
    if extra > 0 {
      FixedPointRemainsFixed(base, rules, n, extra - 1);
    }
  }

  lemma StableRecursiveInputs(base: set<Fact>, rules: set<Rule>, laterBase: set<Fact>, laterRules: set<Rule>, n: nat)
    requires base == laterBase && rules == laterRules
    ensures Iterate(base, rules, n) == Iterate(laterBase, laterRules, n)
  {}

  predicate BoundedInputs(possible: set<Fact>, base: set<Fact>, rules: set<Rule>) {
    base <= possible && (forall r | r in rules :: r.head in possible && r.body <= possible)
  }

  lemma StepStaysFinite(possible: set<Fact>, base: set<Fact>, rules: set<Rule>, prior: set<Fact>)
    requires BoundedInputs(possible, base, rules) && prior <= possible
    ensures prior <= Step(base, rules, prior) <= possible
  {}

  lemma StrictFiniteProgress(possible: set<Fact>, prior: set<Fact>, next: set<Fact>)
    requires prior <= next && prior != next && next <= possible
    ensures |possible - next| < |possible - prior|
  {
    if next - prior == {} {
      forall x | x in next
        ensures x in prior
      {
        assert x !in next - prior;
      }
      assert next <= prior;
      assert next == prior;
    }
    assert next - prior != {};
    assert |next - prior| > 0;
    assert possible - prior == (possible - next) + (next - prior);
    assert (possible - next) !! (next - prior);
    assert |possible - prior| == |possible - next| + |next - prior|;
  }

  function Closure(possible: set<Fact>, base: set<Fact>, rules: set<Rule>, prior: set<Fact>): set<Fact>
    requires BoundedInputs(possible, base, rules) && prior <= possible
    ensures prior <= Closure(possible, base, rules, prior) <= possible
    ensures Step(base, rules, Closure(possible, base, rules, prior)) == Closure(possible, base, rules, prior)
    decreases |possible - prior|
  {
    StepStaysFinite(possible, base, rules, prior);
    var next := Step(base, rules, prior);
    if next == prior then prior else
    (StrictFiniteProgress(possible, prior, next); Closure(possible, base, rules, next))
  }

  lemma ClosureIsLeast(possible: set<Fact>, base: set<Fact>, rules: set<Rule>, prior: set<Fact>, closed: set<Fact>)
    requires BoundedInputs(possible, base, rules) && prior <= possible
    requires prior <= closed && Step(base, rules, closed) <= closed
    ensures Closure(possible, base, rules, prior) <= closed
    decreases |possible - prior|
  {
    StepStaysFinite(possible, base, rules, prior);
    PositiveStepIsMonotone(base, rules, prior, closed);
    var next := Step(base, rules, prior);
    if next != prior {
      StrictFiniteProgress(possible, prior, next);
      ClosureIsLeast(possible, base, rules, next, closed);
    }
  }
}
