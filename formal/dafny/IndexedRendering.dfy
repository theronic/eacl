include "IndexedTraversal.dfy"

module IndexedRendering {
  import Indexed = IndexedTraversal

  predicate ValidEmissionSuffix(
    render: Indexed.RenderState,
    values: seq<int>
  ) {
    Indexed.ValidRenderState(render) &&
    (forall eid <- values :: 0 <= eid) &&
    Indexed.UniqueEids(values) &&
    (forall eid <- values :: eid !in render.emitted)
  }

  lemma DeliveredMembershipImpliesEmittedMembership(
    render: Indexed.RenderState,
    eid: int
  )
    requires Indexed.ValidRenderState(render)
    requires eid in render.delivered
    ensures eid in render.emitted
  {
    assert forall value <- render.delivered ::
        value in render.emitted;
  }

  lemma ValidEmissionHead(
    render: Indexed.RenderState,
    values: seq<int>
  )
    requires ValidEmissionSuffix(render, values)
    requires 0 < |values|
    ensures 0 <= values[0]
    ensures values[0] !in render.emitted
    ensures values[0] !in render.delivered
  {
    assert values[0] in values;
    assert forall eid <- values :: 0 <= eid;
    assert forall eid <- values :: eid !in render.emitted;
    assert 0 <= values[0];
    assert values[0] !in render.emitted;
    assert (set eid <- render.delivered) <=
           (set eid <- render.emitted);
    if values[0] in render.delivered {
      DeliveredMembershipImpliesEmittedMembership(
        render,
        values[0]
      );
      assert false;
    }
  }

  lemma ValidEmissionTail(
    render: Indexed.RenderState,
    values: seq<int>
  )
    requires ValidEmissionSuffix(render, values)
    requires 0 < |values|
    ensures (forall eid <- values[1..] :: 0 <= eid)
    ensures Indexed.UniqueEids(values[1..])
    ensures
      forall eid <- values[1..] ::
        eid !in render.emitted &&
        eid != values[0]
  {
    assert forall eid <- values :: 0 <= eid;
    assert forall eid <- values :: eid !in render.emitted;
    assert forall eid <- values[1..] :: eid in values;
    assert forall eid <- values[1..] :: 0 <= eid;
    Indexed.UniqueSequenceTail(values);
    forall eid <- values[1..]
      ensures eid !in render.emitted &&
              eid != values[0]
    {
      assert eid in values;
      if eid == values[0] {
        var index :| 0 <= index < |values[1..]| &&
                     values[1..][index] == eid;
        assert values[index + 1] == values[0];
        assert 0 < index + 1 < |values|;
        assert false;
      }
    }
  }

  lemma ValidSuffixAfterAdvance(
    render: Indexed.RenderState,
    values: seq<int>
  )
    requires ValidEmissionSuffix(render, values)
    requires 0 < |values|
    requires !render.complete
    requires CheckedAdvanceRenderSpec(
               render,
               values[0]
             ).RenderProgress?
    ensures
      ValidEmissionSuffix(
        CheckedAdvanceRenderSpec(render, values[0]).state,
        values[1..]
      )
  {
    ValidEmissionHead(render, values);
    var advanced :=
      Indexed.AdvanceRenderSpec(render, values[0]);
    assert CheckedAdvanceRenderSpec(render, values[0]) ==
           advanced;
    ValidEmissionTail(render, values);
    assert advanced.state.emitted ==
           render.emitted + [values[0]];
    assert forall eid <- values[1..] ::
        eid !in advanced.state.emitted by {
      forall eid <- values[1..]
        ensures eid !in advanced.state.emitted
      {
        assert eid !in render.emitted;
        assert eid != values[0];
      }
    }
  }

  lemma ValidEmissionPrefix(
    render: Indexed.RenderState,
    prefix: seq<int>,
    suffix: seq<int>
  )
    requires ValidEmissionSuffix(render, prefix + suffix)
    ensures ValidEmissionSuffix(render, prefix)
  {
    assert forall eid <- prefix :: eid in prefix + suffix;
    assert forall eid <- prefix :: 0 <= eid;
    assert forall eid <- prefix :: eid !in render.emitted;
    forall left, right |
      0 <= left < right < |prefix|
      ensures prefix[left] != prefix[right]
    {
      assert (prefix + suffix)[left] == prefix[left];
      assert (prefix + suffix)[right] == prefix[right];
    }
  }

  function CheckedAdvanceRenderSpec(
    render: Indexed.RenderState,
    eid: int
  ): Indexed.RenderAdvance
  {
    if Indexed.ValidRenderState(render) &&
       0 <= eid &&
       eid !in render.emitted &&
       eid !in render.delivered &&
       !render.complete
    then Indexed.AdvanceRenderSpec(render, eid)
    else
      Indexed.RenderRejected(
        Indexed.CursorSkipped(render.ordinal, render.ordinal)
      )
  }

