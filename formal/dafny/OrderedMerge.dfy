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

  function SourceNonEmptyStreams(
    streams: seq<seq<int>>
  ): seq<seq<int>>
    ensures |SourceNonEmptyStreams(streams)| <= |streams|
    decreases |streams|
  {
    if |streams| == 0 then
      []
    else
      (if |streams[0]| == 0 then [] else [streams[0]]) +
      SourceNonEmptyStreams(streams[1..])
  }

  lemma SourceNonEmptyAscendingProperties(
    streams: seq<seq<int>>
  )
    requires AllStrictAscending(streams)
    ensures AllStrictAscending(SourceNonEmptyStreams(streams))
    ensures StreamSet(SourceNonEmptyStreams(streams)) ==
            StreamSet(streams)
    decreases |streams|
  {
    if |streams| != 0 {
      assert AllStrictAscending(streams[1..]);
      SourceNonEmptyAscendingProperties(streams[1..]);
      if |streams[0]| != 0 {
        assert StreamSet(SourceNonEmptyStreams(streams)) ==
               SequenceSet(streams[0]) +
               StreamSet(SourceNonEmptyStreams(streams[1..]));
        forall index | 0 <= index <
                       |SourceNonEmptyStreams(streams)|
          ensures StrictAscending(
                    SourceNonEmptyStreams(streams)[index]
                  )
        {
          if index != 0 {
            assert SourceNonEmptyStreams(streams)[index] ==
                   SourceNonEmptyStreams(streams[1..])[index - 1];
          }
        }
      } else {
        assert SequenceSet(streams[0]) == {};
        assert SourceNonEmptyStreams(streams) ==
               SourceNonEmptyStreams(streams[1..]);
      }
    }
  }

  lemma SourceNonEmptyDescendingProperties(
    streams: seq<seq<int>>
  )
    requires AllStrictDescending(streams)
    ensures AllStrictDescending(SourceNonEmptyStreams(streams))
    ensures StreamSet(SourceNonEmptyStreams(streams)) ==
            StreamSet(streams)
    decreases |streams|
  {
    if |streams| != 0 {
      assert AllStrictDescending(streams[1..]);
      SourceNonEmptyDescendingProperties(streams[1..]);
      if |streams[0]| != 0 {
        assert StreamSet(SourceNonEmptyStreams(streams)) ==
               SequenceSet(streams[0]) +
               StreamSet(SourceNonEmptyStreams(streams[1..]));
        forall index | 0 <= index <
                       |SourceNonEmptyStreams(streams)|
          ensures StrictDescending(
                    SourceNonEmptyStreams(streams)[index]
                  )
        {
          if index != 0 {
            assert SourceNonEmptyStreams(streams)[index] ==
                   SourceNonEmptyStreams(streams[1..])[index - 1];
          }
        }
      } else {
        assert SequenceSet(streams[0]) == {};
        assert SourceNonEmptyStreams(streams) ==
               SourceNonEmptyStreams(streams[1..]);
      }
    }
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

  function SourceDropLeadingValue(
    values: seq<int>,
    last: OptionalLast
  ): seq<int>
    decreases |values|
  {
    if |values| == 0 || last.NoLast? || values[0] != last.value then
      values
    else
      SourceDropLeadingValue(values[1..], last)
  }

  function SourceMergeAscending(
    last: OptionalLast,
    left: seq<int>,
    right: seq<int>
  ): seq<int>
    decreases |left| + |right|
  {
    if |left| == 0 then
      SourceDropLeadingValue(right, last)
    else if |right| == 0 then
      SourceDropLeadingValue(left, last)
    else if left[0] == right[0] then
      if PreviouslyEmitted(last, left[0]) then
        SourceMergeAscending(last, left[1..], right[1..])
      else
        [left[0]] +
        SourceMergeAscending(Last(left[0]), left[1..], right[1..])
    else if left[0] < right[0] then
      if PreviouslyEmitted(last, left[0]) then
        SourceMergeAscending(last, left[1..], right)
      else
        [left[0]] +
        SourceMergeAscending(Last(left[0]), left[1..], right)
    else
    if PreviouslyEmitted(last, right[0]) then
      SourceMergeAscending(last, left, right[1..])
    else
      [right[0]] +
      SourceMergeAscending(Last(right[0]), left, right[1..])
  }

  function SourceMergeDescending(
    last: OptionalLast,
    left: seq<int>,
    right: seq<int>
  ): seq<int>
    decreases |left| + |right|
  {
    if |left| == 0 then
      SourceDropLeadingValue(right, last)
    else if |right| == 0 then
      SourceDropLeadingValue(left, last)
    else if left[0] == right[0] then
      if PreviouslyEmitted(last, left[0]) then
        SourceMergeDescending(last, left[1..], right[1..])
      else
        [left[0]] +
        SourceMergeDescending(Last(left[0]), left[1..], right[1..])
    else if left[0] > right[0] then
      if PreviouslyEmitted(last, left[0]) then
        SourceMergeDescending(last, left[1..], right)
      else
        [left[0]] +
        SourceMergeDescending(Last(left[0]), left[1..], right)
    else
    if PreviouslyEmitted(last, right[0]) then
      SourceMergeDescending(last, left, right[1..])
    else
      [right[0]] +
      SourceMergeDescending(Last(right[0]), left, right[1..])
  }

  function SourceMergeAscendingIterations(
    last: OptionalLast,
    left: seq<int>,
    right: seq<int>
  ): nat
    decreases |left| + |right|
  {
    if |left| == 0 || |right| == 0 then
      0
    else if left[0] == right[0] then
      1 +
      SourceMergeAscendingIterations(last, left[1..], right[1..])
    else if left[0] < right[0] then
      1 + SourceMergeAscendingIterations(last, left[1..], right)
    else
      1 + SourceMergeAscendingIterations(last, left, right[1..])
  }

  function SourceMergeDescendingIterations(
    last: OptionalLast,
    left: seq<int>,
    right: seq<int>
  ): nat
    decreases |left| + |right|
  {
    if |left| == 0 || |right| == 0 then
      0
    else if left[0] == right[0] then
      1 +
      SourceMergeDescendingIterations(last, left[1..], right[1..])
    else if left[0] > right[0] then
      1 + SourceMergeDescendingIterations(last, left[1..], right)
    else
      1 + SourceMergeDescendingIterations(last, left, right[1..])
  }

  lemma SourceMergeAscendingIterationsAreLinear(
    last: OptionalLast,
    left: seq<int>,
    right: seq<int>
  )
    ensures SourceMergeAscendingIterations(last, left, right) <=
            |left| + |right|
    decreases |left| + |right|
  {
    if |left| != 0 && |right| != 0 {
      if left[0] == right[0] {
        SourceMergeAscendingIterationsAreLinear(
          last,
          left[1..],
          right[1..]
        );
      } else if left[0] < right[0] {
        SourceMergeAscendingIterationsAreLinear(
          last,
          left[1..],
          right
        );
      } else {
        SourceMergeAscendingIterationsAreLinear(
          last,
          left,
          right[1..]
        );
      }
    }
  }

  lemma SourceMergeDescendingIterationsAreLinear(
    last: OptionalLast,
    left: seq<int>,
    right: seq<int>
  )
    ensures SourceMergeDescendingIterations(last, left, right) <=
            |left| + |right|
    decreases |left| + |right|
  {
    if |left| != 0 && |right| != 0 {
      if left[0] == right[0] {
        SourceMergeDescendingIterationsAreLinear(
          last,
          left[1..],
          right[1..]
        );
      } else if left[0] > right[0] {
        SourceMergeDescendingIterationsAreLinear(
          last,
          left[1..],
          right
        );
      } else {
        SourceMergeDescendingIterationsAreLinear(
          last,
          left,
          right[1..]
        );
      }
    }
  }

  predicate AscendingAfterLast(
    last: OptionalLast,
    values: seq<int>
  ) {
    last.NoLast? ||
    forall value | value in values :: last.value < value
  }

  predicate DescendingAfterLast(
    last: OptionalLast,
    values: seq<int>
  ) {
    last.NoLast? ||
    forall value | value in values :: last.value > value
  }

  lemma SourceDropLeadingAscendingLastIsAbsent(
    values: seq<int>,
    last: OptionalLast
  )
    requires AscendingAfterLast(last, values)
    ensures SourceDropLeadingValue(values, last) == values
  {
    if |values| != 0 && last.Last? {
      assert values[0] in values;
      assert last.value < values[0];
    }
  }

  lemma SourceDropLeadingDescendingLastIsAbsent(
    values: seq<int>,
    last: OptionalLast
  )
    requires DescendingAfterLast(last, values)
    ensures SourceDropLeadingValue(values, last) == values
  {
    if |values| != 0 && last.Last? {
      assert values[0] in values;
      assert last.value > values[0];
    }
  }

  lemma SourceMergeAscendingRefinesCanonical(
    last: OptionalLast,
    left: seq<int>,
    right: seq<int>
  )
    requires StrictAscending(left)
    requires StrictAscending(right)
    requires AscendingAfterLast(last, left)
    requires AscendingAfterLast(last, right)
    ensures SourceMergeAscending(last, left, right) ==
            MergeAscending(left, right)
    decreases |left| + |right|
  {
    if |left| == 0 {
      SourceDropLeadingAscendingLastIsAbsent(right, last);
    } else if |right| == 0 {
      SourceDropLeadingAscendingLastIsAbsent(left, last);
    } else {
      AscendingTail(left);
      AscendingTail(right);
      if left[0] == right[0] {
        if last.Last? {
          assert left[0] in left;
          assert last.value < left[0];
        }
        assert !PreviouslyEmitted(last, left[0]);
        assert AscendingAfterLast(Last(left[0]), left[1..]);
        assert AscendingAfterLast(Last(left[0]), right[1..]);
        SourceMergeAscendingRefinesCanonical(
          Last(left[0]),
          left[1..],
          right[1..]
        );
      } else if left[0] < right[0] {
        if last.Last? {
          assert left[0] in left;
          assert last.value < left[0];
        }
        assert !PreviouslyEmitted(last, left[0]);
        assert AscendingAfterLast(Last(left[0]), left[1..]);
        forall value | value in right
          ensures left[0] < value
        {
          AscendingHeadBound(right, value);
        }
        assert AscendingAfterLast(Last(left[0]), right);
        SourceMergeAscendingRefinesCanonical(
          Last(left[0]),
          left[1..],
          right
        );
      } else {
        assert right[0] < left[0];
        if last.Last? {
          assert right[0] in right;
          assert last.value < right[0];
        }
        assert !PreviouslyEmitted(last, right[0]);
        forall value | value in left
          ensures right[0] < value
        {
          AscendingHeadBound(left, value);
        }
        assert AscendingAfterLast(Last(right[0]), left);
        assert AscendingAfterLast(Last(right[0]), right[1..]);
        SourceMergeAscendingRefinesCanonical(
          Last(right[0]),
          left,
          right[1..]
        );
      }
    }
  }

  lemma SourceMergeDescendingRefinesCanonical(
    last: OptionalLast,
    left: seq<int>,
    right: seq<int>
  )
    requires StrictDescending(left)
    requires StrictDescending(right)
    requires DescendingAfterLast(last, left)
    requires DescendingAfterLast(last, right)
    ensures SourceMergeDescending(last, left, right) ==
            MergeDescending(left, right)
    decreases |left| + |right|
  {
    if |left| == 0 {
      SourceDropLeadingDescendingLastIsAbsent(right, last);
    } else if |right| == 0 {
      SourceDropLeadingDescendingLastIsAbsent(left, last);
    } else {
      DescendingTail(left);
      DescendingTail(right);
      if left[0] == right[0] {
        if last.Last? {
          assert left[0] in left;
          assert last.value > left[0];
        }
        assert !PreviouslyEmitted(last, left[0]);
        assert DescendingAfterLast(Last(left[0]), left[1..]);
        assert DescendingAfterLast(Last(left[0]), right[1..]);
        SourceMergeDescendingRefinesCanonical(
          Last(left[0]),
          left[1..],
          right[1..]
        );
      } else if left[0] > right[0] {
        if last.Last? {
          assert left[0] in left;
          assert last.value > left[0];
        }
        assert !PreviouslyEmitted(last, left[0]);
        assert DescendingAfterLast(Last(left[0]), left[1..]);
        forall value | value in right
          ensures left[0] > value
        {
          DescendingHeadBound(right, value);
        }
        assert DescendingAfterLast(Last(left[0]), right);
        SourceMergeDescendingRefinesCanonical(
          Last(left[0]),
          left[1..],
          right
        );
      } else {
        assert right[0] > left[0];
        if last.Last? {
          assert right[0] in right;
          assert last.value > right[0];
        }
        assert !PreviouslyEmitted(last, right[0]);
        forall value | value in left
          ensures right[0] > value
        {
          DescendingHeadBound(left, value);
        }
        assert DescendingAfterLast(Last(right[0]), left);
        assert DescendingAfterLast(Last(right[0]), right[1..]);
        SourceMergeDescendingRefinesCanonical(
          Last(right[0]),
          left,
          right[1..]
        );
      }
    }
  }

  lemma ProductionAscendingMergeRefinesCanonical(
    left: seq<int>,
    right: seq<int>
  )
    requires StrictAscending(left)
    requires StrictAscending(right)
    ensures SourceMergeAscending(NoLast, left, right) ==
            MergeAscending(left, right)
  {
    SourceMergeAscendingRefinesCanonical(NoLast, left, right);
  }

  lemma ProductionDescendingMergeRefinesCanonical(
    left: seq<int>,
    right: seq<int>
  )
    requires StrictDescending(left)
    requires StrictDescending(right)
    ensures SourceMergeDescending(NoLast, left, right) ==
            MergeDescending(left, right)
  {
    SourceMergeDescendingRefinesCanonical(NoLast, left, right);
  }

  method ExecuteProductionMerge(
    direction: MergeDirection,
    left: seq<int>,
    right: seq<int>
  ) returns (merged: seq<int>)
    requires direction.Ascending? ==>
               StrictAscending(left) && StrictAscending(right)
    requires direction.Descending? ==>
               StrictDescending(left) && StrictDescending(right)
    ensures direction.Ascending? ==>
              merged == MergeAscending(left, right)
    ensures direction.Descending? ==>
              merged == MergeDescending(left, right)
  {
    if direction.Ascending? {
      ProductionAscendingMergeRefinesCanonical(left, right);
      return SourceMergeAscending(NoLast, left, right);
    }
    ProductionDescendingMergeRefinesCanonical(left, right);
    return SourceMergeDescending(NoLast, left, right);
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

  lemma SequenceSetMembership(
    values: seq<int>,
    value: int
  )
    ensures value in SequenceSet(values) <==> value in values
  {
  }

  lemma StrictAscendingSequenceIsDeterminedBySet(
    left: seq<int>,
    right: seq<int>
  )
    requires StrictAscending(left)
    requires StrictAscending(right)
    requires SequenceSet(left) == SequenceSet(right)
    ensures left == right
    decreases |left| + |right|
  {
    if |left| == 0 {
      if |right| != 0 {
        SequenceSetMembership(right, right[0]);
        assert right[0] in SequenceSet(right);
        assert right[0] in SequenceSet(left);
        SequenceSetMembership(left, right[0]);
      }
    } else if |right| == 0 {
      SequenceSetMembership(left, left[0]);
      assert left[0] in SequenceSet(left);
      assert left[0] in SequenceSet(right);
      SequenceSetMembership(right, left[0]);
    } else {
      SequenceSetMembership(left, left[0]);
      SequenceSetMembership(right, left[0]);
      assert left[0] in right;
      AscendingHeadBound(right, left[0]);

      SequenceSetMembership(right, right[0]);
      SequenceSetMembership(left, right[0]);
      assert right[0] in left;
      AscendingHeadBound(left, right[0]);
      assert left[0] == right[0];

      AscendingTail(left);
      AscendingTail(right);
      assert left[0] !in left[1..];
      assert right[0] !in right[1..];
      forall value
        ensures value in left[1..] <==> value in right[1..]
      {
        SequenceSetMembership(left, value);
        SequenceSetMembership(right, value);
        if value == left[0] {
          assert value !in left[1..];
          assert value !in right[1..];
        }
      }
      assert SequenceSet(left[1..]) == SequenceSet(right[1..]);
      StrictAscendingSequenceIsDeterminedBySet(
        left[1..],
        right[1..]
      );
    }
  }

  lemma StrictDescendingSequenceIsDeterminedBySet(
    left: seq<int>,
    right: seq<int>
  )
    requires StrictDescending(left)
    requires StrictDescending(right)
    requires SequenceSet(left) == SequenceSet(right)
    ensures left == right
    decreases |left| + |right|
  {
    if |left| == 0 {
      if |right| != 0 {
        SequenceSetMembership(right, right[0]);
        assert right[0] in SequenceSet(right);
        assert right[0] in SequenceSet(left);
        SequenceSetMembership(left, right[0]);
      }
    } else if |right| == 0 {
      SequenceSetMembership(left, left[0]);
      assert left[0] in SequenceSet(left);
      assert left[0] in SequenceSet(right);
      SequenceSetMembership(right, left[0]);
    } else {
      SequenceSetMembership(left, left[0]);
      SequenceSetMembership(right, left[0]);
      assert left[0] in right;
      DescendingHeadBound(right, left[0]);

      SequenceSetMembership(right, right[0]);
      SequenceSetMembership(left, right[0]);
      assert right[0] in left;
      DescendingHeadBound(left, right[0]);
      assert left[0] == right[0];

      DescendingTail(left);
      DescendingTail(right);
      assert left[0] !in left[1..];
      assert right[0] !in right[1..];
      forall value
        ensures value in left[1..] <==> value in right[1..]
      {
        SequenceSetMembership(left, value);
        SequenceSetMembership(right, value);
        if value == left[0] {
          assert value !in left[1..];
          assert value !in right[1..];
        }
      }
      assert SequenceSet(left[1..]) == SequenceSet(right[1..]);
      StrictDescendingSequenceIsDeterminedBySet(
        left[1..],
        right[1..]
      );
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

  method SourceMergeAscendingRound(
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
    ProductionAscendingMergeRefinesCanonical(
      streams[0],
      streams[1]
    );
    MergeAscendingIsStrict(streams[0], streams[1]);
    var tail := SourceMergeAscendingRound(streams[2..]);
    round :=
      [SourceMergeAscending(NoLast, streams[0], streams[1])] +
      tail;
  }

  method SourceMergeDescendingRound(
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
    ProductionDescendingMergeRefinesCanonical(
      streams[0],
      streams[1]
    );
    MergeDescendingIsStrict(streams[0], streams[1]);
    var tail := SourceMergeDescendingRound(streams[2..]);
    round :=
      [SourceMergeDescending(NoLast, streams[0], streams[1])] +
      tail;
  }

  method SourceFoldBalancedAscending(
    streams: seq<seq<int>>
  ) returns (merged: seq<int>)
    requires AllStrictAscending(streams)
    ensures StrictAscending(merged)
    ensures SequenceSet(merged) == StreamSet(streams)
    decreases |streams|
  {
    SourceNonEmptyAscendingProperties(streams);
    var nonEmpty := SourceNonEmptyStreams(streams);
    if |nonEmpty| == 0 {
      return [];
    }
    if |nonEmpty| == 1 {
      return nonEmpty[0];
    }
    var round := SourceMergeAscendingRound(nonEmpty);
    assert |round| < |nonEmpty| <= |streams|;
    merged := SourceFoldBalancedAscending(round);
  }

  method SourceFoldBalancedDescending(
    streams: seq<seq<int>>
  ) returns (merged: seq<int>)
    requires AllStrictDescending(streams)
    ensures StrictDescending(merged)
    ensures SequenceSet(merged) == StreamSet(streams)
    decreases |streams|
  {
    SourceNonEmptyDescendingProperties(streams);
    var nonEmpty := SourceNonEmptyStreams(streams);
    if |nonEmpty| == 0 {
      return [];
    }
    if |nonEmpty| == 1 {
      return nonEmpty[0];
    }
    var round := SourceMergeDescendingRound(nonEmpty);
    assert |round| < |nonEmpty| <= |streams|;
    merged := SourceFoldBalancedDescending(round);
  }

  method ExecuteProductionFold(
    direction: MergeDirection,
    streams: seq<seq<int>>
  ) returns (merged: seq<int>)
    requires direction.Ascending? ==> AllStrictAscending(streams)
    requires direction.Descending? ==> AllStrictDescending(streams)
    ensures direction.Ascending? ==> StrictAscending(merged)
    ensures direction.Descending? ==> StrictDescending(merged)
    ensures SequenceSet(merged) == StreamSet(streams)
  {
    if direction.Ascending? {
      merged := SourceFoldBalancedAscending(streams);
      return;
    }
    merged := SourceFoldBalancedDescending(streams);
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

  method ProductionAscendingFoldRefinesCanonical(
    streams: seq<seq<int>>
  ) returns (
      sourceMerged: seq<int>,
      canonicalMerged: seq<int>
    )
    requires AllStrictAscending(streams)
    ensures sourceMerged == canonicalMerged
  {
    sourceMerged := SourceFoldBalancedAscending(streams);
    canonicalMerged := FoldBalancedAscending(streams);
    StrictAscendingSequenceIsDeterminedBySet(
      sourceMerged,
      canonicalMerged
    );
  }

  method ProductionDescendingFoldRefinesCanonical(
    streams: seq<seq<int>>
  ) returns (
      sourceMerged: seq<int>,
      canonicalMerged: seq<int>
    )
    requires AllStrictDescending(streams)
    ensures sourceMerged == canonicalMerged
  {
    sourceMerged := SourceFoldBalancedDescending(streams);
    canonicalMerged := FoldBalancedDescending(streams);
    StrictDescendingSequenceIsDeterminedBySet(
      sourceMerged,
      canonicalMerged
    );
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
