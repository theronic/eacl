(ns eacl.datalevin.backend
  "Owned explicit-snapshot storage operations for the EACL v8 engine."
  (:require [clojure.set :as set]
            [datalevin.core :as d]
            [eacl.backend.snapshot-provider :as snapshot-provider]
            [eacl.backend.v8 :as backend]
            [eacl.datalevin.db :as ddb]
            [eacl.datalevin.impl :as impl]
            [eacl.secure-format :as secure-format]))

(def capabilities
  {:consistency #{:minimize-latency
                  :fully-consistent
                  :at-least-as-fresh}
   :snapshots #{:current :authoritative :causal}
   :source #{:stable-scope :source-lifecycle :native-revision :order-hint}
   :cursor #{:forward :reverse :opaque}
   :transactions #{:schema :relationships :object-deletion}
   ;; Datalevin persistent datoms do not expose their original transaction.
   ;; No ordered-generation proof is claimed by the initial adapter.
   :cache-proofs #{:snapshot-bound :database-visible}
   :runtime #{:clj}})

(def execution-constraints
  {:virtual-threads :rejected
   :snapshot-thread :acquiring-thread
   :release-thread :acquiring-thread})

(def certified-topology-declaration
  {:deployment :embedded
   :jvms 1
   :connections 1
   :writers 1
   :writer-ownership :application-exclusive
   :commit-mode :direct-synchronous
   :physical-schema :frozen
   :request-threads :platform
   :wal false})

(def topology
  (assoc certified-topology-declaration
         :snapshot-values :owned-explicit))

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

(defn- normalized-permission
  [permission]
  {:permission-id (exact-natural! :permission-id (:db/id permission))
   :resource-type (:eacl.permission/resource-type permission)
   :permission-name (:eacl.permission/permission-name permission)
   :source-relation-name
   (:eacl.permission/source-relation-name permission)
   :target-type (:eacl.permission/target-type permission)
   :target-name (:eacl.permission/target-name permission)})

(defn- snapshot-info
  [snapshot]
  (when-not (d/read-snapshot? snapshot)
    (throw
     (ex-info
      "Datalevin EACL adapters require a public explicit read snapshot."
      {:type :eacl/invalid-selected-snapshot
       :eacl/error :eacl/invalid-selected-snapshot
       :backend :datalevin})))
  (let [info (d/read-snapshot-info snapshot)]
    (exact-natural! :revision (:max-tx info))
    (exact-natural! :max-eid (:max-eid info))
    info))

(defn- physical-schema-fingerprint
  [info fingerprint-cache]
  (let [schema (:schema info)
        cached (when fingerprint-cache @fingerprint-cache)]
    (if (= schema (:schema cached))
      (:fingerprint cached)
      (let [fingerprint
            (secure-format/canonical-digest
             "eacl.datalevin.physical-schema.v1"
             schema)]
        (if-not fingerprint-cache
          fingerprint
          (:fingerprint
           (swap! fingerprint-cache
                  (fn [current]
                    ;; Structural equality, rather than a hash-only lookup,
                    ;; makes this memo safe across a physical schema change.
                    ;; Concurrent readers may compute the digest twice but can
                    ;; never receive a digest for a different schema value.
                    (if (= schema (:schema current))
                      current
                      {:schema schema :fingerprint fingerprint})))))))))

(defn snapshot-adapter
  "Creates an immutable v8 adapter around one open Datalevin read snapshot.
  The provider retains ownership; every operation binds the snapshot's one
  explicit native reader and eagerly realizes its result before returning."
  [snapshot {:keys [object-id->entid entid->object-id] :as opts}]
  (let [info (snapshot-info snapshot)
        revision (:max-tx info)
        schema-identity
        (physical-schema-fingerprint
         info (:physical-schema-fingerprint-cache opts))
        source-lifecycle
        (or (some-> (:source-lifecycle-state opts) deref)
            (:source-lifecycle opts))
        source-scope
        (or (:source-scope opts)
            {:source-id (:native-source-id opts)
             :branch nil})
        adapter-opts
        (-> opts
            (dissoc :source-lifecycle-state)
            (assoc :source-lifecycle source-lifecycle
                   :source-scope source-scope))]
    (when-not (some? (:source-id source-scope))
      (throw
       (ex-info
        "Datalevin requires a persisted stable source identity."
        {:type :eacl/invalid-source-identity
         :eacl/error :eacl/invalid-source-identity
         :backend :datalevin})))
    (backend/make-adapter
     {:id :datalevin
      :traversal-execution backend/strict-sequential-traversal-execution
      :fingerprint (:adapter-fingerprint opts)
      :deterministic? (:adapter-deterministic? opts)
      :identity-contract
      (:identity-contract opts
                          :selected-internal/current-external-injective-v2)
      :capabilities capabilities
      :runtime-guards? true
      :state {:db snapshot
              :revision revision
              :max-eid (:max-eid info)
              :schema-identity schema-identity}
      :operations
      {:snapshot-id
       (fn []
         {:database-id :datalevin
          :basis-t revision
          :schema-identity schema-identity})

       :source-scope (constantly source-scope)
       :source-lifecycle (constantly source-lifecycle)
       :native-revision
       (fn [] {:revision revision :exact-locator nil})
       :order-hint (constantly revision)

       ;; These adapter-level operations exist for the closed v8 contract.
       ;; Provider-based public orchestration owns cross-request acquisition.
       :select-current
       (fn [] (snapshot-adapter snapshot adapter-opts))
       :select-authoritative
       (fn [_timeout-ms] (snapshot-adapter snapshot adapter-opts))
       :select-at-least
       (fn [token-data _timeout-ms]
         (if (>= revision (exact-natural! :token-revision
                                          (:revision token-data)))
           (snapshot-adapter snapshot adapter-opts)
           nil))
       :exact-locator (constantly nil)
       :select-exact (fn [_token-data _timeout-ms] nil)

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
           #(mapv normalized-permission
                  (impl/find-permission-defs
                   % resource-type permission-name))))

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
           impl/all-permission-nodes))}})))

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
  [conn opts]
  (let [snapshot (d/open-read-snapshot conn)]
    (try
      {:adapter (snapshot-adapter snapshot opts)
       :ownership :owned
       :release-token snapshot}
      (catch #?(:clj Throwable :cljs :default) error
        (d/close-read-snapshot! snapshot)
        (throw error)))))

