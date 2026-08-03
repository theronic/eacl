module OrderedMerge {
  predicate StrictAscending(values: seq<int>) {
    forall i, j | 0 <= i < j < |values| ::
      values[i] < values[j]
  }

  predicate StrictDescending(values: seq<int>) {
    forall i, j | 0 <= i < j < |values| ::
      values[i] > values[j]
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
