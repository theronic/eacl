----------------------- MODULE EaclTemporalDetailed -----------------------
EXTENDS Integers, FiniteSets

CONSTANTS
  \* @type: Set(Int);
  Histories,
  \* @type: Set(Int);
  Proofs,
  \* @type: Set(Int);
  Scopes,
  \* @type: Set(Int);
  Queries,
  \* @type: Set(Int);
  Directions,
  \* @type: Set(Int);
  Operations,
  \* @type: Set(Int);
  ResultKinds,
  \* @type: Set(Int);
  Times,
  \* @type: Set(Int);
  Sources

ASSUME
  /\ Histories # {}
  /\ Proofs # {}
  /\ Scopes # {}
  /\ Queries # {}
  /\ Directions # {}
  /\ Operations # {}
  /\ ResultKinds # {}
  /\ Times # {}
  /\ Sources # {}
  /\ 0 \in Histories
  /\ 0 \in Proofs
  /\ 0 \in Scopes
  /\ 0 \in Queries
  /\ 0 \in Directions
  /\ 0 \in Operations
  /\ 0 \in ResultKinds
  /\ 0 \in Times
  /\ 0 \in Sources

ManagedWriter == 0
UnmanagedWriter == 1
WriterKinds == {ManagedWriter, UnmanagedWriter}

GenesisWrite == 0
GraphWrite == 1
SchemaWrite == 2
ProofEquivalentWrite == 3
WriteKinds == {GenesisWrite, GraphWrite,
               SchemaWrite, ProofEquivalentWrite}

NoHeadMove == 0
CloneMove == 1
ResetMove == 2
RestoreMove == 3
BranchMove == 4
ForceHeadMove == 5
HeadMoveKinds == {NoHeadMove, CloneMove, ResetMove,
                  RestoreMove, BranchMove, ForceHeadMove}

StrictConflict == 0
AtLeastConflict == 1
ConflictModes == {StrictConflict, AtLeastConflict}

NoOutcome == 0
CacheHit == 1
CacheMiss == 2
CursorCurrent == 3
CursorExact == 4
CursorRejected == 5
ContinuationResumed == 6
PageCacheHit == 7
DeterministicReplay == 8
Outcomes == {NoOutcome, CacheHit, CacheMiss,
             CursorCurrent, CursorExact, CursorRejected,
             ContinuationResumed, PageCacheHit,
             DeterministicReplay}

NoReject == 0
RejectedAuthentication == 1
RejectedScope == 2
RejectedExpiry == 3
RejectedDivergence == 4
RejectedStale == 5
RejectReasons == {NoReject, RejectedAuthentication, RejectedScope,
                  RejectedExpiry, RejectedDivergence, RejectedStale}

VARIABLES
  \* Snapshot history and retention.
  \* @type: Set(Int);
  active,
  \* @type: Int;
  head,
  \* @type: Int -> Set(Int);
  ancestors,
  \* @type: Int -> (Int -> Int);
  proof,
  \* @type: Int -> (Int -> Bool);
  proofAvailable,
  \* @type: Int -> Int;
  writer,
  \* @type: Int -> Int;
  snapshotWrite,
  \* @type: Set(Int);
  retained,
  \* @type: Int;
  source,
  \* @type: Int;
  now,
  \* @type: Int;
  lastHeadMove,

  \* Selected/computation/exact decision context.
  \* @type: Int;
  selectedGraph,
  \* @type: Int;
  computationGraph,
  \* @type: Int;
  exactGraph,
  \* @type: Int;
  dependencyScope,
  \* @type: Int;
  selectedOperation,
  \* @type: Int;
  selectedQuery,
  \* @type: Int;
  selectedDirection,
  \* @type: Int;
  selectedResultKind,
  \* @type: Int;
  selectedConflict,
  \* @type: Int;
  outcome,
  \* @type: Int;
  rejectReason,
  \* @type: Bool;
  decisionReturned,

  \* Authenticated cache entry and validation telemetry.
  \* @type: Bool;
  cachePresent,
  \* @type: Bool;
  cacheAuthenticated,
  \* @type: Int;
  cacheGraph,
  \* @type: Int;
  cacheScope,
  \* @type: Int;
  cacheProof,
  \* @type: Int;
  cacheQuery,
  \* @type: Int;
  cacheSource,
  \* @type: Bool;
  cacheValue,
  \* @type: Int;
  cacheGeneration,
  \* @type: Int;
  telemetry,

  \* Authenticated cursor scope and expiry.
  \* @type: Bool;
  cursorPresent,
  \* @type: Bool;
  cursorAuthenticated,
  \* @type: Int;
  cursorOperation,
  \* @type: Int;
  cursorQuery,
  \* @type: Int;
  cursorDirection,
  \* @type: Int;
  cursorResultKind,
  \* @type: Int;
  cursorGraph,
  \* @type: Int;
  cursorScope,
  \* @type: Int;
  cursorProof,
  \* @type: Int;
  cursorSource,
  \* @type: Bool;
  cursorLiftable,
  \* @type: Int;
  cursorOffset,
  \* @type: Int;
  cursorExpiresAt,

  \* Process-local recursive continuation/page state.
  \* @type: Bool;
  continuationPresent,
  \* @type: Int;
  continuationGraph,
  \* @type: Int;
  continuationOffset,
  \* @type: Bool;
  pagePresent,
  \* @type: Int;
  pageGraph,
  \* @type: Int;
  pageOffset,
  \* @type: Int;
  decisionGraph,
  \* @type: Int;
  decisionOffset,
  \* @type: Int;
  pageGraphUsed,
  \* @type: Bool;
  replayed

