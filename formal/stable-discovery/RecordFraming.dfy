module RecordFraming {
  datatype Token = Length(value: nat) | Data(value: nat)

  function Payload(bytes: seq<nat>): seq<Token> {
    seq(|bytes|, index requires 0 <= index < |bytes| => Data(bytes[index]))
  }

  function FrameOne(record: seq<nat>): seq<Token> {
    [Length(|record|)] + Payload(record)
  }

  function Frame(records: seq<seq<nat>>): seq<Token>
    decreases |records|
  {
    if |records| == 0 then []
    else FrameOne(records[0]) + Frame(records[1..])
  }

  function Unframed(records: seq<seq<nat>>): seq<nat>
    decreases |records|
  {
    if |records| == 0 then []
    else records[0] + Unframed(records[1..])
  }

  lemma PayloadLength(bytes: seq<nat>)
    ensures |Payload(bytes)| == |bytes|
  {
  }

  lemma PayloadInjective(left: seq<nat>, right: seq<nat>)
    requires Payload(left) == Payload(right)
    ensures left == right
  {
    PayloadLength(left);
    PayloadLength(right);
    assert |left| == |right|;
    forall index | 0 <= index < |left|
      ensures left[index] == right[index]
    {
      assert Payload(left)[index] == Data(left[index]);
      assert Payload(right)[index] == Data(right[index]);
    }
  }

  lemma FrameNonempty(records: seq<seq<nat>>)
    requires |records| > 0
    ensures |Frame(records)| > 0
    ensures Frame(records)[0] == Length(|records[0]|)
  {
  }

  lemma FrameInjective(left: seq<seq<nat>>, right: seq<seq<nat>>)
    requires Frame(left) == Frame(right)
    ensures left == right
    decreases |left| + |right|
  {
    if |left| == 0 {
      if |right| > 0 {
        FrameNonempty(right);
        assert false;
      }
    } else if |right| == 0 {
      FrameNonempty(left);
      assert false;
    } else {
      FrameNonempty(left);
      FrameNonempty(right);
      assert Length(|left[0]|) == Length(|right[0]|);
      assert |left[0]| == |right[0]|;
      var width := |left[0]|;
      PayloadLength(left[0]);
      PayloadLength(right[0]);
      assert Frame(left)[1..1 + width] == Payload(left[0]);
      assert Frame(right)[1..1 + width] == Payload(right[0]);
      assert Payload(left[0]) == Payload(right[0]);
      PayloadInjective(left[0], right[0]);
      assert Frame(left)[1 + width..] == Frame(left[1..]);
      assert Frame(right)[1 + width..] == Frame(right[1..]);
      assert Frame(left[1..]) == Frame(right[1..]);
      FrameInjective(left[1..], right[1..]);
    }
  }

  lemma FramingRemovesConcatenationAmbiguity()
    ensures Unframed([[1], [2]]) == Unframed([[1, 2]])
    ensures Frame([[1], [2]]) != Frame([[1, 2]])
  {
    assert Frame([[1], [2]])[0] == Length(1);
    assert Frame([[1, 2]])[0] == Length(2);
  }
}
