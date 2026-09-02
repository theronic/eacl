(ns eacl.datahike.db
  "Datahike adapter primitives: the handful of places where datahike spells
   something differently from DataScript. Nothing above this namespace mentions
   datoms, seek bounds, or how an attribute is represented in a datom.

   This namespace is JVM-only, as is the rest of the module: datahike's
   ClojureScript API is asynchronous, and the backend SPI is synchronous."
  (:require [datahike.api :as d]
            [datahike.db.interface :as dbi]))

(defn db-config
  "The configuration of `db` through Datahike's database protocol, so
   temporal and filter wrappers (`AsOfDB` carries only its origin and time
   point as fields) report their origin's store, writer, history and
   attribute representation instead of nil."
  [db]
  (dbi/-config db))

(defn direct-writer?
  "True when transactions against `db`'s connection run in this process
   (Datahike's default `{:writer {:backend :self}}`). Only a direct writer
   can execute transaction functions: a remote writer receives serialized
   tx-data, which cannot carry a function value."
  [db]
  (= :self (get-in (db-config db) [:writer :backend])))

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
                          {:type :eacl/invalid-entity-id :eacl/error :eacl/invalid-entity-id
                           :value eid-or-ref}))))

(defn attribute-refs?
  "Whether this db represents attributes as numeric refs (Datomic's
   representation) rather than as keywords. A creation-time choice, so it is
   constant for the life of the database. Use Datahike's database protocol
   rather than record-field lookup so temporal wrappers delegate to their
   origin database."
  [db]
  (boolean (:attribute-refs? (dbi/-config db))))

(defn attr-repr
  "`attr` in the representation this db uses for it: the keyword itself, or its
   numeric ref under `:attribute-refs?`. Resolved once per operation rather than
   per datom — an entity lookup per datom would dominate an index scan."
  [db attr]
  (if (attribute-refs? db)
    (entid db attr)
    attr))

(defn direct-db?
  "Whether `db` is a concrete Datahike DB rather than a temporal/filter wrapper.
   Concrete and retained-commit DB records carry their config as a field.
   Wrappers delegate `IDB/-config` but deliberately do not expose that field.

   Public because physical kernel selection is basis-dependent: only a direct
   DB can honor a seek bound, so density-bounded batch kernels must not be
   selected for temporal or filter wrappers."
  [db]
  (some? (:config db)))

(defn- tuple-prefix-matcher
  "A per-scan predicate: does a datom's `arity`-tuple value start with
   `prefix`? Component-wise, so it allocates nothing per datom."
  [arity prefix]
  (let [n (count prefix)]
    (fn [{:keys [v]}]
      (and (vector? v)
           (= arity (count v))
           (loop [i 0]
             (or (== i n)
                 (and (= (nth prefix i) (nth v i))
                      (recur (inc i)))))))))

(defn seek-tuple-prefix
  "Datoms of composite-tuple attribute `attr` (of `arity` components) whose
   value begins with `prefix`.

   Datahike orders vectors by length before contents, so pad the seek key to the
   tuple's full arity. The attribute guard is load-bearing: `seek-datoms`
   continues into the next attribute when the requested prefix has no match.

   Datahike carries an `AsOfDB` temporal context into `seek-datoms`, but
   0.8.1759 can position a full-tuple temporal seek after a historically visible
   tuple that was retracted later. Wrapper values therefore use exact AVET
   datoms plus a schema-bounded prefix filter."
  [db attr arity prefix]
  (let [prefix (vec prefix)
        n      (count prefix)]
    (if (> n arity)
      []
      (let [matches-prefix? (tuple-prefix-matcher arity prefix)]
        (if (direct-db? db)
          (let [padded (into prefix (repeat (- arity n) nil))
                a-repr (attr-repr db attr)]
            (->> (d/seek-datoms
                  db
                  {:index :avet
                   :components [attr padded]})
                 (take-while
                  (fn [{:keys [a] :as datom}]
                    (and (= a-repr a)
                         (matches-prefix? datom))))))
          (filter matches-prefix?
                  (d/datoms db {:index :avet
                                :components [attr]})))))))

(defn avet-datoms
  "Datoms of `attr`, optionally restricted to an exact value."
  ([db attr] (d/datoms db {:index :avet :components [attr]}))
  ([db attr v] (d/datoms db {:index :avet :components [attr v]})))