vars ==
  <<active, head, ancestors, proof, proofAvailable, writer,
    snapshotWrite, retained, source, now, lastHeadMove,
    selectedGraph, computationGraph, exactGraph, dependencyScope,
    selectedOperation, selectedQuery, selectedDirection,
    selectedResultKind, selectedConflict, outcome, rejectReason,
    decisionReturned,
    cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
    cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
    telemetry,
    cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
    cursorDirection, cursorResultKind, cursorGraph, cursorScope,
    cursorProof, cursorSource, cursorLiftable, cursorOffset,
    cursorExpiresAt,
    continuationPresent, continuationGraph, continuationOffset,
    pagePresent, pageGraph, pageOffset, decisionGraph, decisionOffset,
    pageGraphUsed, replayed>>

TypeOK ==
  /\ active \in SUBSET Histories
  /\ active # {}
  /\ head \in active
  /\ ancestors \in [Histories -> SUBSET Histories]
  /\ proof \in [Histories -> [Scopes -> Proofs]]
  /\ proofAvailable \in [Histories -> [Scopes -> BOOLEAN]]
  /\ writer \in [Histories -> WriterKinds]
  /\ snapshotWrite \in [Histories -> WriteKinds]
  /\ retained \in SUBSET active
  /\ source \in Sources
  /\ now \in Times
  /\ lastHeadMove \in HeadMoveKinds
  /\ selectedGraph \in active
  /\ computationGraph \in active
  /\ exactGraph \in active
  /\ dependencyScope \in Scopes
  /\ selectedOperation \in Operations
  /\ selectedQuery \in Queries
  /\ selectedDirection \in Directions
  /\ selectedResultKind \in ResultKinds
  /\ selectedConflict \in ConflictModes
  /\ outcome \in Outcomes
  /\ rejectReason \in RejectReasons
  /\ decisionReturned \in BOOLEAN
  /\ cachePresent \in BOOLEAN
  /\ cacheAuthenticated \in BOOLEAN
  /\ cacheGraph \in active
  /\ cacheScope \in Scopes
  /\ cacheProof \in Proofs
  /\ cacheQuery \in Queries
  /\ cacheSource \in Sources
  /\ cacheValue \in BOOLEAN
  /\ cacheGeneration \in Nat
  /\ telemetry \in Nat
  /\ cursorPresent \in BOOLEAN
  /\ cursorAuthenticated \in BOOLEAN
  /\ cursorOperation \in Operations
  /\ cursorQuery \in Queries
  /\ cursorDirection \in Directions
  /\ cursorResultKind \in ResultKinds
  /\ cursorGraph \in active
  /\ cursorScope \in Scopes
  /\ cursorProof \in Proofs
  /\ cursorSource \in Sources
  /\ cursorLiftable \in BOOLEAN
  /\ cursorOffset \in Nat
  /\ cursorExpiresAt \in Times
  /\ continuationPresent \in BOOLEAN
  /\ continuationGraph \in active
  /\ continuationOffset \in Nat
  /\ pagePresent \in BOOLEAN
  /\ pageGraph \in active
  /\ pageOffset \in Nat
  /\ decisionGraph \in active
  /\ decisionOffset \in Nat
  /\ pageGraphUsed \in active
  /\ replayed \in BOOLEAN

CausalClosure ==
  \A graph \in active:
    /\ ancestors[graph] \subseteq active
    /\ graph \notin ancestors[graph]
    /\ \A ancestor \in ancestors[graph]:
      ancestors[ancestor] \subseteq ancestors[graph]

CacheAcceptanceSafe ==
  outcome # CacheHit \/
    /\ decisionReturned
    /\ selectedGraph = head
    /\ computationGraph = cacheGraph
    /\ cachePresent
    /\ cacheAuthenticated
    /\ cacheSource = source
    /\ cacheQuery = selectedQuery
    /\ cacheScope = dependencyScope
    /\ cacheGraph \in ancestors[head] \union {head}
    /\ proofAvailable[head][dependencyScope]
    /\ cacheProof = proof[head][dependencyScope]

CursorCurrentSafe ==
  outcome # CursorCurrent \/
    /\ decisionReturned
    /\ cursorPresent
    /\ cursorAuthenticated
    /\ cursorSource = source
    /\ cursorOperation = selectedOperation
    /\ cursorQuery = selectedQuery
    /\ cursorResultKind = selectedResultKind
    /\ cursorScope = dependencyScope
    /\ now < cursorExpiresAt
    /\ (cursorGraph = head \/
        /\ cursorLiftable
        /\ proofAvailable[head][dependencyScope]
        /\ cursorProof = proof[head][dependencyScope])
    /\ (selectedConflict # AtLeastConflict \/
        cursorGraph \in ancestors[head] \union {head})
    /\ selectedGraph = head
    /\ computationGraph = head
    /\ decisionGraph = head

