(ns eacl.datahike.db
  "Datahike adapter primitives: the handful of places where datahike spells
   something differently from DataScript. Nothing above this namespace mentions
   datoms, seek bounds, or how an attribute is represented in a datom.

   This namespace is JVM-only, as is the rest of the module: datahike's
   ClojureScript API is asynchronous, and the backend SPI is synchronous."
  (:require [datahike.api :as d]
            [datahike.datom :as dd]
            [datahike.db.interface :as dbi]
            [datahike.db.search :as dh-search])
  (:import [datahike.db AsOfDB]))

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
                          {:type :eacl/invalid-entity-id
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

(defn- direct-db?
  "Whether `db` is a concrete Datahike DB rather than a temporal/filter wrapper.
   Concrete and retained-commit DB records carry their config as a field.
   Wrappers delegate `IDB/-config` but deliberately do not expose that field."
  [db]
  (some? (:config db)))

(defn- asof-seekable?
  "Whether `db` can take the lazy temporal seek path: Datahike's `AsOfDB` over
   a concrete origin with an integer (transaction-id) time point — the shape
   EACL constructs for `:at-exact-snapshot`. A date time point instead
   qualifies transactions through each one's `:db/txInstant` value, which is
   exactly the per-transaction lookup work the lazy path exists to avoid, so
   dates and every other wrapper keep the exact-datoms fallback."
  [db]
  (and (instance? AsOfDB db)
       (integer? (:time-point db))
       (some? (:config (:origin-db db)))))

(defn- asof-event-datoms
  "The raw temporal event stream behind an `AsOfDB`, positioned at
   `components` and lazily merged in full index order (for `:avet`: attribute,
   value, entity, transaction, op). Read from `datahike.db.search` directly:
   the public wrapper `seek-datoms` post-processing is eager over the whole
   remaining index, re-reads each distinct transaction's `:db/txInstant`, and
   returns hash-grouped, order-scrambled datoms — unusable under a take-while
   guard and the cost this path exists to remove. The stream interleaves
   assertion and retraction events of every tuple version; `visible-as-of`
   resolves them."
  [db index-type components direction]
  ((if (= :desc direction)
     dh-search/temporal-rseek-datoms
     dh-search/temporal-seek-datoms)
   (:origin-db db) index-type components))

(defn- visible-as-of
  "Lazily resolve an index-ordered temporal event stream to the datoms visible
   at transaction `time-point`. Sound because the merged stream keeps every
   event of one datom adjacent (the temporal comparators order by the index
   fields, then transaction, then op) and Datahike's temporal indices are a
   complete per-value event log: a retraction inserts the retracted assertion
   plus a retraction event, cardinality-one replacement writes an explicit
   retraction for the replaced value (`-temporal-upsert`), and live multival
   assertions ride along from the current-index side of the merge. The latest
   in-time event therefore decides each datom locally — the same answer as
   Datahike's eager per-entity-and-attribute grouping, with an assertion
   outranking a retraction inside one transaction exactly as its
   stable-by-transaction sort does. For an integer time point the filter
   `(<= tx time-point)` equals the eager path's `filter-txInstant` set,
   because a transaction's `:db/txInstant` datom carries the transaction id
   it describes."
  [time-point direction event-datoms]
  (let [tx-limit (long time-point)]
    (->> event-datoms
         (filter (fn [datom] (<= (dd/datom-tx datom) tx-limit)))
         (partition-by (fn [{:keys [e v]}] [e v]))
         (keep (fn [events]
                 (let [decisive (if (= :desc direction)
                                  (first events)
                                  (last events))]
                   (when (dd/datom-added decisive)
                     decisive)))))))

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
      (let [matches-prefix?
            (fn [{:keys [v]}]
              (and (vector? v)
                   (= arity (count v))
                   (= prefix (subvec v 0 n))))]
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
   retained-commit values use that seek. An integer-time `AsOfDB` — the
   `:at-exact-snapshot` shape — seeks the same bound lazily over the temporal
   event stream (`visible-as-of`), so one page costs the page rather than the
   endpoint's whole history. Every other wrapper still materializes its exact
   EAVT datoms behind an endpoint-local prefix filter, ignoring the seek bound
   (callers re-apply their cursor): Datahike 0.8.1759's own wrapper seek is
   eager over the remaining index and order-scrambled, so it can position a
   scan after a historically visible tuple."
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
             matches-prefix?
             (fn [{:keys [v]}]
               (and (vector? v)
                    (= arity (count v))
                    (= prefix (subvec v 0 prefix-size))))]
         (cond
           (direct-db? db)
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

           (asof-seekable? db)
           (let [a-repr (attr-repr db attr)]
             (->> (asof-event-datoms
                   db :eavt [entity attr seek-bound] direction)
                  (take-while
                   (fn [{:keys [e a] :as datom}]
                     (and (= entity e)
                          (= a-repr a)
                          (matches-prefix? datom))))
                  (visible-as-of (:time-point db) direction)))

           :else
           (cond->> (eavt-datoms db entity attr)
             true (filter matches-prefix?)
             true (sort-by (juxt :v :e))
             (= :desc direction) reverse)))))))

(defn avet-tuple-prefix
  "Datoms across endpoint entities whose tuple value starts with `prefix`.

  Current direct databases seek natively in either direction, and an
  integer-time `AsOfDB` seeks the same bound lazily over the temporal event
  stream (`visible-as-of`). Remaining temporal/filter wrappers use their exact
  visible datoms — the whole prefix, ignoring the seek bound (callers re-apply
  their cursor) — and sort only that fallback result; hot-path pagination
  never materializes the prefix."
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
             matches-prefix?
             (fn [{:keys [v]}]
               (and (vector? v)
                    (= arity (count v))
                    (= prefix (subvec v 0 prefix-size))))]
         (cond
           (direct-db? db)
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

           (asof-seekable? db)
           (let [a-repr (attr-repr db attr)]
             (->> (asof-event-datoms db :avet [attr seek-bound] direction)
                  (take-while
                   (fn [{:keys [a] :as datom}]
                     (and (= a-repr a)
                          (matches-prefix? datom))))
                  (visible-as-of (:time-point db) direction)))

           :else
           (cond->> (avet-datoms db attr)
             true (filter matches-prefix?)
             true (sort-by (juxt :v :e))
             (= :desc direction) reverse)))))))

(defn entity-exists?
  "Whether `eid` has any datom. `entid` passes unallocated numeric ids through
   unchanged, so presence has to be checked separately."
  [db eid]
  (boolean (seq (d/datoms db {:index :eavt :components [eid]}))))
