// Direct instantiation of generic path reversal with EACL's typed grant
// identity. No numeric grant packing or anonymous graph-node encoding is
// assumed.
// Exploratory proof model; intentionally excluded from release artifacts.
include "EaclForwardGrounding.dfy"
include "BidirectionalReachability.dfy"

module EaclBidirectionalReachability {
  import F = EaclForwardGrounding
  import B = BidirectionalReachability

  ghost predicate ExactEdges(
    program: F.Program,
    edges: set<B.Step<F.Grant>>
  ) {
    forall body: F.Grant, head: F.Grant ::
      B.Step(body, head) in edges <==>
      F.Edge(body, head) in F.GroundEdges(program)
  }

  ghost predicate ExactBases(
    program: F.Program,
    principal: nat,
    bases: set<B.BaseGrant<F.Grant>>
  ) {
    forall grant: F.Grant ::
      B.BaseGrant(principal, grant) in bases <==>
      grant in F.BaseGrants(program, principal)
  }

  ghost predicate ForwardPath(program: F.Program, path: seq<F.Grant>)
    decreases |path|
  {
    |path| <= 1 ||
    (F.Edge(path[0], path[1]) in F.GroundEdges(program) &&
     ForwardPath(program, path[1..]))
  }

  ghost predicate ReversePath(program: F.Program, path: seq<F.Grant>)
    decreases |path|
  {
    |path| <= 1 ||
    (F.Edge(path[1], path[0]) in F.GroundEdges(program) &&
     ReversePath(program, path[1..]))
  }

  ghost predicate ForwardAuthorized(
    program: F.Program,
    principal: nat,
    root: F.Grant
  ) {
    exists base: F.Grant, path: seq<F.Grant> ::
      base in F.BaseGrants(program, principal) &&
      |path| > 0 &&
      path[0] == base &&
      path[|path| - 1] == root &&
      ForwardPath(program, path)
  }

  ghost predicate ReverseDiscovers(
    program: F.Program,
    root: F.Grant,
    principal: nat
  ) {
    exists base: F.Grant, path: seq<F.Grant> ::
      base in F.BaseGrants(program, principal) &&
      |path| > 0 &&
      path[0] == root &&
      path[|path| - 1] == base &&
      ReversePath(program, path)
  }

  lemma ForwardPathCorrespondence(
    program: F.Program,
    edges: set<B.Step<F.Grant>>,
    path: seq<F.Grant>
  )
    requires ExactEdges(program, edges)
    ensures ForwardPath(program, path) <==>
            B.PathEdges(edges, path)
    decreases |path|
  {
    if |path| > 1 {
      assert B.Step(path[0], path[1]) in edges <==>
             F.Edge(path[0], path[1]) in F.GroundEdges(program);
      ForwardPathCorrespondence(program, edges, path[1..]);
    }
  }

  lemma ReversePathCorrespondence(
    program: F.Program,
    edges: set<B.Step<F.Grant>>,
    path: seq<F.Grant>
  )
    requires ExactEdges(program, edges)
    ensures ReversePath(program, path) <==>
            B.PathEdges(B.Transpose(edges), path)
    decreases |path|
  {
    if |path| > 1 {
      assert B.Step(path[1], path[0]) in edges <==>
             F.Edge(path[1], path[0]) in F.GroundEdges(program);
      assert B.Step(path[0], path[1]) in B.Transpose(edges) <==>
             B.Step(path[1], path[0]) in edges;
      ReversePathCorrespondence(program, edges, path[1..]);
    }
  }

  lemma ForwardAuthorizationCorrespondence(
    program: F.Program,
    edges: set<B.Step<F.Grant>>,
    bases: set<B.BaseGrant<F.Grant>>,
    principal: nat,
    root: F.Grant
  )
    requires ExactEdges(program, edges)
    requires ExactBases(program, principal, bases)
    ensures ForwardAuthorized(program, principal, root) <==>
            B.ForwardAuthorized(edges, bases, principal, root)
  {
    if ForwardAuthorized(program, principal, root) {
      var base: F.Grant, path: seq<F.Grant> :|
        base in F.BaseGrants(program, principal) &&
        |path| > 0 &&
        path[0] == base &&
        path[|path| - 1] == root &&
        ForwardPath(program, path);
      assert B.BaseGrant(principal, base) in bases;
      ForwardPathCorrespondence(program, edges, path);
    } else if B.ForwardAuthorized(edges, bases, principal, root) {
      var base: F.Grant, path: seq<F.Grant> :|
        B.BaseGrant(principal, base) in bases &&
        |path| > 0 &&
        path[0] == base &&
        path[|path| - 1] == root &&
        B.PathEdges(edges, path);
      assert base in F.BaseGrants(program, principal);
      ForwardPathCorrespondence(program, edges, path);
    }
  }

  lemma ReverseDiscoveryCorrespondence(
    program: F.Program,
    edges: set<B.Step<F.Grant>>,
    bases: set<B.BaseGrant<F.Grant>>,
    principal: nat,
    root: F.Grant
  )
    requires ExactEdges(program, edges)
    requires ExactBases(program, principal, bases)
    ensures ReverseDiscovers(program, root, principal) <==>
            B.ReverseDiscovers(edges, bases, root, principal)
  {
    if ReverseDiscovers(program, root, principal) {
      var base: F.Grant, path: seq<F.Grant> :|
        base in F.BaseGrants(program, principal) &&
        |path| > 0 &&
        path[0] == root &&
        path[|path| - 1] == base &&
        ReversePath(program, path);
      assert B.BaseGrant(principal, base) in bases;
      ReversePathCorrespondence(program, edges, path);
    } else if B.ReverseDiscovers(edges, bases, root, principal) {
      var base: F.Grant, path: seq<F.Grant> :|
        B.BaseGrant(principal, base) in bases &&
        |path| > 0 &&
        path[0] == root &&
        path[|path| - 1] == base &&
        B.PathEdges(B.Transpose(edges), path);
      assert base in F.BaseGrants(program, principal);
      ReversePathCorrespondence(program, edges, path);
    }
  }

  lemma TypedReverseLookupEqualsForwardAuthorization(
    program: F.Program,
    edges: set<B.Step<F.Grant>>,
    bases: set<B.BaseGrant<F.Grant>>,
    principal: nat,
    root: F.Grant
  )
    requires ExactEdges(program, edges)
    requires ExactBases(program, principal, bases)
    ensures ReverseDiscovers(program, root, principal) <==>
            ForwardAuthorized(program, principal, root)
  {
    ForwardAuthorizationCorrespondence(
      program, edges, bases, principal, root
    );
    ReverseDiscoveryCorrespondence(
      program, edges, bases, principal, root
    );
    B.ReverseLookupEqualsForwardAuthorization(
      edges, bases, principal, root
    );
  }
}
