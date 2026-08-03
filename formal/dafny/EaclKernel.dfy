include "Semantics.dfy"
include "AcyclicEngine.dfy"
include "CacheKernel.dfy"
include "CurrentCache.dfy"
include "OrderedMerge.dfy"
include "PageWindow.dfy"
include "Pagination.dfy"
include "RecursiveEngine.dfy"
include "TemporalSafety.dfy"
include "WireFormat.dfy"

module EaclKernel {
  import Semantics
  import AcyclicEngine
  import OrderedMerge
  import Pagination
  import TemporalSafety
  import WireFormat

  const WireVersion: string := "eacl.round-trip/v1"

  datatype WireError =
    | UnknownTag(tag: string)
    | TooManyValues(actualCount: nat, limit: nat)
    | NegativeValue(index: nat)

  datatype WireResult =
    | Accepted(items: seq<int>)
    | Rejected(error: WireError)

  ghost predicate ValidItems(items: seq<int>)
  {
    forall i | 0 <= i < |items| :: 0 <= items[i]
  }

  method RoundTrip(tag: string, items: seq<int>, maxItems: nat)
    returns (result: WireResult)
    ensures result.Accepted? <==>
            tag == WireVersion && |items| <= maxItems && ValidItems(items)
    ensures result.Accepted? ==> result.items == items
    ensures result.Rejected? <==>
            tag != WireVersion || |items| > maxItems || !ValidItems(items)
  {
    if tag != WireVersion {
      return Rejected(UnknownTag(tag));
    }

    if |items| > maxItems {
      return Rejected(TooManyValues(|items|, maxItems));
    }

    var i := 0;
    while i < |items|
      invariant 0 <= i <= |items|
      invariant forall j | 0 <= j < i :: 0 <= items[j]
      decreases |items| - i
    {
      if items[i] < 0 {
        return Rejected(NegativeValue(i));
      }
      i := i + 1;
    }

    return Accepted(items);
  }
}
