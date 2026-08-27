// Signed dependency validity for recursive permission expressions.
//
// A certificate is Tarjan-equivalent when its components are exactly the
// mutual-reachability equivalence classes.  The executable validator does not
// trust component order: it validates that certificate predicate separately,
// then reports the first canonical negative edge whose endpoints share a
// component.
module SignedDependencyStratification {
  datatype EdgeSign = Positive | Negative

  datatype DependencyEdge = DependencyEdge(
    source: nat,
    target: nat,
    sign: EdgeSign
  )

  predicate Unique(values: seq<nat>) {
    forall left, right | 0 <= left < right < |values| ::
      values[left] != values[right]
  }

  function UniqueExecutable(values: seq<nat>): bool
    decreases |values|
  {
    |values| == 0 ||
    (values[0] !in values[1..] && UniqueExecutable(values[1..]))
  }

  lemma UniqueExecutableIffUnique(values: seq<nat>)
    ensures UniqueExecutable(values) <==> Unique(values)
    decreases |values|
  {
    if |values| != 0 {
      UniqueExecutableIffUnique(values[1..]);
    }
  }

  function RemoveFirst(values: seq<nat>, removed: nat): seq<nat>
    decreases |values|
  {
    if |values| == 0 then
      []
    else if values[0] == removed then
      values[1..]
    else
      [values[0]] + RemoveFirst(values[1..], removed)
  }

  lemma RemoveFirstMemberDecreasesLength(
    values: seq<nat>,
    removed: nat
  )
    requires removed in values
    ensures |RemoveFirst(values, removed)| + 1 == |values|
    decreases |values|
  {
    if values[0] != removed {
      RemoveFirstMemberDecreasesLength(values[1..], removed);
    }
  }

  lemma RemoveFirstPreservesOtherMembership(
    values: seq<nat>,
    removed: nat,
    value: nat
  )
    requires value != removed
    ensures value in RemoveFirst(values, removed) <==> value in values
    decreases |values|
  {
    if |values| != 0 && values[0] != removed {
      RemoveFirstPreservesOtherMembership(values[1..], removed, value);
    }
  }

  lemma RemoveFirstPreservesUniqueness(
    values: seq<nat>,
    removed: nat
  )
    requires UniqueExecutable(values)
    ensures UniqueExecutable(RemoveFirst(values, removed))
    decreases |values|
  {
    if |values| != 0 && values[0] != removed {
      RemoveFirstPreservesUniqueness(values[1..], removed);
      RemoveFirstPreservesOtherMembership(
        values[1..],
        removed,
        values[0]
      );
    }
  }

  lemma UniqueContainedSequenceLengthBound(
    contained: seq<nat>,
    container: seq<nat>
  )
    requires UniqueExecutable(contained)
    requires UniqueExecutable(container)
    requires forall value <- contained :: value in container
    ensures |contained| <= |container|
    decreases |contained|
  {
    if |contained| != 0 {
      var value := contained[0];
      var reducedContainer := RemoveFirst(container, value);
      RemoveFirstMemberDecreasesLength(container, value);
      RemoveFirstPreservesUniqueness(container, value);
      forall remaining <- contained[1..]
        ensures remaining in reducedContainer
      {
        assert remaining != value;
        RemoveFirstPreservesOtherMembership(
          container,
          value,
          remaining
        );
      }
      UniqueContainedSequenceLengthBound(
        contained[1..],
        reducedContainer
      );
    }
  }

  function SignRank(sign: EdgeSign): nat {
    if sign.Positive? then 0 else 1
  }

  function EdgeLess(left: DependencyEdge, right: DependencyEdge): bool {
    left.source < right.source ||
    (left.source == right.source &&
     (left.target < right.target ||
      (left.target == right.target &&
       SignRank(left.sign) < SignRank(right.sign))))
  }

  function CanonicalEdgeSequence(edges: seq<DependencyEdge>): bool
    decreases |edges|
  {
    |edges| == 0 ||
    ((forall edge <- edges[1..] :: EdgeLess(edges[0], edge)) &&
     CanonicalEdgeSequence(edges[1..]))
  }

  function EdgeTargets(
    edges: seq<DependencyEdge>,
    reachable: set<nat>
  ): set<nat>
    decreases |edges|
  {
    if |edges| == 0 then
      {}
    else
      (if edges[0].source in reachable then {edges[0].target} else {}) +
      EdgeTargets(edges[1..], reachable)
  }

  function ReachableClosure(
    edges: seq<DependencyEdge>,
    reachable: set<nat>,
    fuel: nat
  ): set<nat>
    decreases fuel
  {
    if fuel == 0 then
      reachable
    else
      var previous := ReachableClosure(edges, reachable, fuel - 1);
      previous + EdgeTargets(edges, previous)
  }

  function ExecutableReachable(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    source: nat,
    target: nat
  ): bool {
    source in vertices &&
    target in vertices &&
    target in ReachableClosure(edges, {source}, |vertices|)
  }

  function CountContaining(
    components: seq<seq<nat>>,
    vertex: nat
  ): nat
    decreases |components|
  {
    if |components| == 0 then
      0
    else
      (if vertex in components[0] then 1 else 0) +
      CountContaining(components[1..], vertex)
  }

  function EdgesStayInsideVertices(
    edges: seq<DependencyEdge>,
    vertices: seq<nat>
  ): bool
    decreases |edges|
  {
    |edges| == 0 ||
    (edges[0].source in vertices &&
     edges[0].target in vertices &&
     EdgesStayInsideVertices(edges[1..], vertices))
  }

  lemma EdgesStayInsideVerticesIff(
    edges: seq<DependencyEdge>,
    vertices: seq<nat>
  )
    ensures EdgesStayInsideVertices(edges, vertices) <==>
            forall edge <- edges ::
              edge.source in vertices && edge.target in vertices
    decreases |edges|
  {
    if |edges| != 0 {
      EdgesStayInsideVerticesIff(edges[1..], vertices);
    }
  }

