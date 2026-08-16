// Exploratory proof model; intentionally excluded from release artifacts.
module StableReducer {
  datatype Admission<T(==)> = Admission(
    newValues: seq<T>,
    seen: set<T>
  )

  datatype Program = Program(
    successors: seq<seq<nat>>,
    resultNodes: set<nat>
  )

  datatype State = State(
    stack: seq<nat>,
    admitted: set<nat>,
    results: seq<nat>
  )

  function Range(count: nat): set<nat>
    decreases count
  {
    if count == 0 then {} else Range(count - 1) + {count - 1}
  }

  lemma RangeMembership(count: nat, value: nat)
    ensures value in Range(count) <==> value < count
    decreases count
  {
    if count > 0 {
      RangeMembership(count - 1, value);
    }
  }

  function Nodes(program: Program): set<nat> {
    Range(|program.successors|)
  }

  function Successors(program: Program, node: nat): seq<nat> {
    if node < |program.successors| then
      program.successors[node]
    else
      []
  }

  predicate Unique<T(==)>(values: seq<T>)
    decreases |values|
  {
    |values| == 0 ||
    (values[0] !in SeqSet(values[1..]) && Unique(values[1..]))
  }

  function SeqSet<T(==)>(values: seq<T>): set<T>
    decreases |values|
  {
    if |values| == 0 then
      {}
    else
      {values[0]} + SeqSet(values[1..])
  }

  lemma SeqSetHeadTail<T>(values: seq<T>)
    requires 0 < |values|
    ensures SeqSet(values) == {values[0]} + SeqSet(values[1..])
  {
  }

  lemma SeqSetConcat<T>(left: seq<T>, right: seq<T>)
    ensures SeqSet(left + right) == SeqSet(left) + SeqSet(right)
    decreases |left|
  {
    if |left| > 0 {
      assert (left + right)[0] == left[0];
      assert (left + right)[1..] == left[1..] + right;
      SeqSetHeadTail(left);
      SeqSetHeadTail(left + right);
      SeqSetConcat(left[1..], right);
      calc {
        SeqSet(left + right);
        ==
        ({left[0]} + SeqSet(left[1..] + right));
        ==
        ({left[0]} + (SeqSet(left[1..]) + SeqSet(right)));
        ==
        ({left[0]} + SeqSet(left[1..])) + SeqSet(right);
        ==
        SeqSet(left) + SeqSet(right);
      }
    } else {
      assert left + right == right;
      assert SeqSet(left) == {};
    }
  }

  lemma ConcatenationIsUnique<T>(left: seq<T>, right: seq<T>)
    requires Unique(left)
    requires Unique(right)
    requires SeqSet(left) * SeqSet(right) == {}
    ensures Unique(left + right)
    decreases |left|
  {
    if |left| > 0 {
      SeqSetHeadTail(left);
      SeqSetConcat(left[1..], right);
      assert left[0] !in SeqSet(left[1..] + right);
      ConcatenationIsUnique(left[1..], right);
      assert (left + right)[0] == left[0];
      assert (left + right)[1..] == left[1..] + right;
      assert Unique(left[1..] + right);
      assert Unique(left + right);
    } else {
      assert left + right == right;
    }
  }

  lemma AppendFreshIsUnique<T>(values: seq<T>, value: T)
    requires Unique(values)
    requires value !in SeqSet(values)
    ensures Unique(values + [value])
  {
    ConcatenationIsUnique(values, [value]);
  }

  lemma AppendMemberPreservesSubset<T>(
    values: seq<T>,
    value: T,
    allowed: set<T>
  )
    requires SeqSet(values) <= allowed
    requires value in allowed
    ensures SeqSet(values + [value]) <= allowed
  {
    SeqSetConcat(values, [value]);
  }