CursorExactSafe ==
  outcome # CursorExact \/
    /\ decisionReturned
    /\ cursorPresent
    /\ cursorAuthenticated
    /\ cursorSource = source
    /\ cursorOperation = selectedOperation
    /\ cursorQuery = selectedQuery
    /\ cursorResultKind = selectedResultKind
    /\ cursorScope = dependencyScope
    /\ now < cursorExpiresAt
    /\ cursorGraph \in retained
    /\ (selectedConflict # AtLeastConflict \/
        cursorGraph \in ancestors[head] \union {head})
    /\ selectedGraph = head
    /\ computationGraph = cursorGraph
    /\ exactGraph = cursorGraph
    /\ decisionGraph = cursorGraph

RejectedFailsClosed ==
  outcome # CursorRejected \/
    /\ ~decisionReturned
    /\ computationGraph = selectedGraph
    /\ selectedGraph = head
    /\ rejectReason # NoReject

ContinuationRaceTransparent ==
  outcome \notin {ContinuationResumed, PageCacheHit,
                  DeterministicReplay} \/
    /\ decisionReturned
    /\ computationGraph = decisionGraph
    /\ pageGraphUsed = decisionGraph
    /\ selectedGraph = head

Safety ==
  /\ CacheAcceptanceSafe
  /\ CursorCurrentSafe
  /\ CursorExactSafe
  /\ RejectedFailsClosed
  /\ ContinuationRaceTransparent

InductiveInvariant ==
  /\ TypeOK
  /\ CausalClosure
  /\ Safety

Init ==
  /\ active = {0}
  /\ head = 0
  /\ ancestors = [graph \in Histories |-> {}]
  /\ proof =
    [graph \in Histories |-> [scope \in Scopes |-> 0]]
  /\ proofAvailable =
    [graph \in Histories |-> [scope \in Scopes |-> TRUE]]
  /\ writer = [graph \in Histories |-> ManagedWriter]
  /\ snapshotWrite = [graph \in Histories |-> GenesisWrite]
  /\ retained = {0}
  /\ source = 0
  /\ now = 0
  /\ lastHeadMove = NoHeadMove
  /\ selectedGraph = 0
  /\ computationGraph = 0
  /\ exactGraph = 0
  /\ dependencyScope = 0
  /\ selectedOperation = 0
  /\ selectedQuery = 0
  /\ selectedDirection = 0
  /\ selectedResultKind = 0
  /\ selectedConflict = StrictConflict
  /\ outcome = NoOutcome
  /\ rejectReason = NoReject
  /\ decisionReturned = FALSE
  /\ cachePresent = FALSE
  /\ cacheAuthenticated = FALSE
  /\ cacheGraph = 0
  /\ cacheScope = 0
  /\ cacheProof = 0
  /\ cacheQuery = 0
  /\ cacheSource = 0
  /\ cacheValue = FALSE
  /\ cacheGeneration = 0
  /\ telemetry = 0
  /\ cursorPresent = FALSE
  /\ cursorAuthenticated = FALSE
  /\ cursorOperation = 0
  /\ cursorQuery = 0
  /\ cursorDirection = 0
  /\ cursorResultKind = 0
  /\ cursorGraph = 0
  /\ cursorScope = 0
  /\ cursorProof = 0
  /\ cursorSource = 0
  /\ cursorLiftable = FALSE
  /\ cursorOffset = 0
  /\ cursorExpiresAt = 0
  /\ continuationPresent = FALSE
  /\ continuationGraph = 0
  /\ continuationOffset = 0
  /\ pagePresent = FALSE
  /\ pageGraph = 0
  /\ pageOffset = 0
  /\ decisionGraph = 0
  /\ decisionOffset = 0
  /\ pageGraphUsed = 0
  /\ replayed = FALSE

PublishSnapshot(newWriter, newWrite, newProof, newAvailability) ==
  \E newGraph \in Histories \ active:
    /\ active' = active \union {newGraph}
    /\ head' = newGraph
    /\ ancestors' =
      [ancestors EXCEPT
        ![newGraph] = ancestors[head] \union {head}]
    /\ proof' = [proof EXCEPT ![newGraph] = newProof]
    /\ proofAvailable' =
      [proofAvailable EXCEPT ![newGraph] = newAvailability]
    /\ writer' = [writer EXCEPT ![newGraph] = newWriter]
    /\ snapshotWrite' =
      [snapshotWrite EXCEPT ![newGraph] = newWrite]
    /\ retained' = retained \union {newGraph}
    /\ lastHeadMove' = NoHeadMove
    /\ selectedGraph' = newGraph
    /\ computationGraph' = newGraph
    /\ exactGraph' = newGraph
    /\ outcome' = NoOutcome
    /\ rejectReason' = NoReject
    /\ decisionReturned' = FALSE
    /\ decisionGraph' = newGraph
    /\ pageGraphUsed' = newGraph
    /\ replayed' = FALSE
    /\ UNCHANGED
      <<source, now, dependencyScope,
        selectedOperation, selectedQuery, selectedDirection,
        selectedResultKind, selectedConflict,
        cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
        cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
        telemetry,
        cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
        cursorDirection, cursorResultKind, cursorGraph, cursorScope,
        cursorProof, cursorSource, cursorLiftable, cursorOffset,
        cursorExpiresAt,
        continuationPresent, continuationGraph, continuationOffset,
        pagePresent, pageGraph, pageOffset, decisionOffset>>

