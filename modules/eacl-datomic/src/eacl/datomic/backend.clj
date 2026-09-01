(ns eacl.datomic.backend
  "Datomic's storage-specific implementation of the shared v8 snapshot
  adapter. Authorization graph algorithms remain outside this namespace."
  (:require [datomic.api :as d]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.datomic.db :as ddb]
            [eacl.schema.expression-persistence :as expression-persistence])
  (:import [java.util.concurrent Future]))

(def adapter-capabilities
  {:cursor #{:forward :reverse :opaque :authenticated :encrypted}
   :cache-proofs #{:ordered-generations :snapshot-bound :database-visible}
   :runtime #{:clj}})

(def source-capabilities
  {:consistency #{:minimize-latency
                  :fully-consistent
                  :at-least-as-fresh
                  :at-exact-snapshot}
   :snapshots #{:current :historical}
   :source #{:stable-scope :source-lifecycle :native-revision :order-hint
             :exact-locator}
   :runtime #{:clj}})

(defn- db-revision
  "Returns the actual selected Datomic revision, including an as-of bound."
  [^datomic.Database db]
  (or (.asOfT db) (d/basis-t db)))

(defn basis-kind
  "Classifies one Datomic database value without touching an EACL runtime.

  This is structural classification only. It deliberately does not claim to
  distinguish a speculative `d/with` product from committed state; public
  EACL APIs never admit arbitrary native values."
  [db]
  (if-not (instance? datomic.Database db)
    :foreign-backend
    (cond
      (.isFiltered ^datomic.Database db) :filtered
      (.isHistory ^datomic.Database db) :history
      (some? (.sinceT ^datomic.Database db)) :since
      (some? (.asOfT ^datomic.Database db)) :as-of
      :else :ordinary)))

(defn database-source-scope
  "Returns the durable Datomic database identity carried by `db`."
  [db]
  (when (contains? #{:ordinary :as-of} (basis-kind db))
    {:source-id {:database-id (str (.id ^datomic.Database db))}
     :branch nil}))

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
  ([conn local-db requested-t timeout-ms phase]
   (await-basis-db
    conn local-db requested-t timeout-ms phase :selection))
  ([conn local-db requested-t timeout-ms phase failure-kind]
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
               (catch InterruptedException interrupt
                 (let [classified
                       (ex-info
                        "Starting targeted Datomic synchronization was interrupted."
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
                   (.interrupt (Thread/currentThread))
                   (throw classified)))
               (catch Exception failure
                 (case failure-kind
                   :freshness
                   (freshness-unavailable!
                    "Failed starting targeted Datomic synchronization."
                    {:reason :sync-failed
                     :phase phase
                     :requested-t requested-t
                     :observed-t local-t
                     :requested-order-hint requested-t
                     :observed-order-hint local-t
                     :timeout-ms timeout-ms
                     :cause failure})

                   :selection
                   (selection-failure!
                    "Failed starting targeted Datomic synchronization."
                    :retryable phase
                    {:requested-t requested-t
                     :observed-t local-t
                     :requested-order-hint requested-t
                     :observed-order-hint local-t
                     :timeout-ms timeout-ms}
                    failure))))]
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
              failure))))))))

(defn- capture-local-db
  [conn requested-t timeout-ms phase]
  (try
    (d/db conn)
    (catch Exception failure
      (selection-failure!
       "Failed capturing the local Datomic basis."
       :retryable phase
       {:requested-t requested-t
        :requested-order-hint requested-t
        :timeout-ms timeout-ms}
       failure))))

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

(defn- public-instance-field
  [value field-name]
  (try
    (let [field (.getField (class value) field-name)]
      (.get field value))
    (catch Exception _ nil)))

(defn connection-source-id
  "Returns Datomic's durable database identity without calling `d/db`.

  Peer connections expose `db_id`; local connections expose their resident
  database reference. The fallback is deliberately rejected rather than
  manufacturing a process-local identity that would make cross-process tokens
  unsound."
  [conn]
  (let [db-id
        (or (public-instance-field conn "db_id")
            (when-let [db-ref (public-instance-field conn "db_ref")]
              (let [db @db-ref]
                (.id ^datomic.Database db))))]
    (if (some? db-id)
      {:database-id (str db-id)}
      (throw
       (ex-info
        "Datomic connection does not expose a stable database identity."
        {:type :eacl/unsupported-topology
         :eacl/error :eacl/unsupported-topology
         :backend :datomic
         :required :stable-connection-database-id
         :connection-class (some-> conn class str)})))))

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
       (mapcat expression-persistence/union-compatible-entity-definitions)
       vec))

