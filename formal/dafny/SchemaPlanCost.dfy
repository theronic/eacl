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

  // Generated rendering pays one FFI drive/resume round trip for the common
  // small page by requesting exactly the page plus its has-next sentinel.
  // This remains a logical adapter-value bound, not a latency claim.
  function GeneratedPageStreamChunkSize(pageSize: nat): nat
    requires 0 < pageSize
  {
    if pageSize + 1 < 64 then pageSize + 1 else 64
  }

  lemma GeneratedPageStreamChunkIsBounded(pageSize: nat)
    requires 0 < pageSize
    ensures 2 <= GeneratedPageStreamChunkSize(pageSize) <= 64
  {
  }

  lemma GeneratedSmallPageFitsItsSentinelInOneChunk(pageSize: nat)
    requires 0 < pageSize < 64
    ensures GeneratedPageStreamChunkSize(pageSize) == pageSize + 1
  {
  }

  // Rule indexing is schema work. A compiled generated plan retains the exact
  // ForwardConsumers and RulesByNode maps proved in IndexedTraversal; request
  // initialization selects only its already-certified seed bucket.
  function QueryRuleIndexBuilds(
    compiledPlanAvailable: bool
  ): nat {
    if compiledPlanAvailable then 0 else 2
  }

  lemma CompiledPlanEliminatesPerQueryRuleIndexBuilds()
    ensures QueryRuleIndexBuilds(true) == 0
  {
  }

  // These are generated-certifier comparison budgets, not elapsed time,
  // allocation counts, verifier obligations, or retained heap. The split
  // mirrors production: canonical source-plan compilation runs once per
  // schema-proof/root plan, while each subject-type seed bucket is checked by
  // the smaller partition certifier.
  function FullPlanCertificationCalls(): nat {
    1
  }

  function SeedBucketCertificationCalls(
    subjectTypes: nat
  ): nat {
    subjectTypes
  }

  function FullPlanComparisonBudget(
    rules: nat
  ): nat {
    rules * rules
  }

  function SeedPartitionComparisonBudget(
    rules: nat,
    subjectTypes: nat
  ): nat {
    // One scan of all rules per subject type, plus both set-inclusion
    // directions across a partition containing at most `rules` total seeds.
    subjectTypes * rules + 2 * rules * rules
  }

  function SplitCertificationComparisonBudget(
    rules: nat,
    subjectTypes: nat
  ): nat {
    FullPlanComparisonBudget(rules) +
    SeedPartitionComparisonBudget(rules, subjectTypes)
  }

  function RepeatedFullCertificationLowerBound(
    rules: nat,
    subjectTypes: nat
  ): nat {
    subjectTypes * FullPlanComparisonBudget(rules)
  }

  lemma FullCompilerRunsOncePerCompiledPlan()
    ensures FullPlanCertificationCalls() == 1
  {
  }

  lemma SplitCertificationIsQuadraticWhenTypesComeFromRules(
    rules: nat,
    subjectTypes: nat
  )
    requires subjectTypes <= rules
    ensures SplitCertificationComparisonBudget(
              rules,
              subjectTypes
            ) <= 4 * rules * rules
  {
    calc {
       SplitCertificationComparisonBudget(rules, subjectTypes);
    == 3 * rules * rules + subjectTypes * rules;
    <= 3 * rules * rules + rules * rules;
    == 4 * rules * rules;
    }
  }

  lemma RepeatingTheFullCompilerCanBeCubic(
    rules: nat
  )
    ensures RepeatedFullCertificationLowerBound(
              rules,
              rules
            ) == rules * rules * rules
  {
  }
}
