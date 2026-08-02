(ns eacl.datahike.backend
  "Datahike storage operations for the shared v8 authorization engine."
  (:require [datahike.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as impl]
            [eacl.datahike.mutation :as journal]
            [eacl.datahike.schema :as schema]
            [eacl.mutation :as mutation]
            [eacl.relationships.endpoint-pair :as endpoint-pair]
            [eacl.secure-format :as secure])
  (:import [java.util UUID]))

(def capabilities
  {:consistency #{:local-snapshot
                  :fully-consistent
                  :synchronized-head
                  :minimize-latency
                  :at-least-as-fresh
                  :at-exact-snapshot}
   :snapshots #{:current :authoritative :causal :exact}
   :source #{:stable-scope :graph-head :anchor-membership :order-hint}
   :cursor #{:forward :reverse :opaque}
   :transactions #{:schema :relationships :object-deletion}
   :cache-proofs #{:schema :relations :snapshot-bound :database-visible}
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

(defn- freshness-timeout!
  [token-data timeout-ms observed]
  (throw
   (ex-info
    "Datahike branch did not acquire the requested mutation anchor."
    {:type :eacl.consistency/freshness-unavailable
     :eacl/error :eacl.consistency/freshness-unavailable
     :reason :freshness-timeout
     :requested-order-hint (:order-hint token-data)
     :observed-order-hint (:max-tx observed)
     :timeout-ms timeout-ms})))

(defn- await-anchor-db
  [conn fallback token-data timeout-ms]
  (let [timeout-ms (or timeout-ms 30000)
        deadline (+ (System/nanoTime)
                    (* 1000000 timeout-ms))]
    (loop []
      (let [candidate (if conn (d/db conn) fallback)]
        (cond
          (journal/contains-anchor?
           candidate (:graph-anchor token-data))
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

(defn- apply-scan-window
  [ids {:keys [direction bound-eid inclusive-bound?]}]
  (let [direction (or direction :asc)
        ordered (sort ids)
        within-bound?
        (case direction
          :asc (if bound-eid
                 (if inclusive-bound?
                   #(<= bound-eid %)
                   #(< bound-eid %))
                 (constantly true))
          :desc (if bound-eid
                  (if inclusive-bound?
                    #(>= bound-eid %)
                    #(> bound-eid %))
                  (constantly true)))]
    (cond->> (filter within-bound? ordered)
      (= :desc direction) reverse)))

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
    "eacl/datahike/schema-content-proof/v3"
    (schema-proof-records db scope))})

(defn- content-relation-proof
  [db relation-ids external-id]
  (let [wanted (set relation-ids)
        forward
        (when (seq wanted)
          (for [{subject :e value :v}
                (ddb/avet-datoms
                 db schema/forward-relationship-attr)
                :let [decoded
                      (endpoint-pair/decode-forward subject value)]
                :when (contains? wanted (:relation-eid decoded))]
            [:forward (:relation-eid decoded)
             (:subject-type decoded) subject (external-id db subject)
             (:resource-type decoded) (:resource-eid decoded)
             (external-id db (:resource-eid decoded))]))
        reverse
        (when (seq wanted)
          (for [{resource :e value :v}
                (ddb/avet-datoms
                 db schema/reverse-relationship-attr)
                :let [decoded
                      (endpoint-pair/decode-reverse resource value)]
                :when (contains? wanted (:relation-eid decoded))]
            [:reverse (:relation-eid decoded)
             (:subject-type decoded) (:subject-eid decoded)
             (external-id db (:subject-eid decoded))
             (:resource-type decoded) resource (external-id db resource)]))]
    {:content-digest
     (secure/canonical-records-digest
      "eacl/datahike/relationship-content-proof/v3"
      (sort (concat forward reverse)))}))

(defn- mutation-schema-proof
  [db]
  (some-> (d/entity db [:eacl/id mutation/schema-entity-id])
          (get mutation/schema-mutation-id-attr)))

(defn- mutation-relation-proof
  [db relation-ids]
  (let [proof
        (mapv (fn [relation-id]
                [relation-id
                 (get (d/entity db relation-id)
                      mutation/relation-mutation-id-attr)])
              (sort relation-ids))]
    (when (every? (comp some? second) proof)
      proof)))

(defn snapshot-adapter
  "Creates a v8 adapter bound to one immutable Datahike db value."
  [db {:keys [object-id->entid entid->object-id conn
              coherence-authority proof-mode]
       :or {proof-mode :content}
       :as opts}]
  (let [source-scope
        (or (:source-scope opts)
            (let [{:keys [backend id]} (get-in db [:config :store])]
              {:source-id
               {:store-backend backend
                :store-id (str id)
                :family-id (:family-id (journal/graph-state db))}
               :branch (get-in db [:config :branch])}))
        opts' (assoc opts :source-scope source-scope)]
    (backend/make-adapter
     {:id :datahike
      :fingerprint (:adapter-fingerprint opts)
      :deterministic? (:adapter-deterministic? opts)
      :identity-contract
      (:identity-contract opts
                          :selected-internal/current-external-v1)
      :capabilities
      (cond-> capabilities
        (not= :managed coherence-authority)
        (update :consistency disj :at-least-as-fresh :at-exact-snapshot)

        (or (nil? conn)
            (not (direct-writer? db)))
        (update :consistency disj :fully-consistent :synchronized-head)

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
          :basis-t (:max-tx db)})

       :source-scope
       (fn [] source-scope)

       :graph-head
       (fn []
         {:graph-anchor (:head-id (journal/graph-state db))
          :order-hint (:max-tx db)
          :exact-locator (commit-locator db)})

       :contains-anchor?
       (fn [anchor]
         (journal/contains-anchor? db anchor))

       :order-hint (fn [] (:max-tx db))

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
          (await-anchor-db conn db token-data timeout-ms)
          opts'))

       :exact-locator (fn [] (commit-locator db))

       :select-exact
       (fn [token-data _timeout-ms]
         (when (and conn
                    (:exact-locator token-data))
           (try
             (let [commit-db
                   (when (exact-commits? db)
                     (d/commit-as-db
                      conn
                      (UUID/fromString
                       (:exact-locator token-data))))
                   temporal-db
                   (when (and (nil? commit-db)
                              (temporal-history? db)
                              (integer? (:order-hint token-data))
                              (<= (:order-hint token-data)
                                  (:max-tx (d/db conn))))
                     (d/as-of (d/db conn)
                              (:order-hint token-data)))]
               (some-> (or commit-db temporal-db)
                       (snapshot-adapter opts')))
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
         (apply-scan-window
          (impl/subject->resources
           db subject-type subject-id relation-id resource-type nil)
          options))

       :resource->subjects
       (fn [resource-type resource-id relation-id subject-type options]
         (apply-scan-window
          (impl/resource->subjects
           db resource-type resource-id relation-id subject-type nil)
          options))

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

       :frontier-key pr-str

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