(defn eavt-datoms
  "Datoms on `entity` for `attr`, optionally restricted to an exact value.
   Datahike resolves the attribute keyword in both attribute representations."
  ([db entity attr]
   (d/datoms db {:index :eavt :components [entity attr]}))
  ([db entity attr v]
   (d/datoms db {:index :eavt :components [entity attr v]})))

(defn eavt-tuple-prefix
  "Datoms on `entity` whose `arity`-tuple value starts with `prefix`.

   A full-length lower bound positions the scan at the requested tuple segment.
   The entity and attribute guards prevent a missing prefix from running into a
   different relationship attribute on the same endpoint. Current and
   retained-commit values use that seek. Temporal/filter wrappers use exact EAVT
   datoms plus an endpoint-local prefix filter because Datahike 0.8.1759 can
   skip a historically visible tuple after a later retraction."
  ([db entity attr arity prefix]
   (eavt-tuple-prefix db entity attr arity prefix nil))
  ([db entity attr arity prefix lower-tail]
   (eavt-tuple-prefix db entity attr arity prefix lower-tail :asc))
  ([db entity attr arity prefix cursor-tail direction]
   (let [prefix (vec prefix)
         prefix-size (count prefix)
         missing (- arity prefix-size)]
     (if (or (neg? missing)
             (not (#{:asc :desc} direction)))
       []
       (let [seek-tail
             (or cursor-tail
                 (when (= :desc direction) Long/MAX_VALUE))
             seek-bound
             (if (and (some? seek-tail) (pos? missing))
               (into prefix
                     (cons seek-tail
                           (repeat (dec missing)
                                   (if (= :desc direction)
                                     Long/MAX_VALUE
                                     nil))))
               (into prefix (repeat missing nil)))
             matches-prefix? (tuple-prefix-matcher arity prefix)]
         (if (direct-db? db)
           (let [a-repr (attr-repr db attr)]
             (->> ((if (= :desc direction)
                     d/rseek-datoms
                     d/seek-datoms)
                   db {:index :eavt
                       :components [entity attr seek-bound]})
                  (take-while
                   (fn [{:keys [e a] :as datom}]
                     (and (= entity e)
                         (= a-repr a)
                         (matches-prefix? datom))))))
           (cond->> (eavt-datoms db entity attr)
             true (filter matches-prefix?)
             true (sort-by (juxt :v :e))
             (= :desc direction) reverse)))))))

(defn avet-tuple-prefix
  "Datoms across endpoint entities whose tuple value starts with `prefix`.

  Current direct databases seek natively in either direction. Temporal/filter
  wrappers use their exact visible datoms and sort only that historical fallback
  result; current hot-path pagination never materializes the prefix."
  ([db attr arity prefix]
   (avet-tuple-prefix db attr arity prefix nil :asc))
  ([db attr arity prefix cursor-tail direction]
   (let [prefix (vec prefix)
         prefix-size (count prefix)
         missing (- arity prefix-size)]
     (if (or (neg? missing)
             (not (#{:asc :desc} direction)))
       []
       (let [seek-tail
             (or cursor-tail
                 (when (= :desc direction) Long/MAX_VALUE))
             seek-bound
             (if (and (some? seek-tail) (pos? missing))
               (into prefix
                     (cons seek-tail
                           (repeat (dec missing)
                                   (if (= :desc direction)
                                     Long/MAX_VALUE
                                     nil))))
               (into prefix (repeat missing nil)))
             matches-prefix? (tuple-prefix-matcher arity prefix)]
         (if (direct-db? db)
           (let [a-repr (attr-repr db attr)]
             (->> ((if (= :desc direction)
                     d/rseek-datoms
                     d/seek-datoms)
                   db {:index :avet
                       :components [attr seek-bound]})
                  (take-while
                   (fn [{:keys [a] :as datom}]
                     (and (= a-repr a)
                          (matches-prefix? datom))))))
           (cond->> (avet-datoms db attr)
             true (filter matches-prefix?)
             true (sort-by (juxt :v :e))
             (= :desc direction) reverse)))))))

(defn entity-exists?
  "Whether `eid` has any datom. `entid` passes unallocated numeric ids through
   unchanged, so presence has to be checked separately."
  [db eid]
  (boolean (seq (d/datoms db {:index :eavt :components [eid]}))))