(defn validate-topology!
  "Rejects any connection/runtime/declaration outside the certified embedded
  Datalevin profile before module bootstrap is allowed to mutate the store."
  [conn opts]
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
        declared-topology (:datalevin-topology opts)
        _
        (when-not (= certified-topology-declaration declared-topology)
          (throw
           (ex-info
            "Datalevin requires the exact certified sole-writer topology declaration."
            {:type :eacl/unsupported-topology
             :eacl/error :eacl/unsupported-topology
             :backend :datalevin
             :expected certified-topology-declaration
             :actual declared-topology})))
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
        (set/intersection #{:nosync :nometasync :mapasync :writemap}
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

(defn provider
  "Builds the owned, platform-thread-affine snapshot provider for one
  qualified local embedded Datalevin connection."
  [conn opts]
  (let [_ (validate-topology! conn opts)
        source-scope {:source-id (:native-source-id opts) :branch nil}
        opts (assoc opts
                    :source-scope source-scope
                    :physical-schema-fingerprint-cache (atom nil))
        source-lifecycle
        (fn []
          (or (some-> (:source-lifecycle-state opts) deref)
              (:source-lifecycle opts)))]
    (snapshot-provider/make-provider
     {:id :datalevin
      :capabilities capabilities
      :traversal-execution backend/strict-sequential-traversal-execution
      :topology topology
      :execution-constraints execution-constraints
      :snapshot-ownership :owned
      :fingerprint
      (or (:adapter-fingerprint opts)
          {:backend :datalevin
           :adapter-version backend/adapter-version
           :snapshot-api :dev.eacl/read-snapshot-v1})
      :deterministic? (:adapter-deterministic? opts)
      :operations
      {:source-scope (constantly source-scope)
       :source-lifecycle source-lifecycle
       :acquire-current! (fn [] (acquire-owned! conn opts))
       :acquire-authoritative!
       (fn [_timeout-ms] (acquire-owned! conn opts))
       ;; Shared consistency orchestration compares the revision, closes an
       ;; insufficient candidate, preserves the original deadline, and retries.
       :acquire-at-least!
       (fn [_token-data _remaining-ms] (acquire-owned! conn opts))
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