(defn- ordered-generation-frame
  [db relation-ids]
  (mapv
   (fn [relation-id]
     [relation-id
      (some-> (first (d/datoms db :eavt relation-id
                               :eacl/relation-version))
              :tx
              d/tx->t)])
   relation-ids))

(defn- certified-schema-generation
  [db]
  (when (d/entid db :eacl/schema-version)
    (some-> (first (d/datoms db :avet :eacl/schema-version))
            :tx
            d/tx->t)))

(def adapter-config-keys
  #{:entid->object-id :object-id->entid :object-eid-fn
    :subject->resources-fn :resource->subjects-fn
    :adapter-fingerprint :adapter-deterministic? :identity-contract})

(defn basis-adapter
  "Creates the connection-free basis adapter used by the shared pipeline."
  [db {:keys [entid->object-id object-id->entid object-eid-fn
              subject->resources-fn
              resource->subjects-fn adapter-fingerprint
              adapter-deterministic? identity-contract]
       :as config}]
  (backend/validate-adapter-config! :datomic adapter-config-keys config)
  (let [external-id
        (or entid->object-id
            (fn [snapshot eid]
              (:eacl/id (d/entity snapshot eid))))]
    (backend/make-adapter
     {:id :datomic
      :traversal-execution backend/strict-sequential-traversal-execution
      :fingerprint adapter-fingerprint
      :deterministic? adapter-deterministic?
      :identity-contract
      (or identity-contract
          :selected-internal/current-external-injective-v2)
      :capabilities adapter-capabilities
      :state {:db db}
      :operations
      {:snapshot-id
       (fn []
         {:database-id (str (.id ^datomic.Database db))
          :basis-t (db-revision db)})
       :basis-kind (fn [] (basis-kind db))
       :native-revision
       (fn []
         {:revision (db-revision db)
          :exact-locator (db-revision db)})
       :order-hint (fn [] (db-revision db))
       :exact-locator (fn [] (db-revision db))
       :schema-generation (fn [] (certified-schema-generation db))
       :object-id->internal
       (fn [object-id]
         ;; Shared orchestration uses internal numeric eids in cache-normalized
         ;; engine requests, while permission-tree expansion resolves a public
         ;; id directly through this operation. Preserve Datomic's historical
         ;; numeric-eid convention before invoking the configurable public-id
         ;; resolver; otherwise an already-resolved eid is encoded a second
         ;; time (for example as [:eacl/id 1759]) and every point/list read
         ;; becomes a false negative.
         (if (number? object-id)
           (d/entid db object-id)
           ((or object-id->entid object-eid-fn ddb/object-eid)
            db object-id)))
       :internal-id->object (fn [internal-id] (external-id db internal-id))
       :relation-defs
       (fn [resource-type relation-name]
         (relation-defs db resource-type relation-name))
       :permission-defs
       (fn [resource-type permission-name]
         (permission-defs db resource-type permission-name))
       :permission-expression
       (fn [resource-type permission-name]
         (expression-persistence/validated-expression-entity
          (ddb/find-permission-defs db resource-type permission-name)))
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
       :all-permission-nodes (fn [] (ddb/all-permission-nodes db))
       :proof-frame
       (fn [relation-ids]
         (ordered-generation-frame db relation-ids))}})))

(defn- await-sync!
  [waiter timeout-ms phase requested-t]
  (try
    (let [selected (deref waiter timeout-ms sync-timeout-marker)]
      (when (identical? sync-timeout-marker selected)
        (cancel-waiter! waiter)
        (freshness-unavailable!
         "Timed out selecting a Datomic basis."
         {:reason :freshness-timeout
          :phase phase
          :requested-order-hint requested-t
          :timeout-ms timeout-ms}))
      (when (and requested-t (< (d/basis-t selected) requested-t))
        (freshness-unavailable!
         "Datomic synchronization returned below the requested basis."
         {:reason :head-behind
          :phase phase
          :requested-order-hint requested-t
          :observed-order-hint (d/basis-t selected)
          :timeout-ms timeout-ms}))
      selected)
    (catch InterruptedException interrupt
      (cancel-waiter! waiter)
      (.interrupt (Thread/currentThread))
      (selection-failure!
       "Datomic basis selection was interrupted."
       :cancelled phase
       {:requested-order-hint requested-t
        :timeout-ms timeout-ms}
       interrupt))
    (catch clojure.lang.ExceptionInfo error
      (throw error))
    (catch Exception error
      (selection-failure!
       "Datomic basis selection failed."
       :retryable phase
       {:requested-order-hint requested-t
        :timeout-ms timeout-ms}
       error))))

