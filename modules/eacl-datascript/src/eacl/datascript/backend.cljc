(ns eacl.datascript.backend
  "DataScript storage operations for the shared v8 authorization engine."
  (:require [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.datascript.db :as ddb]
            [eacl.datascript.impl :as impl]
            [eacl.relationships.storage :as relationship-storage])
  #?(:clj (:import [java.util WeakHashMap])))

(defonce ^:private connection-source-ids
  ;; A DataScript source is one connection, not merely any DB whose numeric
  ;; :max-tx happens to match. Weak keys avoid retaining abandoned conns.
  #?(:clj (WeakHashMap.)
     :cljs (js/WeakMap.)))

(defn connection-source-id
  "Returns the process-local stable identity of one DataScript connection."
  [conn]
  (when conn
    #?(:clj
       (locking connection-source-ids
         (or (.get ^WeakHashMap connection-source-ids conn)
             (let [source-id (str (random-uuid))]
               (.put ^WeakHashMap connection-source-ids conn source-id)
               source-id)))
       :cljs
       (or (.get connection-source-ids conn)
           (let [source-id (str (random-uuid))]
             (.set connection-source-ids conn source-id)
             source-id)))))

(def capabilities
  {:consistency #{:minimize-latency
                  :fully-consistent
                  :at-least-as-fresh}
   :snapshots #{:current :authoritative :causal}
   :source #{:stable-scope :source-lifecycle :native-revision :order-hint}
   :cursor #{:forward :reverse :opaque}
   :transactions #{:schema :relationships :object-deletion}
   :cache-proofs #{:ordered-generations :snapshot-bound :database-visible}
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
   (when-let [schema-eid (ds/entid db [:eacl/id "schema-string"])]
     (some-> (first (ds/datoms db :eavt schema-eid
                                :eacl/schema-generation))
             :tx))
   :relation-stamps
   (mapv
    (fn [relation-id]
      [relation-id
       (some-> (first (ds/datoms db :eavt relation-id
                                  :eacl/relation-version))
               :tx)])
    relation-ids)})

(defn snapshot-adapter
  "Creates a v8 adapter bound to one immutable DataScript db value."
  [db {:keys [object-id->entid entid->object-id conn]
       :as opts}]
  (let [source-lifecycle
        (or (some-> (:source-lifecycle-state opts) deref)
            (:source-lifecycle opts)
            (str (random-uuid)))
        source-scope
        (or (:source-scope opts)
            {:source-id
             {:connection-id
              (or (:native-source-id opts) source-lifecycle)}
             :branch nil})
        opts' (-> opts
                  (dissoc :source-lifecycle-state)
                  (assoc :source-lifecycle source-lifecycle
                         :source-scope source-scope))]
    (backend/make-adapter
     {:id :datascript
      :fingerprint (:adapter-fingerprint opts)
      :deterministic? (:adapter-deterministic? opts)
      :identity-contract
      (:identity-contract opts
                          :selected-internal/current-external-v1)
      :capabilities
      (cond-> capabilities
        (nil? conn)
        (update :consistency disj
                :fully-consistent :at-least-as-fresh))
      :state {:db db}
      :operations
      {:snapshot-id
       (fn []
         {:database-id :datascript
          :basis-t (:max-tx db)})

       :source-scope
       (fn [] source-scope)

       :source-lifecycle
       (fn [] source-lifecycle)

       :native-revision
       (fn []
         {:revision (:max-tx db)
          :exact-locator nil})

       :order-hint (fn [] (:max-tx db))

       :select-current
       (fn []
         (snapshot-adapter db opts'))

       :select-authoritative
       (fn [_timeout-ms]
         (snapshot-adapter (if conn (ds/db conn) db) opts'))

       :select-at-least
       (fn [token-data timeout-ms]
         (snapshot-adapter
          (await-revision-db conn db token-data timeout-ms)
          opts'))

       :exact-locator
       (constantly nil)

       :select-exact
       (fn [_token-data _timeout-ms] nil)

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
          db subject-type subject-id relation-id resource-type resource-id))

       :relation-populated?
       (fn [subject-type relation-id resource-type]
         (boolean
          (first
           (ddb/avet-endpoint-prefix
            db
            relationship-storage/forward-attribute
            [subject-type relation-id resource-type]))))

       :all-permission-nodes
       (fn []
         (->> (ds/datoms
               db :avet :eacl.permission/resource-type+permission-name)
              (map :v)
              set))

       :proof-frame
       (fn [relation-ids]
         (ordered-generation-frame db relation-ids))}})))