  lemma AppendOutsidePreservesDisjoint<T>(
    values: seq<T>,
    value: T,
    other: set<T>
  )
    requires SeqSet(values) * other == {}
    requires value !in other
    ensures SeqSet(values + [value]) * other == {}
  {
    SeqSetConcat(values, [value]);
  }

  function Admit<T(==)>(
    values: seq<T>,
    seen: set<T>
  ): Admission<T>
    decreases |values|
  {
    if |values| == 0 then
      Admission([], seen)
    else if values[0] in seen then
      Admit(values[1..], seen)
    else
      var suffix := Admit(values[1..], seen + {values[0]});
      Admission([values[0]] + suffix.newValues, suffix.seen)
  }

  lemma AdmitProperties<T>(
    values: seq<T>,
    seen: set<T>
  )
    ensures seen <= Admit(values, seen).seen
    ensures Admit(values, seen).seen == seen + SeqSet(values)
    ensures SeqSet(Admit(values, seen).newValues) <= SeqSet(values)
    ensures SeqSet(Admit(values, seen).newValues) ==
            SeqSet(values) - seen
    ensures SeqSet(Admit(values, seen).newValues) <=
            Admit(values, seen).seen
    ensures SeqSet(Admit(values, seen).newValues) * seen == {}
    ensures Unique(Admit(values, seen).newValues)
    decreases |values|
  {
    if |values| > 0 {
      SeqSetHeadTail(values);
      if values[0] in seen {
        AdmitProperties(values[1..], seen);
      } else {
        AdmitProperties(values[1..], seen + {values[0]});
        var suffix := Admit(values[1..], seen + {values[0]}).newValues;
        assert SeqSet(suffix) * (seen + {values[0]}) == {};
        assert values[0] !in SeqSet(suffix);
      }
    }
  }

  predicate ValidProgram(program: Program) {
    program.resultNodes <= Nodes(program) &&
    forall node: nat | node < |program.successors| ::
      SeqSet(Successors(program, node)) <= Nodes(program)
  }

  predicate ExactState(program: Program, state: State) {
    ValidProgram(program) &&
    Unique(state.stack) &&
    SeqSet(state.stack) <= state.admitted &&
    SeqSet(state.stack) <= Nodes(program) &&
    Unique(state.results) &&
    SeqSet(state.results) <= program.resultNodes &&
    SeqSet(state.results) <= state.admitted &&
    SeqSet(state.results) * SeqSet(state.stack) == {}
  }

  function Initial(
    program: Program,
    roots: seq<nat>
  ): State
  {
    var admission := Admit(roots, {});
    State(admission.newValues, admission.seen, [])
  }

  lemma InitialIsExact(program: Program, roots: seq<nat>)
    requires ValidProgram(program)
    requires SeqSet(roots) <= Nodes(program)
    ensures ExactState(program, Initial(program, roots))
  {
    var admission := Admit(roots, {});
    AdmitProperties(roots, {});
    assert SeqSet(admission.newValues) <= SeqSet(roots);
    assert SeqSet(admission.newValues) <= Nodes(program);
  }

  function Step(program: Program, state: State): State {
    if |state.stack| == 0 then
      state
    else
      var node := state.stack[0];
      var admission := Admit(Successors(program, node), state.admitted);
      var nextStack := admission.newValues + state.stack[1..];
      if node in program.resultNodes then
        State(
          nextStack,
          admission.seen,
          state.results + [node]
        )
      else
        State(
          nextStack,
          admission.seen,
          state.results
        )
  }