  function ComponentMembershipMatchesReachability(
    vertices: seq<nat>,
    allVertices: seq<nat>,
    edges: seq<DependencyEdge>,
    component: seq<nat>,
    anchor: nat
  ): bool
    decreases |vertices|
  {
    |vertices| == 0 ||
    ((vertices[0] in component <==>
      (ExecutableReachable(allVertices, edges, anchor, vertices[0]) &&
       ExecutableReachable(allVertices, edges, vertices[0], anchor))) &&
     ComponentMembershipMatchesReachability(
       vertices[1..],
       allVertices,
       edges,
       component,
       anchor
     ))
  }

  function ComponentsMatchReachability(
    components: seq<seq<nat>>,
    vertices: seq<nat>,
    edges: seq<DependencyEdge>
  ): bool
    decreases |components|
  {
    |components| == 0 ||
    (|components[0]| != 0 &&
     UniqueExecutable(components[0]) &&
     (forall member <- components[0] :: member in vertices) &&
     ComponentMembershipMatchesReachability(
       vertices,
       vertices,
       edges,
       components[0],
       components[0][0]
     ) &&
     ComponentsMatchReachability(components[1..], vertices, edges))
  }

  function VerticesOccurExactlyOnce(
    vertices: seq<nat>,
    components: seq<seq<nat>>
  ): bool
    decreases |vertices|
  {
    |vertices| == 0 ||
    (CountContaining(components, vertices[0]) == 1 &&
     VerticesOccurExactlyOnce(vertices[1..], components))
  }

  function ExecutableCertificateValid(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>
  ): bool {
    UniqueExecutable(vertices) &&
    EdgesStayInsideVertices(edges, vertices) &&
    VerticesOccurExactlyOnce(vertices, components) &&
    ComponentsMatchReachability(components, vertices, edges)
  }

  predicate EdgeExists(
    edges: seq<DependencyEdge>,
    source: nat,
    target: nat
  ) {
    exists edge <- edges :: edge.source == source && edge.target == target
  }

  predicate PathUsesEdges(
    edges: seq<DependencyEdge>,
    path: seq<nat>
  )
    decreases |path|
  {
    |path| != 0 &&
    (|path| == 1 ||
     (EdgeExists(edges, path[0], path[1]) &&
      PathUsesEdges(edges, path[1..])))
  }

  ghost predicate Reachable(
    edges: seq<DependencyEdge>,
    source: nat,
    target: nat
  ) {
    exists path: seq<nat> ::
      PathUsesEdges(edges, path) &&
      path[0] == source &&
      path[|path| - 1] == target
  }

  ghost predicate ReachableWithin(
    edges: seq<DependencyEdge>,
    starts: set<nat>,
    target: nat,
    maximumEdges: nat
  ) {
    exists path: seq<nat> ::
      PathUsesEdges(edges, path) &&
      path[0] in starts &&
      path[|path| - 1] == target &&
      |path| <= maximumEdges + 1
  }

  lemma EdgeTargetsContainsExactlyOneStepTargets(
    edges: seq<DependencyEdge>,
    reachable: set<nat>,
    target: nat
  )
    ensures target in EdgeTargets(edges, reachable) <==>
            exists edge <- edges ::
              edge.source in reachable && edge.target == target
    decreases |edges|
  {
    if |edges| != 0 {
      EdgeTargetsContainsExactlyOneStepTargets(
        edges[1..],
        reachable,
        target
      );
    }
  }

  lemma AppendPathEdgePreservesPath(
    edges: seq<DependencyEdge>,
    path: seq<nat>,
    target: nat
  )
    requires PathUsesEdges(edges, path)
    requires EdgeExists(edges, path[|path| - 1], target)
    ensures PathUsesEdges(edges, path + [target])
    decreases |path|
  {
    if |path| > 1 {
      AppendPathEdgePreservesPath(edges, path[1..], target);
      assert path + [target] == [path[0]] + (path[1..] + [target]);
      assert (path + [target])[1..] == path[1..] + [target];
    }
  }

  lemma JoinPathsPreservesPath(
    edges: seq<DependencyEdge>,
    left: seq<nat>,
    right: seq<nat>
  )
    requires PathUsesEdges(edges, left)
    requires PathUsesEdges(edges, right)
    requires left[|left| - 1] == right[0]
    ensures PathUsesEdges(edges, left + right[1..])
    ensures (left + right[1..])[0] == left[0]
    ensures (left + right[1..])[|left + right[1..]| - 1] ==
            right[|right| - 1]
    decreases |left|
  {
    if |left| == 1 {
      assert left + right[1..] == right;
    } else {
      JoinPathsPreservesPath(edges, left[1..], right);
      assert left + right[1..] ==
             [left[0]] + (left[1..] + right[1..]);
      assert (left + right[1..])[1..] == left[1..] + right[1..];
    }
  }

  lemma ReachabilityIsTransitive(
    edges: seq<DependencyEdge>,
    source: nat,
    middle: nat,
    target: nat
  )
    requires Reachable(edges, source, middle)
    requires Reachable(edges, middle, target)
    ensures Reachable(edges, source, target)
  {
    var left: seq<nat> :|
      PathUsesEdges(edges, left) &&
      left[0] == source &&
      left[|left| - 1] == middle;
    var right: seq<nat> :|
      PathUsesEdges(edges, right) &&
      right[0] == middle &&
      right[|right| - 1] == target;
    JoinPathsPreservesPath(edges, left, right);
    var joined := left + right[1..];
    assert Reachable(edges, source, target);
  }

  lemma DependencyEdgeGivesReachability(
    edges: seq<DependencyEdge>,
    edge: DependencyEdge
  )
    requires edge in edges
    ensures Reachable(edges, edge.source, edge.target)
  {
    assert EdgeExists(edges, edge.source, edge.target);
    assert PathUsesEdges(edges, [edge.source, edge.target]);
    assert Reachable(edges, edge.source, edge.target);
  }