ManagedGraphWrite ==
  \E candidate \in [Scopes -> Proofs],
     available \in [Scopes -> BOOLEAN]:
    PublishSnapshot(ManagedWriter, GraphWrite, candidate, available)

UnmanagedGraphWrite ==
  \E candidate \in [Scopes -> Proofs],
     available \in [Scopes -> BOOLEAN]:
    PublishSnapshot(UnmanagedWriter, GraphWrite, candidate, available)

ManagedSchemaWrite ==
  \E candidate \in [Scopes -> Proofs],
     available \in [Scopes -> BOOLEAN]:
    PublishSnapshot(ManagedWriter, SchemaWrite, candidate, available)

UnmanagedSchemaWrite ==
  \E candidate \in [Scopes -> Proofs],
     available \in [Scopes -> BOOLEAN]:
    PublishSnapshot(UnmanagedWriter, SchemaWrite, candidate, available)

PublishProofEquivalent ==
  PublishSnapshot(
    ManagedWriter,
    ProofEquivalentWrite,
    proof[head],
    proofAvailable[head]
  )

MoveHead(moveKind) ==
  \E target \in active:
    /\ head' = target
    /\ lastHeadMove' = moveKind
    /\ selectedGraph' = target
    /\ computationGraph' = target
    /\ exactGraph' = target
    /\ outcome' = NoOutcome
    /\ rejectReason' = NoReject
    /\ decisionReturned' = FALSE
    /\ decisionGraph' = target
    /\ pageGraphUsed' = target
    /\ replayed' = FALSE
    /\ UNCHANGED
      <<active, ancestors, proof, proofAvailable, writer,
        snapshotWrite, retained, source, now, dependencyScope,
        selectedOperation, selectedQuery, selectedDirection,
        selectedResultKind, selectedConflict,
        cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
        cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
        telemetry,
        cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
        cursorDirection, cursorResultKind, cursorGraph, cursorScope,
        cursorProof, cursorSource, cursorLiftable, cursorOffset,
        cursorExpiresAt,
        continuationPresent, continuationGraph, continuationOffset,
        pagePresent, pageGraph, pageOffset, decisionOffset>>

CloneHead == MoveHead(CloneMove)
ResetHead == MoveHead(ResetMove)
RestoreHead == MoveHead(RestoreMove)
BranchHead == MoveHead(BranchMove)
ForceHead == MoveHead(ForceHeadMove)

ExpireRetained ==
  \E target \in retained:
    /\ retained' = retained \ {target}
    /\ selectedGraph' = head
    /\ computationGraph' = head
    /\ exactGraph' = head
    /\ outcome' = NoOutcome
    /\ rejectReason' = NoReject
    /\ decisionReturned' = FALSE
    /\ decisionGraph' = head
    /\ pageGraphUsed' = head
    /\ replayed' = FALSE
    /\ UNCHANGED
      <<active, head, ancestors, proof, proofAvailable, writer,
        snapshotWrite, source, now, lastHeadMove, dependencyScope,
        selectedOperation, selectedQuery, selectedDirection,
        selectedResultKind, selectedConflict,
        cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
        cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
        telemetry,
        cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
        cursorDirection, cursorResultKind, cursorGraph, cursorScope,
        cursorProof, cursorSource, cursorLiftable, cursorOffset,
        cursorExpiresAt,
        continuationPresent, continuationGraph, continuationOffset,
        pagePresent, pageGraph, pageOffset, decisionOffset>>

AdvanceTime ==
  \E nextTime \in Times:
    /\ nextTime >= now
    /\ now' = nextTime
    /\ selectedGraph' = head
    /\ computationGraph' = head
    /\ exactGraph' = head
    /\ outcome' = NoOutcome
    /\ rejectReason' = NoReject
    /\ decisionReturned' = FALSE
    /\ decisionGraph' = head
    /\ pageGraphUsed' = head
    /\ replayed' = FALSE
    /\ UNCHANGED
      <<active, head, ancestors, proof, proofAvailable, writer,
        snapshotWrite, retained, source, lastHeadMove, dependencyScope,
        selectedOperation, selectedQuery, selectedDirection,
        selectedResultKind, selectedConflict,
        cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
        cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
        telemetry,
        cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
        cursorDirection, cursorResultKind, cursorGraph, cursorScope,
        cursorProof, cursorSource, cursorLiftable, cursorOffset,
        cursorExpiresAt,
        continuationPresent, continuationGraph, continuationOffset,
        pagePresent, pageGraph, pageOffset, decisionOffset>>

