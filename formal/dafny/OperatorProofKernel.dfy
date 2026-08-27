include "PermissionSetAlgebra.dfy"
include "SignedDependencyStratification.dfy"
include "CandidateCover.dfy"
include "WitnessPredicate.dfy"
include "VectorPredicate.dfy"
include "AdaptiveBatching.dfy"
include "OperatorLeastPath.dfy"
include "SeekableSetKernels.dfy"
include "DensityBoundedBatch.dfy"
include "AnchorGatedConjunction.dfy"
include "StratifiedExclusion.dfy"
include "OperatorCacheRefinement.dfy"
include "ExpressionPlanRefinement.dfy"
include "OperatorGeneratedPolicyRefinement.dfy"
include "OperatorRecursiveGeneratedPolicyRefinement.dfy"

// Aggregate include boundary for abstract operator verification.  It is
// intentionally separate from EaclKernel so proof-only executable functions
// cannot inflate the generated Java/JavaScript authority surface.
module OperatorProofKernel {
  import PermissionSetAlgebra
  import SignedDependencyStratification
  import CandidateCover
  import WitnessPredicate
  import VectorPredicate
  import AdaptiveBatching
  import OperatorLeastPath
  import SeekableSetKernels
  import DensityBoundedBatch
  import AnchorGatedConjunction
  import StratifiedExclusion
  import OperatorCacheRefinement
  import ExpressionPlanRefinement
  import OperatorGeneratedPolicy
  import OperatorGeneratedPolicyRefinement
  import OperatorRecursiveGeneratedPolicyRefinement

  lemma GeneratedBatchPolicyIsInsideTheAbstractProofClosure(
    demand: nat,
    physicalCap: nat,
    candidateWindow: nat,
    previousWidth: nat
  )
    ensures OperatorGeneratedPolicy.InitialWidth(
              demand,
              physicalCap,
              candidateWindow
            ) ==
            AdaptiveBatching.InitialWidth(
              demand,
              physicalCap,
              candidateWindow
            )
    ensures OperatorGeneratedPolicy.GrownWidth(
              previousWidth,
              physicalCap,
              candidateWindow
            ) ==
            AdaptiveBatching.GrownWidth(
              previousWidth,
              physicalCap,
              candidateWindow
            )
  {
    OperatorGeneratedPolicyRefinement.GeneratedWidthsEqualAbstractWidths(
      demand,
      physicalCap,
      candidateWindow,
      previousWidth
    );
  }
}
