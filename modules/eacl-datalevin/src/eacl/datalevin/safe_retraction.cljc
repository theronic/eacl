(ns eacl.datalevin.safe-retraction
  "Optional in-process Datalevin transaction function for safe entity
  retraction. Datalevin cannot persist arbitrary Clojure function values, so
  this adapter deliberately supports only direct `:db.fn/call` invocation."
  (:require [datalevin.core :as ds]
            [eacl.datalevin.db :as ddb]
            [eacl.relationships.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]))

(def support
  (safe/support-descriptor
   {:backend :datalevin
    :mode :direct
    :reason :in-process-db-fn-call
    :requires-installation? false
    :transport-safe? false}))

(defn support-descriptor
  []
  support)

(defn- component-attributes
  [db]
  (into #{}
        (keep (fn [[attribute options]]
                (when (true? (:db/isComponent options))
                  attribute)))
        (ds/schema db)))

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
  "Datalevin target-only transaction function implementation."
  [db target]
  (safe/validate-target! target)
  (let [target-eid (ds/entid db target)
        lookup-ref? (vector? target)]
    (if (and lookup-ref? (nil? target-eid))
      []
      (let [live? (and target-eid
                       (seq (ds/datoms db :eav target-eid)))
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
                         (ds/datoms db :eav eid))))))
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
                         (mapv :v (ds/datoms db :eav eid
                                            storage/forward-attribute))
                         (mapv :v (ds/datoms db :eav eid
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

(defn prepare!
  "Returns the direct invocation capability; no installation is needed."
  [_conn]
  {:installed? false :state :direct :support support})

(defn install!
  "Always rejects named installation. Datalevin's value encoding does not
  round-trip arbitrary Clojure functions; use `prepare!` and direct tx data."
  [_conn]
  (throw
   (ex-info
    "Datalevin cannot persist the EACL Clojure transaction function; use direct prepared invocation."
    {:type :eacl.safe-retraction/installation-unavailable
     :eacl/error :eacl.safe-retraction/installation-unavailable
     :backend :datalevin
     :support support
     :alternative
     {:prepare 'eacl.datalevin.safe-retraction/prepare!
      :tx-data 'eacl.datalevin.safe-retraction/retract-entity-tx-data}})))

(defn retract-entity-tx-data
  "Builds one in-process invocation. Call `prepare!` before use."
  [target]
  (safe/validate-target! target)
  [[:db.fn/call (fn [db wrapped-target]
                  (retract-entity-function db (first wrapped-target)))
    [target]]])

(defn direct-retract-entity-tx-data
  "Builds one in-process `:db.fn/call` invocation without installation.

  Call `prepare!` once for a new connection before submitting this data."
  [target]
  (retract-entity-tx-data target))
