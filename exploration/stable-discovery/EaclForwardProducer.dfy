// Exploratory refinement from a sealed forward consumer vector to exactly the
// outgoing edges of the typed grounded EACL program.
include "EaclForwardGrounding.dfy"

module EaclForwardProducer {
  import opened F = EaclForwardGrounding

  function RuleSet(rules: seq<F.Rule>): set<F.Rule>
    decreases |rules|
  {
    if |rules| == 0 then {}
    else {rules[0]} + RuleSet(rules[1..])
  }

  predicate ConsumesNode(rule: F.Rule, node: nat) {
    match rule
    case SelfPermission(_, target) => target == node
    case ArrowPermission(_, _, target) => target == node
    case _ => false
  }

  function ConsumerRules(
    rules: seq<F.Rule>,
    node: nat
  ): seq<F.Rule>
    decreases |rules|
  {
    if |rules| == 0 then []
    else if ConsumesNode(rules[0], node) then
      [rules[0]] + ConsumerRules(rules[1..], node)
    else
      ConsumerRules(rules[1..], node)
  }

  lemma ConsumerMembershipExact(
    rules: seq<F.Rule>,
    node: nat,
    rule: F.Rule
  )
    ensures rule in RuleSet(ConsumerRules(rules, node)) <==>
            rule in RuleSet(rules) && ConsumesNode(rule, node)
    decreases |rules|
  {
    if |rules| > 0 {
      ConsumerMembershipExact(rules[1..], node, rule);
    }
  }

  datatype SealedProgram = SealedProgram(
    semantic: F.Program,
    ruleVector: seq<F.Rule>
  )

  ghost predicate ValidSealed(program: SealedProgram) {
    F.ValidProgram(program.semantic) &&
    RuleSet(program.ruleVector) == program.semantic.rules
  }

  ghost predicate RuntimeConsequence(
    program: SealedProgram,
    body: F.Grant,
    result: F.Grant
  ) {
    F.ValidGrant(program.semantic, body) &&
    F.ValidGrant(program.semantic, result) &&
    exists rule: F.Rule ::
      rule in RuleSet(ConsumerRules(program.ruleVector, body.node)) &&
      (match rule
       case SelfPermission(head, target) =>
         result == F.Grant(head, body.resource)
       case ArrowPermission(head, via, target) =>
         exists resource: nat ::
           F.Relationship(
             via, body.resource, resource
           ) in program.semantic.relationships &&
           result == F.Grant(head, resource)
       case _ => false)
  }

  ghost predicate GroundSuccessor(
    program: SealedProgram,
    body: F.Grant,
    result: F.Grant
  ) {
    F.Edge(body, result) in F.GroundEdges(program.semantic)
  }

  lemma RuntimeProducerEmitsExactlyGroundSuccessors(
    program: SealedProgram,
    body: F.Grant,
    result: F.Grant
  )
    requires ValidSealed(program)
    ensures RuntimeConsequence(program, body, result) <==>
            GroundSuccessor(program, body, result)
  {
    if RuntimeConsequence(program, body, result) {
      var rule: F.Rule :|
        rule in RuleSet(
          ConsumerRules(program.ruleVector, body.node)
        ) &&
        (match rule
         case SelfPermission(head, target) =>
           result == F.Grant(head, body.resource)
         case ArrowPermission(head, via, target) =>
           exists resource: nat ::
             F.Relationship(
               via, body.resource, resource
             ) in program.semantic.relationships &&
             result == F.Grant(head, resource)
         case _ => false);
      ConsumerMembershipExact(program.ruleVector, body.node, rule);
      assert rule in program.semantic.rules;
      match rule {
        case SelfPermission(head, target) =>
          assert target == body.node;
          assert F.Edge(body, result) in
                 F.GroundEdges(program.semantic);
        case ArrowPermission(head, via, target) =>
          var resource: nat :|
            F.Relationship(
              via, body.resource, resource
            ) in program.semantic.relationships &&
            result == F.Grant(head, resource);
          assert target == body.node;
          assert F.Edge(body, result) in
                 F.GroundEdges(program.semantic);
        case _ =>
      }
    } else if GroundSuccessor(program, body, result) {
      if exists head: nat, target: nat, resource: nat ::
           F.SelfPermission(head, target) in program.semantic.rules &&
           body == F.Grant(target, resource) &&
           result == F.Grant(head, resource) {
        var head: nat, target: nat, resource: nat :|
          F.SelfPermission(head, target) in program.semantic.rules &&
          body == F.Grant(target, resource) &&
          result == F.Grant(head, resource);
        var rule := F.SelfPermission(head, target);
        assert rule in RuleSet(program.ruleVector);
        assert ConsumesNode(rule, body.node);
        ConsumerMembershipExact(program.ruleVector, body.node, rule);
      } else {
        var head: nat, via: nat, target: nat,
            intermediate: nat, resource: nat :|
          F.ArrowPermission(head, via, target) in
            program.semantic.rules &&
          F.Relationship(via, intermediate, resource) in
            program.semantic.relationships &&
          body == F.Grant(target, intermediate) &&
          result == F.Grant(head, resource);
        var rule := F.ArrowPermission(head, via, target);
        assert rule in RuleSet(program.ruleVector);
        assert ConsumesNode(rule, body.node);
        ConsumerMembershipExact(program.ruleVector, body.node, rule);
      }
    }
  }
}
