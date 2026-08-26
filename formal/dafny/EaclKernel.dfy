include "Semantics.dfy"
include "SignedDependencyStratification.dfy"
include "OperatorGeneratedPolicy.dfy"
include "OperatorRecursiveGeneratedPolicy.dfy"
include "AcyclicEngine.dfy"
include "ConsistencyDecision.dfy"
include "CurrentCache.dfy"
include "IndexedTraversal.dfy"
include "IndexedBatching.dfy"
include "IndexedBatchCompleteness.dfy"
include "IndexedCertification.dfy"
include "IndexedRootDenotation.dfy"
include "OrderedMerge.dfy"
include "PageWindow.dfy"
include "RecursiveEngine.dfy"
include "RootDenotation.dfy"
include "RoutingCertificate.dfy"
include "SubproblemCache.dfy"
include "TemporalSafety.dfy"
include "WireFormat.dfy"

module EaclKernel {
  import Semantics
  import AcyclicEngine
  import OrderedMerge
  import TemporalSafety
  import WireFormat
  import OperatorGeneratedPolicy
  import OperatorRecursiveGeneratedPolicy
  import opened Signed = SignedDependencyStratification

  const WireVersion: string := "eacl.round-trip/v1"

  datatype WireError =
    | UnknownTag(tag: string)
    | TooManyValues(actualCount: nat, limit: nat)
    | NegativeValue(index: nat)

  datatype WireResult =
    | Accepted(items: seq<int>)
    | Rejected(error: WireError)

  datatype OperatorStrategy =
    | OperatorEmpty
    | OperatorDensePrefix
    | OperatorSparseExact

  datatype OperatorDecision = OperatorDecision(
    strategy: OperatorStrategy,
    spanValid: bool,
    inclusiveSpan: nat,
    initialWidth: nat,
    grownWidth: nat,
    logicalCandidates: nat,
    physicalCandidates: nat,
    physicalOverread: nat
  )

  datatype OperatorEdgeSign = OperatorPositive | OperatorNegative

  datatype OperatorDependencyEdge = OperatorDependencyEdge(
    source: nat,
    target: nat,
    sign: OperatorEdgeSign
  )

  datatype OperatorSignedGraphDecision =
    | OperatorSignedGraphAccepted
    | OperatorInvalidComponentCertificate
    | OperatorNonCanonicalEdgeSequence
    | OperatorNegativeCycle(
        edgeIndex: nat,
        source: nat,
        target: nat
      )

  function ToSignedEdge(
    edge: OperatorDependencyEdge
  ): Signed.DependencyEdge {
    Signed.DependencyEdge(
      edge.source,
      edge.target,
      if edge.sign.OperatorPositive? then Signed.Positive else Signed.Negative
    )
  }

  function ToSignedEdges(
    edges: seq<OperatorDependencyEdge>
  ): seq<Signed.DependencyEdge>
    ensures |ToSignedEdges(edges)| == |edges|
    decreases |edges|
  {
    if |edges| == 0 then
      []
    else
      [ToSignedEdge(edges[0])] + ToSignedEdges(edges[1..])
  }

  lemma ToSignedEdgesPreservesEveryIndexedEdge(
    edges: seq<OperatorDependencyEdge>,
    index: nat
  )
    requires index < |edges|
    ensures ToSignedEdges(edges)[index].source == edges[index].source
    ensures ToSignedEdges(edges)[index].target == edges[index].target
    ensures ToSignedEdges(edges)[index].sign.Negative? <==>
            edges[index].sign.OperatorNegative?
    decreases index
  {
    if index != 0 {
      ToSignedEdgesPreservesEveryIndexedEdge(edges[1..], index - 1);
    }
  }

  ghost predicate ValidItems(items: seq<int>)
  {
    forall i | 0 <= i < |items| :: 0 <= items[i]
  }

  method RoundTrip(tag: string, items: seq<int>, maxItems: nat)
    returns (result: WireResult)
    ensures result.Accepted? <==>
            tag == WireVersion && |items| <= maxItems && ValidItems(items)
    ensures result.Accepted? ==> result.items == items
    ensures result.Rejected? <==>
            tag != WireVersion || |items| > maxItems || !ValidItems(items)
  {
    if tag != WireVersion {
      return Rejected(UnknownTag(tag));
    }

    if |items| > maxItems {
      return Rejected(TooManyValues(|items|, maxItems));
    }

    var i := 0;
    while i < |items|
      invariant 0 <= i <= |items|
      invariant forall j | 0 <= j < i :: 0 <= items[j]
      decreases |items| - i
    {
      if items[i] < 0 {
        return Rejected(NegativeValue(i));
      }
      i := i + 1;
    }

    return Accepted(items);
  }

