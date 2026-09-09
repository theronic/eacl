// Selected CEL scalar/container semantics and resource accounting. This is a
// proof model, not the ANTLR implementation and not a claim of full CEL support.
module CaveatProfile {
  datatype Scalar = BoolValue(b: bool) | IntValue(n: int) | TextValue(text: seq<nat>) | TimestampValue(ms: int)
  datatype Value = ScalarValue(scalar: Scalar) | ListValue(items: seq<Scalar>) | MapValue(entries: map<seq<nat>, Scalar>)
  datatype Result = ValueResult(value: Value) | ErrorResult
  datatype Op = Eq | Ne | Lt | Le | Gt | Ge | In | Index | Contains | StartsWith | EndsWith
  datatype ScalarType = BoolType | IntType | TextType | TimestampType
  datatype Type = ScalarT(s: ScalarType) | ListT(item: ScalarType) | MapT(item: ScalarType)

  function ScalarTypeOf(s: Scalar): ScalarType {
    match s
    case BoolValue(_) => BoolType
    case IntValue(_) => IntType
    case TextValue(_) => TextType
    case TimestampValue(_) => TimestampType
  }

  predicate WellTyped(v: Value, t: Type) {
    match t
    case ScalarT(s) => v.ScalarValue? && ScalarTypeOf(v.scalar) == s
    case ListT(item) => v.ListValue? && (forall i | 0 <= i < |v.items| :: ScalarTypeOf(v.items[i]) == item)
    case MapT(item) => v.MapValue? && (forall k | k in v.entries :: ScalarTypeOf(v.entries[k]) == item)
  }

  function Boolean(b: bool): Result { ValueResult(ScalarValue(BoolValue(b))) }

  predicate SameScalar(a: Value, b: Value) {
    a.ScalarValue? && b.ScalarValue? && ScalarTypeOf(a.scalar) == ScalarTypeOf(b.scalar)
  }

  predicate Ordered(a: Value, b: Value) {
    SameScalar(a, b) && (a.scalar.IntValue? || a.scalar.TimestampValue?)
  }

  function Ordinal(a: Scalar): int
    requires a.IntValue? || a.TimestampValue?
  { if a.IntValue? then a.n else a.ms }

  predicate MatchAt(haystack: seq<nat>, needle: seq<nat>, offset: nat) {
    offset + |needle| <= |haystack| && haystack[offset..offset + |needle|] == needle
  }

  predicate HasSubstring(haystack: seq<nat>, needle: seq<nat>) {
    |needle| == 0 || exists i: nat | i <= |haystack| :: MatchAt(haystack, needle, i)
  }

  function Eval(op: Op, a: Value, b: Value): Result {
    if op == Eq || op == Ne then
      if SameScalar(a, b) then Boolean(if op == Eq then a == b else a != b) else ErrorResult
    else if op == Lt || op == Le || op == Gt || op == Ge then
      if Ordered(a, b) then
        Boolean(if op == Lt then Ordinal(a.scalar) < Ordinal(b.scalar)
                else if op == Le then Ordinal(a.scalar) <= Ordinal(b.scalar)
                else if op == Gt then Ordinal(a.scalar) > Ordinal(b.scalar)
                else Ordinal(a.scalar) >= Ordinal(b.scalar))
      else ErrorResult
    else if op == In then
      if a.ScalarValue? && b.ListValue? then Boolean(a.scalar in b.items)
      else if a.ScalarValue? && a.scalar.TextValue? && b.MapValue? then Boolean(a.scalar.text in b.entries)
      else ErrorResult
    else if op == Index then
      if a.MapValue? && b.ScalarValue? && b.scalar.TextValue? && b.scalar.text in a.entries
      then ValueResult(ScalarValue(a.entries[b.scalar.text])) else ErrorResult
    else if a.ScalarValue? && b.ScalarValue? && a.scalar.TextValue? && b.scalar.TextValue? then
      var x := a.scalar.text; var y := b.scalar.text;
                              if op == Contains then Boolean(HasSubstring(x, y))
                              else if op == StartsWith then Boolean(|y| <= |x| && x[..|y|] == y)
                              else Boolean(|y| <= |x| && x[|x| - |y|..] == y)
    else ErrorResult
  }

