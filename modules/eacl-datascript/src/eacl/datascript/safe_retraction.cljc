(ns eacl.datascript.safe-retraction
  "Optional installed/direct DataScript transaction function for safe entity
  retraction. Installation is connection-local and is never part of the
  default EACL DataScript schema."
  (:require [clojure.string :as str]
            [datascript.core :as ds]
            [eacl.datascript.mutation :as journal]
            [eacl.relationships.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]))

(def function-digest
  "5aeb7ef5d50d859a245f8220a837085e85af0825276478daa28a5bb9d453ac01")

(def function-doc
  (str safe/function-doc-prefix " v" safe/function-version
       "; digest=" function-digest
       "; optional DataScript transaction function"))

(def support
  (safe/support-descriptor
   {:backend :datascript
    :mode :named
    :reason :installed-transaction-function
    :ident safe/function-ident
    :requires-installation? true
    :reinstall-after-restore? true}))

(defn support-descriptor
  []
  support)

(defn retract-entity-function
  "DataScript transaction function implementation.

  It performs exactly two target-scoped endpoint reads. Peer operations and
  proof bookkeeping grow only with the target's local degree and the number
  of distinct affected relations."
  [db target envelope]
  (safe/validate-envelope target envelope)
  (let [target-eid (ds/entid db target)]
    (if-not (and target-eid (seq (ds/datoms db :eavt target-eid)))
      []
      (let [forward-values
            (mapv :v (ds/datoms db :eavt target-eid
                                storage/forward-attribute))
            reverse-values
            (mapv :v (ds/datoms db :eavt target-eid
                                storage/reverse-attribute))
            {:keys [peer-retractions relation-ids]}
            (safe/plan-local-halves target-eid
                                    forward-values reverse-values)
            mutation-data
            ;; Unlike :db.fn/call, DataScript's named-function expansion does
            ;; not auto-assign tempids to returned entity maps.
            (update
             (safe/mutation-tx-data
              (journal/graph-state db)
              relation-ids
              :db/current-tx
              envelope)
             0 assoc :db/id -1)]
        (into (into mutation-data peer-retractions)
              [[:db.fn/retractEntity target-eid]])))))

(def function-definition
  {:db/ident safe/function-ident
   :db/doc function-doc
   :db/fn retract-entity-function})

(defn installation-state
  [db]
  (if-let [eid (ds/entid db safe/function-ident)]
    (let [entity (ds/entity db eid)
          doc (:db/doc entity)
          installed-fn (:db/fn entity)]
      (cond
        (and (= function-doc doc) (fn? installed-fn)) :current
        (and (string? doc)
             (str/starts-with? doc safe/function-doc-prefix)) :upgradeable
        :else :conflict))
    :absent))

(defn prepare!
  "Ensures mutation-journal prerequisites for direct invocation."
  [conn]
  (journal/ensure-migrated! conn))

(defn install!
  "Idempotently installs the named function into one DataScript connection.

  DataScript database serialization does not portably preserve arbitrary
  function values; call this again after restore."
  [conn]
  (prepare! conn)
  (let [state (installation-state (ds/db conn))]
    (case state
      :current
      {:installed? false :state :current :support support}

      (:absent :upgradeable)
      (do
        (ds/transact! conn [function-definition])
        {:installed? true :state state :support support})

      :conflict
      (throw
       (ex-info
        "The DataScript ident :eacl.fn/retractEntity is already owned by another function."
        {:type :eacl.safe-retraction/install-conflict
         :eacl/error :eacl.safe-retraction/install-conflict
         :backend :datascript
         :ident safe/function-ident})))))

(defn retract-entity-tx-data
  "Builds one invocation of the installed named function."
  ([target]
   (retract-entity-tx-data target {}))
  ([target options]
   (let [envelope (safe/mutation-envelope target options)]
     (safe/validate-envelope target envelope)
     [[safe/function-ident target envelope]])))

(defn direct-retract-entity-tx-data
  "Builds one in-process `:db.fn/call` invocation without installation.

  Call `prepare!` once for a new connection before submitting this data."
  ([target]
   (direct-retract-entity-tx-data target {}))
  ([target options]
   (let [envelope (safe/mutation-envelope target options)]
     (safe/validate-envelope target envelope)
     [[:db.fn/call retract-entity-function target envelope]])))
