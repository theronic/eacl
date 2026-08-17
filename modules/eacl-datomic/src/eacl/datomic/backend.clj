(ns eacl.datomic.backend
  "Datomic's storage-specific implementation of the shared v8 snapshot
  adapter. Authorization graph algorithms remain outside this namespace."
  (:require [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.datomic.db :as ddb])
  (:import [java.util UUID]
           [java.util.concurrent Future]))

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

(defn- ordinary-view?
  "True when `db` is an ordinary current or as-of database value.

  `d/filter`, `d/since` and `d/history` views report the same database id and
  basis as the plain value they wrap, so nothing downstream can tell them apart
  by revision: they would mint the identical snapshot identity while answering
  different questions. Exact-snapshot consistency is therefore refused for
  them, which is also what keeps them out of the snapshot-exact cache tier. An
  as-of value is ordinary — it is precisely the exact view this backend
  selects."
  [^datomic.Database db]
  (and (not (.isFiltered db))
       (not (.isHistory db))
       (nil? (.sinceT db))))

(def ^:private sync-timeout-marker (Object.))

(defn- freshness-unavailable!
  [message data]
  (throw
   (ex-info
    message
    (assoc data
           :type :eacl.consistency/freshness-unavailable
           :eacl/error :eacl.consistency/freshness-unavailable))))

(defn- selection-failure!
  [message classification phase data cause]
  (throw
   (ex-info
    message
    (merge
     {:type :eacl.basis/selection-failure
      :eacl/error :eacl.basis/selection-failure
      :classification classification
      :phase phase}
     data)
    cause)))

(defn- cancel-waiter!
  [waiter]
  (when (instance? Future waiter)
    (.cancel ^Future waiter true))
  nil)

(defn await-basis-db
  "Returns one Datomic DB whose observed basis is at least `requested-t`.

  `local-db` is the request's single captured local observation. Synchronizes
  only when that value is behind, bounds the wait, verifies Datomic's promised
  postcondition, and owns cancellation of the returned future on timeout or
  interruption. Provider failures retain their cause and selection phase."
  [conn local-db requested-t timeout-ms phase]
  (let [timeout-ms (or timeout-ms 30000)
        local-t
        (try
          (d/basis-t local-db)
          (catch Exception failure
            (selection-failure!
             "Failed reading the local Datomic basis."
             :retryable phase
             {:requested-t requested-t
              :requested-order-hint requested-t
              :timeout-ms timeout-ms}
             failure)))]
    (if (<= requested-t local-t)
      local-db
      (let [waiter
            (try
              (d/sync conn requested-t)
              (catch Exception failure
                (selection-failure!
                 "Failed starting targeted Datomic synchronization."
                 :retryable phase
                 {:requested-t requested-t
                  :observed-t local-t
                  :requested-order-hint requested-t
                  :observed-order-hint local-t
                  :timeout-ms timeout-ms}
                 failure)))]
        (try
          (let [selected (deref waiter timeout-ms sync-timeout-marker)]
            (when (identical? sync-timeout-marker selected)
              (cancel-waiter! waiter)
              (freshness-unavailable!
               "Timed out waiting for the requested Datomic basis."
               {:reason :freshness-timeout
                :phase phase
                :requested-t requested-t
                :observed-t local-t
                :requested-order-hint requested-t
                :observed-order-hint local-t
                :timeout-ms timeout-ms}))
            (let [selected-t (d/basis-t selected)]
              (when (< selected-t requested-t)
                (freshness-unavailable!
                 "Targeted Datomic synchronization returned below the requested basis."
                 {:reason :head-behind
                  :phase phase
                  :requested-t requested-t
                  :observed-t selected-t
                  :requested-order-hint requested-t
                  :observed-order-hint selected-t
                  :timeout-ms timeout-ms}))
              selected))
          (catch InterruptedException interrupt
            (cancel-waiter! waiter)
            (let [classified
                  (ex-info
                   "Targeted Datomic synchronization was interrupted."
                   {:type :eacl.basis/selection-failure
                    :eacl/error :eacl.basis/selection-failure
                    :classification :cancelled
                    :phase phase
                    :requested-t requested-t
                    :observed-t local-t
                    :requested-order-hint requested-t
                    :observed-order-hint local-t
                    :timeout-ms timeout-ms}
                   interrupt)]
              ;; Set the flag after constructing the classified error so no
              ;; intervening provider/runtime work can consume it.
              (.interrupt (Thread/currentThread))
              (throw classified)))
          (catch clojure.lang.ExceptionInfo error
            (throw error))
          (catch Exception failure
            (selection-failure!
             "Targeted Datomic synchronization failed."
             :retryable phase
             {:requested-t requested-t
              :observed-t local-t
              :requested-order-hint requested-t
              :observed-order-hint local-t
              :timeout-ms timeout-ms}
             failure)))))))