  lemma NonemptyPrefixPreservesPath(
    edges: seq<DependencyEdge>,
    path: seq<nat>,
    end: nat
  )
    requires PathUsesEdges(edges, path)
    requires 1 <= end <= |path|
    ensures PathUsesEdges(edges, path[..end])
    decreases |path|
  {
    if end > 1 {
      NonemptyPrefixPreservesPath(edges, path[1..], end - 1);
      assert path[..end] == [path[0]] + path[1..][..end - 1];
      assert path[..end][1..] == path[1..][..end - 1];
    }
  }

  lemma NonemptySuffixPreservesPath(
    edges: seq<DependencyEdge>,
    path: seq<nat>,
    start: nat
  )
    requires PathUsesEdges(edges, path)
    requires start < |path|
    ensures PathUsesEdges(edges, path[start..])
    decreases start
  {
    if start != 0 {
      NonemptySuffixPreservesPath(edges, path[1..], start - 1);
    }
  }

  lemma LastPathStepExists(
    edges: seq<DependencyEdge>,
    path: seq<nat>
  )
    requires PathUsesEdges(edges, path)
    requires |path| > 1
    ensures EdgeExists(edges, path[|path| - 2], path[|path| - 1])
    decreases |path|
  {
    if |path| > 2 {
      LastPathStepExists(edges, path[1..]);
    }
  }

  lemma ReachableClosureIsExactForBoundedPaths(
    edges: seq<DependencyEdge>,
    starts: set<nat>,
    target: nat,
    fuel: nat
  )
    ensures target in ReachableClosure(edges, starts, fuel) <==>
            ReachableWithin(edges, starts, target, fuel)
    decreases fuel
  {
    if fuel == 0 {
      if target in ReachableClosure(edges, starts, fuel) {
        assert PathUsesEdges(edges, [target]);
        assert ReachableWithin(edges, starts, target, fuel);
      }
      if ReachableWithin(edges, starts, target, fuel) {
        var path: seq<nat> :|
          PathUsesEdges(edges, path) &&
          path[0] in starts &&
          path[|path| - 1] == target &&
          |path| <= fuel + 1;
        assert |path| == 1;
      }
    } else {
      var previous := ReachableClosure(edges, starts, fuel - 1);
      ReachableClosureIsExactForBoundedPaths(
        edges,
        starts,
        target,
        fuel - 1
      );
      EdgeTargetsContainsExactlyOneStepTargets(edges, previous, target);

      if target in ReachableClosure(edges, starts, fuel) {
        if target !in previous {
          var edge :|
            edge in edges &&
            edge.source in previous &&
            edge.target == target;
          ReachableClosureIsExactForBoundedPaths(
            edges,
            starts,
            edge.source,
            fuel - 1
          );
          var path: seq<nat> :|
            PathUsesEdges(edges, path) &&
            path[0] in starts &&
            path[|path| - 1] == edge.source &&
            |path| <= fuel;
          AppendPathEdgePreservesPath(edges, path, target);
          assert ReachableWithin(edges, starts, target, fuel);
        }
      } else if ReachableWithin(edges, starts, target, fuel) {
        var path: seq<nat> :|
          PathUsesEdges(edges, path) &&
          path[0] in starts &&
          path[|path| - 1] == target &&
          |path| <= fuel + 1;
        if |path| <= fuel {
          assert ReachableWithin(edges, starts, target, fuel - 1);
        } else {
          assert |path| == fuel + 1;
          NonemptyPrefixPreservesPath(edges, path, |path| - 1);
          LastPathStepExists(edges, path);
          var predecessor := path[|path| - 2];
          ReachableClosureIsExactForBoundedPaths(
            edges,
            starts,
            predecessor,
            fuel - 1
          );
          assert EdgeExists(edges, predecessor, target);
          EdgeTargetsContainsExactlyOneStepTargets(
            edges,
            previous,
            target
          );
        }
      }
    }
  }

  lemma RemoveRepeatedPathSegmentPreservesPath(
    edges: seq<DependencyEdge>,
    path: seq<nat>,
    left: nat,
    right: nat
  )
    requires PathUsesEdges(edges, path)
    requires 0 <= left < right < |path|
    requires path[left] == path[right]
    ensures PathUsesEdges(edges, path[..left] + path[right..])
    ensures (path[..left] + path[right..])[0] == path[0]
    ensures (path[..left] + path[right..])[
            |path[..left] + path[right..]| - 1
            ] == path[|path| - 1]
    ensures |path[..left] + path[right..]| < |path|
    decreases left
  {
    if left == 0 {
      assert path[..left] + path[right..] == path[right..];
      NonemptySuffixPreservesPath(edges, path, right);
    } else {
      RemoveRepeatedPathSegmentPreservesPath(
        edges,
        path[1..],
        left - 1,
        right - 1
      );
      var reducedTail := path[1..][..left - 1] +
      path[1..][right - 1..];
      assert path[..left] + path[right..] ==
             [path[0]] + reducedTail;
      assert (path[..left] + path[right..])[1..] == reducedTail;
    }
  }

  lemma ShortenPathToVertexBound(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    path: seq<nat>
  ) returns (shortPath: seq<nat>)
    requires UniqueExecutable(vertices)
    requires PathUsesEdges(edges, path)
    requires forall vertex <- path :: vertex in vertices
    ensures PathUsesEdges(edges, shortPath)
    ensures shortPath[0] == path[0]
    ensures shortPath[|shortPath| - 1] == path[|path| - 1]
    ensures |shortPath| <= |vertices|
    decreases |path|
  {
    if |path| <= |vertices| {
      shortPath := path;
    } else {
      if UniqueExecutable(path) {
        UniqueContainedSequenceLengthBound(path, vertices);
        assert false;
      }
      UniqueExecutableIffUnique(path);
      assert !Unique(path);
      assert exists left, right |
          0 <= left < right < |path| :: path[left] == path[right];
      var left, right :|
        0 <= left < right < |path| && path[left] == path[right];
      var reduced := path[..left] + path[right..];
      RemoveRepeatedPathSegmentPreservesPath(
        edges,
        path,
        left,
        right
      );
      assert forall vertex <- reduced :: vertex in vertices;
      shortPath := ShortenPathToVertexBound(vertices, edges, reduced);
    }
  }

