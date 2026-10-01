// Phase 2 proof-only model. No ANTLR, database, clock, or serving dependency.
module CaveatOutcomes {
  datatype Outcome = Truth | Falsity | Unknown(missing: set<nat>) | Fault(reason: nat)
  datatype Plan = Leaf(value: Outcome) | Negate(child: Plan) | Both(left: Plan, right: Plan) | Either(left: Plan, right: Plan)

  function Not(x: Outcome): Outcome {
    if x.Truth? then Falsity else if x.Falsity? then Truth else x
  }

  function ErrorChoice(a: Outcome, b: Outcome): Outcome {
    if a.Fault? && b.Fault? then Fault(if a.reason <= b.reason then a.reason else b.reason)
    else if a.Fault? then a else b
  }

  function Missing(a: Outcome, b: Outcome): Outcome {
    Unknown((if a.Unknown? then a.missing else {}) + (if b.Unknown? then b.missing else {}))
  }

  function And(a: Outcome, b: Outcome): Outcome {
    if a.Falsity? || b.Falsity? then Falsity
    else if a.Fault? || b.Fault? then ErrorChoice(a, b)
    else if a.Unknown? || b.Unknown? then Missing(a, b)
    else Truth
  }

  function Or(a: Outcome, b: Outcome): Outcome {
    if a.Truth? || b.Truth? then Truth
    else if a.Fault? || b.Fault? then ErrorChoice(a, b)
    else if a.Unknown? || b.Unknown? then Missing(a, b)
    else Falsity
  }

  function Nodes(p: Plan): nat {
    match p
    case Leaf(_) => 1
    case Negate(c) => 1 + Nodes(c)
    case Both(a, b) => 1 + Nodes(a) + Nodes(b)
    case Either(a, b) => 1 + Nodes(a) + Nodes(b)
  }

  function Evaluate(p: Plan): Outcome {
    match p
    case Leaf(v) => v
    case Negate(c) => Not(Evaluate(c))
    case Both(a, b) => And(Evaluate(a), Evaluate(b))
    case Either(a, b) => Or(Evaluate(a), Evaluate(b))
  }

  function Bounded(p: Plan, budget: nat): Outcome {
    if Nodes(p) > budget then Fault(0) else Evaluate(p)
  }

  function Merge<T>(request: map<nat, T>, bound: map<nat, T>): map<nat, T> {
    map k | k in request.Keys + bound.Keys :: if k in bound then bound[k] else request[k]
  }

  lemma BoundWins<T>(request: map<nat, T>, bound: map<nat, T>, k: nat)
    requires k in bound
    ensures k in Merge(request, bound) && Merge(request, bound)[k] == bound[k]
  {
  }

  lemma RequestSurvives<T>(request: map<nat, T>, bound: map<nat, T>, k: nat)
    requires k in request && k !in bound
    ensures k in Merge(request, bound) && Merge(request, bound)[k] == request[k]
  {
  }

  lemma LogicalAbsorbers(x: Outcome)
    ensures And(x, Falsity) == Falsity && And(Falsity, x) == Falsity
    ensures Or(x, Truth) == Truth && Or(Truth, x) == Truth
  {
  }

  lemma LogicalCommutativity(a: Outcome, b: Outcome)
    ensures And(a, b) == And(b, a)
    ensures Or(a, b) == Or(b, a)
  {
  }

  lemma LogicalIdentities(x: Outcome)
    ensures And(x, Truth) == x
    ensures Or(x, Falsity) == x
    ensures Not(Not(x)) == x
  {
  }

  lemma FaultIsNotMissing(reason: nat, fields: set<nat>)
    ensures And(Fault(reason), Unknown(fields)) == Fault(reason)
    ensures Or(Fault(reason), Unknown(fields)) == Fault(reason)
  {
  }

  lemma MissingUnion(a: set<nat>, b: set<nat>)
    ensures And(Unknown(a), Unknown(b)) == Unknown(a + b)
    ensures Or(Unknown(a), Unknown(b)) == Unknown(a + b)
  {
  }

  lemma TotalClassification(p: Plan)
    ensures Evaluate(p).Truth? || Evaluate(p).Falsity? || Evaluate(p).Unknown? || Evaluate(p).Fault?
  {
  }

  lemma Deterministic(p: Plan, a: Outcome, b: Outcome)
    requires a == Evaluate(p) && b == Evaluate(p)
    ensures a == b
  {
  }

  lemma StrictSubtreeProgress(p: Plan)
    ensures p.Negate? ==> Nodes(p.child) < Nodes(p)
    ensures p.Both? || p.Either? ==> Nodes(p.left) < Nodes(p) && Nodes(p.right) < Nodes(p)
  {
  }

  lemma BudgetRejectsBeforeValue(p: Plan, budget: nat)
    requires Nodes(p) > budget
    ensures Bounded(p, budget) == Fault(0)
  {
  }

