(ns eacl.datomic.impl.indexed
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [eacl.backend.spi :as spi]
            [eacl.core :refer [spice-object]]
            [eacl.engine.indexed :as engine]))

(def ^:private forward-relationship-attr
  :eacl.v7.relationship/subject-type+relation+resource-type+resource)

(def ^:private reverse-relationship-attr
  :eacl.v7.relationship/resource-type+relation+subject-type+subject)

(defn subject->resources
  [db subject-type subject-eid relation-eid resource-type cursor-resource-eid]
  {:pre [subject-type subject-eid relation-eid resource-type]}
  (let [attr-eid    (d/entid db forward-relationship-attr)
        start-tuple [subject-type
                     relation-eid
                     resource-type
                     (if cursor-resource-eid (inc cursor-resource-eid) 0)]]
    (->> (d/seek-datoms db :eavt subject-eid attr-eid start-tuple)
         (take-while
          (fn [[e a v]]
            (and (== subject-eid e)
                 (== attr-eid a)
                 (= [subject-type relation-eid resource-type]
                    (subvec (vec v) 0 3)))))
         (map (fn [[_ _ v]] (nth v 3))))))

(defn resource->subjects
  [db resource-type resource-eid relation-eid subject-type cursor-subject-eid]
  {:pre [resource-type resource-eid relation-eid subject-type]}
  (let [attr-eid    (d/entid db reverse-relationship-attr)
        start-tuple [resource-type
                     relation-eid
                     subject-type
                     (if cursor-subject-eid (inc cursor-subject-eid) 0)]]
    (->> (d/seek-datoms db :eavt resource-eid attr-eid start-tuple)
         (take-while
          (fn [[e a v]]
            (and (== resource-eid e)
                 (== attr-eid a)
                 (= [resource-type relation-eid subject-type]
                    (subvec (vec v) 0 3)))))
         (map (fn [[_ _ v]] (nth v 3))))))

(defn relation-datoms
  "Returns relation datoms for the exact resource/relation name pair,
  for ANY subject-type keyword.

  Implemented as a seek + prefix take-while rather than a bounded
  d/index-range: a keyword-sentinel range like [.. :a]..[.. :z] silently
  misses subject types that collate outside it (uppercase-initial,
  z-prefixed, and all namespaced keywords), which made those relations
  invisible to permission evaluation (audit 2). The attr-eid guard is
  mandatory - seek-datoms iterates past the attribute's index segment."
  [db resource-type relation-name]
  (if (and resource-type relation-name)
    (let [attr-eid (d/entid db :eacl.relation/resource-type+relation-name+subject-type)]
      (->> (d/seek-datoms db :avet
                          :eacl.relation/resource-type+relation-name+subject-type
                          [resource-type relation-name])
           (take-while (fn [datom]
                         (and (= attr-eid (:a datom))
                              (let [v (:v datom)]
                                (and (= resource-type (nth v 0))
                                     (= relation-name (nth v 1)))))))))
    []))

(defn find-relation-def
  "Compatibility helper retained for tests.
  Returns the first matching relation definition, if any."
  [db resource-type relation-name]
  (when-let [datom (first (relation-datoms db resource-type relation-name))]
    (d/pull db
            '[:db/id
              :eacl.relation/subject-type
              :eacl.relation/resource-type
              :eacl.relation/relation-name]
            (:e datom))))