  lemma ExecutableReachabilityEqualsFinitePathReachability(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    source: nat,
    target: nat
  )
    requires UniqueExecutable(vertices)
    requires EdgesStayInsideVertices(edges, vertices)
    requires source in vertices
    requires target in vertices
    ensures ExecutableReachable(vertices, edges, source, target) <==>
            Reachable(edges, source, target)
  {
    EdgesStayInsideVerticesIff(edges, vertices);
    ReachableClosureIsExactForBoundedPaths(
      edges,
      {source},
      target,
      |vertices|
    );
    if Reachable(edges, source, target) {
      var path: seq<nat> :|
        PathUsesEdges(edges, path) &&
        path[0] == source &&
        path[|path| - 1] == target;
      PathVerticesBelongToGraph(vertices, edges, path);
      var shortPath := ShortenPathToVertexBound(vertices, edges, path);
      assert ReachableWithin(edges, {source}, target, |vertices|);
    } else if ExecutableReachable(vertices, edges, source, target) {
      assert ReachableWithin(edges, {source}, target, |vertices|);
      var path: seq<nat> :|
        PathUsesEdges(edges, path) &&
        path[0] in {source} &&
        path[|path| - 1] == target &&
        |path| <= |vertices| + 1;
      assert Reachable(edges, source, target);
    }
  }

  lemma ExecutableMembershipMatchesFinitePaths(
    scanVertices: seq<nat>,
    allVertices: seq<nat>,
    edges: seq<DependencyEdge>,
    component: seq<nat>,
    anchor: nat
  )
    requires UniqueExecutable(allVertices)
    requires EdgesStayInsideVertices(edges, allVertices)
    requires anchor in allVertices
    requires forall vertex <- scanVertices :: vertex in allVertices
    ensures ComponentMembershipMatchesReachability(
              scanVertices,
              allVertices,
              edges,
              component,
              anchor
            ) <==>
            forall vertex <- scanVertices ::
              vertex in component <==>
                        (Reachable(edges, anchor, vertex) &&
                         Reachable(edges, vertex, anchor))
    decreases |scanVertices|
  {
    if |scanVertices| != 0 {
      var vertex := scanVertices[0];
      ExecutableReachabilityEqualsFinitePathReachability(
        allVertices,
        edges,
        anchor,
        vertex
      );
      ExecutableReachabilityEqualsFinitePathReachability(
        allVertices,
        edges,
        vertex,
        anchor
      );
      ExecutableMembershipMatchesFinitePaths(
        scanVertices[1..],
        allVertices,
        edges,
        component,
        anchor
      );
    }
  }

  lemma ExecutableComponentRefinesFinitePathComponent(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    component: seq<nat>
  )
    requires UniqueExecutable(vertices)
    requires EdgesStayInsideVertices(edges, vertices)
    requires |component| != 0
    requires UniqueExecutable(component)
    requires forall member <- component :: member in vertices
    requires ComponentMembershipMatchesReachability(
               vertices,
               vertices,
               edges,
               component,
               component[0]
             )
    ensures ComponentMatchesReachability(vertices, edges, component)
  {
    UniqueExecutableIffUnique(component);
    ExecutableMembershipMatchesFinitePaths(
      vertices,
      vertices,
      edges,
      component,
      component[0]
    );
  }

  lemma ExecutableComponentsRefineFinitePathComponents(
    components: seq<seq<nat>>,
    vertices: seq<nat>,
    edges: seq<DependencyEdge>
  )
    requires UniqueExecutable(vertices)
    requires EdgesStayInsideVertices(edges, vertices)
    requires ComponentsMatchReachability(components, vertices, edges)
    ensures forall component <- components ::
              ComponentMatchesReachability(vertices, edges, component)
    decreases |components|
  {
    if |components| != 0 {
      ExecutableComponentRefinesFinitePathComponent(
        vertices,
        edges,
        components[0]
      );
      ExecutableComponentsRefineFinitePathComponents(
        components[1..],
        vertices,
        edges
      );
    }
  }

  lemma VerticesOccurExactlyOnceIffPointwiseCount(
    vertices: seq<nat>,
    components: seq<seq<nat>>
  )
    ensures VerticesOccurExactlyOnce(vertices, components) <==>
            forall vertex <- vertices ::
              CountContaining(components, vertex) == 1
    decreases |vertices|
  {
    if |vertices| != 0 {
      VerticesOccurExactlyOnceIffPointwiseCount(
        vertices[1..],
        components
      );
      assert VerticesOccurExactlyOnce(vertices, components) <==>
             CountContaining(components, vertices[0]) == 1 &&
             VerticesOccurExactlyOnce(vertices[1..], components);
      assert (forall vertex <- vertices ::
                CountContaining(components, vertex) == 1) <==>
             CountContaining(components, vertices[0]) == 1 &&
             (forall vertex <- vertices[1..] ::
                CountContaining(components, vertex) == 1);
    }
  }

  lemma ExecutableCertificateRefinesTarjanEquivalentCertificate(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>
  )
    requires ExecutableCertificateValid(vertices, edges, components)
    ensures TarjanEquivalentCertificate(vertices, edges, components)
  {
    UniqueExecutableIffUnique(vertices);
    EdgesStayInsideVerticesIff(edges, vertices);
    ExecutableComponentsRefineFinitePathComponents(
      components,
      vertices,
      edges
    );
    VerticesOccurExactlyOnceIffPointwiseCount(vertices, components);
  }

