// Proof-only permission evidence. Worlds are Boolean completions of the fixed
// request's residual Caveat atoms, not database entities or a runtime encoding.
// In each world a qualified value is one of Kleene's F, U (a faulted subterm of
// unknown value, with diagnostic reasons) or T, and operators compose world by
// world with the strong Kleene connectives, so a definite absorber decides an
// operator even when its other operand faults.
module QualifiedEvidence {
  datatype Tri = F | U(reasons: set<nat>) | T
  // A finite map from worlds to values. A world without a cell is F.
  datatype Outcome = Outcome(cells: map<nat, Tri>)
  datatype Operator = Union | Intersection | Exclusion | Arrow
  datatype Kind = Has | No | Conditional | Failure

  // Strong Kleene connectives in one world, with ¬U = U. Reasons are a
  // diagnostic component beside the truth order F < U < T.
  function Reasons(x: Tri): set<nat> {
    if x.U? then x.reasons else {}
  }

  function Or(a: Tri, b: Tri): Tri {
    if a.T? || b.T? then T
    else if a.U? || b.U? then U(Reasons(a) + Reasons(b))
    else F
  }

  function And(a: Tri, b: Tri): Tri {
    if a.F? || b.F? then F
    else if a.U? || b.U? then U(Reasons(a) + Reasons(b))
    else T
  }

  function Not(a: Tri): Tri {
    if a.T? then F else if a.F? then T else a
  }

  function Minus(a: Tri, b: Tri): Tri {
    And(a, Not(b))
  }

  // An arrow is its via edge intersected with its target.
  function Connect(op: Operator, a: Tri, b: Tri): Tri {
    match op
    case Union => Or(a, b)
    case Intersection => And(a, b)
    case Arrow => And(a, b)
    case Exclusion => Minus(a, b)
  }

  lemma UnionTruthTable(x: Tri, r: set<nat>, s: set<nat>)
    ensures Or(T, x) == T && Or(x, T) == T
    ensures Or(F, F) == F
    ensures Or(U(r), F) == U(r) && Or(F, U(r)) == U(r)
    ensures Or(U(r), U(s)) == U(r + s)
  {
    assert r + {} == r && {} + r == r;
  }

  lemma IntersectionTruthTable(x: Tri, r: set<nat>, s: set<nat>)
    ensures And(F, x) == F && And(x, F) == F
    ensures And(T, T) == T
    ensures And(U(r), T) == U(r) && And(T, U(r)) == U(r)
    ensures And(U(r), U(s)) == U(r + s)
  {
    assert r + {} == r && {} + r == r;
  }

  lemma ExclusionTruthTable(x: Tri, r: set<nat>, s: set<nat>)
    ensures Minus(F, x) == F && Minus(x, T) == F
    ensures Minus(T, F) == T
    ensures Minus(U(r), F) == U(r) && Minus(T, U(r)) == U(r)
    ensures Minus(U(r), U(s)) == U(r + s)
  {
    assert r + {} == r && {} + r == r;
  }

  lemma NegationTruthTable(x: Tri, r: set<nat>)
    ensures Not(T) == F && Not(F) == T && Not(U(r)) == U(r)
    ensures Not(Not(x)) == x
  {
  }

  lemma KleeneCommutes(a: Tri, b: Tri)
    ensures Or(a, b) == Or(b, a) && And(a, b) == And(b, a)
  {
    assert Reasons(a) + Reasons(b) == Reasons(b) + Reasons(a);
  }

  lemma OrReasons(a: Tri, b: Tri)
    ensures !Or(a, b).T? ==> Reasons(Or(a, b)) == Reasons(a) + Reasons(b)
  {
    if !a.U? && !b.U? {
      assert Reasons(a) + Reasons(b) == {};
    }
  }

  lemma AndReasons(a: Tri, b: Tri)
    ensures !And(a, b).F? ==> Reasons(And(a, b)) == Reasons(a) + Reasons(b)
  {
    if !a.U? && !b.U? {
      assert Reasons(a) + Reasons(b) == {};
    }
  }

  lemma KleeneAssociates(a: Tri, b: Tri, c: Tri)
    ensures Or(Or(a, b), c) == Or(a, Or(b, c))
    ensures And(And(a, b), c) == And(a, And(b, c))
  {
    OrReasons(a, b);
    OrReasons(b, c);
    AndReasons(a, b);
    AndReasons(b, c);
    assert (Reasons(a) + Reasons(b)) + Reasons(c) == Reasons(a) + (Reasons(b) + Reasons(c));
  }

  lemma KleeneIsIdempotent(a: Tri)
    ensures Or(a, a) == a && And(a, a) == a
  {
    assert Reasons(a) + Reasons(a) == Reasons(a);
  }

  lemma KleeneDeMorgan(a: Tri, b: Tri)
    ensures Not(Or(a, b)) == And(Not(a), Not(b))
    ensures Not(And(a, b)) == Or(Not(a), Not(b))
  {
    assert Reasons(Not(a)) == Reasons(a) && Reasons(Not(b)) == Reasons(b);
  }

  // A definite absorber fixes the connective whatever the other value is,
  // including U.
  lemma DefiniteAbsorbers(x: Tri)
    ensures Or(T, x) == T && Or(x, T) == T
    ensures And(F, x) == F && And(x, F) == F
    ensures Minus(F, x) == F && Minus(x, T) == F
  {
  }

  // Kleene's truth order F < U < T, with the reasons erased.
  function Level(x: Tri): nat {
    if x.F? then 0 else if x.U? then 1 else 2
  }

  function Max(a: nat, b: nat): nat {
    if a <= b then b else a
  }

  function Min(a: nat, b: nat): nat {
    if a <= b then a else b
  }

  lemma KleeneIsMaxMinOnLevels(a: Tri, b: Tri)
    ensures Level(Or(a, b)) == Max(Level(a), Level(b))
    ensures Level(And(a, b)) == Min(Level(a), Level(b))
    ensures Level(Not(a)) == 2 - Level(a)
    ensures Level(Minus(a, b)) == Min(Level(a), 2 - Level(b))
  {
  }

