// Proof-only semantics of SpiceDB wildcard subjects (`user:*`) in EACL.
//
// A relationship whose subject is the wildcard W makes every concrete subject
// of its type a member of its relation on its resource. Outcomes are sets of
// worlds, the Boolean completions of a request's residual Caveat atoms, as in
// QualifiedEvidence.dfy: an absent relationship holds in no world and a
// definite one in every world of the universe.
//
// The model states the facts EACL's engine relies on:
//
// - A concrete subject without a relationship in a permission's touch cover
//   (the cover in which every intersection and exclusion becomes a union of
//   its operands) is indistinguishable from W.
// - A subject lookup that decides every touch-cover subject exactly and
//   returns W as `*`, excluding the touch-cover subjects it does not grant
//   definitely, denotes every subject's permission exactly under both result
//   policies. A subject holds the permission through its own entry or, unless
//   `*` excludes it, through `*` (SpiceDB's reading of LookupSubjects).
// - In a permission without intersection or exclusion, a subject's
//   permission is its own derivation joined with W's, so listing own
//   derivations beside `*` needs no exclusions. The definite form of that
//   listing is sound but can omit a subject that only the join of two
//   conditional derivations grants.
//
// Fuel bounds the unfolding of permission references and arrows; every
// lemma holds at every fuel. It does not prove the Clojure engine, adapter
// scans, identity conversion or the least-fixed-point limit of recursion.
module WildcardSubjects {
  datatype Subject = Concrete(id: nat) | Wildcard

  datatype Expr =
    | Rel(relation: nat)
    | Perm(permission: nat)
    | Arrow(via: nat, target: nat)
    | Union(left: Expr, right: Expr)
    | Intersection(left: Expr, right: Expr)
    | Exclusion(left: Expr, right: Expr)

  // body(p) is permission p's expression on the resource type in question;
  // allowsWildcard(x) holds when relation x declares a `T:*` branch for the
  // requested subject type T.
  datatype Schema = Schema(body: nat -> Expr, allowsWildcard: nat -> bool)

  // tuple(s, x, r): the worlds in which s holds relation x on resource r.
  // edge(via, r, t): the worlds in which resource t holds relation `via` on
  // resource r. The left side of an arrow never holds a wildcard.
  datatype Store = Store(
    resources: set<nat>,
    tuple: (Subject, nat, nat) -> set<nat>,
    edge: (nat, nat, nat) -> set<nat>
  )

  // Writes admit W only on relations that declare the wildcard branch.
  ghost predicate WellFormed(sc: Schema, st: Store) {
    forall x: nat, r: nat :: !sc.allowsWildcard(x) ==> st.tuple(Wildcard, x, r) == {}
  }

  ghost predicate WithinUniverse(st: Store, universe: set<nat>) {
    && (forall s: Subject, x: nat, r: nat :: st.tuple(s, x, r) <= universe)
    && (forall via: nat, r: nat, t: nat :: st.edge(via, r, t) <= universe)
  }

  // Membership of a concrete subject joins its own relationship with W's
  // when the relation declares the wildcard (the engine's wildcard variant of
  // a relation rule). Without variants, a subject has its own relationships
  // only: the reverse enumeration's own derivation, and always W's.
  function Member(sc: Schema, st: Store, variants: bool, s: Subject, x: nat, r: nat): set<nat> {
    st.tuple(s, x, r)
    + (if variants && s.Concrete? && sc.allowsWildcard(x) then st.tuple(Wildcard, x, r) else {})
  }

  function Eval(sc: Schema, st: Store, variants: bool, e: Expr, s: Subject, r: nat, fuel: nat): set<nat>
    decreases fuel, e
  {
    match e
    case Rel(x) => Member(sc, st, variants, s, x, r)
    case Perm(p) =>
      if fuel == 0 then {} else Eval(sc, st, variants, sc.body(p), s, r, fuel - 1)
    case Arrow(via, p) =>
      if fuel == 0 then {}
      else
        set t, w | t in st.resources && w in st.edge(via, r, t)
                   && w in Eval(sc, st, variants, sc.body(p), s, t, fuel - 1) :: w
    case Union(a, b) =>
      Eval(sc, st, variants, a, s, r, fuel) + Eval(sc, st, variants, b, s, r, fuel)
    case Intersection(a, b) =>
      Eval(sc, st, variants, a, s, r, fuel) * Eval(sc, st, variants, b, s, r, fuel)
    case Exclusion(a, b) =>
      Eval(sc, st, variants, a, s, r, fuel) - Eval(sc, st, variants, b, s, r, fuel)
  }