(defn- start-sync!
  [start phase requested-t failure-kind]
  (try
    (start)
    (catch InterruptedException interrupt
      (.interrupt (Thread/currentThread))
      (selection-failure!
       "Starting Datomic basis selection was interrupted."
       :cancelled phase
       {:requested-order-hint requested-t}
       interrupt))
    (catch Exception error
      (case failure-kind
        :freshness
        (freshness-unavailable!
         "Failed starting Datomic synchronization."
         {:reason :sync-failed
          :phase phase
          :requested-order-hint requested-t
          :cause error})

        :selection
        (selection-failure!
         "Failed starting Datomic exact-basis synchronization."
         :retryable phase
         {:requested-order-hint requested-t}
         error)))))

(defn source
  "Builds a static borrowed Datomic basis source.

  Construction reads only the connection's durable database id. At-least and
  exact selection capture one local Peer DB and start one targeted `d/sync T`
  only when that immutable observation is below the authenticated floor."
  [conn opts]
  (let [source-scope
        {:source-id (connection-source-id conn) :branch nil}
        source-lifecycle
        (fn []
          (or (some-> (:runtime-lifecycle-state opts)
                      deref
                      :source-lifecycle)
              (some-> (:source-lifecycle-state opts) deref)
              (:source-lifecycle opts)))
        adapter-options (select-keys opts adapter-config-keys)
        borrowed
        (fn [db]
          {:adapter (basis-adapter db adapter-options)
           :ownership :borrowed
           :release-token nil})
        timeout
        (fn [timeout-ms] (or timeout-ms 30000))]
    (source/make-source
     {:id :datomic
      :capabilities source-capabilities
      :traversal-execution backend/strict-sequential-traversal-execution
      :topology {:deployment :embedded-or-peer
                 :snapshot-values :immutable}
      :execution-constraints source/default-execution-constraints
      :basis-ownership :borrowed
      :fingerprint (:adapter-fingerprint opts)
      :deterministic? (:adapter-deterministic? opts)
      :operations
      {:source-scope (constantly source-scope)
       :source-lifecycle source-lifecycle
       :acquire-current! #(borrowed (d/db conn))
       :acquire-authoritative!
       (fn [timeout-ms]
         (let [timeout-ms (timeout timeout-ms)]
           (borrowed
            (await-sync! (start-sync! #(d/sync conn)
                                      :authoritative-sync nil :freshness)
                         timeout-ms
                         :authoritative-sync nil))))
       :acquire-at-least!
       (fn [token-data timeout-ms]
         (let [requested (:revision token-data)
               timeout-ms (timeout timeout-ms)
               local-db
               (capture-local-db
                conn requested timeout-ms :at-least-sync)]
           (borrowed
            (await-basis-db
             conn local-db requested timeout-ms :at-least-sync :freshness))))
       :acquire-exact!
       (fn [token-data timeout-ms]
         (validate-exact-token! token-data)
         (let [locator (:exact-locator token-data)
               timeout-ms (timeout timeout-ms)
               local-db
               (capture-local-db conn locator timeout-ms :exact-sync)
               caught-up
               (await-basis-db
                conn local-db locator timeout-ms :exact-sync)
               exact-db
               (try
                 (d/as-of caught-up locator)
                 (catch Exception failure
                   (selection-failure!
                    "Failed reconstructing the exact Datomic basis."
                    :retryable :exact-as-of
                    {:requested-t locator
                     :observed-t (d/basis-t caught-up)
                     :requested-order-hint locator
                     :observed-order-hint (d/basis-t caught-up)
                     :timeout-ms timeout-ms}
                    failure)))]
           ;; `db-revision` of the as-of view returns the requested locator
           ;; verbatim, so it cannot witness anything. The reachable
           ;; divergence is a synchronized head that still sits below the
           ;; requested basis: the as-of window would then silently show an
           ;; older database while claiming the exact locator.
           (when (< (d/basis-t caught-up) locator)
             (throw
              (ex-info
               "Datomic exact locator resolved to another basis."
               {:type :eacl.consistency/history-divergence
                :eacl/error :eacl.consistency/history-divergence
                :requested locator
                :selected (d/basis-t caught-up)})))
           (borrowed exact-db)))
       :release! (constantly nil)}})))
