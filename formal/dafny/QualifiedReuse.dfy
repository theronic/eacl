include "QualifiedTemporal.dfy"

module QualifiedReuse {
  import opened E = QualifiedEvidence
  import opened T = QualifiedTemporal

  // These identities are collision-checked canonical values, not bare hashes.
  datatype Scope = Scope(source: nat, schema: nat, relations: seq<nat>, qualifiers: seq<nat>, context: seq<nat>, evaluator: seq<nat>, policy: nat, abi: seq<nat>, query: seq<nat>)
  datatype Entry = Entry(authenticated: bool, scope: Scope, basis: nat, start: int, evidence: Evidence, kind: Kind)
  datatype Mode = Pinned | Live
  datatype Cursor = Cursor(entry: Entry, mode: Mode, tokenExpiry: int, retainedComplete: bool)

  predicate ScopeMatches(entry: Entry, scope: Scope) { entry.authenticated && entry.scope == scope }

  predicate TimeMatches(entry: Entry, basis: nat, time: int) {
    (entry.start == time && entry.basis == basis) ||
    (entry.evidence.complete && entry.start <= time && Before(time, entry.evidence.end))
  }

  predicate AcceptCache(universe: set<nat>, entry: Entry, scope: Scope, basis: nat, ancestors: set<nat>, time: int) {
    ScopeMatches(entry, scope) &&
    Before(entry.start, entry.evidence.end) &&
    (entry.basis == basis || entry.basis in ancestors) &&
    entry.kind == Classify(universe, entry.evidence.value) && entry.kind != Failure &&
    TimeMatches(entry, basis, time)
  }

  // Certificate completeness includes skipped/subtracting evidence before the
  // boundary and retained frontier/lookahead, not just the last emitted item.
  predicate AcceptCursor(universe: set<nat>, cursor: Cursor, scope: Scope, basis: nat, ancestors: set<nat>, rawNow: int, evaluationTime: int, keyAvailable: bool) {
    keyAvailable && rawNow < cursor.tokenExpiry &&
    AcceptCache(universe, cursor.entry, scope, basis, ancestors, evaluationTime) &&
    (if cursor.mode.Pinned?
      then basis == cursor.entry.basis && evaluationTime == cursor.entry.start
      else cursor.entry.start <= evaluationTime &&
           (evaluationTime == cursor.entry.start || (cursor.retainedComplete && cursor.entry.evidence.complete && Before(evaluationTime, cursor.entry.evidence.end))))
  }

  datatype DecodeProof = DecodeProof(source: nat, basis: nat, qid: nat, version: nat, format: nat, relation: nat, writerCertified: bool, content: seq<nat>)

  predicate AcceptDecode(prior: DecodeProof, selected: DecodeProof) {
    prior.source == selected.source && prior.qid == selected.qid && prior.format == selected.format &&
    (prior.basis == selected.basis ||
     (prior.writerCertified && selected.writerCertified && prior.version != 0 && prior.version == selected.version && prior.relation == selected.relation) ||
     (prior.content != [] && prior.content == selected.content))
  }

  lemma CacheAcceptsOnlyCompleteScope(universe: set<nat>, entry: Entry, scope: Scope, basis: nat, ancestors: set<nat>, time: int)
    requires AcceptCache(universe, entry, scope, basis, ancestors, time)
    ensures entry.authenticated && entry.scope == scope
    ensures entry.kind == Classify(universe, entry.evidence.value) && entry.kind != Failure
    ensures (entry.start == time && entry.basis == basis) || (entry.evidence.complete && entry.start <= time && Before(time, entry.evidence.end))
  {}

  lemma DeadlineIsRejectedWithoutEviction(universe: set<nat>, entry: Entry, scope: Scope, basis: nat, ancestors: set<nat>, time: int)
    requires entry.evidence.end.Until? && entry.evidence.end.at <= time
    requires entry.start < entry.evidence.end.at
    ensures !AcceptCache(universe, entry, scope, basis, ancestors, time)
  {}

  lemma ConditionalAliasIsRejected(universe: set<nat>, entry: Entry, scope: Scope, basis: nat, ancestors: set<nat>, time: int)
    requires Classify(universe, entry.evidence.value) == Conditional && entry.kind == Has
    ensures !AcceptCache(universe, entry, scope, basis, ancestors, time)
  {}

  lemma NoCertificateMeansExactTimeAndBasis(universe: set<nat>, entry: Entry, scope: Scope, basis: nat, ancestors: set<nat>, time: int)
    requires !entry.evidence.complete
    requires AcceptCache(universe, entry, scope, basis, ancestors, time)
    ensures time == entry.start && basis == entry.basis
  {}

  lemma PinnedRetainsOriginalView(universe: set<nat>, cursor: Cursor, scope: Scope, basis: nat, ancestors: set<nat>, rawNow: int, evaluationTime: int, keyAvailable: bool)
    requires cursor.mode.Pinned?
    requires AcceptCursor(universe, cursor, scope, basis, ancestors, rawNow, evaluationTime, keyAvailable)
    ensures evaluationTime == cursor.entry.start && basis == cursor.entry.basis
    ensures rawNow < cursor.tokenExpiry && keyAvailable
  {}

  lemma LiveCannotCrossIncompleteOrExpiredState(universe: set<nat>, cursor: Cursor, scope: Scope, basis: nat, ancestors: set<nat>, rawNow: int, evaluationTime: int, keyAvailable: bool)
    requires cursor.mode.Live? && evaluationTime != cursor.entry.start
    requires AcceptCursor(universe, cursor, scope, basis, ancestors, rawNow, evaluationTime, keyAvailable)
    ensures cursor.entry.start < evaluationTime
    ensures cursor.retainedComplete && cursor.entry.evidence.complete && Before(evaluationTime, cursor.entry.evidence.end)
  {}

  lemma DecodeCannotCrossLifecycle(prior: DecodeProof, selected: DecodeProof)
    requires prior.source != selected.source
    ensures !AcceptDecode(prior, selected)
  {}

  lemma UnknownWriterNeedsExactOrContent(prior: DecodeProof, selected: DecodeProof)
    requires !selected.writerCertified && prior.basis != selected.basis
    requires AcceptDecode(prior, selected)
    ensures prior.content != [] && prior.content == selected.content
  {}

  lemma ManagedDecodeNeedsVersionAndRelation(prior: DecodeProof, selected: DecodeProof)
    requires prior.basis != selected.basis && (prior.content == [] || prior.content != selected.content)
    requires AcceptDecode(prior, selected)
    ensures prior.writerCertified && selected.writerCertified && prior.version != 0
    ensures prior.version == selected.version && prior.relation == selected.relation
  {}
}