  lemma SufficientBudgetDoesNotChangeOutcome(p: Plan, a: nat, b: nat)
    requires Nodes(p) <= a && a <= b
    ensures Bounded(p, a) == Evaluate(p) && Bounded(p, b) == Evaluate(p)
  {
  }

  // A cache may substitute only an equal plan/semantic identity.
  lemma CacheIsOnlyWork(p: Plan, cached: Plan, budget: nat)
    requires p == cached
    ensures Bounded(p, budget) == Bounded(cached, budget)
  {
  }

  // Profile 2 comprehensions. CEL expands `xs.exists(x, p)` to a fold of `||`
  // from false and `xs.all(x, p)` to a fold of `&&` from true over the
  // outcomes of p for each element. An absent range is Unknown on its own and
  // is not folded.
  function Exists(xs: seq<Outcome>): Outcome
    decreases |xs|
  {
    if |xs| == 0 then Falsity else Or(xs[0], Exists(xs[1..]))
  }

  function All(xs: seq<Outcome>): Outcome
    decreases |xs|
  {
    if |xs| == 0 then Truth else And(xs[0], All(xs[1..]))
  }

  function MissingOf(xs: seq<Outcome>): set<nat>
    decreases |xs|
  {
    if |xs| == 0 then {} else (if xs[0].Unknown? then xs[0].missing else {}) + MissingOf(xs[1..])
  }

  // The residual of an undecided fold keeps only its Unknown elements.
  function Undecided(xs: seq<Outcome>): seq<Outcome>
    decreases |xs|
  {
    if |xs| == 0 then [] else (if xs[0].Unknown? then [xs[0]] else []) + Undecided(xs[1..])
  }

  lemma EmptyComprehensions()
    ensures Exists([]) == Falsity && All([]) == Truth
  {
  }

  lemma OrAssociative(a: Outcome, b: Outcome, c: Outcome)
    ensures Or(Or(a, b), c) == Or(a, Or(b, c))
  {
  }

  lemma AndAssociative(a: Outcome, b: Outcome, c: Outcome)
    ensures And(And(a, b), c) == And(a, And(b, c))
  {
  }

  // A true element decides exists, whatever faults or missing fields the
  // other elements have; a false one decides all.
  lemma TruthDecidesExists(xs: seq<Outcome>, i: nat)
    requires i < |xs| && xs[i].Truth?
    ensures Exists(xs) == Truth
    decreases |xs|
  {
    if i > 0 {
      TruthDecidesExists(xs[1..], i - 1);
    }
  }

  lemma FalsityDecidesAll(xs: seq<Outcome>, i: nat)
    requires i < |xs| && xs[i].Falsity?
    ensures All(xs) == Falsity
    decreases |xs|
  {
    if i > 0 {
      FalsityDecidesAll(xs[1..], i - 1);
    }
  }

  lemma WithoutTruthExistsIsNotTruth(xs: seq<Outcome>)
    requires forall j | 0 <= j < |xs| :: !xs[j].Truth?
    ensures !Exists(xs).Truth?
    decreases |xs|
  {
    if |xs| > 0 {
      WithoutTruthExistsIsNotTruth(xs[1..]);
    }
  }

  lemma WithoutFalsityAllIsNotFalsity(xs: seq<Outcome>)
    requires forall j | 0 <= j < |xs| :: !xs[j].Falsity?
    ensures !All(xs).Falsity?
    decreases |xs|
  {
    if |xs| > 0 {
      WithoutFalsityAllIsNotFalsity(xs[1..]);
    }
  }

  // Without a deciding element, a fault is the result, before any missing
  // field: EACL's fail-closed order, the same as for `&&` and `||`.
  lemma FaultWithoutTruthFaultsExists(xs: seq<Outcome>, i: nat)
    requires forall j | 0 <= j < |xs| :: !xs[j].Truth?
    requires i < |xs| && xs[i].Fault?
    ensures Exists(xs).Fault?
    decreases |xs|
  {
    WithoutTruthExistsIsNotTruth(xs[1..]);
    if i > 0 {
      FaultWithoutTruthFaultsExists(xs[1..], i - 1);
    }
  }

  lemma FaultWithoutFalsityFaultsAll(xs: seq<Outcome>, i: nat)
    requires forall j | 0 <= j < |xs| :: !xs[j].Falsity?
    requires i < |xs| && xs[i].Fault?
    ensures All(xs).Fault?
    decreases |xs|
  {
    WithoutFalsityAllIsNotFalsity(xs[1..]);
    if i > 0 {
      FaultWithoutFalsityFaultsAll(xs[1..], i - 1);
    }
  }

  lemma DecidedMissNothing(xs: seq<Outcome>)
    requires forall j | 0 <= j < |xs| :: !xs[j].Unknown?
    ensures MissingOf(xs) == {}
    decreases |xs|
  {
    if |xs| > 0 {
      DecidedMissNothing(xs[1..]);
    }
  }