  // A check: the subject's permission with wildcard variants.
  function Check(sc: Schema, st: Store, e: Expr, id: nat, r: nat, fuel: nat): set<nat> {
    Eval(sc, st, true, e, Concrete(id), r, fuel)
  }

  // W's own permission, the decision of the `*` entry.
  function WildcardDecision(sc: Schema, st: Store, e: Expr, r: nat, fuel: nat): set<nat> {
    Eval(sc, st, false, e, Wildcard, r, fuel)
  }

  // s holds a relationship reachable through the touch cover of e at r.
  predicate Touched(sc: Schema, st: Store, e: Expr, s: Subject, r: nat, fuel: nat)
    decreases fuel, e
  {
    match e
    case Rel(x) => st.tuple(s, x, r) != {}
    case Perm(p) => fuel > 0 && Touched(sc, st, sc.body(p), s, r, fuel - 1)
    case Arrow(via, p) =>
      fuel > 0
      && exists t :: t in st.resources && st.edge(via, r, t) != {}
                     && Touched(sc, st, sc.body(p), s, t, fuel - 1)
    case Union(a, b) => Touched(sc, st, a, s, r, fuel) || Touched(sc, st, b, s, r, fuel)
    case Intersection(a, b) => Touched(sc, st, a, s, r, fuel) || Touched(sc, st, b, s, r, fuel)
    case Exclusion(a, b) => Touched(sc, st, a, s, r, fuel) || Touched(sc, st, b, s, r, fuel)
  }

  // Candidate subjects cover every touched concrete subject.
  ghost predicate Covers(sc: Schema, st: Store, e: Expr, r: nat, fuel: nat, candidates: set<nat>) {
    forall id: nat :: Touched(sc, st, e, Concrete(id), r, fuel) ==> id in candidates
  }

  predicate Positive(sc: Schema, e: Expr, fuel: nat)
    decreases fuel, e
  {
    match e
    case Rel(_) => true
    case Perm(p) => fuel == 0 || Positive(sc, sc.body(p), fuel - 1)
    case Arrow(_, p) => fuel == 0 || Positive(sc, sc.body(p), fuel - 1)
    case Union(a, b) => Positive(sc, a, fuel) && Positive(sc, b, fuel)
    case Intersection(_, _) => false
    case Exclusion(_, _) => false
  }

  lemma EvalWithinUniverse(sc: Schema, st: Store, universe: set<nat>, variants: bool, e: Expr, s: Subject, r: nat, fuel: nat)
    requires WithinUniverse(st, universe)
    ensures Eval(sc, st, variants, e, s, r, fuel) <= universe
    decreases fuel, e
  {
    match e
    case Rel(x) =>
      assert st.tuple(s, x, r) <= universe;
      assert st.tuple(Wildcard, x, r) <= universe;
    case Perm(p) =>
      if fuel > 0 {
        EvalWithinUniverse(sc, st, universe, variants, sc.body(p), s, r, fuel - 1);
      }
    case Arrow(via, p) =>
      if fuel > 0 {
        forall w | w in Eval(sc, st, variants, e, s, r, fuel)
          ensures w in universe
        {
          var t :| t in st.resources && w in st.edge(via, r, t)
                   && w in Eval(sc, st, variants, sc.body(p), s, t, fuel - 1);
          assert st.edge(via, r, t) <= universe;
        }
      }
    case Union(a, b) =>
      EvalWithinUniverse(sc, st, universe, variants, a, s, r, fuel);
      EvalWithinUniverse(sc, st, universe, variants, b, s, r, fuel);
    case Intersection(a, b) =>
      EvalWithinUniverse(sc, st, universe, variants, a, s, r, fuel);
    case Exclusion(a, b) =>
      EvalWithinUniverse(sc, st, universe, variants, a, s, r, fuel);
  }

