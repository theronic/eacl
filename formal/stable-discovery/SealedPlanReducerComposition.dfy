// Exploratory composition proof from one accepted sealed rule vector to the
// concrete right-edge stack trace. The runtime must append successors in
// reverse vector order so repeated right-edge pops expose canonical order.
include "SealedVectorOrder.dfy"
include "RuntimeStackRefinement.dfy"

module SealedPlanReducerComposition {
  import V = SealedVectorOrder
  import S = RuntimeStackRefinement

  function DrainRight<T>(stack: seq<T>): seq<T>
    decreases |stack|
  {
    if |stack| == 0 then []
    else [stack[|stack| - 1]] +
         DrainRight(stack[..|stack| - 1])
  }

  lemma ReverseNonemptyDecomposes<T>(values: seq<T>)
    requires |values| > 0
    ensures S.Reverse(values) ==
            S.Reverse(values[1..]) + [values[0]]
  {
  }

  lemma DrainReverseIsOriginal<T>(values: seq<T>)
    ensures DrainRight(S.Reverse(values)) == values
    decreases |values|
  {
    if |values| > 0 {
      ReverseNonemptyDecomposes(values);
      S.ReverseLength(values[1..]);
      assert S.Reverse(values)[..|S.Reverse(values)| - 1] ==
             S.Reverse(values[1..]);
      DrainReverseIsOriginal(values[1..]);
    }
  }

  function RunAcceptedVector(
    candidate: seq<V.Rule>
  ): seq<V.Rule> {
    DrainRight(S.Reverse(candidate))
  }

  lemma ConcreteRightStackConsumesCanonicalVector(
    expected: set<V.Rule>,
    candidate: seq<V.Rule>
  )
    requires V.AcceptedVector(expected, candidate)
    ensures RunAcceptedVector(candidate) == candidate
  {
    DrainReverseIsOriginal(candidate);
  }

  lemma AnyAcceptedPlanProducesOneConcreteTrace(
    expected: set<V.Rule>,
    left: seq<V.Rule>,
    right: seq<V.Rule>
  )
    requires V.AcceptedVector(expected, left)
    requires V.AcceptedVector(expected, right)
    ensures RunAcceptedVector(left) == RunAcceptedVector(right)
  {
    V.TwoAcceptedVectorsAreEqual(expected, left, right);
  }

  function WrongPushWithoutReverse<T>(
    candidate: seq<T>
  ): seq<T> {
    DrainRight(candidate)
  }

  lemma WrongPushOrderChangesTrace()
    ensures
      var cheap := V.Rule(0, 0);
      var costly := V.Rule(1, 1);
      var candidate := [cheap, costly];
      WrongPushWithoutReverse(candidate) != candidate
  {
  }
}
