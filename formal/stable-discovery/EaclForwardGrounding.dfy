// Exact finite grounding of EACL's four positive forward rule forms.
// This proves why forward enumeration is graph reachability rather than an
// N-way merge or a dynamic grant/consumer join.
module EaclForwardGrounding {
  datatype Grant = Grant(node: nat, resource: nat)

  datatype Relationship = Relationship(
    relation: nat,
    subject: nat,
    resource: nat
  )

  datatype SubjectAdmission = SubjectAdmission(
    relation: nat,
    subjectType: nat
  )

  datatype Rule =
    | Direct(head: nat, relation: nat)
    | SelfPermission(head: nat, target: nat)
    | ArrowRelation(head: nat, via: nat, targetRelation: nat)
    | ArrowPermission(head: nat, via: nat, target: nat)

  datatype Edge = Edge(body: Grant, head: Grant)

  datatype Program = Program(
    nodeCount: nat,
    entityCount: nat,
    relationCount: nat,
    nodeTypes: seq<nat>,
    entityTypes: seq<nat>,
    relationResourceTypes: seq<nat>,
    allowedSubjects: set<SubjectAdmission>,
    relationships: set<Relationship>,
    rules: set<Rule>
  )

  ghost predicate ValidGrant(program: Program, grant: Grant) {
    grant.node < program.nodeCount &&
    grant.node < |program.nodeTypes| &&
    grant.resource < program.entityCount &&
    grant.resource < |program.entityTypes| &&
    program.nodeTypes[grant.node] ==
      program.entityTypes[grant.resource]
  }

  ghost predicate ValidRelationship(
    program: Program,
    relationship: Relationship
  ) {
    relationship.relation < program.relationCount &&
    relationship.relation < |program.relationResourceTypes| &&
    relationship.subject < program.entityCount &&
    relationship.subject < |program.entityTypes| &&
    relationship.resource < program.entityCount &&
    relationship.resource < |program.entityTypes| &&
    program.entityTypes[relationship.resource] ==
      program.relationResourceTypes[relationship.relation] &&
    SubjectAdmission(
      relationship.relation,
      program.entityTypes[relationship.subject]
    ) in program.allowedSubjects
  }

  ghost predicate ValidRule(program: Program, rule: Rule) {
    match rule
    case Direct(head, relation) =>
      head < program.nodeCount &&
      head < |program.nodeTypes| &&
      relation < program.relationCount &&
      relation < |program.relationResourceTypes| &&
      program.nodeTypes[head] ==
        program.relationResourceTypes[relation]
    case SelfPermission(head, target) =>
      head < program.nodeCount && head < |program.nodeTypes| &&
      target < program.nodeCount && target < |program.nodeTypes| &&
      program.nodeTypes[head] == program.nodeTypes[target]
    case ArrowRelation(head, via, targetRelation) =>
      head < program.nodeCount &&
      head < |program.nodeTypes| &&
      via < program.relationCount &&
      via < |program.relationResourceTypes| &&
      targetRelation < program.relationCount &&
      targetRelation < |program.relationResourceTypes| &&
      program.nodeTypes[head] ==
        program.relationResourceTypes[via] &&
      SubjectAdmission(
        via,
        program.relationResourceTypes[targetRelation]
      ) in program.allowedSubjects
    case ArrowPermission(head, via, target) =>
      head < program.nodeCount && head < |program.nodeTypes| &&
      target < program.nodeCount && target < |program.nodeTypes| &&
      via < program.relationCount &&
      via < |program.relationResourceTypes| &&
      program.nodeTypes[head] ==
        program.relationResourceTypes[via] &&
      SubjectAdmission(
        via,
        program.nodeTypes[target]
      ) in program.allowedSubjects
  }

