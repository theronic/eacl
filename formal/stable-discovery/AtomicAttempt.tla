---------------------------- MODULE AtomicAttempt ----------------------------
EXTENDS Naturals, FiniteSets

CONSTANTS
  Attempts,
  Capacity,
  IntegratePartial,
  IntegrateDuplicate,
  PublishPartial

ASSUME
  /\ Cardinality(Attempts) > Capacity
  /\ Capacity > 1
  /\ IntegratePartial \in BOOLEAN
  /\ IntegrateDuplicate \in BOOLEAN
  /\ PublishPartial \in BOOLEAN

VARIABLES
  used,
  physical,
  charged,
  partial,
  validated,
  integrations,
  canceled,
  integrationsAtCancel,
  projectionPublished,
  invalidIntegration,
  invalidPublication

vars ==
  <<used, physical, charged, partial, validated, integrations, canceled,
    integrationsAtCancel, projectionPublished, invalidIntegration,
    invalidPublication>>

TypeOK ==
  /\ used \in SUBSET Attempts
  /\ physical \in SUBSET Attempts
  /\ charged \in SUBSET Attempts
  /\ partial \in SUBSET Attempts
  /\ validated \in SUBSET Attempts
  /\ integrations \in 0..2
  /\ canceled \in BOOLEAN
  /\ integrationsAtCancel \in 0..2
  /\ projectionPublished \in BOOLEAN
  /\ invalidIntegration \in BOOLEAN
  /\ invalidPublication \in BOOLEAN

CapacitySafety ==
  /\ charged = physical
  /\ Cardinality(charged) <= Capacity

AtomicSemanticIntegration ==
  /\ integrations <= 1
  /\ ~invalidIntegration
  /\ (~canceled \/ integrations = integrationsAtCancel)

AtomicProjectionPublication == ~invalidPublication

Safety ==
  /\ TypeOK
  /\ CapacitySafety
  /\ AtomicSemanticIntegration
  /\ AtomicProjectionPublication

Init ==
  /\ used = {}
  /\ physical = {}
  /\ charged = {}
  /\ partial = {}
  /\ validated = {}
  /\ integrations = 0
  /\ canceled = FALSE
  /\ integrationsAtCancel = 0
  /\ projectionPublished = FALSE
  /\ invalidIntegration = FALSE
  /\ invalidPublication = FALSE

Start ==
  \E attempt \in Attempts \ used:
    /\ ~canceled
    /\ Cardinality(charged) < Capacity
    /\ used' = used \union {attempt}
    /\ physical' = physical \union {attempt}
    /\ charged' = charged \union {attempt}
    /\ UNCHANGED
         <<partial, validated, integrations, canceled,
           integrationsAtCancel, projectionPublished,
           invalidIntegration, invalidPublication>>

ReceivePartial ==
  \E attempt \in physical:
    /\ partial' = partial \union {attempt}
    /\ IF PublishPartial
       THEN
         /\ projectionPublished' = TRUE
         /\ invalidPublication' = TRUE
       ELSE UNCHANGED <<projectionPublished, invalidPublication>>
    /\ UNCHANGED
         <<used, physical, charged, validated, integrations, canceled,
           integrationsAtCancel, invalidIntegration>>

CompleteValidated ==
  \E attempt \in physical:
    /\ physical' = physical \ {attempt}
    /\ charged' = charged \ {attempt}
    /\ partial' = partial \ {attempt}
    /\ validated' = validated \union {attempt}
    /\ projectionPublished' = TRUE
    /\ UNCHANGED
         <<used, integrations, canceled, integrationsAtCancel,
           invalidIntegration, invalidPublication>>

CompleteFailure ==
  \E attempt \in physical:
    /\ physical' = physical \ {attempt}
    /\ charged' = charged \ {attempt}
    /\ partial' = partial \ {attempt}
    /\ UNCHANGED
         <<used, validated, integrations, canceled,
           integrationsAtCancel, projectionPublished,
           invalidIntegration, invalidPublication>>

Integrate ==
  /\ ~canceled
  /\ integrations < 2
  /\ integrations = 0 \/ IntegrateDuplicate
  /\ \/ Cardinality(validated) > 0
     \/ IntegratePartial /\ Cardinality(partial) > 0
  /\ integrations' = integrations + 1
  /\ invalidIntegration' =
       IF Cardinality(validated) = 0 \/ integrations > 0
       THEN TRUE
       ELSE invalidIntegration
  /\ IF IntegrateDuplicate
     THEN UNCHANGED validated
     ELSE validated' = {}
  /\ UNCHANGED
       <<used, physical, charged, partial, canceled,
         integrationsAtCancel, projectionPublished, invalidPublication>>

Cancel ==
  /\ ~canceled
  /\ canceled' = TRUE
  /\ integrationsAtCancel' = integrations
  /\ validated' = {}
  /\ UNCHANGED
       <<used, physical, charged, partial, integrations,
         projectionPublished, invalidIntegration, invalidPublication>>

Next ==
  \/ Start
  \/ ReceivePartial
  \/ CompleteValidated
  \/ CompleteFailure
  \/ Integrate
  \/ Cancel

=============================================================================