  // Union and intersection (so also arrow) are monotone in both operands.
  lemma ConnectivesAreMonotone(a: Tri, b: Tri, a2: Tri, b2: Tri)
    requires Level(a) <= Level(a2) && Level(b) <= Level(b2)
    ensures Level(Or(a, b)) <= Level(Or(a2, b2))
    ensures Level(And(a, b)) <= Level(And(a2, b2))
  {
    KleeneIsMaxMinOnLevels(a, b);
    KleeneIsMaxMinOnLevels(a2, b2);
  }

  // Exclusion is monotone in its left operand and antitone in its right one.
  lemma ExclusionIsMonotoneLeftAntitoneRight(a: Tri, b: Tri, a2: Tri, b2: Tri)
    requires Level(a) <= Level(a2) && Level(b2) <= Level(b)
    ensures Level(Minus(a, b)) <= Level(Minus(a2, b2))
  {
    KleeneIsMaxMinOnLevels(a, b);
    KleeneIsMaxMinOnLevels(a2, b2);
  }

  // Raising an exclusion's right operand lowers the result, so a negative
  // dependency cannot join a positive fixed point.
  lemma ExclusionIsNotMonotoneInItsRight()
    ensures Level(F) < Level(T) && Level(Minus(T, T)) < Level(Minus(T, F))
  {
  }

  // On levels the connectives form a distributive lattice (min and max on a
  // chain).
  lemma LevelsDistribute(a: Tri, b: Tri, c: Tri)
    ensures Level(And(a, Or(b, c))) == Level(Or(And(a, b), And(a, c)))
    ensures Level(Or(a, And(b, c))) == Level(And(Or(a, b), Or(a, c)))
  {
    KleeneIsMaxMinOnLevels(b, c);
    KleeneIsMaxMinOnLevels(a, Or(b, c));
    KleeneIsMaxMinOnLevels(a, b);
    KleeneIsMaxMinOnLevels(a, c);
    KleeneIsMaxMinOnLevels(And(a, b), And(a, c));
    KleeneIsMaxMinOnLevels(a, And(b, c));
    KleeneIsMaxMinOnLevels(Or(a, b), Or(a, c));
  }

  // Reasons are diagnostic: they need not distribute.
  lemma ReasonsDoNotDistribute()
    ensures And(U({1}), Or(U({2}), T)) == U({1})
    ensures Or(And(U({1}), U({2})), And(U({1}), T)) == U({1, 2})
    ensures U({1}) != U({1, 2})
  {
    assert {1} + {} == {1};
    assert {1} + {2} == {1, 2};
    assert {1, 2} + {1} == {1, 2};
    assert 2 in {1, 2} && 2 !in {1};
  }

  // A Boolean resolution of a value: a definite value resolves to itself and U
  // resolves to either Boolean.
  predicate Resolves(x: Tri, b: bool) {
    (x.T? ==> b) && (x.F? ==> !b)
  }

  function Bool(op: Operator, a: bool, b: bool): bool {
    if op.Union? then a || b else if op.Exclusion? then a && !b else a && b
  }

  // A definite Kleene value is the Boolean value under every resolution of the
  // operands' U values.
  lemma KleeneIsSoundForEveryResolution(op: Operator, a: Tri, b: Tri, ra: bool, rb: bool)
    requires Resolves(a, ra) && Resolves(b, rb)
    ensures Resolves(Connect(op, a, b), Bool(op, ra, rb))
    ensures Resolves(Or(a, b), ra || rb) && Resolves(And(a, b), ra && rb)
    ensures Resolves(Minus(a, b), ra && !rb) && Resolves(Not(a), !ra)
  {
  }

  // Conversely, a U result has resolutions with both Boolean values.
  lemma UnknownResultsHaveDisagreeingResolutions(op: Operator, a: Tri, b: Tri)
    requires Connect(op, a, b).U?
    ensures exists ra, rb :: Resolves(a, ra) && Resolves(b, rb) && Bool(op, ra, rb)
    ensures exists ra, rb :: Resolves(a, ra) && Resolves(b, rb) && !Bool(op, ra, rb)
  {
    var upA, upB, downA, downB := !a.F?, !b.F?, a.T?, b.T?;
    if op.Exclusion? {
      assert Resolves(a, upA) && Resolves(b, downB) && Bool(op, upA, downB);
      assert Resolves(a, downA) && Resolves(b, upB) && !Bool(op, downA, upB);
    } else {
      assert Resolves(a, upA) && Resolves(b, upB) && Bool(op, upA, upB);
      assert Resolves(a, downA) && Resolves(b, downB) && !Bool(op, downA, downB);
    }
  }

  function At(x: Outcome, w: nat): Tri {
    if w in x.cells then x.cells[w] else F
  }

  // Every cell is U or T, so pointwise equal normal outcomes are equal.
  predicate Normal(x: Outcome) {
    forall w | w in x.cells :: !x.cells[w].F?
  }

  // A Boolean outcome: T exactly in `worlds`.
  function Value(worlds: set<nat>): Outcome
    ensures Normal(Value(worlds))
  {
    Outcome(map w | w in worlds :: T)
  }

  // A total fault: U with `reasons` in every world of the universe.
  function Fault(universe: set<nat>, reasons: set<nat>): Outcome
    ensures Normal(Fault(universe, reasons))
  {
    Outcome(map w | w in universe :: U(reasons))
  }

  // The T worlds.
  function Worlds(x: Outcome): set<nat> {
    set w | w in x.cells && x.cells[w].T?
  }

  lemma TrueWorlds(x: Outcome)
    ensures forall w :: w in Worlds(x) <==> At(x, w).T?
  {
  }

  // No world outside the universe is U or T.
  predicate Within(universe: set<nat>, x: Outcome) {
    forall w | w in x.cells && w !in universe :: x.cells[w].F?
  }

  // No world is U.
  predicate Definite(x: Outcome) {
    forall w | w in x.cells :: !x.cells[w].U?
  }

  predicate Faulted(universe: set<nat>, x: Outcome) {
    exists w | w in universe :: At(x, w).U?
  }

