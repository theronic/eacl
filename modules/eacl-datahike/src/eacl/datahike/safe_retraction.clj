(ns eacl.datahike.safe-retraction
  "Capability-driven safe entity retraction for Datahike.

  Schema-on-read, in-process databases can store the named function. Strict
  schema-on-write databases use an in-process `:db.fn/call` value and keep
  their configuration unchanged. Remote writer transports are reported as
  unsupported because arbitrary function values are not transport-safe."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.mutation :as journal]
            [eacl.relationships.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]))

(def function-digest
  "8db6ea954b6e22fd681d394a26963498e787c1f1bfbf6756c50855ff7a226c3b")

(def function-doc
  (str safe/function-doc-prefix " v" safe/function-version
       "; digest=" function-digest
       "; optional Datahike transaction function"))

(defn support-descriptor
  "Reports support from the actual database configuration.

  This is deliberately capability-driven instead of a Datahike version test."
  [db]
  (let [{:keys [schema-flexibility attribute-refs? writer]} (:config db)
        writer-backend (:backend writer)
        local-writer? (or (nil? writer-backend) (= :self writer-backend))
        common {:backend :datahike
                :ident safe/function-ident
                :schema-flexibility schema-flexibility
                :attribute-representation (if attribute-refs? :ref :keyword)
                :writer-topology (if local-writer? :in-process :remote)}]
    (cond
      (not local-writer?)
      (safe/support-descriptor
       (merge common
              {:mode :unsupported
               :reason :function-transport-unsafe
               :requires-installation? false}))

      (= :read schema-flexibility)
      (safe/support-descriptor
       (merge common
              {:mode :named
               :reason :stored-function-value
               :requires-installation? true
               :reinstall-after-restore? true}))

      (= :write schema-flexibility)
      (safe/support-descriptor
       (merge common
              {:mode :direct
               :reason :in-process-db-fn-call
               :requires-installation? false
               :transport-safe? false}))

      :else
      (safe/support-descriptor
       (merge common
              {:mode :unsupported
               :reason :unknown-schema-flexibility
               :requires-installation? false})))))

(defn- native-mutation-data
  [db tx-data]
  (mapv
   (fn [op]
     (if (and (vector? op) (= :db.fn/cas (first op)))
       (assoc op 2 (ddb/attr-repr db (nth op 2)))
       op))
   tx-data))

(defn retract-entity-function
  [db target envelope]
  (safe/validate-envelope target envelope)
  (let [target-eid (ddb/entid db target)]
    (if-not (and target-eid (ddb/entity-exists? db target-eid))
      []
      (let [forward-values
            (mapv :v (ddb/eavt-datoms db target-eid
                                      storage/forward-attribute))
            reverse-values
            (mapv :v (ddb/eavt-datoms db target-eid
                                      storage/reverse-attribute))
            {:keys [peer-retractions relation-ids]}
            (safe/plan-local-halves target-eid
                                    forward-values reverse-values)
            mutation-data
            (native-mutation-data
             db
             (safe/mutation-tx-data
              (journal/graph-state db)
              relation-ids
              :db/current-tx
              envelope))]
        (into (into mutation-data peer-retractions)
              [[:db.fn/retractEntity target-eid]])))))

(defn retract-entity-direct-function
  "Direct-call argument order avoids a Datahike attribute-ref parser edge:
  the transaction parser examines the first user argument as if it were an
  attribute before dispatching `:db.fn/call`. The envelope is non-numeric,
  whereas a raw target eid commonly is numeric."
  [db envelope target]
  (retract-entity-function db target envelope))

(def function-definition
  {:db/ident safe/function-ident
   :db/doc function-doc
   :db/fn retract-entity-function})

(defn installation-state
  [db]
  (if-let [eid (ddb/entid db safe/function-ident)]
    (let [entity (d/entity db eid)
          doc (:db/doc entity)
          installed-fn (:db/fn entity)]
      (cond
        (and (= function-doc doc) (fn? installed-fn)) :current
        (and (string? doc)
             (str/starts-with? doc safe/function-doc-prefix)) :upgradeable
        :else :conflict))
    :absent))

