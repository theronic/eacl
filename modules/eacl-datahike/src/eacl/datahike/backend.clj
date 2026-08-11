(ns eacl.datahike.backend
  "Datahike storage operations for the shared v8 authorization engine."
  (:require [datahike.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as impl]
            [eacl.datahike.schema :as schema]
            [eacl.relationships.storage :as relationship-storage])
  (:import [java.util UUID]))

(def capabilities
  {:consistency #{:minimize-latency
                  :fully-consistent
                  :at-least-as-fresh
                  :at-exact-snapshot}
   :snapshots #{:current :authoritative :causal :exact}
   :source #{:stable-scope :source-lifecycle :native-revision :order-hint}
   :cursor #{:forward :reverse :opaque}
   :transactions #{:schema :relationships :object-deletion}
   :cache-proofs #{:ordered-generations :snapshot-bound :database-visible}
   :runtime #{:clj}})

(defn- direct-writer?
  [db]
  (= :self (get-in db [:config :writer :backend])))

(defn- exact-commits?
  [db]
  (not (false? (get-in db [:config :commit-graph?] true))))

(defn- temporal-history?
  [db]
  (true? (get-in db [:config :keep-history?])))

(defn- exact-reconstruction?
  [db]
  (or (exact-commits? db)
      (temporal-history? db)))

(defn- commit-locator
  [db]
  (some-> (get-in db [:meta :datahike/commit-id]) str))

(defn- parent-locators
  [db]
  (->> (get-in db [:meta :datahike/parents])
       (map str)
       sort
       vec))

(defn- db-revision
  "Returns the revision carried by an ordinary or temporal Datahike value."
  [db]
  (or (:time-point db) (:max-tx db)))

(defn- freshness-timeout!
  [token-data timeout-ms observed]
  (throw
   (ex-info
    "Datahike branch did not acquire the requested native revision."
    {:type :eacl.consistency/freshness-unavailable
     :eacl/error :eacl.consistency/freshness-unavailable
     :reason :freshness-timeout
     :requested-order-hint (:revision token-data)
     :observed-order-hint (:max-tx observed)
     :timeout-ms timeout-ms})))

(defn- await-revision-db
  [conn fallback token-data timeout-ms]
  (let [timeout-ms (or timeout-ms 30000)
        deadline (+ (System/nanoTime)
                    (* 1000000 timeout-ms))]
    (loop []
      (let [candidate (if conn (d/db conn) fallback)]
        (cond
          (>= (:max-tx candidate) (:revision token-data))
          candidate

          (>= (System/nanoTime) deadline)
          (freshness-timeout! token-data timeout-ms candidate)

          :else
          (do
            (Thread/sleep 2)
            (recur)))))))

(defn- normalized-permission
  [permission]
  {:permission-id (:db/id permission)
   :resource-type (:eacl.permission/resource-type permission)
   :permission-name (:eacl.permission/permission-name permission)
   :source-relation-name
   (:eacl.permission/source-relation-name permission)
   :target-type (:eacl.permission/target-type permission)
   :target-name (:eacl.permission/target-name permission)})

(defn- ordered-generation-frame
  [db relation-ids]
  {:schema-stamp
   (when-let [schema-eid (ddb/entid db [:eacl/id "schema-string"])]
     (some-> (first (ddb/eavt-datoms
                     db schema-eid :eacl/schema-generation))
             :tx))
   :relation-stamps
   (mapv
    (fn [relation-id]
      [relation-id
       (some-> (first (ddb/eavt-datoms
                       db relation-id :eacl/relation-version))
               :tx)])
    relation-ids)})

(defn snapshot-adapter
  "Creates a v8 adapter bound to one immutable Datahike db value."
  [db {:keys [object-id->entid entid->object-id conn
              selected-order-hint selected-exact-locator]
       :as opts}]
  (let [source-lifecycle
        (or (some-> (:source-lifecycle-state opts) deref)
            (:source-lifecycle opts)
            (str (UUID/randomUUID)))
        source-scope
        (or (:source-scope opts)
            (let [{:keys [backend id]} (get-in db [:config :store])]
              {:source-id
               {:store-backend backend
                :store-id (str id)}
               :branch (get-in db [:config :branch])}))
        opts' (-> opts
                  (dissoc :source-lifecycle-state)
                  (assoc :source-lifecycle source-lifecycle
                         :source-scope source-scope))]
    (backend/make-adapter
     {:id :datahike
      :fingerprint (:adapter-fingerprint opts)
      :deterministic? (:adapter-deterministic? opts)
      :identity-contract
      (:identity-contract opts
                          :selected-internal/current-external-v1)
      :capabilities
      (cond-> capabilities
        (or (nil? conn)
            (not (direct-writer? db)))
        (update :consistency disj :fully-consistent)

        (nil? conn)
        (update :consistency disj :at-least-as-fresh)

        (or (nil? conn)
            (not (exact-reconstruction? db)))
        (update :consistency disj :at-exact-snapshot))
      :state {:db db
              :commit-id (commit-locator db)
              :parent-commit-ids (parent-locators db)}
      :operations
      {:snapshot-id
       (fn []
         {:database-id
          {:store
           (update (:store (:config db)) :id str)}
          :attribute-refs? (boolean
                            (:attribute-refs? (:config db)))
          :basis-t (or (db-revision db) selected-order-hint)})

       :source-scope
       (fn [] source-scope)

       :source-lifecycle
       (fn [] source-lifecycle)

       :native-revision
       (fn []
         {:revision (or (db-revision db) selected-order-hint)
          :exact-locator (or (commit-locator db)
                             selected-exact-locator)})

       :order-hint (fn [] (or (db-revision db) selected-order-hint))

       :select-current
       (fn []
         (snapshot-adapter (if conn (d/db conn) db) opts'))

       :select-authoritative
       (fn [_timeout-ms]
         (when-not (direct-writer? db)
           (throw
            (ex-info
             "Datahike source has no authoritative branch-head barrier."
             {:type :eacl/unsupported-capability
              :eacl/error :eacl/unsupported-capability
              :backend :datahike
              :capability :consistency
              :requested :fully-consistent})))
         (snapshot-adapter (if conn (d/db conn) db) opts'))

       :select-at-least
       (fn [token-data timeout-ms]
         (snapshot-adapter
          (await-revision-db conn db token-data timeout-ms)
          opts'))

       :exact-locator
       (fn [] (or (commit-locator db) selected-exact-locator))

       :select-exact
       (fn [token-data _timeout-ms]
         (when (and conn
                    (or (:exact-locator token-data)
                        (and (temporal-history? db)
                             (integer? (:revision token-data)))))
           (try
             (let [commit-db
                   (when (and (exact-commits? db)
                              (:exact-locator token-data))
                     (d/commit-as-db
                      conn
                      (UUID/fromString
                       (:exact-locator token-data))))
                   temporal-db
                   (when (and (nil? commit-db)
                              (temporal-history? db)
                              (integer? (:revision token-data))
                              (<= (:revision token-data)
                                  (:max-tx (d/db conn))))
                     (d/as-of (d/db conn)
                              (:revision token-data)))]
               (when-let [selected-db (or commit-db temporal-db)]
                 (snapshot-adapter
                  selected-db
                  (assoc opts'
                         :selected-order-hint (:revision token-data)
                         :selected-exact-locator
                         (:exact-locator token-data)))))
             (catch Throwable _
               nil))))

       :object-id->internal
       (fn [object-id]
         (if (number? object-id)
           object-id
           (object-id->entid db object-id)))

       :internal-id->object
       (fn [internal-id]
         (entid->object-id db internal-id))

       :relation-defs
       (fn [resource-type relation-name]
         (mapv (fn [{:keys [e v]}]
                 {:relation-id e
                  :resource-type resource-type
                  :relation-name relation-name
                  :subject-type (nth v 2)})
               (impl/relation-datoms db resource-type relation-name)))

       :permission-defs
       (fn [resource-type permission-name]
         (mapv normalized-permission
               (impl/find-permission-defs
                db resource-type permission-name)))

       :subject->resources
       (fn [subject-type subject-id relation-id resource-type options]
         (impl/subject->resources
          db subject-type subject-id relation-id resource-type options))

       :resource->subjects
       (fn [resource-type resource-id relation-id subject-type options]
         (impl/resource->subjects
          db resource-type resource-id relation-id subject-type options))

       :direct-match?
       (fn [subject-type subject-id relation-id resource-type resource-id]
         (impl/direct-match?
          db subject-type subject-id relation-id
          resource-type resource-id))

       :relation-populated?
       (fn [subject-type relation-id resource-type]
         (boolean
          (first
           (ddb/avet-tuple-prefix
            db
            relationship-storage/forward-attribute
            4
            [subject-type relation-id resource-type]))))

       :all-permission-nodes
       (fn []
         (->> (ddb/avet-datoms db schema/permission-key-attr)
              (map :v)
              set))

       :proof-frame
       (fn [relation-ids]
         (ordered-generation-frame db relation-ids))}})))
