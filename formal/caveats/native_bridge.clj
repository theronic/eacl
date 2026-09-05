(ns eacl.formal.caveats.native-bridge
  "Generated native transactions versus the independently pre-green lifecycle.
   Reads physical tuples directly; no production pair decoder supplies expected state."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [datomic.api :as dt]
            [datahike.api :as dh]
            [eacl.datascript.schema :as ds-schema]
            [eacl.datascript.qualifiers :as ds-qualifiers]
            [eacl.datomic.schema :as dt-schema]
            [eacl.datomic.qualifiers :as dt-qualifiers]
            [eacl.datahike.schema :as dh-schema]
            [eacl.datahike.qualifiers :as dh-qualifiers]
            [eacl.datahike.db :as dh-db]
            [eacl.formal.caveats.model :as model]
            [eacl.relationships.qualifier-integrity :as integrity]
            [eacl.relationships.staged :as staged]))

(def schema-source "definition user {}\ndefinition doc {\n  relation viewer: user\n}\n")
(def app-schema [{:db/ident :app/seen :db/valueType :db.type/long :db/cardinality :db.cardinality/many}])
(def forward-attribute :eacl.v9.relationship/subject-type+relation+resource-type+resource+qualifier)
(def reverse-attribute :eacl.v9.relationship/resource-type+relation+subject-type+subject+qualifier)
(def marker :eacl.relationship-qualifier/format-version)
(def until :eacl.relationship-qualifier/valid-until-ms)

