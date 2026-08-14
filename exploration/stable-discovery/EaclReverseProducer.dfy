// Exploratory refinement from sealed reverse rules to exact transposed
// grounded predecessors and principal-owned base grants.
include "EaclForwardProducer.dfy"

module EaclReverseProducer {
  import opened F = EaclForwardGrounding
  import P = EaclForwardProducer

  predicate ProducesNode(rule: F.Rule, node: nat) {
    match rule
    case Direct(head, _) => head == node
    case SelfPermission(head, _) => head == node
    case ArrowRelation(head, _, _) => head == node
    case ArrowPermission(head, _, _) => head == node
  }

  function ProducerRules(
    rules: seq<F.Rule>,
    node: nat
  ): seq<F.Rule>
    decreases |rules|
  {
    if |rules| == 0 then []
    else if ProducesNode(rules[0], node) then
      [rules[0]] + ProducerRules(rules[1..], node)
    else
      ProducerRules(rules[1..], node)
  }

  lemma ProducerMembershipExact(
    rules: seq<F.Rule>,
    node: nat,
    rule: F.Rule
  )
    ensures rule in P.RuleSet(ProducerRules(rules, node)) <==>
            rule in P.RuleSet(rules) && ProducesNode(rule, node)
    decreases |rules|
  {
    if |rules| > 0 {
      ProducerMembershipExact(rules[1..], node, rule);
    }
  }

  ghost predicate RuntimePredecessor(
    program: P.SealedProgram,
    head: F.Grant,
    body: F.Grant
  ) {
    F.ValidGrant(program.semantic, head) &&
    F.ValidGrant(program.semantic, body) &&
    exists rule: F.Rule ::
      rule in P.RuleSet(
        ProducerRules(program.ruleVector, head.node)
      ) &&
      (match rule
       case SelfPermission(ruleHead, target) =>
         body == F.Grant(target, head.resource)
       case ArrowPermission(ruleHead, via, target) =>
         F.Relationship(
           via, body.resource, head.resource
         ) in program.semantic.relationships &&
         body.node == target
       case _ => false)
  }

  ghost predicate GroundPredecessor(
    program: P.SealedProgram,
    head: F.Grant,
    body: F.Grant
  ) {
    F.Edge(body, head) in F.GroundEdges(program.semantic)
  }

  lemma RuntimeReverseRulesEmitExactlyGroundPredecessors(
    program: P.SealedProgram,
    head: F.Grant,
    body: F.Grant
  )
    requires P.ValidSealed(program)
    ensures RuntimePredecessor(program, head, body) <==>
            GroundPredecessor(program, head, body)
  {
    if RuntimePredecessor(program, head, body) {
      var rule: F.Rule :|
        rule in P.RuleSet(
          ProducerRules(program.ruleVector, head.node)
        ) &&
        (match rule
         case SelfPermission(ruleHead, target) =>
           body == F.Grant(target, head.resource)
         case ArrowPermission(ruleHead, via, target) =>
           F.Relationship(
             via, body.resource, head.resource
           ) in program.semantic.relationships &&
           body.node == target
         case _ => false);
      ProducerMembershipExact(program.ruleVector, head.node, rule);
      assert rule in program.semantic.rules;
      match rule {
        case SelfPermission(ruleHead, target) =>
          assert ruleHead == head.node;
          assert F.Edge(body, head) in F.GroundEdges(program.semantic);
        case ArrowPermission(ruleHead, via, target) =>
          assert ruleHead == head.node;
          assert F.Edge(body, head) in F.GroundEdges(program.semantic);
        case _ =>
      }
    } else if GroundPredecessor(program, head, body) {
      if exists ruleHead: nat, target: nat, resource: nat ::
           F.SelfPermission(ruleHead, target) in
             program.semantic.rules &&
           body == F.Grant(target, resource) &&
           head == F.Grant(ruleHead, resource) {
        var ruleHead: nat, target: nat, resource: nat :|
          F.SelfPermission(ruleHead, target) in
            program.semantic.rules &&
          body == F.Grant(target, resource) &&
          head == F.Grant(ruleHead, resource);
        var rule := F.SelfPermission(ruleHead, target);
        assert rule in P.RuleSet(program.ruleVector);
        assert ProducesNode(rule, head.node);
        ProducerMembershipExact(program.ruleVector, head.node, rule);
      } else {
        var ruleHead: nat, via: nat, target: nat,
            intermediate: nat, resource: nat :|
          F.ArrowPermission(ruleHead, via, target) in
            program.semantic.rules &&
          F.Relationship(via, intermediate, resource) in
            program.semantic.relationships &&
          body == F.Grant(target, intermediate) &&
          head == F.Grant(ruleHead, resource);
        var rule := F.ArrowPermission(ruleHead, via, target);
        assert rule in P.RuleSet(program.ruleVector);
        assert ProducesNode(rule, head.node);
        ProducerMembershipExact(program.ruleVector, head.node, rule);
      }
    }
  }