  predicate AllTrue(universe: set<nat>, x: Outcome) {
    forall w | w in universe :: At(x, w).T?
  }

  predicate AllFalse(universe: set<nat>, x: Outcome) {
    forall w | w in universe :: At(x, w).F?
  }

  function Classify(universe: set<nat>, x: Outcome): Kind {
    if Faulted(universe, x) then Failure
    else if AllFalse(universe, x) then No
    else if AllTrue(universe, x) then Has
    else Conditional
  }

  // The reasons of every U world of the universe.
  function Errors(universe: set<nat>, x: Outcome): set<nat> {
    set w, r | w in universe && At(x, w).U? && r in At(x, w).reasons :: r
  }

  // `after` is U only in worlds where `before` is U, for no new reason.
  predicate NoNewFaults(before: Outcome, after: Outcome) {
    forall w | w in after.cells && after.cells[w].U? ::
      At(before, w).U? && after.cells[w].reasons <= At(before, w).reasons
  }

  opaque function Compose(op: Operator, a: Outcome, b: Outcome): Outcome
    ensures Normal(Compose(op, a, b))
  {
    Outcome(map w | w in a.cells.Keys + b.cells.Keys && !Connect(op, At(a, w), At(b, w)).F?
              :: Connect(op, At(a, w), At(b, w)))
  }

  // Outcomes compose world by world.
  lemma Pointwise(op: Operator, a: Outcome, b: Outcome, w: nat)
    ensures At(Compose(op, a, b), w) == Connect(op, At(a, w), At(b, w))
  {
    reveal Compose();
  }

  // Kleene's truth order lifted to outcomes, world by world.
  ghost predicate Below(x: Outcome, y: Outcome) {
    forall w :: Level(At(x, w)) <= Level(At(y, w))
  }

  lemma CompositionIsMonotone(op: Operator, a: Outcome, b: Outcome, a2: Outcome, b2: Outcome)
    requires Below(a, a2)
    requires if op.Exclusion? then Below(b2, b) else Below(b, b2)
    ensures Below(Compose(op, a, b), Compose(op, a2, b2))
  {
    forall w
      ensures Level(At(Compose(op, a, b), w)) <= Level(At(Compose(op, a2, b2), w))
    {
      Pointwise(op, a, b, w);
      Pointwise(op, a2, b2, w);
      if op.Exclusion? {
        ExclusionIsMonotoneLeftAntitoneRight(At(a, w), At(b, w), At(a2, w), At(b2, w));
      } else {
        ConnectivesAreMonotone(At(a, w), At(b, w), At(a2, w), At(b2, w));
      }
    }
  }

  lemma Extensionality(x: Outcome, y: Outcome)
    requires Normal(x) && Normal(y)
    requires forall w :: At(x, w) == At(y, w)
    ensures x == y
  {
    forall w | w in x.cells
      ensures w in y.cells && x.cells[w] == y.cells[w]
    {
      assert At(x, w) == At(y, w);
    }
    forall w | w in y.cells
      ensures w in x.cells
    {
      assert At(x, w) == At(y, w);
    }
    assert x.cells == y.cells;
  }

  lemma AgreeOnTheUniverse(universe: set<nat>, x: Outcome, y: Outcome)
    requires Normal(x) && Normal(y) && Within(universe, x) && Within(universe, y)
    requires forall w | w in universe :: At(x, w) == At(y, w)
    ensures x == y
  {
    forall w
      ensures At(x, w) == At(y, w)
    {
      if w !in universe {
        assert At(x, w) == F && At(y, w) == F;
      }
    }
    Extensionality(x, y);
  }

  lemma ComposeIsWithin(universe: set<nat>, op: Operator, a: Outcome, b: Outcome)
    requires Within(universe, a) && Within(universe, b)
    ensures Within(universe, Compose(op, a, b))
  {
    forall w | w in Compose(op, a, b).cells && w !in universe
      ensures Compose(op, a, b).cells[w].F?
    {
      Pointwise(op, a, b, w);
      assert At(a, w) == F && At(b, w) == F;
    }
  }

  // Compositions that agree in every world of the universe are equal.
  lemma ComposeAgreesOnTheUniverse(universe: set<nat>, op: Operator, a: Outcome, b: Outcome, a2: Outcome, b2: Outcome)
    requires Within(universe, a) && Within(universe, b) && Within(universe, a2) && Within(universe, b2)
    requires forall w | w in universe :: Connect(op, At(a, w), At(b, w)) == Connect(op, At(a2, w), At(b2, w))
    ensures Compose(op, a, b) == Compose(op, a2, b2)
  {
    ComposeIsWithin(universe, op, a, b);
    ComposeIsWithin(universe, op, a2, b2);
    forall w | w in universe
      ensures At(Compose(op, a, b), w) == At(Compose(op, a2, b2), w)
    {
      Pointwise(op, a, b, w);
      Pointwise(op, a2, b2, w);
    }
    AgreeOnTheUniverse(universe, Compose(op, a, b), Compose(op, a2, b2));
  }

  // Fault-free outcomes compose as the Boolean algebra of their worlds.
  lemma PointwiseBooleanAlgebra(op: Operator, a: set<nat>, b: set<nat>)
    ensures Compose(op, Value(a), Value(b)) ==
            Value(if op.Union? then a + b else if op.Exclusion? then a - b else a * b)
  {
    var c := if op.Union? then a + b else if op.Exclusion? then a - b else a * b;
    forall w
      ensures At(Compose(op, Value(a), Value(b)), w) == At(Value(c), w)
    {
      Pointwise(op, Value(a), Value(b), w);
    }
    Extensionality(Compose(op, Value(a), Value(b)), Value(c));
  }

  lemma UnionAndIntersectionCommute(a: Outcome, b: Outcome)
    ensures Compose(Union, a, b) == Compose(Union, b, a)
    ensures Compose(Intersection, a, b) == Compose(Intersection, b, a)
  {
    forall w
      ensures At(Compose(Union, a, b), w) == At(Compose(Union, b, a), w)
      ensures At(Compose(Intersection, a, b), w) == At(Compose(Intersection, b, a), w)
    {
      Pointwise(Union, a, b, w);
      Pointwise(Union, b, a, w);
      Pointwise(Intersection, a, b, w);
      Pointwise(Intersection, b, a, w);
      KleeneCommutes(At(a, w), At(b, w));
    }
    Extensionality(Compose(Union, a, b), Compose(Union, b, a));
    Extensionality(Compose(Intersection, a, b), Compose(Intersection, b, a));
  }

