(ns eacl.datahike.backend
  "Datahike storage operations for the shared v8 authorization engine."
  (:require [datahike.api :as d]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as impl]
            [eacl.datahike.schema :as schema])
  (:import [datahike.db AsOfDB DB FilteredDB HistoricalDB SinceDB]
           [java.util UUID]))

(def adapter-capabilities
  {:cursor #{:forward :reverse :opaque :authenticated :encrypted}
   :cache-proofs #{:ordered-generations :snapshot-bound :database-visible}
   :runtime #{:clj}})

(def source-capabilities
  {:consistency #{:minimize-latency
                  :fully-consistent
                  :at-least-as-fresh
                  :at-exact-snapshot}
   :snapshots #{:current :authoritative :causal}
   :source #{:stable-scope :source-lifecycle :native-revision :order-hint}
   :runtime #{:clj}})

(def ^:private db-config ddb/db-config)
(def ^:private direct-writer? ddb/direct-writer?)

(defn- exact-commits?
  [db]
  (not (false? (get (db-config db) :commit-graph? true))))

(defn- temporal-history?
  [db]
  (true? (:keep-history? (db-config db))))

(defn- store-identity
  "The bounded public identity of the store: backend and id only. The rest
  of the store map is connection configuration (paths, endpoints,
  credentials for jdbc/s3 stores) and must not leak into snapshot ids,
  cache bases, or cursor digests."
  [db]
  (let [config (db-config db)
        {:keys [backend id]} (:store config)
        id (if (= :memory backend)
             (get config schema/live-source-id-key)
             id)]
    {:backend backend :id (some-> id str)}))

(defn- connection-live-source-id
  "Returns one random identity for the lifetime of a memory connection.

  EACL-created connections carry it in their non-durable runtime config so
  their immutable DB values can be admitted as direct snapshots. For an
  externally created memory connection, attach it to the connection's private
  listener carrier; direct DB values from that unsupported construction remain
  unkeyable rather than falling back to a caller-supplied store id."
  [conn db]
  (or (get (db-config db) schema/live-source-id-key)
      (when-let [carrier (:listeners (meta conn))]
        (or (::live-source-id (meta carrier))
            (::live-source-id
             (alter-meta!
              carrier
              (fn [metadata]
                (if (::live-source-id metadata)
                  metadata
                  (assoc metadata ::live-source-id (random-uuid))))))))))

(defn basis-kind
  "Classifies one Datahike database value without touching an EACL runtime."
  [db]
  (cond
    (instance? AsOfDB db) :as-of
    (instance? FilteredDB db) :filtered
    (instance? SinceDB db) :since
    (instance? HistoricalDB db) :history
    (instance? DB db) :ordinary
    :else :foreign-backend))

(defn database-source-scope
  "Returns the durable store and branch identity carried by `db`."
  [db]
  (when (contains? #{:ordinary :as-of} (basis-kind db))
    (let [{:keys [backend id]} (store-identity db)]
      (when id
        {:source-id {:store-backend backend :store-id id}
         :branch (:branch (db-config db))}))))

(defn- exact-reconstruction?
  [db]
  (or (exact-commits? db)
      (temporal-history? db)))

(defn- selection-failure!
  [message phase token-data cause]
  (throw
   (ex-info
    message
    {:type :eacl.basis/selection-failure
     :eacl/error :eacl.basis/selection-failure
     :classification :retryable
     :phase phase
     :requested-revision (:revision token-data)
     :requested-exact-locator (:exact-locator token-data)}
    cause)))

(defn- missing-commit-error?
  [error]
  (some #{:not-found :missing-node}
        [(:type (ex-data error)) (:error (ex-data error))]))

(defn- load-exact-commit
  [conn locator token-data]
  (when locator
    (try
      (d/commit-as-db conn (UUID/fromString locator))
      ;; A locator from another backend format is absence for the conditional
      ;; commit path. Temporal history may still reconstruct by revision.
      (catch IllegalArgumentException _
        nil)
      (catch InterruptedException interrupt
        (.interrupt (Thread/currentThread))
        (throw
         (ex-info
          "Datahike exact commit selection was interrupted."
          {:type :eacl.basis/selection-failure
           :eacl/error :eacl.basis/selection-failure
           :classification :cancelled
           :phase :exact-commit
           :requested-revision (:revision token-data)
           :requested-exact-locator locator}
          interrupt)))
      (catch clojure.lang.ExceptionInfo info
        (if (missing-commit-error? info)
          nil
          (selection-failure!
           "Datahike exact commit selection failed."
           :exact-commit token-data info)))
      (catch Exception failure
        (selection-failure!
         "Datahike exact commit selection failed."
         :exact-commit token-data failure)))))

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
  (mapv
   (fn [relation-id]
     [relation-id
      (some-> (first (ddb/eavt-datoms
                      db relation-id :eacl/relation-version))
              :tx)])
   relation-ids))

(defn- certified-schema-generation
  [db]
  (when (ddb/entid db :eacl/schema-generation)
    (some-> (first (ddb/avet-datoms db :eacl/schema-generation))
            :tx)))

(def adapter-config-keys
  #{:object-id->entid :entid->object-id
    :selected-order-hint :selected-exact-locator
    :adapter-fingerprint :adapter-deterministic? :identity-contract})

(defn basis-adapter
  "Creates a v8 adapter bound to one immutable Datahike db value."
  [db {:keys [object-id->entid entid->object-id
              selected-order-hint selected-exact-locator]
       :as opts}]
  (backend/validate-adapter-config! :datahike adapter-config-keys opts)
  (backend/make-adapter
     {:id :datahike
      :traversal-execution backend/strict-sequential-traversal-execution
      :fingerprint (:adapter-fingerprint opts)
      :deterministic? (:adapter-deterministic? opts)
      :identity-contract
      (:identity-contract opts
                          :selected-internal/current-external-injective-v2)
      :capabilities adapter-capabilities
      :state {:db db
              :commit-id (commit-locator db)
              :parent-commit-ids (parent-locators db)}
      :operations
      {:snapshot-id
       (fn []
         {:database-id {:store (store-identity db)}
          :attribute-refs? (boolean
                            (:attribute-refs? (db-config db)))
          :basis-t (or (db-revision db) selected-order-hint)})

       :basis-kind
       (fn [] (basis-kind db))

       :native-revision
       (fn []
         {:revision (or (db-revision db) selected-order-hint)
          :exact-locator (or (commit-locator db)
                             selected-exact-locator)})

       :order-hint (fn [] (or (db-revision db) selected-order-hint))

       :schema-generation
       (fn []
         (certified-schema-generation db))

       :exact-locator
       (fn [] (or (commit-locator db) selected-exact-locator))

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

       :all-permission-nodes
       (fn []
         (->> (ddb/avet-datoms db schema/permission-key-attr)
              (map :v)
              set))

       :proof-frame
       (fn [relation-ids]
         (ordered-generation-frame db relation-ids))}}))

(defn source
  "Builds the borrowed immutable-basis source for one Datahike conn."
  [conn opts]
  (let [;; Dereferencing a Datahike connection reads its already-resident
        ;; immutable value without a branch-head store operation. Construction
        ;; consumes only configuration needed for the source's static profile.
        static-db @conn
        {:keys [backend id]} (store-identity static-db)
        id (if (= :memory backend)
             (some-> (connection-live-source-id conn static-db) str)
             id)
        source-scope
        {:source-id {:store-backend backend :store-id id}
         :branch (:branch (db-config static-db))}
        source-lifecycle
        (fn []
          (or (some-> (:source-lifecycle-state opts) deref)
              (:source-lifecycle opts)))
        adapter-options (select-keys opts adapter-config-keys)
        borrowed
        (fn [db token-data]
          {:adapter
           (basis-adapter
            db
            (cond-> adapter-options
              token-data
              (assoc :selected-order-hint (:revision token-data)
                     :selected-exact-locator
                     (:exact-locator token-data))))
           :ownership :borrowed
           :release-token nil})
        effective-source-capabilities
        (cond-> source-capabilities
          (exact-reconstruction? static-db)
          (update :snapshots conj :exact)

          (temporal-history? static-db)
          (update :snapshots conj :durable-history)

          (exact-commits? static-db)
          (update :snapshots conj :conditional-exact)

          (not (direct-writer? static-db))
          (update :consistency disj :fully-consistent)

          (not (exact-reconstruction? static-db))
          (update :consistency disj :at-exact-snapshot))]
    (source/make-source
     {:id :datahike
      :capabilities effective-source-capabilities
      :traversal-execution backend/strict-sequential-traversal-execution
      :topology {:deployment :embedded
                 :snapshot-values :immutable}
      :execution-constraints source/default-execution-constraints
      :basis-ownership :borrowed
      :fingerprint (:adapter-fingerprint opts)
      :deterministic? (:adapter-deterministic? opts)
      :operations
      {:source-scope (constantly source-scope)
       :source-lifecycle source-lifecycle
       :acquire-current! #(borrowed (d/db conn) nil)
       :acquire-authoritative!
       (fn [_timeout-ms]
         (when-not (direct-writer? static-db)
           (throw
            (ex-info
             "Datahike source has no authoritative branch-head barrier."
             {:type :eacl/unsupported-capability
              :eacl/error :eacl/unsupported-capability
              :backend :datahike
              :capability :consistency
              :requested :fully-consistent})))
         (borrowed (d/db conn) nil))
       :acquire-at-least!
       (fn [token-data timeout-ms]
         (borrowed
          (await-revision-db conn nil token-data timeout-ms) token-data))
       :acquire-exact!
       (fn [token-data _timeout-ms]
         (let [commit-db
               (when (and (exact-commits? static-db)
                          (:exact-locator token-data))
                 (load-exact-commit
                  conn (:exact-locator token-data) token-data))
               temporal-db
               (when (and (nil? commit-db)
                          (temporal-history? static-db)
                          (integer? (:revision token-data)))
                 (try
                   (let [current (d/db conn)]
                     (when (<= (:revision token-data) (:max-tx current))
                       (d/as-of current (:revision token-data))))
                   (catch InterruptedException interrupt
                     (.interrupt (Thread/currentThread))
                     (throw
                      (ex-info
                       "Datahike temporal reconstruction was interrupted."
                       {:type :eacl.basis/selection-failure
                        :eacl/error :eacl.basis/selection-failure
                        :classification :cancelled
                        :phase :exact-temporal
                        :requested-revision (:revision token-data)}
                       interrupt)))
                   (catch Exception failure
                     (selection-failure!
                      "Datahike temporal reconstruction failed."
                      :exact-temporal token-data failure))))
               selected-db (or commit-db temporal-db)]
           (if selected-db
             (borrowed selected-db token-data)
             (throw
              (ex-info
               "The requested Datahike snapshot is unavailable."
               {:type :eacl.consistency/exact-snapshot-unavailable
                :eacl/error :eacl.consistency/exact-snapshot-unavailable
                :backend :datahike})))))
       :release! (constantly nil)}})))
