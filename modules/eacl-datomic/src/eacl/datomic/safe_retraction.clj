(ns eacl.datomic.safe-retraction
  "Optional target-only Datomic transaction function for cache-coherent native
  entity retraction and EACL relationship peer cleanup."
  (:require [clojure.string :as str]
            [datomic.api :as d]
            [eacl.relationships.safe-retraction :as safe]))

(def function-digest
  "a9808277940344437dcc5e57f956bbada019838522ab874ef2f64bcce6410479")

(def function-doc
  (str safe/function-doc-prefix " v" safe/function-version
       "; digest=" function-digest
       "; optional Datomic database function"))

(def function-definition
  "Self-contained Datomic database function; EACL is not required on the
  transactor classpath."
  {:db/ident safe/function-ident
   :db/doc function-doc
   :db/fn
   (d/function
    {:lang "clojure"
     :params '[db target]
     :code
     '(let [invalid!
            (fn [reason data]
              (throw
               (ex-info
                "Invalid EACL safe-retraction transaction."
                (merge
                 {:type :eacl.safe-retraction/invalid
                  :eacl/error :eacl.safe-retraction/invalid
                  :reason reason}
                 data))))
            forward-attr
            :eacl.v7.relationship/subject-type+relation+resource-type+resource
            reverse-attr
            :eacl.v7.relationship/resource-type+relation+subject-type+subject
            relation-key-attr
            :eacl.relation/resource-type+relation-name+subject-type
            valid-target?
            (or (and (integer? target) (not (neg? target)))
                (and (vector? target)
                     (= 2 (count target))
                     (keyword? (first target))
                     (some? (second target))))
            _ (when-not valid-target?
                (invalid! :invalid-target {:target target}))
            target-eid (datomic.api/entid db target)
            numeric-target? (integer? target)]
        (cond
          (and (not numeric-target?) (nil? target-eid))
          []

          (nil? target-eid)
          []

          :else
          (let [live? (boolean
                       (seq (datomic.api/datoms db :eavt target-eid)))
                component-attrs
                (when live?
                  (set
                   (datomic.api/q
                    '[:find [?attribute ...]
                      :where [?attribute :db/isComponent true]]
                    db)))
                closure
                (if-not live?
                  [target-eid]
                  (loop [pending [target-eid]
                         index 0
                         seen #{}
                         result []]
                    (if (= index (count pending))
                      result
                      (let [eid (nth pending index)]
                        (if (contains? seen eid)
                          (recur pending (inc index) seen result)
                          (let [children
                                (into []
                                      (comp
                                       (filter
                                        #(contains? component-attrs (:a %)))
                                       (map :v))
                                      (datomic.api/datoms db :eavt eid))]
                            (recur (into pending children)
                                   (inc index)
                                   (conj seen eid)
                                   (conj result eid))))))))
                protected?
                (fn [eid]
                  (let [entity (datomic.api/entity db eid)
                        ident (:db/ident entity)]
                    (or (= "schema-string" (:eacl/id entity))
                        (some? (:eacl/schema-string entity))
                        (some? (:eacl.relation/relation-name entity))
                        (some? (:eacl.permission/permission-name entity))
                        (and (keyword? ident)
                             (contains? #{"eacl" "eacl.fn"}
                                        (namespace ident))))))
                protected-eid (some #(when (protected? %) %) closure)]
            (when protected-eid
              (invalid! :protected-control-entity
                        {:target-eid target-eid
                         :protected-eid protected-eid}))
            (let [valid-value?
                  (fn [value]
                    (and (vector? value)
                         (= 4 (count value))
                         (keyword? (nth value 0))
                         (integer? (nth value 1))
                         (not (neg? (nth value 1)))
                         (keyword? (nth value 2))
                         (integer? (nth value 3))
                         (not (neg? (nth value 3)))))
                  local-halves
                  (when live?
                    (mapcat
                     (fn [eid]
                       (let [forward-values
                             (mapv :v
                                   (datomic.api/datoms
                                    db :eavt eid forward-attr))
                             reverse-values
                             (mapv :v
                                   (datomic.api/datoms
                                    db :eavt eid reverse-attr))]
                         (when-not
                          (every? valid-value?
                                  (concat forward-values reverse-values))
                           (invalid! :malformed-endpoint-half
                                     {:target-eid eid}))
                         (concat
                          (for [[subject-type relation-eid resource-type
                                 resource-eid] forward-values]
                            {:relation-eid relation-eid
                             :op
                             (when (not= eid resource-eid)
                               [:db/retract
                                resource-eid reverse-attr
                                [resource-type relation-eid
                                 subject-type eid]])})
                          (for [[resource-type relation-eid subject-type
                                 subject-eid] reverse-values]
                            {:relation-eid relation-eid
                             :op
                             (when (not= eid subject-eid)
                               [:db/retract
                                subject-eid forward-attr
                                [subject-type relation-eid
                                 resource-type eid]])}))))
                     closure))
                  repair-halves
                  (when-not live?
                    (mapcat
                     (fn [relation-datom]
                       (let [relation-eid (:e relation-datom)
                             [resource-type _relation-name subject-type]
                             (:v relation-datom)
                             reverse-value
                             [resource-type relation-eid
                              subject-type target-eid]
                             forward-value
                             [subject-type relation-eid
                              resource-type target-eid]]
                         (concat
                          (for [peer
                                (datomic.api/datoms
                                 db :avet reverse-attr reverse-value)]
                            {:relation-eid relation-eid
                             :op [:db/retract (:e peer)
                                  reverse-attr reverse-value]})
                          (for [peer
                                (datomic.api/datoms
                                 db :avet forward-attr forward-value)]
                            {:relation-eid relation-eid
                             :op [:db/retract (:e peer)
                                  forward-attr forward-value]}))))
                     (datomic.api/datoms db :aevt relation-key-attr)))
                  halves (remove #(nil? (:op %))
                                 (or local-halves repair-halves))
                  peer-retractions (distinct (map :op halves))
                  relation-eids (distinct (map :relation-eid halves))]
              (vec
               (concat
                peer-retractions
                (map (fn [relation-eid]
                       [:db/add relation-eid
                        :eacl/relation-version "datomic.tx"])
                     relation-eids)
                (when live?
                  [[:db.fn/retractEntity target-eid]])))))))})})

