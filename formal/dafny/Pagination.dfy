module Pagination {
  datatype Direction = Ascending | Descending
  datatype Boundary =
    | NoBoundary
    | Exclusive(value: int)
    | Inclusive(value: int)
  datatype Frontier =
    | Active(boundary: int)
    | Exhausted

  predicate StrictlyOrdered(
    direction: Direction,
    values: seq<int>
  ) {
    match direction
    case Ascending =>
      forall i, j | 0 <= i < j < |values| ::
        values[i] < values[j]
    case Descending =>
      forall i, j | 0 <= i < j < |values| ::
        values[i] > values[j]
  }

  predicate Allows(
    direction: Direction,
    boundary: Boundary,
    value: int
  ) {
    match boundary
    case NoBoundary => true
    case Exclusive(edge) =>
      if direction.Ascending?
      then edge < value
      else value < edge
    case Inclusive(edge) =>
      if direction.Ascending?
      then edge <= value
      else value <= edge
  }

  lemma OrderedTail(
    direction: Direction,
    values: seq<int>
  )
    requires StrictlyOrdered(direction, values)
    requires |values| != 0
    ensures StrictlyOrdered(direction, values[1..])
  {
  }

  lemma AllowedHeadImpliesAllowedTail(
    direction: Direction,
    boundary: Boundary,
    values: seq<int>
  )
    requires StrictlyOrdered(direction, values)
    requires |values| != 0
    requires Allows(direction, boundary, values[0])
    ensures forall value | value in values ::
              Allows(direction, boundary, value)
  {
  }

  function ApplyBoundary(
    direction: Direction,
    boundary: Boundary,
    values: seq<int>
  ): seq<int>
    requires StrictlyOrdered(direction, values)
    ensures StrictlyOrdered(
              direction,
              ApplyBoundary(direction, boundary, values)
            )
    ensures forall value |
              value in ApplyBoundary(direction, boundary, values) ::
              Allows(direction, boundary, value)
    decreases |values|
  {
    if |values| == 0 then
      []
    else if Allows(direction, boundary, values[0]) then
      values
    else
      ApplyBoundary(direction, boundary, values[1..])
  }

  lemma ApplyBoundaryMembership(
    direction: Direction,
    boundary: Boundary,
    values: seq<int>,
    value: int
  )
    requires StrictlyOrdered(direction, values)
    ensures value in ApplyBoundary(direction, boundary, values) <==>
            value in values && Allows(direction, boundary, value)
    decreases |values|
  {
    if |values| != 0 {
      OrderedTail(direction, values);
      if Allows(direction, boundary, values[0]) {
        AllowedHeadImpliesAllowedTail(
          direction,
          boundary,
          values
        );
      } else {
        ApplyBoundaryMembership(
          direction,
          boundary,
          values[1..],
          value
        );
      }
    }
  }

  function Resume(
    direction: Direction,
    frontier: Frontier,
    values: seq<int>
  ): seq<int>
    requires StrictlyOrdered(direction, values)
    ensures StrictlyOrdered(
              direction,
              Resume(direction, frontier, values)
            )
  {
    match frontier
    case Exhausted => []
    case Active(boundary) =>
      ApplyBoundary(direction, Exclusive(boundary), values)
  }

  lemma ExhaustedFrontierReturnsNothing(
    direction: Direction,
    values: seq<int>
  )
    requires StrictlyOrdered(direction, values)
    ensures Resume(direction, Exhausted, values) == []
  {
  }

  lemma ActiveFrontierIsExclusive(
    direction: Direction,
    boundary: int,
    values: seq<int>,
    value: int
  )
    requires StrictlyOrdered(direction, values)
    ensures value in Resume(
                       direction,
                       Active(boundary),
                       values
                     ) <==>
            value in values &&
            Allows(direction, Exclusive(boundary), value)
  {
    ApplyBoundaryMembership(
      direction,
      Exclusive(boundary),
      values,
      value
    );
  }

  lemma AscendingExclusiveEqualsInclusiveSuccessor(
    boundary: int,
    value: int
  )
    ensures Allows(Ascending, Exclusive(boundary), value) <==>
            Allows(Ascending, Inclusive(boundary + 1), value)
  {
  }

  lemma DescendingExclusiveEqualsInclusivePredecessor(
    boundary: int,
    value: int
  )
    ensures Allows(Descending, Exclusive(boundary), value) <==>
            Allows(Descending, Inclusive(boundary - 1), value)
  {
  }

  lemma DirectionReversalChangesBoundarySide(
    boundary: int,
    value: int
  )
    ensures Allows(Ascending, Exclusive(boundary), value) <==>
            Allows(Descending, Exclusive(value), boundary)
  {
  }
}
