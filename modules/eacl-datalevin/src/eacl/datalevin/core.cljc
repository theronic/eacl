(ns eacl.datalevin.core
  "Datalevin construction shim over the shared client orchestration
  (backend-unification, D-7). Everything here is genuinely
  Datalevin-specific: index access for managed stamps, the snapshot
  adapter, schema installation, and transaction submission. The
  nine public operations, snapshot-context assembly, cursor plumbing, and
  cache wiring live once in eacl.client.orchestration."
  (:require [datalevin.core :as ds]
            [eacl.client.orchestration :as orchestration]
            [eacl.cursor :as cursor]
            [eacl.datalevin.backend :as datalevin-backend]
            [eacl.datalevin.db :as ddb]
            [eacl.datalevin.fork :as fork]
            [eacl.datalevin.impl :as impl]
            [eacl.datalevin.schema :as schema]
            [eacl.datalevin.storage :as target-storage]
            [eacl.relationships.storage :as relationship-storage]))

(def cursor->token cursor/cursor->token)
(def token->cursor cursor/token->cursor)

(def default-internal-cursor->spice orchestration/default-internal-cursor->spice)
(def default-spice-cursor->internal orchestration/default-spice-cursor->internal)

(def ^:private prepared-native-source-id-key
  ::prepared-native-source-id)

(def ^:private extra-client-opt-keys
  #{:revision-watermark
    :advance-revision-watermark!
    :maximum-snapshot-retention-ms
    prepared-native-source-id-key
    datalevin-backend/prepared-schema-eid-key})

(defn- relationship-retraction-count
  [_db tx-data]
  (count
   (filter
    (fn [{:keys [a added]}]
      (and (false? added)
           (contains? relationship-storage/attributes a)))
    tx-data)))

(defn- cas-failure-data
  [throwable]
  (loop [cause throwable]
    (when cause
      (let [data (ex-data cause)]
        (if (= :transact/cas (:error data))
          data
          (recur #?(:clj (.getCause ^Throwable cause)
                    :cljs (ex-cause cause))))))))

(defn- datalevin-failure-data
  [throwable failure-type]
  (loop [cause throwable]
    (when cause
      (let [data (ex-data cause)]
        (if (= failure-type (:type data))
          data
          (recur #?(:clj (.getCause ^Throwable cause)
                    :cljs (ex-cause cause))))))))

(defn- stale-connection-contention?
  [throwable]
  (= :eacl.datalevin/stale-connection-generation
     (:type (ex-data throwable))))

(defn- transact-native!
  [write-token conn {:keys [tx-data]}]
  (try
    (ds/transact!
     conn (vec tx-data)
     {:datalevin/write-token write-token})
    (catch #?(:clj Throwable :cljs :default) throwable
      (cond
        (cas-failure-data throwable)
        (let [cause-data (cas-failure-data throwable)]
          (throw
           (ex-info
            "A Datalevin relationship mutation lost a commit-time fence."
            {:type :eacl/relationship-concurrent-write
             :eacl/error :eacl/relationship-concurrent-write
             :backend :datalevin
             :datalevin-error cause-data}
            throwable)))

        (datalevin-failure-data throwable :datalevin/stale-generation)
        (let [cause-data
              (datalevin-failure-data throwable :datalevin/stale-generation)]
          (fork/refresh-connection! conn)
          (throw
           (ex-info
            "A shared Datalevin connection prepared from a stale generation."
            {:type :eacl.datalevin/stale-connection-generation
             :eacl/error :eacl.datalevin/stale-connection-generation
             :backend :datalevin
             :datalevin-error cause-data}
            throwable)))

        :else
        (throw throwable)))))

(defn- derefable?
  [value]
  #?(:clj (instance? clojure.lang.IDeref value)
     :cljs (satisfies? IDeref value)))