  function ConsumeSequenceSpec(
    render: Indexed.RenderState,
    values: seq<int>
  ): Indexed.RenderAdvance
    decreases |values|
  {
    if render.complete || |values| == 0
    then Indexed.RenderProgress(render, false)
    else
      var advanced :=
        CheckedAdvanceRenderSpec(render, values[0]);
      if advanced.RenderRejected?
      then advanced
      else
        ConsumeSequenceSpec(advanced.state, values[1..])
  }

  lemma ConsumeSequencePreservesValidity(
    render: Indexed.RenderState,
    values: seq<int>
  )
    requires ValidEmissionSuffix(render, values)
    ensures
      ConsumeSequenceSpec(render, values).RenderProgress? ==>
        Indexed.ValidRenderState(
          ConsumeSequenceSpec(render, values).state
        )
    decreases |values|
  {
    if !render.complete && |values| != 0 {
      var advanced :=
        CheckedAdvanceRenderSpec(render, values[0]);
      ValidEmissionHead(render, values);
      assert advanced ==
             Indexed.AdvanceRenderSpec(render, values[0]);
      if advanced.RenderProgress? {
        ValidSuffixAfterAdvance(render, values);
        ConsumeSequencePreservesValidity(
          advanced.state,
          values[1..]
        );
      }
    }
  }

  lemma ConsumeSequencePrefixComposition(
    render: Indexed.RenderState,
    prefix: seq<int>,
    suffix: seq<int>
  )
    requires ValidEmissionSuffix(render, prefix + suffix)
    requires
      ConsumeSequenceSpec(render, prefix).RenderProgress?
    requires
      !ConsumeSequenceSpec(render, prefix).state.complete
    ensures
      ConsumeSequenceSpec(render, prefix + suffix) ==
      ConsumeSequenceSpec(
        ConsumeSequenceSpec(render, prefix).state,
        suffix
      )
    decreases |prefix|
  {
    if |prefix| == 0 {
      assert prefix == [];
      assert prefix + suffix == suffix;
      assert ConsumeSequenceSpec(render, prefix) ==
             Indexed.RenderProgress(render, false);
      assert !render.complete;
    } else if !render.complete {
      ValidEmissionPrefix(render, prefix, suffix);
      var advanced :=
        CheckedAdvanceRenderSpec(render, prefix[0]);
      assert advanced.RenderProgress?;
      assert ConsumeSequenceSpec(render, prefix) ==
             ConsumeSequenceSpec(advanced.state, prefix[1..]);
      assert ConsumeSequenceSpec(
          advanced.state,
          prefix[1..]
        ).RenderProgress?;
      assert !ConsumeSequenceSpec(
          advanced.state,
          prefix[1..]
        ).state.complete;
      if advanced.RenderProgress? {
        ValidSuffixAfterAdvance(render, prefix + suffix);
        assert (prefix + suffix)[1..] ==
               prefix[1..] + suffix;
        ConsumeSequencePrefixComposition(
          advanced.state,
          prefix[1..],
          suffix
        );
      }
    }
  }

  function FinishSequenceSpec(
    render: Indexed.RenderState,
    values: seq<int>
  ): Indexed.RenderAdvance
    requires ValidEmissionSuffix(render, values)
  {
    var consumed := ConsumeSequenceSpec(render, values);
    if consumed.RenderRejected?
    then consumed
    else if consumed.state.complete
      then consumed
      else if Indexed.ValidRenderState(consumed.state)
        then Indexed.FinishRenderSpec(consumed.state)
        else
          Indexed.RenderRejected(
            Indexed.CursorSkipped(
              consumed.state.ordinal,
              consumed.state.ordinal
            )
          )
  }

  function RenderDenotationSpec(
    mode: Indexed.RenderMode,
    values: seq<int>
  ): Indexed.RenderAdvance
    requires Indexed.ValidRenderMode(mode)
    requires (forall eid <- values :: 0 <= eid)
    requires Indexed.UniqueEids(values)
  {
    FinishSequenceSpec(Indexed.InitialRender(mode), values)
  }

  function CheckedReadRenderResultSpec(
    render: Indexed.RenderState
  ): Indexed.PublicRenderResult
  {
    if Indexed.ValidRenderState(render) && render.complete
    then Indexed.ReadRenderResultSpec(render)
    else Indexed.BooleanReady(false)
  }

