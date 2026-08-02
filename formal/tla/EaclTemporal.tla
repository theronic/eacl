-------------------------- MODULE EaclTemporal --------------------------
EXTENDS Integers, FiniteSets

CONSTANTS
  \* @type: Set(Int);
  Histories,
  \* @type: Set(Int);
  Proofs,
  \* @type: Set(Int);
  Queries,
  \* @type: Set(Int);
  Directions

ASSUME
  /\ Histories # {}
  /\ Proofs # {}
  /\ Queries # {}
  /\ Directions # {}
  /\ 0 \in Histories
  /\ 0 \in Proofs
  /\ 0 \in Queries
  /\ 0 \in Directions

NoOutcome == 0
CacheHit == 1
CacheMiss == 2
CursorCurrent == 3
CursorExact == 4
CursorRejected == 5
Outcomes == {NoOutcome, CacheHit, CacheMiss,
             CursorCurrent, CursorExact, CursorRejected}

VARIABLES
  \* @type: Set(Int);
  active,
  \* @type: Int;
  head,
  \* @type: Int -> Set(Int);
  ancestors,
  \* @type: Int -> Int;
  proof,
  \* @type: Set(Int);
  retained,
  \* @type: Bool;
  cachePresent,
  \* @type: Int;
  cacheGraph,
  \* @type: Int;
  cacheProof,
  \* @type: Int;
  cacheQuery,
  \* @type: Bool;
  cacheValue,
  \* @type: Bool;
  cursorPresent,
  \* @type: Int;
  cursorGraph,
  \* @type: Int;
  cursorProof,
  \* @type: Bool;
  cursorLiftable,
  \* @type: Int;
  cursorQuery,
  \* @type: Int;
  cursorDirection,
  \* @type: Int;
  cursorOffset,
  \* @type: Bool;
  continuationPresent,
  \* @type: Bool;
  pagePresent,
  \* @type: Int;
  selectedQuery,
  \* @type: Int;
  selectedDirection,
  \* @type: Int;
  outcome,
  \* @type: Int;
  chosenGraph,
  \* @type: Int;
  telemetry

vars ==
  <<active, head, ancestors, proof, retained,
    cachePresent, cacheGraph, cacheProof, cacheQuery, cacheValue,
    cursorPresent, cursorGraph, cursorProof, cursorLiftable, cursorQuery,
    cursorDirection, cursorOffset, continuationPresent, pagePresent,
    selectedQuery, selectedDirection, outcome, chosenGraph, telemetry>>

TypeOK ==
  /\ active \in SUBSET Histories
  /\ active # {}
  /\ head \in active
  /\ ancestors \in [Histories -> SUBSET Histories]
  /\ proof \in [Histories -> Proofs]
  /\ retained \in SUBSET active
  /\ cachePresent \in BOOLEAN
  /\ cacheGraph \in active
  /\ cacheProof \in Proofs
  /\ cacheQuery \in Queries
  /\ cacheValue \in BOOLEAN
  /\ cursorPresent \in BOOLEAN
  /\ cursorGraph \in active
  /\ cursorProof \in Proofs
  /\ cursorLiftable \in BOOLEAN
  /\ cursorQuery \in Queries
  /\ cursorDirection \in Directions
  /\ cursorOffset \in Nat
  /\ continuationPresent \in BOOLEAN
  /\ pagePresent \in BOOLEAN
  /\ selectedQuery \in Queries
  /\ selectedDirection \in Directions
  /\ outcome \in Outcomes
  /\ chosenGraph \in active
  /\ telemetry \in Nat

CausalClosure ==
  \A graph \in active:
    /\ ancestors[graph] \subseteq active
    /\ graph \notin ancestors[graph]
    /\ \A ancestor \in ancestors[graph]:
      ancestors[ancestor] \subseteq ancestors[graph]

CacheAcceptanceSafe ==
  outcome # CacheHit \/
    /\ cachePresent
    /\ cacheQuery = selectedQuery
    /\ cacheGraph \in ancestors[head] \union {head}
    /\ cacheProof = proof[head]
    /\ chosenGraph = cacheGraph

CursorCurrentSafe ==
  outcome # CursorCurrent \/
    /\ cursorPresent
    /\ cursorQuery = selectedQuery
    /\ cursorDirection = selectedDirection
    /\ (cursorGraph = head \/
        /\ cursorLiftable
        /\ cursorProof = proof[head])
    /\ chosenGraph = head

CursorExactSafe ==
  outcome # CursorExact \/
    /\ cursorPresent
    /\ cursorQuery = selectedQuery
    /\ cursorDirection = selectedDirection
    /\ cursorGraph \in retained
    /\ chosenGraph = cursorGraph

RejectedFailsClosed ==
  outcome # CursorRejected \/ chosenGraph = head

Safety ==
  /\ CacheAcceptanceSafe
  /\ CursorCurrentSafe
  /\ CursorExactSafe
  /\ RejectedFailsClosed

InductiveInvariant ==
  /\ TypeOK
  /\ CausalClosure
  /\ Safety

