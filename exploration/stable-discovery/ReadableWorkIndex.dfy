// Exploratory proof model; intentionally excluded from release artifacts.
module ReadableWorkIndex {
  datatype Work =
    | PureWork(id: nat)
    | ScanWork(id: nat, descriptor: nat)

  function ScanProjection(work: seq<Work>): seq<Work>
    decreases |work|
  {
    if |work| == 0 then
      []
    else if work[0].ScanWork? then
      [work[0]] + ScanProjection(work[1..])
    else
      ScanProjection(work[1..])
  }

  predicate ExactReadableIndex(
    workStack: seq<Work>,
    readableStack: seq<Work>
  ) {
    readableStack == ScanProjection(workStack)
  }

  function ReadableAfterPop(
    head: Work,
    readableStack: seq<Work>
  ): seq<Work>
    requires head.ScanWork? ==> |readableStack| > 0
  {
    if head.ScanWork? then readableStack[1..] else readableStack
  }

  function UpdateReadable(
    head: Work,
    admitted: seq<Work>,
    readableStack: seq<Work>
  ): seq<Work>
    requires head.ScanWork? ==> |readableStack| > 0
  {
    ScanProjection(admitted) +
    ReadableAfterPop(head, readableStack)
  }

  lemma ScanProjectionConcatenates(
    left: seq<Work>,
    right: seq<Work>
  )
    ensures ScanProjection(left + right) ==
            ScanProjection(left) + ScanProjection(right)
    decreases |left|
  {
    if |left| > 0 {
      assert (left + right)[1..] == left[1..] + right;
      ScanProjectionConcatenates(left[1..], right);
    } else {
      assert left + right == right;
    }
  }

  lemma PopPreservesReadableTail(
    workStack: seq<Work>,
    readableStack: seq<Work>
  )
    requires |workStack| > 0
    requires ExactReadableIndex(workStack, readableStack)
    ensures ReadableAfterPop(workStack[0], readableStack) ==
            ScanProjection(workStack[1..])
  {
    if workStack[0].ScanWork? {
      assert |readableStack| > 0;
      assert readableStack[0] == workStack[0];
    }
  }

  lemma IncrementalUpdateIsExact(
    workStack: seq<Work>,
    readableStack: seq<Work>,
    admitted: seq<Work>
  )
    requires |workStack| > 0
    requires ExactReadableIndex(workStack, readableStack)
    ensures ExactReadableIndex(
              admitted + workStack[1..],
              UpdateReadable(
                workStack[0], admitted, readableStack
              )
            )
  {
    PopPreservesReadableTail(workStack, readableStack);
    ScanProjectionConcatenates(admitted, workStack[1..]);
  }

  lemma CanonicalScanIsReadableHead(
    workStack: seq<Work>,
    readableStack: seq<Work>
  )
    requires |workStack| > 0
    requires workStack[0].ScanWork?
    requires ExactReadableIndex(workStack, readableStack)
    ensures |readableStack| > 0
    ensures readableStack[0] == workStack[0]
  {
  }
}
