---------------- MODULE EaclUuidLifecycle ----------------
EXTENDS Integers, FiniteSets

CONSTANTS
  \* @type: Bool;
  OmitScope,
  \* @type: Bool;
  OmitLifecycle,
  \* @type: Bool;
  AllowDetached

\* UUIDs are finite abstract identities here, not numeric generations. The
\* representation/refinement proof lives in UuidLifecycle.dfy. The host must
\* never reuse an identity for a replacement history of the same source.
Sources == {0, 1}
Uuids == {0, 1, 2}
Incarnations == 0..4
Revisions == 0..2
Scopes == [backend: Sources, source: Sources, branch: Sources]
Captures == [scope: Scopes, uuid: Uuids, incarnation: Incarnations, store: Incarnations, revision: Revisions]

VARIABLES
  \* @type: {scope: {backend: Int, source: Int, branch: Int}, uuid: Int, incarnation: Int, store: Int, revision: Int};
  current,
  \* @type: {scope: {backend: Int, source: Int, branch: Int}, uuid: Int, incarnation: Int, store: Int, revision: Int};
  held,
  \* @type: Bool;
  captured,
  \* @type: Bool;
  accepted,
  \* @type: Bool;
  restorePending,
  \* @type: {scope: {backend: Int, source: Int, branch: Int}, uuid: Int, incarnation: Int, store: Int, revision: Int};
  restoreCapture,
  \* @type: Bool;
  publication,
  \* @type: Bool;
  storePublication

vars == <<current, held, captured, accepted, restorePending, restoreCapture, publication, storePublication>>

Init ==
  /\ current = [scope |-> [backend |-> 0, source |-> 0, branch |-> 0],
                 uuid |-> 0, incarnation |-> 0, store |-> 0, revision |-> 0]
  /\ held = current
  /\ restoreCapture = current
  /\ captured = FALSE
  /\ accepted = FALSE
  /\ publication = FALSE
  /\ storePublication = FALSE
  /\ restorePending = FALSE

Capture ==
  /\ held' = current
  /\ captured' = TRUE
  /\ accepted' = FALSE
  /\ UNCHANGED <<current, restorePending, restoreCapture, publication, storePublication>>

Commit ==
  /\ current.revision < 2
  /\ current' = [current EXCEPT !.revision = @ + 1]
  /\ UNCHANGED <<held, captured, accepted, restorePending, restoreCapture, publication, storePublication>>

\* Narrow clear changes only the cache-store token. A validated restore may
\* rebase across this action because the source incarnation remains current.
Clear ==
  /\ current.store < 4
  /\ current' = [current EXCEPT !.store = @ + 1]
  /\ accepted' = FALSE
  /\ UNCHANGED <<held, captured, restorePending, restoreCapture, publication, storePublication>>

\* Full same-UUID expiry also retires source incarnation and proof health.
FullReset ==
  /\ current.incarnation < 4 /\ current.store < 4
  /\ current' = [current EXCEPT !.incarnation = @ + 1, !.store = @ + 1]
  /\ accepted' = FALSE
  /\ UNCHANGED <<held, captured, restorePending, restoreCapture, publication, storePublication>>

Rotate ==
  /\ current.incarnation < 4 /\ current.store < 4
  /\ current.uuid < 2
  /\ current' = [current EXCEPT !.uuid = @ + 1, !.incarnation = @ + 1, !.store = @ + 1, !.revision = 0]
  /\ accepted' = FALSE
  /\ UNCHANGED <<held, captured, restorePending, restoreCapture, publication, storePublication>>

\* Different sources can share the reserved initial UUID. Retained work still
\* carries its original full scope; no field is overwritten on source change.
SelectAnotherSource ==
  /\ \E scope \in Scopes : current' = [current EXCEPT !.scope = scope]
  /\ accepted' = FALSE
  /\ UNCHANGED <<held, captured, restorePending, restoreCapture, publication, storePublication>>

\* @type: ({scope: {backend: Int, source: Int, branch: Int}, uuid: Int, incarnation: Int, store: Int, revision: Int}, {scope: {backend: Int, source: Int, branch: Int}, uuid: Int, incarnation: Int, store: Int, revision: Int}) => Bool;
Matches(a, b) ==
  /\ (OmitScope \/ a.scope = b.scope)
  /\ (OmitLifecycle \/ a.uuid = b.uuid)

Publish ==
  /\ captured
  /\ publication' = TRUE
  /\ storePublication' = TRUE
  /\ accepted' = Matches(held, current) /\
                  (AllowDetached \/ held.incarnation = current.incarnation) /\
                  held.store = current.store
  /\ UNCHANGED <<current, held, captured, restorePending, restoreCapture>>

PrepareRestore ==
  /\ restoreCapture' = current
  /\ restorePending' = TRUE
  /\ accepted' = FALSE
  /\ UNCHANGED <<current, held, captured, publication, storePublication>>

InstallRestore ==
  /\ restorePending
  /\ current.store < 4
  /\ publication' = TRUE
  /\ storePublication' = FALSE
  /\ held' = restoreCapture
  /\ captured' = TRUE
  /\ accepted' = Matches(restoreCapture, current) /\
                  (AllowDetached \/ restoreCapture.incarnation = current.incarnation)
  /\ current' = IF accepted' THEN [current EXCEPT !.store = @ + 1] ELSE current
  /\ restorePending' = FALSE
  /\ UNCHANGED restoreCapture

\* Imported artifacts have no meaningful process-local incarnation. This
\* action checks lineage eligibility only, not their independent proofs.
ReuseArtifact ==
  /\ captured
  /\ accepted' = Matches(held, current)
  /\ publication' = FALSE
  /\ storePublication' = FALSE
  /\ UNCHANGED <<current, held, captured, restorePending, restoreCapture>>

Next == ReuseArtifact \/ Capture \/ Commit \/ Clear \/ FullReset \/ Rotate \/ SelectAnotherSource \/ Publish \/ PrepareRestore \/ InstallRestore

TypeOK ==
  /\ current \in Captures
  /\ held \in Captures
  /\ restoreCapture \in Captures
  /\ captured \in BOOLEAN
  /\ accepted \in BOOLEAN
  /\ restorePending \in BOOLEAN
  /\ publication \in BOOLEAN
  /\ storePublication \in BOOLEAN

Safety == accepted =>
  /\ captured
  /\ held.scope = current.scope
  /\ held.uuid = current.uuid
  /\ (publication => held.incarnation = current.incarnation)
  /\ (storePublication => held.store = current.store)

Spec == Init /\ [][Next]_vars
InductiveInvariant == TypeOK /\ Safety
============================================================
