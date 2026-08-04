module OrderedMerge {
  datatype MergeDirection =
    | Ascending
    | Descending

  datatype OptionalHead =
    | NoHead
    | Head(value: int)

  datatype OptionalLast =
    | NoLast
    | Last(value: int)

  predicate PreviouslyEmitted(last: OptionalLast, candidate: int) {
    last.Last? && last.value == candidate
  }

  lemma NoLastNeverSuppresses(candidate: int)
    ensures !PreviouslyEmitted(NoLast, candidate)
  {
  }

  lemma LastSuppressesExactlyItsValue(previous: int, candidate: int)
    ensures PreviouslyEmitted(Last(previous), candidate) ==
            (previous == candidate)
  {
  }

  datatype MergeStep =
    | LeftExhausted
    | RightExhausted
    | TakeLeft
    | TakeRight
    | TakeBoth

  datatype MergeChunk =
    MergeChunk(
      values: seq<int>,
      leftConsumed: nat,
      rightConsumed: nat
    )

  function DecideMergeStepSpec(
    direction: MergeDirection,
    left: OptionalHead,
    right: OptionalHead
  ): MergeStep {
    if left.NoHead? then
      LeftExhausted
    else if right.NoHead? then
      RightExhausted
    else if left.value == right.value then
      TakeBoth
    else if
      (direction.Ascending? && left.value < right.value) ||
      (direction.Descending? && left.value > right.value)
    then
      TakeLeft
    else
      TakeRight
  }

  method DecideMergeStep(
    direction: MergeDirection,
    left: OptionalHead,
    right: OptionalHead
  ) returns (step: MergeStep)
    ensures step == DecideMergeStepSpec(direction, left, right)
  {
    if left.NoHead? {
      return LeftExhausted;
    }
    if right.NoHead? {
      return RightExhausted;
    }
    if left.value == right.value {
      return TakeBoth;
    }
    if (direction.Ascending? && left.value < right.value) ||
       (direction.Descending? && left.value > right.value) {
      return TakeLeft;
    }
    return TakeRight;
  }

  function MergeAscendingChunkSpec(
    left: seq<int>,
    right: seq<int>
  ): MergeChunk
    requires StrictAscending(left)
    requires StrictAscending(right)
    ensures MergeAscendingChunkSpec(left, right).leftConsumed <= |left|
    ensures MergeAscendingChunkSpec(left, right).rightConsumed <= |right|
    ensures
      |left| == 0 || |right| == 0 ==>
        MergeAscendingChunkSpec(left, right) == MergeChunk([], 0, 0)
    ensures
      |left| != 0 && |right| != 0 ==>
        MergeAscendingChunkSpec(left, right).leftConsumed == |left| ||
        MergeAscendingChunkSpec(left, right).rightConsumed == |right|
    decreases |left| + |right|
  {
    if |left| == 0 || |right| == 0 then
      MergeChunk([], 0, 0)
    else if left[0] < right[0] then
      var tail := MergeAscendingChunkSpec(left[1..], right);
      MergeChunk(
        [left[0]] + tail.values,
        tail.leftConsumed + 1,
        tail.rightConsumed
      )
    else if right[0] < left[0] then
      var tail := MergeAscendingChunkSpec(left, right[1..]);
      MergeChunk(
        [right[0]] + tail.values,
        tail.leftConsumed,
        tail.rightConsumed + 1
      )
    else
      var tail := MergeAscendingChunkSpec(left[1..], right[1..]);
      MergeChunk(
        [left[0]] + tail.values,
        tail.leftConsumed + 1,
        tail.rightConsumed + 1
      )
  }

  function MergeDescendingChunkSpec(
    left: seq<int>,
    right: seq<int>
  ): MergeChunk
    requires StrictDescending(left)
    requires StrictDescending(right)
    ensures MergeDescendingChunkSpec(left, right).leftConsumed <= |left|
    ensures MergeDescendingChunkSpec(left, right).rightConsumed <= |right|
    ensures
      |left| == 0 || |right| == 0 ==>
        MergeDescendingChunkSpec(left, right) == MergeChunk([], 0, 0)
    ensures
      |left| != 0 && |right| != 0 ==>
        MergeDescendingChunkSpec(left, right).leftConsumed == |left| ||
        MergeDescendingChunkSpec(left, right).rightConsumed == |right|
    decreases |left| + |right|
  {
    if |left| == 0 || |right| == 0 then
      MergeChunk([], 0, 0)
    else if left[0] > right[0] then
      var tail := MergeDescendingChunkSpec(left[1..], right);
      MergeChunk(
        [left[0]] + tail.values,
        tail.leftConsumed + 1,
        tail.rightConsumed
      )
    else if right[0] > left[0] then
      var tail := MergeDescendingChunkSpec(left, right[1..]);
      MergeChunk(
        [right[0]] + tail.values,
        tail.leftConsumed,
        tail.rightConsumed + 1
      )
    else
      var tail := MergeDescendingChunkSpec(left[1..], right[1..]);
      MergeChunk(
        [left[0]] + tail.values,
        tail.leftConsumed + 1,
        tail.rightConsumed + 1
      )
  }

  function DecideMergeChunkSpec(
    direction: MergeDirection,
    left: seq<int>,
    right: seq<int>
  ): MergeChunk
    requires direction.Ascending? ==>
               StrictAscending(left) && StrictAscending(right)
    requires direction.Descending? ==>
               StrictDescending(left) && StrictDescending(right)
  {
    if direction.Ascending? then
      MergeAscendingChunkSpec(left, right)
    else
      MergeDescendingChunkSpec(left, right)
  }

  method DecideMergeChunk(
    direction: MergeDirection,
    left: seq<int>,
    right: seq<int>
  ) returns (chunk: MergeChunk)
    requires direction.Ascending? ==>
               StrictAscending(left) && StrictAscending(right)
    requires direction.Descending? ==>
               StrictDescending(left) && StrictDescending(right)
    ensures chunk == DecideMergeChunkSpec(direction, left, right)
  {
    chunk := DecideMergeChunkSpec(direction, left, right);
  }

  function SequenceHead(values: seq<int>): OptionalHead {
    if |values| == 0 then NoHead else Head(values[0])
  }

  lemma AscendingMergeStepRefinesDefinition(
    left: seq<int>,
    right: seq<int>
  )
    requires StrictAscending(left)
    requires StrictAscending(right)
    ensures
      match DecideMergeStepSpec(
          Ascending,
          SequenceHead(left),
          SequenceHead(right)
        )
      case LeftExhausted =>
        |left| == 0 &&
        MergeAscending(left, right) == right
      case RightExhausted =>
        |left| != 0 &&
        |right| == 0 &&
        MergeAscending(left, right) == left
      case TakeLeft =>
        |left| != 0 &&
        |right| != 0 &&
        left[0] < right[0] &&
        MergeAscending(left, right) ==
        [left[0]] + MergeAscending(left[1..], right)
      case TakeRight =>
        |left| != 0 &&
        |right| != 0 &&
        right[0] < left[0] &&
        MergeAscending(left, right) ==
        [right[0]] + MergeAscending(left, right[1..])
      case TakeBoth =>
        |left| != 0 &&
        |right| != 0 &&
        left[0] == right[0] &&
        MergeAscending(left, right) ==
        [left[0]] + MergeAscending(left[1..], right[1..])
  {
  }

  lemma DescendingMergeStepRefinesDefinition(
    left: seq<int>,
    right: seq<int>
  )
    requires StrictDescending(left)
    requires StrictDescending(right)
    ensures
      match DecideMergeStepSpec(
          Descending,
          SequenceHead(left),
          SequenceHead(right)
        )
      case LeftExhausted =>
        |left| == 0 &&
        MergeDescending(left, right) == right
      case RightExhausted =>
        |left| != 0 &&
        |right| == 0 &&
        MergeDescending(left, right) == left
      case TakeLeft =>
        |left| != 0 &&
        |right| != 0 &&
        left[0] > right[0] &&
        MergeDescending(left, right) ==
        [left[0]] + MergeDescending(left[1..], right)
      case TakeRight =>
        |left| != 0 &&
        |right| != 0 &&
        right[0] > left[0] &&
        MergeDescending(left, right) ==
        [right[0]] + MergeDescending(left, right[1..])
      case TakeBoth =>
        |left| != 0 &&
        |right| != 0 &&
        left[0] == right[0] &&
        MergeDescending(left, right) ==
        [left[0]] + MergeDescending(left[1..], right[1..])
  {
  }

  predicate StrictAscending(values: seq<int>) {
    forall i, j | 0 <= i < j < |values| ::
      values[i] < values[j]
  }

  predicate StrictDescending(values: seq<int>) {
    forall i, j | 0 <= i < j < |values| ::
      values[i] > values[j]
  }

  function SequenceSet(values: seq<int>): set<int> {
    set value | value in values :: value
  }

  function StreamSet(streams: seq<seq<int>>): set<int>
    decreases |streams|
  {
    if |streams| == 0 then
      {}
    else
      SequenceSet(streams[0]) + StreamSet(streams[1..])
  }

  predicate AllStrictAscending(streams: seq<seq<int>>) {
    forall index | 0 <= index < |streams| ::
      StrictAscending(streams[index])
  }

  predicate AllStrictDescending(streams: seq<seq<int>>) {
    forall index | 0 <= index < |streams| ::
      StrictDescending(streams[index])
  }

  lemma AscendingTail(values: seq<int>)
    requires StrictAscending(values)
    requires |values| != 0
    ensures StrictAscending(values[1..])
    ensures forall value | value in values[1..] ::
              values[0] < value
  {
  }

  lemma DescendingTail(values: seq<int>)
    requires StrictDescending(values)
    requires |values| != 0
    ensures StrictDescending(values[1..])
    ensures forall value | value in values[1..] ::
              values[0] > value
  {
  }

  lemma AscendingHeadBound(values: seq<int>, value: int)
    requires StrictAscending(values)
    requires |values| != 0
    requires value in values
    ensures values[0] <= value
  {
    AscendingTail(values);
  }

  lemma DescendingHeadBound(values: seq<int>, value: int)
    requires StrictDescending(values)
    requires |values| != 0
    requires value in values
    ensures values[0] >= value
  {
    DescendingTail(values);
  }

  lemma PrependAscending(head: int, tail: seq<int>)
    requires StrictAscending(tail)
    requires forall value | value in tail :: head < value
    ensures StrictAscending([head] + tail)
  {
    forall i, j | 0 <= i < j < |[head] + tail|
      ensures ([head] + tail)[i] < ([head] + tail)[j]
    {
      if i == 0 {
        assert ([head] + tail)[j] == tail[j - 1];
        assert tail[j - 1] in tail;
      } else {
        assert ([head] + tail)[i] == tail[i - 1];
        assert ([head] + tail)[j] == tail[j - 1];
        assert 0 <= i - 1 < j - 1 < |tail|;
      }
    }
  }

  lemma PrependDescending(head: int, tail: seq<int>)
    requires StrictDescending(tail)
    requires forall value | value in tail :: head > value
    ensures StrictDescending([head] + tail)
  {
    forall i, j | 0 <= i < j < |[head] + tail|
      ensures ([head] + tail)[i] > ([head] + tail)[j]
    {
      if i == 0 {
        assert ([head] + tail)[j] == tail[j - 1];
        assert tail[j - 1] in tail;
      } else {
        assert ([head] + tail)[i] == tail[i - 1];
        assert ([head] + tail)[j] == tail[j - 1];
        assert 0 <= i - 1 < j - 1 < |tail|;
      }
    }
  }

  function MergeAscending(
    left: seq<int>,
    right: seq<int>
  ): seq<int>
    requires StrictAscending(left)
    requires StrictAscending(right)
    ensures forall value ::
              value in MergeAscending(left, right) <==>
                       value in left || value in right
    decreases |left| + |right|
  {
    if |left| == 0 then
      right
    else if |right| == 0 then
      left
    else if left[0] < right[0] then
      [left[0]] + MergeAscending(left[1..], right)
    else if right[0] < left[0] then
      [right[0]] + MergeAscending(left, right[1..])
    else
      [left[0]] + MergeAscending(left[1..], right[1..])
  }

  function MergeDescending(
    left: seq<int>,
    right: seq<int>
  ): seq<int>
    requires StrictDescending(left)
    requires StrictDescending(right)
    ensures forall value ::
              value in MergeDescending(left, right) <==>
                       value in left || value in right
    decreases |left| + |right|
  {
    if |left| == 0 then
      right
    else if |right| == 0 then
      left
    else if left[0] > right[0] then
      [left[0]] + MergeDescending(left[1..], right)
    else if right[0] > left[0] then
      [right[0]] + MergeDescending(left, right[1..])
    else
      [left[0]] + MergeDescending(left[1..], right[1..])
  }

  lemma AscendingChunkReconstructsMerge(
    left: seq<int>,
    right: seq<int>
  )
    requires StrictAscending(left)
    requires StrictAscending(right)
    ensures
      var chunk := MergeAscendingChunkSpec(left, right);
      MergeAscending(left, right) ==
      chunk.values +
      MergeAscending(
        left[chunk.leftConsumed..],
        right[chunk.rightConsumed..]
      )
    decreases |left| + |right|
  {
    if |left| != 0 && |right| != 0 {
      AscendingTail(left);
      AscendingTail(right);
      if left[0] < right[0] {
        AscendingChunkReconstructsMerge(left[1..], right);
      } else if right[0] < left[0] {
        AscendingChunkReconstructsMerge(left, right[1..]);
      } else {
        AscendingChunkReconstructsMerge(left[1..], right[1..]);
      }
    }
  }

  lemma DescendingChunkReconstructsMerge(
    left: seq<int>,
    right: seq<int>
  )
    requires StrictDescending(left)
    requires StrictDescending(right)
    ensures
      var chunk := MergeDescendingChunkSpec(left, right);
      MergeDescending(left, right) ==
      chunk.values +
      MergeDescending(
        left[chunk.leftConsumed..],
        right[chunk.rightConsumed..]
      )
    decreases |left| + |right|
  {
    if |left| != 0 && |right| != 0 {
      DescendingTail(left);
      DescendingTail(right);
      if left[0] > right[0] {
        DescendingChunkReconstructsMerge(left[1..], right);
      } else if right[0] > left[0] {
        DescendingChunkReconstructsMerge(left, right[1..]);
      } else {
        DescendingChunkReconstructsMerge(left[1..], right[1..]);
      }
    }
  }

  lemma MergeAscendingIsStrict(
    left: seq<int>,
    right: seq<int>
  )
    requires StrictAscending(left)
    requires StrictAscending(right)
    ensures StrictAscending(MergeAscending(left, right))
    decreases |left| + |right|
  {
    if |left| != 0 && |right| != 0 {
      AscendingTail(left);
      AscendingTail(right);
      if left[0] < right[0] {
        MergeAscendingIsStrict(left[1..], right);
        forall value |
          value in MergeAscending(left[1..], right)
          ensures left[0] < value
        {
          if value in right {
            AscendingHeadBound(right, value);
          }
        }
        PrependAscending(
          left[0],
          MergeAscending(left[1..], right)
        );
      } else if right[0] < left[0] {
        MergeAscendingIsStrict(left, right[1..]);
        forall value |
          value in MergeAscending(left, right[1..])
          ensures right[0] < value
        {
          if value in left {
            AscendingHeadBound(left, value);
          }
        }
        PrependAscending(
          right[0],
          MergeAscending(left, right[1..])
        );
      } else {
        MergeAscendingIsStrict(left[1..], right[1..]);
        forall value |
          value in MergeAscending(left[1..], right[1..])
          ensures left[0] < value
        {
        }
        PrependAscending(
          left[0],
          MergeAscending(left[1..], right[1..])
        );
      }
    }
  }

  lemma MergeDescendingIsStrict(
    left: seq<int>,
    right: seq<int>
  )
    requires StrictDescending(left)
    requires StrictDescending(right)
    ensures StrictDescending(MergeDescending(left, right))
    decreases |left| + |right|
  {
    if |left| != 0 && |right| != 0 {
      DescendingTail(left);
      DescendingTail(right);
      if left[0] > right[0] {
        MergeDescendingIsStrict(left[1..], right);
        forall value |
          value in MergeDescending(left[1..], right)
          ensures left[0] > value
        {
          if value in right {
            DescendingHeadBound(right, value);
          }
        }
        PrependDescending(
          left[0],
          MergeDescending(left[1..], right)
        );
      } else if right[0] > left[0] {
        MergeDescendingIsStrict(left, right[1..]);
        forall value |
          value in MergeDescending(left, right[1..])
          ensures right[0] > value
        {
          if value in left {
            DescendingHeadBound(left, value);
          }
        }
        PrependDescending(
          right[0],
          MergeDescending(left, right[1..])
        );
      } else {
        MergeDescendingIsStrict(left[1..], right[1..]);
        forall value |
          value in MergeDescending(left[1..], right[1..])
          ensures left[0] > value
        {
        }
        PrependDescending(
          left[0],
          MergeDescending(left[1..], right[1..])
        );
      }
    }
  }

  method MergeAscendingRound(
    streams: seq<seq<int>>
  ) returns (round: seq<seq<int>>)
    requires AllStrictAscending(streams)
    ensures AllStrictAscending(round)
    ensures StreamSet(round) == StreamSet(streams)
    ensures |streams| == 0 ==> round == []
    ensures |streams| == 1 ==> round == streams
    ensures |streams| > 1 ==> 0 < |round| < |streams|
    decreases |streams|
  {
    if |streams| == 0 {
      return [];
    }
    if |streams| == 1 {
      return streams;
    }
    MergeAscendingIsStrict(streams[0], streams[1]);
    var tail := MergeAscendingRound(streams[2..]);
    round :=
      [MergeAscending(streams[0], streams[1])] + tail;
  }

  method MergeDescendingRound(
    streams: seq<seq<int>>
  ) returns (round: seq<seq<int>>)
    requires AllStrictDescending(streams)
    ensures AllStrictDescending(round)
    ensures StreamSet(round) == StreamSet(streams)
    ensures |streams| == 0 ==> round == []
    ensures |streams| == 1 ==> round == streams
    ensures |streams| > 1 ==> 0 < |round| < |streams|
    decreases |streams|
  {
    if |streams| == 0 {
      return [];
    }
    if |streams| == 1 {
      return streams;
    }
    MergeDescendingIsStrict(streams[0], streams[1]);
    var tail := MergeDescendingRound(streams[2..]);
    round :=
      [MergeDescending(streams[0], streams[1])] + tail;
  }

  method FoldBalancedAscending(
    streams: seq<seq<int>>
  ) returns (merged: seq<int>)
    requires AllStrictAscending(streams)
    ensures StrictAscending(merged)
    ensures SequenceSet(merged) == StreamSet(streams)
    decreases |streams|
  {
    if |streams| == 0 {
      return [];
    }
    if |streams| == 1 {
      return streams[0];
    }
    var round := MergeAscendingRound(streams);
    merged := FoldBalancedAscending(round);
  }

  method FoldBalancedDescending(
    streams: seq<seq<int>>
  ) returns (merged: seq<int>)
    requires AllStrictDescending(streams)
    ensures StrictDescending(merged)
    ensures SequenceSet(merged) == StreamSet(streams)
    decreases |streams|
  {
    if |streams| == 0 {
      return [];
    }
    if |streams| == 1 {
      return streams[0];
    }
    var round := MergeDescendingRound(streams);
    merged := FoldBalancedDescending(round);
  }

  lemma AscendingMergePreservesSetAndUniqueness(
    left: seq<int>,
    right: seq<int>
  )
    requires StrictAscending(left)
    requires StrictAscending(right)
    ensures StrictAscending(MergeAscending(left, right))
    ensures (set value |
               value in MergeAscending(left, right) :: value) ==
            (set value | value in left :: value) +
            (set value | value in right :: value)
  {
    MergeAscendingIsStrict(left, right);
  }

  lemma DescendingMergePreservesSetAndUniqueness(
    left: seq<int>,
    right: seq<int>
  )
    requires StrictDescending(left)
    requires StrictDescending(right)
    ensures StrictDescending(MergeDescending(left, right))
    ensures (set value |
               value in MergeDescending(left, right) :: value) ==
            (set value | value in left :: value) +
            (set value | value in right :: value)
  {
    MergeDescendingIsStrict(left, right);
  }
}
