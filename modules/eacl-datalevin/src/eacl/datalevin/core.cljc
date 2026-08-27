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
            [eacl.datalevin.impl :as impl]
            [eacl.datalevin.schema :as schema]
            [eacl.relationships.storage :as relationship-storage]))

(def cursor->token cursor/cursor->token)
(def token->cursor cursor/token->cursor)

(def default-internal-cursor->spice orchestration/default-internal-cursor->spice)
(def default-spice-cursor->internal orchestration/default-spice-cursor->internal)

(def ^:private prepared-native-source-id-key
  ::prepared-native-source-id)

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

(defn- transact-native!
  [conn {:keys [tx-data]}]
  (try
    (ds/transact! conn (vec tx-data))
    (catch #?(:clj Throwable :cljs :default) throwable
      (if-let [cause-data (cas-failure-data throwable)]
        (throw
         (ex-info
          "A Datalevin relationship mutation lost a commit-time fence."
          {:type :eacl/relationship-concurrent-write
           :eacl/error :eacl/relationship-concurrent-write
           :backend :datalevin
           :datalevin-error cause-data}
          throwable))
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

(def ^:private api
  {:backend-id :datalevin
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
   :transact! transact-native!
   ;; Vars, not values: late binding keeps instrumentation (with-redefs in
   ;; the impl suites) and REPL redefinition visible through the shared
   ;; orchestration.
   :schema {:read-schema #'schema/read-schema
            :generation snapshot-schema-generation
            :write-schema! #'schema/write-schema!}
   :impl {:validate-relationship-operation!
          #'impl/validate-relationship-operation!
          :relationship-relation-id snapshot-relationship-relation-id
          :tx-update-relationship snapshot-tx-update-relationship
          :tx-delete-object snapshot-tx-delete-object
          :affected-relation-ids #'impl/affected-relation-ids
          :read-relationships snapshot-read-relationships}
   :extra-client-opt-keys
   #{:datalevin-topology
     :revision-watermark
     :advance-revision-watermark!
     :maximum-snapshot-retention-ms
     prepared-native-source-id-key}})

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

(defn make-client
  "Builds an EACL acl over a Datalevin conn.

  Options (unknown keys throw :eacl/invalid-config - a silently ignored key
  means silently wrong ID coercion, audit 5):
  - :entid->object-id  (fn [db eid] external-id) - canonical.
  - :object-id->lookup-ref (fn [external-id] lookup-ref). Default: [:eacl/id id].
  - :cache - omitted creates a bounded client-private basis
    cache; eacl.cache/no-cache disables it; a config map bounds it.
    Completed answers reuse only at the identical complete basis identity;
    this adapter makes no ordered-generation proof claim. Certified schema
    generation still reuses derived plans across relationship-only writes. Authorization
    mutations must use EACL APIs or intact EACL-produced transaction data.
  - :cursor-ttl-seconds - optional cursor token expiry; default nil (tokens never expire).
  - :maximum-snapshot-retention-ms - optional positive upper bound for an
    EACL snapshot wrapper. Once exceeded, the next access releases an owned
    Datalevin reader and fails with :eacl/snapshot-retention-exceeded.
  - :internal-cursor->spice / :spice-cursor->internal - advanced cursor coercion overrides.

  Datalevin is current-basis-only across requests. It does not retain old DB
  values and rejects :at-exact-snapshot before cache access."
  [conn config-opts]
  (when (contains? config-opts prepared-native-source-id-key)
    (throw
     (ex-info
      "Datalevin prepared source identity is module-internal."
      {:type :eacl/invalid-config
       :eacl/error :eacl/invalid-config
       :key prepared-native-source-id-key})))
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
  (let [source-id (schema/ensure-physical-schema! conn)
        native-revision
        {:revision (datalevin-backend/exact-natural!
                    :startup-revision (:max-tx (ds/db conn)))
         :exact-locator nil}]
    ;; Readiness is not acknowledged until the externally retained watermark
    ;; covers every bootstrap transaction visible at this lifecycle.
    (advance-revision-watermark! native-revision config-opts)
    (orchestration/make-client
     api conn (assoc config-opts prepared-native-source-id-key
                     (str source-id)))))

(defn snapshot
  "Constructs a borrowed public snapshot over an open Datalevin read snapshot."
  [acl read-snapshot]
  (orchestration/direct-snapshot acl :datalevin read-snapshot))

(defn db
  "Returns the open Datalevin read snapshot wrapped by `snapshot`."
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
