(ns eacl.datascript.safe-retraction
  "Optional installed/direct DataScript transaction function for safe entity
  retraction. Installation is connection-local and is never part of the
  default EACL DataScript schema."
  (:require [clojure.string :as str]
            [datascript.core :as ds]
            [eacl.datascript.db :as ddb]
            [eacl.relationships.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]))

(def function-digest
  "b95f310357fa216e59435c6d15a8adaea910650b0ee5d602f9d98e5cbeed4361")

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
  "DataScript target-only transaction function implementation."
  [db target]
  (safe/validate-target! target)
  (let [target-eid (ds/entid db target)
        lookup-ref? (vector? target)]
    (if (and lookup-ref? (nil? target-eid))
      []
      (let [live? (and target-eid
                       (seq (ds/datoms db :eavt target-eid)))
            closure
            (when live?
              (let [component-attrs (safe/component-attributes (:schema db))]
                (safe/component-closure
                 target-eid
                 (fn [eid]
                   (into []
                         (comp
                          (filter #(contains? component-attrs (:a %)))
                          (map :v))
                         (ds/datoms db :eavt eid))))))]
        (safe/ensure-unprotected! target-eid closure #(ds/entity db %))
        (let [plan
              (if live?
                (safe/combine-plans
                 (map (fn [eid]
                        (safe/plan-local-halves
                         eid
                         (mapv :v (ds/datoms db :eavt eid
                                            storage/forward-attribute))
                         (mapv :v (ds/datoms db :eavt eid
                                            storage/reverse-attribute))))
                      closure))
                (if target-eid
                  (safe/known-ghost-plan
                   (storage/relation-triples
                    (ddb/avet-datoms
                     db :eacl.relation/resource-type+relation-name+subject-type))
                   target-eid
                   (fn [attribute value]
                     (ddb/global-relationship-identity-datoms db attribute value)))
                  safe/empty-plan))]
          (into []
                (concat
                 (:peer-retractions plan)
                 (safe/relation-stamps (:relation-ids plan))
                 (when live?
                   [[:db.fn/retractEntity target-eid]]))))))))

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
  "Returns the direct invocation capability; no journal preparation is needed."
  [_conn]
  {:prepared? true :support support})

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
  [target]
  (safe/target-invocation target))

(defn direct-retract-entity-tx-data
  "Builds one in-process `:db.fn/call` invocation without installation.

  Call `prepare!` once for a new connection before submitting this data."
  [target]
  (safe/validate-target! target)
  ;; Wrap the target so the same tx data works for numeric eids and lookup
  ;; refs without any envelope or caller-generated metadata.
  [[:db.fn/call (fn [db wrapped-target]
                  (retract-entity-function db (first wrapped-target)))
    [target]]])