(def support
  (safe/support-descriptor
   {:backend :datomic
    :mode :named
    :reason :database-function
    :ident safe/function-ident
    :requires-installation? true}))

(defn support-descriptor
  []
  support)

(defn installation-state
  "Returns :absent, :current, :upgradeable, or :conflict for `db`."
  [db]
  (if-let [eid (d/entid db safe/function-ident)]
    (let [entity (d/entity db eid)
          doc (:db/doc entity)
          installed-fn (:db/fn entity)]
      (cond
        (and (= function-doc doc) installed-fn) :current
        (and (string? doc)
             (str/starts-with? doc safe/function-doc-prefix)) :upgradeable
        :else :conflict))
    :absent))

(defn install!
  "Idempotently installs or upgrades `:eacl.fn/retractEntity` on `conn`."
  [conn]
  (let [state (installation-state (d/db conn))]
    (case state
      :current
      {:installed? false :state :current :support support}

      (:absent :upgradeable)
      (do
        @(d/transact conn [function-definition])
        {:installed? true :state state :support support})

      :conflict
      (throw
       (ex-info
        "The Datomic ident :eacl.fn/retractEntity is already owned by another function."
        {:type :eacl.safe-retraction/install-conflict
         :eacl/error :eacl.safe-retraction/install-conflict
         :backend :datomic
         :ident safe/function-ident})))))

(defn retract-entity-tx-data
  "Builds one target-only invocation of the installed function."
  [target]
  (safe/target-invocation target))