  // The `*` entry's decision uses W's relationships only, with or without
  // variants: a variant applies to concrete subjects.
  lemma WildcardIgnoresVariants(sc: Schema, st: Store, e: Expr, r: nat, fuel: nat)
    ensures Eval(sc, st, true, e, Wildcard, r, fuel) == Eval(sc, st, false, e, Wildcard, r, fuel)
    decreases fuel, e
  {
    match e
    case Rel(_) =>
    case Perm(p) =>
      if fuel > 0 {
        WildcardIgnoresVariants(sc, st, sc.body(p), r, fuel - 1);
      }
    case Arrow(via, p) =>
      if fuel > 0 {
        forall t | t in st.resources
          ensures Eval(sc, st, true, sc.body(p), Wildcard, t, fuel - 1)
               == Eval(sc, st, false, sc.body(p), Wildcard, t, fuel - 1)
        {
          WildcardIgnoresVariants(sc, st, sc.body(p), t, fuel - 1);
        }
      }
    case Union(a, b) =>
      WildcardIgnoresVariants(sc, st, a, r, fuel);
      WildcardIgnoresVariants(sc, st, b, r, fuel);
    case Intersection(a, b) =>
      WildcardIgnoresVariants(sc, st, a, r, fuel);
      WildcardIgnoresVariants(sc, st, b, r, fuel);
    case Exclusion(a, b) =>
      WildcardIgnoresVariants(sc, st, a, r, fuel);
      WildcardIgnoresVariants(sc, st, b, r, fuel);
  }

  // A concrete subject outside the touch cover has exactly W's memberships,
  // so W's decision stands for it.
  lemma UntouchedSubjectIsTheWildcard(sc: Schema, st: Store, e: Expr, id: nat, r: nat, fuel: nat)
    requires WellFormed(sc, st)
    requires !Touched(sc, st, e, Concrete(id), r, fuel)
    ensures Eval(sc, st, true, e, Concrete(id), r, fuel) == Eval(sc, st, true, e, Wildcard, r, fuel)
    decreases fuel, e
  {
    match e
    case Rel(x) =>
      if !sc.allowsWildcard(x) {
        assert st.tuple(Wildcard, x, r) == {};
      }
    case Perm(p) =>
      if fuel > 0 {
        UntouchedSubjectIsTheWildcard(sc, st, sc.body(p), id, r, fuel - 1);
      }
    case Arrow(via, p) =>
      if fuel > 0 {
        forall t | t in st.resources && st.edge(via, r, t) != {}
          ensures Eval(sc, st, true, sc.body(p), Concrete(id), t, fuel - 1)
               == Eval(sc, st, true, sc.body(p), Wildcard, t, fuel - 1)
        {
          UntouchedSubjectIsTheWildcard(sc, st, sc.body(p), id, t, fuel - 1);
        }
        var own := Eval(sc, st, true, e, Concrete(id), r, fuel);
        var wild := Eval(sc, st, true, e, Wildcard, r, fuel);
        forall w | w in own
          ensures w in wild
        {
          var t :| t in st.resources && w in st.edge(via, r, t)
                   && w in Eval(sc, st, true, sc.body(p), Concrete(id), t, fuel - 1);
          assert st.edge(via, r, t) != {};
        }
        forall w | w in wild
          ensures w in own
        {
          var t :| t in st.resources && w in st.edge(via, r, t)
                   && w in Eval(sc, st, true, sc.body(p), Wildcard, t, fuel - 1);
          assert st.edge(via, r, t) != {};
        }
      }
    case Union(a, b) =>
      UntouchedSubjectIsTheWildcard(sc, st, a, id, r, fuel);
      UntouchedSubjectIsTheWildcard(sc, st, b, id, r, fuel);
    case Intersection(a, b) =>
      UntouchedSubjectIsTheWildcard(sc, st, a, id, r, fuel);
      UntouchedSubjectIsTheWildcard(sc, st, b, id, r, fuel);
    case Exclusion(a, b) =>
      UntouchedSubjectIsTheWildcard(sc, st, a, id, r, fuel);
      UntouchedSubjectIsTheWildcard(sc, st, b, id, r, fuel);
  }

