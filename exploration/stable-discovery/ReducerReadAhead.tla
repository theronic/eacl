--------------------------- MODULE ReducerReadAhead ---------------------------
EXTENDS Integers, Naturals, FiniteSets, Sequences

\* This is deliberately a small concurrency shell around a deterministic
\* reducer.  Tasks are logical read occurrences, not physical storage calls.
\* Children become visible only after their parent integrates, so the model
\* exercises a dynamically revealed traversal rather than a fixed task list.

CONSTANTS
  Tasks,
  Root,
  Capacity,
  Reserve,
  IntegrateAnyReady,
  LendReserveToSpeculation,
  IntegrateAfterCancel,
  FreeReadyWindow,
  EvictPinned

\* A small dynamically revealed tree.  The concurrency theorem is about the
\* shell; generalized reducer refinement belongs in Dafny, where it is much
\* cheaper to prove universally than to enumerate many TLA+ topologies.
Children(task) ==
  CASE task = 0 -> <<1, 2, 4>>
    [] task = 1 -> <<3>>
    [] OTHER -> <<>>

Expected == <<0, 1, 3, 2, 4>>

ASSUME
  /\ Root \in Tasks
  /\ Len(Expected) = Cardinality(Tasks)
  /\ Expected[1] = Root
  /\ 0 < Reserve
  /\ Reserve <= Capacity
  /\ IntegrateAnyReady \in BOOLEAN
  /\ LendReserveToSpeculation \in BOOLEAN
  /\ IntegrateAfterCancel \in BOOLEAN
  /\ FreeReadyWindow \in BOOLEAN
  /\ EvictPinned \in BOOLEAN

VARIABLES
  \* Canonical reducer stack.  Index one is the only integrable task.
  frontier,
  \* Physical calls that still occupy service capacity.
  physical,
  \* Complete responses retained by this request until canonical consumption.
  ready,
  \* Complete immutable chunks published for exact-context reuse.
  projection,
  \* Ghost history of semantic integration.
  integrated,
  canceled,
  integratedAtCancel

vars ==
  <<frontier, physical, ready, projection, integrated, canceled,
    integratedAtCancel>>

SeqToSet(sequence) ==
  {sequence[index] : index \in 1..Len(sequence)}

NoDuplicates(sequence) ==
  Cardinality(SeqToSet(sequence)) = Len(sequence)

IsPrefix(prefix, whole) ==
  /\ Len(prefix) <= Len(whole)
  /\ prefix = SubSeq(whole, 1, Len(prefix))

CanonicalTask ==
  IF Len(frontier) = 0 THEN Root ELSE Head(frontier)

TypeOK ==
  /\ frontier \in Seq(Tasks)
  /\ physical \in SUBSET Tasks
  /\ ready \in SUBSET Tasks
  /\ projection \in SUBSET Tasks
  /\ integrated \in Seq(Tasks)
  /\ canceled \in BOOLEAN
  /\ integratedAtCancel \in Seq(Tasks)

ReducerSafety ==
  /\ IsPrefix(integrated, Expected)
  /\ NoDuplicates(integrated)
  /\ NoDuplicates(frontier)
  /\ SeqToSet(integrated) \cap SeqToSet(frontier) = {}
  /\ physical \subseteq SeqToSet(frontier)
  /\ ready \subseteq SeqToSet(frontier)
  /\ ready \subseteq projection
  /\ physical \cap ready = {}

CapacitySafety ==
  /\ Cardinality(physical) <= Capacity
  /\ Cardinality(physical \union ready) <= Capacity
  /\ Cardinality((physical \union ready) \ {CanonicalTask}) <=
       Capacity - Reserve

WindowHeld ==
  IF FreeReadyWindow THEN physical ELSE physical \union ready

CancellationSafety ==
  ~canceled \/ integrated = integratedAtCancel

Safety ==
  /\ TypeOK
  /\ ReducerSafety
  /\ CapacitySafety
  /\ CancellationSafety

Init ==
  /\ frontier = <<Root>>
  /\ physical = {}
  /\ ready = {}
  /\ projection = {}
  /\ integrated = <<>>
  /\ canceled = FALSE
  /\ integratedAtCancel = <<>>

Issue ==
  \E task \in SeqToSet(frontier):
    /\ ~canceled
    /\ task \notin physical \union ready \union projection
    /\ Cardinality(WindowHeld) < Capacity
    /\ \/ task = CanonicalTask
       \/ LendReserveToSpeculation
       \/ Cardinality(WindowHeld \ {CanonicalTask}) < Capacity - Reserve
    /\ physical' = physical \union {task}
    /\ UNCHANGED
         <<frontier, ready, projection, integrated, canceled,
           integratedAtCancel>>

CompleteSuccess ==
  \E task \in physical:
    /\ physical' = physical \ {task}
    /\ projection' = projection \union {task}
    /\ IF canceled
       THEN UNCHANGED ready
       ELSE ready' = ready \union {task}
    /\ UNCHANGED
         <<frontier, integrated, canceled, integratedAtCancel>>

CompleteFailure ==
  \E task \in physical:
    /\ physical' = physical \ {task}
    /\ UNCHANGED
         <<frontier, ready, projection, integrated, canceled,
           integratedAtCancel>>

EvictProjection ==
  \E task \in projection:
    /\ \/ task \notin ready
       \/ EvictPinned
    /\ projection' = projection \ {task}
    /\ UNCHANGED
         <<frontier, physical, ready, integrated, canceled,
           integratedAtCancel>>

IntegrateCanonical ==
  /\ Len(frontier) > 0
  /\ ~canceled \/ IntegrateAfterCancel
  /\ \E task \in SeqToSet(frontier):
    /\ task \in ready \union projection
    /\ task = Head(frontier) \/ IntegrateAnyReady
    /\ LET remainder ==
             SelectSeq(frontier, LAMBDA current: current # task)
       IN
       /\ frontier' = Children(task) \o remainder
       /\ integrated' = Append(integrated, task)
       /\ ready' = ready \ {task}
       /\ UNCHANGED <<physical, projection, canceled, integratedAtCancel>>

Cancel ==
  /\ ~canceled
  /\ canceled' = TRUE
  /\ integratedAtCancel' = integrated
  /\ ready' = {}
  /\ UNCHANGED <<frontier, physical, projection, integrated>>

Next ==
  \/ Issue
  \/ CompleteSuccess
  \/ CompleteFailure
  \/ EvictProjection
  \/ IntegrateCanonical
  \/ Cancel

=============================================================================
