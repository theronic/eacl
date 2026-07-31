(ns eacl.datomic.watermark
  "Cache epochs derived from Datomic's transaction log.

  The exact-result cache is keyed by Datomic `basis-t`, which advances on every
  transaction in the database. On a busy database that is a ~0% hit rate: an
  unrelated invoice write mints a new key even though no EACL data moved.

  The obvious shortcut — key on the relationship coordinator's `observed-t`
  instead — is UNSOUND. A coordinator only learns about writes made through its
  own process, so a second app server's write leaves `observed-t` untouched and
  the cache serves a stale answer. That is a privilege-escalation bug, not a
  staleness annoyance, and it is why this namespace verifies against the
  database rather than trusting a process-local counter.

  An epoch is a `t` that changes only when EACL-relevant data changes. Two
  reads sharing an epoch are guaranteed to see identical relationship tuples
  and identical relation/permission definitions, so a cached answer — which
  holds internal EIDs only — is equally valid for both.

  What makes that guarantee airtight: an EACL answer is a function of
  relationship tuples, relation/permission definitions, and the schema
  generation (already in every cache key). External-id coercion, input id
  resolution and entity-existence checks all happen per request against the
  caller's selected database, never from cache.

  Verified against Datomic (see eacl.datomic.watermark-test): unrelated
  transactions do not move the epoch; creates, deletes, schema changes, writes
  from another connection, and raw `d/transact` of relationship tx-data all do."
  (:require [datomic.api :as d]))

(def watched-attributes
  "Every attribute whose change can alter an EACL answer.

  Relationship tuples carry the grants themselves. Relation and permission
  attributes define the paths. :eacl/schema-version covers a generation bump
  even when the definition diff happens to be invisible here.

  :eacl/id is deliberately absent: external ids are resolved per request
  against the caller's database on the way in and on the way out, never from
  cache, so changing one cannot make a cached answer wrong."
  [:eacl.v7.relationship/subject-type+relation+resource-type+resource
   :eacl.v7.relationship/resource-type+relation+subject-type+subject
   :eacl.relation/resource-type
   :eacl.relation/relation-name
   :eacl.relation/subject-type
   :eacl.permission/resource-type
   :eacl.permission/permission-name
   :eacl.permission/source-relation-name
   :eacl.permission/target-type
   :eacl.permission/target-name
   :eacl/schema-version])

(def ^:private default-max-scanned-transactions
  "A reader that has been idle while the database moved a long way should not
  pay an unbounded scan to find out. Past this many transactions the window is
  abandoned and the epoch advances unconditionally — a cache miss, never a
  wrong answer."
  256)

