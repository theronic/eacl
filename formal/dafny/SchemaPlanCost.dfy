module SchemaPlanCost {
  // This models expensive recursive-plan compilation work, not wall time or
  // the complexity of the host map lookup. Production exposes the matching
  // :compiled-recursive-plans counter.
  datatype PlanCache = PlanCache(
    schemaGeneration: nat,
    compiledRoots: set<int>
  )

  function ActiveRoots(
    cache: PlanCache,
    selectedGeneration: nat
  ): set<int> {
    if cache.schemaGeneration == selectedGeneration
    then cache.compiledRoots
    else {}
  }

  function CompileWork(
    cache: PlanCache,
    selectedGeneration: nat,
    root: int
  ): nat {
    if root in ActiveRoots(cache, selectedGeneration) then 0 else 1
  }

  function AfterLookup(
    cache: PlanCache,
    selectedGeneration: nat,
    root: int
  ): PlanCache {
    PlanCache(
      selectedGeneration,
      ActiveRoots(cache, selectedGeneration) + {root}
    )
  }

  lemma CacheHitDoesNoCompilation(
    cache: PlanCache,
    selectedGeneration: nat,
    root: int
  )
    requires root in ActiveRoots(cache, selectedGeneration)
    ensures CompileWork(cache, selectedGeneration, root) == 0
  {
  }

  lemma CacheMissCompilesExactlyOnce(
    cache: PlanCache,
    selectedGeneration: nat,
    root: int
  )
    requires root !in ActiveRoots(cache, selectedGeneration)
    ensures CompileWork(cache, selectedGeneration, root) == 1
    ensures root in
              ActiveRoots(
                AfterLookup(cache, selectedGeneration, root),
                selectedGeneration
              )
  {
  }

  lemma RepeatedRootCompilesAtMostOnce(
    cache: PlanCache,
    selectedGeneration: nat,
    root: int
  )
    ensures
      CompileWork(cache, selectedGeneration, root) +
      CompileWork(
        AfterLookup(cache, selectedGeneration, root),
        selectedGeneration,
        root
      ) <= 1
  {
  }

  lemma NewSchemaGenerationCannotReuseAnOldPlan(
    cache: PlanCache,
    selectedGeneration: nat,
    root: int
  )
    requires cache.schemaGeneration != selectedGeneration
    ensures ActiveRoots(cache, selectedGeneration) == {}
    ensures CompileWork(cache, selectedGeneration, root) == 1
  {
  }

  // This mirrors the page-window-sensitive backend scan batching in the
  // production recursive engine. It is a deterministic operation-count
  // boundary, not a claim about backend or wall-clock latency.
  function PageStreamChunkSize(pageSize: nat): nat
    requires 0 < pageSize
  {
    if pageSize <= 32 then
      16
    else if pageSize <= 256 then
      32
    else
      64
  }

  lemma PageStreamChunkIsBounded(pageSize: nat)
    requires 0 < pageSize
    ensures 16 <= PageStreamChunkSize(pageSize) <= 64
  {
  }

  lemma SmallPageAvoidsTheLargeBatch(pageSize: nat)
    requires 0 < pageSize <= 32
    ensures PageStreamChunkSize(pageSize) == 16
  {
  }

  lemma MediumPageUsesTheMiddleBatch(pageSize: nat)
    requires 32 < pageSize <= 256
    ensures PageStreamChunkSize(pageSize) == 32
  {
  }

  lemma LargePageKeepsScanAmortization(pageSize: nat)
    requires 256 < pageSize
    ensures PageStreamChunkSize(pageSize) == 64
  {
  }
}