  lemma UnionAndIntersectionAssociate(a: Outcome, b: Outcome, c: Outcome)
    ensures Compose(Union, Compose(Union, a, b), c) == Compose(Union, a, Compose(Union, b, c))
    ensures Compose(Intersection, Compose(Intersection, a, b), c) == Compose(Intersection, a, Compose(Intersection, b, c))
  {
    forall w
      ensures At(Compose(Union, Compose(Union, a, b), c), w) == At(Compose(Union, a, Compose(Union, b, c)), w)
      ensures At(Compose(Intersection, Compose(Intersection, a, b), c), w) ==
              At(Compose(Intersection, a, Compose(Intersection, b, c)), w)
    {
      Pointwise(Union, a, b, w);
      Pointwise(Union, b, c, w);
      Pointwise(Union, Compose(Union, a, b), c, w);
      Pointwise(Union, a, Compose(Union, b, c), w);
      Pointwise(Intersection, a, b, w);
      Pointwise(Intersection, b, c, w);
      Pointwise(Intersection, Compose(Intersection, a, b), c, w);
      Pointwise(Intersection, a, Compose(Intersection, b, c), w);
      KleeneAssociates(At(a, w), At(b, w), At(c, w));
    }
    Extensionality(Compose(Union, Compose(Union, a, b), c), Compose(Union, a, Compose(Union, b, c)));
    Extensionality(Compose(Intersection, Compose(Intersection, a, b), c), Compose(Intersection, a, Compose(Intersection, b, c)));
  }

  lemma UnionAndIntersectionAreIdempotent(a: Outcome)
    requires Normal(a)
    ensures Compose(Union, a, a) == a && Compose(Intersection, a, a) == a
  {
    forall w
      ensures At(Compose(Union, a, a), w) == At(a, w) && At(Compose(Intersection, a, a), w) == At(a, w)
    {
      Pointwise(Union, a, a, w);
      Pointwise(Intersection, a, a, w);
      KleeneIsIdempotent(At(a, w));
    }
    Extensionality(Compose(Union, a, a), a);
    Extensionality(Compose(Intersection, a, a), a);
  }

  // Definite operands absorb every other operand, including a faulting one.
  lemma TotalGrantAbsorbs(universe: set<nat>, x: Outcome)
    requires Within(universe, x)
    ensures Compose(Union, Value(universe), x) == Value(universe)
    ensures Compose(Union, x, Value(universe)) == Value(universe)
    ensures Compose(Exclusion, x, Value(universe)) == Value({})
  {
    forall w
      ensures At(Compose(Union, Value(universe), x), w) == At(Value(universe), w)
      ensures At(Compose(Union, x, Value(universe)), w) == At(Value(universe), w)
      ensures At(Compose(Exclusion, x, Value(universe)), w) == At(Value({}), w)
    {
      Pointwise(Union, Value(universe), x, w);
      Pointwise(Union, x, Value(universe), w);
      Pointwise(Exclusion, x, Value(universe), w);
      if w !in universe {
        assert At(x, w) == F;
      }
    }
    Extensionality(Compose(Union, Value(universe), x), Value(universe));
    Extensionality(Compose(Union, x, Value(universe)), Value(universe));
    Extensionality(Compose(Exclusion, x, Value(universe)), Value({}));
  }

  lemma TotalDenialAbsorbs(x: Outcome)
    ensures Compose(Intersection, Value({}), x) == Value({})
    ensures Compose(Intersection, x, Value({})) == Value({})
    ensures Compose(Arrow, Value({}), x) == Value({})
    ensures Compose(Arrow, x, Value({})) == Value({})
    ensures Compose(Exclusion, Value({}), x) == Value({})
  {
    forall w
      ensures At(Compose(Intersection, Value({}), x), w) == F
      ensures At(Compose(Intersection, x, Value({})), w) == F
      ensures At(Compose(Arrow, Value({}), x), w) == F
      ensures At(Compose(Arrow, x, Value({})), w) == F
      ensures At(Compose(Exclusion, Value({}), x), w) == F
    {
      Pointwise(Intersection, Value({}), x, w);
      Pointwise(Intersection, x, Value({}), w);
      Pointwise(Arrow, Value({}), x, w);
      Pointwise(Arrow, x, Value({}), w);
      Pointwise(Exclusion, Value({}), x, w);
    }
    Extensionality(Compose(Intersection, Value({}), x), Value({}));
    Extensionality(Compose(Intersection, x, Value({})), Value({}));
    Extensionality(Compose(Arrow, Value({}), x), Value({}));
    Extensionality(Compose(Arrow, x, Value({})), Value({}));
    Extensionality(Compose(Exclusion, Value({}), x), Value({}));
  }

  lemma DecisiveUnionWitness(universe: set<nat>, b: Outcome, laterB: Outcome)
    requires Within(universe, b) && Within(universe, laterB)
    ensures Compose(Union, Value(universe), b) == Compose(Union, Value(universe), laterB)
    ensures Compose(Union, b, Value(universe)) == Compose(Union, laterB, Value(universe))
  {
    TotalGrantAbsorbs(universe, b);
    TotalGrantAbsorbs(universe, laterB);
  }

  lemma DecisiveIntersectionWitness(b: Outcome, laterB: Outcome)
    ensures Compose(Intersection, Value({}), b) == Compose(Intersection, Value({}), laterB)
    ensures Compose(Intersection, b, Value({})) == Compose(Intersection, laterB, Value({}))
    ensures Compose(Arrow, Value({}), b) == Compose(Arrow, Value({}), laterB)
    ensures Compose(Arrow, b, Value({})) == Compose(Arrow, laterB, Value({}))
  {
    TotalDenialAbsorbs(b);
    TotalDenialAbsorbs(laterB);
  }

