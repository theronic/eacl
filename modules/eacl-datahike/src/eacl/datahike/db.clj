(ns eacl.datahike.db
  "Datahike adapter primitives: the handful of places where datahike spells
   something differently from DataScript. Nothing above this namespace mentions
   datoms, seek bounds, or how an attribute is represented in a datom.

   This namespace is JVM-only, as is the rest of the module: datahike's
   ClojureScript API is asynchronous, and the backend SPI is synchronous."
  (:require [datahike.api :as d]))

(defn entid
  "`entid` as DataScript and Datomic define it: a number passes through
   unchanged (an unallocated id is deliberately NOT rejected), an ident or
   lookup ref resolves to its eid, and an unresolvable one is nil.

   Datahike has no public `entid`, but `d/entity` returns nil rather than
   throwing for an unresolvable lookup ref, which is exactly the contract eacl
   reads. Anything else throws, as in DataScript — a silently-nil id means a
   permission check that denies for the wrong reason."
  [db eid-or-ref]
  (cond
    (nil? eid-or-ref) nil
    (number? eid-or-ref) eid-or-ref
    (or (keyword? eid-or-ref) (sequential? eid-or-ref)) (:db/id (d/entity db eid-or-ref))
    :else (throw (ex-info (str "Expected a number, ident or lookup ref, got " (pr-str eid-or-ref) ".")
                          {:type :eacl/invalid-entity-id
                           :value eid-or-ref}))))

(defn attr-ident
  "A datom's `:a` as an ident. Datahike reports `:a` as the attribute keyword by
   default and as a numeric ref under `:attribute-refs? true` (Datomic's
   representation), so code that compares `:a` against a keyword set matches
   nothing in the second mode without this."
  [db a]
  (if (number? a) (:db/ident (d/entity db a)) a))

(defn attribute-refs?
  "Whether this db represents attributes as numeric refs (Datomic's
   representation) rather than as keywords. A creation-time choice, so it is
   constant for the life of the database."
  [db]
  (boolean (:attribute-refs? (:config db))))

(defn attr-repr
  "`attr` in the representation this db uses for it: the keyword itself, or its
   numeric ref under `:attribute-refs?`. Resolved once per operation rather than
   per datom — an entity lookup per datom would dominate an index scan."
  [db attr]
  (if (attribute-refs? db)
    (entid db attr)
    attr))

(defn seek-tuple-prefix
  "Datoms of composite-tuple attribute `attr` (of `arity` components) whose
   value begins with `prefix`.

   Restrict the scan to the exact AVET attribute first, then filter its tuple
   values. Datahike 0.8.1759's `AsOfDB` delegates `seek-datoms` without applying
   the temporal wrapper's seek context: a padded composite lower bound can skip
   the wanted historical tuple and start in the following attribute. Exact
   `datoms` does apply that context. Relation-definition cardinality is bounded
   by the authorization schema, so this preserves correctness on temporal
   snapshots without widening the scan to the database."
  [db attr arity prefix]
  (let [prefix (vec prefix)
        n      (count prefix)]
    (->> (d/datoms db {:index :avet :components [attr]})
         (filter (fn [{:keys [v]}]
                   (and (vector? v)
                        (= arity (count v))
                        (<= n arity)
                        (= prefix (subvec v 0 n))))))))

(defn avet-datoms
  "Datoms of `attr`, optionally restricted to an exact value."
  ([db attr] (d/datoms db {:index :avet :components [attr]}))
  ([db attr v] (d/datoms db {:index :avet :components [attr v]})))

(defn avet-range
  "Datoms of `attr` whose value falls in [`start`, `end`]. Datahike's
   `index-range` takes a map where DataScript takes positional arguments; tuple
   values are valid bounds here, unlike a partial tuple in a seek.

   `:attrid` is the one place datahike does NOT accept the attribute keyword in
   both modes: under `:attribute-refs?` it demands the numeric ref and raises
   otherwise. It raises rather than denying, which is why this surfaced as an
   error instead of a wrong answer."
  [db attr start end]
  (d/index-range db {:attrid (attr-repr db attr) :start start :end end}))

(defn entity-exists?
  "Whether `eid` has any datom. `entid` passes unallocated numeric ids through
   unchanged, so presence has to be checked separately."
  [db eid]
  (boolean (seq (d/datoms db {:index :eavt :components [eid]}))))
