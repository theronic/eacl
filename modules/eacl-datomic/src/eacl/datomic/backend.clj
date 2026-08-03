(ns eacl.datomic.backend
  "Datomic's storage-specific implementation of the shared v8 snapshot
  adapter. Authorization graph algorithms remain outside this namespace."
  (:require [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.datomic.db :as ddb]
            [eacl.datomic.mutation :as journal]
            [eacl.mutation :as mutation]
            [eacl.relationships.endpoint-pair :as endpoint-pair])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64]))

(def capabilities
  {:consistency #{:local-snapshot
                  :fully-consistent
                  :synchronized-head
                  :minimize-latency
                  :at-least-as-fresh
                  :at-exact-snapshot}
   :snapshots #{:current :historical}
   :source #{:stable-scope :graph-head :anchor-membership :order-hint
             :exact-locator}
   :cursor #{:forward :reverse :opaque :authenticated :encrypted}
   :transactions #{:schema :relationships :object-deletion}
   :cache-proofs #{:schema :relations :snapshot-bound :database-visible}
   :runtime #{:clj}})

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

(defn- digest-records
  "Hashes an ordered sequence without materializing one giant encoding.

  Each record is length framed, so concatenation cannot create ambiguous
  proofs. Callers provide fixed-shape vectors and a domain; map print ordering
  is therefore never part of the proof contract."
  [domain records]
  (let [digest (MessageDigest/getInstance "SHA-256")
        update-bytes!
        (fn [^bytes bytes]
          (let [length-prefix
                (byte-array
                 [(unchecked-byte (bit-shift-right (alength bytes) 24))
                  (unchecked-byte (bit-shift-right (alength bytes) 16))
                  (unchecked-byte (bit-shift-right (alength bytes) 8))
                  (unchecked-byte (alength bytes))])]
            (.update digest length-prefix)
            (.update digest bytes)))]
    (update-bytes! (.getBytes domain StandardCharsets/UTF_8))
    (doseq [record records]
      (update-bytes!
       (.getBytes (pr-str record) StandardCharsets/UTF_8)))
    (.encodeToString
     (.withoutPadding (Base64/getUrlEncoder))
     (.digest digest))))

(defn- schema-proof-records
  [db {:keys [permission-nodes relation-ids] :as scope}]
  (if-not (and (d/entid db :eacl.relation/relation-name)
               (d/entid db :eacl.permission/permission-name))
    []
    (let [relation-ids (if scope
                         relation-ids
                         (journal/relation-ids db))
          permission-nodes (if scope
                             permission-nodes
                             (ddb/all-permission-nodes db))]
      (concat
       (->> relation-ids
            (map (fn [relation-id]
                   (let [relation (d/entity db relation-id)]
                     [:relation
                      relation-id
                      (:eacl.relation/resource-type relation)
                      (:eacl.relation/relation-name relation)
                      (:eacl.relation/subject-type relation)])))
            sort)
       (->> permission-nodes
            (mapcat (fn [[resource-type permission-name]]
                      (permission-defs
                       db resource-type permission-name)))
            (map (fn [permission]
                   [:permission
                    (:permission-id permission)
                    (:resource-type permission)
                    (:permission-name permission)
                    (:source-relation-name permission)
                    (:target-type permission)
                    (:target-name permission)]))
            sort)))))

(defn- content-schema-proof
  [db scope]
  {:content-digest
   (digest-records
    "eacl/datomic/schema-content-proof/v3"
    (schema-proof-records db scope))})