(defn- validate-exact-token!
  [{:keys [revision exact-locator] :as token-data}]
  (when-not (and (integer? revision)
                 (not (neg? revision))
                 (integer? exact-locator)
                 (not (neg? exact-locator)))
    (throw
     (ex-info
      "Datomic exact selection requires a non-negative integer locator."
      {:type :eacl/invalid-zed-token
       :eacl/error :eacl/invalid-zed-token
       :reason :malformed
       :token-data token-data})))
  (when-not (= revision exact-locator)
    (throw
     (ex-info
      "Datomic token revision and exact locator contradict one another."
      {:type :eacl/invalid-zed-token
       :eacl/error :eacl/invalid-zed-token
       :reason :contradictory-native-revision
       :revision revision
       :exact-locator exact-locator})))
  token-data)

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
                 :at-exact-snapshot)

         (not (ordinary-view? db))
         (update :consistency disj :at-exact-snapshot))
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
          ;; The waiter is held outside the body so every exit path can cancel
          ;; it, while `d/sync` itself stays inside the try and keeps its
          ;; existing failure classification.
          (let [waiter (volatile! nil)]
            (try
              (let [selected
                    (if conn
                      (do (vreset! waiter (d/sync conn))
                          (deref @waiter (or timeout-ms 30000) ::timeout))
                      db)]
                (when (= ::timeout selected)
                  ;; EACL owns the future once it stops waiting: an abandoned
                  ;; sync stays registered on the connection until its basis
                  ;; arrives, which under retry traffic is unbounded.
                  (cancel-waiter! @waiter)
                  (throw
                   (ex-info
                    "Timed out establishing the Datomic authoritative head."
                    {:type :eacl.consistency/freshness-unavailable
                     :eacl/error :eacl.consistency/freshness-unavailable
                     :reason :freshness-timeout
                     :timeout-ms (or timeout-ms 30000)})))
                (snapshot-adapter selected opts'))
              (catch InterruptedException interrupt
                (cancel-waiter! @waiter)
                (let [classified
                      (ex-info
                       "Establishing the Datomic authoritative head was interrupted."
                       {:type :eacl.basis/selection-failure
                        :eacl/error :eacl.basis/selection-failure
                        :classification :cancelled
                        :phase :authoritative-sync
                        :timeout-ms (or timeout-ms 30000)}
                       interrupt)]
                  (.interrupt (Thread/currentThread))
                  (throw classified)))
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
                    error)))))))

        :select-at-least
        (fn [token-data timeout-ms]
          ;; The waiter is held outside the body so every exit path can cancel
          ;; it, while `d/sync` itself stays inside the try and keeps its
          ;; existing failure classification.
          (let [waiter (volatile! nil)]
            (try
              (let [selected
                    (if conn
                      (do (vreset! waiter (d/sync conn (:revision token-data)))
                          (deref @waiter (or timeout-ms 30000) ::timeout))
                      db)
                    requested-order-hint (:revision token-data)]
                (when (= ::timeout selected)
                  ;; EACL owns the future once it stops waiting: an abandoned
                  ;; sync stays registered on the connection until its basis
                  ;; arrives, which under retry traffic is unbounded.
                  (cancel-waiter! @waiter)
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
              (catch InterruptedException interrupt
                (cancel-waiter! @waiter)
                (let [classified
                      (ex-info
                       "Waiting for the Datomic causal floor was interrupted."
                       {:type :eacl.basis/selection-failure
                        :eacl/error :eacl.basis/selection-failure
                        :classification :cancelled
                        :phase :at-least-sync
                        :requested-order-hint (:revision token-data)
                        :timeout-ms (or timeout-ms 30000)}
                       interrupt)]
                  (.interrupt (Thread/currentThread))
                  (throw classified)))
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
                    error)))))))

        :exact-locator
        (fn []
          (db-revision db))

        :select-exact
        (fn [token-data timeout-ms]
          (validate-exact-token! token-data)
          (let [locator (:exact-locator token-data)
                current
                (try
                  (if conn (d/db conn) db)
                  (catch Exception failure
                    (selection-failure!
                     "Failed reading the local Datomic database."
                     :retryable :exact-local-read
                     {:requested-t locator
                      :requested-order-hint locator
                      :timeout-ms (or timeout-ms 30000)}
                     failure)))
                caught-up
                (await-basis-db
                 conn current locator timeout-ms :exact-sync)
                exact-db
                (try
                  (d/as-of caught-up locator)
                  (catch InterruptedException interrupt
                    (let [classified
                          (ex-info
                           "Exact Datomic reconstruction was interrupted."
                           {:type :eacl.basis/selection-failure
                            :eacl/error :eacl.basis/selection-failure
                            :classification :cancelled
                            :phase :exact-as-of
                            :requested-t locator
                            :requested-order-hint locator
                            :timeout-ms (or timeout-ms 30000)}
                           interrupt)]
                      (.interrupt (Thread/currentThread))
                      (throw classified)))
                  (catch Exception failure
                    (selection-failure!
                     "Exact Datomic reconstruction failed."
                     :retryable :exact-as-of
                     {:requested-t locator
                      :requested-order-hint locator
                      :observed-t (d/basis-t caught-up)
                      :observed-order-hint (d/basis-t caught-up)
                      :timeout-ms (or timeout-ms 30000)}
                     failure)))]
            (snapshot-adapter
             exact-db
             (assoc opts'
                    :selected-order-hint locator
                    :selected-exact-locator locator))))

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