  lemma DecisiveExclusionWitness(universe: set<nat>, a: Outcome, b: Outcome, laterA: Outcome, laterB: Outcome)
    requires Within(universe, a) && Within(universe, laterA)
    ensures Compose(Exclusion, Value({}), b) == Compose(Exclusion, Value({}), laterB)
    ensures Compose(Exclusion, a, Value(universe)) == Compose(Exclusion, laterA, Value(universe))
  {
    TotalDenialAbsorbs(b);
    TotalDenialAbsorbs(laterB);
    TotalGrantAbsorbs(universe, a);
    TotalGrantAbsorbs(universe, laterA);
  }

  lemma TotalValuesClassify(universe: set<nat>)
    requires universe != {}
    ensures Classify(universe, Value(universe)) == Has
    ensures Classify(universe, Value({})) == No
  {
    var w :| w in universe;
    assert At(Value(universe), w) == T;
  }

  // A fault is not decisive: a later union can still make the accumulator T
  // and a later intersection can still make it F, so evaluation must not stop
  // at a fault.
  lemma FaultsAreNotDecisive(universe: set<nat>, reasons: set<nat>)
    requires universe != {}
    ensures Classify(universe, Fault(universe, reasons)) == Failure
    ensures Compose(Union, Fault(universe, reasons), Value(universe)) == Value(universe)
    ensures Compose(Intersection, Fault(universe, reasons), Value({})) == Value({})
    ensures Classify(universe, Value(universe)) == Has && Classify(universe, Value({})) == No
  {
    var w :| w in universe;
    assert At(Fault(universe, reasons), w).U?;
    TotalGrantAbsorbs(universe, Fault(universe, reasons));
    TotalDenialAbsorbs(Fault(universe, reasons));
    TotalValuesClassify(universe);
  }

  // A composition is U only where an operand is U, and only for its operands'
  // reasons.
  lemma CompositionInventsNoFault(op: Operator, a: Outcome, b: Outcome, w: nat)
    ensures At(Compose(op, a, b), w).U? ==> At(a, w).U? || At(b, w).U?
    ensures Reasons(At(Compose(op, a, b), w)) <= Reasons(At(a, w)) + Reasons(At(b, w))
  {
    Pointwise(op, a, b, w);
  }

  lemma ErrorsComeFromOperands(universe: set<nat>, op: Operator, a: Outcome, b: Outcome)
    ensures Errors(universe, Compose(op, a, b)) <= Errors(universe, a) + Errors(universe, b)
  {
    forall r | r in Errors(universe, Compose(op, a, b))
      ensures r in Errors(universe, a) + Errors(universe, b)
    {
      var w :| w in universe && At(Compose(op, a, b), w).U? && r in At(Compose(op, a, b), w).reasons;
      CompositionInventsNoFault(op, a, b, w);
      if r in Reasons(At(a, w)) {
        assert r in Errors(universe, a);
      } else {
        assert r in Errors(universe, b);
      }
    }
  }

  // Leaf fault monotonicity does not lift to compositions: once a masking
  // witness becomes F, the fault it absorbed is visible.
  lemma MaskedFaultsCanReappear()
    ensures NoNewFaults(Value({0}), Value({}))
    ensures NoNewFaults(Fault({0}, {7}), Fault({0}, {7}))
    ensures !NoNewFaults(Compose(Union, Value({0}), Fault({0}, {7})), Compose(Union, Value({}), Fault({0}, {7})))
  {
    Pointwise(Union, Value({0}), Fault({0}, {7}), 0);
    Pointwise(Union, Value({}), Fault({0}, {7}), 0);
    assert {} + {7} == {7};
    assert At(Compose(Union, Value({}), Fault({0}, {7})), 0) == U({7});
    assert At(Compose(Union, Value({0}), Fault({0}, {7})), 0) == T;
  }

  lemma ConditionalCannotGrant(universe: set<nat>, x: Outcome)
    requires Classify(universe, x) == Conditional || Classify(universe, x) == Failure
    ensures Classify(universe, x) != Has
  {
  }

  lemma ClassificationIsExact(universe: set<nat>, x: Outcome)
    requires universe != {}
    ensures Classify(universe, x) == Has <==> AllTrue(universe, x)
    ensures Classify(universe, x) == No <==> AllFalse(universe, x)
    ensures Classify(universe, x) == Failure <==> Faulted(universe, x)
    ensures Classify(universe, x) == Conditional <==>
            !Faulted(universe, x) && (exists w | w in universe :: At(x, w).T?) && (exists w | w in universe :: At(x, w).F?)
  {
    var v :| v in universe;
    if AllTrue(universe, x) {
      assert At(x, v).T?;
    }
    if AllFalse(universe, x) {
      assert At(x, v).F?;
    }
    if !Faulted(universe, x) && !AllFalse(universe, x) && !AllTrue(universe, x) {
      var t :| t in universe && !At(x, t).F?;
      var f :| f in universe && !At(x, f).T?;
      assert At(x, t).T? && At(x, f).F?;
    }
  }

  lemma FailureReportsErrors(universe: set<nat>, x: Outcome)
    requires Classify(universe, x) == Failure
    requires forall w | w in universe && At(x, w).U? :: At(x, w).reasons != {}
    ensures Errors(universe, x) != {}
  {
    var w :| w in universe && At(x, w).U?;
    var r :| r in At(x, w).reasons;
    assert r in Errors(universe, x);
  }

  lemma DefiniteOutcomesAreDecided(universe: set<nat>, x: Outcome)
    requires Definite(x)
    ensures !Faulted(universe, x) && Classify(universe, x) != Failure
    ensures Errors(universe, x) == {}
  {
  }

  // Order-independent accumulation. Folding by union or intersection depends
  // only on the operands, not on their order or repetition.
  function FoldUnion(s: seq<Outcome>): Outcome
    ensures Normal(FoldUnion(s))
    decreases |s|
  {
    if |s| == 0 then Value({}) else Compose(Union, FoldUnion(s[..|s| - 1]), s[|s| - 1])
  }

