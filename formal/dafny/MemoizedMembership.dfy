// Memoized point membership over one immutable rule graph.
//
// Production mapping: eacl.engine.stable-route/check-many-eids, the union
// oracle through which a delegated operator plan decides its union-only
// operands (eacl.operator.plan/delegated-permissions). One request fixes the
// subject and the selected basis, so "does the subject hold the operand on
// this entity?" is a reachability question over one finite graph: states are
// (plan node, entity) pairs and arrow frames, successors follow sealed rules
// and scanned intermediates, and witnesses are states with a plainly present
// base tuple for the subject.
//
// The search visits states depth-first from the root, skips states already
// answered false and states whose node cannot reach any of the subject's
// holdings, stops at the first witness or answered-true state, and retains
// plain answers across searches: an exhausted search answers every state it
// admitted false; a search that finds a witness answers its root true. This
// leaf proves each search exact and the retained answers sound, for any
// sequence of searches over the same graph. Conditional or temporal evidence
// never enters this model: production routes such a point to the exact
// membership-probe check (MembershipProbeCheck.dfy) and retains nothing.
//
// Executable refinement: eacl.engine.memoized-membership-refinement-test
// builds this graph from a sealed plan's rules and relationship tuples (an
// arrow rule's expansion is a state of its own), checks the ConsistentNodes
// premise on every reachable state, and runs a transcription of Search beside
// the production search, requiring equal decisions, equal retained answers
// and the least possible-node set after every call.
module MemoizedMembership {
  datatype Graph = Graph(succ: seq<seq<nat>>, grants: set<nat>)

  ghost predicate ValidGraph(g: Graph) {
    (forall s | 0 <= s < |g.succ| :: forall t | t in g.succ[s] :: t < |g.succ|) &&
    (forall w | w in g.grants :: w < |g.succ|)
  }

  // A walk along successor edges.
  ghost predicate Walk(g: Graph, path: seq<nat>) {
    |path| > 0 &&
    (forall i | 0 <= i < |path| :: path[i] < |g.succ|) &&
    (forall i | 0 <= i < |path| - 1 :: path[i + 1] in g.succ[path[i]])
  }

  ghost predicate Reaches(g: Graph, from: nat, to: nat) {
    exists path :: Walk(g, path) && path[0] == from && path[|path| - 1] == to
  }

  // The denotation: some walk from s ends at a witness.
  ghost predicate Positive(g: Graph, s: nat) {
    exists path :: Walk(g, path) && path[0] == s && path[|path| - 1] in g.grants
  }

  // ---------------------------------------------------------------------
  // Walk composition
  // ---------------------------------------------------------------------

  lemma WalkSuffix(g: Graph, path: seq<nat>, k: nat)
    requires Walk(g, path) && k < |path|
    ensures Walk(g, path[k..])
  {
  }

  lemma WalkExtend(g: Graph, path: seq<nat>, t: nat)
    requires ValidGraph(g) && Walk(g, path)
    requires t in g.succ[path[|path| - 1]]
    ensures Walk(g, path + [t])
  {
  }

  lemma WalkConcat(g: Graph, left: seq<nat>, right: seq<nat>)
    requires Walk(g, left) && Walk(g, right)
    requires left[|left| - 1] == right[0]
    ensures Walk(g, left + right[1..])
    ensures (left + right[1..])[0] == left[0]
    ensures (left + right[1..])[|left + right[1..]| - 1] == right[|right| - 1]
  {
    var joined := left + right[1..];
    forall i | 0 <= i < |joined| - 1
      ensures joined[i + 1] in g.succ[joined[i]]
    {
      if i < |left| - 1 {
      } else {
        assert joined[i] == right[i - |left| + 1];
        assert joined[i + 1] == right[i - |left| + 2];
      }
    }
  }

  lemma ReachedPositiveIsPositive(g: Graph, root: nat, s: nat)
    requires Reaches(g, root, s) && Positive(g, s)
    ensures Positive(g, root)
  {
    var left :| Walk(g, left) && left[0] == root && left[|left| - 1] == s;
    var right :| Walk(g, right) && right[0] == s && right[|right| - 1] in g.grants;
    WalkConcat(g, left, right);
  }

  lemma ReachedWitnessIsPositive(g: Graph, root: nat, s: nat)
    requires Reaches(g, root, s) && s in g.grants
    ensures Positive(g, root)
  {
  }