  lemma StepPreservesExactState(program: Program, state: State)
    requires ExactState(program, state)
    ensures ExactState(program, Step(program, state))
  {
    if |state.stack| > 0 {
      var node := state.stack[0];
      var tail := state.stack[1..];
      SeqSetHeadTail(state.stack);
      assert node in SeqSet(state.stack);
      assert Unique(state.results);
      assert SeqSet(state.results) <= program.resultNodes;
      assert SeqSet(state.results) <= state.admitted;
      assert SeqSet(state.results) * SeqSet(state.stack) == {};
      assert node !in SeqSet(state.results);
      assert node in Nodes(program);
      RangeMembership(|program.successors|, node);
      assert node < |program.successors|;
      var admission := Admit(Successors(program, node), state.admitted);

      AdmitProperties(Successors(program, node), state.admitted);
      assert ValidProgram(program);

      assert Unique(tail);
      assert SeqSet(tail) <= state.admitted;
      assert SeqSet(admission.newValues) * SeqSet(tail) == {};
      ConcatenationIsUnique(admission.newValues, tail);
      SeqSetConcat(admission.newValues, tail);
      assert SeqSet(admission.newValues + tail) ==
             SeqSet(admission.newValues) + SeqSet(tail);
      assert SeqSet(admission.newValues + tail) <= admission.seen;
      assert SeqSet(Successors(program, node)) <= Nodes(program);
      assert SeqSet(admission.newValues) <= Nodes(program);
      assert SeqSet(tail) <= Nodes(program);
      assert SeqSet(admission.newValues + tail) <= Nodes(program);

      assert SeqSet(state.results) *
             SeqSet(admission.newValues + tail) == {};
      if node in program.resultNodes {
        assert node !in SeqSet(state.results);
        assert node !in SeqSet(admission.newValues);
        assert node !in SeqSet(tail);
        assert node !in SeqSet(admission.newValues + tail);
        assert Unique(state.results) &&
               node !in SeqSet(state.results);
        AppendFreshIsUnique<nat>(state.results, node);
        AppendMemberPreservesSubset<nat>(
          state.results, node, program.resultNodes
        );
        assert state.admitted <= admission.seen;
        assert SeqSet(state.results) <= admission.seen;
        AppendMemberPreservesSubset<nat>(
          state.results, node, admission.seen
        );
        AppendOutsidePreservesDisjoint<nat>(
          state.results,
          node,
          SeqSet(admission.newValues + tail)
        );
      }
    }
  }

  predicate IsPrefix<T(==)>(prefix: seq<T>, whole: seq<T>) {
    |prefix| <= |whole| && prefix == whole[..|prefix|]
  }

  lemma StepOnlyExtendsResults(program: Program, state: State)
    ensures IsPrefix(state.results, Step(program, state).results)
    ensures |Step(program, state).results| <= |state.results| + 1
  {
  }

  function Run(
    program: Program,
    state: State,
    fuel: nat
  ): State
    decreases fuel
  {
    if fuel == 0 || |state.stack| == 0 then
      state
    else
      Run(program, Step(program, state), fuel - 1)
  }

  lemma RunIsExact(
    program: Program,
    state: State,
    fuel: nat
  )
    requires ExactState(program, state)
    ensures ExactState(program, Run(program, state, fuel))
    decreases fuel
  {
    if fuel > 0 && |state.stack| > 0 {
      StepPreservesExactState(program, state);
      RunIsExact(program, Step(program, state), fuel - 1);
    }
  }

  lemma PrefixTransitive<T>(
    left: seq<T>,
    middle: seq<T>,
    right: seq<T>
  )
    requires IsPrefix(left, middle)
    requires IsPrefix(middle, right)
    ensures IsPrefix(left, right)
  {
  }

  lemma RunOnlyExtendsResults(
    program: Program,
    state: State,
    fuel: nat
  )
    requires ExactState(program, state)
    ensures IsPrefix(state.results, Run(program, state, fuel).results)
    decreases fuel
  {
    if fuel > 0 && |state.stack| > 0 {
      StepOnlyExtendsResults(program, state);
      StepPreservesExactState(program, state);
      RunOnlyExtendsResults(program, Step(program, state), fuel - 1);
      PrefixTransitive(
        state.results,
        Step(program, state).results,
        Run(program, Step(program, state), fuel - 1).results
      );
    }
  }
}