  ghost predicate ComponentMatchesReachability(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    component: seq<nat>
  ) {
    0 < |component| &&
    Unique(component) &&
    (forall vertex <- component :: vertex in vertices) &&
    (forall vertex <- vertices ::
       vertex in component <==>
                 (Reachable(edges, component[0], vertex) &&
                  Reachable(edges, vertex, component[0])))
  }

  predicate VertexOccursExactlyOnce(
    components: seq<seq<nat>>,
    vertex: nat
  ) {
    (exists index | 0 <= index < |components| ::
       vertex in components[index]) &&
    (forall left, right |
       0 <= left < |components| &&
       0 <= right < |components| &&
       vertex in components[left] &&
       vertex in components[right] :: left == right)
  }

  // This is the mathematical contract required from a Tarjan implementation.
  // Component and member order are deliberately irrelevant.
  ghost predicate TarjanEquivalentCertificate(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>
  ) {
    Unique(vertices) &&
    (forall edge <- edges ::
       edge.source in vertices && edge.target in vertices) &&
    (forall component <- components ::
       ComponentMatchesReachability(vertices, edges, component)) &&
    (forall vertex <- vertices ::
       CountContaining(components, vertex) == 1)
  }

  datatype ComponentLookup =
    | ComponentMissing
    | ComponentFound(index: nat)

  function FindComponent(
    components: seq<seq<nat>>,
    vertex: nat,
    offset: nat
  ): ComponentLookup
    decreases |components|
  {
    if |components| == 0 then
      ComponentMissing
    else if vertex in components[0] then
      ComponentFound(offset)
    else
      FindComponent(components[1..], vertex, offset + 1)
  }

  function SameCertifiedComponent(
    components: seq<seq<nat>>,
    left: nat,
    right: nat
  ): bool {
    var leftComponent := FindComponent(components, left, 0);
    var rightComponent := FindComponent(components, right, 0);
    leftComponent.ComponentFound? &&
    rightComponent.ComponentFound? &&
    leftComponent.index == rightComponent.index
  }

  lemma FindComponentIsSound(
    components: seq<seq<nat>>,
    vertex: nat,
    offset: nat
  )
    ensures FindComponent(components, vertex, offset).ComponentFound? ==>
              var outcome := FindComponent(components, vertex, offset);
              offset <= outcome.index < offset + |components| &&
              vertex in components[outcome.index - offset]
    decreases |components|
  {
    if |components| != 0 && vertex !in components[0] {
      FindComponentIsSound(components[1..], vertex, offset + 1);
    }
  }

  lemma FindComponentIsFoundIffCountPositive(
    components: seq<seq<nat>>,
    vertex: nat,
    offset: nat
  )
    ensures FindComponent(components, vertex, offset).ComponentFound? <==>
            CountContaining(components, vertex) > 0
    decreases |components|
  {
    if |components| != 0 && vertex !in components[0] {
      FindComponentIsFoundIffCountPositive(
        components[1..],
        vertex,
        offset + 1
      );
    }
  }

  lemma TwoMembershipsGiveCountAtLeastTwo(
    components: seq<seq<nat>>,
    vertex: nat,
    left: nat,
    right: nat
  )
    requires 0 <= left < right < |components|
    requires vertex in components[left]
    requires vertex in components[right]
    ensures CountContaining(components, vertex) >= 2
    decreases left
  {
    if left == 0 {
      ComponentMembershipGivesPositiveCount(
        components[1..],
        vertex,
        right - 1
      );
    } else {
      TwoMembershipsGiveCountAtLeastTwo(
        components[1..],
        vertex,
        left - 1,
        right - 1
      );
    }
  }

  lemma ComponentMembershipGivesPositiveCount(
    components: seq<seq<nat>>,
    vertex: nat,
    index: nat
  )
    requires index < |components|
    requires vertex in components[index]
    ensures CountContaining(components, vertex) > 0
    decreases index
  {
    if index != 0 {
      ComponentMembershipGivesPositiveCount(
        components[1..],
        vertex,
        index - 1
      );
    }
  }

  datatype SignedGraphOutcome =
    | SignedGraphAccepted
    | InvalidComponentCertificate
    | NonCanonicalEdgeSequence
    | NegativeCycle(
        edgeIndex: nat,
        source: nat,
        target: nat
      )

  function ValidateSignedEdges(
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>,
    offset: nat
  ): SignedGraphOutcome
    decreases |edges|
  {
    if |edges| == 0 then
      SignedGraphAccepted
    else if edges[0].sign.Negative? &&
            SameCertifiedComponent(
              components,
              edges[0].source,
              edges[0].target
            ) then
      NegativeCycle(offset, edges[0].source, edges[0].target)
    else
      ValidateSignedEdges(edges[1..], components, offset + 1)
  }

  function ValidateSignedGraph(
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>
  ): SignedGraphOutcome {
    ValidateSignedEdges(edges, components, 0)
  }

  lemma SignedEdgeValidationHasNoCertificateError(
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>,
    offset: nat
  )
    ensures var outcome := ValidateSignedEdges(edges, components, offset);
            outcome.SignedGraphAccepted? || outcome.NegativeCycle?
    decreases |edges|
  {
    if |edges| != 0 &&
       !(edges[0].sign.Negative? &&
         SameCertifiedComponent(
           components,
           edges[0].source,
           edges[0].target
         )) {
      SignedEdgeValidationHasNoCertificateError(
        edges[1..],
        components,
        offset + 1
      );
    }
  }

  function ValidateCertifiedSignedGraph(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>
  ): SignedGraphOutcome {
    if !CanonicalEdgeSequence(edges) then
      NonCanonicalEdgeSequence
    else if !ExecutableCertificateValid(vertices, edges, components) then
      InvalidComponentCertificate
    else
      ValidateSignedEdges(edges, components, 0)
  }