(defn prepare!
  "Prepares mutation-journal state and, in named mode, installs the function.

  Returns the effective capability and installation state."
  [conn]
  (let [initial-support (support-descriptor (d/db conn))]
    (when (= :unsupported (:mode initial-support))
      (throw
       (ex-info
        "This Datahike writer cannot safely transport an EACL transaction function."
        {:type :eacl.safe-retraction/unsupported
         :eacl/error :eacl.safe-retraction/unsupported
         :backend :datahike
         :support initial-support})))
    (journal/ensure-migrated! conn)
    (let [db (d/db conn)
          support (support-descriptor db)]
    (case (:mode support)
      :direct
      {:installed? false :state :direct :support support}

      :named
      (let [state (installation-state db)]
        (case state
          :current
          {:installed? false :state :current :support support}

          :absent
          (do
            (d/transact conn [function-definition])
            (when-not (= :current (installation-state (d/db conn)))
              (throw
               (ex-info
                "Datahike did not round-trip the installed EACL function value."
                {:type :eacl.safe-retraction/function-round-trip-failed
                 :eacl/error
                 :eacl.safe-retraction/function-round-trip-failed
                 :backend :datahike})))
            {:installed? true :state state :support support})

          :upgradeable
          (let [function-eid (ddb/entid db safe/function-ident)]
            ;; Datahike classifies an entity carrying :db/fn as schema data and
            ;; rejects changing that property in place. A recognized EACL
            ;; marker authorizes a deliberate remove/reinstall upgrade.
            (d/transact conn [[:db.fn/retractEntity function-eid]
                              function-definition])
            (when-not (= :current (installation-state (d/db conn)))
              (throw
               (ex-info
                "Datahike did not round-trip the upgraded EACL function value."
                {:type :eacl.safe-retraction/function-round-trip-failed
                 :eacl/error
                 :eacl.safe-retraction/function-round-trip-failed
                 :backend :datahike})))
            {:installed? true :state state :support support})

          :conflict
          (throw
           (ex-info
            "The Datahike ident :eacl.fn/retractEntity is already owned by another function."
            {:type :eacl.safe-retraction/install-conflict
             :eacl/error :eacl.safe-retraction/install-conflict
             :backend :datahike
             :ident safe/function-ident}))))

      :unsupported
      (throw
       (ex-info
        "This Datahike writer cannot safely transport an EACL transaction function."
        {:type :eacl.safe-retraction/unsupported
         :eacl/error :eacl.safe-retraction/unsupported
         :backend :datahike
         :support support}))))))

(defn install!
  "Installs the named function when the current database supports that mode.

  Direct mode is deliberately not reported as an installation: callers must
  opt into `prepare!` plus `retract-entity-tx-data`, which keeps the function
  value inside the in-process writer boundary."
  [conn]
  (let [support (support-descriptor (d/db conn))]
    (case (:mode support)
      :named (prepare! conn)
      :direct
      (throw
       (ex-info
        "This Datahike configuration cannot store a named transaction function; use the direct prepared invocation."
        {:type :eacl.safe-retraction/installation-unavailable
         :eacl/error :eacl.safe-retraction/installation-unavailable
         :backend :datahike
         :support support
         :alternative
         {:prepare 'eacl.datahike.safe-retraction/prepare!
          :tx-data 'eacl.datahike.safe-retraction/retract-entity-tx-data}}))
      :unsupported
      (throw
       (ex-info
        "This Datahike writer cannot safely transport an EACL transaction function."
        {:type :eacl.safe-retraction/unsupported
         :eacl/error :eacl.safe-retraction/unsupported
         :backend :datahike
         :support support
         :alternative 'eacl.core/delete-object!})))))

(defn retract-entity-tx-data
  "Builds one named or direct invocation for the supplied database value.

  Call `prepare!` on the connection first. The database argument makes mode
  selection explicit and prevents a direct function value from accidentally
  crossing a remote writer boundary."
  ([db target]
   (retract-entity-tx-data db target {}))
  ([db target options]
   (let [support (support-descriptor db)
         envelope (safe/mutation-envelope target options)]
     (safe/validate-envelope target envelope)
     (case (:mode support)
       :named [[safe/function-ident target envelope]]
       :direct [[:db.fn/call retract-entity-direct-function envelope target]]
       :unsupported
       (throw
        (ex-info
         "This Datahike writer does not support safe transaction-function invocation."
         {:type :eacl.safe-retraction/unsupported
          :eacl/error :eacl.safe-retraction/unsupported
          :backend :datahike
          :support support}))))))
