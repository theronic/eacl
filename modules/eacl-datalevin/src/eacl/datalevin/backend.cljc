(ns eacl.datalevin.backend
  "Owned explicit-snapshot storage operations for the EACL v8 engine."
  (:require [clojure.set :as set]
            [datalevin.core :as d]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.datalevin.db :as ddb]
            [eacl.datalevin.fork :as fork]
            [eacl.datalevin.impl :as impl]
            [eacl.schema.expression-persistence :as expression-persistence]))

(def adapter-capabilities
  {:cursor #{:forward :reverse :opaque :authenticated :encrypted}
   :cache-proofs #{:ordered-generations :snapshot-bound :database-visible}
   :runtime #{:clj}})

(def source-capabilities
  {:consistency #{:minimize-latency
                  :fully-consistent
                  :at-least-as-fresh}
   :snapshots #{:current :authoritative :causal}
   :source #{:stable-scope :source-lifecycle :native-revision :order-hint}
   :runtime #{:clj}})

(def execution-constraints
  {:virtual-threads :rejected
   :snapshot-thread :acquiring-thread
   :release-thread :acquiring-thread})

(def topology
  {:deployment :embedded
   :snapshot-values :owned-explicit
   :writer-safety :storage-enforced})

(def prepared-schema-eid-key
  "Module-internal adapter option containing the frozen schema singleton eid."
  ::prepared-schema-eid)

(def required-write-policy-capabilities
  {:version 1
   :commit-generation-materialization true
   :max-tx-continuity true
   :post-expansion-enforcement true
   :persisted-policy true
   :per-open-admission-token true
   :shared-store-stale-generation-recovery true})

(defn validate-fork-capabilities!
  "Requires executable fork support before module bootstrap may write."
  [_conn]
  (let [actual (fork/write-policy-capabilities)
        missing-or-wrong
        (into {}
              (keep (fn [[capability required-value]]
                      (when-not (= required-value (get actual capability))
                        [capability
                         {:required required-value
                          :actual (get actual capability)}])))
              required-write-policy-capabilities)]
    (when (seq missing-or-wrong)
      (throw
       (ex-info
        "The Datalevin artifact lacks required ordered-generation enforcement."
        {:type :eacl/unsupported-capability
         :eacl/error :eacl/unsupported-capability
         :backend :datalevin
         :capability :ordered-generations
         :required required-write-policy-capabilities
         :actual actual
         :missing-or-wrong missing-or-wrong})))
    actual))

(defn exact-natural!
  [field value]
  (when-not (and (integer? value)
                 (not (neg? value))
                 (<= value backend/maximum-exact-integer))
    (throw
     (ex-info
      "Datalevin snapshot metadata exceeds EACL's exact integer domain."
      {:type :eacl/numeric-domain-error
       :eacl/error :eacl/numeric-domain-error
       :backend :datalevin
       :field field
       :value value
       :maximum backend/maximum-exact-integer})))
  value)

(defn basis-kind
  "Classifies the only Datalevin value admitted by the public constructor."
  [value]
  (if (d/read-snapshot? value) :ordinary :foreign-backend))

(defn database-source-scope
  "Reads the durable Datalevin source UUID from an open read snapshot."
  [snapshot]
  (when (= :ordinary (basis-kind snapshot))
    (ddb/with-db
      snapshot
      (fn [db]
        (when-let [source-id
                   (:eacl.datalevin/source-id
                    (d/entity db [:eacl/id "datalevin-metadata"]))]
          {:source-id (str source-id)
           :branch nil})))))

(defn- normalized-permissions
  [permission]
  (let [permission-id
        (exact-natural! :permission-id (:db/id permission))]
    (expression-persistence/union-compatible-definitions
     permission-id
     (expression-persistence/decode-entity permission))))

(defn- permission-expression [db resource-type permission-name]
  (some-> (expression-persistence/validate-entities
           (impl/find-permission-defs db resource-type permission-name))
          first
          :entity))

(defn- scalar-generation
  [db entity-id attribute]
  (some-> (first (d/datoms db :eav entity-id attribute)) :v))

(defn- ordered-generation-frame
  [snapshot relation-ids]
  (ddb/with-db
   snapshot
   (fn [db]
     (mapv
      (fn [relation-id]
        [relation-id
         (scalar-generation
          db relation-id :eacl.datalevin/relation-generation)])
      relation-ids))))

(defn- snapshot-revision-info
  [snapshot]
  (when-not (d/read-snapshot? snapshot)
    (throw
     (ex-info
      "Datalevin EACL adapters require a public explicit read snapshot."
      {:type :eacl/invalid-selected-basis
       :eacl/error :eacl/invalid-selected-basis
       :backend :datalevin})))
  (let [info (d/read-snapshot-revision-info snapshot)]
    (exact-natural! :revision (:max-tx info))
    (exact-natural! :max-eid (:max-eid info))
    info))

(def adapter-config-keys
  #{:object-id->entid :entid->object-id
    :adapter-fingerprint :adapter-deterministic? :identity-contract
    prepared-schema-eid-key})

(defn basis-adapter
  "Creates an immutable v8 adapter around one open Datalevin read snapshot.
  The provider retains ownership; every operation binds the snapshot's one
  explicit native reader and eagerly realizes its result before returning."
  [snapshot {:keys [object-id->entid entid->object-id] :as opts}]
  (backend/validate-adapter-config! :datalevin adapter-config-keys opts)
  (let [info (snapshot-revision-info snapshot)
        revision (:max-tx info)
        schema-eid
        (exact-natural! :schema-entity-id
                        (get opts prepared-schema-eid-key))]
    (backend/make-adapter
     {:id :datalevin
      :traversal-execution backend/strict-sequential-traversal-execution
      :fingerprint (:adapter-fingerprint opts)
      :deterministic? (:adapter-deterministic? opts)
      :identity-contract
      (:identity-contract opts
                          :selected-internal/current-external-injective-v2)
      :capabilities adapter-capabilities
      :runtime-guards? true
      :state {:db snapshot
              :revision revision
              :max-eid (:max-eid info)}
      :operations
      {:snapshot-id
       (fn []
         {:database-id :datalevin
          :basis-t revision})

       :basis-kind (fn [] (basis-kind snapshot))

       :native-revision
       (fn [] {:revision revision :exact-locator nil})
       :order-hint (constantly revision)
       :schema-generation
       (fn []
         (ddb/with-db
          snapshot
          #(scalar-generation
            % schema-eid :eacl.datalevin/schema-generation)))

       :exact-locator (constantly nil)

       :object-id->internal
       (fn [object-id]
         (ddb/with-db
           snapshot
           (fn [db]
             (let [internal-id
                   (if (number? object-id)
                     object-id
                     (object-id->entid db object-id))]
               (when (some? internal-id)
                 (exact-natural! :entity-id internal-id))))))

       :internal-id->object
       (fn [internal-id]
         (exact-natural! :entity-id internal-id)
         (ddb/with-db snapshot #(entid->object-id % internal-id)))

       :relation-defs
       (fn [resource-type relation-name]
         (ddb/with-db
           snapshot
           (fn [db]
             (mapv
              (fn [{:keys [e v]}]
                (exact-natural! :relation-id e)
                {:relation-id e
                 :resource-type resource-type
                 :relation-name relation-name
                 :subject-type (nth v 2)})
              (impl/relation-datoms db resource-type relation-name)))))

       :permission-defs
       (fn [resource-type permission-name]
         (ddb/with-db
           snapshot
           #(vec (mapcat normalized-permissions
                         (impl/find-permission-defs
                          % resource-type permission-name)))))

       :permission-expression
       (fn [resource-type permission-name]
         (ddb/with-db
          snapshot
          #(permission-expression % resource-type permission-name)))

       :subject->resources
       (fn [subject-type subject-id relation-id resource-type options]
         (exact-natural! :subject-id subject-id)
         (exact-natural! :relation-id relation-id)
         (when-some [bound-eid (:bound-eid options)]
           (exact-natural! :cursor-bound bound-eid))
         (ddb/with-db
           snapshot
           #(mapv (fn [resource-id]
                    (exact-natural! :resource-id resource-id))
                  (impl/subject->resources
                   % subject-type subject-id relation-id resource-type
                   options))))

       :resource->subjects
       (fn [resource-type resource-id relation-id subject-type options]
         (exact-natural! :resource-id resource-id)
         (exact-natural! :relation-id relation-id)
         (when-some [bound-eid (:bound-eid options)]
           (exact-natural! :cursor-bound bound-eid))
         (ddb/with-db
           snapshot
           #(mapv (fn [subject-id]
                    (exact-natural! :subject-id subject-id))
                  (impl/resource->subjects
                   % resource-type resource-id relation-id subject-type
                   options))))

       :direct-match?
       (fn [subject-type subject-id relation-id resource-type resource-id]
         (exact-natural! :subject-id subject-id)
         (exact-natural! :relation-id relation-id)
         (exact-natural! :resource-id resource-id)
         (ddb/with-db
           snapshot
           #(boolean
             (impl/direct-match?
              % subject-type subject-id relation-id
              resource-type resource-id))))

       :all-permission-nodes
       (fn []
         (ddb/with-db
           snapshot
           impl/all-permission-nodes))

       :proof-frame
       (fn [relation-ids]
         (ordered-generation-frame snapshot relation-ids))}})))

