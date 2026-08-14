// Exploratory identity proof; source adapters must refine these exact fields.
module DescriptorIdentity {
  datatype Position = Start | After(value: nat)

  datatype PhysicalRange = PhysicalRange(
    backend: nat,
    database: nat,
    basis: nat,
    operation: nat,
    index: nat,
    lowerBound: nat,
    upperBound: nat,
    position: Position,
    projection: nat,
    limit: nat,
    chunkAbi: nat
  )

  datatype SemanticContinuation = SemanticContinuation(
    rule: nat,
    consequence: nat,
    occurrence: nat
  )

  datatype LogicalScan = LogicalScan(
    physicalRange: PhysicalRange,
    continuation: SemanticContinuation
  )

  datatype TransportAttempt = TransportAttempt(
    requestId: nat,
    physicalRange: PhysicalRange
  )

  function PhysicalKey(scan: LogicalScan): PhysicalRange {
    scan.physicalRange
  }

  lemma DifferentRangePositionCannotCoalesce(
    left: LogicalScan,
    right: LogicalScan
  )
    requires left.physicalRange.position !=
             right.physicalRange.position
    ensures PhysicalKey(left) != PhysicalKey(right)
  {
  }

  lemma DifferentSemanticContinuationsCanReuseOnePhysicalChunk(
    range: PhysicalRange,
    left: SemanticContinuation,
    right: SemanticContinuation
  )
    requires left != right
    ensures LogicalScan(range, left) != LogicalScan(range, right)
    ensures PhysicalKey(LogicalScan(range, left)) ==
            PhysicalKey(LogicalScan(range, right))
  {
  }

  lemma PhysicalKeyEqualityIsExactRangeEquality(
    left: LogicalScan,
    right: LogicalScan
  )
    ensures PhysicalKey(left) == PhysicalKey(right) <==>
            left.physicalRange == right.physicalRange
  {
  }

  lemma TransportRequestIdDoesNotChangePhysicalIdentity(
    range: PhysicalRange,
    leftRequest: nat,
    rightRequest: nat
  )
    ensures TransportAttempt(leftRequest, range).physicalRange ==
            TransportAttempt(rightRequest, range).physicalRange
  {
  }
}
