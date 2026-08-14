// Any exact online membership authority must distinguish admitted subsets.
// Exploratory proof model; intentionally excluded from release artifacts.
module ExactDedupLowerBound {
  lemma ExactMembershipSummaryIsInjective<T, M>(
    encode: (set<T>) -> M,
    query: (M, T) -> bool,
    left: set<T>,
    right: set<T>
  )
    requires forall seen: set<T>, value: T ::
      query(encode(seen), value) == (value in seen)
    ensures left != right ==> encode(left) != encode(right)
  {
    if left != right {
      assert exists value: T ::
        (value in left) != (value in right);
      var differingValue: T :|
        (differingValue in left) != (differingValue in right);
      if encode(left) == encode(right) {
        assert query(encode(left), differingValue) ==
               (differingValue in left);
        assert query(encode(right), differingValue) ==
               (differingValue in right);
        assert false;
      }
    }
  }

  lemma EqualCountsDoNotDetermineMembership()
    ensures |{0}| == |{1}|
    ensures 0 in {0}
    ensures 0 !in {1}
  {
  }
}
