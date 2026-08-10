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

(defn- component-attributes
  [db]
  (into #{}
        (keep (fn [[attribute options]]
                (when (true? (:db/isComponent options))
                  attribute)))
        (:schema db)))

(defn- control-entity-data
  [db eid]
  (let [entity (ds/entity db eid)]
    {:db-ident (:db/ident entity)
     :eacl-id (:eacl/id entity)
     :schema-string (:eacl/schema-string entity)
     :relation-name (:eacl.relation/relation-name entity)
     :permission-name (:eacl.permission/permission-name entity)}))

(defn- relation-triples
  [db]
  (mapv (fn [{:keys [e v]}]
          [(nth v 0) e (nth v 2)])
        (ddb/avet-datoms
         db :eacl.relation/resource-type+relation-name+subject-type)))

(defn- known-ghost-plan
  [db target-eid]
  (safe/combine-plans
   (mapcat
    (fn [[resource-type relation-eid subject-type]]
      (let [reverse-value
            [resource-type relation-eid subject-type target-eid]
            forward-value
            [subject-type relation-eid resource-type target-eid]]
        (concat
         (for [{peer-eid :e}
               (ddb/avet-datoms db storage/reverse-attribute reverse-value)]
           {:peer-retractions
            [[:db/retract peer-eid storage/reverse-attribute reverse-value]]
            :relation-ids [relation-eid]
            :local-half-count 0})
         (for [{peer-eid :e}
               (ddb/avet-datoms db storage/forward-attribute forward-value)]
           {:peer-retractions
            [[:db/retract peer-eid storage/forward-attribute forward-value]]
            :relation-ids [relation-eid]
            :local-half-count 0}))))
    (relation-triples db))))

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
              (let [component-attrs (component-attributes db)]
                (safe/component-closure
                 target-eid
                 (fn [eid]
                   (into []
                         (comp
                          (filter #(contains? component-attrs (:a %)))
                          (map :v))
                         (ds/datoms db :eavt eid))))))
            protected-eid
            (some (fn [eid]
                    (when (safe/protected-control-entity?
                           (control-entity-data db eid))
                      eid))
                  closure)]
        (when protected-eid
          (throw
           (ex-info
            "EACL safe retraction cannot delete schema/control entities."
            {:type :eacl.safe-retraction/invalid
             :eacl/error :eacl.safe-retraction/invalid
             :reason :protected-control-entity
             :target-eid target-eid
             :protected-eid protected-eid})))
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
                  (known-ghost-plan db target-eid)
                  {:peer-retractions []
                   :relation-ids []
                   :local-half-count 0}))]
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