Init ==
  /\ active = {0}
  /\ head = 0
  /\ ancestors = [graph \in Histories |-> {}]
  /\ proof = [graph \in Histories |-> 0]
  /\ retained = {0}
  /\ cachePresent = FALSE
  /\ cacheGraph = 0
  /\ cacheProof = 0
  /\ cacheQuery = 0
  /\ cacheValue = FALSE
  /\ cursorPresent = FALSE
  /\ cursorGraph = 0
  /\ cursorProof = 0
  /\ cursorLiftable = FALSE
  /\ cursorQuery = 0
  /\ cursorDirection = 0
  /\ cursorOffset = 0
  /\ continuationPresent = FALSE
  /\ pagePresent = FALSE
  /\ selectedQuery = 0
  /\ selectedDirection = 0
  /\ outcome = NoOutcome
  /\ chosenGraph = 0
  /\ telemetry = 0

PublishChanged ==
  \E newGraph \in Histories \ active, newProof \in Proofs:
    /\ active' = active \union {newGraph}
    /\ head' = newGraph
    /\ ancestors' =
      [ancestors EXCEPT
        ![newGraph] = ancestors[head] \union {head}]
    /\ proof' = [proof EXCEPT ![newGraph] = newProof]
    /\ retained' = retained \union {newGraph}
    /\ outcome' = NoOutcome
    /\ chosenGraph' = newGraph
    /\ UNCHANGED
      <<cachePresent, cacheGraph, cacheProof, cacheQuery, cacheValue,
        cursorPresent, cursorGraph, cursorProof, cursorLiftable, cursorQuery,
        cursorDirection, cursorOffset, continuationPresent, pagePresent,
        selectedQuery, selectedDirection, telemetry>>

PublishProofEquivalent ==
  \E newGraph \in Histories \ active:
    /\ active' = active \union {newGraph}
    /\ head' = newGraph
    /\ ancestors' =
      [ancestors EXCEPT
        ![newGraph] = ancestors[head] \union {head}]
    /\ proof' = [proof EXCEPT ![newGraph] = proof[head]]
    /\ retained' = retained \union {newGraph}
    /\ outcome' = NoOutcome
    /\ chosenGraph' = newGraph
    /\ UNCHANGED
      <<cachePresent, cacheGraph, cacheProof, cacheQuery, cacheValue,
        cursorPresent, cursorGraph, cursorProof, cursorLiftable, cursorQuery,
        cursorDirection, cursorOffset, continuationPresent, pagePresent,
        selectedQuery, selectedDirection, telemetry>>

MoveHead ==
  \E target \in active:
    /\ head' = target
    /\ outcome' = NoOutcome
    /\ chosenGraph' = target
    /\ UNCHANGED
      <<active, ancestors, proof, retained,
        cachePresent, cacheGraph, cacheProof, cacheQuery, cacheValue,
        cursorPresent, cursorGraph, cursorProof, cursorLiftable, cursorQuery,
        cursorDirection, cursorOffset, continuationPresent, pagePresent,
        selectedQuery, selectedDirection, telemetry>>

ExpireRetained ==
  \E target \in retained:
    /\ retained' = retained \ {target}
    /\ outcome' = NoOutcome
    /\ chosenGraph' = head
    /\ UNCHANGED
      <<active, head, ancestors, proof,
        cachePresent, cacheGraph, cacheProof, cacheQuery, cacheValue,
        cursorPresent, cursorGraph, cursorProof, cursorLiftable, cursorQuery,
        cursorDirection, cursorOffset, continuationPresent, pagePresent,
        selectedQuery, selectedDirection, telemetry>>

CachePut ==
  \E query \in Queries, value \in BOOLEAN:
    /\ cachePresent' = TRUE
    /\ cacheGraph' = head
    /\ cacheProof' = proof[head]
    /\ cacheQuery' = query
    /\ cacheValue' = value
    /\ outcome' = NoOutcome
    /\ chosenGraph' = head
    /\ UNCHANGED
      <<active, head, ancestors, proof, retained,
        cursorPresent, cursorGraph, cursorProof, cursorLiftable, cursorQuery,
        cursorDirection, cursorOffset, continuationPresent, pagePresent,
        selectedQuery, selectedDirection, telemetry>>

TamperCache ==
  \E graph \in active, candidateProof \in Proofs:
    /\ cachePresent' = TRUE
    /\ cacheGraph' = graph
    /\ cacheProof' = candidateProof
    /\ outcome' = NoOutcome
    /\ chosenGraph' = head
    /\ UNCHANGED
      <<active, head, ancestors, proof, retained,
        cacheQuery, cacheValue,
        cursorPresent, cursorGraph, cursorProof, cursorLiftable, cursorQuery,
        cursorDirection, cursorOffset, continuationPresent, pagePresent,
        selectedQuery, selectedDirection, telemetry>>