  // Without a relationship of its own, a subject derives nothing by itself.
  lemma OwnDerivationWithoutRelationshipsIsEmpty(sc: Schema, st: Store, e: Expr, id: nat, r: nat, fuel: nat)
    requires !Touched(sc, st, e, Concrete(id), r, fuel)
    ensures Eval(sc, st, false, e, Concrete(id), r, fuel) == {}
    decreases fuel, e
  {
    match e
    case Rel(_) =>
    case Perm(p) =>
      if fuel > 0 {
        OwnDerivationWithoutRelationshipsIsEmpty(sc, st, sc.body(p), id, r, fuel - 1);
      }
    case Arrow(via, p) =>
      if fuel > 0 {
        forall w | w in Eval(sc, st, false, e, Concrete(id), r, fuel)
          ensures false
        {
          var t :| t in st.resources && w in st.edge(via, r, t)
                   && w in Eval(sc, st, false, sc.body(p), Concrete(id), t, fuel - 1);
          assert st.edge(via, r, t) != {};
          OwnDerivationWithoutRelationshipsIsEmpty(sc, st, sc.body(p), id, t, fuel - 1);
        }
      }
    case Union(a, b) =>
      OwnDerivationWithoutRelationshipsIsEmpty(sc, st, a, id, r, fuel);
      OwnDerivationWithoutRelationshipsIsEmpty(sc, st, b, id, r, fuel);
    case Intersection(a, b) =>
      OwnDerivationWithoutRelationshipsIsEmpty(sc, st, a, id, r, fuel);
    case Exclusion(a, b) =>
      OwnDerivationWithoutRelationshipsIsEmpty(sc, st, a, id, r, fuel);
  }

  // Without intersection or exclusion, a subject's permission is its own
  // derivation joined with W's.
  lemma PositivePermissionSplits(sc: Schema, st: Store, e: Expr, id: nat, r: nat, fuel: nat)
    requires WellFormed(sc, st)
    requires Positive(sc, e, fuel)
    ensures Eval(sc, st, true, e, Concrete(id), r, fuel)
         == Eval(sc, st, false, e, Concrete(id), r, fuel) + Eval(sc, st, false, e, Wildcard, r, fuel)
    decreases fuel, e
  {
    match e
    case Rel(x) =>
      if !sc.allowsWildcard(x) {
        assert st.tuple(Wildcard, x, r) == {};
      }
    case Perm(p) =>
      if fuel > 0 {
        PositivePermissionSplits(sc, st, sc.body(p), id, r, fuel - 1);
      }
    case Arrow(via, p) =>
      if fuel > 0 {
        forall t | t in st.resources
          ensures Eval(sc, st, true, sc.body(p), Concrete(id), t, fuel - 1)
               == Eval(sc, st, false, sc.body(p), Concrete(id), t, fuel - 1)
                  + Eval(sc, st, false, sc.body(p), Wildcard, t, fuel - 1)
        {
          PositivePermissionSplits(sc, st, sc.body(p), id, t, fuel - 1);
        }
        var joined := Eval(sc, st, true, e, Concrete(id), r, fuel);
        var own := Eval(sc, st, false, e, Concrete(id), r, fuel);
        var wild := Eval(sc, st, false, e, Wildcard, r, fuel);
        forall w | w in joined
          ensures w in own + wild
        {
          var t :| t in st.resources && w in st.edge(via, r, t)
                   && w in Eval(sc, st, true, sc.body(p), Concrete(id), t, fuel - 1);
          if w in Eval(sc, st, false, sc.body(p), Concrete(id), t, fuel - 1) {
            assert w in own;
          } else {
            assert w in wild;
          }
        }
        forall w | w in own + wild
          ensures w in joined
        {
          if w in own {
            var t :| t in st.resources && w in st.edge(via, r, t)
                     && w in Eval(sc, st, false, sc.body(p), Concrete(id), t, fuel - 1);
            assert w in Eval(sc, st, true, sc.body(p), Concrete(id), t, fuel - 1);
          } else {
            var t :| t in st.resources && w in st.edge(via, r, t)
                     && w in Eval(sc, st, false, sc.body(p), Wildcard, t, fuel - 1);
            assert w in Eval(sc, st, true, sc.body(p), Concrete(id), t, fuel - 1);
          }
        }
      }
    case Union(a, b) =>
      PositivePermissionSplits(sc, st, a, id, r, fuel);
      PositivePermissionSplits(sc, st, b, id, r, fuel);
  }

  // A subject lookup for one resource: each listed concrete subject with its
  // entry's worlds, and the `*` entry's worlds (empty when it is absent) with
  // the subjects it excludes.
  datatype Listing = Listing(listed: map<nat, set<nat>>, star: set<nat>, excluded: set<nat>)

  // A subject holds the permission through its own entry or, unless `*`
  // excludes it, through `*`.
  function Denote(l: Listing, id: nat): set<nat> {
    (if id in l.listed then l.listed[id] else {}) + (if id in l.excluded then {} else l.star)
  }