  ghost predicate RuntimeBaseOwner(
    program: P.SealedProgram,
    grant: F.Grant,
    principal: nat
  ) {
    F.ValidGrant(program.semantic, grant) &&
    exists rule: F.Rule ::
      rule in P.RuleSet(
        ProducerRules(program.ruleVector, grant.node)
      ) &&
      (match rule
       case Direct(head, relation) =>
         F.Relationship(
           relation, principal, grant.resource
         ) in program.semantic.relationships
       case ArrowRelation(head, via, targetRelation) =>
         exists intermediate: nat ::
           F.Relationship(
             targetRelation, principal, intermediate
           ) in program.semantic.relationships &&
           F.Relationship(
             via, intermediate, grant.resource
           ) in program.semantic.relationships
       case _ => false)
  }

  lemma RuntimeBaseOwnerIsExact(
    program: P.SealedProgram,
    grant: F.Grant,
    principal: nat
  )
    requires P.ValidSealed(program)
    ensures RuntimeBaseOwner(program, grant, principal) <==>
            grant in F.BaseGrants(program.semantic, principal)
  {
    if RuntimeBaseOwner(program, grant, principal) {
      var rule: F.Rule :|
        rule in P.RuleSet(
          ProducerRules(program.ruleVector, grant.node)
        ) &&
        (match rule
         case Direct(head, relation) =>
           F.Relationship(
             relation, principal, grant.resource
           ) in program.semantic.relationships
         case ArrowRelation(head, via, targetRelation) =>
           exists intermediate: nat ::
             F.Relationship(
               targetRelation, principal, intermediate
             ) in program.semantic.relationships &&
             F.Relationship(
               via, intermediate, grant.resource
             ) in program.semantic.relationships
         case _ => false);
      ProducerMembershipExact(program.ruleVector, grant.node, rule);
      assert rule in program.semantic.rules;
      match rule {
        case Direct(head, relation) =>
          assert head == grant.node;
        case ArrowRelation(head, via, targetRelation) =>
          assert head == grant.node;
        case _ =>
      }
    } else if grant in F.BaseGrants(program.semantic, principal) {
      if exists relation: nat ::
           F.Direct(grant.node, relation) in program.semantic.rules &&
           F.Relationship(
             relation, principal, grant.resource
           ) in program.semantic.relationships {
        var relation: nat :|
          F.Direct(grant.node, relation) in program.semantic.rules &&
          F.Relationship(
            relation, principal, grant.resource
          ) in program.semantic.relationships;
        var rule := F.Direct(grant.node, relation);
        assert rule in P.RuleSet(program.ruleVector);
        assert ProducesNode(rule, grant.node);
        ProducerMembershipExact(
          program.ruleVector, grant.node, rule
        );
      } else {
        var via: nat, targetRelation: nat, intermediate: nat :|
          F.ArrowRelation(
            grant.node, via, targetRelation
          ) in program.semantic.rules &&
          F.Relationship(
            targetRelation, principal, intermediate
          ) in program.semantic.relationships &&
          F.Relationship(
            via, intermediate, grant.resource
          ) in program.semantic.relationships;
        var rule := F.ArrowRelation(
          grant.node, via, targetRelation
        );
        assert rule in P.RuleSet(program.ruleVector);
        assert ProducesNode(rule, grant.node);
        ProducerMembershipExact(
          program.ruleVector, grant.node, rule
        );
      }
    }
  }
}