  function ScalarSize(s: Scalar): nat {
    if s.TextValue? then |s.text| else 1
  }

  function SaturatingAdd(a: nat, b: nat, limit: nat): nat {
    if a > limit || b > limit - a then limit + 1 else a + b
  }

  function SaturatingMultiply(a: nat, b: nat, limit: nat): nat {
    if a * b > limit then limit + 1 else a * b
  }

  function StringWork(op: Op, a: nat, b: nat, limit: nat): nat {
    SaturatingAdd(1, if op == Contains then SaturatingMultiply(a, b, limit) else SaturatingAdd(a, b, limit), limit)
  }

  lemma EqualitySymmetric(a: Value, b: Value)
    ensures Eval(Eq, a, b) == Eval(Eq, b, a)
    ensures Eval(Ne, a, b) == Eval(Ne, b, a)
  {
  }

  lemma HomogeneousEqualityIsBoolean(a: Value, b: Value)
    requires SameScalar(a, b)
    ensures Eval(Eq, a, b) == Boolean(a == b)
    ensures Eval(Ne, a, b) == Boolean(a != b)
  {
  }

  lemma InvalidOrderingRejected(a: Value, b: Value)
    requires !Ordered(a, b)
    ensures Eval(Lt, a, b) == ErrorResult && Eval(Le, a, b) == ErrorResult
    ensures Eval(Gt, a, b) == ErrorResult && Eval(Ge, a, b) == ErrorResult
  {
  }

  lemma AbsentMapKeyIsError(m: map<seq<nat>, Scalar>, key: seq<nat>)
    requires key !in m
    ensures Eval(Index, MapValue(m), ScalarValue(TextValue(key))) == ErrorResult
  {
  }

  lemma MapIndexReturnsTypedValue(m: map<seq<nat>, Scalar>, key: seq<nat>, t: ScalarType)
    requires key in m && WellTyped(MapValue(m), MapT(t))
    ensures Eval(Index, MapValue(m), ScalarValue(TextValue(key))) == ValueResult(ScalarValue(m[key]))
    ensures ScalarTypeOf(m[key]) == t
  {
  }

  lemma EmptyNeedleMatches(x: seq<nat>)
    ensures Eval(Contains, ScalarValue(TextValue(x)), ScalarValue(TextValue([]))) == Boolean(true)
  {
    assert x[0..0] == [];
  }

  lemma AdditionBound(a: nat, b: nat, limit: nat)
    ensures SaturatingAdd(a, b, limit) <= limit + 1
    ensures SaturatingAdd(a, b, limit) <= limit <==> a + b <= limit
    ensures a + b <= limit ==> SaturatingAdd(a, b, limit) == a + b
  {
  }

  lemma MultiplicationBound(a: nat, b: nat, limit: nat)
    ensures SaturatingMultiply(a, b, limit) <= limit + 1
    ensures SaturatingMultiply(a, b, limit) <= limit <==> a * b <= limit
    ensures a * b <= limit ==> SaturatingMultiply(a, b, limit) == a * b
  {
  }

  lemma StringWorkBound(op: Op, a: nat, b: nat, limit: nat)
    ensures StringWork(op, a, b, limit) <= limit + 1
    ensures op == Contains && 1 + a * b > limit ==> StringWork(op, a, b, limit) > limit
  {
    MultiplicationBound(a, b, limit);
    AdditionBound(a, b, limit);
    AdditionBound(1, if op == Contains then SaturatingMultiply(a, b, limit) else SaturatingAdd(a, b, limit), limit);
  }
}