  // The `:detailed` touch-cover lookup: every candidate decided exactly is
  // listed unless denied, W is `*`, and `*` excludes every candidate that is
  // not definitely granted.
  function DetailedTouchListing(
    sc: Schema, st: Store, universe: set<nat>, e: Expr, r: nat, fuel: nat, candidates: set<nat>
  ): Listing {
    Listing(
      map id | id in candidates && Check(sc, st, e, id, r, fuel) != {} :: Check(sc, st, e, id, r, fuel),
      WildcardDecision(sc, st, e, r, fuel),
      set id | id in candidates && Check(sc, st, e, id, r, fuel) != universe)
  }

  // The definite (default) touch-cover lookup lists definite grants only.
  function DefiniteTouchListing(
    sc: Schema, st: Store, universe: set<nat>, e: Expr, r: nat, fuel: nat, candidates: set<nat>
  ): Listing {
    Listing(
      map id | id in candidates && Check(sc, st, e, id, r, fuel) == universe :: universe,
      if WildcardDecision(sc, st, e, r, fuel) == universe then universe else {},
      set id | id in candidates && Check(sc, st, e, id, r, fuel) != universe)
  }

  // The reverse enumeration of a permission without intersection or
  // exclusion: each subject's own derivation, and W as `*`.
  function UnionListing(sc: Schema, st: Store, e: Expr, r: nat, fuel: nat, candidates: set<nat>): Listing {
    Listing(
      map id | id in candidates && Eval(sc, st, false, e, Concrete(id), r, fuel) != {}
        :: Eval(sc, st, false, e, Concrete(id), r, fuel),
      WildcardDecision(sc, st, e, r, fuel),
      {})
  }

  function DefiniteUnionListing(
    sc: Schema, st: Store, universe: set<nat>, e: Expr, r: nat, fuel: nat, candidates: set<nat>
  ): Listing {
    Listing(
      map id | id in candidates && Eval(sc, st, false, e, Concrete(id), r, fuel) == universe :: universe,
      if WildcardDecision(sc, st, e, r, fuel) == universe then universe else {},
      {})
  }

  lemma DetailedTouchListingDenotesExactly(
    sc: Schema, st: Store, universe: set<nat>, e: Expr, r: nat, fuel: nat, candidates: set<nat>, id: nat
  )
    requires WellFormed(sc, st) && WithinUniverse(st, universe)
    requires Covers(sc, st, e, r, fuel, candidates)
    ensures Denote(DetailedTouchListing(sc, st, universe, e, r, fuel, candidates), id)
         == Check(sc, st, e, id, r, fuel)
  {
    var decision := Check(sc, st, e, id, r, fuel);
    var star := WildcardDecision(sc, st, e, r, fuel);
    EvalWithinUniverse(sc, st, universe, false, e, Wildcard, r, fuel);
    WildcardIgnoresVariants(sc, st, e, r, fuel);
    if id !in candidates {
      UntouchedSubjectIsTheWildcard(sc, st, e, id, r, fuel);
    }
    var l := DetailedTouchListing(sc, st, universe, e, r, fuel, candidates);
    if id in candidates && decision == universe {
      assert id !in l.excluded;
    }
  }

  lemma DefiniteTouchListingDenotesExactly(
    sc: Schema, st: Store, universe: set<nat>, e: Expr, r: nat, fuel: nat, candidates: set<nat>, id: nat
  )
    requires WellFormed(sc, st) && universe != {}
    requires Covers(sc, st, e, r, fuel, candidates)
    ensures var granted := Denote(DefiniteTouchListing(sc, st, universe, e, r, fuel, candidates), id);
            (granted == universe || granted == {})
            && (granted == universe <==> Check(sc, st, e, id, r, fuel) == universe)
  {
    WildcardIgnoresVariants(sc, st, e, r, fuel);
    if id !in candidates {
      UntouchedSubjectIsTheWildcard(sc, st, e, id, r, fuel);
    }
  }

  lemma UnionListingDenotesExactly(
    sc: Schema, st: Store, e: Expr, r: nat, fuel: nat, candidates: set<nat>, id: nat
  )
    requires WellFormed(sc, st) && Positive(sc, e, fuel)
    requires Covers(sc, st, e, r, fuel, candidates)
    ensures Denote(UnionListing(sc, st, e, r, fuel, candidates), id) == Check(sc, st, e, id, r, fuel)
  {
    PositivePermissionSplits(sc, st, e, id, r, fuel);
    if id !in candidates {
      OwnDerivationWithoutRelationshipsIsEmpty(sc, st, e, id, r, fuel);
    }
  }

