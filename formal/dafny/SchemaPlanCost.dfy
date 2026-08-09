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

  // A cached exact count must not turn one bounded count window into many
  // tiny backend seeks. Production raises the projection prefix for the
  // complete count traversal while still realizing only one bounded window
  // plus its has-next sentinel at a time.
  function CountProjectionChunkSize(
    countPageSize: nat,
    defaultChunkSize: nat
  ): nat
    requires 0 < countPageSize
    requires 0 < defaultChunkSize
  {
    if defaultChunkSize < countPageSize + 1
    then countPageSize + 1
    else defaultChunkSize
  }

  lemma CountProjectionChunkCoversWindowAndSentinel(
    countPageSize: nat,
    defaultChunkSize: nat
  )
    requires 0 < countPageSize
    requires 0 < defaultChunkSize
    ensures CountProjectionChunkSize(
              countPageSize,
              defaultChunkSize
            ) >= countPageSize + 1
  {
  }

  lemma CountProjectionChunkNeverShrinksConfiguredBound(
    countPageSize: nat,
    defaultChunkSize: nat
  )
    requires 0 < countPageSize
    requires 0 < defaultChunkSize
    ensures CountProjectionChunkSize(
              countPageSize,
              defaultChunkSize
            ) >= defaultChunkSize
  {
  }

  // Exact count constructs the permission-path merge once, then advances that
  // same lazy tail through every certified window. The number of traversal
  // constructions is therefore independent of both result cardinality and
  // window count.
  function CountMergeTraversalConstructions(): nat {
    1
  }

  lemma ExactCountBuildsOneMergeTraversal(
    certifiedWindows: nat
  )
    requires 0 < certifiedWindows
    ensures CountMergeTraversalConstructions() == 1
    ensures CountMergeTraversalConstructions() <= certifiedWindows
  {
  }

  function CountWindowAdvance(
    remainingResults: nat,
    countWindowSize: nat
  ): nat
    requires 0 < countWindowSize
  {
    if remainingResults < countWindowSize
    then remainingResults
    else countWindowSize
  }

  lemma CountWindowAdvanceIsBounded(
    remainingResults: nat,
    countWindowSize: nat
  )
    requires 0 < countWindowSize
    ensures CountWindowAdvance(
              remainingResults,
              countWindowSize
            ) <= countWindowSize
    ensures CountWindowAdvance(
              remainingResults,
              countWindowSize
            ) <= remainingResults
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

  // The acyclic frontier builder follows only an exact single
  // self-permission body. Every other permission body is terminal. This is
  // the production canonical-permission-alias decision, including its cycle
  // guard; it is not a general schema rewrite.
  datatype PermissionBody =
    | PureSelfPermission(targetPermission: int)
    | CompositePermission

  function CanonicalPermissionAlias(
    bodies: map<int, PermissionBody>,
    current: int,
    seen: set<int>
  ): int
    decreases bodies.Keys - seen
  {
    if current in seen || current !in bodies
    then current
    else
      match bodies[current]
      case CompositePermission => current
      case PureSelfPermission(targetPermission) =>
        CanonicalPermissionAlias(
          bodies,
          targetPermission,
          seen + {current}
        )
  }

  predicate PureAliasesPreserveDenotation(
    bodies: map<int, PermissionBody>,
    denotations: map<int, set<int>>
  ) {
    forall permission ::
      permission in bodies && bodies[permission].PureSelfPermission? ==>
        permission in denotations &&
        bodies[permission].targetPermission in denotations &&
        denotations[permission] ==
        denotations[bodies[permission].targetPermission]
  }

  lemma CanonicalPermissionAliasPreservesDenotation(
    bodies: map<int, PermissionBody>,
    denotations: map<int, set<int>>,
    current: int,
    seen: set<int>
  )
    requires current in denotations
    requires PureAliasesPreserveDenotation(bodies, denotations)
    ensures CanonicalPermissionAlias(bodies, current, seen) in denotations
    ensures denotations[CanonicalPermissionAlias(bodies, current, seen)] ==
            denotations[current]
    decreases bodies.Keys - seen
  {
    if current !in seen && current in bodies &&
       bodies[current].PureSelfPermission?
    {
      CanonicalPermissionAliasPreservesDenotation(
        bodies,
        denotations,
        bodies[current].targetPermission,
        seen + {current}
      );
    }
  }

  datatype ArrowFrontier = ArrowFrontier(
    relation: int,
    targetType: int,
    targetPermission: int
  )

  function CanonicalArrowFrontier(
    bodiesByType: map<int, map<int, PermissionBody>>,
    path: ArrowFrontier
  ): ArrowFrontier {
    if path.targetType in bodiesByType
    then ArrowFrontier(
           path.relation,
           path.targetType,
           CanonicalPermissionAlias(
             bodiesByType[path.targetType],
             path.targetPermission,
             {}
           )
         )
    else path
  }

  function DeduplicateCanonicalFrontiers(
    bodiesByType: map<int, map<int, PermissionBody>>,
    paths: seq<ArrowFrontier>,
    seen: set<ArrowFrontier>
  ): seq<ArrowFrontier>
    decreases |paths|
  {
    if |paths| == 0 then
      []
    else if CanonicalArrowFrontier(bodiesByType, paths[0]) in seen then
      DeduplicateCanonicalFrontiers(
        bodiesByType,
        paths[1..],
        seen
      )
    else
      [CanonicalArrowFrontier(bodiesByType, paths[0])] +
      DeduplicateCanonicalFrontiers(
        bodiesByType,
        paths[1..],
        seen + {CanonicalArrowFrontier(bodiesByType, paths[0])}
      )
  }

  function CanonicalFrontierSequence(
    bodiesByType: map<int, map<int, PermissionBody>>,
    paths: seq<ArrowFrontier>
  ): seq<ArrowFrontier> {
    DeduplicateCanonicalFrontiers(bodiesByType, paths, {})
  }

  predicate UniqueFrontiers(paths: seq<ArrowFrontier>) {
    forall i, j ::
      0 <= i < j < |paths| ==> paths[i] != paths[j]
  }

  lemma DeduplicateCanonicalFrontiersExcludesSeen(
    bodiesByType: map<int, map<int, PermissionBody>>,
    paths: seq<ArrowFrontier>,
    seen: set<ArrowFrontier>
  )
    ensures forall index ::
              0 <= index <
              |DeduplicateCanonicalFrontiers(bodiesByType, paths, seen)| ==>
                DeduplicateCanonicalFrontiers(
                  bodiesByType,
                  paths,
                  seen
                )[index] !in seen
    decreases |paths|
  {
    if |paths| > 0 {
      var canonical := CanonicalArrowFrontier(bodiesByType, paths[0]);
      if canonical in seen {
        DeduplicateCanonicalFrontiersExcludesSeen(
          bodiesByType,
          paths[1..],
          seen
        );
      } else {
        DeduplicateCanonicalFrontiersExcludesSeen(
          bodiesByType,
          paths[1..],
          seen + {canonical}
        );
      }
    }
  }

  lemma DeduplicateCanonicalFrontiersAreUnique(
    bodiesByType: map<int, map<int, PermissionBody>>,
    paths: seq<ArrowFrontier>,
    seen: set<ArrowFrontier>
  )
    ensures UniqueFrontiers(
              DeduplicateCanonicalFrontiers(
                bodiesByType,
                paths,
                seen
              )
            )
    decreases |paths|
  {
    if |paths| > 0 {
      var canonical := CanonicalArrowFrontier(bodiesByType, paths[0]);
      if canonical in seen {
        DeduplicateCanonicalFrontiersAreUnique(
          bodiesByType,
          paths[1..],
          seen
        );
      } else {
        DeduplicateCanonicalFrontiersExcludesSeen(
          bodiesByType,
          paths[1..],
          seen + {canonical}
        );
        DeduplicateCanonicalFrontiersAreUnique(
          bodiesByType,
          paths[1..],
          seen + {canonical}
        );
      }
    }
  }

  lemma DeduplicateCanonicalFrontiersCannotAddTraversalStreams(
    bodiesByType: map<int, map<int, PermissionBody>>,
    paths: seq<ArrowFrontier>,
    seen: set<ArrowFrontier>
  )
    ensures |DeduplicateCanonicalFrontiers(
              bodiesByType,
              paths,
              seen
            )| <= |paths|
    decreases |paths|
  {
    if |paths| > 0 {
      var canonical := CanonicalArrowFrontier(bodiesByType, paths[0]);
      if canonical in seen {
        DeduplicateCanonicalFrontiersCannotAddTraversalStreams(
          bodiesByType,
          paths[1..],
          seen
        );
      } else {
        DeduplicateCanonicalFrontiersCannotAddTraversalStreams(
          bodiesByType,
          paths[1..],
          seen + {canonical}
        );
      }
    }
  }

  lemma CanonicalFrontierDeduplicationCannotAddTraversalStreams(
    bodiesByType: map<int, map<int, PermissionBody>>,
    paths: seq<ArrowFrontier>
  )
    ensures |CanonicalFrontierSequence(bodiesByType, paths)| <= |paths|
  {
    DeduplicateCanonicalFrontiersCannotAddTraversalStreams(
      bodiesByType,
      paths,
      {}
    );
  }

  lemma CanonicalFrontierSequenceIsUniqueAndKeepsFirstPath(
    bodiesByType: map<int, map<int, PermissionBody>>,
    paths: seq<ArrowFrontier>
  )
    ensures UniqueFrontiers(
              CanonicalFrontierSequence(bodiesByType, paths)
            )
    ensures |paths| > 0 ==>
              |CanonicalFrontierSequence(bodiesByType, paths)| > 0 &&
              CanonicalFrontierSequence(bodiesByType, paths)[0] ==
              CanonicalArrowFrontier(bodiesByType, paths[0])
  {
    DeduplicateCanonicalFrontiersAreUnique(
      bodiesByType,
      paths,
      {}
    );
  }
}
