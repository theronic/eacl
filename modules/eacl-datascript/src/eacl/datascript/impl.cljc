(ns eacl.datascript.impl
  (:require [datascript.core :as ds]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datascript.db :as ddb]
            [eacl.datascript.schema :as schema]
            [eacl.engine.indexed :as engine]
            [eacl.engine.relationships :as relationship-engine]
            [eacl.relationships.endpoint-pair :as endpoint-pair]
            [eacl.schema.model :as model]))

(def Relation model/Relation)
(def Permission model/Permission)

(defn Relationship
  [subject relation resource]
  (eacl/->Relationship subject relation resource))

(def permission-paths-cache
  (atom {}))

(def max-entid
  #?(:clj Long/MAX_VALUE
     :cljs js/Number.MAX_SAFE_INTEGER))

(def permission-def-pull
  '[:db/id
    :eacl.permission/resource-type
    :eacl.permission/permission-name
    :eacl.permission/source-relation-name
    :eacl.permission/target-type
    :eacl.permission/target-name])

(defn evict-permission-paths-cache!
  ([] (evict-permission-paths-cache! permission-paths-cache))
  ([cache-atom]
   (engine/evict-permission-paths-cache! cache-atom)))

(defn relation-datoms
  "Returns relation datoms for the exact resource/relation name pair, for ANY
  subject-type keyword. Implemented as a seek + prefix take-while rather than a
  bounded index-range: a keyword-sentinel range like [.. :a]..[.. :z] silently
  misses subject types that collate outside it (uppercase-initial, z-prefixed,
  and namespaced keywords), making those relations invisible to permission
  evaluation (audit 2)."
  [db resource-type relation-name]
  (if (and resource-type relation-name)
    ;; DataScript sorts vectors by LENGTH FIRST, so a short seek-start would
    ;; land at the head of the whole attribute; pad to full tuple arity with
    ;; nil (nil sorts lowest) to position at the exact prefix.
    (->> (ds/seek-datoms db :avet
                         :eacl.relation/resource-type+relation-name+subject-type
                         [resource-type relation-name nil])
         (take-while (fn [datom]
                       (and (= :eacl.relation/resource-type+relation-name+subject-type (:a datom))
                            (let [v (:v datom)]
                              (and (= resource-type (nth v 0))
                                   (= relation-name (nth v 1))))))))
    []))

(defn find-relation-def
  [db resource-type relation-name]
  (when-let [datom (first (relation-datoms db resource-type relation-name))]
    (ds/pull db
             '[:db/id
               :eacl.relation/subject-type
               :eacl.relation/resource-type
               :eacl.relation/relation-name]
             (:e datom))))