CachePut ==
  \E scope \in Scopes, query \in Queries, value \in BOOLEAN:
    /\ proofAvailable[head][scope]
    /\ cachePresent' = TRUE
    /\ cacheAuthenticated' = TRUE
    /\ cacheGraph' = head
    /\ cacheScope' = scope
    /\ cacheProof' = proof[head][scope]
    /\ cacheQuery' = query
    /\ cacheSource' = source
    /\ cacheValue' = value
    /\ cacheGeneration' = cacheGeneration + 1
    /\ selectedGraph' = head
    /\ computationGraph' = head
    /\ exactGraph' = head
    /\ outcome' = NoOutcome
    /\ rejectReason' = NoReject
    /\ decisionReturned' = FALSE
    /\ decisionGraph' = head
    /\ pageGraphUsed' = head
    /\ replayed' = FALSE
    /\ UNCHANGED
      <<active, head, ancestors, proof, proofAvailable, writer,
        snapshotWrite, retained, source, now, lastHeadMove,
        dependencyScope, selectedOperation, selectedQuery,
        selectedDirection, selectedResultKind, selectedConflict,
        telemetry,
        cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
        cursorDirection, cursorResultKind, cursorGraph, cursorScope,
        cursorProof, cursorSource, cursorLiftable, cursorOffset,
        cursorExpiresAt,
        continuationPresent, continuationGraph, continuationOffset,
        pagePresent, pageGraph, pageOffset, decisionOffset>>

TamperCache ==
  \E graph \in active, scope \in Scopes, candidateProof \in Proofs,
     query \in Queries, candidateSource \in Sources, value \in BOOLEAN:
    /\ cachePresent' = TRUE
    /\ cacheAuthenticated' = FALSE
    /\ cacheGraph' = graph
    /\ cacheScope' = scope
    /\ cacheProof' = candidateProof
    /\ cacheQuery' = query
    /\ cacheSource' = candidateSource
    /\ cacheValue' = value
    /\ cacheGeneration' = cacheGeneration + 1
    /\ selectedGraph' = head
    /\ computationGraph' = head
    /\ exactGraph' = head
    /\ outcome' = NoOutcome
    /\ rejectReason' = NoReject
    /\ decisionReturned' = FALSE
    /\ decisionGraph' = head
    /\ pageGraphUsed' = head
    /\ replayed' = FALSE
    /\ UNCHANGED
      <<active, head, ancestors, proof, proofAvailable, writer,
        snapshotWrite, retained, source, now, lastHeadMove,
        dependencyScope, selectedOperation, selectedQuery,
        selectedDirection, selectedResultKind, selectedConflict,
        telemetry,
        cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
        cursorDirection, cursorResultKind, cursorGraph, cursorScope,
        cursorProof, cursorSource, cursorLiftable, cursorOffset,
        cursorExpiresAt,
        continuationPresent, continuationGraph, continuationOffset,
        pagePresent, pageGraph, pageOffset, decisionOffset>>

CacheRead ==
  \E scope \in Scopes, query \in Queries:
    LET eligible ==
      /\ cachePresent
      /\ cacheAuthenticated
      /\ cacheSource = source
      /\ cacheQuery = query
      /\ cacheScope = scope
      /\ cacheGraph \in ancestors[head] \union {head}
      /\ proofAvailable[head][scope]
      /\ cacheProof = proof[head][scope]
    IN
    /\ dependencyScope' = scope
    /\ selectedQuery' = query
    /\ selectedGraph' = head
    /\ computationGraph' = IF eligible THEN cacheGraph ELSE head
    /\ exactGraph' = head
    /\ outcome' = IF eligible THEN CacheHit ELSE CacheMiss
    /\ rejectReason' = NoReject
    /\ decisionReturned' = eligible
    /\ decisionGraph' = IF eligible THEN cacheGraph ELSE head
    /\ pageGraphUsed' = IF eligible THEN cacheGraph ELSE head
    /\ replayed' = FALSE
    /\ telemetry' = telemetry + 1
    /\ UNCHANGED
      <<active, head, ancestors, proof, proofAvailable, writer,
        snapshotWrite, retained, source, now, lastHeadMove,
        selectedOperation, selectedDirection, selectedResultKind,
        selectedConflict,
        cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
        cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
        cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
        cursorDirection, cursorResultKind, cursorGraph, cursorScope,
        cursorProof, cursorSource, cursorLiftable, cursorOffset,
        cursorExpiresAt,
        continuationPresent, continuationGraph, continuationOffset,
        pagePresent, pageGraph, pageOffset, decisionOffset>>