  lemma FinishSequencePreservesValidity(
    render: Indexed.RenderState,
    values: seq<int>
  )
    requires ValidEmissionSuffix(render, values)
    ensures
      FinishSequenceSpec(render, values).RenderProgress? ==>
        Indexed.ValidRenderState(
          FinishSequenceSpec(render, values).state
        ) &&
        FinishSequenceSpec(render, values).state.complete
  {
    ConsumeSequencePreservesValidity(render, values);
    var consumed := ConsumeSequenceSpec(render, values);
    if consumed.RenderProgress? {
      assert Indexed.ValidRenderState(consumed.state);
      if !consumed.state.complete {
        assert FinishSequenceSpec(render, values) ==
               Indexed.FinishRenderSpec(consumed.state);
      }
    }
  }

  lemma RenderDenotationPreservesValidity(
    mode: Indexed.RenderMode,
    values: seq<int>
  )
    requires Indexed.ValidRenderMode(mode)
    requires (forall eid <- values :: 0 <= eid)
    requires Indexed.UniqueEids(values)
    ensures
      RenderDenotationSpec(mode, values).RenderProgress? ==>
        Indexed.ValidRenderState(
          RenderDenotationSpec(mode, values).state
        ) &&
        RenderDenotationSpec(mode, values).state.complete
  {
    assert ValidEmissionSuffix(
        Indexed.InitialRender(mode),
        values
      );
    FinishSequencePreservesValidity(
      Indexed.InitialRender(mode),
      values
    );
  }

  lemma IdenticalDenotationsRenderIdentically(
    mode: Indexed.RenderMode,
    liveValues: seq<int>,
    cachedValues: seq<int>
  )
    requires Indexed.ValidRenderMode(mode)
    requires (forall eid <- liveValues :: 0 <= eid)
    requires Indexed.UniqueEids(liveValues)
    requires cachedValues == liveValues
    ensures
      RenderDenotationSpec(mode, liveValues) ==
      RenderDenotationSpec(mode, cachedValues)
  {
  }

  lemma DeterministicPrefixIndependentOfChunkBoundary(
    mode: Indexed.RenderMode,
    prefix: seq<int>,
    suffix: seq<int>
  )
    requires Indexed.ValidRenderMode(mode)
    requires (forall eid <- prefix + suffix :: 0 <= eid)
    requires Indexed.UniqueEids(prefix + suffix)
    requires
      ConsumeSequenceSpec(
        Indexed.InitialRender(mode),
        prefix
      ).RenderProgress?
    requires
      !ConsumeSequenceSpec(
        Indexed.InitialRender(mode),
        prefix
      ).state.complete
    ensures
      ConsumeSequenceSpec(
        Indexed.InitialRender(mode),
        prefix + suffix
      ) ==
      ConsumeSequenceSpec(
        ConsumeSequenceSpec(
          Indexed.InitialRender(mode),
          prefix
        ).state,
        suffix
      )
  {
    ConsumeSequencePrefixComposition(
      Indexed.InitialRender(mode),
      prefix,
      suffix
    );
  }

  lemma CompletedRenderReadsDeterministically(
    mode: Indexed.RenderMode,
    liveValues: seq<int>,
    cachedValues: seq<int>
  )
    requires Indexed.ValidRenderMode(mode)
    requires (forall eid <- liveValues :: 0 <= eid)
    requires Indexed.UniqueEids(liveValues)
    requires cachedValues == liveValues
    requires
      RenderDenotationSpec(mode, liveValues).RenderProgress?
    requires RenderDenotationSpec(mode, liveValues).state.complete
    ensures
      CheckedReadRenderResultSpec(
        RenderDenotationSpec(mode, liveValues).state
      ) ==
      CheckedReadRenderResultSpec(
        RenderDenotationSpec(mode, cachedValues).state
      )
  {
    IdenticalDenotationsRenderIdentically(
      mode,
      liveValues,
      cachedValues
    );
    RenderDenotationPreservesValidity(mode, liveValues);
    var live := RenderDenotationSpec(mode, liveValues);
    var cached := RenderDenotationSpec(mode, cachedValues);
    assert cached == live;
    assert Indexed.ValidRenderState(live.state);
    assert Indexed.ValidRenderState(cached.state);
  }

  lemma SetEqualityDoesNotEstablishOrderedPageRefinement()
    ensures (set eid <- [1, 2]) == (set eid <- [2, 1])
    ensures [1, 2] != [2, 1]
    ensures
      RenderDenotationSpec(
        Indexed.RenderPage(2, Indexed.NoCursorBound),
        [1, 2]
      ) !=
      RenderDenotationSpec(
        Indexed.RenderPage(2, Indexed.NoCursorBound),
        [2, 1]
      )
  {
    assert [1, 2][0] != [2, 1][0];
  }
}
