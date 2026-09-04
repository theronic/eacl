(ns eacl.relationships.upgrade
  "Portable v7-to-v9 migration planning and verification. Native schema,
  snapshot and transaction operations are supplied by the adapter. No serving
  path calls this namespace's source enumeration or migration runner."
  (:require [eacl.relationships.endpoint-pair :as pair]
            [eacl.relationships.legacy-v7 :as legacy]
            [eacl.relationships.storage :as storage]
            [eacl.secure-format :as secure]))

(def metadata-id "schema-string")
(def state-attribute :eacl.storage/migration-state)
(def phases [:preflight :converting :verifying :cleaning :complete])
(def metadata-schema
  [{:db/ident :eacl/storage-version :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one :db/index true}
   {:db/ident state-attribute :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(defn fail!
  [reason data]
  (throw (ex-info "Relationship storage upgrade cannot proceed."
                  (merge {:type :eacl.storage/upgrade-failed
                          :eacl/error :eacl.storage/upgrade-failed
                          :reason reason}
                         data))))

(defn decode-state [payload]
  (when payload
    (let [state (try (secure/decode-canonical payload)
                     (catch #?(:clj Exception :cljs :default) _
                       (fail! :invalid-state {})))]
      (when-not (and (= 1 (:format state))
                     (= storage/format-id (:storage-format state))
                     (some #{(:phase state)} phases)
                     (string? (:run-id state)))
        (fail! :invalid-state {:state state}))
      state)))

(defn encode-state [state] (secure/encode-canonical state))

(defn transition
  "Only adjacent durable phases are legal. Same-phase conversion commits
  advance progress without skipping the verification boundary."
  [state phase]
  (let [current (:phase state)
        successors (zipmap phases (rest phases))]
    (when-not (or (and (= :converting current) (= current phase))
                  (= phase (get successors current)))
      (fail! :invalid-transition {:from current :to phase}))
    (assoc state :phase phase)))

(defn bootstrap-state [revision]
  {:format 1 :storage-format storage/format-id :phase :complete
   :run-id (str (random-uuid)) :bootstrap? true :expected-revision revision})

(defn bootstrap-tx [revision]
  [{:db/id -1 :eacl/id metadata-id :eacl/storage-version storage/version
    state-attribute (encode-state (bootstrap-state revision))}])

(defn assert-compatible!
  "Bounded metadata/schema/existence evidence assembled by a native adapter."
  [{:keys [backend version state legacy? v6? schema-compatible?] :as evidence}]
  (when-not (and (= storage/version version) (= :complete (:phase state))
                 (= storage/format-id (:storage-format state))
                 (not legacy?) (not v6?) schema-compatible?)
    (throw (ex-info "EACL v8 requires Relationship storage ABI 9; run the explicit upgrade."
                    {:type :eacl/storage-version :eacl/error :eacl/storage-version
                     :backend backend :required-version storage/version
                     :detected-version version
                     :migration-state (:phase state)
                     :reason (cond v6? :v6-prerequisite legacy? :legacy-data
                                   (not schema-compatible?) :incompatible-schema
                                   :else :incomplete-storage)
                     :migration-ns (if v6? 'eacl.migrations.v6-to-v7
                                       (symbol (str "eacl." (name backend) ".migrations.v7-to-v9")))
                     :documentation "docs/migration-v7-to-v9.md"})))
  evidence)

(defn reject-auto-migration! [options]
  (when-let [unsupported (seq (filter #(re-find #"^auto-migrate" (name %)) (keys options)))]
    (throw (ex-info "Storage migration must be invoked explicitly."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :unknown-keys (vec unsupported)}))))

(defn canonical-identity
  [direction owner value source-version]
  (let [target (if (= legacy/version source-version) (conj value nil) value)
        decoded (case direction :forward (pair/decode-forward owner target)
                                 :reverse (pair/decode-reverse owner target))]
    (when-not (and (nat-int? owner) decoded (nil? (:qualifier-eid decoded)))
      (fail! :malformed-pair {:direction direction :owner owner :value value}))
    (let [{:keys [subject-eid subject-type relation-eid resource-type resource-eid]} decoded]
      [subject-eid subject-type relation-eid resource-type resource-eid])))

(defn inspect-pairs
  "Independently enumerates both directions and validates parity/uniqueness.
  Returns owner-qualified identities; no success is inferred from seed counts."
  [{:keys [forward reverse]} source-version]
  (let [index (fn [direction rows]
                (reduce (fn [result {:keys [e v]}]
                          (let [identity (canonical-identity direction e v source-version)]
                            (when (contains? result identity)
                              (fail! :duplicate-identity {:identity identity}))
                            (conj result identity)))
                        #{} rows))
        forward-identities (index :forward forward)
        reverse-identities (index :reverse reverse)]
    (when-not (= forward-identities reverse-identities)
      (fail! :pair-mismatch
             {:forward-count (count forward-identities)
              :reverse-count (count reverse-identities)
              :sample (vec (take 20 (concat
                                    (remove reverse-identities forward-identities)
                                    (remove forward-identities reverse-identities))))}))
    forward-identities))

(defn certificate [identities]
  {:source-count (count identities)
   :source-digest (secure/canonical-records-digest
                   "eacl.relationships/v7-to-v9/logical-identity-v1" (sort identities))})

(defn validate-references!
  "Checks endpoint presence and the selected Relation's exact typed identity."
  [identities entity-exists? relation]
  (doseq [[subject-eid subject-type relation-eid resource-type resource-eid :as identity] identities]
    (when-not (and (entity-exists? subject-eid) (entity-exists? resource-eid))
      (fail! :missing-endpoint {:identity identity}))
    (let [definition (relation relation-eid)]
      (when-not (and (= subject-type (:eacl.relation/subject-type definition))
                     (= resource-type (:eacl.relation/resource-type definition))
                     (keyword? (:eacl.relation/relation-name definition)))
        (fail! :invalid-relation {:identity identity}))))
  identities)

(defn batch-plan
  "Exact paired conversion. Matching targets are idempotent under native set
  semantics; full union reconciliation precedes resumed conversion."
  [identities]
  {:relations (set (map #(nth % 2) identities))
   :tx-data
   (into []
         (mapcat
          (fn [[subject-eid subject-type relation-eid resource-type resource-eid]]
            (let [forward (pair/forward-value subject-type relation-eid resource-type resource-eid)
                  reverse (pair/reverse-value resource-type relation-eid subject-type subject-eid)]
              [[:db/add subject-eid storage/forward-attribute forward]
               [:db/add resource-eid storage/reverse-attribute reverse]
               [:db/retract subject-eid legacy/forward-attribute (pop forward)]
               [:db/retract resource-eid legacy/reverse-attribute (pop reverse)]])))
         identities)})

(defn reconcile!
  [state source target]
  (let [combined (into source target)]
    (when-not (= (select-keys state [:source-count :source-digest]) (certificate combined))
      (fail! :content-mismatch {:expected (select-keys state [:source-count :source-digest])
                               :actual (certificate combined)}))
    combined))

(defn verify!
  [state source target relation-generation]
  (when (seq source) (fail! :source-not-empty {:count (count source)}))
  (reconcile! state source target)
  (doseq [relation-eid (set (map #(nth % 2) target))]
    (let [generation (relation-generation relation-eid)]
      (when-not (and (integer? generation) (> generation (:start-revision state)))
        (fail! :missing-relation-stamp {:relation-eid relation-eid :generation generation}))))
  true)

(defn assert-head! [state revision]
  (when-not (= (:expected-revision state) revision)
    (fail! :concurrent-write {:expected (:expected-revision state) :actual revision})))

(defn- report [state already-complete?]
  {:state (:phase state) :storage-version (when (= :complete (:phase state)) storage/version)
   :run-id (:run-id state) :converted (:converted state 0)
   :source-count (:source-count state) :source-digest (:source-digest state)
   :already-complete? already-complete?})

(defn migrate!
  "Runs the quiesced migration through native adapter operations. `commit!`
  must guard the complete calculation revision at transaction time. Each
  committed progress callback is an interruption-safe recovery boundary."
  [{:keys [snapshot revision evidence install! read-state read-pairs
           source-batch entity-exists? relation relation-generation commit! stamp] :as adapter}
   {:keys [quiesced? batch-size on-progress] :or {batch-size 1000} :as options}]
  (when-not (and (= true quiesced?) (pos-int? batch-size) (<= batch-size 10000)
                 (or (nil? on-progress) (fn? on-progress))
                 (every? #{:quiesced? :batch-size :on-progress} (keys options)))
    (fail! :invalid-options {:required :quiesced? :maximum-batch-size 10000}))
  (let [initial (snapshot)
        initial-evidence (evidence initial)]
    (when (:v6? initial-evidence)
      (assert-compatible! initial-evidence))
    (if (and (= storage/version (:version initial-evidence))
             (= :complete (get-in initial-evidence [:state :phase])))
      (do (assert-compatible! initial-evidence)
          (report (:state initial-evidence) true))
      (do
        (when-not (contains? #{nil legacy/version} (:version initial-evidence))
          (fail! :wrong-source-version {:version (:version initial-evidence)}))
        (when (= :complete (get-in initial-evidence [:state :phase]))
          (fail! :inconsistent-completion-stamp {}))
        (when-not (:source-schema-compatible? initial-evidence)
          (fail! :incompatible-source-schema {:backend (:backend initial-evidence)}))
        (install!)
        (let [db (snapshot)
              state (read-state db)
              _ (when state (assert-head! state (revision db)))
              source (inspect-pairs (read-pairs db legacy/version) legacy/version)
              target (inspect-pairs (read-pairs db storage/version) storage/version)
              _ (when (and (nil? state) (not (every? source target)))
                  (fail! :unexpected-target-data {}))
              _ (validate-references! (into source target)
                                       #(entity-exists? db %) #(relation db %))
              _ (when state (reconcile! state source target))
              state (or state
                        (merge {:format 1 :storage-format storage/format-id
                                :phase :preflight :run-id (str (random-uuid))
                                :start-revision (revision db) :converted 0}
                               (certificate source)))
              publish!
              (fn [db state operations]
                (let [next-state (assoc state :expected-revision (inc (revision db)))
                      operations (conj (vec operations)
                                       (cond-> {:db/id -1 :eacl/id metadata-id
                                                state-attribute (encode-state next-state)}
                                         (= :complete (:phase next-state))
                                         (assoc :eacl/storage-version storage/version)))
                      after (commit! db operations)
                      committed (read-state after)]
                  (assert-head! committed (revision after))
                  (when on-progress (on-progress (report committed false)))
                  [after committed]))
              [db state] (if (read-state db) [db state] (publish! db state []))]
          (loop [db db state state]
            (assert-head! state (revision (snapshot)))
            (case (:phase state)
              :preflight (let [[db state] (publish! db (transition state :converting) [])]
                           (recur db state))
              :converting
              (let [identities (mapv #(canonical-identity :forward (:e %) (:v %) legacy/version)
                                     (source-batch db batch-size))
                    {:keys [tx-data relations]} (batch-plan identities)
                    next-state (if (seq identities)
                                 (update state :converted + (count identities))
                                 (transition state :verifying))
                    [db state] (publish! db next-state (into tx-data (map stamp) (sort relations)))]
                (recur db state))
              (:verifying :cleaning)
              (let [source (inspect-pairs (read-pairs db legacy/version) legacy/version)
                    target (inspect-pairs (read-pairs db storage/version) storage/version)]
                (validate-references! target #(entity-exists? db %) #(relation db %))
                (verify! state source target #(relation-generation db %))
                (let [[db state] (publish! db (transition state (if (= :verifying (:phase state)) :cleaning :complete)) [])]
                  (recur db state)))
              :complete (report state false))))))))
