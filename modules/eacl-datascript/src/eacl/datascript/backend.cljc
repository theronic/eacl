(ns eacl.datascript.backend
  "DataScript storage operations for the shared v8 authorization engine."
  (:require [datascript.core :as ds]
            [datascript.db :as dsdb]
            [eacl.backend.source :as source]
            [eacl.schema.expression-persistence :as expression-persistence]
            [eacl.backend.v8 :as backend]
            [eacl.datascript.impl :as impl]))

(defn connection-source-id
  "Returns the process-local stable identity of one DataScript connection."
  [conn]
  (when conn
    (or (:eacl.datascript/source-id (meta conn))
        (:eacl.datascript/source-id
         (alter-meta!
          conn
          (fn [metadata]
            (if (:eacl.datascript/source-id metadata)
              metadata
              (assoc metadata
                     :eacl.datascript/source-id
                     (str (random-uuid))))))))))

(defn basis-kind
  "Classifies one DataScript database value without touching an EACL runtime.

  This is structural classification only. It cannot distinguish `db-with`
  products; public EACL APIs therefore never admit arbitrary native values."
  [db]
  (cond
    (not (dsdb/db? db)) :foreign-backend
    (identical? db (dsdb/unfiltered-db db)) :ordinary
    :else :filtered))

(defn database-source-scope
  "Returns the source identity materialized by `eacl.datascript/create-conn`."
  [db]
  (when (= :ordinary (basis-kind db))
    (when-let [source-id
               (:eacl.datascript/source-id
                (ds/entity db [:eacl/id "datascript-metadata"]))]
      {:source-id {:connection-id source-id}
       :branch nil})))

(def adapter-capabilities
  {:cursor #{:forward :reverse :opaque :authenticated :encrypted}
   :cache-proofs #{:ordered-generations :snapshot-bound :database-visible}
   :runtime #{:clj :cljs}})

(def source-capabilities
  {:consistency #{:minimize-latency
                  :fully-consistent
                  :at-least-as-fresh}
   :snapshots #{:current :authoritative :causal}
   :source #{:stable-scope :source-lifecycle :native-revision :order-hint}
   :runtime #{:clj :cljs}})

(defn- freshness-timeout!
  [token-data timeout-ms observed]
  (throw
   (ex-info
    "DataScript connection did not acquire the requested native revision."
    {:type :eacl.consistency/freshness-unavailable
     :eacl/error :eacl.consistency/freshness-unavailable
     :reason :freshness-timeout
     :requested-order-hint (:revision token-data)
     :observed-order-hint (:max-tx observed)
     :timeout-ms timeout-ms})))

(defn- await-revision-db
  [conn fallback token-data timeout-ms]
  (let [timeout-ms (or timeout-ms 30000)]
    #?(:clj
       (let [deadline (+ (System/nanoTime)
                         (* 1000000 timeout-ms))]
         (loop []
           (let [candidate (if conn (ds/db conn) fallback)]
             (cond
               (>= (:max-tx candidate) (:revision token-data))
               candidate

               (>= (System/nanoTime) deadline)
               (freshness-timeout!
                token-data timeout-ms candidate)

               :else
               (do
                 (Thread/sleep 2)
                 (recur))))))
       :cljs
       (let [candidate (if conn (ds/db conn) fallback)]
         (if (>= (:max-tx candidate) (:revision token-data))
           candidate
           ;; A synchronous browser API cannot yield to an asynchronous writer
           ;; while preserving this call's return type. It therefore reports
           ;; the unavailable floor immediately rather than busy-waiting and
           ;; pretending to provide replication.
           (freshness-timeout!
            token-data timeout-ms candidate))))))

(defn- normalized-permissions
  [permission]
  (expression-persistence/union-compatible-definitions
    (:db/id permission)
    (expression-persistence/decode-entity permission)))

(defn- permission-expression [db resource-type permission-name]
  (some-> (expression-persistence/validate-entities
           (impl/find-permission-defs db resource-type permission-name))
          first
          :entity))

(defn- ordered-generation-frame
  [db relation-ids]
  (mapv
   (fn [relation-id]
     [relation-id
      (some-> (first (ds/datoms db :eavt relation-id
                                 :eacl/relation-version))
              :tx)])
   relation-ids))

(defn- certified-schema-generation
  [db]
  (when (contains? (:schema db) :eacl/schema-generation)
    (some-> (first (ds/datoms db :avet :eacl/schema-generation))
            :tx)))

(def adapter-config-keys
  #{:object-id->entid :entid->object-id
    :adapter-fingerprint :adapter-deterministic? :identity-contract})

(defn basis-adapter
  "Creates a v8 adapter bound to one immutable DataScript db value."
  [db {:keys [object-id->entid entid->object-id]
       :as opts}]
  (backend/validate-adapter-config! :datascript adapter-config-keys opts)
  (backend/make-adapter
     {:id :datascript
      :traversal-execution backend/strict-sequential-traversal-execution
      :fingerprint (:adapter-fingerprint opts)
      :deterministic? (:adapter-deterministic? opts)
      :identity-contract
      (:identity-contract opts
                          :selected-internal/current-external-injective-v2)
      :capabilities adapter-capabilities
      :state {:db db}
      :operations
      {:snapshot-id
       (fn []
         {:database-id :datascript
          :basis-t (:max-tx db)})

       :basis-kind
       (fn [] (basis-kind db))

       :native-revision
       (fn []
         {:revision (:max-tx db)
          :exact-locator nil})

       :order-hint (fn [] (:max-tx db))

       :schema-generation
       (fn []
         (certified-schema-generation db))

       :exact-locator
       (constantly nil)

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
         (vec (mapcat normalized-permissions
                      (impl/find-permission-defs
                       db resource-type permission-name))))

       :permission-expression
       (fn [resource-type permission-name]
         (permission-expression db resource-type permission-name))

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
          db subject-type subject-id relation-id resource-type resource-id))

       :all-permission-nodes
       (fn []
         (->> (ds/datoms
               db :avet :eacl.permission/resource-type+permission-name)
              (map :v)
              set))

       :proof-frame
       (fn [relation-ids]
         (ordered-generation-frame db relation-ids))}}))