(defn- content-relation-proof
  [db relation-ids external-id]
  (let [wanted (set relation-ids)
        forward-attr ddb/forward-relationship-attr
        reverse-attr ddb/reverse-relationship-attr
        forward
        (when (and (seq wanted) (d/entid db forward-attr))
          (for [{subject :e value :v}
                (d/datoms db :aevt forward-attr)
                :let [decoded
                      (endpoint-pair/decode-forward subject value)]
                :when (contains? wanted (:relation-eid decoded))]
            [:forward (:relation-eid decoded)
             (:subject-type decoded) subject (external-id db subject)
             (:resource-type decoded) (:resource-eid decoded)
             (external-id db (:resource-eid decoded))]))
        reverse
        (when (and (seq wanted) (d/entid db reverse-attr))
          (for [{resource :e value :v}
                (d/datoms db :aevt reverse-attr)
                :let [decoded
                      (endpoint-pair/decode-reverse resource value)]
                :when (contains? wanted (:relation-eid decoded))]
            [:reverse (:relation-eid decoded)
             (:subject-type decoded) (:subject-eid decoded)
             (external-id db (:subject-eid decoded))
             (:resource-type decoded) resource (external-id db resource)]))]
    ;; Preserve both physical halves. Direct/forward evaluation reads the
    ;; forward tuple and reverse lookup reads the reverse tuple, so a
    ;; corruption or out-of-band half-write must invalidate whichever
    ;; operation it can affect.
    {:content-digest
     (digest-records
      "eacl/datomic/relationship-content-proof/v3"
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
  "Creates an adapter bound to one immutable Datomic db value. Proof and scan
  operations therefore cannot accidentally observe a different basis."
  ([db]
   (snapshot-adapter db {}))
  ([db {:keys [entid->object-id
               object-eid-fn subject->resources-fn
               resource->subjects-fn conn coherence-authority
               database-id proof-mode selected-order-hint
               selected-exact-locator]
        :or {proof-mode :content}
        :as opts}]
   (let [external-id
         (or entid->object-id
             (fn [snapshot eid]
               (:eacl/id (d/entity snapshot eid))))
         graph-state
         (delay (journal/graph-state db))]
     (backend/make-adapter
      {:id :datomic
       :fingerprint (:adapter-fingerprint opts)
       :deterministic? (:adapter-deterministic? opts)
       :identity-contract
       (:identity-contract opts
                           :selected-internal/current-external-v1)
       :capabilities
       (cond-> capabilities
         (not= :managed coherence-authority)
         (update :consistency disj
                 :at-least-as-fresh
                 :at-exact-snapshot)

         (nil? conn)
         (update :consistency disj
                 :fully-consistent
                 :synchronized-head
                 :at-exact-snapshot))
       :state {:db db
               :opts opts}
       :operations
       {:snapshot-id
        (fn []
          {:database-id (str (.id ^datomic.Database db))
           :basis-t (or selected-exact-locator
                        (d/basis-t db))})

        :source-scope
        (fn []
          {:source-id
            {:database-id
            (or database-id
                (str (.id ^datomic.Database db)))
            :family-id (:family-id @graph-state)}
           :branch nil})

        :graph-head
        (fn []
          {:graph-anchor (:head-id @graph-state)
           :order-hint (or selected-order-hint
                           (d/basis-t db))
           :exact-locator (or selected-exact-locator
                              (d/basis-t db))})

        :contains-anchor?
        (fn [anchor]
          (journal/contains-anchor? db anchor))

        :order-hint
        (fn []
          (or selected-order-hint
              (d/basis-t db)))

        :select-current
        (fn []
          (snapshot-adapter (if conn (d/db conn) db) opts))

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
              (snapshot-adapter selected opts))
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
                    (deref (d/sync conn (:order-hint token-data))
                           (or timeout-ms 30000)
                           ::timeout)
                    db)
                  requested-order-hint (:order-hint token-data)]
              (when (= ::timeout selected)
                (throw
                 (ex-info
                  "Timed out waiting for the Datomic causal floor."
                  {:type :eacl.consistency/freshness-unavailable
                   :eacl/error :eacl.consistency/freshness-unavailable
                   :reason :freshness-timeout
                   :requested-order-hint (:order-hint token-data)
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
              (snapshot-adapter selected opts))
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
                   :requested-order-hint (:order-hint token-data)
                   :timeout-ms (or timeout-ms 30000)}
                  error))))))

        :exact-locator
        (fn []
          (or selected-exact-locator
              (d/basis-t db)))

        :select-exact
        (fn [token-data _timeout-ms]
          (let [locator (:exact-locator token-data)
                current (if conn (d/db conn) db)]
            (when (and (integer? locator)
                       (<= locator (d/basis-t current)))
              (try
                (snapshot-adapter
                 (d/as-of current locator)
                 (assoc opts
                        :selected-order-hint locator
                        :selected-exact-locator locator))
                (catch Throwable _
                  nil)))))

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

        :frontier-key
        (fn [identity]
          (let [bytes (.getBytes (pr-str identity)
                                 StandardCharsets/UTF_8)
                digest (.digest
                        (MessageDigest/getInstance "SHA-256")
                        bytes)]
            (.encodeToString
             (.withoutPadding (Base64/getUrlEncoder))
             digest)))

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
            :content (content-relation-proof db relation-ids external-id)
            nil))}}))))