  // Small generated boundary used by both runtimes.  It exports only the
  // abstract policy choices and dimensional counters, not an evaluator.
  method DecideOperatorBatch(
    candidateCount: nat,
    firstEid: nat,
    lastEid: nat,
    maximumRepresentableSpan: nat,
    densityMultiplier: nat,
    demand: nat,
    physicalCap: nat,
    candidateWindow: nat,
    previousWidth: nat,
    physicalDecisions: seq<bool>
  ) returns (decision: OperatorDecision)
    ensures decision.logicalCandidates <= decision.physicalCandidates
    ensures decision.physicalCandidates == |physicalDecisions|
    ensures decision.physicalOverread ==
            decision.physicalCandidates - decision.logicalCandidates
    ensures decision.initialWidth <= demand
    ensures decision.initialWidth <= physicalCap
    ensures decision.initialWidth <= candidateWindow
    ensures decision.grownWidth <= 2 * previousWidth
    ensures decision.grownWidth <= physicalCap
    ensures decision.grownWidth <= candidateWindow
  {
    var checkedSpan := OperatorGeneratedPolicy.CheckedInclusiveSpan(
      firstEid,
      lastEid,
      maximumRepresentableSpan
    );
    var selected := OperatorGeneratedPolicy.SelectStrategy(
      candidateCount,
      checkedSpan,
      densityMultiplier
    );
    var strategy :=
      if selected.EmptyBatch? then
        OperatorEmpty
      else if selected.DensePrefix? then
        OperatorDensePrefix
      else
        OperatorSparseExact;
    var logicalCandidates := OperatorGeneratedPolicy.PrefixForDemand(
      physicalDecisions,
      demand
    );
    decision := OperatorDecision(
      strategy,
      checkedSpan.CheckedSpan?,
      if checkedSpan.CheckedSpan? then checkedSpan.span else 0,
      OperatorGeneratedPolicy.InitialWidth(
        demand,
        physicalCap,
        candidateWindow
      ),
      OperatorGeneratedPolicy.GrownWidth(
        previousWidth,
        physicalCap,
        candidateWindow
      ),
      logicalCandidates,
      |physicalDecisions|,
      |physicalDecisions| - logicalCandidates
    );
    OperatorGeneratedPolicy.InitialWidthRespectsEveryBound(
      demand,
      physicalCap,
      candidateWindow
    );
    OperatorGeneratedPolicy.GrownWidthIsBoundedDoubling(
      previousWidth,
      physicalCap,
      candidateWindow
    );
  }

  method DecideOperatorSignedGraph(
    vertices: seq<nat>,
    edges: seq<OperatorDependencyEdge>,
    components: seq<seq<nat>>
  ) returns (decision: OperatorSignedGraphDecision)
    ensures decision.OperatorSignedGraphAccepted? <==>
            var signedEdges := ToSignedEdges(edges);
            Signed.CanonicalEdgeSequence(signedEdges) &&
            Signed.ExecutableCertificateValid(
              vertices,
              signedEdges,
              components
            ) &&
            (forall edge <- signedEdges ::
               edge.sign.Positive? ||
               !Signed.SameCertifiedComponent(
                 components,
                 edge.source,
                 edge.target
               ))
    ensures decision.OperatorNonCanonicalEdgeSequence? <==>
            !Signed.CanonicalEdgeSequence(ToSignedEdges(edges))
    ensures decision.OperatorInvalidComponentCertificate? <==>
            Signed.CanonicalEdgeSequence(ToSignedEdges(edges)) &&
            !Signed.ExecutableCertificateValid(
              vertices,
              ToSignedEdges(edges),
              components
            )
    ensures decision.OperatorNegativeCycle? ==>
              decision.edgeIndex < |edges| &&
              edges[decision.edgeIndex].sign.OperatorNegative? &&
              edges[decision.edgeIndex].source == decision.source &&
              edges[decision.edgeIndex].target == decision.target &&
              Signed.SameCertifiedComponent(
                components,
                decision.source,
                decision.target
              )
    ensures decision.OperatorSignedGraphAccepted? ==>
              forall edge <- ToSignedEdges(edges) ::
                edge.sign.Positive? ||
                !Signed.Reachable(
                  ToSignedEdges(edges),
                  edge.target,
                  edge.source
                )
  {
    var signedEdges := ToSignedEdges(edges);
    var outcome := Signed.ValidateCertifiedSignedGraph(
      vertices,
      signedEdges,
      components
    );
    Signed.CertifiedValidationStatusPrecedence(
      vertices,
      signedEdges,
      components
    );
    Signed.CertifiedValidationAcceptsIffCertificateCanonicalAndNoInternalNegative(
      vertices,
      signedEdges,
      components
    );
    if Signed.CanonicalEdgeSequence(signedEdges) &&
       Signed.ExecutableCertificateValid(vertices, signedEdges, components) {
      Signed.CertifiedNegativeDiagnosticIsFirstInCanonicalOrder(
        vertices,
        signedEdges,
        components
      );
      Signed.CertifiedValidationAcceptsIffNoNegativeFinitePathCycle(
        vertices,
        signedEdges,
        components
      );
    }
    decision :=
      match outcome
      case SignedGraphAccepted => OperatorSignedGraphAccepted
      case InvalidComponentCertificate =>
        OperatorInvalidComponentCertificate
      case NonCanonicalEdgeSequence =>
        OperatorNonCanonicalEdgeSequence
      case NegativeCycle(edgeIndex, source, target) =>
        OperatorNegativeCycle(edgeIndex, source, target);
    if decision.OperatorNegativeCycle? {
      ToSignedEdgesPreservesEveryIndexedEdge(edges, decision.edgeIndex);
    }
  }

  method DecideOperatorRecursiveCommand(
    state: OperatorRecursiveGeneratedPolicy.RecursiveState,
    positiveRules: seq<OperatorRecursiveGeneratedPolicy.PositiveRule>,
    positiveEdges: seq<OperatorRecursiveGeneratedPolicy.PositiveConsumerEdge>,
    strata: seq<OperatorRecursiveGeneratedPolicy.ExpressionStratum>,
    exclusions: seq<OperatorRecursiveGeneratedPolicy.ExclusionRule>,
    command: OperatorRecursiveGeneratedPolicy.RecursiveCommand
  ) returns (
      transition: OperatorRecursiveGeneratedPolicy.RecursiveTransition
    )
    ensures transition ==
            OperatorRecursiveGeneratedPolicy.ApplyCommand(
              state,
              positiveRules,
              positiveEdges,
              strata,
              exclusions,
              command
            )
  {
    transition := OperatorRecursiveGeneratedPolicy.ApplyCommand(
      state,
      positiveRules,
      positiveEdges,
      strata,
      exclusions,
      command
    );
  }
}