  lemma DefiniteUnionListingIsSound(
    sc: Schema, st: Store, universe: set<nat>, e: Expr, r: nat, fuel: nat, candidates: set<nat>, id: nat
  )
    requires WellFormed(sc, st) && WithinUniverse(st, universe) && Positive(sc, e, fuel)
    requires Denote(DefiniteUnionListing(sc, st, universe, e, r, fuel, candidates), id) == universe
    requires universe != {}
    ensures Check(sc, st, e, id, r, fuel) == universe
  {
    PositivePermissionSplits(sc, st, e, id, r, fuel);
    EvalWithinUniverse(sc, st, universe, true, e, Concrete(id), r, fuel);
  }

  // The definite union listing can omit a subject granted only by the join
  // of its own conditional relationship and a conditional wildcard: EACL's
  // `:detailed` lookups return both conditional entries, as SpiceDB does.
  lemma ComplementaryConditionalsWitness()
    ensures var sc := Schema(p => Rel(0), x => true);
            var st := Store({0}, (s: Subject, x: nat, r: nat) =>
                              if s == Concrete(0) then {0} else if s == Wildcard then {1} else {},
                            (via: nat, r: nat, t: nat) => {});
            && Check(sc, st, Rel(0), 0, 0, 0) == {0, 1}
            && Denote(DefiniteUnionListing(sc, st, {0, 1}, Rel(0), 0, 0, {0}), 0) == {}
            && Denote(UnionListing(sc, st, Rel(0), 0, 0, {0}), 0) == {0, 1}
  {
    var sc := Schema(p => Rel(0), x => true);
    var st := Store({0}, (s: Subject, x: nat, r: nat) =>
                      if s == Concrete(0) then {0} else if s == Wildcard then {1} else {},
                    (via: nat, r: nat, t: nat) => {});
    assert Eval(sc, st, false, Rel(0), Concrete(0), 0, 0) == {0};
    assert WildcardDecision(sc, st, Rel(0), 0, 0) == {1};
    assert {0} != {0, 1} by {
      assert 1 !in {0};
    }
    assert {1} != {0, 1} by {
      assert 0 !in {1};
    }
  }
  // ---------------------------------------------------------------------
  // Covers without a wildcard
  // ---------------------------------------------------------------------

  // The cover of e generates its lookup candidates: every operand of a
  // union, the left operand of an exclusion and one operand of an
  // intersection, its anchor. WildcardCover(e) holds when a relation of that
  // cover declares the wildcard branch. The anchor is an operand without a
  // wildcard cover whenever one exists, so an intersection has a wildcard
  // cover only when both operands do.
  predicate WildcardCover(sc: Schema, e: Expr, fuel: nat)
    decreases fuel, e
  {
    match e
    case Rel(x) => sc.allowsWildcard(x)
    case Perm(p) => fuel > 0 && WildcardCover(sc, sc.body(p), fuel - 1)
    case Arrow(_, p) => fuel > 0 && WildcardCover(sc, sc.body(p), fuel - 1)
    case Union(a, b) => WildcardCover(sc, a, fuel) || WildcardCover(sc, b, fuel)
    case Intersection(a, b) => WildcardCover(sc, a, fuel) && WildcardCover(sc, b, fuel)
    case Exclusion(a, _) => WildcardCover(sc, a, fuel)
  }

  // Whether an intersection's anchor is its left operand. When exactly one
  // operand has no wildcard cover it is the anchor; otherwise `tie` decides,
  // standing for any order of the operands that does not read relationships.
  predicate AnchorIsLeft(sc: Schema, tie: (Expr, Expr) -> bool, a: Expr, b: Expr, fuel: nat) {
    if WildcardCover(sc, a, fuel) != WildcardCover(sc, b, fuel)
    then WildcardCover(sc, b, fuel)
    else tie(a, b)
  }

  // s holds a relationship of its own in the cover of e at r.
  predicate Anchored(
    sc: Schema, st: Store, tie: (Expr, Expr) -> bool, e: Expr, s: Subject, r: nat, fuel: nat
  )
    decreases fuel, e
  {
    match e
    case Rel(x) => st.tuple(s, x, r) != {}
    case Perm(p) => fuel > 0 && Anchored(sc, st, tie, sc.body(p), s, r, fuel - 1)
    case Arrow(via, p) =>
      fuel > 0
      && exists t :: t in st.resources && st.edge(via, r, t) != {}
                     && Anchored(sc, st, tie, sc.body(p), s, t, fuel - 1)
    case Union(a, b) => Anchored(sc, st, tie, a, s, r, fuel) || Anchored(sc, st, tie, b, s, r, fuel)
    case Intersection(a, b) =>
      if AnchorIsLeft(sc, tie, a, b, fuel)
      then Anchored(sc, st, tie, a, s, r, fuel)
      else Anchored(sc, st, tie, b, s, r, fuel)
    case Exclusion(a, _) => Anchored(sc, st, tie, a, s, r, fuel)
  }