  function FoldIntersection(universe: set<nat>, s: seq<Outcome>): Outcome
    ensures Normal(FoldIntersection(universe, s))
    decreases |s|
  {
    if |s| == 0 then Value(universe) else Compose(Intersection, FoldIntersection(universe, s[..|s| - 1]), s[|s| - 1])
  }

  function UnknownReasons(s: seq<Outcome>, w: nat): set<nat> {
    set x, r | x in s && At(x, w).U? && r in At(x, w).reasons :: r
  }

  function UnionAt(s: seq<Outcome>, w: nat): Tri {
    if exists x | x in s :: At(x, w).T? then T
    else if exists x | x in s :: At(x, w).U? then U(UnknownReasons(s, w))
    else F
  }

  function IntersectionAt(universe: set<nat>, s: seq<Outcome>, w: nat): Tri {
    if w !in universe || exists x | x in s :: At(x, w).F? then F
    else if exists x | x in s :: At(x, w).U? then U(UnknownReasons(s, w))
    else T
  }

  lemma UnknownReasonsOfSnoc(p: seq<Outcome>, x: Outcome, w: nat)
    ensures UnknownReasons(p + [x], w) == UnknownReasons(p, w) + Reasons(At(x, w))
  {
    var s := p + [x];
    forall r | r in UnknownReasons(s, w)
      ensures r in UnknownReasons(p, w) + Reasons(At(x, w))
    {
      var y :| y in s && At(y, w).U? && r in At(y, w).reasons;
      if y in p {
        assert r in UnknownReasons(p, w);
      }
    }
    forall r | r in UnknownReasons(p, w) + Reasons(At(x, w))
      ensures r in UnknownReasons(s, w)
    {
      if r in UnknownReasons(p, w) {
        var y :| y in p && At(y, w).U? && r in At(y, w).reasons;
        assert y in s;
      } else {
        assert x in s;
      }
    }
  }

  lemma NoUnknownNoReasons(s: seq<Outcome>, w: nat)
    requires forall x | x in s :: !At(x, w).U?
    ensures UnknownReasons(s, w) == {}
  {
  }

  lemma FoldUnionIsPointwise(s: seq<Outcome>, w: nat)
    ensures At(FoldUnion(s), w) == UnionAt(s, w)
    decreases |s|
  {
    if |s| > 0 {
      var p, x := s[..|s| - 1], s[|s| - 1];
      assert s == p + [x];
      FoldUnionIsPointwise(p, w);
      Pointwise(Union, FoldUnion(p), x, w);
      UnknownReasonsOfSnoc(p, x, w);
      assert forall y :: y in s <==> y in p || y == x;
      if !(exists y | y in p :: At(y, w).U?) {
        NoUnknownNoReasons(p, w);
      }
    }
  }

  lemma FoldIntersectionIsPointwise(universe: set<nat>, s: seq<Outcome>, w: nat)
    ensures At(FoldIntersection(universe, s), w) == IntersectionAt(universe, s, w)
    decreases |s|
  {
    if |s| > 0 {
      var p, x := s[..|s| - 1], s[|s| - 1];
      assert s == p + [x];
      FoldIntersectionIsPointwise(universe, p, w);
      Pointwise(Intersection, FoldIntersection(universe, p), x, w);
      UnknownReasonsOfSnoc(p, x, w);
      assert forall y :: y in s <==> y in p || y == x;
      if !(exists y | y in p :: At(y, w).U?) {
        NoUnknownNoReasons(p, w);
      }
    }
  }

  lemma SameOperandsSameReasons(s: seq<Outcome>, t: seq<Outcome>, w: nat)
    requires forall x :: x in s <==> x in t
    ensures UnknownReasons(s, w) == UnknownReasons(t, w)
  {
    forall r | r in UnknownReasons(s, w)
      ensures r in UnknownReasons(t, w)
    {
      var y :| y in s && At(y, w).U? && r in At(y, w).reasons;
      assert y in t;
    }
    forall r | r in UnknownReasons(t, w)
      ensures r in UnknownReasons(s, w)
    {
      var y :| y in t && At(y, w).U? && r in At(y, w).reasons;
      assert y in s;
    }
  }

  lemma FoldsDependOnlyOnTheirOperands(universe: set<nat>, s: seq<Outcome>, t: seq<Outcome>)
    requires forall x :: x in s <==> x in t
    ensures FoldUnion(s) == FoldUnion(t)
    ensures FoldIntersection(universe, s) == FoldIntersection(universe, t)
  {
    forall w
      ensures At(FoldUnion(s), w) == At(FoldUnion(t), w)
      ensures At(FoldIntersection(universe, s), w) == At(FoldIntersection(universe, t), w)
    {
      FoldUnionIsPointwise(s, w);
      FoldUnionIsPointwise(t, w);
      FoldIntersectionIsPointwise(universe, s, w);
      FoldIntersectionIsPointwise(universe, t, w);
      SameOperandsSameReasons(s, t, w);
    }
    Extensionality(FoldUnion(s), FoldUnion(t));
    Extensionality(FoldIntersection(universe, s), FoldIntersection(universe, t));
  }

  lemma FoldsIgnoreOrder(universe: set<nat>, s: seq<Outcome>, t: seq<Outcome>)
    requires multiset(s) == multiset(t)
    ensures FoldUnion(s) == FoldUnion(t)
    ensures FoldIntersection(universe, s) == FoldIntersection(universe, t)
  {
    forall x
      ensures x in s <==> x in t
    {
      assert x in s <==> x in multiset(s);
      assert x in t <==> x in multiset(t);
    }
    FoldsDependOnlyOnTheirOperands(universe, s, t);
  }

  lemma PrefixOperands(s: seq<Outcome>, k: nat)
    requires k <= |s|
    ensures forall x | x in s[..k] :: x in s
  {
    forall x | x in s[..k]
      ensures x in s
    {
      var i :| 0 <= i < k && s[..k][i] == x;
      assert s[i] == x;
    }
  }

