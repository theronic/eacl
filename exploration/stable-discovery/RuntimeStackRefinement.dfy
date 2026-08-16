// Exploratory representation proof; intentionally excluded from release artifacts.
module RuntimeStackRefinement {
  function Reverse<T>(values: seq<T>): seq<T>
    decreases |values|
  {
    if |values| == 0 then []
    else Reverse(values[1..]) + [values[0]]
  }

  lemma ReverseLength<T>(values: seq<T>)
    ensures |Reverse(values)| == |values|
    decreases |values|
  {
    if |values| > 0 {
      ReverseLength(values[1..]);
    }
  }

  lemma ReverseConcatenates<T>(left: seq<T>, right: seq<T>)
    ensures Reverse(left + right) == Reverse(right) + Reverse(left)
    decreases |left|
  {
    if |left| > 0 {
      assert (left + right)[1..] == left[1..] + right;
      ReverseConcatenates(left[1..], right);
    } else {
      assert left + right == right;
    }
  }

  predicate Represents<T(==)>(concrete: seq<T>, abstractStack: seq<T>) {
    concrete == Reverse(abstractStack)
  }

  function PopAndPushCanonical<T>(
    concrete: seq<T>,
    admittedInCanonicalOrder: seq<T>
  ): seq<T>
    requires |concrete| > 0
  {
    concrete[..|concrete| - 1] +
    Reverse(admittedInCanonicalOrder)
  }

  lemma RightHeadRefinesAbstractHead<T>(
    concrete: seq<T>,
    abstractStack: seq<T>
  )
    requires |abstractStack| > 0
    requires Represents(concrete, abstractStack)
    ensures |concrete| > 0
    ensures concrete[|concrete| - 1] == abstractStack[0]
  {
    ReverseLength(abstractStack[1..]);
    assert concrete ==
           Reverse(abstractStack[1..]) + [abstractStack[0]];
  }

  lemma RightPopRefinesAbstractTail<T>(
    concrete: seq<T>,
    abstractStack: seq<T>
  )
    requires |abstractStack| > 0
    requires Represents(concrete, abstractStack)
    ensures Represents(
              concrete[..|concrete| - 1],
              abstractStack[1..]
            )
  {
    ReverseLength(abstractStack[1..]);
    assert concrete ==
           Reverse(abstractStack[1..]) + [abstractStack[0]];
  }

  lemma PopPushRefinesCanonicalReplacement<T>(
    concrete: seq<T>,
    abstractStack: seq<T>,
    admittedInCanonicalOrder: seq<T>
  )
    requires |abstractStack| > 0
    requires Represents(concrete, abstractStack)
    ensures Represents(
              PopAndPushCanonical(
                concrete,
                admittedInCanonicalOrder
              ),
              admittedInCanonicalOrder + abstractStack[1..]
            )
  {
    RightPopRefinesAbstractTail(concrete, abstractStack);
    ReverseConcatenates(
      admittedInCanonicalOrder,
      abstractStack[1..]
    );
  }

  lemma EmptyRepresentation<T>()
    ensures Represents<T>([], [])
  {
  }
}
