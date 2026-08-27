module SpeculativeCacheCoherence {
  type Component = nat
  type Stamp = nat

  datatype SnapshotKind = Ordinary | Speculative

  datatype CursorProvenance =
    CommittedCursor |
    SpeculativeCursor(id: nat)

  datatype EffectCertificate = EffectCertificate(
    complete: bool,
    relationships: set<Component>,
    schema: set<Component>,
    other: set<Component>
  )

  datatype DependencyWitness = DependencyWitness(
    complete: bool,
    relationships: set<Component>,
    schema: set<Component>,
    other: set<Component>
  )

  datatype CommittedRoot = CommittedRoot(
    lifecycle: nat,
    revision: nat,
    schemaStamp: Stamp,
    relationshipStamp: Stamp
  )

  datatype ManagedEntry = ManagedEntry(
    committed: bool,
    root: CommittedRoot,
    dependencies: DependencyWitness
  )

  function UnionEffects(
    parent: EffectCertificate,
    child: EffectCertificate
  ): EffectCertificate {
    EffectCertificate(
      parent.complete && child.complete,
      parent.relationships + child.relationships,
      parent.schema + child.schema,
      parent.other + child.other
    )
  }

  predicate DisjointFromEffects(
    dependencies: DependencyWitness,
    effects: EffectCertificate
  ) {
    dependencies.complete &&
    effects.complete &&
    dependencies.relationships !! effects.relationships &&
    dependencies.schema !! effects.schema &&
    dependencies.other !! effects.other
  }

  predicate OrdinaryValidAtRoot(
    entry: ManagedEntry,
    root: CommittedRoot
  ) {
    entry.committed &&
    entry.root == root &&
    entry.dependencies.complete
  }

  predicate MayReuseManaged(
    kind: SnapshotKind,
    entry: ManagedEntry,
    root: CommittedRoot,
    effects: EffectCertificate
  ) {
    OrdinaryValidAtRoot(entry, root) &&
    (kind.Ordinary? || DisjointFromEffects(entry.dependencies, effects))
  }

  predicate MayReadExact(kind: SnapshotKind, completeBasisIdentity: bool) {
    kind.Ordinary? && completeBasisIdentity
  }

  predicate MayPublish(kind: SnapshotKind) {
    kind.Ordinary?
  }

  predicate MayResumeCursor(
    issuedBy: CursorProvenance,
    selected: CursorProvenance
  ) {
    issuedBy == selected
  }

  lemma SpeculativeNeverReadsExact(completeBasisIdentity: bool)
    ensures !MayReadExact(Speculative, completeBasisIdentity)
  {
  }

  lemma SpeculativeNeverPublishes()
    ensures !MayPublish(Speculative)
  {
  }

  lemma UnknownEffectsDisableManagedReuse(
    entry: ManagedEntry,
    root: CommittedRoot,
    relationships: set<Component>,
    schema: set<Component>,
    other: set<Component>
  )
    ensures !MayReuseManaged(
              Speculative,
              entry,
              root,
              EffectCertificate(false, relationships, schema, other)
            )
  {
  }

  lemma RelationshipOverlapDisablesManagedReuse(
    entry: ManagedEntry,
    root: CommittedRoot,
    effects: EffectCertificate,
    component: Component
  )
    requires component in entry.dependencies.relationships
    requires component in effects.relationships
    ensures !MayReuseManaged(Speculative, entry, root, effects)
  {
  }

  lemma SchemaOverlapDisablesManagedReuse(
    entry: ManagedEntry,
    root: CommittedRoot,
    effects: EffectCertificate,
    component: Component
  )
    requires component in entry.dependencies.schema
    requires component in effects.schema
    ensures !MayReuseManaged(Speculative, entry, root, effects)
  {
  }

  lemma OtherOverlapDisablesManagedReuse(
    entry: ManagedEntry,
    root: CommittedRoot,
    effects: EffectCertificate,
    component: Component
  )
    requires component in entry.dependencies.other
    requires component in effects.other
    ensures !MayReuseManaged(Speculative, entry, root, effects)
  {
  }

  lemma DisjointCompleteCommittedEntryMayBeReused(
    entry: ManagedEntry,
    root: CommittedRoot,
    effects: EffectCertificate
  )
    requires OrdinaryValidAtRoot(entry, root)
    requires DisjointFromEffects(entry.dependencies, effects)
    ensures MayReuseManaged(Speculative, entry, root, effects)
  {
  }

  lemma EffectsOnlyAccumulate(
    parent: EffectCertificate,
    child: EffectCertificate
  )
    ensures parent.relationships <= UnionEffects(parent, child).relationships
    ensures parent.schema <= UnionEffects(parent, child).schema
    ensures parent.other <= UnionEffects(parent, child).other
    ensures !parent.complete ==> !UnionEffects(parent, child).complete
  {
  }

  lemma SameNativeBasisCannotAuthorizeExactReuse(
    speculativeBasisEqualsCommittedBasis: bool
  )
    ensures !MayReadExact(Speculative, speculativeBasisEqualsCommittedBasis)
  {
  }

  lemma SpeculativeCursorCannotResumeOnCommitted(
    speculativeId: nat
  )
    ensures !MayResumeCursor(
              SpeculativeCursor(speculativeId),
              CommittedCursor
            )
  {
  }

  lemma SpeculativeCursorCannotResumeOnSibling(
    speculativeId: nat,
    siblingId: nat
  )
    requires speculativeId != siblingId
    ensures !MayResumeCursor(
              SpeculativeCursor(speculativeId),
              SpeculativeCursor(siblingId)
            )
  {
  }
}