  // Short circuits are sound: a union may stop once its accumulator is T in
  // every world, and an intersection once its accumulator is F in every world.
  lemma UnionMayStopAtATotalGrant(universe: set<nat>, s: seq<Outcome>, k: nat)
    requires k <= |s|
    requires forall x | x in s :: Within(universe, x)
    requires AllTrue(universe, FoldUnion(s[..k]))
    ensures FoldUnion(s) == FoldUnion(s[..k])
  {
    PrefixOperands(s, k);
    forall w
      ensures At(FoldUnion(s), w) == At(FoldUnion(s[..k]), w)
    {
      FoldUnionIsPointwise(s, w);
      FoldUnionIsPointwise(s[..k], w);
      if w in universe {
        assert At(FoldUnion(s[..k]), w).T?;
        var x :| x in s[..k] && At(x, w).T?;
        assert x in s;
      } else {
        assert forall x | x in s :: At(x, w) == F;
      }
    }
    Extensionality(FoldUnion(s), FoldUnion(s[..k]));
  }

  lemma IntersectionMayStopAtATotalDenial(universe: set<nat>, s: seq<Outcome>, k: nat)
    requires k <= |s|
    requires AllFalse(universe, FoldIntersection(universe, s[..k]))
    ensures FoldIntersection(universe, s) == FoldIntersection(universe, s[..k])
  {
    PrefixOperands(s, k);
    forall w
      ensures At(FoldIntersection(universe, s), w) == At(FoldIntersection(universe, s[..k]), w)
    {
      FoldIntersectionIsPointwise(universe, s, w);
      FoldIntersectionIsPointwise(universe, s[..k], w);
      if w in universe {
        assert At(FoldIntersection(universe, s[..k]), w).F?;
        var x :| x in s[..k] && At(x, w).F?;
        assert x in s;
      }
    }
    Extensionality(FoldIntersection(universe, s), FoldIntersection(universe, s[..k]));
  }

  // A fold may not stop at a total fault: a later operand can still decide.
  lemma FoldsMustNotStopAtAFault(universe: set<nat>, reasons: set<nat>)
    requires universe != {}
    ensures Classify(universe, FoldUnion([Fault(universe, reasons)])) == Failure
    ensures FoldUnion([Fault(universe, reasons), Value(universe)]) == Value(universe)
    ensures FoldIntersection(universe, [Fault(universe, reasons), Value({})]) == Value({})
  {
    var x := Fault(universe, reasons);
    var w :| w in universe;
    assert [x, Value(universe)][..1] == [x];
    assert [x, Value({})][..1] == [x];
    assert [x][..0] == [];
    Pointwise(Union, Value({}), x, w);
    assert At(FoldUnion([x]), w).U?;
    ComposeIsWithin(universe, Union, Value({}), x);
    TotalGrantAbsorbs(universe, FoldUnion([x]));
    TotalDenialAbsorbs(FoldIntersection(universe, [x]));
  }

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

  lemma StepIsMonotoneInItsBase(base: set<Fact>, base2: set<Fact>, rules: set<Rule>, a: set<Fact>, b: set<Fact>)
    requires base <= base2 && a <= b
    ensures Step(base, rules, a) <= Step(base2, rules, b)
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

