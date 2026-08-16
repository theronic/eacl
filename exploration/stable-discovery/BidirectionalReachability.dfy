// Exploratory denotational bridge between forward authorization and reverse
// subject discovery on the same finite grounded grant graph.
module BidirectionalReachability {
  datatype Step<T> = Step(from: T, to: T)
  datatype BaseGrant<T> = BaseGrant(principal: nat, grant: T)

  function Transpose<T(==)>(edges: set<Step<T>>): set<Step<T>> {
    set edge: Step<T> | edge in edges :: Step(edge.to, edge.from)
  }

  lemma TransposeIsInvolution<T>(edges: set<Step<T>>)
    ensures Transpose(Transpose(edges)) == edges
  {
    forall edge: Step<T>
      ensures edge in Transpose(Transpose(edges)) <==> edge in edges
    {
    }
  }

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

  lemma ReverseStartsAtLast<T>(values: seq<T>)
    requires |values| > 0
    ensures Reverse(values)[0] == values[|values| - 1]
    decreases |values|
  {
    if |values| > 1 {
      ReverseStartsAtLast(values[1..]);
    }
  }

  lemma ReverseEndsAtFirst<T>(values: seq<T>)
    requires |values| > 0
    ensures Reverse(values)[|Reverse(values)| - 1] == values[0]
  {
    ReverseLength(values);
  }

  predicate PathEdges<T(==)>(edges: set<Step<T>>, path: seq<T>)
    decreases |path|
  {
    |path| <= 1 ||
    (Step(path[0], path[1]) in edges &&
     PathEdges(edges, path[1..]))
  }

  lemma AppendPreservesPathEdges<T>(
    edges: set<Step<T>>,
    path: seq<T>,
    successor: T
  )
    requires |path| > 0
    requires PathEdges(edges, path)
    requires Step(path[|path| - 1], successor) in edges
    ensures PathEdges(edges, path + [successor])
    decreases |path|
  {
    if |path| > 1 {
      AppendPreservesPathEdges(edges, path[1..], successor);
      assert (path + [successor])[1..] == path[1..] + [successor];
    }
  }

  lemma ReversingPathUsesTransposedEdges<T>(
    edges: set<Step<T>>,
    path: seq<T>
  )
    requires |path| > 0
    requires PathEdges(edges, path)
    ensures PathEdges(Transpose(edges), Reverse(path))
    decreases |path|
  {
    if |path| > 1 {
      var tail := path[1..];
      ReversingPathUsesTransposedEdges(edges, tail);
      ReverseLength(tail);
      ReverseStartsAtLast(tail);
      ReverseEndsAtFirst(tail);
      assert Step(path[1], path[0]) in Transpose(edges);
      AppendPreservesPathEdges(
        Transpose(edges), Reverse(tail), path[0]
      );
      assert Reverse(path) == Reverse(tail) + [path[0]];
    }
  }

  ghost predicate ForwardAuthorized<T(!new)>(
    edges: set<Step<T>>,
    bases: set<BaseGrant<T>>,
    principal: nat,
    root: T
  ) {
    exists base: T, path: seq<T> ::
      BaseGrant(principal, base) in bases &&
      |path| > 0 &&
      path[0] == base &&
      path[|path| - 1] == root &&
      PathEdges(edges, path)
  }

  ghost predicate ReverseDiscovers<T(!new)>(
    edges: set<Step<T>>,
    bases: set<BaseGrant<T>>,
    root: T,
    principal: nat
  ) {
    exists base: T, path: seq<T> ::
      BaseGrant(principal, base) in bases &&
      |path| > 0 &&
      path[0] == root &&
      path[|path| - 1] == base &&
      PathEdges(Transpose(edges), path)
  }

  lemma ReverseLookupEqualsForwardAuthorization<T(!new)>(
    edges: set<Step<T>>,
    bases: set<BaseGrant<T>>,
    principal: nat,
    root: T
  )
    ensures ReverseDiscovers(edges, bases, root, principal) <==>
            ForwardAuthorized(edges, bases, principal, root)
  {
    if ForwardAuthorized(edges, bases, principal, root) {
      var base: T, path: seq<T> :|
        BaseGrant(principal, base) in bases &&
        |path| > 0 &&
        path[0] == base &&
        path[|path| - 1] == root &&
        PathEdges(edges, path);
      ReversingPathUsesTransposedEdges(edges, path);
      ReverseLength(path);
      ReverseStartsAtLast(path);
      ReverseEndsAtFirst(path);
    } else if ReverseDiscovers(edges, bases, root, principal) {
      var base: T, path: seq<T> :|
        BaseGrant(principal, base) in bases &&
        |path| > 0 &&
        path[0] == root &&
        path[|path| - 1] == base &&
        PathEdges(Transpose(edges), path);
      ReversingPathUsesTransposedEdges(Transpose(edges), path);
      TransposeIsInvolution(edges);
      ReverseLength(path);
      ReverseStartsAtLast(path);
      ReverseEndsAtFirst(path);
    }
  }
}
