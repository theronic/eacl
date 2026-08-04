module RoutingCertificate {
  datatype IndexedDependencyEdge = IndexedDependencyEdge(
    head: nat,
    target: nat
  )

  datatype IndexedRoutingPath =
    | IndexedDirectRelation(head: nat)
    | IndexedSelfPermission(head: nat, target: nat)
    | IndexedArrowRelation(head: nat)
    | IndexedArrowPermission(head: nat, target: nat)

  ghost function DerivedRoutingEdges(
    paths: seq<IndexedRoutingPath>
  ): seq<IndexedDependencyEdge>
    decreases |paths|
  {
    if |paths| == 0 then
      []
    else
      match paths[0]
      case IndexedDirectRelation(_) =>
        DerivedRoutingEdges(paths[1..])
      case IndexedSelfPermission(head, target) =>
        [IndexedDependencyEdge(head, target)] +
        DerivedRoutingEdges(paths[1..])
      case IndexedArrowRelation(_) =>
        DerivedRoutingEdges(paths[1..])
      case IndexedArrowPermission(head, target) =>
        [IndexedDependencyEdge(head, target)] +
        DerivedRoutingEdges(paths[1..])
  }

  predicate ValidIndexedRoutingPath(
    nodeCount: nat,
    path: IndexedRoutingPath
  )
  {
    match path
    case IndexedDirectRelation(head) =>
      head < nodeCount
    case IndexedSelfPermission(head, target) =>
      head < nodeCount && target < nodeCount
    case IndexedArrowRelation(head) =>
      head < nodeCount
    case IndexedArrowPermission(head, target) =>
      head < nodeCount && target < nodeCount
  }

  predicate ValidIndexedRoutingPaths(
    nodeCount: nat,
    paths: seq<IndexedRoutingPath>
  )
  {
    forall pathIndex: int | 0 <= pathIndex < |paths| ::
      ValidIndexedRoutingPath(nodeCount, paths[pathIndex])
  }

  datatype RoutingProof = RoutingProof(
    componentRoot: seq<nat>,
    forwardParentEdge: seq<int>,
    reverseParentEdge: seq<int>,
    forwardDepth: seq<nat>,
    reverseDepth: seq<nat>,
    componentRank: seq<nat>,
    multipleMemberWitness: seq<int>,
    selfLoopWitnessEdge: seq<int>,
    traversal: seq<bool>,
    traversalWitnessEdge: seq<int>
  )

  predicate ValidIndex(nodeCount: nat, index: int)
  {
    0 <= index < nodeCount
  }

  predicate ValidEdgeIndices(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>
  )
  {
    forall e: int | 0 <= e < |edges| ::
      edges[e].head < nodeCount &&
      edges[e].target < nodeCount
  }

  ghost predicate HasRoutingEdge(
    edges: seq<IndexedDependencyEdge>,
    source: nat,
    target: nat
  )
  {
    exists edgeIndex: nat ::
      edgeIndex < |edges| &&
      edges[edgeIndex].head == source &&
      edges[edgeIndex].target == target
  }

  ghost predicate ValidRoutingPath(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    path: seq<nat>
  )
  {
    |path| > 0 &&
    (forall i: int | 0 <= i < |path| :: path[i] < nodeCount) &&
    (forall i: int | 0 <= i && i + 1 < |path| ::
       HasRoutingEdge(edges, path[i], path[i + 1]))
  }

  ghost predicate RoutingReachable(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    head: nat,
    target: nat
  )
  {
    exists path: seq<nat> ::
      ValidRoutingPath(nodeCount, edges, path) &&
      path[0] == head &&
      path[|path| - 1] == target
  }

  lemma RoutingReachableReflexive(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    node: nat
  )
    requires node < nodeCount
    ensures RoutingReachable(nodeCount, edges, node, node)
  {
    var path := [node];
  }

  lemma RoutingReachableEdge(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    edgeIndex: nat
  )
    requires ValidEdgeIndices(nodeCount, edges)
    requires edgeIndex < |edges|
    ensures RoutingReachable(
              nodeCount,
              edges,
              edges[edgeIndex].head,
              edges[edgeIndex].target
            )
  {
    var path := [edges[edgeIndex].head, edges[edgeIndex].target];
  }

  lemma RoutingPathConcatenation(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    left: seq<nat>,
    right: seq<nat>
  )
    requires ValidRoutingPath(nodeCount, edges, left)
    requires ValidRoutingPath(nodeCount, edges, right)
    requires left[|left| - 1] == right[0]
    ensures ValidRoutingPath(
              nodeCount,
              edges,
              left + right[1..]
            )
  {
  }

  lemma RoutingReachableTransitive(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    first: nat,
    middle: nat,
    last: nat
  )
    requires RoutingReachable(
               nodeCount,
               edges,
               first,
               middle
             )
    requires RoutingReachable(
               nodeCount,
               edges,
               middle,
               last
             )
    ensures RoutingReachable(
              nodeCount,
              edges,
              first,
              last
            )
  {
    var left :| ValidRoutingPath(nodeCount, edges, left) &&
                left[0] == first &&
                left[|left| - 1] == middle;
    var right :| ValidRoutingPath(nodeCount, edges, right) &&
                 right[0] == middle &&
                 right[|right| - 1] == last;
    RoutingPathConcatenation(nodeCount, edges, left, right);
  }

  predicate ValidCertificateShape(
    nodeCount: nat,
    certificate: RoutingProof
  )
  {
    |certificate.componentRoot| == nodeCount &&
    |certificate.forwardParentEdge| == nodeCount &&
    |certificate.reverseParentEdge| == nodeCount &&
    |certificate.forwardDepth| == nodeCount &&
    |certificate.reverseDepth| == nodeCount &&
    |certificate.componentRank| == nodeCount &&
    |certificate.multipleMemberWitness| == nodeCount &&
    |certificate.selfLoopWitnessEdge| == nodeCount &&
    |certificate.traversal| == nodeCount &&
    |certificate.traversalWitnessEdge| == nodeCount
  }

  predicate ValidComponentRoot(
    nodeCount: nat,
    certificate: RoutingProof,
    node: nat
  )
    requires ValidCertificateShape(nodeCount, certificate)
    requires node < nodeCount
  {
    certificate.componentRoot[node] < nodeCount &&
    certificate.componentRoot[
    certificate.componentRoot[node]
    ] == certificate.componentRoot[node]
  }

  predicate ValidForwardParent(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof,
    node: nat
  )
    requires ValidCertificateShape(nodeCount, certificate)
    requires ValidEdgeIndices(nodeCount, edges)
    requires node < nodeCount
    requires ValidComponentRoot(nodeCount, certificate, node)
  {
    var root := certificate.componentRoot[node];
    if node == root then
      certificate.forwardParentEdge[node] == -1 &&
      certificate.forwardDepth[node] == 0
    else
      ValidIndex(
        |edges|,
        certificate.forwardParentEdge[node]
      ) &&
      edges[certificate.forwardParentEdge[node]].target == node &&
      certificate.componentRoot[
      edges[certificate.forwardParentEdge[node]].head
      ] == root &&
      certificate.forwardDepth[
      edges[certificate.forwardParentEdge[node]].head
      ] < certificate.forwardDepth[node]
  }

  predicate ValidReverseParent(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof,
    node: nat
  )
    requires ValidCertificateShape(nodeCount, certificate)
    requires ValidEdgeIndices(nodeCount, edges)
    requires node < nodeCount
    requires ValidComponentRoot(nodeCount, certificate, node)
  {
    var root := certificate.componentRoot[node];
    if node == root then
      certificate.reverseParentEdge[node] == -1 &&
      certificate.reverseDepth[node] == 0
    else
      ValidIndex(
        |edges|,
        certificate.reverseParentEdge[node]
      ) &&
      edges[certificate.reverseParentEdge[node]].head == node &&
      certificate.componentRoot[
      edges[certificate.reverseParentEdge[node]].target
      ] == root &&
      certificate.reverseDepth[
      edges[certificate.reverseParentEdge[node]].target
      ] < certificate.reverseDepth[node]
  }

  predicate ValidMultipleMemberWitness(
    nodeCount: nat,
    certificate: RoutingProof,
    node: nat
  )
    requires ValidCertificateShape(nodeCount, certificate)
    requires node < nodeCount
    requires ValidComponentRoot(nodeCount, certificate, node)
  {
    var root := certificate.componentRoot[node];
    var witnessIndex := certificate.multipleMemberWitness[root];
    (witnessIndex == -1 ||
     (ValidIndex(nodeCount, witnessIndex) &&
      witnessIndex != root &&
      certificate.componentRoot[witnessIndex] == root)) &&
    (node != root ==> witnessIndex != -1)
  }

  predicate ValidSelfLoopWitness(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof,
    node: nat
  )
    requires ValidCertificateShape(nodeCount, certificate)
    requires ValidEdgeIndices(nodeCount, edges)
    requires node < nodeCount
    requires ValidComponentRoot(nodeCount, certificate, node)
  {
    var root := certificate.componentRoot[node];
    var witnessIndex := certificate.selfLoopWitnessEdge[root];
    witnessIndex == -1 ||
    (ValidIndex(|edges|, witnessIndex) &&
     edges[witnessIndex].head == edges[witnessIndex].target &&
     certificate.componentRoot[edges[witnessIndex].head] == root)
  }

  predicate CertificateComponentIsRecursive(
    nodeCount: nat,
    certificate: RoutingProof,
    node: nat
  )
    requires ValidCertificateShape(nodeCount, certificate)
    requires node < nodeCount
    requires ValidComponentRoot(nodeCount, certificate, node)
  {
    var root := certificate.componentRoot[node];
    certificate.multipleMemberWitness[root] != -1 ||
    certificate.selfLoopWitnessEdge[root] != -1
  }

  predicate ValidTraversalWitness(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof,
    node: nat
  )
    requires ValidCertificateShape(nodeCount, certificate)
    requires ValidEdgeIndices(nodeCount, edges)
    requires node < nodeCount
    requires ValidComponentRoot(nodeCount, certificate, node)
  {
    var root := certificate.componentRoot[node];
    var witnessIndex := certificate.traversalWitnessEdge[root];
    if certificate.traversal[root] &&
       !CertificateComponentIsRecursive(nodeCount, certificate, root) then
      ValidIndex(|edges|, witnessIndex) &&
      certificate.componentRoot[edges[witnessIndex].head] == root &&
      certificate.componentRoot[edges[witnessIndex].target] != root &&
      certificate.traversal[edges[witnessIndex].target]
    else
      witnessIndex == -1
  }

  ghost predicate ValidRoutingCertificate(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof
  )
  {
    ValidCertificateShape(nodeCount, certificate) &&
    ValidEdgeIndices(nodeCount, edges) &&
    (forall node: nat | node < nodeCount ::
       ValidComponentRoot(nodeCount, certificate, node) &&
       ValidForwardParent(nodeCount, edges, certificate, node) &&
       ValidReverseParent(nodeCount, edges, certificate, node) &&
       ValidMultipleMemberWitness(nodeCount, certificate, node) &&
       ValidSelfLoopWitness(nodeCount, edges, certificate, node) &&
       certificate.componentRank[node] < nodeCount &&
       certificate.traversal[node] ==
       certificate.traversal[certificate.componentRoot[node]] &&
       (CertificateComponentIsRecursive(nodeCount, certificate, node) ==>
          certificate.traversal[node]) &&
       ValidTraversalWitness(nodeCount, edges, certificate, node)) &&
    (forall edgeIndex: nat | edgeIndex < |edges| ::
       var edge := edges[edgeIndex];
       var headRoot := certificate.componentRoot[edge.head];
       var targetRoot := certificate.componentRoot[edge.target];
       headRoot < nodeCount &&
       targetRoot < nodeCount &&
       (headRoot != targetRoot ==>
          certificate.componentRank[headRoot] <
          certificate.componentRank[targetRoot]) &&
       (edge.head == edge.target ==>
          certificate.selfLoopWitnessEdge[headRoot] != -1) &&
       (certificate.traversal[edge.target] ==>
          certificate.traversal[edge.head]))
  }

  lemma CertificateRootReachesNode(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof,
    node: nat
  )
    requires ValidRoutingCertificate(nodeCount, edges, certificate)
    requires node < nodeCount
    ensures RoutingReachable(
              nodeCount,
              edges,
              certificate.componentRoot[node],
              node
            )
    decreases certificate.forwardDepth[node]
  {
    var root := certificate.componentRoot[node];
    if node == root {
      RoutingReachableReflexive(nodeCount, edges, node);
    } else {
      var edgeIndex := certificate.forwardParentEdge[node];
      assert ValidForwardParent(
          nodeCount,
          edges,
          certificate,
          node
        );
      assert ValidIndex(|edges|, edgeIndex);
      var parent := edges[edgeIndex].head;
      assert parent < nodeCount;
      assert certificate.componentRoot[parent] == root;
      assert certificate.forwardDepth[parent] <
             certificate.forwardDepth[node];
      CertificateRootReachesNode(
        nodeCount,
        edges,
        certificate,
        parent
      );
      assert RoutingReachable(
          nodeCount,
          edges,
          root,
          parent
        );
      RoutingReachableEdge(nodeCount, edges, edgeIndex);
      assert RoutingReachable(
          nodeCount,
          edges,
          parent,
          node
        );
      RoutingReachableTransitive(
        nodeCount,
        edges,
        root,
        parent,
        node
      );
    }
  }

  lemma CertificateNodeReachesRoot(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof,
    node: nat
  )
    requires ValidRoutingCertificate(nodeCount, edges, certificate)
    requires node < nodeCount
    ensures RoutingReachable(
              nodeCount,
              edges,
              node,
              certificate.componentRoot[node]
            )
    decreases certificate.reverseDepth[node]
  {
    var root := certificate.componentRoot[node];
    if node == root {
      RoutingReachableReflexive(nodeCount, edges, node);
    } else {
      var edgeIndex := certificate.reverseParentEdge[node];
      assert ValidReverseParent(
          nodeCount,
          edges,
          certificate,
          node
        );
      assert ValidIndex(|edges|, edgeIndex);
      var parent := edges[edgeIndex].target;
      assert parent < nodeCount;
      assert certificate.componentRoot[parent] == root;
      assert certificate.reverseDepth[parent] <
             certificate.reverseDepth[node];
      RoutingReachableEdge(nodeCount, edges, edgeIndex);
      assert RoutingReachable(
          nodeCount,
          edges,
          node,
          parent
        );
      CertificateNodeReachesRoot(
        nodeCount,
        edges,
        certificate,
        parent
      );
      assert RoutingReachable(
          nodeCount,
          edges,
          parent,
          root
        );
      RoutingReachableTransitive(
        nodeCount,
        edges,
        node,
        parent,
        root
      );
    }
  }

  lemma SameCertificateComponentIsMutuallyReachable(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof,
    left: nat,
    right: nat
  )
    requires ValidRoutingCertificate(nodeCount, edges, certificate)
    requires left < nodeCount
    requires right < nodeCount
    requires certificate.componentRoot[left] ==
             certificate.componentRoot[right]
    ensures RoutingReachable(nodeCount, edges, left, right)
    ensures RoutingReachable(nodeCount, edges, right, left)
  {
    CertificateNodeReachesRoot(
      nodeCount,
      edges,
      certificate,
      left
    );
    CertificateRootReachesNode(
      nodeCount,
      edges,
      certificate,
      right
    );
    RoutingReachableTransitive(
      nodeCount,
      edges,
      left,
      certificate.componentRoot[left],
      right
    );
    CertificateNodeReachesRoot(
      nodeCount,
      edges,
      certificate,
      right
    );
    CertificateRootReachesNode(
      nodeCount,
      edges,
      certificate,
      left
    );
    RoutingReachableTransitive(
      nodeCount,
      edges,
      right,
      certificate.componentRoot[right],
      left
    );
  }

  lemma RoutingPathSuffixIsValid(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    path: seq<nat>
  )
    requires ValidRoutingPath(nodeCount, edges, path)
    requires |path| > 1
    ensures ValidRoutingPath(nodeCount, edges, path[1..])
  {
  }

  lemma RoutingPathRanksIncrease(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof,
    path: seq<nat>
  )
    requires ValidRoutingCertificate(nodeCount, edges, certificate)
    requires ValidCertificateShape(nodeCount, certificate)
    requires ValidRoutingPath(nodeCount, edges, path)
    requires path[0] < nodeCount
    requires path[|path| - 1] < nodeCount
    requires certificate.componentRoot[path[0]] < nodeCount
    requires certificate.componentRoot[path[|path| - 1]] <
             nodeCount
    ensures certificate.componentRank[
            certificate.componentRoot[path[0]]
            ] <=
            certificate.componentRank[
            certificate.componentRoot[path[|path| - 1]]
            ]
    ensures certificate.componentRoot[path[0]] !=
            certificate.componentRoot[path[|path| - 1]] ==>
              certificate.componentRank[
              certificate.componentRoot[path[0]]
              ] <
              certificate.componentRank[
              certificate.componentRoot[path[|path| - 1]]
              ]
    decreases |path|
  {
    if |path| > 1 {
      assert HasRoutingEdge(edges, path[0], path[1]);
      var edgeIndex: nat :|
        edgeIndex < |edges| &&
        edges[edgeIndex].head == path[0] &&
        edges[edgeIndex].target == path[1];
      var firstRoot := certificate.componentRoot[path[0]];
      var secondRoot := certificate.componentRoot[path[1]];
      var lastRoot :=
        certificate.componentRoot[path[|path| - 1]];
      assert firstRoot < nodeCount;
      assert secondRoot < nodeCount;
      assert path[|path| - 1] < nodeCount;
      assert lastRoot < nodeCount;
      if firstRoot == secondRoot {
        assert certificate.componentRank[firstRoot] ==
               certificate.componentRank[secondRoot];
      } else {
        assert certificate.componentRank[firstRoot] <
               certificate.componentRank[secondRoot];
      }
      RoutingPathSuffixIsValid(nodeCount, edges, path);
      assert path[1] < nodeCount;
      assert path[1..][0] == path[1];
      assert path[1..][|path[1..]| - 1] ==
             path[|path| - 1];
      assert certificate.componentRoot[path[1..][0]] <
             nodeCount;
      assert certificate.componentRoot[
        path[1..][|path[1..]| - 1]
        ] < nodeCount;
      RoutingPathRanksIncrease(
        nodeCount,
        edges,
        certificate,
        path[1..]
      );
      if firstRoot != lastRoot {
        if firstRoot == secondRoot {
          assert secondRoot != lastRoot;
        } else if secondRoot == lastRoot {
          assert certificate.componentRank[firstRoot] <
                 certificate.componentRank[lastRoot];
        }
      }
    }
  }

  lemma MutuallyReachableNodesShareCertificateComponent(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof,
    left: nat,
    right: nat
  )
    requires ValidRoutingCertificate(nodeCount, edges, certificate)
    requires left < nodeCount
    requires right < nodeCount
    requires RoutingReachable(nodeCount, edges, left, right)
    requires RoutingReachable(nodeCount, edges, right, left)
    ensures certificate.componentRoot[left] ==
            certificate.componentRoot[right]
  {
    var forwardPath :|
      ValidRoutingPath(nodeCount, edges, forwardPath) &&
      forwardPath[0] == left &&
      forwardPath[|forwardPath| - 1] == right;
    var reversePath :|
      ValidRoutingPath(nodeCount, edges, reversePath) &&
      reversePath[0] == right &&
      reversePath[|reversePath| - 1] == left;
    assert forwardPath[0] < nodeCount;
    assert forwardPath[|forwardPath| - 1] < nodeCount;
    assert reversePath[0] < nodeCount;
    assert reversePath[|reversePath| - 1] < nodeCount;
    assert ValidComponentRoot(
        nodeCount,
        certificate,
        left
      );
    assert ValidComponentRoot(
        nodeCount,
        certificate,
        right
      );
    assert certificate.componentRoot[forwardPath[0]] <
           nodeCount;
    assert certificate.componentRoot[
      forwardPath[|forwardPath| - 1]
      ] < nodeCount;
    assert certificate.componentRoot[reversePath[0]] <
           nodeCount;
    assert certificate.componentRoot[
      reversePath[|reversePath| - 1]
      ] < nodeCount;
    RoutingPathRanksIncrease(
      nodeCount,
      edges,
      certificate,
      forwardPath
    );
    RoutingPathRanksIncrease(
      nodeCount,
      edges,
      certificate,
      reversePath
    );
  }

  ghost predicate IndexedNodeIsRecursive(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    node: nat
  )
  {
    node < nodeCount &&
    ((exists other: nat ::
        other < nodeCount &&
        other != node &&
        RoutingReachable(nodeCount, edges, node, other) &&
        RoutingReachable(nodeCount, edges, other, node)) ||
     HasRoutingEdge(edges, node, node))
  }

  lemma CertificateRecursiveComponentIsExact(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof,
    node: nat
  )
    requires ValidRoutingCertificate(nodeCount, edges, certificate)
    requires node < nodeCount
    ensures CertificateComponentIsRecursive(
              nodeCount,
              certificate,
              node
            ) <==>
            IndexedNodeIsRecursive(nodeCount, edges, node)
  {
    var root := certificate.componentRoot[node];
    if CertificateComponentIsRecursive(
        nodeCount,
        certificate,
        node
      ) {
      if certificate.multipleMemberWitness[root] != -1 {
        assert ValidMultipleMemberWitness(
            nodeCount,
            certificate,
            node
          );
        assert ValidIndex(
            nodeCount,
            certificate.multipleMemberWitness[root]
          );
        var member :=
          certificate.multipleMemberWitness[root] as nat;
        assert member < nodeCount;
        assert member != root;
        assert certificate.componentRoot[member] == root;
        if node == root {
          SameCertificateComponentIsMutuallyReachable(
            nodeCount,
            edges,
            certificate,
            node,
            member
          );
        } else {
          SameCertificateComponentIsMutuallyReachable(
            nodeCount,
            edges,
            certificate,
            node,
            root
          );
        }
      } else {
        assert ValidSelfLoopWitness(
            nodeCount,
            edges,
            certificate,
            node
          );
        assert ValidIndex(
            |edges|,
            certificate.selfLoopWitnessEdge[root]
          );
        var edgeIndex :=
          certificate.selfLoopWitnessEdge[root] as nat;
        var selfNode := edges[edgeIndex].head;
        assert selfNode < nodeCount;
        assert edges[edgeIndex].target == selfNode;
        assert certificate.componentRoot[selfNode] == root;
        if node == selfNode {
          assert HasRoutingEdge(edges, node, node);
        } else {
          SameCertificateComponentIsMutuallyReachable(
            nodeCount,
            edges,
            certificate,
            node,
            selfNode
          );
        }
      }
    }
    if IndexedNodeIsRecursive(nodeCount, edges, node) {
      if HasRoutingEdge(edges, node, node) {
        var edgeIndex: nat :|
          edgeIndex < |edges| &&
          edges[edgeIndex].head == node &&
          edges[edgeIndex].target == node;
        assert certificate.selfLoopWitnessEdge[root] != -1;
      } else {
        var other: nat :|
          other < nodeCount &&
          other != node &&
          RoutingReachable(nodeCount, edges, node, other) &&
          RoutingReachable(nodeCount, edges, other, node);
        MutuallyReachableNodesShareCertificateComponent(
          nodeCount,
          edges,
          certificate,
          node,
          other
        );
        if node != root {
          assert ValidMultipleMemberWitness(
              nodeCount,
              certificate,
              node
            );
          assert certificate.multipleMemberWitness[root] != -1;
        } else {
          assert other != root;
          assert ValidMultipleMemberWitness(
              nodeCount,
              certificate,
              other
            );
          assert certificate.multipleMemberWitness[
            certificate.componentRoot[other]
            ] != -1;
        }
      }
    }
  }

  ghost predicate IndexedDependsOnRecursiveComponent(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    node: nat
  )
  {
    node < nodeCount &&
    exists target: nat ::
      target < nodeCount &&
      RoutingReachable(nodeCount, edges, node, target) &&
      IndexedNodeIsRecursive(nodeCount, edges, target)
  }

  lemma RoutingPathPropagatesTraversalBackward(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof,
    path: seq<nat>
  )
    requires ValidRoutingCertificate(nodeCount, edges, certificate)
    requires ValidRoutingPath(nodeCount, edges, path)
    requires certificate.traversal[path[|path| - 1]]
    ensures certificate.traversal[path[0]]
    decreases |path|
  {
    if |path| > 1 {
      RoutingPathSuffixIsValid(nodeCount, edges, path);
      RoutingPathPropagatesTraversalBackward(
        nodeCount,
        edges,
        certificate,
        path[1..]
      );
      assert HasRoutingEdge(edges, path[0], path[1]);
      var edgeIndex: nat :|
        edgeIndex < |edges| &&
        edges[edgeIndex].head == path[0] &&
        edges[edgeIndex].target == path[1];
      assert certificate.traversal[path[1]];
      assert certificate.traversal[path[0]];
    }
  }

  lemma CertificateTraversalIsSound(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof,
    node: nat
  )
    requires ValidRoutingCertificate(nodeCount, edges, certificate)
    requires node < nodeCount
    requires certificate.traversal[node]
    requires ValidComponentRoot(nodeCount, certificate, node)
    ensures IndexedDependsOnRecursiveComponent(
              nodeCount,
              edges,
              node
            )
    decreases nodeCount -
              certificate.componentRank[
              certificate.componentRoot[node]
              ]
  {
    var root := certificate.componentRoot[node];
    if CertificateComponentIsRecursive(
        nodeCount,
        certificate,
        node
      ) {
      RoutingReachableReflexive(nodeCount, edges, node);
      CertificateRecursiveComponentIsExact(
        nodeCount,
        edges,
        certificate,
        node
      );
    } else {
      assert ValidTraversalWitness(
          nodeCount,
          edges,
          certificate,
          node
        );
      var edgeIndex :=
        certificate.traversalWitnessEdge[root] as nat;
      var edge := edges[edgeIndex];
      assert certificate.componentRoot[edge.head] == root;
      assert certificate.componentRoot[edge.target] != root;
      assert certificate.traversal[edge.target];
      assert certificate.componentRank[root] <
             certificate.componentRank[
             certificate.componentRoot[edge.target]
             ];
      SameCertificateComponentIsMutuallyReachable(
        nodeCount,
        edges,
        certificate,
        node,
        edge.head
      );
      RoutingReachableEdge(nodeCount, edges, edgeIndex);
      RoutingReachableTransitive(
        nodeCount,
        edges,
        node,
        edge.head,
        edge.target
      );
      assert ValidComponentRoot(
          nodeCount,
          certificate,
          edge.target
        );
      CertificateTraversalIsSound(
        nodeCount,
        edges,
        certificate,
        edge.target
      );
      var recursiveTarget: nat :|
        recursiveTarget < nodeCount &&
        RoutingReachable(
          nodeCount,
          edges,
          edge.target,
          recursiveTarget
        ) &&
        IndexedNodeIsRecursive(
          nodeCount,
          edges,
          recursiveTarget
        );
      RoutingReachableTransitive(
        nodeCount,
        edges,
        node,
        edge.target,
        recursiveTarget
      );
    }
  }

  lemma CertificateTraversalIsComplete(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof,
    node: nat
  )
    requires ValidRoutingCertificate(nodeCount, edges, certificate)
    requires node < nodeCount
    requires IndexedDependsOnRecursiveComponent(
               nodeCount,
               edges,
               node
             )
    ensures certificate.traversal[node]
  {
    var target: nat :|
      target < nodeCount &&
      RoutingReachable(nodeCount, edges, node, target) &&
      IndexedNodeIsRecursive(nodeCount, edges, target);
    CertificateRecursiveComponentIsExact(
      nodeCount,
      edges,
      certificate,
      target
    );
    assert CertificateComponentIsRecursive(
        nodeCount,
        certificate,
        target
      );
    assert certificate.traversal[target];
    var path :|
      ValidRoutingPath(nodeCount, edges, path) &&
      path[0] == node &&
      path[|path| - 1] == target;
    RoutingPathPropagatesTraversalBackward(
      nodeCount,
      edges,
      certificate,
      path
    );
  }

  lemma AcceptedRoutingCertificateIsExact(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof
  )
    requires ValidRoutingCertificate(nodeCount, edges, certificate)
    ensures forall node: nat | node < nodeCount ::
              certificate.traversal[node] <==>
              IndexedDependsOnRecursiveComponent(
                nodeCount,
                edges,
                node
              )
  {
    forall node: nat | node < nodeCount
      ensures certificate.traversal[node] <==>
              IndexedDependsOnRecursiveComponent(
                nodeCount,
                edges,
                node
              )
    {
      if certificate.traversal[node] {
        assert ValidComponentRoot(
            nodeCount,
            certificate,
            node
          );
        CertificateTraversalIsSound(
          nodeCount,
          edges,
          certificate,
          node
        );
      }
      if IndexedDependsOnRecursiveComponent(
          nodeCount,
          edges,
          node
        ) {
        CertificateTraversalIsComplete(
          nodeCount,
          edges,
          certificate,
          node
        );
      }
    }
  }

  datatype RoutingCertificateError =
    | ShapeMismatch
    | InvalidComponent
    | InvalidDependencyEdge
    | InvalidComponentWitness
    | InvalidRoutingPath
    | RoutingPathEdgeMismatch

  datatype RoutingCertificateCounters = RoutingCertificateCounters(
    nodeChecks: nat,
    edgeChecks: nat
  )

  datatype RoutingCertificateDecision =
    | RoutingCertificateAccepted(
        traversal: seq<bool>,
        counters: RoutingCertificateCounters
      )
    | RoutingCertificateRejected(
        error: RoutingCertificateError,
        counters: RoutingCertificateCounters
      )

  method CheckRoutingCertificate(
    nodeCount: nat,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof
  ) returns (decision: RoutingCertificateDecision)
    ensures decision.RoutingCertificateAccepted? <==>
            ValidRoutingCertificate(
              nodeCount,
              edges,
              certificate
            )
    ensures decision.RoutingCertificateAccepted? ==>
              decision.traversal == certificate.traversal
    ensures decision.RoutingCertificateAccepted? ==>
              decision.counters.nodeChecks == 2 * nodeCount
    ensures decision.RoutingCertificateAccepted? ==>
              decision.counters.edgeChecks == |edges|
    ensures decision.counters.nodeChecks <= 2 * nodeCount
    ensures decision.counters.edgeChecks <= |edges|
    ensures decision.RoutingCertificateAccepted? ==>
              forall node: nat | node < nodeCount ::
                decision.traversal[node] <==>
                IndexedDependsOnRecursiveComponent(
                  nodeCount,
                  edges,
                  node
                )
  {
    var nodeChecks: nat := 0;
    var edgeChecks: nat := 0;
    if !ValidCertificateShape(nodeCount, certificate) {
      return RoutingCertificateRejected(
          ShapeMismatch,
          RoutingCertificateCounters(nodeChecks, edgeChecks)
        );
    }

    var node: nat := 0;
    while node < nodeCount
      invariant node <= nodeCount
      invariant nodeChecks == node
      invariant edgeChecks == 0
      invariant ValidCertificateShape(nodeCount, certificate)
      invariant forall prior: nat | prior < node ::
                  ValidComponentRoot(
                    nodeCount,
                    certificate,
                    prior
                  ) &&
                  certificate.componentRank[prior] < nodeCount
      decreases nodeCount - node
    {
      nodeChecks := nodeChecks + 1;
      if !ValidComponentRoot(nodeCount, certificate, node) ||
         certificate.componentRank[node] >= nodeCount {
        return RoutingCertificateRejected(
            InvalidComponent,
            RoutingCertificateCounters(nodeChecks, edgeChecks)
          );
      }
      node := node + 1;
    }

    var edgeIndex: nat := 0;
    while edgeIndex < |edges|
      invariant edgeIndex <= |edges|
      invariant nodeChecks == nodeCount
      invariant edgeChecks == edgeIndex
      invariant ValidCertificateShape(nodeCount, certificate)
      invariant forall checkedNode: nat | checkedNode < nodeCount ::
                  ValidComponentRoot(
                    nodeCount,
                    certificate,
                    checkedNode
                  ) &&
                  certificate.componentRank[checkedNode] <
                  nodeCount
      invariant forall priorEdge: nat | priorEdge < edgeIndex ::
                  edges[priorEdge].head < nodeCount &&
                  edges[priorEdge].target < nodeCount &&
                  certificate.componentRoot[
                  edges[priorEdge].head
                  ] < nodeCount &&
                  certificate.componentRoot[
                  edges[priorEdge].target
                  ] < nodeCount &&
                  (certificate.componentRoot[
                   edges[priorEdge].head
                   ] !=
                   certificate.componentRoot[
                   edges[priorEdge].target
                   ] ==>
                     certificate.componentRank[
                     certificate.componentRoot[
                     edges[priorEdge].head
                     ]
                     ] <
                     certificate.componentRank[
                     certificate.componentRoot[
                     edges[priorEdge].target
                     ]
                     ]) &&
                  (edges[priorEdge].head ==
                   edges[priorEdge].target ==>
                     certificate.selfLoopWitnessEdge[
                     certificate.componentRoot[
                     edges[priorEdge].head
                     ]
                     ] != -1) &&
                  (certificate.traversal[
                   edges[priorEdge].target
                   ] ==>
                     certificate.traversal[
                     edges[priorEdge].head
                     ])
      decreases |edges| - edgeIndex
    {
      edgeChecks := edgeChecks + 1;
      var edge := edges[edgeIndex];
      if edge.head >= nodeCount || edge.target >= nodeCount {
        return RoutingCertificateRejected(
            InvalidDependencyEdge,
            RoutingCertificateCounters(nodeChecks, edgeChecks)
          );
      }
      var headRoot := certificate.componentRoot[edge.head];
      var targetRoot := certificate.componentRoot[edge.target];
      assert ValidComponentRoot(
          nodeCount,
          certificate,
          edge.head
        );
      assert ValidComponentRoot(
          nodeCount,
          certificate,
          edge.target
        );
      assert headRoot < nodeCount;
      assert targetRoot < nodeCount;
      if (headRoot != targetRoot &&
          certificate.componentRank[headRoot] >=
          certificate.componentRank[targetRoot]) ||
         (edge.head == edge.target &&
          certificate.selfLoopWitnessEdge[headRoot] == -1) ||
         (certificate.traversal[edge.target] &&
          !certificate.traversal[edge.head]) {
        return RoutingCertificateRejected(
            InvalidDependencyEdge,
            RoutingCertificateCounters(nodeChecks, edgeChecks)
          );
      }
      edgeIndex := edgeIndex + 1;
    }
    assert ValidEdgeIndices(nodeCount, edges);

    node := 0;
    while node < nodeCount
      invariant node <= nodeCount
      invariant nodeChecks == nodeCount + node
      invariant edgeChecks == |edges|
      invariant ValidCertificateShape(nodeCount, certificate)
      invariant ValidEdgeIndices(nodeCount, edges)
      invariant forall checkedNode: nat | checkedNode < nodeCount ::
                  ValidComponentRoot(
                    nodeCount,
                    certificate,
                    checkedNode
                  ) &&
                  certificate.componentRank[checkedNode] <
                  nodeCount
      invariant forall checkedEdge: nat | checkedEdge < |edges| ::
                  var checked := edges[checkedEdge];
                  var checkedHeadRoot :=
                    certificate.componentRoot[checked.head];
                  var checkedTargetRoot :=
                    certificate.componentRoot[checked.target];
                  checkedHeadRoot < nodeCount &&
                  checkedTargetRoot < nodeCount &&
                  (checkedHeadRoot != checkedTargetRoot ==>
                     certificate.componentRank[checkedHeadRoot] <
                     certificate.componentRank[
                     checkedTargetRoot
                     ]) &&
                  (checked.head == checked.target ==>
                     certificate.selfLoopWitnessEdge[
                     checkedHeadRoot
                     ] != -1) &&
                  (certificate.traversal[checked.target] ==>
                     certificate.traversal[checked.head])
      invariant forall prior: nat | prior < node ::
                  ValidForwardParent(
                    nodeCount,
                    edges,
                    certificate,
                    prior
                  ) &&
                  ValidReverseParent(
                    nodeCount,
                    edges,
                    certificate,
                    prior
                  ) &&
                  ValidMultipleMemberWitness(
                    nodeCount,
                    certificate,
                    prior
                  ) &&
                  ValidSelfLoopWitness(
                    nodeCount,
                    edges,
                    certificate,
                    prior
                  ) &&
                  certificate.traversal[prior] ==
                  certificate.traversal[
                  certificate.componentRoot[prior]
                  ] &&
                  (CertificateComponentIsRecursive(
                     nodeCount,
                     certificate,
                     prior
                   ) ==>
                     certificate.traversal[prior]) &&
                  ValidTraversalWitness(
                    nodeCount,
                    edges,
                    certificate,
                    prior
                  )
      decreases nodeCount - node
    {
      nodeChecks := nodeChecks + 1;
      if !ValidForwardParent(
          nodeCount,
          edges,
          certificate,
          node
        ) ||
         !ValidReverseParent(
           nodeCount,
           edges,
           certificate,
           node
         ) ||
         !ValidMultipleMemberWitness(
           nodeCount,
           certificate,
           node
         ) ||
         !ValidSelfLoopWitness(
           nodeCount,
           edges,
           certificate,
           node
         ) ||
         certificate.traversal[node] !=
         certificate.traversal[
         certificate.componentRoot[node]
         ] ||
         (CertificateComponentIsRecursive(
            nodeCount,
            certificate,
            node
          ) &&
          !certificate.traversal[node]) ||
         !ValidTraversalWitness(
           nodeCount,
           edges,
           certificate,
           node
         ) {
        return RoutingCertificateRejected(
            InvalidComponentWitness,
            RoutingCertificateCounters(nodeChecks, edgeChecks)
          );
      }
      node := node + 1;
    }
    assert ValidRoutingCertificate(
        nodeCount,
        edges,
        certificate
      );
    AcceptedRoutingCertificateIsExact(
      nodeCount,
      edges,
      certificate
    );
    return RoutingCertificateAccepted(
        certificate.traversal,
        RoutingCertificateCounters(nodeChecks, edgeChecks)
      );
  }

  datatype RoutingDerivationCounters = RoutingDerivationCounters(
    pathChecks: nat,
    nodeChecks: nat,
    edgeChecks: nat
  )

  datatype RoutingDerivationDecision =
    | RoutingDerivationAccepted(
        traversal: seq<bool>,
        counters: RoutingDerivationCounters
      )
    | RoutingDerivationRejected(
        error: RoutingCertificateError,
        counters: RoutingDerivationCounters
      )

  method CheckRoutingCertificateFromPaths(
    nodeCount: nat,
    paths: seq<IndexedRoutingPath>,
    edges: seq<IndexedDependencyEdge>,
    certificate: RoutingProof
  ) returns (decision: RoutingDerivationDecision)
    ensures decision.RoutingDerivationAccepted? <==>
            ValidIndexedRoutingPaths(nodeCount, paths) &&
            edges == DerivedRoutingEdges(paths) &&
            ValidRoutingCertificate(
              nodeCount,
              edges,
              certificate
            )
    ensures decision.RoutingDerivationAccepted? ==>
              decision.traversal == certificate.traversal
    ensures decision.RoutingDerivationAccepted? ==>
              decision.counters.pathChecks == |paths|
    ensures decision.RoutingDerivationAccepted? ==>
              decision.counters.nodeChecks == 2 * nodeCount
    ensures decision.RoutingDerivationAccepted? ==>
              decision.counters.edgeChecks == |edges|
    ensures decision.counters.pathChecks <= |paths|
    ensures decision.counters.nodeChecks <= 2 * nodeCount
    ensures decision.counters.edgeChecks <= |edges|
    ensures decision.RoutingDerivationAccepted? ==>
              forall node: nat | node < nodeCount ::
                decision.traversal[node] <==>
                IndexedDependsOnRecursiveComponent(
                  nodeCount,
                  DerivedRoutingEdges(paths),
                  node
                )
  {
    var pathChecks: nat := 0;
    var pathIndex: nat := 0;
    var derivedEdgeIndex: nat := 0;
    while pathIndex < |paths|
      invariant pathIndex <= |paths|
      invariant pathChecks == pathIndex
      invariant derivedEdgeIndex <= |edges|
      invariant edges[..derivedEdgeIndex] +
                DerivedRoutingEdges(paths[pathIndex..]) ==
                DerivedRoutingEdges(paths)
      invariant forall prior: nat | prior < pathIndex ::
                  ValidIndexedRoutingPath(nodeCount, paths[prior])
      decreases |paths| - pathIndex
    {
      pathChecks := pathChecks + 1;
      var path := paths[pathIndex];
      match path {
        case IndexedDirectRelation(head) =>
          if head >= nodeCount {
            return RoutingDerivationRejected(
                InvalidRoutingPath,
                RoutingDerivationCounters(pathChecks, 0, 0)
              );
          }
        case IndexedSelfPermission(head, target) =>
          if head >= nodeCount || target >= nodeCount {
            return RoutingDerivationRejected(
                InvalidRoutingPath,
                RoutingDerivationCounters(pathChecks, 0, 0)
              );
          }
          if derivedEdgeIndex >= |edges| ||
             edges[derivedEdgeIndex] !=
             IndexedDependencyEdge(head, target) {
            return RoutingDerivationRejected(
                RoutingPathEdgeMismatch,
                RoutingDerivationCounters(pathChecks, 0, 0)
              );
          }
          derivedEdgeIndex := derivedEdgeIndex + 1;
        case IndexedArrowRelation(head) =>
          if head >= nodeCount {
            return RoutingDerivationRejected(
                InvalidRoutingPath,
                RoutingDerivationCounters(pathChecks, 0, 0)
              );
          }
        case IndexedArrowPermission(head, target) =>
          if head >= nodeCount || target >= nodeCount {
            return RoutingDerivationRejected(
                InvalidRoutingPath,
                RoutingDerivationCounters(pathChecks, 0, 0)
              );
          }
          if derivedEdgeIndex >= |edges| ||
             edges[derivedEdgeIndex] !=
             IndexedDependencyEdge(head, target) {
            return RoutingDerivationRejected(
                RoutingPathEdgeMismatch,
                RoutingDerivationCounters(pathChecks, 0, 0)
              );
          }
          derivedEdgeIndex := derivedEdgeIndex + 1;
      }
      pathIndex := pathIndex + 1;
    }
    if derivedEdgeIndex != |edges| {
      return RoutingDerivationRejected(
          RoutingPathEdgeMismatch,
          RoutingDerivationCounters(pathChecks, 0, 0)
        );
    }
    assert edges == DerivedRoutingEdges(paths);
    assert ValidIndexedRoutingPaths(nodeCount, paths);

    var checked := CheckRoutingCertificate(
      nodeCount,
      edges,
      certificate
    );
    if checked.RoutingCertificateAccepted? {
      return RoutingDerivationAccepted(
          checked.traversal,
          RoutingDerivationCounters(
            pathChecks,
            checked.counters.nodeChecks,
            checked.counters.edgeChecks
          )
        );
    }
    return RoutingDerivationRejected(
        checked.error,
        RoutingDerivationCounters(
          pathChecks,
          checked.counters.nodeChecks,
          checked.counters.edgeChecks
        )
      );
  }
}