CacheRead ==
  \E query \in Queries:
    LET eligible ==
      /\ cachePresent
      /\ cacheQuery = query
      /\ cacheGraph \in ancestors[head] \union {head}
      /\ cacheProof = proof[head]
    IN
    /\ selectedQuery' = query
    /\ outcome' = IF eligible THEN CacheHit ELSE CacheMiss
    /\ chosenGraph' = IF eligible THEN cacheGraph ELSE head
    /\ telemetry' = telemetry + 1
    /\ UNCHANGED
      <<active, head, ancestors, proof, retained,
        cachePresent, cacheGraph, cacheProof, cacheQuery, cacheValue,
        cursorPresent, cursorGraph, cursorProof, cursorLiftable, cursorQuery,
        cursorDirection, cursorOffset, continuationPresent, pagePresent,
        selectedDirection>>

CacheProviderFailure ==
  \E query \in Queries:
    /\ selectedQuery' = query
    /\ outcome' = CacheMiss
    /\ chosenGraph' = head
    /\ telemetry' = telemetry + 1
    /\ UNCHANGED
      <<active, head, ancestors, proof, retained,
        cachePresent, cacheGraph, cacheProof, cacheQuery, cacheValue,
        cursorPresent, cursorGraph, cursorProof, cursorLiftable, cursorQuery,
        cursorDirection, cursorOffset, continuationPresent, pagePresent,
        selectedDirection>>

CursorMint ==
  \E query \in Queries, direction \in Directions, liftable \in BOOLEAN:
    /\ cursorPresent' = TRUE
    /\ cursorGraph' = head
    /\ cursorProof' = proof[head]
    /\ cursorLiftable' = liftable
    /\ cursorQuery' = query
    /\ cursorDirection' = direction
    /\ cursorOffset' = 0
    /\ continuationPresent' = TRUE
    /\ pagePresent' = FALSE
    /\ outcome' = NoOutcome
    /\ chosenGraph' = head
    /\ UNCHANGED
      <<active, head, ancestors, proof, retained,
        cachePresent, cacheGraph, cacheProof, cacheQuery, cacheValue,
        selectedQuery, selectedDirection, telemetry>>

CursorResume ==
  \E query \in Queries, direction \in Directions:
    LET scopeMatches ==
          /\ cursorPresent
          /\ cursorQuery = query
          /\ cursorDirection = direction
        currentEligible ==
          /\ scopeMatches
          /\ (cursorGraph = head \/
              /\ cursorLiftable
              /\ cursorProof = proof[head])
        exactEligible ==
          /\ scopeMatches
          /\ ~currentEligible
          /\ cursorGraph \in retained
        accepted == currentEligible \/ exactEligible
    IN
    /\ selectedQuery' = query
    /\ selectedDirection' = direction
    /\ outcome' =
      IF currentEligible
      THEN CursorCurrent
      ELSE IF exactEligible THEN CursorExact ELSE CursorRejected
    /\ chosenGraph' =
      IF currentEligible
      THEN head
      ELSE IF exactEligible THEN cursorGraph ELSE head
    /\ cursorOffset' =
      IF accepted THEN cursorOffset + 1 ELSE cursorOffset
    /\ continuationPresent' =
      IF accepted THEN TRUE ELSE continuationPresent
    /\ pagePresent' = IF accepted THEN TRUE ELSE pagePresent
    /\ telemetry' = telemetry + 1
    /\ UNCHANGED
      <<active, head, ancestors, proof, retained,
        cachePresent, cacheGraph, cacheProof, cacheQuery, cacheValue,
        cursorPresent, cursorGraph, cursorProof, cursorLiftable, cursorQuery,
        cursorDirection>>

EvictContinuation ==
  /\ continuationPresent' = FALSE
  /\ pagePresent' = FALSE
  /\ outcome' = NoOutcome
  /\ chosenGraph' = head
  /\ UNCHANGED
    <<active, head, ancestors, proof, retained,
      cachePresent, cacheGraph, cacheProof, cacheQuery, cacheValue,
      cursorPresent, cursorGraph, cursorProof, cursorLiftable, cursorQuery,
      cursorDirection, cursorOffset, selectedQuery, selectedDirection,
      telemetry>>

TelemetryCAS ==
  /\ telemetry' = telemetry + 1
  /\ outcome' = NoOutcome
  /\ chosenGraph' = head
  /\ UNCHANGED
    <<active, head, ancestors, proof, retained,
      cachePresent, cacheGraph, cacheProof, cacheQuery, cacheValue,
      cursorPresent, cursorGraph, cursorProof, cursorLiftable, cursorQuery,
      cursorDirection, cursorOffset, continuationPresent, pagePresent,
      selectedQuery, selectedDirection>>

Next ==
  \/ PublishChanged
  \/ PublishProofEquivalent
  \/ MoveHead
  \/ ExpireRetained
  \/ CachePut
  \/ TamperCache
  \/ CacheRead
  \/ CacheProviderFailure
  \/ CursorMint
  \/ CursorResume
  \/ EvictContinuation
  \/ TelemetryCAS

=============================================================================
