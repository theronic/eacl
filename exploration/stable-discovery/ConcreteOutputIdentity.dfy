// Exploratory proof model; intentionally excluded from release artifacts.
module ConcreteOutputIdentity {
  datatype PermissionNode = PermissionNode(
    resourceType: string,
    permissionName: string
  )

  datatype ForwardGrantKey = ForwardGrantKey(
    node: PermissionNode,
    resourceEid: int
  )

  datatype ReverseGrantKey = ReverseGrantKey(
    node: PermissionNode,
    resourceEid: int,
    subjectType: string,
    subjectEid: int
  )

  datatype ForwardWork =
    | ForwardNonResult(id: nat)
    | ForwardGrantWork(grant: ForwardGrantKey)

  datatype ReverseWork =
    | ReverseNonResult(id: nat)
    | ReverseGrantWork(grant: ReverseGrantKey)

  datatype MaybeResult = NoResult | Result(eid: int)

  function ForwardOutput(
    rootNode: PermissionNode,
    work: ForwardWork
  ): MaybeResult {
    match work
    case ForwardNonResult(_) => NoResult
    case ForwardGrantWork(grant) =>
      if grant.node == rootNode
      then Result(grant.resourceEid)
      else NoResult
  }

  function ReverseOutput(
    rootNode: PermissionNode,
    rootResourceEid: int,
    resultType: string,
    work: ReverseWork
  ): MaybeResult {
    match work
    case ReverseNonResult(_) => NoResult
    case ReverseGrantWork(grant) =>
      if grant.node == rootNode &&
         grant.resourceEid == rootResourceEid &&
         grant.subjectType == resultType
      then Result(grant.subjectEid)
      else NoResult
  }

  lemma ForwardRootOutputIsInjective(
    rootNode: PermissionNode,
    left: ForwardWork,
    right: ForwardWork
  )
    requires ForwardOutput(rootNode, left).Result?
    requires ForwardOutput(rootNode, right).Result?
    requires ForwardOutput(rootNode, left).eid ==
             ForwardOutput(rootNode, right).eid
    ensures left == right
  {
  }

  lemma ReverseRootOutputIsInjective(
    rootNode: PermissionNode,
    rootResourceEid: int,
    resultType: string,
    left: ReverseWork,
    right: ReverseWork
  )
    requires ReverseOutput(
               rootNode,
               rootResourceEid,
               resultType,
               left
             ).Result?
    requires ReverseOutput(
               rootNode,
               rootResourceEid,
               resultType,
               right
             ).Result?
    requires ReverseOutput(
               rootNode,
               rootResourceEid,
               resultType,
               left
             ).eid ==
             ReverseOutput(
               rootNode,
               rootResourceEid,
               resultType,
               right
             ).eid
    ensures left == right
  {
  }
}