  // Otherwise exists is Unknown exactly when an element is, missing the union
  // of their fields, and Falsity when none is.
  lemma UndecidedExists(xs: seq<Outcome>)
    requires forall j | 0 <= j < |xs| :: xs[j].Falsity? || xs[j].Unknown?
    ensures (exists j | 0 <= j < |xs| :: xs[j].Unknown?) ==> Exists(xs) == Unknown(MissingOf(xs))
    ensures (forall j | 0 <= j < |xs| :: xs[j].Falsity?) ==> Exists(xs) == Falsity
    decreases |xs|
  {
    if |xs| > 0 {
      var rest := xs[1..];
      UndecidedExists(rest);
      if exists j | 0 <= j < |rest| :: rest[j].Unknown? {
        assert Exists(rest) == Unknown(MissingOf(rest));
      } else {
        assert forall j | 0 <= j < |rest| :: rest[j].Falsity?;
        assert Exists(rest) == Falsity;
        DecidedMissNothing(rest);
      }
      if xs[0].Falsity? && exists j | 0 <= j < |xs| :: xs[j].Unknown? {
        var j :| 0 <= j < |xs| && xs[j].Unknown?;
        assert rest[j - 1].Unknown?;
      }
    }
  }

  lemma UndecidedAll(xs: seq<Outcome>)
    requires forall j | 0 <= j < |xs| :: xs[j].Truth? || xs[j].Unknown?
    ensures (exists j | 0 <= j < |xs| :: xs[j].Unknown?) ==> All(xs) == Unknown(MissingOf(xs))
    ensures (forall j | 0 <= j < |xs| :: xs[j].Truth?) ==> All(xs) == Truth
    decreases |xs|
  {
    if |xs| > 0 {
      var rest := xs[1..];
      UndecidedAll(rest);
      if exists j | 0 <= j < |rest| :: rest[j].Unknown? {
        assert All(rest) == Unknown(MissingOf(rest));
      } else {
        assert forall j | 0 <= j < |rest| :: rest[j].Truth?;
        assert All(rest) == Truth;
        DecidedMissNothing(rest);
      }
      if xs[0].Truth? && exists j | 0 <= j < |xs| :: xs[j].Unknown? {
        var j :| 0 <= j < |xs| && xs[j].Unknown?;
        assert rest[j - 1].Unknown?;
      }
    }
  }

  // Keeping only the undecided elements, as the residual does, keeps the
  // fold's outcome, including its missing fields.
  lemma ResidualKeepsUndecided(xs: seq<Outcome>)
    requires forall j | 0 <= j < |xs| :: xs[j].Falsity? || xs[j].Unknown?
    ensures Exists(Undecided(xs)) == Exists(xs)
    ensures MissingOf(Undecided(xs)) == MissingOf(xs)
    decreases |xs|
  {
    if |xs| > 0 {
      var rest := Undecided(xs[1..]);
      ResidualKeepsUndecided(xs[1..]);
      if xs[0].Unknown? {
        var kept := [xs[0]] + rest;
        assert Undecided(xs) == kept;
        assert kept[0] == xs[0] && kept[1..] == rest;
        assert Exists(kept) == Or(xs[0], Exists(rest));
        assert MissingOf(kept) == xs[0].missing + MissingOf(rest);
      } else {
        assert Undecided(xs) == [] + rest == rest;
        LogicalIdentities(Exists(xs[1..]));
        LogicalCommutativity(Falsity, Exists(xs[1..]));
      }
    }
  }

  // Folding in two parts is folding the whole, so the outcome depends on no
  // particular iteration order: map keys may be visited in any order.
  lemma ExistsAppend(xs: seq<Outcome>, ys: seq<Outcome>)
    ensures Exists(xs + ys) == Or(Exists(xs), Exists(ys))
    decreases |xs|
  {
    if |xs| == 0 {
      assert xs + ys == ys;
    } else {
      assert (xs + ys)[1..] == xs[1..] + ys;
      ExistsAppend(xs[1..], ys);
      OrAssociative(xs[0], Exists(xs[1..]), Exists(ys));
    }
  }

  lemma AllAppend(xs: seq<Outcome>, ys: seq<Outcome>)
    ensures All(xs + ys) == And(All(xs), All(ys))
    decreases |xs|
  {
    if |xs| == 0 {
      assert xs + ys == ys;
    } else {
      assert (xs + ys)[1..] == xs[1..] + ys;
      AllAppend(xs[1..], ys);
      AndAssociative(xs[0], All(xs[1..]), All(ys));
    }
  }

  lemma OrderIndependent(xs: seq<Outcome>, ys: seq<Outcome>)
    ensures Exists(xs + ys) == Exists(ys + xs)
    ensures All(xs + ys) == All(ys + xs)
  {
    ExistsAppend(xs, ys);
    ExistsAppend(ys, xs);
    AllAppend(xs, ys);
    AllAppend(ys, xs);
    LogicalCommutativity(Exists(xs), Exists(ys));
    LogicalCommutativity(All(xs), All(ys));
  }
}