CacheProviderFailure ==
  \E scope \in Scopes, query \in Queries:
    /\ dependencyScope' = scope
    /\ selectedQuery' = query
    /\ selectedGraph' = head
    /\ computationGraph' = head
    /\ exactGraph' = head
    /\ outcome' = CacheMiss
    /\ rejectReason' = NoReject
    /\ decisionReturned' = FALSE
    /\ decisionGraph' = head
    /\ pageGraphUsed' = head
    /\ replayed' = FALSE
    /\ telemetry' = telemetry + 1
    /\ UNCHANGED
      <<active, head, ancestors, proof, proofAvailable, writer,
        snapshotWrite, retained, source, now, lastHeadMove,
        selectedOperation, selectedDirection, selectedResultKind,
        selectedConflict,
        cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
        cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
        cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
        cursorDirection, cursorResultKind, cursorGraph, cursorScope,
        cursorProof, cursorSource, cursorLiftable, cursorOffset,
        cursorExpiresAt,
        continuationPresent, continuationGraph, continuationOffset,
        pagePresent, pageGraph, pageOffset, decisionOffset>>

TelemetryCAS ==
  \E expectedGeneration \in Nat:
    /\ telemetry' =
      IF expectedGeneration = cacheGeneration
      THEN telemetry + 1
      ELSE telemetry
    /\ selectedGraph' = head
    /\ computationGraph' = head
    /\ exactGraph' = head
    /\ outcome' = NoOutcome
    /\ rejectReason' = NoReject
    /\ decisionReturned' = FALSE
    /\ decisionGraph' = head
    /\ pageGraphUsed' = head
    /\ replayed' = FALSE
    /\ UNCHANGED
      <<active, head, ancestors, proof, proofAvailable, writer,
        snapshotWrite, retained, source, now, lastHeadMove,
        dependencyScope, selectedOperation, selectedQuery,
        selectedDirection, selectedResultKind, selectedConflict,
        cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
        cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
        cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
        cursorDirection, cursorResultKind, cursorGraph, cursorScope,
        cursorProof, cursorSource, cursorLiftable, cursorOffset,
        cursorExpiresAt,
        continuationPresent, continuationGraph, continuationOffset,
        pagePresent, pageGraph, pageOffset, decisionOffset>>

CursorMint ==
  \E operation \in Operations, query \in Queries,
     direction \in Directions, resultKind \in ResultKinds,
     scope \in Scopes, expiry \in Times, liftable \in BOOLEAN:
    /\ now < expiry
    /\ cursorPresent' = TRUE
    /\ cursorAuthenticated' = TRUE
    /\ cursorOperation' = operation
    /\ cursorQuery' = query
    /\ cursorDirection' = direction
    /\ cursorResultKind' = resultKind
    /\ cursorGraph' = head
    /\ cursorScope' = scope
    /\ cursorProof' = proof[head][scope]
    /\ cursorSource' = source
    /\ cursorLiftable' = liftable /\ proofAvailable[head][scope]
    /\ cursorOffset' = 0
    /\ cursorExpiresAt' = expiry
    /\ selectedGraph' = head
    /\ computationGraph' = head
    /\ exactGraph' = head
    /\ outcome' = NoOutcome
    /\ rejectReason' = NoReject
    /\ decisionReturned' = FALSE
    /\ decisionGraph' = head
    /\ decisionOffset' = 0
    /\ pageGraphUsed' = head
    /\ replayed' = FALSE
    /\ UNCHANGED
      <<active, head, ancestors, proof, proofAvailable, writer,
        snapshotWrite, retained, source, now, lastHeadMove,
        dependencyScope, selectedOperation, selectedQuery,
        selectedDirection, selectedResultKind, selectedConflict,
        cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
        cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
        telemetry,
        continuationPresent, continuationGraph, continuationOffset,
        pagePresent, pageGraph, pageOffset>>

TamperCursor ==
  \E operation \in Operations, query \in Queries,
     direction \in Directions, resultKind \in ResultKinds,
     graph \in active, scope \in Scopes, candidateProof \in Proofs,
     candidateSource \in Sources, expiry \in Times:
    /\ cursorPresent' = TRUE
    /\ cursorAuthenticated' = FALSE
    /\ cursorOperation' = operation
    /\ cursorQuery' = query
    /\ cursorDirection' = direction
    /\ cursorResultKind' = resultKind
    /\ cursorGraph' = graph
    /\ cursorScope' = scope
    /\ cursorProof' = candidateProof
    /\ cursorSource' = candidateSource
    /\ cursorLiftable' = TRUE
    /\ cursorOffset' = 0
    /\ cursorExpiresAt' = expiry
    /\ selectedGraph' = head
    /\ computationGraph' = head
    /\ exactGraph' = head
    /\ outcome' = NoOutcome
    /\ rejectReason' = NoReject
    /\ decisionReturned' = FALSE
    /\ decisionGraph' = head
    /\ decisionOffset' = 0
    /\ pageGraphUsed' = head
    /\ replayed' = FALSE
    /\ UNCHANGED
      <<active, head, ancestors, proof, proofAvailable, writer,
        snapshotWrite, retained, source, now, lastHeadMove,
        dependencyScope, selectedOperation, selectedQuery,
        selectedDirection, selectedResultKind, selectedConflict,
        cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
        cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
        telemetry,
        continuationPresent, continuationGraph, continuationOffset,
        pagePresent, pageGraph, pageOffset>>