  lemma CertifiedValidationStatusPrecedence(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>
  )
    ensures ValidateCertifiedSignedGraph(
              vertices,
              edges,
              components
            ).NonCanonicalEdgeSequence? <==>
            !CanonicalEdgeSequence(edges)
    ensures ValidateCertifiedSignedGraph(
              vertices,
              edges,
              components
            ).InvalidComponentCertificate? <==>
            CanonicalEdgeSequence(edges) &&
            !ExecutableCertificateValid(vertices, edges, components)
  {
    if !CanonicalEdgeSequence(edges) {
      assert ValidateCertifiedSignedGraph(
          vertices,
          edges,
          components
        ) == NonCanonicalEdgeSequence;
    } else if !ExecutableCertificateValid(vertices, edges, components) {
      assert ValidateCertifiedSignedGraph(
          vertices,
          edges,
          components
        ) == InvalidComponentCertificate;
    } else {
      SignedEdgeValidationHasNoCertificateError(edges, components, 0);
      var outcome := ValidateSignedEdges(edges, components, 0);
      assert outcome.SignedGraphAccepted? || outcome.NegativeCycle?;
      assert ValidateCertifiedSignedGraph(
          vertices,
          edges,
          components
        ) == outcome;
    }
  }

  lemma CertifiedValidationAcceptsIffCertificateCanonicalAndNoInternalNegative(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>
  )
    ensures ValidateCertifiedSignedGraph(
              vertices,
              edges,
              components
            ).SignedGraphAccepted? <==>
            CanonicalEdgeSequence(edges) &&
            ExecutableCertificateValid(vertices, edges, components) &&
            (forall edge <- edges ::
               edge.sign.Positive? ||
               !SameCertifiedComponent(
                 components,
                 edge.source,
                 edge.target
               ))
  {
    if CanonicalEdgeSequence(edges) &&
       ExecutableCertificateValid(vertices, edges, components) {
      AcceptedExactlyWhenNoNegativeEdgeIsInternal(edges, components, 0);
    }
  }

  lemma CertifiedValidationRejectsAnInternalNegativeEdge(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>,
    edge: DependencyEdge
  )
    requires CanonicalEdgeSequence(edges)
    requires ExecutableCertificateValid(vertices, edges, components)
    requires edge in edges
    requires edge.sign.Negative?
    requires SameCertifiedComponent(
               components,
               edge.source,
               edge.target
             )
    ensures ValidateCertifiedSignedGraph(
              vertices,
              edges,
              components
            ).NegativeCycle?
  {
    AcceptedExactlyWhenNoNegativeEdgeIsInternal(edges, components, 0);
    SignedEdgeValidationHasNoCertificateError(edges, components, 0);
    assert !ValidateSignedEdges(edges, components, 0).SignedGraphAccepted?;
  }

  lemma CertifiedNegativeDiagnosticIsFirstInCanonicalOrder(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>
  )
    requires CanonicalEdgeSequence(edges)
    requires ExecutableCertificateValid(vertices, edges, components)
    ensures var outcome := ValidateCertifiedSignedGraph(
                             vertices,
                             edges,
                             components
                           );
            outcome.NegativeCycle? ==>
              outcome.edgeIndex < |edges| &&
              edges[outcome.edgeIndex].sign.Negative? &&
              edges[outcome.edgeIndex].source == outcome.source &&
              edges[outcome.edgeIndex].target == outcome.target &&
              SameCertifiedComponent(
                components,
                outcome.source,
                outcome.target
              ) &&
              forall earlier | 0 <= earlier < outcome.edgeIndex ::
                edges[earlier].sign.Positive? ||
                !SameCertifiedComponent(
                  components,
                  edges[earlier].source,
                  edges[earlier].target
                )
  {
    DiagnosticIsDeterministicAndFirst(edges, components, 0);
  }

  predicate EarlierNegativeEdgesAreAcyclic(
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>,
    exclusiveEnd: nat
  ) {
    forall index | 0 <= index < exclusiveEnd && index < |edges| ::
      edges[index].sign.Positive? ||
      !SameCertifiedComponent(
        components,
        edges[index].source,
        edges[index].target
      )
  }

  lemma DiagnosticIsDeterministicAndFirst(
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>,
    offset: nat
  )
    ensures ValidateSignedEdges(edges, components, offset)
            .NegativeCycle? ==>
              var outcome := ValidateSignedEdges(
                               edges,
                               components,
                               offset
                             );
              offset <= outcome.edgeIndex < offset + |edges| &&
              var localIndex := outcome.edgeIndex - offset;
              edges[localIndex].sign.Negative? &&
              edges[localIndex].source == outcome.source &&
              edges[localIndex].target == outcome.target &&
              SameCertifiedComponent(
                components,
                outcome.source,
                outcome.target
              ) &&
              forall earlier | 0 <= earlier < localIndex ::
                edges[earlier].sign.Positive? ||
                !SameCertifiedComponent(
                  components,
                  edges[earlier].source,
                  edges[earlier].target
                )
    decreases |edges|
  {
    if |edges| != 0 &&
       !(edges[0].sign.Negative? &&
         SameCertifiedComponent(
           components,
           edges[0].source,
           edges[0].target
         )) {
      DiagnosticIsDeterministicAndFirst(
        edges[1..],
        components,
        offset + 1
      );
    }
  }

  lemma AcceptedExactlyWhenNoNegativeEdgeIsInternal(
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>,
    offset: nat
  )
    ensures ValidateSignedEdges(
              edges,
              components,
              offset
            ).SignedGraphAccepted? <==>
            forall edge <- edges ::
              edge.sign.Positive? ||
              !SameCertifiedComponent(
                components,
                edge.source,
                edge.target
              )
    decreases |edges|
  {
    if |edges| != 0 {
      AcceptedExactlyWhenNoNegativeEdgeIsInternal(
        edges[1..],
        components,
        offset + 1
      );
    }
  }

