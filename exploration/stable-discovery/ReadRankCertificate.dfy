// A small proof-carrying certificate for static remaining storage-read cost.
// The compiler may generate the certificate by any deterministic algorithm;
// the trusted checker needs only local edge inequalities and one well-founded
// witness edge per non-root node.
module ReadRankCertificate {
  datatype Edge = Edge(from: nat, to: nat, cost: nat)

  datatype Graph = Graph(
    nodeCount: nat,
    root: nat,
    edges: seq<Edge>
  )

  datatype Certificate = Certificate(
    distance: seq<nat>,
    witnessEdge: seq<nat>,
    hops: seq<nat>
  )

  predicate ValidGraph(graph: Graph) {
    graph.root < graph.nodeCount &&
    forall index | 0 <= index < |graph.edges| ::
      var edge := graph.edges[index];
      edge.from < graph.nodeCount &&
      edge.to < graph.nodeCount &&
      edge.cost <= 1
  }

  predicate ValidCertificate(
    graph: Graph,
    certificate: Certificate
  ) {
    ValidGraph(graph) &&
    |certificate.distance| == graph.nodeCount &&
    |certificate.witnessEdge| == graph.nodeCount &&
    |certificate.hops| == graph.nodeCount &&
    certificate.distance[graph.root] == 0 &&
    certificate.witnessEdge[graph.root] == |graph.edges| &&
    certificate.hops[graph.root] == 0 &&
    (forall node: nat | node < graph.nodeCount ::
      certificate.distance[node] < graph.nodeCount &&
      certificate.hops[node] < graph.nodeCount) &&
    (forall node: nat | node < graph.nodeCount ::
      (certificate.hops[node] == 0 <==> node == graph.root)) &&
    (forall node: nat | node < graph.nodeCount && node != graph.root ::
      certificate.witnessEdge[node] < |graph.edges| &&
      graph.edges[certificate.witnessEdge[node]].from == node &&
      certificate.hops[
        graph.edges[certificate.witnessEdge[node]].to
      ] < certificate.hops[node] &&
      certificate.distance[node] ==
        graph.edges[certificate.witnessEdge[node]].cost +
        certificate.distance[
          graph.edges[certificate.witnessEdge[node]].to
        ]) &&
    (forall index | 0 <= index < |graph.edges| ::
      var edge := graph.edges[index];
      certificate.distance[edge.from] <=
        edge.cost + certificate.distance[edge.to])
  }

  function WitnessEdge(
    graph: Graph,
    certificate: Certificate,
    node: nat
  ): Edge
    requires ValidCertificate(graph, certificate)
    requires node < graph.nodeCount
    requires node != graph.root
  {
    graph.edges[certificate.witnessEdge[node]]
  }

  function WitnessPath(
    graph: Graph,
    certificate: Certificate,
    node: nat
  ): seq<nat>
    requires ValidCertificate(graph, certificate)
    requires node < graph.nodeCount
    decreases certificate.hops[node]
  {
    if node == graph.root then [node]
    else [node] + WitnessPath(
                    graph,
                    certificate,
                    WitnessEdge(graph, certificate, node).to
                  )
  }

  function WitnessPathCost(
    graph: Graph,
    certificate: Certificate,
    node: nat
  ): nat
    requires ValidCertificate(graph, certificate)
    requires node < graph.nodeCount
    decreases certificate.hops[node]
  {
    if node == graph.root then 0
    else WitnessEdge(graph, certificate, node).cost +
         WitnessPathCost(
           graph,
           certificate,
           WitnessEdge(graph, certificate, node).to
         )
  }

  lemma WitnessEndsAtRoot(
    graph: Graph,
    certificate: Certificate,
    node: nat
  )
    requires ValidCertificate(graph, certificate)
    requires node < graph.nodeCount
    ensures |WitnessPath(graph, certificate, node)| > 0
    ensures WitnessPath(graph, certificate, node)[0] == node
    ensures WitnessPath(graph, certificate, node)[
              |WitnessPath(graph, certificate, node)| - 1
            ] == graph.root
    decreases certificate.hops[node]
  {
    if node != graph.root {
      WitnessEndsAtRoot(
        graph, certificate, WitnessEdge(graph, certificate, node).to
      );
    }
  }

  lemma WitnessHasCertifiedCost(
    graph: Graph,
    certificate: Certificate,
    node: nat
  )
    requires ValidCertificate(graph, certificate)
    requires node < graph.nodeCount
    ensures WitnessPathCost(graph, certificate, node) ==
            certificate.distance[node]
    decreases certificate.hops[node]
  {
    if node != graph.root {
      WitnessHasCertifiedCost(
        graph, certificate, WitnessEdge(graph, certificate, node).to
      );
    }
  }

  predicate ValidEdgePath(
    graph: Graph,
    start: nat,
    path: seq<Edge>
  )
    decreases |path|
  {
    if |path| == 0 then start == graph.root
    else path[0] in graph.edges &&
         path[0].from == start &&
         ValidEdgePath(graph, path[0].to, path[1..])
  }

  function EdgePathCost(path: seq<Edge>): nat
    decreases |path|
  {
    if |path| == 0 then 0
    else path[0].cost + EdgePathCost(path[1..])
  }

  lemma CertifiedDistanceLowerBoundsEveryRootPath(
    graph: Graph,
    certificate: Certificate,
    start: nat,
    path: seq<Edge>
  )
    requires ValidCertificate(graph, certificate)
    requires start < graph.nodeCount
    requires ValidEdgePath(graph, start, path)
    ensures certificate.distance[start] <= EdgePathCost(path)
    decreases |path|
  {
    if |path| == 0 {
      assert start == graph.root;
    } else {
      var edge := path[0];
      var edgeIndex :| 0 <= edgeIndex < |graph.edges| &&
                       graph.edges[edgeIndex] == edge;
      assert certificate.distance[edge.from] <=
             edge.cost + certificate.distance[edge.to];
      CertifiedDistanceLowerBoundsEveryRootPath(
        graph, certificate, edge.to, path[1..]
      );
    }
  }

  lemma CertificateEstablishesShortestReadDistance(
    graph: Graph,
    certificate: Certificate,
    node: nat
  )
    requires ValidCertificate(graph, certificate)
    requires node < graph.nodeCount
    ensures WitnessPathCost(graph, certificate, node) ==
            certificate.distance[node]
    ensures forall path: seq<Edge> |
              ValidEdgePath(graph, node, path) ::
      certificate.distance[node] <= EdgePathCost(path)
  {
    WitnessEndsAtRoot(graph, certificate, node);
    WitnessHasCertifiedCost(graph, certificate, node);
    forall path: seq<Edge> |
      ValidEdgePath(graph, node, path)
      ensures certificate.distance[node] <= EdgePathCost(path)
    {
      CertifiedDistanceLowerBoundsEveryRootPath(
        graph, certificate, node, path
      );
    }
  }

  function SeedRank(
    certificate: Certificate,
    head: nat,
    seedReadCost: nat
  ): nat
    requires head < |certificate.distance|
  {
    seedReadCost + certificate.distance[head]
  }

  lemma SeedRankIsExactForItsWitness(
    graph: Graph,
    certificate: Certificate,
    head: nat,
    seedReadCost: nat
  )
    requires ValidCertificate(graph, certificate)
    requires head < graph.nodeCount
    ensures SeedRank(certificate, head, seedReadCost) ==
            seedReadCost +
            WitnessPathCost(graph, certificate, head)
  {
    WitnessHasCertifiedCost(graph, certificate, head);
  }
}