  ghost predicate ValidProgram(program: Program) {
    |program.nodeTypes| == program.nodeCount &&
    |program.entityTypes| == program.entityCount &&
    |program.relationResourceTypes| == program.relationCount &&
    (forall admission | admission in program.allowedSubjects ::
      admission.relation < program.relationCount) &&
    (forall relationship | relationship in program.relationships ::
      ValidRelationship(program, relationship))
    &&
    (forall rule | rule in program.rules :: ValidRule(program, rule))
  }

  ghost function BaseGrants(
    program: Program,
    principal: nat
  ): iset<Grant> {
    iset grant: Grant |
      ValidGrant(program, grant) &&
      ((exists relation: nat ::
          Direct(grant.node, relation) in program.rules &&
          Relationship(
            relation, principal, grant.resource
          ) in program.relationships)
       ||
       (exists via: nat, targetRelation: nat, intermediate: nat ::
          ArrowRelation(
            grant.node, via, targetRelation
          ) in program.rules &&
          Relationship(
            targetRelation, principal, intermediate
          ) in program.relationships &&
          Relationship(
            via, intermediate, grant.resource
          ) in program.relationships))
  }

  ghost function GroundEdges(program: Program): iset<Edge> {
    iset edge: Edge |
      ValidGrant(program, edge.body) &&
      ValidGrant(program, edge.head) &&
      ((exists head: nat, target: nat, resource: nat ::
          SelfPermission(head, target) in program.rules &&
          edge.body == Grant(target, resource) &&
          edge.head == Grant(head, resource))
       ||
       (exists head: nat, via: nat, target: nat,
               intermediate: nat, resource: nat ::
          ArrowPermission(head, via, target) in program.rules &&
          Relationship(
            via, intermediate, resource
          ) in program.relationships &&
          edge.body == Grant(target, intermediate) &&
          edge.head == Grant(head, resource)))
  }

  ghost predicate EaclConsequence(
    program: Program,
    known: iset<Grant>,
    grant: Grant
  ) {
      ValidGrant(program, grant) &&
      ((exists target: nat ::
          SelfPermission(grant.node, target) in program.rules &&
          ValidGrant(program, Grant(target, grant.resource)) &&
          Grant(target, grant.resource) in known)
     ||
     (exists via: nat, target: nat, intermediate: nat ::
        ArrowPermission(grant.node, via, target) in program.rules &&
        Relationship(
          via, intermediate, grant.resource
        ) in program.relationships &&
        ValidGrant(program, Grant(target, intermediate)) &&
        Grant(target, intermediate) in known))
  }

  ghost predicate GroundConsequence(
    program: Program,
    known: iset<Grant>,
    grant: Grant
  ) {
    exists edge: Edge ::
      edge in GroundEdges(program) &&
      edge.body in known &&
      edge.head == grant
  }

  ghost function EaclImmediate(
    program: Program,
    principal: nat,
    known: iset<Grant>
  ): iset<Grant> {
    BaseGrants(program, principal) +
    iset grant: Grant | EaclConsequence(program, known, grant)
  }

  ghost function GroundImmediate(
    program: Program,
    principal: nat,
    known: iset<Grant>
  ): iset<Grant> {
    BaseGrants(program, principal) +
    iset grant: Grant | GroundConsequence(program, known, grant)
  }

