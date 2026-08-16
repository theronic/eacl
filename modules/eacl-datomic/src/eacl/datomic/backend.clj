(ns eacl.datomic.backend
  "Datomic's storage-specific implementation of the shared v8 snapshot
  adapter. Authorization graph algorithms remain outside this namespace."
  (:require [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.datomic.db :as ddb])
  (:import [java.util UUID]))

(def capabilities
  {:consistency #{:minimize-latency
                  :fully-consistent
                  :at-least-as-fresh
                  :at-exact-snapshot}
   :snapshots #{:current :historical}
   :source #{:stable-scope :source-lifecycle :native-revision :order-hint
             :exact-locator}
   :cursor #{:forward :reverse :opaque :authenticated :encrypted}
   :transactions #{:schema :relationships :object-deletion}
   :cache-proofs #{:ordered-generations :snapshot-bound :database-visible}
   :runtime #{:clj}})

(defn- db-revision
  "Returns the actual selected Datomic revision, including an as-of bound."
  [^datomic.Database db]
  (or (.asOfT db) (d/basis-t db)))

(defn- relation-defs
  [db resource-type relation-name]
  (mapv (fn [datom]
          {:relation-id (:e datom)
           :resource-type resource-type
           :relation-name relation-name
           :subject-type (nth (:v datom) 2)})
        (ddb/relation-datoms db resource-type relation-name)))

(defn- permission-defs
  [db resource-type permission-name]
  (->> (ddb/find-permission-defs
        db resource-type permission-name)
       (mapv
        (fn [permission]
          {:permission-id (:db/id permission)
           :resource-type (:eacl.permission/resource-type permission)
           :permission-name (:eacl.permission/permission-name permission)
           :source-relation-name
           (:eacl.permission/source-relation-name permission)
           :target-type (:eacl.permission/target-type permission)
           :target-name (:eacl.permission/target-name permission)}))))

(defn- ordered-generation-frame
  [db relation-ids]
  {:schema-stamp
   (when-let [schema-eid (d/entid db [:eacl/id "schema-string"])]
     (some-> (first (d/datoms db :eavt schema-eid
                              :eacl/schema-version))
             :tx))
   :relation-stamps
   (mapv
    (fn [relation-id]
      [relation-id
       (some-> (first (d/datoms db :eavt relation-id
                                :eacl/relation-version))
               :tx)])
    relation-ids)})