(defn find-permission-defs
  [db resource-type permission-name]
  (let [tuple-val [resource-type permission-name]]
    (->> (d/datoms db :avet :eacl.permission/resource-type+permission-name tuple-val)
         (map :e)
         (map #(d/pull db '[*] %))
         vec)))

(def permission-paths-cache
  (atom {}))

(defn evict-permission-paths-cache! []
  (engine/evict-permission-paths-cache! permission-paths-cache))

;; --- Schema-history digest cache stamp (audit 3 / D8) ----------------------
;;
;; Coverage invariant: every attribute that permission-path computation reads
;; MUST be a component of one of the composite tuples digested below. Datomic
;; rewrites a composite tuple datom in the same transaction as ANY component
;; change, and retractEntity retracts it, so the tuple histories are a complete
;; record of path-relevant schema mutations. Any schema mutation - write-schema!,
;; programmatic transaction, retraction, or excision - changes the digest, on
;; every peer, for d/as-of views, with no writer-side signal.

(def ^:private schema-digest-attrs
  [:eacl.relation/resource-type+relation-name+subject-type
   :eacl.permission/resource-type+source-relation-name+target-type+target-name+permission-name])

(defonce ^:private schema-scope-memo
  ;; Keyed by the db value itself: Datomic Db equality is value- and
  ;; content-based (pinned by tests), so this can only unify db values whose
  ;; visible histories - and therefore digests - are identical. Weak keys
  ;; release with the db value. Sentinel (uncached) scopes are never stored.
  (java.util.Collections/synchronizedMap (java.util.WeakHashMap.)))

(defn- classified-view
  "Positively classifies a db value: :plain, :as-of, or nil for anything the
  digest must not be shared for. d/filter views are excluded because their
  predicates are arbitrary functions (possibly impure or time-dependent), so a
  digest of their filtered history is not trustworthy as a shared cache key;
  d/since views hide old schema; history views are not queryable schema states."
  [db]
  (cond
    (d/is-history db)      nil
    (d/is-filtered db)     nil
    (some? (d/since-t db)) nil
    (some? (d/as-of-t db)) :as-of
    :else                  :plain))

(defn- schema-history-digest
  "128-bit hex digest (SHA-256 truncated; FIPS-safe) folded in index order over
  the history datoms of the schema composite tuple attrs, filtered to the db's
  visible basis. NOTE: d/basis-t of an as-of view returns the UNDERLYING basis
  (pinned by tests), so the visibility filter must prefer d/as-of-t."
  [db]
  (let [md      (java.security.MessageDigest/getInstance "SHA-256")
        limit-t (or (d/as-of-t db) (d/basis-t db))
        hist    (d/history db)]
    (doseq [attr schema-digest-attrs
            datom (d/datoms hist :aevt attr)
            :let [t (d/tx->t (:tx datom))]
            :when (<= t limit-t)]
      (.update md (.getBytes ^String (pr-str [(:e datom) (:v datom) t (:added datom)]) "UTF-8")))
    (format "%032x" (java.math.BigInteger. 1 (java.util.Arrays/copyOf (.digest md) 16)))))

(defn schema-cache-scope
  "Returns [database-id schema-history-digest] for positively classified
  plain/as-of db values, or a fresh unique sentinel for anything else and for
  ANY failure. Sentinel scopes make every cache key unique: every failure mode
  degrades to recomputation from the queried db value - never staleness."
  [db]
  (or (.get ^java.util.Map schema-scope-memo db)
      (let [scope (try
                    (when (classified-view db)
                      [(str (.id db)) (schema-history-digest db)])
                    (catch Throwable _ nil))]
        (if scope
          (do (.put ^java.util.Map schema-scope-memo db scope)
              scope)
          [::uncached (Object.)]))))

(defn schema-basis-digest
  "The schema-history digest for this db value, or nil when the view cannot be
  cached (see schema-cache-scope)."
  [db]
  (let [scope (schema-cache-scope db)]
    (when-not (= ::uncached (first scope))
      (second scope))))

(defn direct-match-datoms-in-relationship-index
  [db subject-type subject-eid relation-eid resource-type resource-eid]
  (d/datoms db
            :eavt
            subject-eid
            forward-relationship-attr
            [subject-type relation-eid resource-type resource-eid]))

(defn indexed-backend
  [db]
  {:cache-stamp (fn []
                  ;; Content-derived schema scope (audit 3): identityHashCode
                  ;; keyed one cache entry per db OBJECT (a near-always-miss
                  ;; cache) and could collide across GC address reuse.
                  (schema-cache-scope db))
   :relation-defs (fn [resource-type relation-name]
                    (mapv (fn [datom]
                            {:relation-id (:e datom)
                             :resource-type resource-type
                             :relation-name relation-name
                             :subject-type (nth (:v datom) 2)})
                      (relation-datoms db resource-type relation-name)))
   :permission-defs (fn [resource-type permission-name]
                      (mapv (fn [perm]
                              {:permission-id (:db/id perm)
                               :resource-type (:eacl.permission/resource-type perm)
                               :permission-name (:eacl.permission/permission-name perm)
                               :source-relation-name (:eacl.permission/source-relation-name perm)
                               :target-type (:eacl.permission/target-type perm)
                               :target-name (:eacl.permission/target-name perm)})
                        (find-permission-defs db resource-type permission-name)))
   :subject->resources (fn [subject-type subject-id relation-id resource-type cursor-resource-id]
                         (subject->resources db subject-type subject-id relation-id resource-type cursor-resource-id))
   :resource->subjects (fn [resource-type resource-id relation-id subject-type cursor-subject-id]
                         (resource->subjects db resource-type resource-id relation-id subject-type cursor-subject-id))
   :direct-match? (fn [subject-type subject-id relation-id resource-type resource-id]
                    (boolean
                     (seq
                      (direct-match-datoms-in-relationship-index db
                                                                 subject-type
                                                                 subject-id
                                                                 relation-id
                                                                 resource-type
                                                                 resource-id))))})

(defn- backend*
  [db-or-backend]
  (if (and (map? db-or-backend) (contains? db-or-backend :cache-stamp))
    db-or-backend
    (indexed-backend db-or-backend)))

(defn calc-permission-paths
  ([db-or-backend resource-type permission-name]
   (engine/calc-permission-paths (backend* db-or-backend) resource-type permission-name))
  ([db-or-backend resource-type permission-name visited-perms]
   (engine/calc-permission-paths (backend* db-or-backend) resource-type permission-name visited-perms)))

(defn get-permission-paths
  [db-or-backend resource-type permission-name]
  (let [backend (backend* db-or-backend)]
    (engine/get-permission-paths permission-paths-cache calc-permission-paths backend resource-type permission-name)))

(defn traverse-permission-path
  ([db subject-type subject-eid permission-name resource-type cursor-eid]
   (traverse-permission-path db subject-type subject-eid permission-name resource-type cursor-eid #{}))
  ([db subject-type subject-eid permission-name resource-type cursor-eid visited-paths]
   (engine/traverse-permission-path
    (indexed-backend db)
    get-permission-paths
    subject-type
    subject-eid
    permission-name
    resource-type
    cursor-eid
    visited-paths)))

(defn- internal-id
  [db value]
  (when value
    (d/entid db value)))

(defn can?
  [db subject permission resource]
  (let [subject-id  (internal-id db (:id subject))
        resource-id (internal-id db (:id resource))]
    (engine/can?
     (indexed-backend db)
     get-permission-paths
     (assoc subject :id subject-id)
     permission
     (assoc resource :id resource-id))))

(defn- internalize-anchor
  [db object]
  (update object :id #(internal-id db %)))

(defn lookup-resources
  [db query]
  (engine/lookup
   (indexed-backend db)
   engine/forward-direction
   get-permission-paths
   (update query :subject #(internalize-anchor db %))))

(defn lookup-subjects
  "Unknown/missing resources return an empty page (SpiceDB-consistent),
  matching can? -> false; assertion-based rejection disappears under
  *assert* false and crashed with an untyped AssertionError."
  [db query]
  (engine/lookup
   (indexed-backend db)
   engine/reverse-direction
   get-permission-paths
   (update query :resource #(internalize-anchor db %))))

(defn count-resources
  [db query]
  (engine/count-results
   (indexed-backend db)
   engine/forward-direction
   get-permission-paths
   (update query :subject #(internalize-anchor db %))
   :resource))

(defn count-subjects
  [db query]
  (engine/count-results
   (indexed-backend db)
   engine/reverse-direction
   get-permission-paths
   (update query :resource #(internalize-anchor db %))
   :subject))