(defn source
  "Builds the borrowed immutable-basis source for one DataScript conn."
  [conn opts]
  (let [source-scope
        {:source-id {:connection-id (:native-source-id opts)}
         :branch nil}
        source-lifecycle
        (fn []
          (or (some-> (:runtime-lifecycle-state opts)
                      deref
                      :source-lifecycle)
              (some-> (:source-lifecycle-state opts) deref)
              (:source-lifecycle opts)))
        adapter-options (select-keys opts adapter-config-keys)
        adapter-cache (volatile! nil)
        borrowed
        (fn [db]
          (let [{cached-db :db cached-adapter :adapter} @adapter-cache
                adapter
                (if (identical? cached-db db)
                  cached-adapter
                  (let [created (basis-adapter db adapter-options)]
                    (vreset! adapter-cache {:db db :adapter created})
                    created))]
            {:adapter adapter
             :ownership :borrowed
             :release-token nil}))]
    (source/make-source
     {:id :datascript
      :capabilities source-capabilities
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
       :acquire-current! #(borrowed (ds/db conn))
       :acquire-authoritative! (fn [_timeout-ms]
                                 (borrowed (ds/db conn)))
       :acquire-at-least! (fn [token-data timeout-ms]
                            (borrowed
                             (await-revision-db
                              conn nil token-data timeout-ms)))
       :acquire-exact!
       (fn [_token-data _timeout-ms]
         (throw
          (ex-info
           "DataScript does not retain exact historical snapshots."
           {:type :eacl/unsupported-capability
            :eacl/error :eacl/unsupported-capability
            :backend :datascript
            :capability :consistency
            :requested :at-exact-snapshot})))
       :release! (constantly nil)}})))
