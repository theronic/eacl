include "NativeGenerationCoherence.dfy"
include "ScalarFrontierCoherence.dfy"

module UuidLifecycle {
  import Native = NativeGenerationCoherence
  import Scalar = ScalarFrontierCoherence

  type Half = n: nat | n < 18446744073709551616 witness 0
  datatype Uuid = Uuid(high: Half, low: Half)
  datatype Scope = Scope(backend: nat, source: nat, branch: nat)
  datatype Lineage = Lineage(scope: Scope, uuid: Uuid)
  datatype Captured = Captured(lineage: Lineage, incarnation: nat, revision: nat)

  const Digits: string := "0123456789abcdef"

  function {:fuel 17} Power16(width: nat): nat
    ensures 0 < Power16(width)
    decreases width
  {
    if width == 0 then 1 else 16 * Power16(width - 1)
  }

  predicate HexText(text: string) {
    forall i | 0 <= i < |text| :: text[i] in Digits
  }

  function DigitValue(c: char): nat
    requires c in Digits
  {
    if c == '0' then 0 else if c == '1' then 1
    else if c == '2' then 2 else if c == '3' then 3
    else if c == '4' then 4 else if c == '5' then 5
    else if c == '6' then 6 else if c == '7' then 7
    else if c == '8' then 8 else if c == '9' then 9
    else if c == 'a' then 10 else if c == 'b' then 11
    else if c == 'c' then 12 else if c == 'd' then 13
    else if c == 'e' then 14 else 15
  }

  lemma DigitRoundTrip(n: nat)
    requires n < 16
    ensures DigitValue(Digits[n]) == n
  {}

  function Hex(n: nat, width: nat): string
    requires n < Power16(width)
    ensures |Hex(n, width)| == width
    ensures HexText(Hex(n, width))
    decreases width
  {
    if width == 0 then ""
    else Hex(n / 16, width - 1) + [Digits[n % 16]]
  }

  function ParseHex(text: string): nat
    requires HexText(text)
    decreases |text|
  {
    if |text| == 0 then 0
    else 16 * ParseHex(text[..|text| - 1]) + DigitValue(text[|text| - 1])
  }

  lemma HexRoundTrip(n: nat, width: nat)
    requires n < Power16(width)
    ensures ParseHex(Hex(n, width)) == n
    decreases width
  {
    if width > 0 {
      HexRoundTrip(n / 16, width - 1);
      DigitRoundTrip(n % 16);
    }
  }

  function Raw(u: Uuid): string {
    Hex(u.high, 16) + Hex(u.low, 16)
  }

  function Text(u: Uuid): string {
    var h := Raw(u);
    h[..8] + "-" + h[8..12] + "-" + h[12..16] + "-" + h[16..20] + "-" + h[20..]
  }

  function Untag(text: string): string
    requires |text| == 36
  {
    text[..8] + text[9..13] + text[14..18] + text[19..23] + text[24..]
  }

  function Encode(u: Uuid): string {
    "#uuid \"" + Text(u) + "\""
  }

  lemma RepresentationRoundTrip(u: Uuid)
    ensures |Text(u)| == 36
    ensures |Encode(u)| == 44
    ensures Untag(Text(u)) == Raw(u)
    ensures ParseHex(Untag(Text(u))[..16]) == u.high
    ensures ParseHex(Untag(Text(u))[16..]) == u.low
  {
    HexRoundTrip(u.high, 16);
    HexRoundTrip(u.low, 16);
    var h := Raw(u);
    assert Text(u)[..8] == h[..8];
    assert Text(u)[9..13] == h[8..12];
    assert Text(u)[14..18] == h[12..16];
    assert Text(u)[19..23] == h[16..20];
    assert Text(u)[24..] == h[20..];
    assert h == h[..8] + h[8..12] + h[12..16] + h[16..20] + h[20..];
  }

  lemma Injective(a: Uuid, b: Uuid)
    requires Encode(a) == Encode(b)
    ensures a == b
  {
    RepresentationRoundTrip(a);
    RepresentationRoundTrip(b);
    assert Encode(a)[7..43] == Text(a);
    assert Encode(b)[7..43] == Text(b);
    assert Text(a) == Text(b);
  }

  lemma UuidIsNotString(u: Uuid)
    ensures Encode(u) != "\"" + Text(u) + "\""
  {}

  predicate CanPublish(captured: Captured, current: Captured) {
    captured.lineage == current.lineage && captured.incarnation == current.incarnation
  }

  lemma CompleteScopeIsolation(a: Captured, b: Captured)
    requires a.lineage.scope != b.lineage.scope || a.lineage.uuid != b.lineage.uuid
    ensures !CanPublish(a, b)
  {}

  lemma SameUuidResetDetaches(prior: Captured)
    ensures !CanPublish(prior, Captured(prior.lineage, prior.incarnation + 1, prior.revision))
  {}

  lemma OrdinaryCommitPreservesLineage(prior: Captured, revision: nat)
    ensures CanPublish(prior, Captured(prior.lineage, prior.incarnation, revision))
  {}

  lemma RecycledUuidCanReauthorize(l: Lineage, revision: nat)
    ensures Captured(l, 0, revision).lineage == Captured(l, 1, revision).lineage
  {}

  // Existing coherence models use an abstract nat for COMPLETE lineage.
  // The host interpretation must be injective over its compared lineages;
  // this is not an assumption that random UUID generation proves freshness.
  predicate Faithful(ids: map<Lineage, nat>) {
    forall a, b | a in ids && b in ids :: (ids[a] == ids[b] <==> a == b)
  }

  lemma CoherenceRefinement(ids: map<Lineage, nat>, a: Lineage, b: Lineage,
                            na: Native.Snapshot, nb: Native.Snapshot,
                            sa: Scalar.Snapshot, sb: Scalar.Snapshot)
    requires Faithful(ids) && a in ids && b in ids
    requires na.lifecycle == ids[a] && nb.lifecycle == ids[b]
    requires sa.lifecycle == ids[a] && sb.lifecycle == ids[b]
    ensures (na.lifecycle == nb.lifecycle <==> a == b)
    ensures (sa.lifecycle == sb.lifecycle <==> a == b)
  {}
}