  datatype StratumOutcome =
    | StrataAccepted
    | MissingStratum(node: nat)
    | PositiveStratumViolation(
        edgeIndex: nat,
        source: nat,
        target: nat
      )
    | NegativeStratumViolation(
        edgeIndex: nat,
        source: nat,
        target: nat
      )

  function ValidateStratumVertices(
    vertices: seq<nat>,
    strata: map<nat, nat>
  ): StratumOutcome
    decreases |vertices|
  {
    if |vertices| == 0 then
      StrataAccepted
    else if vertices[0] !in strata then
      MissingStratum(vertices[0])
    else
      ValidateStratumVertices(vertices[1..], strata)
  }

  function ValidateStratumEdges(
    edges: seq<DependencyEdge>,
    strata: map<nat, nat>,
    offset: nat
  ): StratumOutcome
    decreases |edges|
  {
    if |edges| == 0 then
      StrataAccepted
    else if edges[0].source !in strata then
      MissingStratum(edges[0].source)
    else if edges[0].target !in strata then
      MissingStratum(edges[0].target)
    else if edges[0].sign.Positive? &&
            strata[edges[0].target] > strata[edges[0].source] then
      PositiveStratumViolation(
        offset,
        edges[0].source,
        edges[0].target
      )
    else if edges[0].sign.Negative? &&
            strata[edges[0].target] >= strata[edges[0].source] then
      NegativeStratumViolation(
        offset,
        edges[0].source,
        edges[0].target
      )
    else
      ValidateStratumEdges(edges[1..], strata, offset + 1)
  }

  function ValidateStrata(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    strata: map<nat, nat>
  ): StratumOutcome {
    var verticesOutcome := ValidateStratumVertices(vertices, strata);
    if !verticesOutcome.StrataAccepted? then
      verticesOutcome
    else
      ValidateStratumEdges(edges, strata, 0)
  }

  predicate StrictStratumInequalities(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    strata: map<nat, nat>
  ) {
    (forall vertex <- vertices :: vertex in strata) &&
    (forall edge <- edges ::
       edge.source in strata &&
       edge.target in strata &&
       if edge.sign.Positive? then
         strata[edge.target] <= strata[edge.source]
       else
         strata[edge.target] < strata[edge.source])
  }

  lemma VertexValidationAcceptedIffComplete(
    vertices: seq<nat>,
    strata: map<nat, nat>
  )
    ensures ValidateStratumVertices(vertices, strata).StrataAccepted? <==>
            forall vertex <- vertices :: vertex in strata
    decreases |vertices|
  {
    if |vertices| != 0 {
      VertexValidationAcceptedIffComplete(vertices[1..], strata);
    }
  }

  lemma EdgeValidationAcceptedIffStrict(
    edges: seq<DependencyEdge>,
    strata: map<nat, nat>,
    offset: nat
  )
    ensures ValidateStratumEdges(
              edges,
              strata,
              offset
            ).StrataAccepted? <==>
            forall edge <- edges ::
              edge.source in strata &&
              edge.target in strata &&
              if edge.sign.Positive? then
                strata[edge.target] <= strata[edge.source]
              else
                strata[edge.target] < strata[edge.source]
    decreases |edges|
  {
    if |edges| != 0 {
      EdgeValidationAcceptedIffStrict(edges[1..], strata, offset + 1);
    }
  }

  lemma StratumValidationAcceptedIffStrict(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    strata: map<nat, nat>
  )
    ensures ValidateStrata(vertices, edges, strata).StrataAccepted? <==>
            StrictStratumInequalities(vertices, edges, strata)
  {
    VertexValidationAcceptedIffComplete(vertices, strata);
    if ValidateStratumVertices(vertices, strata).StrataAccepted? {
      EdgeValidationAcceptedIffStrict(edges, strata, 0);
    }
  }

  lemma PathStrataNeverIncrease(
    edges: seq<DependencyEdge>,
    path: seq<nat>,
    strata: map<nat, nat>
  )
    requires PathUsesEdges(edges, path)
    requires forall vertex <- path :: vertex in strata
    requires forall edge <- edges ::
               edge.source in strata &&
               edge.target in strata &&
               if edge.sign.Positive? then
                 strata[edge.target] <= strata[edge.source]
               else
                 strata[edge.target] < strata[edge.source]
    ensures strata[path[|path| - 1]] <= strata[path[0]]
    decreases |path|
  {
    if |path| > 1 {
      var edge :|
        edge in edges &&
        edge.source == path[0] &&
        edge.target == path[1];
      PathStrataNeverIncrease(edges, path[1..], strata);
    }
  }

  lemma PathVerticesBelongToGraph(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    path: seq<nat>
  )
    requires PathUsesEdges(edges, path)
    requires path[0] in vertices
    requires forall edge <- edges ::
               edge.source in vertices && edge.target in vertices
    ensures forall vertex <- path :: vertex in vertices
    decreases |path|
  {
    if |path| > 1 {
      var edge :|
        edge in edges &&
        edge.source == path[0] &&
        edge.target == path[1];
      assert path[1] in vertices;
      PathVerticesBelongToGraph(vertices, edges, path[1..]);
    }
  }

  lemma SameCertifiedComponentImpliesMutualReachability(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>,
    left: nat,
    right: nat
  )
    requires TarjanEquivalentCertificate(vertices, edges, components)
    requires SameCertifiedComponent(components, left, right)
    ensures Reachable(edges, left, right)
    ensures Reachable(edges, right, left)
  {
    var leftLookup := FindComponent(components, left, 0);
    var rightLookup := FindComponent(components, right, 0);
    FindComponentIsSound(components, left, 0);
    FindComponentIsSound(components, right, 0);
    var index := leftLookup.index;
    assert rightLookup.index == index;
    assert index < |components|;
    assert left in components[index];
    assert right in components[index];
    assert ComponentMatchesReachability(
        vertices,
        edges,
        components[index]
      );
    var anchor := components[index][0];
    assert Reachable(edges, left, anchor);
    assert Reachable(edges, anchor, right);
    ReachabilityIsTransitive(edges, left, anchor, right);
    assert Reachable(edges, right, anchor);
    assert Reachable(edges, anchor, left);
    ReachabilityIsTransitive(edges, right, anchor, left);
  }

