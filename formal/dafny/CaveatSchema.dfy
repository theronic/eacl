include "CaveatProfile.dfy"

module CaveatSchema {
  import Profile = CaveatProfile

  datatype Definition = Definition(parameters: map<nat, Profile.Type>, booleanRoot: bool, profile: nat)
  datatype Schema = Schema(definitions: map<nat, Definition>, allowances: map<nat, set<nat>>, generation: nat)
  datatype Replacement = Replacement(accepted: bool, selected: Schema)

  predicate Valid(s: Schema) {
    0 !in s.definitions &&
    (forall n | n in s.definitions :: s.definitions[n].booleanRoot && s.definitions[n].profile == 1 && |s.definitions[n].parameters| <= 32) &&
    (forall relation | relation in s.allowances :: s.allowances[relation] - {0} <= s.definitions.Keys)
  }

  predicate Allowed(s: Schema, relation: nat, caveat: nat) {
    relation in s.allowances && caveat in s.allowances[relation]
  }

  function Replace(current: Schema, expected: nat, candidate: Schema, retained: set<nat>): Replacement {
    if current.generation == expected && Valid(candidate) && retained <= candidate.definitions.Keys then
      Replacement(true, Schema(candidate.definitions, candidate.allowances, current.generation + 1))
    else Replacement(false, current)
  }

  lemma AcceptedReplacementIsAtomic(current: Schema, expected: nat, candidate: Schema, retained: set<nat>)
    requires Replace(current, expected, candidate, retained).accepted
    ensures Valid(Replace(current, expected, candidate, retained).selected)
    ensures Replace(current, expected, candidate, retained).selected.generation == current.generation + 1
    ensures Replace(current, expected, candidate, retained).selected.definitions == candidate.definitions
    ensures Replace(current, expected, candidate, retained).selected.allowances == candidate.allowances
  {
  }

  lemma ConcurrentReplacementRejected(current: Schema, expected: nat, candidate: Schema, retained: set<nat>)
    requires expected != current.generation
    ensures !Replace(current, expected, candidate, retained).accepted
    ensures Replace(current, expected, candidate, retained).selected == current
  {
  }

  lemma RetainedReferencePreventsRemoval(current: Schema, expected: nat, candidate: Schema, retained: set<nat>, name: nat)
    requires name in retained && name !in candidate.definitions
    ensures !Replace(current, expected, candidate, retained).accepted
  {
  }

  lemma RelationMustAllowQualifiedCaveat(s: Schema, relation: nat, caveat: nat)
    requires caveat != 0 && (relation !in s.allowances || caveat !in s.allowances[relation])
    ensures !Allowed(s, relation, caveat)
  {
  }

  lemma UnqualifiedRequiresPlainBranch(s: Schema, relation: nat)
    ensures Allowed(s, relation, 0) <==> relation in s.allowances && 0 in s.allowances[relation]
  {
  }

  function Named(s: Schema, name: nat): (nat, Definition)
    requires name in s.definitions
  { (name, s.definitions[name]) }

  lemma NamedDefinitionsRemainDistinct(s: Schema, a: nat, b: nat)
    requires a in s.definitions && b in s.definitions
    ensures Named(s, a) == Named(s, b) ==> a == b
  {
  }
}
