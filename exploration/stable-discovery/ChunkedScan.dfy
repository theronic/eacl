// Exploratory proof model; intentionally excluded from release artifacts.
module ChunkedScan {
  function Min(left: nat, right: nat): nat {
    if left <= right then left else right
  }

  function ChunkEnd<T>(
    values: seq<T>,
    offset: nat,
    limit: nat
  ): nat
    requires offset <= |values|
    requires 0 < limit
  {
    Min(offset + limit, |values|)
  }

  lemma ChunkEndBounds<T>(
    values: seq<T>,
    offset: nat,
    limit: nat
  )
    requires offset <= |values|
    requires 0 < limit
    ensures offset <= ChunkEnd(values, offset, limit) <= |values|
    ensures offset < |values| ==>
            offset < ChunkEnd(values, offset, limit)
  {
  }

  function Chunk<T>(
    values: seq<T>,
    offset: nat,
    limit: nat
  ): seq<T>
    requires offset <= |values|
    requires 0 < limit
  {
    values[offset..ChunkEnd(values, offset, limit)]
  }

  function Drain<T>(
    values: seq<T>,
    offset: nat,
    limit: nat
  ): seq<T>
    requires offset <= |values|
    requires 0 < limit
    decreases |values| - offset
  {
    if offset == |values| then
      []
    else
      Chunk(values, offset, limit) +
      Drain(values, ChunkEnd(values, offset, limit), limit)
  }

  lemma DrainIsExactSuffix<T>(
    values: seq<T>,
    offset: nat,
    limit: nat
  )
    requires offset <= |values|
    requires 0 < limit
    ensures Drain(values, offset, limit) == values[offset..]
    decreases |values| - offset
  {
    if offset < |values| {
      var end := ChunkEnd(values, offset, limit);
      ChunkEndBounds(values, offset, limit);
      DrainIsExactSuffix(values, end, limit);
      assert values[offset..] == values[offset..end] + values[end..];
    }
  }

  lemma ChunkSizeDoesNotChangeFlattenedScanValues<T>(
    values: seq<T>,
    offset: nat,
    leftLimit: nat,
    rightLimit: nat
  )
    requires offset <= |values|
    requires 0 < leftLimit
    requires 0 < rightLimit
    ensures Drain(values, offset, leftLimit) ==
            Drain(values, offset, rightLimit)
  {
    DrainIsExactSuffix(values, offset, leftLimit);
    DrainIsExactSuffix(values, offset, rightLimit);
  }

  lemma FirstChunkIsBounded<T>(
    values: seq<T>,
    offset: nat,
    limit: nat
  )
    requires offset <= |values|
    requires 0 < limit
    ensures |Chunk(values, offset, limit)| <= limit
    ensures |Chunk(values, offset, limit)| <= |values| - offset
  {
  }

}