(defn- resolve-attribute-eids
  "Entity ids for the watched attributes, and whether ALL of them resolved.

  A client may be constructed before the EACL schema is installed — the
  benchmark's own seeding does exactly that. Resolving once at construction
  then yielded an empty watched set, so no relationship write was ever
  detected and the epoch never moved: stale answers, permanently. The set is
  therefore re-resolved until it is complete, and an incomplete set is treated
  as 'cannot verify' rather than 'nothing changed'."
  [db]
  (let [eids (into #{} (keep #(d/entid db %)) watched-attributes)]
    {:eids eids
     :complete? (= (count eids) (count watched-attributes))}))

(defn- transaction-touches-eacl?
  [attr-eids tx]
  (reduce (fn [_ datom]
            (if (contains? attr-eids (:a datom))
              (reduced true)
              false))
          false
          (:data tx)))

(defn- window-changed?
  "Whether any EACL-relevant datom was transacted in (from-t, to-t].

  `d/log` is fetched fresh on every call and must stay that way: a Log obtained
  before a transaction does not observe it, so caching the object would
  silently miss writes. Verified — a cached log saw 1 transaction where a fresh
  one saw 2.

  `d/tx-range`'s start is inclusive and `t` values are not guaranteed
  contiguous, so the boundary transaction is filtered out by `:t` rather than
  by asking for `(inc from-t)`. Without that filter the transaction that
  established `from-t` is re-read, and a relationship write at exactly the
  boundary reports a change forever."
  [conn attr-eids from-t to-t max-transactions]
  (if-let [log (and (seq attr-eids) (d/log conn))]
    (loop [txs (seq (d/tx-range log from-t nil))
           scanned 0]
      (cond
        (nil? txs) false
        ;; Bailing out means "assume changed", which costs a miss.
        (>= scanned max-transactions) true
        :else
        (let [tx (first txs)
              t (long (:t tx))]
          (cond
            (<= t (long from-t)) (recur (next txs) scanned)
            (> t (long to-t)) false
            (transaction-touches-eacl? attr-eids tx) true
            :else (recur (next txs) (inc scanned))))))
    ;; No log available for this connection: cannot prove anything.
    true))

(defprotocol CacheEpoch
  (epoch-for [this basis-t]
    "Returns an epoch `t` for a database at `basis-t`.

    Equal epochs mean no EACL-relevant datom changed between the two reads.
    Unequal epochs mean nothing beyond 'recompute'."))

(defn- current-attribute-eids
  "Watched attribute eids, refreshed while the schema is still incomplete.
  Once every attribute resolves the set is frozen — attribute ids never move."
  [conn attrs]
  (let [{:keys [complete?] :as cached} @attrs]
    (if complete?
      (:eids cached)
      (let [resolved (resolve-attribute-eids (d/db conn))]
        (reset! attrs resolved)
        (when (:complete? resolved)
          (:eids resolved))))))

(defrecord LogCacheEpoch [conn attrs state config]
  CacheEpoch
  (epoch-for [_ basis-t]
    (let [basis-t (long basis-t)
          attr-eids (current-attribute-eids conn attrs)
          {:keys [verified-t epoch]} @state]
      (cond
        ;; Already proved up to here.
        (= basis-t (long verified-t)) epoch

        ;; An older basis than we have verified — a cursor or exact-snapshot
        ;; read. EACL targets the current database; historical reads get their
        ;; own basis as their epoch, which simply will not share entries with
        ;; anything. Deliberately not made hot.
        (< basis-t (long verified-t)) basis-t

        :else
        (let [changed? (window-changed? conn attr-eids verified-t basis-t
                                        (:max-scanned-transactions config))
              epoch' (if changed? basis-t epoch)
              advanced (swap! state
                              (fn [{:keys [verified-t] :as st}]
                                ;; Forward-only. A concurrent reader at a
                                ;; higher basis may already have advanced past
                                ;; us; its scan covered ours, so leave it.
                                (if (< (long verified-t) basis-t)
                                  {:verified-t basis-t :epoch epoch'}
                                  st)))]
          (if (= basis-t (long (:verified-t advanced)))
            (:epoch advanced)
            ;; Someone else moved further while we scanned. Our own basis is
            ;; always a sound epoch for ourselves.
            basis-t))))))

(defn log-cache-epoch
  "Creates epoch state for one connection, starting from its current basis.

  Construction never scans history: the first read is its own epoch and
  everything is measured forward from there."
  ([conn]
   (log-cache-epoch conn {}))
  ([conn {:keys [max-scanned-transactions]
          :or {max-scanned-transactions default-max-scanned-transactions}}]
   (when-not (and (integer? max-scanned-transactions)
                  (pos? max-scanned-transactions))
     (throw (ex-info "EACL Config Error: :max-scanned-transactions must be a positive integer."
                     {:type :eacl/invalid-config
                      :value max-scanned-transactions})))
   (let [db (d/db conn)
         basis-t (d/basis-t db)]
     (->LogCacheEpoch conn
                      (atom (resolve-attribute-eids db))
                      (atom {:verified-t basis-t :epoch basis-t})
                      {:max-scanned-transactions max-scanned-transactions}))))

(defn safe-epoch-for
  "The epoch for `basis-t`, falling back to `basis-t` itself on any failure.

  Falling back is always sound: an epoch of `basis-t` is what the cache keyed
  on before this existed, so a broken or unavailable log degrades to the old
  behaviour rather than to a wrong answer."
  [cache-epoch basis-t]
  (if cache-epoch
    (try
      (epoch-for cache-epoch basis-t)
      (catch Exception _
        basis-t))
    basis-t))