(defn snapshot-adapter
  "Creates an adapter bound to one immutable Datomic db value. Proof and scan
  operations therefore cannot accidentally observe a different basis."
  ([db]
   (snapshot-adapter db {}))
  ([db {:keys [entid->object-id
               object-eid-fn subject->resources-fn
               resource->subjects-fn conn
               database-id]
        :as opts}]
   (let [external-id
         (or entid->object-id
             (fn [snapshot eid]
               (:eacl/id (d/entity snapshot eid))))
         source-lifecycle
         (or (some-> (:source-lifecycle-state opts) deref)
             (:source-lifecycle opts)
             (str (UUID/randomUUID)))
         source-scope
         (or (:source-scope opts)
             {:source-id
              {:database-id
               (or database-id (str (.id ^datomic.Database db)))}
              :branch nil})
         opts' (-> opts
                   (dissoc :source-lifecycle-state)
                   (assoc :source-lifecycle source-lifecycle
                          :source-scope source-scope))]
     (backend/make-adapter
      {:id :datomic
       :traversal-execution backend/strict-sequential-traversal-execution
       :fingerprint (:adapter-fingerprint opts)
       :deterministic? (:adapter-deterministic? opts)
       :identity-contract
       (:identity-contract opts
                           :selected-internal/current-external-injective-v2)
       :capabilities
       (cond-> capabilities
         (nil? conn)
         (update :consistency disj
                 :fully-consistent :at-least-as-fresh
                 :at-exact-snapshot))
       :state {:db db
               :opts opts}
       :operations
       {:snapshot-id
        (fn []
          {:database-id (str (.id ^datomic.Database db))
           :basis-t (db-revision db)})

        :source-scope
        (fn [] source-scope)

        :source-lifecycle
        (fn [] source-lifecycle)

        :native-revision
        (fn []
          {:revision (db-revision db)
           :exact-locator (db-revision db)})

        :order-hint
        (fn []
          (db-revision db))

        :select-current
        (fn []
          (snapshot-adapter (if conn (d/db conn) db) opts'))

        :select-authoritative
        (fn [timeout-ms]
          (try
            (let [selected
                  (if conn
                    (deref (d/sync conn)
                           (or timeout-ms 30000)
                           ::timeout)
                    db)]
              (when (= ::timeout selected)
                (throw
                 (ex-info
                  "Timed out establishing the Datomic authoritative head."
                  {:type :eacl.consistency/freshness-unavailable
                   :eacl/error :eacl.consistency/freshness-unavailable
                   :reason :freshness-timeout
                   :timeout-ms (or timeout-ms 30000)})))
              (snapshot-adapter selected opts'))
            (catch clojure.lang.ExceptionInfo error
              (if (= :eacl.consistency/freshness-unavailable
                     (:type (ex-data error)))
                (throw error)
                (throw
                 (ex-info
                  "Failed establishing the Datomic authoritative head."
                  {:type :eacl.consistency/freshness-unavailable
                   :eacl/error :eacl.consistency/freshness-unavailable
                   :reason :sync-failed
                   :timeout-ms (or timeout-ms 30000)}
                  error))))))

        :select-at-least
        (fn [token-data timeout-ms]
          (try
            (let [selected
                  (if conn
                    (deref (d/sync conn (:revision token-data))
                           (or timeout-ms 30000)
                           ::timeout)
                    db)
                  requested-order-hint (:revision token-data)]
              (when (= ::timeout selected)
                (throw
                 (ex-info
                  "Timed out waiting for the Datomic causal floor."
                  {:type :eacl.consistency/freshness-unavailable
                   :eacl/error :eacl.consistency/freshness-unavailable
                   :reason :freshness-timeout
                   :requested-order-hint (:revision token-data)
                   :timeout-ms (or timeout-ms 30000)})))
              ;; d/sync is specified to return a DB at least as new as the
              ;; requested basis. Check the postcondition anyway: adapters and
              ;; test doubles are not allowed to turn an order hint into an
              ;; unverified freshness claim.
              (when (and requested-order-hint
                         (< (d/basis-t selected) requested-order-hint))
                (throw
                 (ex-info
                  "The selected Datomic snapshot did not reach the causal floor."
                  {:type :eacl.consistency/freshness-unavailable
                   :eacl/error :eacl.consistency/freshness-unavailable
                   :reason :head-behind
                   :requested-order-hint requested-order-hint
                   :observed-order-hint (d/basis-t selected)
                   :timeout-ms (or timeout-ms 30000)})))
              (snapshot-adapter selected opts'))
            (catch clojure.lang.ExceptionInfo error
              (if (= :eacl.consistency/freshness-unavailable
                     (:type (ex-data error)))
                (throw error)
                (throw
                 (ex-info
                  "Failed waiting for the Datomic causal floor."
                  {:type :eacl.consistency/freshness-unavailable
                   :eacl/error :eacl.consistency/freshness-unavailable
                   :reason :sync-failed
                   :requested-order-hint (:revision token-data)
                   :timeout-ms (or timeout-ms 30000)}
                  error))))))

        :exact-locator
        (fn []
          (db-revision db))

        :select-exact
        (fn [token-data _timeout-ms]
          (let [locator (:exact-locator token-data)
                current (if conn (d/db conn) db)]
            (when (and (integer? locator)
                       (<= locator (d/basis-t current)))
              ;; Genuine unavailability (a future locator) is handled by the
              ;; guard above; nothing in this body legitimately throws for
              ;; trimmed history, so every Throwable here is a classified
              ;; failure — never a silent nil that would misreport a
              ;; transient fault as an expired snapshot.
              (try
                (snapshot-adapter
                 (d/as-of current locator)
                 (assoc opts'
                        :selected-order-hint locator
                        :selected-exact-locator locator))
                (catch InterruptedException interrupt
                  (throw (ex-info "Exact-basis selection was interrupted."
                                  {:type :eacl.basis/selection-failure
                                   :eacl/error :eacl.basis/selection-failure
                                   :classification :cancelled}
                                  interrupt)))
                (catch Throwable failure
                  (throw (ex-info "Exact-basis selection failed."
                                  {:type :eacl.basis/selection-failure
                                   :eacl/error :eacl.basis/selection-failure
                                   :classification :retryable
                                   :cause-class (.getName (class failure))}
                                  failure)))))))

        :object-id->internal
        (fn [object-id]
          ((or object-eid-fn ddb/object-eid) db object-id))

        :internal-id->object
        (fn [internal-id]
          (external-id db internal-id))

        :relation-defs
        (fn [resource-type relation-name]
          (relation-defs db resource-type relation-name))

        :permission-defs
        (fn [resource-type permission-name]
          (permission-defs db resource-type permission-name))

        :subject->resources
        (fn [subject-type subject-id relation-id resource-type scan-options]
          ((or subject->resources-fn ddb/subject->resources)
           db subject-type subject-id relation-id resource-type scan-options))

        :resource->subjects
        (fn [resource-type resource-id relation-id subject-type scan-options]
          ((or resource->subjects-fn ddb/resource->subjects)
           db resource-type resource-id relation-id subject-type scan-options))

        :direct-match?
        (fn [subject-type subject-id relation-id resource-type resource-id]
          (ddb/direct-match?
           db subject-type subject-id relation-id resource-type resource-id))

        :all-permission-nodes
        (fn []
          (ddb/all-permission-nodes db))

        :proof-frame
        (fn [relation-ids]
          (ordered-generation-frame db relation-ids))}}))))
