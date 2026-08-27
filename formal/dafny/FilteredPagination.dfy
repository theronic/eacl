module FilteredPagination {
  datatype Window = Window(
    examined: nat,
    sentinel: bool,
    bounded: bool,
    deadlineCut: bool
  )

  datatype WindowOutcome =
    | Deadline
    | Page(items: seq<int>, hasNext: bool, bounded: bool, resume: nat)

  function Filter(stream: seq<int>, accepted: set<int>): seq<int>
    decreases |stream|
  {
    if |stream| == 0 then
      []
    else
      (if stream[0] in accepted then [stream[0]] else []) +
      Filter(stream[1..], accepted)
  }

  lemma FilterConcatenation(
    left: seq<int>,
    right: seq<int>,
    accepted: set<int>
  )
    ensures Filter(left + right, accepted) ==
            Filter(left, accepted) + Filter(right, accepted)
    decreases |left|
  {
    if |left| > 0 {
      FilterConcatenation(left[1..], right, accepted);
      assert (left + right)[0] == left[0];
      assert (left + right)[1..] == left[1..] + right;
      calc {
         Filter(left + right, accepted);
      == (if left[0] in accepted then [left[0]] else []) +
         Filter(left[1..] + right, accepted);
      == (if left[0] in accepted then [left[0]] else []) +
         Filter(left[1..], accepted) + Filter(right, accepted);
      == Filter(left, accepted) + Filter(right, accepted);
      }
    } else {
      assert left == [];
      assert left + right == right;
    }
  }

  function Resume(window: Window): nat
    requires !window.sentinel || window.examined > 0
  {
    if window.sentinel then window.examined - 1 else window.examined
  }

  predicate ValidWindow(
    stream: seq<int>,
    accepted: set<int>,
    pageSize: nat,
    windowBudget: nat,
    window: Window
  )
  {
    pageSize > 0 &&
    windowBudget > 0 &&
    window.examined <= |stream| &&
    window.examined <= windowBudget &&
    (|stream| > 0 ==> window.examined > 0) &&
    |Filter(stream[..window.examined], accepted)| <= pageSize + 1 &&
    (window.sentinel <==>
     |Filter(stream[..window.examined], accepted)| == pageSize + 1) &&
    (window.sentinel ==>
       window.examined > 0 &&
       stream[window.examined - 1] in accepted) &&
    (window.bounded <==>
     !window.sentinel &&
     window.examined == windowBudget &&
     window.examined < |stream|) &&
    (!window.sentinel && !window.bounded ==>
       window.examined == |stream|)
  }

  function EmittedItems(
    stream: seq<int>,
    accepted: set<int>,
    pageSize: nat,
    window: Window
  ): seq<int>
    requires window.examined <= |stream|
    requires !window.sentinel ||
             pageSize <= |Filter(stream[..window.examined], accepted)|
  {
    if window.deadlineCut then
      []
    else
      var filtered := Filter(stream[..window.examined], accepted);
      if window.sentinel then filtered[..pageSize] else filtered
  }

  function Outcome(
    stream: seq<int>,
    accepted: set<int>,
    pageSize: nat,
    window: Window
  ): WindowOutcome
    requires window.examined <= |stream|
    requires !window.sentinel || window.examined > 0
    requires !window.sentinel ||
             pageSize <= |Filter(stream[..window.examined], accepted)|
  {
    if window.deadlineCut then
      Deadline
    else
      Page(
        EmittedItems(stream, accepted, pageSize, window),
        Resume(window) < |stream|,
        window.bounded,
        Resume(window)
      )
  }

  lemma DeadlineCutPublishesNoPage(
    stream: seq<int>,
    accepted: set<int>,
    pageSize: nat,
    windowBudget: nat,
    window: Window
  )
    requires ValidWindow(stream, accepted, pageSize, windowBudget, window)
    requires window.deadlineCut
    ensures Outcome(stream, accepted, pageSize, window).Deadline?
    ensures EmittedItems(stream, accepted, pageSize, window) == []
  {
  }

  lemma UnboundedHasNextIsExact(
    stream: seq<int>,
    accepted: set<int>,
    pageSize: nat,
    windowBudget: nat,
    window: Window
  )
    requires ValidWindow(stream, accepted, pageSize, windowBudget, window)
    requires !window.deadlineCut
    requires !window.bounded
    ensures Outcome(stream, accepted, pageSize, window).Page?
    ensures Outcome(stream, accepted, pageSize, window).hasNext <==>
            Outcome(stream, accepted, pageSize, window).resume < |stream|
  {
  }

  lemma OneWindowPreservesFilteredStream(
    stream: seq<int>,
    accepted: set<int>,
    pageSize: nat,
    windowBudget: nat,
    window: Window
  )
    requires ValidWindow(stream, accepted, pageSize, windowBudget, window)
    requires !window.deadlineCut
    ensures EmittedItems(stream, accepted, pageSize, window) +
            Filter(stream[Resume(window)..], accepted) ==
            Filter(stream, accepted)
  {
    if !window.sentinel {
      FilterConcatenation(
        stream[..window.examined],
        stream[window.examined..],
        accepted
      );
      assert stream ==
             stream[..window.examined] + stream[window.examined..];
    } else {
      var prefix := stream[..window.examined - 1];
      var sentinel := stream[window.examined - 1];
      var suffix := stream[window.examined..];
      assert stream[..window.examined] == prefix + [sentinel];
      assert stream == prefix + [sentinel] + suffix;
      assert stream[Resume(window)..] == [sentinel] + suffix;
      FilterConcatenation(prefix, [sentinel], accepted);
      FilterConcatenation(prefix + [sentinel], suffix, accepted);
      FilterConcatenation([sentinel], suffix, accepted);
      assert Filter([sentinel], accepted) == [sentinel];
      assert Filter(stream[..window.examined], accepted) ==
             Filter(prefix, accepted) + [sentinel];
      assert |Filter(prefix, accepted)| == pageSize;
      assert EmittedItems(stream, accepted, pageSize, window) ==
             Filter(prefix, accepted);
      calc {
         EmittedItems(stream, accepted, pageSize, window) +
         Filter(stream[Resume(window)..], accepted);
      == Filter(prefix, accepted) +
         Filter([sentinel] + suffix, accepted);
      == Filter(prefix, accepted) + [sentinel] +
         Filter(suffix, accepted);
      == Filter(prefix + [sentinel], accepted) +
         Filter(suffix, accepted);
      == Filter(stream, accepted);
      }
    }
  }

  predicate ValidWalk(
    stream: seq<int>,
    accepted: set<int>,
    pageSize: nat,
    windowBudget: nat,
    windows: seq<Window>
  )
    decreases |stream|
  {
    if |stream| == 0 then
      |windows| == 0
    else
      |windows| > 0 &&
      ValidWindow(stream, accepted, pageSize, windowBudget, windows[0]) &&
      !windows[0].deadlineCut &&
      Resume(windows[0]) > 0 &&
      ValidWalk(
        stream[Resume(windows[0])..],
        accepted,
        pageSize,
        windowBudget,
        windows[1..]
      )
  }

  function WalkItems(
    stream: seq<int>,
    accepted: set<int>,
    pageSize: nat,
    windowBudget: nat,
    windows: seq<Window>
  ): seq<int>
    requires ValidWalk(stream, accepted, pageSize, windowBudget, windows)
    decreases |stream|
  {
    if |stream| == 0 then
      []
    else
      EmittedItems(stream, accepted, pageSize, windows[0]) +
      WalkItems(
        stream[Resume(windows[0])..],
        accepted,
        pageSize,
        windowBudget,
        windows[1..]
      )
  }

  lemma ArbitraryWindowConcatenationIsExact(
    stream: seq<int>,
    accepted: set<int>,
    pageSize: nat,
    windowBudget: nat,
    windows: seq<Window>
  )
    requires ValidWalk(stream, accepted, pageSize, windowBudget, windows)
    ensures WalkItems(stream, accepted, pageSize, windowBudget, windows) ==
            Filter(stream, accepted)
    decreases |stream|
  {
    if |stream| > 0 {
      OneWindowPreservesFilteredStream(
        stream, accepted, pageSize, windowBudget, windows[0]
      );
      ArbitraryWindowConcatenationIsExact(
        stream[Resume(windows[0])..],
        accepted,
        pageSize,
        windowBudget,
        windows[1..]
      );
    }
  }
}