  lemma MutualReachabilityImpliesSameCertifiedComponent(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>,
    left: nat,
    right: nat
  )
    requires TarjanEquivalentCertificate(vertices, edges, components)
    requires left in vertices
    requires right in vertices
    requires Reachable(edges, left, right)
    requires Reachable(edges, right, left)
    ensures SameCertifiedComponent(components, left, right)
  {
    assert CountContaining(components, left) == 1;
    assert CountContaining(components, right) == 1;
    FindComponentIsFoundIffCountPositive(components, left, 0);
    FindComponentIsFoundIffCountPositive(components, right, 0);
    var leftLookup := FindComponent(components, left, 0);
    var rightLookup := FindComponent(components, right, 0);
    FindComponentIsSound(components, left, 0);
    FindComponentIsSound(components, right, 0);
    var leftIndex := leftLookup.index;
    var rightIndex := rightLookup.index;
    assert left in components[leftIndex];
    assert right in components[rightIndex];
    assert ComponentMatchesReachability(
        vertices,
        edges,
        components[leftIndex]
      );
    var anchor := components[leftIndex][0];
    assert Reachable(edges, anchor, left);
    assert Reachable(edges, left, anchor);
    ReachabilityIsTransitive(edges, anchor, left, right);
    ReachabilityIsTransitive(edges, right, left, anchor);
    assert right in components[leftIndex];
    if leftIndex < rightIndex {
      TwoMembershipsGiveCountAtLeastTwo(
        components,
        right,
        leftIndex,
        rightIndex
      );
      assert false;
    } else if rightIndex < leftIndex {
      TwoMembershipsGiveCountAtLeastTwo(
        components,
        right,
        rightIndex,
        leftIndex
      );
      assert false;
    }
    assert leftLookup.index == rightLookup.index;
  }

  lemma SameCertifiedComponentIffMutualFinitePathReachability(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>,
    left: nat,
    right: nat
  )
    requires TarjanEquivalentCertificate(vertices, edges, components)
    requires left in vertices
    requires right in vertices
    ensures SameCertifiedComponent(components, left, right) <==>
            (Reachable(edges, left, right) &&
             Reachable(edges, right, left))
  {
    if SameCertifiedComponent(components, left, right) {
      SameCertifiedComponentImpliesMutualReachability(
        vertices,
        edges,
        components,
        left,
        right
      );
    } else if Reachable(edges, left, right) &&
              Reachable(edges, right, left) {
      MutualReachabilityImpliesSameCertifiedComponent(
        vertices,
        edges,
        components,
        left,
        right
      );
    }
  }

  lemma CertifiedValidationAcceptsIffNoNegativeFinitePathCycle(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>
  )
    requires CanonicalEdgeSequence(edges)
    requires ExecutableCertificateValid(vertices, edges, components)
    ensures ValidateCertifiedSignedGraph(
              vertices,
              edges,
              components
            ).SignedGraphAccepted? <==>
            forall edge <- edges ::
              edge.sign.Positive? ||
              !Reachable(edges, edge.target, edge.source)
  {
    ExecutableCertificateRefinesTarjanEquivalentCertificate(
      vertices,
      edges,
      components
    );
    forall edge <- edges
      ensures !SameCertifiedComponent(
                components,
                edge.source,
                edge.target
              ) <==>
              !Reachable(edges, edge.target, edge.source)
    {
      DependencyEdgeGivesReachability(edges, edge);
      SameCertifiedComponentIffMutualFinitePathReachability(
        vertices,
        edges,
        components,
        edge.source,
        edge.target
      );
    }
    CertifiedValidationAcceptsIffCertificateCanonicalAndNoInternalNegative(
      vertices,
      edges,
      components
    );
  }

  lemma StrictStrataExcludeEveryNegativeCycle(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>,
    strata: map<nat, nat>,
    edge: DependencyEdge
  )
    requires TarjanEquivalentCertificate(vertices, edges, components)
    requires StrictStratumInequalities(vertices, edges, strata)
    requires edge in edges
    requires edge.sign.Negative?
    ensures !SameCertifiedComponent(
              components,
              edge.source,
              edge.target
            )
  {
    if SameCertifiedComponent(
        components,
        edge.source,
        edge.target
      ) {
      SameCertifiedComponentImpliesMutualReachability(
        vertices,
        edges,
        components,
        edge.target,
        edge.source
      );
      var path: seq<nat> :|
        PathUsesEdges(edges, path) &&
        path[0] == edge.target &&
        path[|path| - 1] == edge.source;
      PathVerticesBelongToGraph(vertices, edges, path);
      assert forall vertex <- path :: vertex in strata;
      PathStrataNeverIncrease(edges, path, strata);
    }
  }

  lemma StrictStrataAreAcceptedBySignedGraphValidation(
    vertices: seq<nat>,
    edges: seq<DependencyEdge>,
    components: seq<seq<nat>>,
    strata: map<nat, nat>
  )
    requires TarjanEquivalentCertificate(vertices, edges, components)
    requires StrictStratumInequalities(vertices, edges, strata)
    ensures ValidateSignedGraph(edges, components).SignedGraphAccepted?
  {
    forall edge <- edges
      ensures edge.sign.Positive? ||
              !SameCertifiedComponent(
                components,
                edge.source,
                edge.target
              )
    {
      if edge.sign.Negative? {
        StrictStrataExcludeEveryNegativeCycle(
          vertices,
          edges,
          components,
          strata,
          edge
        );
      }
    }
    AcceptedExactlyWhenNoNegativeEdgeIsInternal(edges, components, 0);
  }
}
