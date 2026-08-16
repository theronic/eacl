------------------------- MODULE DescriptorCoalescing -------------------------
EXTENDS Naturals, FiniteSets, Sequences

\* Request-local physical coalescing. Logical occurrences remain distinct:
\* occurrences 1 and 2 deliberately have the same equality-complete physical
\* descriptor, but may represent different semantic continuations.

CONSTANTS
  Occurrences,
  Capacity,
  AllowDuplicateFlight

ASSUME
  /\ Occurrences = {0, 1, 2, 3}
  /\ Capacity = 2
  /\ AllowDuplicateFlight \in BOOLEAN

Descriptor(occurrence) ==
  CASE occurrence = 1 -> 1
    [] occurrence = 2 -> 1
    [] OTHER -> occurrence

Expected == <<0, 1, 2, 3>>

VARIABLES
  frontier,
  physical,
  complete,
  integrated,
  canceled,
  integratedAtCancel

vars ==
  <<frontier, physical, complete, integrated, canceled,
    integratedAtCancel>>

SeqToSet(sequence) ==
  {sequence[index] : index \in 1..Len(sequence)}

IsPrefix(prefix, whole) ==
  /\ Len(prefix) <= Len(whole)
  /\ prefix = SubSeq(whole, 1, Len(prefix))

PhysicalDescriptors ==
  {Descriptor(occurrence) : occurrence \in physical}

TypeOK ==
  /\ frontier \in Seq(Occurrences)
  /\ physical \in SUBSET Occurrences
  /\ complete \in SUBSET Occurrences
  /\ integrated \in Seq(Occurrences)
  /\ canceled \in BOOLEAN
  /\ integratedAtCancel \in Seq(Occurrences)

AtMostOneFlightPerDescriptor ==
  Cardinality(PhysicalDescriptors) = Cardinality(physical)

StableIntegration ==
  /\ IsPrefix(integrated, Expected)
  /\ frontier = SubSeq(Expected, Len(integrated) + 1, Len(Expected))

CapacitySafety ==
  Cardinality(physical) <= Capacity

CancellationSafety ==
  ~canceled \/ integrated = integratedAtCancel

Safety ==
  /\ TypeOK
  /\ AtMostOneFlightPerDescriptor
  /\ StableIntegration
  /\ CapacitySafety
  /\ CancellationSafety

Init ==
  /\ frontier = Expected
  /\ physical = {}
  /\ complete = {}
  /\ integrated = <<>>
  /\ canceled = FALSE
  /\ integratedAtCancel = <<>>

Issue ==
  \E occurrence \in SeqToSet(frontier):
    /\ ~canceled
    /\ Descriptor(occurrence) \notin complete
    /\ occurrence \notin physical
    /\ Cardinality(physical) < Capacity
    /\ \/ AllowDuplicateFlight
       \/ Descriptor(occurrence) \notin PhysicalDescriptors
    /\ physical' = physical \union {occurrence}
    /\ UNCHANGED
         <<frontier, complete, integrated, canceled, integratedAtCancel>>

CompleteSuccess ==
  \E occurrence \in physical:
    /\ physical' = physical \ {occurrence}
    /\ complete' = complete \union {Descriptor(occurrence)}
    /\ UNCHANGED
         <<frontier, integrated, canceled, integratedAtCancel>>

CompleteFailure ==
  \E occurrence \in physical:
    /\ physical' = physical \ {occurrence}
    /\ UNCHANGED
         <<frontier, complete, integrated, canceled, integratedAtCancel>>

IntegrateCanonical ==
  /\ ~canceled
  /\ Len(frontier) > 0
  /\ Descriptor(Head(frontier)) \in complete
  /\ integrated' = Append(integrated, Head(frontier))
  /\ frontier' = Tail(frontier)
  /\ UNCHANGED
       <<physical, complete, canceled, integratedAtCancel>>

Cancel ==
  /\ ~canceled
  /\ canceled' = TRUE
  /\ integratedAtCancel' = integrated
  /\ UNCHANGED <<frontier, physical, complete, integrated>>

Next ==
  \/ Issue
  \/ CompleteSuccess
  \/ CompleteFailure
  \/ IntegrateCanonical
  \/ Cancel

=============================================================================