CursorResume ==
  \E operation \in Operations, query \in Queries,
     direction \in Directions, resultKind \in ResultKinds,
     scope \in Scopes, conflict \in ConflictModes:
    LET authenticated == cursorPresent /\ cursorAuthenticated
        scopeMatches ==
          /\ authenticated
          /\ cursorSource = source
          /\ cursorOperation = operation
          /\ cursorQuery = query
          /\ cursorResultKind = resultKind
          /\ cursorScope = scope
        unexpired == now < cursorExpiresAt
        causal ==
          cursorGraph \in ancestors[head] \union {head}
        conflictAllows ==
          conflict # AtLeastConflict \/ causal
        proofEquivalent ==
          /\ cursorLiftable
          /\ proofAvailable[head][scope]
          /\ cursorProof = proof[head][scope]
        currentEligible ==
          /\ scopeMatches
          /\ unexpired
          /\ conflictAllows
          /\ (cursorGraph = head \/ proofEquivalent)
        exactEligible ==
          /\ scopeMatches
          /\ unexpired
          /\ conflictAllows
          /\ ~currentEligible
          /\ cursorGraph \in retained
        accepted == currentEligible \/ exactEligible
        selectedDecisionGraph ==
          IF currentEligible
          THEN head
          ELSE IF exactEligible THEN cursorGraph ELSE head
        rejection ==
          IF ~authenticated
          THEN RejectedAuthentication
          ELSE IF ~scopeMatches
          THEN RejectedScope
          ELSE IF ~unexpired
          THEN RejectedExpiry
          ELSE IF ~conflictAllows
          THEN RejectedDivergence
          ELSE RejectedStale
    IN
    /\ dependencyScope' = scope
    /\ selectedOperation' = operation
    /\ selectedQuery' = query
    /\ selectedDirection' = direction
    /\ selectedResultKind' = resultKind
    /\ selectedConflict' = conflict
    /\ selectedGraph' = head
    /\ computationGraph' = selectedDecisionGraph
    /\ exactGraph' = IF exactEligible THEN cursorGraph ELSE head
    /\ outcome' =
      IF currentEligible
      THEN CursorCurrent
      ELSE IF exactEligible THEN CursorExact ELSE CursorRejected
    /\ rejectReason' = IF accepted THEN NoReject ELSE rejection
    /\ decisionReturned' = accepted
    /\ decisionGraph' = selectedDecisionGraph
    /\ decisionOffset' = cursorOffset
    /\ pageGraphUsed' = selectedDecisionGraph
    /\ replayed' = FALSE
    /\ cursorOffset' =
      IF accepted THEN cursorOffset + 1 ELSE cursorOffset
    /\ telemetry' = telemetry + 1
    /\ UNCHANGED
      <<active, head, ancestors, proof, proofAvailable, writer,
        snapshotWrite, retained, source, now, lastHeadMove,
        cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
        cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
        cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
        cursorDirection, cursorResultKind, cursorGraph, cursorScope,
        cursorProof, cursorSource, cursorLiftable, cursorExpiresAt,
        continuationPresent, continuationGraph, continuationOffset,
        pagePresent, pageGraph, pageOffset>>

PublishContinuation ==
  /\ cursorPresent
  /\ continuationPresent' = TRUE
  /\ continuationGraph' = cursorGraph
  /\ continuationOffset' = cursorOffset
  /\ selectedGraph' = head
  /\ computationGraph' = head
  /\ exactGraph' = head
  /\ outcome' = NoOutcome
  /\ rejectReason' = NoReject
  /\ decisionReturned' = FALSE
  /\ decisionGraph' = head
  /\ decisionOffset' = cursorOffset
  /\ pageGraphUsed' = head
  /\ replayed' = FALSE
  /\ UNCHANGED
    <<active, head, ancestors, proof, proofAvailable, writer,
      snapshotWrite, retained, source, now, lastHeadMove,
      dependencyScope, selectedOperation, selectedQuery,
      selectedDirection, selectedResultKind, selectedConflict,
      cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
      cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
      telemetry,
      cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
      cursorDirection, cursorResultKind, cursorGraph, cursorScope,
      cursorProof, cursorSource, cursorLiftable, cursorOffset,
      cursorExpiresAt,
      pagePresent, pageGraph, pageOffset>>

PublishPage ==
  /\ cursorPresent
  /\ pagePresent' = TRUE
  /\ pageGraph' = cursorGraph
  /\ pageOffset' = cursorOffset
  /\ selectedGraph' = head
  /\ computationGraph' = head
  /\ exactGraph' = head
  /\ outcome' = NoOutcome
  /\ rejectReason' = NoReject
  /\ decisionReturned' = FALSE
  /\ decisionGraph' = head
  /\ decisionOffset' = cursorOffset
  /\ pageGraphUsed' = head
  /\ replayed' = FALSE
  /\ UNCHANGED
    <<active, head, ancestors, proof, proofAvailable, writer,
      snapshotWrite, retained, source, now, lastHeadMove,
      dependencyScope, selectedOperation, selectedQuery,
      selectedDirection, selectedResultKind, selectedConflict,
      cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
      cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
      telemetry,
      cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
      cursorDirection, cursorResultKind, cursorGraph, cursorScope,
      cursorProof, cursorSource, cursorLiftable, cursorOffset,
      cursorExpiresAt,
      continuationPresent, continuationGraph, continuationOffset>>