  // Candidate subjects contain every concrete subject of the cover.
  ghost predicate AnchorCovers(
    sc: Schema, st: Store, tie: (Expr, Expr) -> bool, e: Expr, r: nat, fuel: nat, candidates: set<nat>
  ) {
    forall id: nat :: Anchored(sc, st, tie, e, Concrete(id), r, fuel) ==> id in candidates
  }

  // W holds nothing through a cover without a wildcard, so its lookup owes
  // no `*` entry.
  lemma CoverWithoutWildcardDeniesTheWildcard(
    sc: Schema, st: Store, variants: bool, e: Expr, r: nat, fuel: nat
  )
    requires WellFormed(sc, st)
    requires !WildcardCover(sc, e, fuel)
    ensures Eval(sc, st, variants, e, Wildcard, r, fuel) == {}
    decreases fuel, e
  {
    match e
    case Rel(x) =>
      assert st.tuple(Wildcard, x, r) == {};
    case Perm(p) =>
      if fuel > 0 {
        CoverWithoutWildcardDeniesTheWildcard(sc, st, variants, sc.body(p), r, fuel - 1);
      }
    case Arrow(via, p) =>
      if fuel > 0 {
        forall w | w in Eval(sc, st, variants, e, Wildcard, r, fuel)
          ensures false
        {
          var t :| t in st.resources && w in st.edge(via, r, t)
                   && w in Eval(sc, st, variants, sc.body(p), Wildcard, t, fuel - 1);
          CoverWithoutWildcardDeniesTheWildcard(sc, st, variants, sc.body(p), t, fuel - 1);
        }
      }
    case Union(a, b) =>
      CoverWithoutWildcardDeniesTheWildcard(sc, st, variants, a, r, fuel);
      CoverWithoutWildcardDeniesTheWildcard(sc, st, variants, b, r, fuel);
    case Intersection(a, b) =>
      if !WildcardCover(sc, a, fuel) {
        CoverWithoutWildcardDeniesTheWildcard(sc, st, variants, a, r, fuel);
      } else {
        CoverWithoutWildcardDeniesTheWildcard(sc, st, variants, b, r, fuel);
      }
    case Exclusion(a, _) =>
      CoverWithoutWildcardDeniesTheWildcard(sc, st, variants, a, r, fuel);
  }

  // A subject that holds a permission whose cover has no wildcard holds a
  // relationship of its own in that cover: the wildcard may grant it the
  // other operands, never the anchor.
  lemma GrantedSubjectIsAnchored(
    sc: Schema, st: Store, tie: (Expr, Expr) -> bool, e: Expr, id: nat, r: nat, fuel: nat
  )
    requires WellFormed(sc, st)
    requires !WildcardCover(sc, e, fuel)
    requires Check(sc, st, e, id, r, fuel) != {}
    ensures Anchored(sc, st, tie, e, Concrete(id), r, fuel)
    decreases fuel, e
  {
    match e
    case Rel(x) =>
    case Perm(p) =>
      GrantedSubjectIsAnchored(sc, st, tie, sc.body(p), id, r, fuel - 1);
    case Arrow(via, p) =>
      var w :| w in Check(sc, st, e, id, r, fuel);
      var t :| t in st.resources && w in st.edge(via, r, t)
               && w in Eval(sc, st, true, sc.body(p), Concrete(id), t, fuel - 1);
      assert st.edge(via, r, t) != {};
      GrantedSubjectIsAnchored(sc, st, tie, sc.body(p), id, t, fuel - 1);
    case Union(a, b) =>
      if Eval(sc, st, true, a, Concrete(id), r, fuel) != {} {
        GrantedSubjectIsAnchored(sc, st, tie, a, id, r, fuel);
      } else {
        GrantedSubjectIsAnchored(sc, st, tie, b, id, r, fuel);
      }
    case Intersection(a, b) =>
      if AnchorIsLeft(sc, tie, a, b, fuel) {
        GrantedSubjectIsAnchored(sc, st, tie, a, id, r, fuel);
      } else {
        GrantedSubjectIsAnchored(sc, st, tie, b, id, r, fuel);
      }
    case Exclusion(a, _) =>
      GrantedSubjectIsAnchored(sc, st, tie, a, id, r, fuel);
  }