(defn observe [native]
  ((:with-snapshot native)
   (fn [db]
     (let [rows #((:all-rows native) db %)
           halves (fn [attribute forward?]
                    (into {} (map (fn [{:keys [e v]}]
                                    (is (= 5 (count v)))
                                    (let [[a r b other qid] v]
                                      [(if forward? [a e r b other] [b other r a e]) qid])) (rows attribute))))
           qids (set (map :e (rows marker)))]
       {:forward (halves forward-attribute true) :reverse (halves reverse-attribute false)
        :qualifiers (into {} (map (fn [qid]
                                    (let [entity ((:entity native) db qid)]
                                      (is (= #{:db/id marker until} (set (keys entity))))
                                      [qid {:valid-until-ms (get entity until)}]))) qids)
        :facts (set (map :v (rows :app/seen)))
        :assertions (into {} (map #(vector % ((:facts native) db %))) qids)}))))

(defn advance [state command]
  (let [result (model/transition state command)]
    (is (:accepted result) (pr-str command))
    (:state result)))

(defn assert-refines! [state observed prior]
  (is (model/healthy? state))
  (is (= (:forward state) (:forward observed)))
  (is (= (:reverse state) (:reverse observed)))
  (is (= (update-vals (:qualifiers state) :value) (:qualifiers observed)))
  (is (= (:facts state) (:facts observed)))
  (doseq [qid (set/intersection (set (keys (:assertions prior))) (set (keys (:assertions observed))))]
    (is (= (get-in prior [:assertions qid]) (get-in observed [:assertions qid]))
        "retained qualifier facts and native assertion versions never mutate")))

(defn run-peer-repair! [writer state]
  (let [native (:native writer) [identity qid] (first (sort-by key (filter val (:forward state))))
        [st subject relation rt resource] identity]
    (is (some? qid))
    (reduce
      (fn [state direction]
        (let [prior (observe native)
              datom (if (= direction :forward)
                      [:db/retract subject forward-attribute [st relation rt resource qid]]
                      [:db/retract resource reverse-attribute [rt relation st subject qid]])
              damage ((:with-snapshot native) #(conj (vec ((:fence native) % relation false)) datom))]
          ((:transact! native) damage)
          (let [expected (model/repair-peer (update state direction dissoc identity) identity)]
            (is (:accepted expected))
            (is (= qid (:qualifier (integrity/repair-pair! writer identity))))
            (assert-refines! (:state expected) (observe native) prior)
            (:state expected))))
      state [:forward :reverse])))

(defn run-lifecycle!
  "One reproducible 96-step campaign. Also used by the separately pinned
   Datalevin conformance run, without putting its unpublished fork in core CI."
  [writer entid seed]
  (let [native (:native writer) random (java.util.Random. seed)
        _ ((:transact! native) (mapv #(hash-map :eacl/id (str "generated/" %)) (range 5)))
        db ((:snapshot native))
        subject (entid db [:eacl/id "generated/0"])
        relation (entid db [:eacl.relation/resource-type+relation-name+subject-type [:doc :viewer :user]])
        identities (mapv #(vector :user subject relation :doc (entid db [:eacl/id (str "generated/" %)])) (range 1 5))
        relation-version #((:with-snapshot native)
                           (fn [db] (get ((:entity native) db relation) (:relation-version-attribute native))))]
    (loop [step 0 state model/empty-state handles {} prior (observe native)]
      (if (= 96 step)
        (do (is (pos? (count (:allocated state)))) (is (seq (:facts state)))
            (run-peer-repair! writer state))
        (let [choice (.nextInt random 8) identity (nth identities (.nextInt random 4))
              orphan (first (sort-by key handles))
              operation (cond (= choice 0) :prepare
                              (= choice 1) (if orphan :cleanup :prepare)
                              (= choice 2) (if orphan :attach :prepare)
                              (and (= choice 3) (contains? (:forward state) identity)) :delete
                              :else :write)
              semantic {:valid-until-ms (+ 1000 step)}
              stamp (relation-version)
              [next-state next-handles]
              (case operation
                :prepare
                (let [handle (staged/prepare! writer identity semantic)
                      qid (:qualifier-eid (staged/plan-current writer (if (contains? (:forward state) identity) :replace :create) identity handle))]
                  [(advance state [:prepare nil qid semantic #{}])
                   (assoc handles qid {:handle handle :identity identity})])
                :cleanup
                (let [[qid {:keys [handle]}] orphan]
                  (staged/cleanup! writer handle)
                  [(advance state [:cleanup nil qid]) (dissoc handles qid)])
                :attach
                (let [[qid {:keys [handle identity]}] orphan
                      op (if (contains? (:forward state) identity) :replace :create)]
                  (staged/write! writer op identity handle [[:db/add subject :app/seen step]])
                  [(advance state [(if (= :create op) :publish :replace) identity qid nil #{step}])
                   (dissoc handles qid)])
                :delete
                (do (staged/write! writer :delete identity nil)
                    [(advance state [:delete identity]) handles])
                :write
                (let [op (if (contains? (:forward state) identity) :replace :create)
                      value (when (odd? choice) semantic)
                      _ (staged/write! writer op identity value [[:db/add subject :app/seen step]])
                      actual (observe native) qid (get-in actual [:forward identity])
                      state (if qid (advance state [:prepare nil qid value #{}]) state)]
                  [(advance state [(if (= :create op) :publish :replace) identity qid nil #{step}]) handles]))
              actual (observe native)]
          (testing (str "seed=" seed " step=" step " operation=" operation)
            (assert-refines! next-state actual prior)
            (when (#{:write :attach :delete} operation)
              (is (not= stamp (relation-version)) "pair publication advances its owning Relation stamp")))
          (recur (inc step) next-state next-handles actual))))))

(deftest generated-native-lifecycle
  (let [conn (ds-schema/create-conn {:app/seen {:db/cardinality :db.cardinality/many}})]
    (ds-schema/write-schema! conn schema-source)
    (run-lifecycle! (ds-qualifiers/writer conn) ds/entid 901))
  (let [uri (str "datomic:mem://qualifier-model-" (random-uuid))
        _ (dt/create-database uri) conn (dt/connect uri)]
    (try
      (dt-schema/install! conn) @(dt/transact conn app-schema)
      (dt-schema/write-schema! conn schema-source)
      (run-lifecycle! (dt-qualifiers/writer conn) dt/entid 902)
      (finally (dt/release conn) (dt/delete-database uri))))
  (doseq [options [{} {:attribute-refs? true}]]
    (let [conn (dh-schema/create-conn app-schema options) config (:config (dh/db conn))]
      (try
        (dh-schema/write-schema! conn schema-source)
        (run-lifecycle! (dh-qualifiers/writer conn) dh-db/entid 903)
        (finally (dh/release conn) (dh/delete-database config))))))