  // ---------------------------------------------------------------------
  // Exhaustion: a closed admitted set is negative
  // ---------------------------------------------------------------------

  // The admitted states of an exhausted search: none is a witness, and each
  // successor of an admitted state was admitted too or is already known
  // negative.
  ghost predicate ClosedUnder(g: Graph, admitted: set<nat>, negative: set<nat>) {
    forall s | s in admitted ::
      s < |g.succ| && s !in g.grants &&
      forall t | t in g.succ[s] :: t in admitted || t in negative
  }

  ghost predicate AllNegative(g: Graph, states: set<nat>) {
    forall n | n in states :: !Positive(g, n)
  }

  lemma NoWitnessWalkFromClosedSet(
    g: Graph, admitted: set<nat>, negative: set<nat>, path: seq<nat>)
    requires ValidGraph(g) && ClosedUnder(g, admitted, negative)
    requires AllNegative(g, negative)
    requires Walk(g, path) && path[0] in admitted
    ensures path[|path| - 1] !in g.grants
    decreases |path|
  {
    if |path| > 1 {
      assert path[1] in g.succ[path[0]];
      WalkSuffix(g, path, 1);
      if path[1] in admitted {
        NoWitnessWalkFromClosedSet(g, admitted, negative, path[1..]);
      } else {
        assert path[1] in negative;
        if path[|path| - 1] in g.grants {
          assert Walk(g, path[1..]) && path[1..][0] == path[1] &&
                 path[1..][|path[1..]| - 1] in g.grants;
          assert Positive(g, path[1]);
        }
      }
    }
  }

  lemma ClosedSetIsNegative(g: Graph, admitted: set<nat>, negative: set<nat>)
    requires ValidGraph(g) && ClosedUnder(g, admitted, negative)
    requires AllNegative(g, negative)
    ensures AllNegative(g, admitted)
  {
    forall s | s in admitted ensures !Positive(g, s) {
      if Positive(g, s) {
        var path :| Walk(g, path) && path[0] == s && path[|path| - 1] in g.grants;
        NoWitnessWalkFromClosedSet(g, admitted, negative, path);
      }
    }
  }

  // ---------------------------------------------------------------------
  // Possibility: a node the subject's holdings cannot reach
  // ---------------------------------------------------------------------

  // Every state carries its plan node. A successor's node is one of its
  // source node's rule targets, and a witness's node is seeded: it has a base
  // rule over a relation slice in which the subject holds a tuple. Production
  // computes the least node set containing the seeds and closed under rule
  // predecessors; any such closed set suffices here.
  datatype Nodes = Nodes(node: seq<nat>, targets: map<nat, set<nat>>, seeds: set<nat>)

  ghost predicate ConsistentNodes(g: Graph, n: Nodes) {
    |n.node| == |g.succ| &&
    (forall s, t | 0 <= s < |g.succ| && t in g.succ[s] && t < |g.succ| ::
       n.node[s] in n.targets && n.node[t] in n.targets[n.node[s]]) &&
    (forall w | w in g.grants && w < |g.succ| :: n.node[w] in n.seeds)
  }

  ghost predicate ClosedPossible(n: Nodes, possible: set<nat>) {
    n.seeds <= possible &&
    forall source, target | source in n.targets && target in n.targets[source] &&
                            target in possible ::
      source in possible
  }

  lemma ImpossibleNodeHasNoWitnessWalk(
    g: Graph, n: Nodes, possible: set<nat>, path: seq<nat>)
    requires ValidGraph(g) && ConsistentNodes(g, n) && ClosedPossible(n, possible)
    requires Walk(g, path) && n.node[path[0]] !in possible
    ensures path[|path| - 1] !in g.grants
    decreases |path|
  {
    if |path| > 1 {
      assert path[1] in g.succ[path[0]];
      WalkSuffix(g, path, 1);
      ImpossibleNodeHasNoWitnessWalk(g, n, possible, path[1..]);
    }
  }

  lemma ImpossibleNodeIsNegative(g: Graph, n: Nodes, possible: set<nat>, s: nat)
    requires ValidGraph(g) && ConsistentNodes(g, n) && ClosedPossible(n, possible)
    requires s < |g.succ| && n.node[s] !in possible
    ensures !Positive(g, s)
  {
    if Positive(g, s) {
      var path :| Walk(g, path) && path[0] == s && path[|path| - 1] in g.grants;
      ImpossibleNodeHasNoWitnessWalk(g, n, possible, path);
    }
  }