  lemma FiniteProgress(possible: set<Fact>, prior: set<Fact>, next: set<Fact>)
    requires prior <= next && next <= possible
    ensures |possible - next| <= |possible - prior|
    ensures prior != next ==> |possible - next| < |possible - prior|
  {
    if prior != next {
      StrictFiniteProgress(possible, prior, next);
    }
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

  // Three-valued positive recursion. A cell's level is F = 0 < U = 1 < T = 2;
  // a rule's head reaches the least level of its body (Kleene intersection) and
  // a cell keeps the greatest of its base, prior and rule levels (Kleene
  // union). An interpretation is represented by its two level cuts, so its T
  // cut is the Boolean recursion above.
  datatype Interp = Interp(nonFalse: set<Fact>, isTrue: set<Fact>)

  function CellLevel(i: Interp, f: Fact): nat {
    if f in i.isTrue then 2 else if f in i.nonFalse then 1 else 0
  }

  predicate Coherent(i: Interp) {
    i.isTrue <= i.nonFalse
  }

  predicate Le(i: Interp, j: Interp) {
    i.nonFalse <= j.nonFalse && i.isTrue <= j.isTrue
  }

  lemma LeIsPointwiseLevel(i: Interp, j: Interp)
    requires Coherent(i) && Coherent(j)
    ensures Le(i, j) <==> forall f :: CellLevel(i, f) <= CellLevel(j, f)
  {
    if forall f :: CellLevel(i, f) <= CellLevel(j, f) {
      forall f | f in i.nonFalse
        ensures f in j.nonFalse
      {
        assert CellLevel(i, f) >= 1;
      }
      forall f | f in i.isTrue
        ensures f in j.isTrue
      {
        assert CellLevel(i, f) == 2;
      }
    }
  }

  function Step3(base: Interp, rules: set<Rule>, prior: Interp): Interp {
    Interp(Step(base.nonFalse, rules, prior.nonFalse), Step(base.isTrue, rules, prior.isTrue))
  }

  // The step is the Kleene step on levels: a cell reaches level k exactly when
  // its base or prior level does, or when some rule's whole body does.
  lemma Step3IsTheKleeneStep(base: Interp, rules: set<Rule>, prior: Interp, f: Fact, k: nat)
    requires Coherent(base) && Coherent(prior) && 1 <= k <= 2
    ensures Coherent(Step3(base, rules, prior))
    ensures CellLevel(Step3(base, rules, prior), f) >= k <==>
            (CellLevel(base, f) >= k || CellLevel(prior, f) >= k ||
             exists r | r in rules && r.head == f :: forall g | g in r.body :: CellLevel(prior, g) >= k)
  {
    StepIsMonotoneInItsBase(base.isTrue, base.nonFalse, rules, prior.isTrue, prior.nonFalse);
    var next := Step3(base, rules, prior);
    if k == 2 {
      if exists r | r in rules && r.head == f :: forall g | g in r.body :: CellLevel(prior, g) >= k {
        var r :| r in rules && r.head == f && forall g | g in r.body :: CellLevel(prior, g) >= k;
        assert r.body <= prior.isTrue;
      }
      if f in next.isTrue && f !in base.isTrue && f !in prior.isTrue {
        var r :| r in rules && r.body <= prior.isTrue && r.head == f;
        assert forall g | g in r.body :: CellLevel(prior, g) >= k;
      }
    } else {
      if exists r | r in rules && r.head == f :: forall g | g in r.body :: CellLevel(prior, g) >= k {
        var r :| r in rules && r.head == f && forall g | g in r.body :: CellLevel(prior, g) >= k;
        assert r.body <= prior.nonFalse;
      }
      if f in next.nonFalse && f !in base.nonFalse && f !in prior.nonFalse {
        var r :| r in rules && r.body <= prior.nonFalse && r.head == f;
        assert forall g | g in r.body :: CellLevel(prior, g) >= k;
      }
    }
  }

  lemma Step3IsMonotone(base: Interp, rules: set<Rule>, a: Interp, b: Interp)
    requires Le(a, b)
    ensures Le(a, Step3(base, rules, a))
    ensures Le(Step3(base, rules, a), Step3(base, rules, b))
  {
    PositiveStepIsMonotone(base.nonFalse, rules, a.nonFalse, b.nonFalse);
    PositiveStepIsMonotone(base.isTrue, rules, a.isTrue, b.isTrue);
  }

  function Iterate3(base: Interp, rules: set<Rule>, n: nat): Interp {
    if n == 0 then Interp({}, {}) else Step3(base, rules, Iterate3(base, rules, n - 1))
  }

  lemma Iteration3IsTheBooleanCuts(base: Interp, rules: set<Rule>, n: nat)
    ensures Iterate3(base, rules, n) == Interp(Iterate(base.nonFalse, rules, n), Iterate(base.isTrue, rules, n))
  {
    if n > 0 {
      Iteration3IsTheBooleanCuts(base, rules, n - 1);
    }
  }

  lemma Iteration3IsCoherentAndAscending(base: Interp, rules: set<Rule>, n: nat)
    requires Coherent(base)
    ensures Coherent(Iterate3(base, rules, n))
    ensures Le(Iterate3(base, rules, n), Iterate3(base, rules, n + 1))
  {
    if n > 0 {
      Iteration3IsCoherentAndAscending(base, rules, n - 1);
      var prior := Iterate3(base, rules, n - 1);
      StepIsMonotoneInItsBase(base.isTrue, base.nonFalse, rules, prior.isTrue, prior.nonFalse);
      Step3IsMonotone(base, rules, prior, Iterate3(base, rules, n));
    }
  }

  lemma Iteration3IsLeast(base: Interp, rules: set<Rule>, closed: Interp, n: nat)
    requires Le(Step3(base, rules, closed), closed)
    ensures Le(Iterate3(base, rules, n), closed)
  {
    if n > 0 {
      Iteration3IsLeast(base, rules, closed, n - 1);
      Step3IsMonotone(base, rules, Iterate3(base, rules, n - 1), closed);
    }
  }

  function Gap(possible: set<Fact>, i: Interp): nat {
    |possible - i.nonFalse| + |possible - i.isTrue|
  }

  lemma Iteration3StaysFinite(possible: set<Fact>, base: Interp, rules: set<Rule>, n: nat)
    requires Coherent(base) && BoundedInputs(possible, base.nonFalse, rules)
    ensures Iterate3(base, rules, n).nonFalse <= possible && Iterate3(base, rules, n).isTrue <= possible
  {
    if n > 0 {
      Iteration3StaysFinite(possible, base, rules, n - 1);
      var prior := Iterate3(base, rules, n - 1);
      StepStaysFinite(possible, base.nonFalse, rules, prior.nonFalse);
      StepStaysFinite(possible, base.isTrue, rules, prior.isTrue);
    }
  }

  lemma Iteration3FixesOrHasRoom(possible: set<Fact>, base: Interp, rules: set<Rule>, n: nat)
    requires Coherent(base) && BoundedInputs(possible, base.nonFalse, rules)
    ensures Iterate3(base, rules, n) == Iterate3(base, rules, n + 1) ||
            Gap(possible, Iterate3(base, rules, n + 1)) + n + 1 <= 2 * |possible|
  {
    var prior, next := Iterate3(base, rules, n), Iterate3(base, rules, n + 1);
    Iteration3IsCoherentAndAscending(base, rules, n);
    Iteration3StaysFinite(possible, base, rules, n + 1);
    FiniteProgress(possible, prior.nonFalse, next.nonFalse);
    FiniteProgress(possible, prior.isTrue, next.isTrue);
    if n == 0 {
      assert possible - {} == possible;
    } else {
      Iteration3FixesOrHasRoom(possible, base, rules, n - 1);
    }
  }

  // Ascending iteration from all-F reaches a fixed point within the height of
  // the finite level lattice: two strict rises per cell.
  lemma Iteration3ReachesItsFixedPoint(possible: set<Fact>, base: Interp, rules: set<Rule>)
    requires Coherent(base) && BoundedInputs(possible, base.nonFalse, rules)
    ensures Step3(base, rules, Iterate3(base, rules, 2 * |possible|)) == Iterate3(base, rules, 2 * |possible|)
    ensures Coherent(Iterate3(base, rules, 2 * |possible|))
  {
    Iteration3FixesOrHasRoom(possible, base, rules, 2 * |possible|);
    Iteration3IsCoherentAndAscending(base, rules, 2 * |possible|);
  }

  // That fixed point is below every pre-fixed point: the least fixed point.
  lemma KleeneLeastFixedPoint(possible: set<Fact>, base: Interp, rules: set<Rule>, closed: Interp)
    requires Coherent(base) && BoundedInputs(possible, base.nonFalse, rules)
    requires Le(Step3(base, rules, closed), closed)
    ensures Step3(base, rules, Iterate3(base, rules, 2 * |possible|)) == Iterate3(base, rules, 2 * |possible|)
    ensures Le(Iterate3(base, rules, 2 * |possible|), closed)
  {
    Iteration3ReachesItsFixedPoint(possible, base, rules);
    Iteration3IsLeast(base, rules, closed, 2 * |possible|);
  }
}
