// Exact ordered projections are reusable; context-free denotations are not.
// Exploratory proof model; intentionally excluded from release artifacts.
include "StableReducer.dfy"

module CacheBoundary {
  import R = StableReducer

  // A projection is the complete ordered logical successor response for one
  // equality-complete physical occurrence, including residual scan work.
  function StepWithProjection(
    program: R.Program,
    state: R.State,
    projection: seq<nat>
  ): R.State
  {
    if |state.stack| == 0 then
      state
    else
      var node := state.stack[0];
      var admission := R.Admit(projection, state.admitted);
      var nextStack := admission.newValues + state.stack[1..];
      if node in program.resultNodes then
        R.State(nextStack, admission.seen, state.results + [node])
      else
        R.State(nextStack, admission.seen, state.results)
  }

  lemma ExactOrderedProjectionPreservesStep(
    program: R.Program,
    state: R.State,
    cachedProjection: seq<nat>
  )
    requires |state.stack| > 0
    requires cachedProjection ==
             R.Successors(program, state.stack[0])
    ensures StepWithProjection(program, state, cachedProjection) ==
            R.Step(program, state)
  {
  }

  lemma EqualProjectionSetsDoNotPreserveOrder()
    ensures var program := R.Program([[1, 2], [], []], {1, 2});
            var state := R.Initial(program, [0]);
            R.SeqSet([1, 2]) == R.SeqSet([2, 1]) &&
            StepWithProjection(program, state, [1, 2]) !=
            StepWithProjection(program, state, [2, 1])
  {
    var program := R.Program([[1, 2], [], []], {1, 2});
    var state := R.Initial(program, [0]);
    assert R.Admit([0], {}) == R.Admission([0], {0});
    assert state == R.State([0], {0}, []);
    assert R.SeqSet([1, 2]) == {1, 2};
    assert R.SeqSet([2, 1]) == {1, 2};
    assert R.Admit([1, 2], {0}) ==
           R.Admission([1, 2], {0, 1, 2});
    assert R.Admit([2, 1], {0}) ==
           R.Admission([2, 1], {0, 1, 2});
    assert StepWithProjection(program, state, [1, 2]) ==
           R.State([1, 2], {0, 1, 2}, []);
    assert StepWithProjection(program, state, [2, 1]) ==
           R.State([2, 1], {0, 1, 2}, []);
  }

  // Node 2 is admitted as a sibling before node 1 runs. Consequently node 1
  // skips 2 and discovers 3 first. A fresh traversal rooted at node 1 sees 2
  // before 3. Both traversals have the same denotation set, but not the same
  // stable sequence. A complete context-free denotation therefore cannot
  // replace request-local reducer execution.
  lemma ContextFreeDenotationIsNotAStableTrace()
    ensures var program :=
              R.Program([[1, 2], [2, 3], [], []], {1, 2, 3});
            var globalBeforeOne :=
              R.Step(program, R.Initial(program, [0]));
            var globalResult := R.Run(program, globalBeforeOne, 3).results;
            var freshResult :=
              R.Run(program, R.Initial(program, [1]), 3).results;
            globalResult == [1, 3, 2] &&
            freshResult == [1, 2, 3] &&
            globalResult != freshResult &&
            R.SeqSet(globalResult) == R.SeqSet(freshResult)
  {
    var program :=
      R.Program([[1, 2], [2, 3], [], []], {1, 2, 3});

    var globalInitial := R.Initial(program, [0]);
    assert globalInitial == R.State([0], {0}, []);
    var globalBeforeOne := R.Step(program, globalInitial);
    assert globalBeforeOne == R.State([1, 2], {0, 1, 2}, []);
    var globalAfterOne := R.Step(program, globalBeforeOne);
    assert globalAfterOne ==
           R.State([3, 2], {0, 1, 2, 3}, [1]);
    var globalAfterThree := R.Step(program, globalAfterOne);
    assert globalAfterThree ==
           R.State([2], {0, 1, 2, 3}, [1, 3]);
    var globalAfterTwo := R.Step(program, globalAfterThree);
    assert globalAfterTwo ==
           R.State([], {0, 1, 2, 3}, [1, 3, 2]);
    assert R.Run(program, globalBeforeOne, 3) == globalAfterTwo;

    var freshInitial := R.Initial(program, [1]);
    assert freshInitial == R.State([1], {1}, []);
    var freshAfterOne := R.Step(program, freshInitial);
    assert freshAfterOne == R.State([2, 3], {1, 2, 3}, [1]);
    var freshAfterTwo := R.Step(program, freshAfterOne);
    assert freshAfterTwo == R.State([3], {1, 2, 3}, [1, 2]);
    var freshAfterThree := R.Step(program, freshAfterTwo);
    assert freshAfterThree == R.State([], {1, 2, 3}, [1, 2, 3]);
    assert R.Run(program, freshInitial, 3) == freshAfterThree;

    assert R.SeqSet([1, 3, 2]) == {1, 2, 3};
    assert R.SeqSet([1, 2, 3]) == {1, 2, 3};
  }
}
