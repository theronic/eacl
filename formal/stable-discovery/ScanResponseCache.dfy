// Exact scan-response prefixes: a cached fetch is one of the fetches the
// chunked-scan lemmas already quantify over.
//
// An entry holds a prefix of one adapter scan sequence from its first value
// and whether that prefix is the complete sequence. Serving is sound when it
// returns exactly the chunk the adapter would return for the same offset and
// limit; extending by a contiguous reply keeps the prefix a prefix. Both are
// proved here over the abstract sequence, which is what the production
// `eacl.engine.scan-cache` namespace implements value for value.
// Exploratory proof model; intentionally excluded from release artifacts.
include "ChunkedScan.dfy"

module ScanResponseCache {
  import opened ChunkedScan

  // The scan sequence `values` is fixed for one immutable basis; an entry is
  // a prefix `values[..k]` with `exhausted` true only when `k == |values|`.
  predicate ValidEntry<T(==)>(values: seq<T>, prefix: seq<T>, exhausted: bool)
  {
    |prefix| <= |values| &&
    prefix == values[..|prefix|] &&
    (exhausted ==> |prefix| == |values|)
  }

  // The reducer's request: the exclusive bound has already been mapped to
  // the offset of the first value beyond it, so a request is (offset, limit).
  function Available<T(==)>(prefix: seq<T>, offset: nat): nat
    requires offset <= |prefix|
  {
    |prefix| - offset
  }

  // Serve returns Some(chunk) exactly when the prefix can reproduce the
  // adapter's reply: enough values beyond the offset, or the complete scan.
  datatype Reply<T(==)> = None | Some(chunk: seq<T>)

  function Serve<T(==)>(
    prefix: seq<T>,
    exhausted: bool,
    offset: nat,
    limit: nat
  ): Reply<T>
    requires offset <= |prefix|
    requires 0 < limit
  {
    if Available(prefix, offset) >= limit then
      Some(prefix[offset..offset + limit])
    else if exhausted then
      Some(prefix[offset..])
    else
      None
  }

  // A served reply equals the adapter's chunk for the same offset and limit.
  lemma ServeIsExactChunk<T>(
    values: seq<T>,
    prefix: seq<T>,
    exhausted: bool,
    offset: nat,
    limit: nat
  )
    requires ValidEntry(values, prefix, exhausted)
    requires offset <= |prefix|
    requires 0 < limit
    requires Serve(prefix, exhausted, offset, limit).Some?
    ensures Serve(prefix, exhausted, offset, limit).chunk ==
            Chunk(values, offset, limit)
  {
    if Available(prefix, offset) >= limit {
      assert offset + limit <= |prefix| <= |values|;
      assert ChunkEnd(values, offset, limit) == offset + limit;
      assert prefix[offset..offset + limit] == values[offset..offset + limit];
    } else {
      assert exhausted;
      assert |prefix| == |values|;
      assert ChunkEnd(values, offset, limit) == |values|;
      assert prefix[offset..] == values[offset..];
    }
  }

  // A miss forwards the identical command; its reply is the adapter's chunk
  // at the offset. Extending a prefix by that chunk when the offset lies
  // within the prefix (or at its end) yields a prefix again, exhausted
  // exactly when the reply was short.
  function Extend<T(==)>(
    values: seq<T>,
    prefix: seq<T>,
    offset: nat,
    limit: nat
  ): seq<T>
    requires offset <= |prefix| <= |values|
    requires 0 < limit
  {
    prefix[..offset] + Chunk(values, offset, limit)
  }

  lemma ExtendIsPrefix<T>(
    values: seq<T>,
    prefix: seq<T>,
    exhausted: bool,
    offset: nat,
    limit: nat
  )
    requires ValidEntry(values, prefix, exhausted)
    requires offset <= |prefix|
    requires 0 < limit
    ensures ValidEntry(values,
                       Extend(values, prefix, offset, limit),
                       |Chunk(values, offset, limit)| < limit)
  {
    var extended := Extend(values, prefix, offset, limit);
    var end := ChunkEnd(values, offset, limit);
    ChunkEndBounds(values, offset, limit);
    assert extended == values[..offset] + values[offset..end];
    assert extended == values[..end];
    if |Chunk(values, offset, limit)| < limit {
      assert end - offset < limit;
      assert end == |values|;
    }
  }

  // The extension never shortens a prefix that already covers the reply, so
  // retaining the longer of two concurrent extensions is always a prefix.
  lemma LongerPrefixWins<T>(
    values: seq<T>,
    shorter: seq<T>,
    longer: seq<T>
  )
    requires ValidEntry(values, shorter, false)
    requires ValidEntry(values, longer, false)
    requires |shorter| <= |longer|
    ensures shorter == longer[..|shorter|]
  {
    assert shorter == values[..|shorter|];
    assert longer == values[..|longer|];
  }

  // A fragment that does not start at a known offset is never admitted: the
  // only admitted prefixes are the ones built by Extend from the scan start.
  lemma FirstChunkIsPrefix<T>(values: seq<T>, limit: nat)
    requires 0 < limit
    ensures ValidEntry(values, Chunk(values, 0, limit),
                       |Chunk(values, 0, limit)| < limit)
  {
    ExtendIsPrefix(values, [], false, 0, limit);
    assert Extend(values, [], 0, limit) == Chunk(values, 0, limit);
  }
}
