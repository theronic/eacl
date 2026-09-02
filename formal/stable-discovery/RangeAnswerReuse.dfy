// Range answer reuse: a shorter page from one start boundary is a prefix of
// a longer completed page from the same boundary, and a request larger than
// a resident page that already reached the end of the enumeration is that
// page. Both public orders are deterministic functions of plan, snapshot, and
// boundary, so the internal `Page` over the fixed result sequence is the
// right abstraction; the production `eacl.client.range-reuse/derive-page`
// slices the retained results and edges exactly this way and is limited to
// least-path pages, whose cursors are history-free.
// Exploratory proof model; intentionally excluded from release artifacts.
include "StablePagination.dfy"

module RangeAnswerReuse {
  import opened StablePagination

  // Two cursors that share every context field but the page size and start
  // at the same ordinal.
  predicate SameStart(shorter: Cursor, longer: Cursor) {
    shorter.ordinal == longer.ordinal &&
    shorter.context.basis == longer.context.basis &&
    shorter.context.query == longer.context.query &&
    shorter.context.principal == longer.context.principal &&
    shorter.context.plan == longer.context.plan &&
    shorter.context.orderAbi == longer.context.orderAbi
  }

  // The shorter page is the prefix of the longer page of its own length.
  lemma ShorterPageIsPrefixOfLongerPage<T>(
    results: seq<T>,
    shorter: Cursor,
    longer: Cursor
  )
    requires ValidCursor(shorter.context, results, shorter)
    requires ValidCursor(longer.context, results, longer)
    requires SameStart(shorter, longer)
    requires shorter.context.pageSize <= longer.context.pageSize
    ensures |Page(results, shorter)| <= |Page(results, longer)|
    ensures Page(results, shorter) ==
            Page(results, longer)[..|Page(results, shorter)|]
  {
    var start := shorter.ordinal;
    var shortEnd := Min(start + shorter.context.pageSize, |results|);
    var longEnd := Min(start + longer.context.pageSize, |results|);
    assert shortEnd <= longEnd;
    assert Page(results, shorter) == results[start..shortEnd];
    assert Page(results, longer) == results[start..longEnd];
    assert results[start..longEnd][..shortEnd - start] == results[start..shortEnd];
  }

  // A resident page that reached the end of the results answers every larger
  // request from the same start unchanged: there is nothing beyond it.
  lemma CompletePageAnswersLargerRequest<T>(
    results: seq<T>,
    resident: Cursor,
    larger: Cursor
  )
    requires ValidCursor(resident.context, results, resident)
    requires ValidCursor(larger.context, results, larger)
    requires SameStart(resident, larger)
    requires resident.context.pageSize <= larger.context.pageSize
    requires resident.ordinal + resident.context.pageSize >= |results|
    ensures Page(results, larger) == Page(results, resident)
  {
    var start := resident.ordinal;
    assert Min(start + resident.context.pageSize, |results|) == |results|;
    assert Min(start + larger.context.pageSize, |results|) == |results|;
  }

  // The derived page's next-page flag: the shorter page has a next page iff
  // the longer page holds more than the shorter page's length or itself has
  // a next page.
  lemma DerivedNextPageFlag<T>(
    results: seq<T>,
    shorter: Cursor,
    longer: Cursor
  )
    requires ValidCursor(shorter.context, results, shorter)
    requires ValidCursor(longer.context, results, longer)
    requires SameStart(shorter, longer)
    requires shorter.context.pageSize <= longer.context.pageSize
    ensures (NextCursor(results, shorter).ordinal < |results|) <==>
            (|Page(results, longer)| > |Page(results, shorter)| ||
             NextCursor(results, longer).ordinal < |results|)
  {
    var start := shorter.ordinal;
    var shortEnd := Min(start + shorter.context.pageSize, |results|);
    var longEnd := Min(start + longer.context.pageSize, |results|);
    assert NextCursor(results, shorter).ordinal == shortEnd;
    assert NextCursor(results, longer).ordinal == longEnd;
    assert |Page(results, shorter)| == shortEnd - start;
    assert |Page(results, longer)| == longEnd - start;
  }
}