(defn- advance-revision-watermark!
  [{:keys [revision] :as native-revision} opts]
  (datalevin-backend/exact-natural! :committed-revision revision)
  (let [watermark-state (:revision-watermark opts)
        advance! (:advance-revision-watermark! opts)]
    (try
      (advance! revision)
      (catch #?(:clj Throwable :cljs :default) error
        (throw
         (ex-info
          "Datalevin committed, but external revision-watermark persistence failed."
          {:type :eacl.datalevin/revision-watermark-persistence-failed
           :eacl/error :eacl.datalevin/revision-watermark-persistence-failed
           :committed-revision native-revision}
          error))))
    (let [persisted @watermark-state]
      (when-not (and (integer? persisted)
                     (<= revision persisted 9007199254740991))
        (throw
         (ex-info
          "Datalevin committed, but the external revision watermark did not advance."
          {:type :eacl.datalevin/revision-watermark-not-persisted
           :eacl/error :eacl.datalevin/revision-watermark-not-persisted
           :committed-revision native-revision
           :observed-watermark persisted})))))
  native-revision)

(defn- snapshot-entid
  [snapshot-or-db eid-or-ref]
  (ddb/with-db snapshot-or-db #(ds/entid % eid-or-ref)))

(defn- snapshot-object-id
  [snapshot-or-db eid]
  (ddb/with-db
   snapshot-or-db
   #(some-> (ddb/eavt-datoms % eid :eacl/id) first :v)))

(defn- snapshot-schema-generation
  [snapshot-or-db]
  (ddb/with-db snapshot-or-db schema/current-schema-generation))

(defn- snapshot-relationship-relation-id
  [snapshot-or-db relationship]
  (ddb/with-db
   snapshot-or-db
   #(impl/relationship-relation-id % relationship)))

(defn- snapshot-relation-coordinate
  [snapshot-or-db relation-id]
  (ddb/with-db
   snapshot-or-db
   (fn [db]
     (let [entity (ds/entity db relation-id)
           resource-type (:eacl.relation/resource-type entity)
           relation-name (:eacl.relation/relation-name entity)
           subject-type (:eacl.relation/subject-type entity)]
       (when (and resource-type relation-name subject-type)
         [:relation resource-type relation-name subject-type])))))

(defn- snapshot-tx-update-relationship
  [snapshot-or-db update]
  (ddb/with-db snapshot-or-db #(impl/tx-update-relationship % update)))

(defn- snapshot-tx-delete-object
  [snapshot-or-db object-eid]
  (ddb/with-db snapshot-or-db #(impl/tx-delete-object % object-eid)))

(defn- snapshot-read-relationships
  ([snapshot-or-db query kernel]
   (snapshot-read-relationships snapshot-or-db query kernel nil))
  ([snapshot-or-db query kernel window-options]
   (ddb/with-db
    snapshot-or-db
    #(impl/read-relationships % query kernel window-options))))

(def ^:private base-api
  {:backend-id :datalevin
   :writer-max-attempts 8
   :writer-contention? stale-connection-contention?
   :db ds/db
   :entid snapshot-entid
   :default-entid->object-id snapshot-object-id
   :basis-adapter datalevin-backend/basis-adapter
   :basis-adapter-config-keys datalevin-backend/adapter-config-keys
   :source datalevin-backend/source
   :basis-kind datalevin-backend/basis-kind
   :database-source-scope datalevin-backend/database-source-scope
   :db-native-revision
   (fn [db]
     {:revision (datalevin-backend/exact-natural!
                 :committed-revision (:max-tx db))
      :exact-locator nil})
   :after-commit! advance-revision-watermark!
   :native-source-id datalevin-backend/connection-source-id
   :prepared-native-source-id-key prepared-native-source-id-key
   :relationship-retraction-count relationship-retraction-count
   ;; The per-open admission token is closed over by `api-for-write-token`.
   :transact! nil
   ;; Vars, not values: late binding keeps instrumentation (with-redefs in
   ;; the impl suites) and REPL redefinition visible through the shared
   ;; orchestration.
   :schema {:read-schema #'schema/read-schema
            :generation snapshot-schema-generation
            :write-schema! nil}
   :impl {:validate-relationship-operation!
          #'impl/validate-relationship-operation!
          :relationship-relation-id snapshot-relationship-relation-id
          :relation-coordinate snapshot-relation-coordinate
          :tx-update-relationship snapshot-tx-update-relationship
          :tx-delete-object snapshot-tx-delete-object
          :affected-relation-ids #'impl/affected-relation-ids
          :read-relationships snapshot-read-relationships}
   :extra-client-opt-keys
   extra-client-opt-keys})

(defn- api-for-client-context
  [write-token schema-eid]
  (-> base-api
      (assoc
       ;; Direct public snapshots are constructed from the client's API after
       ;; shared orchestration has reduced runtime options to backend-neutral
       ;; state. Close the bootstrap-resolved singleton eid over the adapter
       ;; constructor so that this path keeps the same one-probe generation
       ;; lookup as provider-owned snapshots without accepting caller input.
       :basis-adapter
       (fn [snapshot opts]
         (datalevin-backend/basis-adapter
          snapshot
          (assoc opts
                 datalevin-backend/prepared-schema-eid-key schema-eid)))
       :transact!
       (fn [conn native-tx]
         (transact-native! write-token conn native-tx)))
      (assoc-in
       [:schema :write-schema!]
       (fn [conn schema-string options expected-generation]
         (schema/write-schema!
          conn schema-string options expected-generation write-token)))))

(defn- require-datalevin-client!
  [client fn-name]
  (when-not (orchestration/client? client :datalevin)
    (throw (ex-info (str fn-name " requires a Datalevin EACL client.")
                    {:type :eacl/invalid-client :eacl/error :eacl/invalid-client}))))

(defn expire-cache!
  "Datalevin lifecycle rotation is an external durability operation.

  A process-local cache rotation would claim a new source lifecycle without
  proving it was retained across restart. Recreate the client only after the
  operator has durably stored the new lifecycle and coordinated any restored
  revision watermark."
  ([client]
   (require-datalevin-client! client "expire-cache!")
   (throw
    (ex-info
     "Datalevin lifecycle rotation must be persisted before client recreation."
     {:type :eacl.datalevin/source-lifecycle-persistence-required
      :eacl/error :eacl.datalevin/source-lifecycle-persistence-required
      :operation :expire-cache!})))
  ([client source-lifecycle]
   (require-datalevin-client! client "expire-cache!")
   (throw
    (ex-info
     "Datalevin lifecycle rotation must be persisted before client recreation."
     {:type :eacl.datalevin/source-lifecycle-persistence-required
      :eacl/error :eacl.datalevin/source-lifecycle-persistence-required
      :operation :expire-cache!
      :requested-source-lifecycle source-lifecycle}))))

(def prepare-cache-coherence!
  "Initializes missing native cache generations on a quiesced connection.

  This does not detect or repair earlier unsupported unstamped mutations and
  is not a cache flush."
  schema/prepare-cache-coherence!)

(defn clear-answer-cache!
  "Evicts completed answers and resumable page state without rotating the
  persisted Datalevin source lifecycle or discarding schema-derived plans.

  This is an operational cache clear, not recovery from restore, rollback, or
  unsupported mutation. Those events still require durable lifecycle and
  watermark rotation followed by client recreation."
  [client]
  (require-datalevin-client! client "clear-answer-cache!")
  (orchestration/clear-answer-cache! client))

(defn cache-stats
  "Returns private completed-cache counters for one Datalevin EACL client."
  [client]
  (require-datalevin-client! client "cache-stats")
  (orchestration/cache-stats client))

(defn refresh-metrics!
  "Evicts cache-only metrics; optionally recomputes structural metrics now."
  ([client]
   (require-datalevin-client! client "refresh-metrics!")
   (orchestration/refresh-metrics! client))
  ([client opts]
   (require-datalevin-client! client "refresh-metrics!")
   (orchestration/refresh-metrics! client opts)))

(defn make-client
  "Builds an EACL acl over a Datalevin conn.

  Options (unknown keys throw :eacl/invalid-config - a silently ignored key
  means silently wrong ID coercion, audit 5):
  - :entid->object-id  (fn [db eid] external-id) - canonical.
  - :object-id->lookup-ref (fn [external-id] lookup-ref). Default: [:eacl/id id].
  - :cache - omitted creates a bounded client-private basis
    cache; eacl.cache/no-cache disables it; a config map bounds it.
    Exact lookup precedes proof-backed reuse. The adapter supplies certified
    ordered schema/relation generations, so completed answers and managed
    subproblems may lift across commits that leave their complete dependency
    frame unchanged. Authorization mutations must use EACL APIs or intact
    EACL-produced transaction data.
  - :cursor-ttl-seconds - optional cursor token expiry; default nil (tokens never expire).
  - :identity-immutable? - whether one internal object's public identity is
    immutable for this source lifecycle. The built-in :eacl/id codec defaults
    true; set false when IDs may be reassigned so cursors stay exact-basis-bound.
    Custom codecs must set true explicitly to enable proof-equivalent cursors.
  - :maximum-snapshot-retention-ms - optional positive upper bound for an
    EACL snapshot wrapper. Once exceeded, the next access releases an owned
    Datalevin reader and fails with :eacl/snapshot-retention-exceeded.
  - :internal-cursor->spice / :spice-cursor->internal - advanced cursor coercion overrides.

  Datalevin is current-basis-only across requests. It does not retain old DB
  values and rejects :at-exact-snapshot before cache access."
  [conn config-opts]
  (let [known-keys
        (into orchestration/base-client-opt-keys extra-client-opt-keys)]
    (when-let [unknown-keys
               (seq (remove known-keys (keys config-opts)))]
      (throw
       (ex-info
        "EACL Config Error: unknown Datalevin make-client option."
        {:type :eacl/invalid-config
         :eacl/error :eacl/invalid-config
         :unknown-keys (vec unknown-keys)
         :known-keys known-keys}))))
  (when (contains? config-opts prepared-native-source-id-key)
    (throw
     (ex-info
      "Datalevin prepared source identity is module-internal."
      {:type :eacl/invalid-config
       :eacl/error :eacl/invalid-config
       :key prepared-native-source-id-key})))
  (when (contains? config-opts datalevin-backend/prepared-schema-eid-key)
    (throw
     (ex-info
      "Datalevin prepared schema identity is module-internal."
      {:type :eacl/invalid-config
       :eacl/error :eacl/invalid-config
       :key datalevin-backend/prepared-schema-eid-key})))
  (when-not (contains? config-opts :source-lifecycle)
    (throw
     (ex-info
      "Datalevin requires an externally retained :source-lifecycle."
      {:type :eacl/invalid-config
       :eacl/error :eacl/invalid-config
       :key :source-lifecycle})))
  (when-not (some? (:source-lifecycle config-opts))
    (throw
     (ex-info
      "Datalevin requires a non-nil externally retained :source-lifecycle."
      {:type :eacl/invalid-config
       :eacl/error :eacl/invalid-config
       :key :source-lifecycle
       :value nil})))
  (when-not (or (contains? config-opts :security-key)
                (contains? config-opts :security-keyring))
    (throw
     (ex-info
      "Datalevin requires an externally retained token-signing key or keyring."
      {:type :eacl/invalid-config
       :eacl/error :eacl/invalid-config
       :key :security-key})))
  (when-not (contains? config-opts :revision-watermark)
    (throw
     (ex-info
      "Datalevin requires an externally retained :revision-watermark."
      {:type :eacl/invalid-config
       :eacl/error :eacl/invalid-config
       :key :revision-watermark})))
  (when-not (derefable? (:revision-watermark config-opts))
    (throw
     (ex-info
      "Datalevin :revision-watermark must be an externally retained dereferenceable state."
      {:type :eacl/invalid-config
       :eacl/error :eacl/invalid-config
       :key :revision-watermark
       :value (:revision-watermark config-opts)})))
  (when-not (fn? (:advance-revision-watermark! config-opts))
    (throw
     (ex-info
      "Datalevin requires synchronous external revision-watermark persistence."
      {:type :eacl/invalid-config
       :eacl/error :eacl/invalid-config
       :key :advance-revision-watermark!})))
  (when (and
         (contains? config-opts :maximum-snapshot-retention-ms)
         (not
          (and
           (integer? (:maximum-snapshot-retention-ms config-opts))
           (pos? (:maximum-snapshot-retention-ms config-opts))
           (<= (:maximum-snapshot-retention-ms config-opts)
               9007199254740991))))
    (throw
     (ex-info
      "Datalevin :maximum-snapshot-retention-ms must be a positive portable exact integer."
      {:type :eacl/invalid-config
       :eacl/error :eacl/invalid-config
       :key :maximum-snapshot-retention-ms
       :value (:maximum-snapshot-retention-ms config-opts)
       :maximum 9007199254740991})))
  (let [watermark-value (:revision-watermark config-opts)
        watermark @watermark-value]
    (when-not (and (integer? watermark)
                   (not (neg? watermark))
                   (<= watermark 9007199254740991))
      (throw
       (ex-info
        "Datalevin :revision-watermark must be an exact nonnegative integer."
        {:type :eacl/invalid-config
         :eacl/error :eacl/invalid-config
         :key :revision-watermark
         :value watermark})))
    (datalevin-backend/validate-fork-capabilities! conn)
    (datalevin-backend/validate-topology! conn config-opts)
    (let [revision (:max-tx (ds/db conn))]
      (when (< revision watermark)
        (throw
         (ex-info
          "Datalevin revision regressed below the external watermark."
          {:type :eacl.datalevin/revision-regression
           :eacl/error :eacl.datalevin/revision-regression
           :revision revision
           :revision-watermark watermark})))))
  (target-storage/assert-compatible! (ds/db conn))
  (let [{:keys [source-id schema-eid write-token]}
        (schema/ensure-physical-schema! conn)
        validated-source-id (datalevin-backend/connection-source-id conn)
        _ (when-not (= (str source-id) validated-source-id)
            (throw
             (ex-info
              "Datalevin bootstrap and persisted source identities disagree."
              {:type :eacl/invalid-source-identity
               :eacl/error :eacl/invalid-source-identity
               :backend :datalevin
               :bootstrap-source-id source-id
               :persisted-source-id validated-source-id})))
        native-revision
        {:revision (datalevin-backend/exact-natural!
                    :startup-revision (:max-tx (ds/db conn)))
         :exact-locator nil}]
    ;; Readiness is not acknowledged until the externally retained watermark
    ;; covers every bootstrap transaction visible at this lifecycle.
    (advance-revision-watermark! native-revision config-opts)
    (orchestration/make-client
     (api-for-client-context write-token schema-eid)
     conn
     (assoc config-opts
            prepared-native-source-id-key validated-source-id
            datalevin-backend/prepared-schema-eid-key schema-eid))))

(defn db
  "Returns the open Datalevin value held by an EACL-created snapshot."
  [snapshot]
  (orchestration/snapshot-db snapshot :datalevin))

(defn create-conn
  "A Datalevin connection carrying EACL's schema. See
  `eacl.datalevin.schema/create-conn` for the config options."
  ([] (schema/create-conn))
  ([dir] (schema/create-conn dir))
  ([dir extra-schema] (schema/create-conn dir extra-schema))
  ([dir extra-schema store-options]
   (schema/create-conn dir extra-schema store-options)))