RetryPublication ==
  /\ cursorPresent
  /\ continuationPresent' = TRUE
  /\ continuationGraph' = cursorGraph
  /\ continuationOffset' = cursorOffset
  /\ pagePresent' = TRUE
  /\ pageGraph' = cursorGraph
  /\ pageOffset' = cursorOffset
  /\ selectedGraph' = head
  /\ computationGraph' = head
  /\ exactGraph' = head
  /\ outcome' = NoOutcome
  /\ rejectReason' = NoReject
  /\ decisionReturned' = FALSE
  /\ decisionGraph' = head
  /\ decisionOffset' = cursorOffset
  /\ pageGraphUsed' = head
  /\ replayed' = FALSE
  /\ UNCHANGED
    <<active, head, ancestors, proof, proofAvailable, writer,
      snapshotWrite, retained, source, now, lastHeadMove,
      dependencyScope, selectedOperation, selectedQuery,
      selectedDirection, selectedResultKind, selectedConflict,
      cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
      cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
      telemetry,
      cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
      cursorDirection, cursorResultKind, cursorGraph, cursorScope,
      cursorProof, cursorSource, cursorLiftable, cursorOffset,
      cursorExpiresAt>>

EvictContinuation ==
  /\ continuationPresent' = FALSE
  /\ pagePresent' = FALSE
  /\ selectedGraph' = head
  /\ computationGraph' = head
  /\ exactGraph' = head
  /\ outcome' = NoOutcome
  /\ rejectReason' = NoReject
  /\ decisionReturned' = FALSE
  /\ decisionGraph' = head
  /\ decisionOffset' = cursorOffset
  /\ pageGraphUsed' = head
  /\ replayed' = FALSE
  /\ UNCHANGED
    <<active, head, ancestors, proof, proofAvailable, writer,
      snapshotWrite, retained, source, now, lastHeadMove,
      dependencyScope, selectedOperation, selectedQuery,
      selectedDirection, selectedResultKind, selectedConflict,
      cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
      cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
      telemetry,
      cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
      cursorDirection, cursorResultKind, cursorGraph, cursorScope,
      cursorProof, cursorSource, cursorLiftable, cursorOffset,
      cursorExpiresAt,
      continuationGraph, continuationOffset, pageGraph, pageOffset>>

ContinuationFetch ==
  /\ outcome \in {CursorCurrent, CursorExact}
  /\ LET pageEligible ==
           /\ pagePresent
           /\ pageGraph = decisionGraph
           /\ pageOffset = decisionOffset
         continuationEligible ==
           /\ continuationPresent
           /\ continuationGraph = decisionGraph
           /\ continuationOffset = decisionOffset
     IN
     /\ outcome' =
       IF pageEligible
       THEN PageCacheHit
       ELSE IF continuationEligible
            THEN ContinuationResumed
            ELSE DeterministicReplay
     /\ replayed' = ~(pageEligible \/ continuationEligible)
  /\ selectedGraph' = head
  /\ computationGraph' = decisionGraph
  /\ exactGraph' = decisionGraph
  /\ rejectReason' = NoReject
  /\ decisionReturned' = TRUE
  /\ pageGraphUsed' = decisionGraph
  /\ UNCHANGED
    <<active, head, ancestors, proof, proofAvailable, writer,
      snapshotWrite, retained, source, now, lastHeadMove,
      dependencyScope, selectedOperation, selectedQuery,
      selectedDirection, selectedResultKind, selectedConflict,
      cachePresent, cacheAuthenticated, cacheGraph, cacheScope,
      cacheProof, cacheQuery, cacheSource, cacheValue, cacheGeneration,
      telemetry,
      cursorPresent, cursorAuthenticated, cursorOperation, cursorQuery,
      cursorDirection, cursorResultKind, cursorGraph, cursorScope,
      cursorProof, cursorSource, cursorLiftable, cursorOffset,
      cursorExpiresAt,
      continuationPresent, continuationGraph, continuationOffset,
      pagePresent, pageGraph, pageOffset, decisionGraph, decisionOffset>>

Next ==
  \/ ManagedGraphWrite
  \/ UnmanagedGraphWrite
  \/ ManagedSchemaWrite
  \/ UnmanagedSchemaWrite
  \/ PublishProofEquivalent
  \/ CloneHead
  \/ ResetHead
  \/ RestoreHead
  \/ BranchHead
  \/ ForceHead
  \/ ExpireRetained
  \/ AdvanceTime
  \/ CachePut
  \/ TamperCache
  \/ CacheRead
  \/ CacheProviderFailure
  \/ TelemetryCAS
  \/ CursorMint
  \/ TamperCursor
  \/ CursorResume
  \/ PublishContinuation
  \/ PublishPage
  \/ RetryPublication
  \/ EvictContinuation
  \/ ContinuationFetch

=============================================================================