(defn find-permission-defs
  [db resource-type permission-name]
  (->> (ds/datoms db :avet :eacl.permission/resource-type+permission-name [resource-type permission-name])
       (map :e)
       (map #(ds/pull db permission-def-pull %))
       vec))

(defn all-relation-defs
  [db]
  (mapv (fn [{:keys [e v]}]
          {:relation-id e
           :resource-type (nth v 0)
           :relation-name (nth v 1)
           :subject-type (nth v 2)})
        (ds/datoms db :avet :eacl.relation/resource-type+relation-name+subject-type)))

(defn subject->resources
  [db subject-type subject-id relation-id resource-type cursor-or-options]
  (let [{:keys [direction bound-eid inclusive-bound?]}
        (if (map? cursor-or-options)
          (merge {:direction :asc} cursor-or-options)
          {:direction :asc
           :bound-eid cursor-or-options
           :inclusive-bound? false})
        within-bound?
        (case direction
          :asc
          (if bound-eid
            (if inclusive-bound?
              #(<= bound-eid %)
              #(< bound-eid %))
            (constantly true))

          :desc
          (if bound-eid
            (if inclusive-bound?
              #(>= bound-eid %)
              #(> bound-eid %))
            (constantly true)))]
    (->> (ddb/eavt-endpoint-prefix
          db subject-id schema/forward-relationship-attr
          [subject-type relation-id resource-type]
          bound-eid direction)
         (map (comp #(nth % 3) :v))
         (filter within-bound?))))

(defn resource->subjects
  [db resource-type resource-id relation-id subject-type cursor-or-options]
  (let [{:keys [direction bound-eid inclusive-bound?]}
        (if (map? cursor-or-options)
          (merge {:direction :asc} cursor-or-options)
          {:direction :asc
           :bound-eid cursor-or-options
           :inclusive-bound? false})
        within-bound?
        (case direction
          :asc
          (if bound-eid
            (if inclusive-bound?
              #(<= bound-eid %)
              #(< bound-eid %))
            (constantly true))

          :desc
          (if bound-eid
            (if inclusive-bound?
              #(>= bound-eid %)
              #(> bound-eid %))
            (constantly true)))]
    (->> (ddb/eavt-endpoint-prefix
          db resource-id schema/reverse-relationship-attr
          [resource-type relation-id subject-type]
          bound-eid direction)
         (map (comp #(nth % 3) :v))
         (filter within-bound?))))

(defn build-schema-catalog
  [db]
  {:relation-defs
   (reduce (fn [idx {:keys [e v]}]
             (let [[resource-type relation-name subject-type] v
                   relation-def {:relation-id e
                                 :resource-type resource-type
                                 :relation-name relation-name
                                 :subject-type subject-type}]
               (update idx [resource-type relation-name] (fnil conj []) relation-def)))
           {}
           (ds/datoms db :avet :eacl.relation/resource-type+relation-name+subject-type))
   :permission-defs
   (reduce (fn [idx {:keys [e]}]
             (let [perm (ds/pull db permission-def-pull e)]
               (update idx
                       [(:eacl.permission/resource-type perm)
                        (:eacl.permission/permission-name perm)]
                       (fnil conj [])
                       perm)))
           {}
           (ds/datoms db :avet :eacl.permission/resource-type+permission-name))})

(defn- relationship-tuple
  [{:keys [subject-type relation-id resource-type resource-id]}]
  (endpoint-pair/forward-value
   subject-type relation-id resource-type resource-id))

(defn- reverse-relationship-tuple
  [{:keys [resource-type relation-id subject-type subject-id]}]
  (endpoint-pair/reverse-value
   resource-type relation-id subject-type subject-id))

(defn- internal-id
  [db value]
  (when value
    (ds/entid db value)))

(defn- existing-internal-id
  "Resolves to an eid and verifies the entity exists (datom presence - entid
  passes unallocated numeric ids through unchanged). Throws :eacl/unknown-object
  otherwise: nil ids reaching tx-data raised raw transact errors, and silent
  no-ops hid typos (audit 11/12)."
  [db {:keys [type id]}]
  (let [eid (internal-id db id)]
    (if (and eid (seq (ds/datoms db :eavt eid)))
      eid
      (throw (ex-info (str "Unknown object: " (pr-str type) " with id " (pr-str id) " does not exist.")
               {:type :eacl/unknown-object
                :object {:type type :id id}})))))

(defn- relation-id
  [resource-type relation-name subject-type]
  [:eacl/id (model/->relation-id resource-type relation-name subject-type)])

(defn- resolve-relationship
  [db {:keys [subject relation resource]}]
  (let [subject-type  (:type subject)
        subject-id    (existing-internal-id db subject)
        resource-type (:type resource)
        resource-id   (existing-internal-id db resource)
        relation-eid  (ds/entid db (relation-id resource-type relation subject-type))]
    (when-not relation-eid
      (throw
       (ex-info
        (str "Missing Relation: " relation
             " on resource type " resource-type
             " for subject type " subject-type ".")
        {:resource/type resource-type
         :relation/name relation
         :subject/type subject-type})))
    {:subject subject
     :subject-type subject-type
     :subject-id subject-id
     :relation relation
     :relation-id relation-eid
     :resource resource
     :resource-type resource-type
     :resource-id resource-id}))

(defn find-one-relationship-id
  "Returns the resolved tuple identity for an existing relationship, or nil.
  A read: unresolvable endpoints mean no such relationship can exist -> nil."
  [db relationship]
  (let [resolved (try
                   (resolve-relationship db relationship)
                   (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
                     (when-not (= :eacl/unknown-object (:type (ex-data e)))
                       (throw e))))
        existing? (and resolved
                       (seq
                        (ddb/eavt-datoms
                         db
                         (:subject-id resolved)
                         schema/forward-relationship-attr
                         (relationship-tuple resolved)))
                       (seq
                        (ddb/eavt-datoms
                         db
                         (:resource-id resolved)
                         schema/reverse-relationship-attr
                         (reverse-relationship-tuple resolved))))]
    (when existing?
      resolved)))

(defn relationship-relation-id
  [db relationship]
  (:relation-id (resolve-relationship db relationship)))

(defn- add-relationship-txes
  [resolved]
  [[:db/add
    (:subject-id resolved)
    schema/forward-relationship-attr
    (relationship-tuple resolved)]
   [:db/add
    (:resource-id resolved)
    schema/reverse-relationship-attr
    (reverse-relationship-tuple resolved)]])

(defn- retract-relationship-txes
  [resolved]
  [[:db/retract
    (:subject-id resolved)
    schema/forward-relationship-attr
    (relationship-tuple resolved)]
   [:db/retract
    (:resource-id resolved)
    schema/reverse-relationship-attr
    (reverse-relationship-tuple resolved)]])

(defn direct-match?
  [db subject-type subject-id relation-id resource-type resource-id]
  (boolean
   (seq
    (ddb/eavt-datoms
     db subject-id schema/forward-relationship-attr
     (endpoint-pair/forward-value
      subject-type relation-id resource-type resource-id)))))

(defn- reverse-match?
  [db resource-type resource-id relation-id subject-type subject-id]
  (boolean
   (seq
    (ddb/eavt-datoms
     db resource-id schema/reverse-relationship-attr
     (endpoint-pair/reverse-value
      resource-type relation-id subject-type subject-id)))))

(defn- relationship-exists?
  [db {:keys [subject-type subject-id relation-id
              resource-type resource-id]}]
  (and (direct-match? db subject-type subject-id relation-id
                      resource-type resource-id)
       (reverse-match? db resource-type resource-id relation-id
                       subject-type subject-id)))

(def ^:private supported-relationship-operations
  #{:create :touch :delete})

(defn validate-relationship-operation!
  [operation]
  (when-not (contains? supported-relationship-operations operation)
    (throw
     (ex-info
      (str (pr-str operation)
           " relationship update is not supported. Use :create, :touch or :delete.")
      {:type :eacl/unsupported-operation
       :operation operation})))
  true)

(defn tx-update-relationship
  [db {:keys [operation relationship]}]
  (validate-relationship-operation! operation)
  (let [resolved (resolve-relationship db relationship)
        exists?  (relationship-exists? db resolved)]
    (case operation
      :touch
      (when-not exists?
        (add-relationship-txes resolved))

      :create
      (if exists?
        (throw
         (ex-info
          ":create conflicts with an existing relationship. Use :touch for idempotent writes."
          {:type :eacl/relationship-conflict
           :relationship relationship}))
        (add-relationship-txes resolved))

      :delete
      ;; Retraction of an absent DataScript datom is harmless. Always retract
      ;; both halves so an out-of-band half pair is repairable.
      (retract-relationship-txes resolved))))

(def ^:private known-relationship-filter-keys
  #{:subject/type :subject/id
    :resource/type :resource/id :resource/relation
    :limit :cursor :consistency})

(def ^:private relationship-anchor-keys
  #{:subject/type :subject/id
    :resource/type :resource/id :resource/relation})

(defn validate-relationship-filters!
  [filters]
  (doseq [[unsupported-key hint]
          [[:resource/id-prefix "Filter on :resource/id, or filter external ids client-side."]
           [:subject/relation "EACL does not support subject-relation filters."]]]
    (when (contains? filters unsupported-key)
      (throw (ex-info (str (pr-str unsupported-key)
                           " is not supported by read-relationships. "
                           hint)
               {:eacl/error :eacl.pagination/unsupported-filter
                :filter unsupported-key}))))
  (when-let [unknown-keys
             (seq (remove known-relationship-filter-keys (keys filters)))]
    (throw (ex-info (str "read-relationships was passed unknown filter key(s): "
                         (pr-str (vec unknown-keys))
                         ". Known keys: "
                         (pr-str (vec (sort known-relationship-filter-keys)))
                         ".")
             {:eacl/error :eacl.filters/unknown-filter
              :unknown-keys (vec unknown-keys)})))
  (when-not (some #(contains? filters %) relationship-anchor-keys)
    (throw (ex-info (str "read-relationships requires at least one anchor filter of "
                         (pr-str (vec (sort relationship-anchor-keys)))
                         ". An unfiltered read would scan the entire relationship index.")
             {:eacl/error :eacl.filters/missing-anchor}))))

(defn read-relationships
  [db filters]
  (validate-relationship-filters! filters)
  (let [subject-id'  (when (contains? filters :subject/id)
                       (internal-id db (:subject/id filters)))
        resource-id' (when (contains? filters :resource/id)
                       (internal-id db (:resource/id filters)))
        filters'     (cond-> filters
                       (contains? filters :subject/id) (assoc :subject/id subject-id')
                       (contains? filters :resource/id) (assoc :resource/id resource-id'))]
    (if (or (and (contains? filters :subject/id) (nil? subject-id'))
            (and (contains? filters :resource/id) (nil? resource-id')))
      {:data [] :cursor nil}
      (letfn [(relationship-row [spec subject-id resource-id]
                {:spec-idx    (:idx spec)
                 :subject-id  subject-id
                 :resource-id resource-id
                 :relationship
                 (eacl/->Relationship
                  (spice-object (:subject-type spec) subject-id)
                  (:relation-name spec)
                  (spice-object (:resource-type spec) resource-id))})
              (drop-until-after-cursor [spec cursor rows]
                (drop-while
                 #(not
                   (relationship-engine/after-cursor?
                    (:scan-kind spec) cursor %))
                 rows))
              (exact-match-row [spec cursor]
                (let [row
                      (when (and (:subject-id spec) (:resource-id spec))
                        (when
                         (direct-match?
                          db
                          (:subject-type spec)
                          (:subject-id spec)
                          (:relation-id spec)
                          (:resource-type spec)
                          (:resource-id spec))
                          (relationship-row
                           spec (:subject-id spec) (:resource-id spec))))]
                  (if row
                    (drop-until-after-cursor spec cursor [row])
                    [])))
              (scan-forward-anchored [spec cursor]
                (if (:resource-id spec)
                  (exact-match-row spec cursor)
                  (->> (ddb/eavt-endpoint-prefix
                        db
                        (:subject-id spec)
                        schema/forward-relationship-attr
                        [(:subject-type spec)
                         (:relation-id spec)
                         (:resource-type spec)]
                        (:resource cursor)
                        :asc)
                       (map
                        (fn [{:keys [v]}]
                          (relationship-row
                           spec (:subject-id spec) (nth v 3))))
                       (drop-until-after-cursor spec cursor))))
              (scan-reverse-anchored [spec cursor]
                (if (:subject-id spec)
                  (exact-match-row spec cursor)
                  (->> (ddb/eavt-endpoint-prefix
                        db
                        (:resource-id spec)
                        schema/reverse-relationship-attr
                        [(:resource-type spec)
                         (:relation-id spec)
                         (:subject-type spec)]
                        (:subject cursor)
                        :asc)
                       (map
                        (fn [{:keys [v]}]
                          (relationship-row
                           spec (nth v 3) (:resource-id spec))))
                       (drop-until-after-cursor spec cursor))))
              (scan-forward-partial [spec cursor]
                (->> (ddb/avet-endpoint-prefix
                      db
                      schema/forward-relationship-attr
                      [(:subject-type spec)
                       (:relation-id spec)
                       (:resource-type spec)]
                      (:resource cursor)
                      :asc)
                     (map
                      (fn [{:keys [e v]}]
                        (relationship-row spec e (nth v 3))))
                     (drop-until-after-cursor spec cursor)))
              (scan-reverse-partial [spec cursor]
                (->> (ddb/avet-endpoint-prefix
                      db
                      schema/reverse-relationship-attr
                      [(:resource-type spec)
                       (:relation-id spec)
                       (:subject-type spec)]
                      (:subject cursor)
                      :asc)
                     (map
                      (fn [{:keys [e v]}]
                        (relationship-row spec (nth v 3) e)))
                     (drop-until-after-cursor spec cursor)))
              (scan-spec [spec cursor]
                (case (:scan-kind spec)
                  :forward-anchored
                  (scan-forward-anchored spec cursor)

                  :reverse-anchored
                  (scan-reverse-anchored spec cursor)

                  :forward-partial
                  (scan-forward-partial spec cursor)

                  (scan-reverse-partial spec cursor)))]
        (relationship-engine/execute-plan
         (relationship-engine/plan-scans (all-relation-defs db) filters')
         filters'
         scan-spec)))))

(defn- relation-triples
  [db]
  (mapv (fn [{:keys [e v]}]
          [(nth v 0) e (nth v 2)])
        (ddb/avet-datoms
         db :eacl.relation/resource-type+relation-name+subject-type)))

(defn- relationship-pair-retractions
  [subject-type subject-id relation-id resource-type resource-id]
  [[:db/retract
    subject-id
    schema/forward-relationship-attr
    (endpoint-pair/forward-value
     subject-type relation-id resource-type resource-id)]
   [:db/retract
    resource-id
    schema/reverse-relationship-attr
    (endpoint-pair/reverse-value
     resource-type relation-id subject-type subject-id)]])

(defn tx-delete-object
  "Returns transaction data removing both physical halves of every
  relationship touching `object-id`. The object entity itself is retained.

  Cross-entity exact AVET probes also find a surviving peer half when the local
  endpoint entity was already retracted out of band and its numeric eid is
  supplied for cleanup."
  [db object-id]
  (if-let [object-eid (internal-id db object-id)]
    (let [triples (relation-triples db)]
      (->>
       (concat
        (mapcat
         (fn [{:keys [v]}]
           (when-let [{:keys [subject-type relation-eid
                             resource-type resource-eid]}
                      (endpoint-pair/decode-forward object-eid v)]
             (relationship-pair-retractions
              subject-type object-eid relation-eid
              resource-type resource-eid)))
         (ddb/eavt-datoms
          db object-eid schema/forward-relationship-attr))

        (mapcat
         (fn [{:keys [v]}]
           (when-let [{:keys [subject-type subject-eid relation-eid
                             resource-type]}
                      (endpoint-pair/decode-reverse object-eid v)]
             (relationship-pair-retractions
              subject-type subject-eid relation-eid
              resource-type object-eid)))
         (ddb/eavt-datoms
          db object-eid schema/reverse-relationship-attr))

        (mapcat
         (fn [[resource-type relation-id subject-type]]
           (mapcat
            (fn [{resource-id :e}]
              (relationship-pair-retractions
               subject-type object-eid relation-id
               resource-type resource-id))
            (ddb/avet-datoms
             db schema/reverse-relationship-attr
             (endpoint-pair/reverse-value
              resource-type relation-id subject-type object-eid))))
         triples)

        (mapcat
         (fn [[resource-type relation-id subject-type]]
           (mapcat
            (fn [{subject-id :e}]
              (relationship-pair-retractions
               subject-type subject-id relation-id
               resource-type object-eid))
            (ddb/avet-datoms
             db schema/forward-relationship-attr
             (endpoint-pair/forward-value
              subject-type relation-id resource-type object-eid))))
         triples))
       (remove nil?)
       distinct
       vec))
    []))

(defn affected-relation-ids
  "Every relation named by endpoint-pair retraction operations."
  [tx-data]
  (->> tx-data
       (keep
        (fn [op]
          (when (and (vector? op)
                     (= :db/retract (first op))
                     (contains?
                      #{schema/forward-relationship-attr
                        schema/reverse-relationship-attr}
                      (nth op 2 nil)))
            (nth (nth op 3) 1))))
       distinct
       sort
       vec))

(defn orphaned-relationship-halves
  "Lazy deterministic scan of physical relationship halves whose exact peer
  half is absent. Malformed values are reported as dangling rather than
  throwing from the diagnostic."
  [db]
  (concat
   (for [{subject-id :e value :v}
         (ddb/avet-datoms db schema/forward-relationship-attr)
         :let [decoded (endpoint-pair/decode-forward subject-id value)
               peer (endpoint-pair/peer-half :forward subject-id value)]
         :when
         (or (nil? decoded)
             (empty?
              (ddb/eavt-datoms
               db (:endpoint-eid peer)
               schema/reverse-relationship-attr
               (:value peer))))]
     {:half :forward
      :e subject-id
      :attr schema/forward-relationship-attr
      :v (if (sequential? value) (vec value) value)
      :subject-eid subject-id
      :resource-eid (:resource-eid decoded)
      :relation-eid (:relation-eid decoded)
      :value-arity (when (counted? value) (count value))})
   (for [{resource-id :e value :v}
         (ddb/avet-datoms db schema/reverse-relationship-attr)
         :let [decoded (endpoint-pair/decode-reverse resource-id value)
               peer (endpoint-pair/peer-half :reverse resource-id value)]
         :when
         (or (nil? decoded)
             (empty?
              (ddb/eavt-datoms
               db (:endpoint-eid peer)
               schema/forward-relationship-attr
               (:value peer))))]
     {:half :reverse
      :e resource-id
      :attr schema/reverse-relationship-attr
      :v (if (sequential? value) (vec value) value)
      :subject-eid (:subject-eid decoded)
      :resource-eid resource-id
      :relation-eid (:relation-eid decoded)
      :value-arity (when (counted? value) (count value))})))

(defn- normalize-backend-options
  [cache-stamp-or-opts]
  (cond
    (nil? cache-stamp-or-opts) {}
    (fn? cache-stamp-or-opts) {:cache-stamp cache-stamp-or-opts}
    (map? cache-stamp-or-opts) cache-stamp-or-opts
    :else (throw (ex-info "Unsupported DataScript backend options"
                          {:value cache-stamp-or-opts}))))

(defn- schema-catalog-data
  [db schema-catalog]
  (cond
    (nil? schema-catalog) nil
    (fn? schema-catalog) (schema-catalog db)
    :else schema-catalog))

(defn- relation-defs-from-db
  [db resource-type relation-name]
  (mapv (fn [datom]
          {:relation-id (:e datom)
           :resource-type resource-type
           :relation-name relation-name
           :subject-type (nth (:v datom) 2)})
        (relation-datoms db resource-type relation-name)))

(defn- permission-defs-from-db
  [db resource-type permission-name]
  (mapv (fn [perm]
          {:permission-id (:db/id perm)
           :resource-type (:eacl.permission/resource-type perm)
           :permission-name (:eacl.permission/permission-name perm)
           :source-relation-name (:eacl.permission/source-relation-name perm)
           :target-type (:eacl.permission/target-type perm)
           :target-name (:eacl.permission/target-name perm)})
        (find-permission-defs db resource-type permission-name)))

(defn indexed-backend
  ([db]
   (indexed-backend db nil))
  ([db cache-stamp-or-opts]
   (let [{:keys [cache-stamp
                 schema-catalog]
          :as options} (normalize-backend-options cache-stamp-or-opts)
         permission-paths-cache-atom (:permission-paths-cache options)]
     {:cache-stamp (or cache-stamp
                       (fn []
                         (hash db)))
      :permission-paths-cache (or permission-paths-cache-atom permission-paths-cache)
      :relation-defs (fn [resource-type relation-name]
                       (if-let [catalog (schema-catalog-data db schema-catalog)]
                         (get-in catalog [:relation-defs [resource-type relation-name]] [])
                         (relation-defs-from-db db resource-type relation-name)))
      :permission-defs (fn [resource-type permission-name]
                         (if-let [catalog (schema-catalog-data db schema-catalog)]
                           (->> (get-in catalog [:permission-defs [resource-type permission-name]] [])
                                (mapv (fn [perm]
                                        {:permission-id (:db/id perm)
                                         :resource-type (:eacl.permission/resource-type perm)
                                         :permission-name (:eacl.permission/permission-name perm)
                                         :source-relation-name (:eacl.permission/source-relation-name perm)
                                         :target-type (:eacl.permission/target-type perm)
                                         :target-name (:eacl.permission/target-name perm)})))
                           (permission-defs-from-db db resource-type permission-name)))
    :subject->resources (fn [subject-type subject-id relation-id resource-type cursor-resource-id]
                          (subject->resources db subject-type subject-id relation-id resource-type cursor-resource-id))
    :resource->subjects (fn [resource-type resource-id relation-id subject-type cursor-subject-id]
                          (resource->subjects db resource-type resource-id relation-id subject-type cursor-subject-id))
	    :direct-match?
        (fn [subject-type subject-id relation-id resource-type resource-id]
          (direct-match?
           db subject-type subject-id relation-id resource-type resource-id))})))

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
  (let [backend    (backend* db-or-backend)
        cache-atom (or (:permission-paths-cache backend) permission-paths-cache)]
    (engine/get-permission-paths cache-atom calc-permission-paths backend resource-type permission-name)))

(defn can?
  ([db subject permission resource]
   (can? db nil subject permission resource))
  ([db cache-stamp-or-opts subject permission resource]
   (let [subject-id  (internal-id db (:id subject))
         resource-id (internal-id db (:id resource))]
     (engine/can?
      (indexed-backend db cache-stamp-or-opts)
      get-permission-paths
      (assoc subject :id subject-id)
      permission
      (assoc resource :id resource-id)))))

(defn- internalize-anchor
  [db object]
  (update object :id #(internal-id db %)))

(defn lookup-resources
  ([db query]
   (lookup-resources db nil query))
  ([db cache-stamp-or-opts query]
   (engine/lookup
    (indexed-backend db cache-stamp-or-opts)
    engine/forward-direction
    get-permission-paths
    (update query :subject #(internalize-anchor db %)))))

(defn lookup-subjects
  ([db query]
   (lookup-subjects db nil query))
  ([db cache-stamp-or-opts query]
   (engine/lookup
    (indexed-backend db cache-stamp-or-opts)
    engine/reverse-direction
    get-permission-paths
    (update query :resource #(internalize-anchor db %)))))

(defn count-resources
  ([db query]
   (count-resources db nil query))
  ([db cache-stamp-or-opts query]
   (engine/count-results
    (indexed-backend db cache-stamp-or-opts)
    engine/forward-direction
    get-permission-paths
    (update query :subject #(internalize-anchor db %))
    :resource)))

(defn count-subjects
  ([db query]
   (count-subjects db nil query))
  ([db cache-stamp-or-opts query]
   (engine/count-results
    (indexed-backend db cache-stamp-or-opts)
    engine/reverse-direction
    get-permission-paths
    (update query :resource #(internalize-anchor db %))
    :subject)))