  // ---------------------------------------------------------------------
  // One memoized search
  // ---------------------------------------------------------------------

  ghost predicate SoundMemo(g: Graph, positive: set<nat>, negative: set<nat>) {
    (forall p | p in positive :: p < |g.succ| && Positive(g, p)) &&
    AllNegative(g, negative)
  }

  predicate Skippable(g: Graph, n: Nodes, possible: set<nat>,
                      negative: set<nat>, t: nat) {
    t in negative || (t < |n.node| && n.node[t] !in possible)
  }

  method Search(g: Graph, n: Nodes, possible: set<nat>,
                positive: set<nat>, negative: set<nat>, root: nat)
    returns (found: bool, positive': set<nat>, negative': set<nat>)
    requires ValidGraph(g) && ConsistentNodes(g, n) && ClosedPossible(n, possible)
    requires root < |g.succ|
    requires SoundMemo(g, positive, negative)
    ensures found == Positive(g, root)
    ensures SoundMemo(g, positive', negative')
    ensures positive <= positive' && negative <= negative'
  {
    var stack: seq<nat> := [root];
    var admitted: set<nat> := {};
    assert Walk(g, [root]);
    while |stack| > 0
      invariant forall s | s in stack :: s < |g.succ| && Reaches(g, root, s)
      invariant forall s | s in admitted ::
                  s < |g.succ| && Reaches(g, root, s) && s !in g.grants &&
                  forall t | t in g.succ[s] ::
                    t in admitted || t in stack || Skippable(g, n, possible, negative, t)
      invariant root in admitted || root in stack ||
                Skippable(g, n, possible, negative, root)
      decreases (set i | 0 <= i < |g.succ| && i !in admitted), |stack|
    {
      var s := stack[|stack| - 1];
      stack := stack[..|stack| - 1];
      assert Reaches(g, root, s);
      if s in positive {
        ReachedPositiveIsPositive(g, root, s);
        return true, positive + {root}, negative;
      } else if s in admitted || Skippable(g, n, possible, negative, s) {
        // Answered false, already admitted, or unreachable from holdings.
      } else if s in g.grants {
        ReachedWitnessIsPositive(g, root, s);
        return true, positive + {root}, negative;
      } else {
        ghost var path :| Walk(g, path) && path[0] == root && path[|path| - 1] == s;
        forall t | t in g.succ[s] ensures Reaches(g, root, t) {
          WalkExtend(g, path, t);
          assert (path + [t])[|path + [t]| - 1] == t;
        }
        ghost var unadmitted := set i | 0 <= i < |g.succ| && i !in admitted;
        admitted := admitted + {s};
        stack := stack + g.succ[s];
        assert s in unadmitted;
        assert (set i | 0 <= i < |g.succ| && i !in admitted) == unadmitted - {s};
      }
    }
    // Exhausted: every admitted state's successors are admitted or
    // skippable, and skippable states are negative.
    var impossible := set t | 0 <= t < |g.succ| && n.node[t] !in possible;
    forall t | t in impossible ensures !Positive(g, t) {
      ImpossibleNodeIsNegative(g, n, possible, t);
    }
    var known := negative + impossible;
    assert ClosedUnder(g, admitted, known);
    ClosedSetIsNegative(g, admitted, known);
    if root !in admitted && root in negative {
    } else if root !in admitted {
      ImpossibleNodeIsNegative(g, n, possible, root);
    }
    return false, positive, negative + admitted;
  }

  // Any number of searches, in any order, over one graph stay exact.
  method SearchAll(g: Graph, n: Nodes, possible: set<nat>, roots: seq<nat>)
    returns (answers: seq<bool>)
    requires ValidGraph(g) && ConsistentNodes(g, n) && ClosedPossible(n, possible)
    requires forall r | r in roots :: r < |g.succ|
    ensures |answers| == |roots|
    ensures forall i | 0 <= i < |roots| :: answers[i] == Positive(g, roots[i])
  {
    var positive: set<nat> := {};
    var negative: set<nat> := {};
    answers := [];
    var i := 0;
    while i < |roots|
      invariant 0 <= i <= |roots| && |answers| == i
      invariant SoundMemo(g, positive, negative)
      invariant forall j | 0 <= j < i :: answers[j] == Positive(g, roots[j])
    {
      var found;
      assert roots[i] in roots;
      found, positive, negative := Search(g, n, possible, positive, negative, roots[i]);
      answers := answers + [found];
      i := i + 1;
    }
  }
}