(defn connection-source-id
  "Reads the bounded source UUID installed in Datalevin module metadata.
  Called once during client construction, never on a request path."
  [conn]
  (let [source-id
        (:eacl.datalevin/source-id
         (d/entity (d/db conn) [:eacl/id "datalevin-metadata"]))]
    (when-not (uuid? source-id)
      (throw
       (ex-info
        "Datalevin module metadata has no stable source UUID."
        {:type :eacl/invalid-source-identity
         :eacl/error :eacl/invalid-source-identity
         :backend :datalevin
         :value source-id})))
    (str source-id)))

(defn- acquire-owned!
  [conn adapter-options]
  (let [snapshot (d/open-read-snapshot conn)]
    (try
      {:adapter (basis-adapter snapshot adapter-options)
       :ownership :owned
       :release-token snapshot}
      (catch #?(:clj Throwable :cljs :default) error
        (d/close-read-snapshot! snapshot)
        (throw error)))))

(defn validate-topology!
  "Rejects any connection/runtime state outside the certified embedded
  Datalevin profile before module bootstrap is allowed to mutate the store."
  [conn _opts]
  (let [snapshot-capabilities (d/read-snapshot-capabilities conn)
        _
        (when-not (:supported? snapshot-capabilities)
          (throw
           (ex-info
            "Datalevin topology has no supported embedded snapshot session."
            {:type :eacl/unsupported-topology
             :eacl/error :eacl/unsupported-topology
             :backend :datalevin
             :reason (:reason snapshot-capabilities)})))
        _
        (when (or (:wal? snapshot-capabilities)
                  (some? (:ha-mode snapshot-capabilities)))
          (throw
           (ex-info
            "Datalevin WAL and HA modes are not certified for this adapter."
            {:type :eacl/unsupported-topology
             :eacl/error :eacl/unsupported-topology
             :backend :datalevin
             :wal? (:wal? snapshot-capabilities)
             :ha-mode (:ha-mode snapshot-capabilities)})))
        unsafe-flags
        (set/intersection #{:nolock :nosync :nometasync :mapasync :writemap}
                          (:env-flags snapshot-capabilities))
        _
        (when (seq unsafe-flags)
          (throw
           (ex-info
            "Datalevin LMDB durability flags are outside the certified profile."
            {:type :eacl/unsupported-topology
             :eacl/error :eacl/unsupported-topology
             :backend :datalevin
             :unsafe-env-flags unsafe-flags})))]
    snapshot-capabilities))

