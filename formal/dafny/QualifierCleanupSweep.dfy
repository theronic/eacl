module QualifierCleanupSweep {
  datatype Snapshot = Snapshot(
    source: nat,
    lifecycle: nat,
    revision: nat,
    qualifiers: set<nat>,
    attached: set<nat>
  )

  predicate Certified(snapshot: Snapshot, candidates: set<nat>) {
    candidates <= snapshot.qualifiers && candidates !! snapshot.attached
  }

  predicate OwnCleanup(before: Snapshot, after: Snapshot, deleted: set<nat>) {
    after.source == before.source &&
    after.lifecycle == before.lifecycle &&
    after.revision > before.revision &&
    after.qualifiers == before.qualifiers - deleted &&
    after.attached == before.attached
  }

  lemma OwnCommitPreservesRemainingAbsence(
    before: Snapshot, after: Snapshot,
    candidates: set<nat>, deleted: set<nat>
  )
    requires Certified(before, candidates)
    requires deleted <= candidates
    requires OwnCleanup(before, after, deleted)
    ensures Certified(after, candidates - deleted)
    ensures deleted !! after.attached
  {
  }

  lemma ForeignAttachmentCannotExtendCertificate(
    before: Snapshot, after: Snapshot, candidates: set<nat>, qid: nat
  )
    requires Certified(before, candidates)
    requires qid in candidates
    requires qid in after.attached
    ensures !OwnCleanup(before, after, {})
    ensures !Certified(after, candidates)
  {
  }
}