  // The lookup of a permission whose cover has no wildcard: every candidate
  // of the cover decided exactly, with no `*` entry and no exclusions.
  function AnchorListing(sc: Schema, st: Store, e: Expr, r: nat, fuel: nat, candidates: set<nat>): Listing {
    Listing(
      map id | id in candidates && Check(sc, st, e, id, r, fuel) != {} :: Check(sc, st, e, id, r, fuel),
      {},
      {})
  }

  function DefiniteAnchorListing(
    sc: Schema, st: Store, universe: set<nat>, e: Expr, r: nat, fuel: nat, candidates: set<nat>
  ): Listing {
    Listing(
      map id | id in candidates && Check(sc, st, e, id, r, fuel) == universe :: universe,
      {},
      {})
  }

  lemma AnchorListingDenotesExactly(
    sc: Schema, st: Store, tie: (Expr, Expr) -> bool, e: Expr, r: nat, fuel: nat,
    candidates: set<nat>, id: nat
  )
    requires WellFormed(sc, st)
    requires !WildcardCover(sc, e, fuel)
    requires AnchorCovers(sc, st, tie, e, r, fuel, candidates)
    ensures WildcardDecision(sc, st, e, r, fuel) == {}
    ensures Denote(AnchorListing(sc, st, e, r, fuel, candidates), id) == Check(sc, st, e, id, r, fuel)
  {
    CoverWithoutWildcardDeniesTheWildcard(sc, st, false, e, r, fuel);
    if id !in candidates && Check(sc, st, e, id, r, fuel) != {} {
      GrantedSubjectIsAnchored(sc, st, tie, e, id, r, fuel);
    }
  }

  lemma DefiniteAnchorListingDenotesExactly(
    sc: Schema, st: Store, tie: (Expr, Expr) -> bool, universe: set<nat>, e: Expr, r: nat, fuel: nat,
    candidates: set<nat>, id: nat
  )
    requires WellFormed(sc, st) && universe != {}
    requires !WildcardCover(sc, e, fuel)
    requires AnchorCovers(sc, st, tie, e, r, fuel, candidates)
    ensures var granted := Denote(DefiniteAnchorListing(sc, st, universe, e, r, fuel, candidates), id);
            (granted == universe || granted == {})
            && (granted == universe <==> Check(sc, st, e, id, r, fuel) == universe)
  {
    if id !in candidates && Check(sc, st, e, id, r, fuel) != {} {
      GrantedSubjectIsAnchored(sc, st, tie, e, id, r, fuel);
    }
  }

  // An anchor with a wildcard cover does not list its permission's subjects:
  // with `viewer & editor`, `viewer: *` and an editor without a viewer
  // relationship, the editor holds the permission but no relationship in
  // `viewer`. Such a lookup takes the touch cover instead.
  lemma WildcardAnchorOmitsAGrantedSubject()
    ensures var sc := Schema(p => Rel(0), x => x == 0);
            var st := Store({0}, (s: Subject, x: nat, r: nat) =>
                              if s == Wildcard && x == 0 then {0}
                              else if s == Concrete(7) && x == 1 then {0}
                              else {},
                            (via: nat, r: nat, t: nat) => {});
            var e := Intersection(Rel(0), Rel(1));
            && WellFormed(sc, st)
            && Check(sc, st, e, 7, 0, 0) == {0}
            && !Anchored(sc, st, (a, b) => true, Rel(0), Concrete(7), 0, 0)
            && Anchored(sc, st, (a, b) => true, e, Concrete(7), 0, 0)
  {
    var sc := Schema(p => Rel(0), x => x == 0);
    var st := Store({0}, (s: Subject, x: nat, r: nat) =>
                      if s == Wildcard && x == 0 then {0}
                      else if s == Concrete(7) && x == 1 then {0}
                      else {},
                    (via: nat, r: nat, t: nat) => {});
    assert Eval(sc, st, true, Rel(0), Concrete(7), 0, 0) == {0};
    assert Eval(sc, st, true, Rel(1), Concrete(7), 0, 0) == {0};
    assert WildcardCover(sc, Rel(0), 0);
    assert !WildcardCover(sc, Rel(1), 0);
  }
}
