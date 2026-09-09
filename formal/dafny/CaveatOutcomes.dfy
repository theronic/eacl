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
}