(defn source
  "Builds the owned, platform-thread-affine basis source for one
  qualified local embedded Datalevin connection."
  [conn opts]
  (let [_ (validate-topology! conn opts)
        source-scope {:source-id (:native-source-id opts) :branch nil}
        opts (assoc opts :source-scope source-scope)
        adapter-options (select-keys opts adapter-config-keys)
        source-lifecycle
        (fn []
          (or (some-> (:source-lifecycle-state opts) deref)
              (:source-lifecycle opts)))]
    (source/make-source
     {:id :datalevin
      :capabilities source-capabilities
      :traversal-execution backend/strict-sequential-traversal-execution
      :topology topology
      :execution-constraints execution-constraints
      :basis-ownership :owned
      :fingerprint
      (or (:adapter-fingerprint opts)
          {:backend :datalevin
           :adapter-version backend/adapter-version
           :snapshot-api :dev.eacl/read-snapshot-v1})
      :deterministic? (:adapter-deterministic? opts)
      :operations
      {:source-scope (constantly source-scope)
       :source-lifecycle source-lifecycle
       :acquire-current! (fn [] (acquire-owned! conn adapter-options))
       :acquire-authoritative!
       (fn [_timeout-ms] (acquire-owned! conn adapter-options))
       ;; Shared consistency orchestration compares the revision, closes an
       ;; insufficient candidate, preserves the original deadline, and
       ;; retries. Returning the head immediately made that retry loop spin
       ;; LMDB read transactions at full CPU until the writer caught up, so
       ;; an insufficient head now waits briefly before acquiring, matching
       ;; the Datahike and DataScript freshness polls.
       :acquire-at-least!
       (fn [token-data _remaining-ms]
         (let [requested (:revision token-data)
               candidate (acquire-owned! conn adapter-options)
               revision (:revision
                         (backend/invoke (:adapter candidate)
                                         :native-revision))]
           (if (or (nil? requested)
                   (and (integer? revision) (>= revision requested)))
             candidate
             (do
               (d/close-read-snapshot! (:release-token candidate))
               #?(:clj (Thread/sleep 2))
               (acquire-owned! conn adapter-options)))))
       :acquire-exact!
       (fn [_token-data _timeout-ms]
         (throw
          (ex-info
           "Datalevin does not retain exact historical snapshots."
           {:type :eacl/unsupported-capability
            :eacl/error :eacl/unsupported-capability
            :backend :datalevin
            :capability :consistency
            :requested :at-exact-snapshot})))
       :release! d/close-read-snapshot!}})))