  lemma ConsequenceGroundingExact(
    program: Program,
    known: iset<Grant>,
    grant: Grant
  )
    requires ValidProgram(program)
    ensures EaclConsequence(program, known, grant) <==>
            GroundConsequence(program, known, grant)
  {
    if EaclConsequence(program, known, grant) {
      if exists target: nat ::
           SelfPermission(grant.node, target) in program.rules &&
           ValidGrant(program, Grant(target, grant.resource)) &&
           Grant(target, grant.resource) in known {
        var target: nat :|
          SelfPermission(grant.node, target) in program.rules &&
          ValidGrant(program, Grant(target, grant.resource)) &&
          Grant(target, grant.resource) in known;
        var edge := Edge(
          Grant(target, grant.resource), grant
        );
        assert ValidRule(
                 program,
                 SelfPermission(grant.node, target)
               );
        assert ValidGrant(program, edge.body);
        assert edge in GroundEdges(program);
      } else {
        var via: nat, target: nat, intermediate: nat :|
          ArrowPermission(
            grant.node, via, target
          ) in program.rules &&
          Relationship(
            via, intermediate, grant.resource
          ) in program.relationships &&
          ValidGrant(program, Grant(target, intermediate)) &&
          Grant(target, intermediate) in known;
        var edge := Edge(
          Grant(target, intermediate), grant
        );
        assert ValidRule(
                 program,
                 ArrowPermission(grant.node, via, target)
               );
        assert ValidRelationship(
                 program,
                 Relationship(via, intermediate, grant.resource)
               );
        assert ValidGrant(program, edge.body);
        assert edge in GroundEdges(program);
      }
    } else if GroundConsequence(program, known, grant) {
      var edge: Edge :|
        edge in GroundEdges(program) &&
        edge.body in known &&
        edge.head == grant;
      if exists head: nat, target: nat, resource: nat ::
           SelfPermission(head, target) in program.rules &&
           edge.body == Grant(target, resource) &&
           edge.head == Grant(head, resource) {
        var head: nat, target: nat, resource: nat :|
          SelfPermission(head, target) in program.rules &&
          edge.body == Grant(target, resource) &&
          edge.head == Grant(head, resource);
      } else {
        var head: nat, via: nat, target: nat,
            intermediate: nat, resource: nat :|
          ArrowPermission(head, via, target) in program.rules &&
          Relationship(
            via, intermediate, resource
          ) in program.relationships &&
          edge.body == Grant(target, intermediate) &&
          edge.head == Grant(head, resource);
      }
    }
  }

  lemma ImmediateGroundingExact(
    program: Program,
    principal: nat,
    known: iset<Grant>
  )
    requires ValidProgram(program)
    ensures EaclImmediate(program, principal, known) ==
            GroundImmediate(program, principal, known)
  {
    forall grant: Grant
      ensures grant in EaclImmediate(program, principal, known) <==>
              grant in GroundImmediate(program, principal, known)
    {
      ConsequenceGroundingExact(program, known, grant);
    }
  }

  ghost predicate EaclClosed(
    program: Program,
    principal: nat,
    candidate: iset<Grant>
  ) {
    BaseGrants(program, principal) <= candidate &&
    forall grant | EaclConsequence(program, candidate, grant) ::
      grant in candidate
  }

  ghost predicate GroundClosed(
    program: Program,
    principal: nat,
    candidate: iset<Grant>
  ) {
    BaseGrants(program, principal) <= candidate &&
    forall edge | edge in GroundEdges(program) &&
                  edge.body in candidate ::
      edge.head in candidate
  }

  lemma ClosedGroundingExact(
    program: Program,
    principal: nat,
    candidate: iset<Grant>
  )
    requires ValidProgram(program)
    ensures EaclClosed(program, principal, candidate) <==>
            GroundClosed(program, principal, candidate)
  {
    if EaclClosed(program, principal, candidate) {
      forall edge | edge in GroundEdges(program) &&
                    edge.body in candidate
        ensures edge.head in candidate
      {
        ConsequenceGroundingExact(
          program, candidate, edge.head
        );
        assert GroundConsequence(
                 program, candidate, edge.head
               );
        assert EaclConsequence(
                 program, candidate, edge.head
               );
      }
    } else if GroundClosed(program, principal, candidate) {
      forall grant | EaclConsequence(program, candidate, grant)
        ensures grant in candidate
      {
        ConsequenceGroundingExact(program, candidate, grant);
        var edge: Edge :|
          edge in GroundEdges(program) &&
          edge.body in candidate &&
          edge.head == grant;
      }
    }
  }
}
