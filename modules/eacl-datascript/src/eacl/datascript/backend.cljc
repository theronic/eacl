(ns eacl.datascript.backend
  "DataScript storage operations for the shared v8 authorization engine."
  (:require [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.datascript.db :as ddb]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.mutation :as journal]
            [eacl.mutation :as mutation]
            [eacl.relationships.endpoint-pair :as endpoint-pair]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.secure-format :as secure]))

(def capabilities
  {:consistency #{:minimize-latency
                  :fully-consistent
                  :at-least-as-fresh
                  :at-exact-snapshot}
   :snapshots #{:current :authoritative :causal}
   :source #{:stable-scope :graph-head :anchor-membership :order-hint}
   :cursor #{:forward :reverse :opaque}
   :transactions #{:schema :relationships :object-deletion}
   :cache-proofs #{:schema :relations :snapshot-bound :database-visible}
   :runtime #{:clj :cljs}})

(defn- freshness-timeout!
  [token-data timeout-ms observed]
  (throw
   (ex-info
    "DataScript connection did not acquire the requested mutation anchor."
    {:type :eacl.consistency/freshness-unavailable
     :eacl/error :eacl.consistency/freshness-unavailable
     :reason :freshness-timeout
     :requested-order-hint (:order-hint token-data)
     :observed-order-hint (:max-tx observed)
     :timeout-ms timeout-ms})))

(defn- await-anchor-db
  [conn fallback token-data timeout-ms]
  (let [timeout-ms (or timeout-ms 30000)]
    #?(:clj
       (let [deadline (+ (System/nanoTime)
                         (* 1000000 timeout-ms))]
         (loop []
           (let [candidate (if conn (ds/db conn) fallback)]
             (cond
               (journal/contains-anchor?
                candidate (:graph-anchor token-data))
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
         (if (journal/contains-anchor?
              candidate (:graph-anchor token-data))
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

(defn- schema-proof-records
  [db {:keys [permission-nodes relation-ids] :as scope}]
  (let [{:keys [relation-defs permission-defs]}
        (impl/build-schema-catalog db)
        relation-ids (set relation-ids)
        scoped-relations
        (cond->> (mapcat identity (vals relation-defs))
          scope (filter #(contains? relation-ids (:relation-id %))))
        scoped-permissions
        (if scope
          (mapcat #(get permission-defs % []) permission-nodes)
          (mapcat identity (vals permission-defs)))]
    (concat
     (->> scoped-relations
          (map (fn [relation]
                 [:relation
                  (:relation-id relation)
                  (:resource-type relation)
                  (:relation-name relation)
                  (:subject-type relation)]))
          sort)
     (->> scoped-permissions
          (map normalized-permission)
          (map (fn [permission]
                 [:permission
                  (:permission-id permission)
                  (:resource-type permission)
                  (:permission-name permission)
                  (:source-relation-name permission)
                  (:target-type permission)
                  (:target-name permission)]))
          sort))))

(defn- content-schema-proof
  [db scope]
  {:content-digest
   (secure/canonical-records-digest
    "eacl/datascript/schema-content-proof/v3"
    (schema-proof-records db scope))})

(defn- content-relation-proof
  [db relation-ids external-id]
  (let [wanted (set relation-ids)
        forward
        (when (seq wanted)
          (for [{subject-eid :e value :v}
                (ddb/avet-datoms
                 db relationship-storage/forward-attribute)
                :let [decoded
                      (endpoint-pair/decode-forward subject-eid value)]
                :when (contains? wanted (:relation-eid decoded))]
            [:forward
             (:relation-eid decoded)
             (:subject-type decoded)
             subject-eid
             (external-id db subject-eid)
             (:resource-type decoded)
             (:resource-eid decoded)
             (external-id db (:resource-eid decoded))
             (count value)]))
        reverse
        (when (seq wanted)
          (for [{resource-eid :e value :v}
                (ddb/avet-datoms
                 db relationship-storage/reverse-attribute)
                :let [decoded
                      (endpoint-pair/decode-reverse resource-eid value)]
                :when (contains? wanted (:relation-eid decoded))]
            [:reverse
             (:relation-eid decoded)
             (:subject-type decoded)
             (:subject-eid decoded)
             (external-id db (:subject-eid decoded))
             (:resource-type decoded)
             resource-eid
             (external-id db resource-eid)
             (count value)]))]
    {:content-digest
     (secure/canonical-records-digest
      "eacl/datascript/relationship-content-proof/v3"
      (sort (concat forward reverse)))}))

(defn- mutation-schema-proof
  [db]
  (some-> (ds/entity db [:eacl/id mutation/schema-entity-id])
          (get mutation/schema-mutation-id-attr)))

(defn- mutation-relation-proof
  [db relation-ids]
  (let [proof
        (mapv (fn [relation-id]
                [relation-id
                 (get (ds/entity db relation-id)
                      mutation/relation-mutation-id-attr)])
              (sort relation-ids))]
    (when (every? (comp some? second) proof)
      proof)))

(defn remember-snapshot!
  [registry limit db]
  (when registry
    (let [selected-handle (volatile! nil)]
      (swap! registry
             (fn [{:keys [order snapshots identities]}]
               (if-let [handle
                        (some (fn [[known-db known-handle]]
                                (when (identical? known-db db)
                                  known-handle))
                              identities)]
                 (do
                   (vreset! selected-handle handle)
                   {:order order
                    :snapshots snapshots
                    :identities identities})
                 (let [handle (str (random-uuid))
                       _ (vreset! selected-handle handle)
                       order' (conj (vec order) handle)
                       snapshots' (assoc snapshots handle db)
                       ;; Current snapshots dominate traffic. Keep the newest
                       ;; immutable identity first so the ordinary path is
                       ;; constant-time even though the portable CLJ/CLJS
                       ;; registry uses identity pairs rather than equality
                       ;; keys.
                       identities' (into [[db handle]] identities)
                       overflow (- (count order') limit)
                       evicted (when (pos? overflow)
                                 (set (take overflow order')))]
                   {:order (if (pos? overflow)
                             (vec (drop overflow order'))
                             order')
                    :snapshots (apply dissoc snapshots' evicted)
                    :identities
                    (if (seq evicted)
                      (into []
                            (remove (comp evicted second))
                            identities')
                      identities')}))))
      @selected-handle)))

(defn snapshot-adapter
  "Creates a v8 adapter bound to one immutable DataScript db value."
  [db {:keys [object-id->entid entid->object-id conn
              coherence-authority proof-mode exact-registry]
       :or {proof-mode :content}
       :as opts}]
  (let [graph-state
        (delay (journal/graph-state db))
        exact-handle
        (when exact-registry
          (remember-snapshot!
           exact-registry
           (:exact-registry-limit opts)
           db))]
    (backend/make-adapter
     {:id :datascript
      :fingerprint (:adapter-fingerprint opts)
      :deterministic? (:adapter-deterministic? opts)
      :identity-contract
      (:identity-contract opts
                          :selected-internal/current-external-v1)
      :capabilities
      (cond-> capabilities
        (not= :managed coherence-authority)
        (update :consistency disj :at-least-as-fresh :at-exact-snapshot)

        (nil? conn)
        (update :consistency disj :fully-consistent)

        (nil? exact-registry)
        (update :consistency disj :at-exact-snapshot))
      :state {:db db}
      :operations
      {:snapshot-id
       (fn []
         {:database-id :datascript
          :basis-t (:max-tx db)})

       :source-scope
       (fn []
         {:source-id (:family-id @graph-state)
          :branch nil})

       :graph-head
       (fn []
         {:graph-anchor (:head-id @graph-state)
          :order-hint (:max-tx db)
          :exact-locator exact-handle})

       :contains-anchor?
       (fn [anchor]
         (journal/contains-anchor? db anchor))

       :order-hint (fn [] (:max-tx db))

       :select-current
       (fn []
         (snapshot-adapter (if conn (ds/db conn) db) opts))

       :select-authoritative
       (fn [_timeout-ms]
         (snapshot-adapter (if conn (ds/db conn) db) opts))

       :select-at-least
       (fn [token-data timeout-ms]
         (snapshot-adapter
          (await-anchor-db conn db token-data timeout-ms)
          opts))

       :exact-locator
       (fn []
         exact-handle)

       :select-exact
       (fn [token-data _timeout-ms]
         (some-> exact-registry
                 deref
                 :snapshots
                 (get (:exact-locator token-data))
                 (snapshot-adapter opts)))

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

       :schema-proof
       (fn
         ([]
          (case proof-mode
            :mutation (mutation-schema-proof db)
            :content (content-schema-proof db nil)
            nil))
         ([scope]
          (case proof-mode
            :mutation (mutation-schema-proof db)
            :content (content-schema-proof db scope)
            nil)))

       :relation-proof
       (fn [relation-ids]
         (case proof-mode
           :mutation (mutation-relation-proof db relation-ids)
           :content (content-relation-proof db relation-ids entid->object-id)
           nil))}})))
