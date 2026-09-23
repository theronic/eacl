(ns eacl.operator.delegated-recursion-test
  "Routing of recursive operator plans whose recursion lies inside union-only
  operands: the operands are decided by the union engine, the delegated
  operand's own sealed plan generates candidates, and cursors minted under any
  other generator are rejected. Plans that recurse through an operator keep
  the tabled recursive evaluator."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.authorization.qualification-test :as qualification-fixtures]
            [eacl.core :as eacl]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.relationships.staged :as staged]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-route :as stable-route]
            [eacl.engine.v8 :as engine]
            [eacl.operator.cover-plan :as cover-plan]
            [eacl.operator.plan :as plan]
            [eacl.operator.recursive :as recursive]))

(def schema
  "definition user {}
   definition folder {
     relation parent: folder
     relation reader: user
     relation deleter: user
     relation eligible: user
     permission readable = reader + parent->readable
     permission granted = deleter + parent->granted
     permission removable = granted & readable
     permission inherited = reader + (parent->inherited & eligible)
   }")

(defn- fixture []
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})
        ids ["alice" "f0" "f1" "f2" "f3"]
        object (fn [type id] (eacl/spice-object type id))]
    (eacl/write-schema! client schema)
    (ds/transact! conn (mapv #(hash-map :eacl/id %) ids))
    (eacl/create-relationships!
     client
     [(eacl/->Relationship (object :folder "f0") :parent (object :folder "f1"))
      (eacl/->Relationship (object :folder "f1") :parent (object :folder "f2"))
      (eacl/->Relationship (object :folder "f2") :parent (object :folder "f3"))
      (eacl/->Relationship (object :user "alice") :deleter (object :folder "f0"))
      (eacl/->Relationship (object :user "alice") :reader (object :folder "f1"))
      (eacl/->Relationship (object :user "alice") :eligible (object :folder "f2"))])
    (let [db (ds/db conn)
          adapter (datascript-backend/basis-adapter
                   db
                   {:object-id->entid (fn [snapshot object-id]
                                        (ds/entid snapshot [:eacl/id object-id]))
                    :entid->object-id (fn [snapshot internal-id]
                                        (:eacl/id (ds/entity snapshot internal-id)))})]
      {:client client :adapter adapter
       :eids (fn [& ids] (mapv #(ds/entid db [:eacl/id %]) ids))})))

(defn- error-data [f]
  (try (f) nil
       (catch #?(:clj Exception :cljs :default) error (ex-data error))))

(deftest delegated-plans-generate-from-the-operand-plan-test
  (let [{:keys [adapter eids]} (fixture)
        query {:subject (eacl/spice-object :user "alice") :permission :removable
               :resource/type :folder}
        operator-plan (plan/seal-plan adapter [:folder :removable])
        operand-plan (sealed-plan/seal-plan adapter [:folder :granted])
        recursive-stats (atom {})
        membership-stats (atom {})
        first-page (binding [recursive/*recursive-stats* recursive-stats
                             stable-route/*membership-stats* membership-stats]
                     (engine/lookup-resources adapter (assoc query :first 1)))
        edge (get-in first-page [:page-info :end-cursor])]
    (is (= #{[:folder :granted] [:folder :readable]}
           (plan/delegated-permissions operator-plan)))
    (is (= (eids "f1") (mapv :id (:data first-page))))
    (testing "the operand's own union plan is the generator"
      (is (= :operator-recursive-edge (:kind edge)))
      (is (= (:fingerprint operand-plan) (:cover-fingerprint edge))))
    (testing "the union engine decides the operands"
      (is (empty? @recursive-stats))
      (is (pos? (:decided @membership-stats))))
    (testing "the walk continues from its own cursor"
      (let [rest-page (engine/lookup-resources adapter (assoc query :first 10 :after edge))]
        (is (= (eids "f2" "f3") (mapv :id (:data rest-page))))))
    (testing "a cursor minted under the synthetic operator cover is rejected"
      (let [synthetic (cover-plan/seal-plan adapter operator-plan)]
        (is (not= (:fingerprint synthetic) (:fingerprint operand-plan)))
        (is (= :eacl.pagination/invalid-cursor
               (:eacl/error
                (error-data
                 #(engine/lookup-resources
                   adapter
                   (assoc query :first 1
                          :after (assoc edge :cover-fingerprint
                                        (:fingerprint synthetic))))))))))))

(deftest delegated-checks-and-counts-use-the-union-engine-test
  (let [{:keys [client]} (fixture)
        alice (eacl/spice-object :user "alice")
        recursive-stats (atom {})]
    (binding [recursive/*recursive-stats* recursive-stats]
      (is (= [false true true true]
             (mapv #(eacl/can? client {:subject alice :permission :removable
                                       :resource (eacl/spice-object :folder %)})
                   ["f0" "f1" "f2" "f3"])))
      (is (= 3 (:count (eacl/count-resources
                        client {:subject alice :permission :removable
                                :resource/type :folder})))))
    (is (empty? @recursive-stats))))

(deftest recursion-through-an-operator-keeps-the-tabled-evaluator-test
  (let [{:keys [adapter client]} (fixture)
        alice (eacl/spice-object :user "alice")
        recursive-stats (atom {})]
    (is (nil? (plan/delegated-permissions (plan/seal-plan adapter [:folder :inherited]))))
    (binding [recursive/*recursive-stats* recursive-stats]
      (is (= ["f1" "f2"]
             (mapv :id (:data (eacl/lookup-resources
                               client {:subject alice :permission :inherited
                                       :resource/type :folder :first 10}))))))
    (is (pos? (:questions @recursive-stats 0)))))

(defn- meet
  "Intersection of two operands' permissionship."
  [left right]
  (cond
    (some #{:no-permission} [left right]) :no-permission
    (some #{:conditional-permission} [left right]) :conditional-permission
    :else :has-permission))

(deftest delegated-operands-carry-conditional-and-temporal-evidence-test
  (let [conn (datascript/create-conn)
        now (atom 99)
        client (datascript/make-client
                conn {:clock #(deref now)
                      :caveat-evaluator (qualification-fixtures/portable-evaluator (atom 0))})
        alice (eacl/spice-object :user "alice")
        folders (mapv #(eacl/spice-object :folder %) ["f0" "f1" "f2" "f3"])]
    (eacl/write-schema! client (str "caveat enabled(flag bool) { flag }\n" schema))
    (ds/transact! conn (mapv #(hash-map :eacl/id %) ["alice" "f0" "f1" "f2" "f3"]))
    (eacl/create-relationships!
     client
     [(eacl/->Relationship (folders 0) :parent (folders 1))
      (eacl/->Relationship (folders 1) :parent (folders 2))
      (eacl/->Relationship (folders 2) :parent (folders 3))
      (eacl/->Relationship alice :deleter (folders 0))])
    (let [db (ds/db conn)
          eid #(ds/entid db [:eacl/id %])
          reader (ds/entid db [:eacl.relation/resource-type+relation-name+subject-type
                               [:folder :reader :user]])
          caveat (ds/entid db [:eacl.caveat/name "enabled"])
          writer (qualifiers/writer conn)]
      (ds/transact! conn [{:db/id reader :eacl.relation/caveats [caveat]
                          :eacl.relation/allows-unqualified? true}])
      ;; readable below f1 is conditional on the caveat and expires at 200;
      ;; granted below f0 is plain.
      (staged/write! writer :create [:user (eid "alice") reader :folder (eid "f1")]
                     {:caveat caveat :valid-until-ms 200}))
    (doseq [time [99 200]
            context [{} {"flag" true} {"flag" false}]]
      (reset! now time)
      (testing (str "time " time ", context " context)
        (let [permissionship
              (fn [permission folder]
                (:permissionship
                 (eacl/check-permission client {:subject alice :permission permission
                                                :resource folder :caveat-context context})))
              expected (into {}
                             (map (fn [folder]
                                    [folder (meet (permissionship :granted folder)
                                                  (permissionship :readable folder))]))
                             folders)
              lookup (fn [policy]
                       ;; :detailed returns decision maps; :definite the objects.
                       (set (map #(if (contains? % :object) (:object %) %)
                                 (:data (eacl/lookup-resources
                                         client {:subject alice :permission :removable
                                                 :resource/type :folder :first 10
                                                 :caveat-context context
                                                 :result-policy policy})))))]
          (when (and (= time 99) (empty? context))
            (is (some #{:conditional-permission} (vals expected))
                "the fixture exercises conditional evidence"))
          (doseq [folder folders]
            (is (= (expected folder) (permissionship :removable folder)) (:id folder)))
          (is (= (set (keep (fn [[folder p]] (when (not= :no-permission p) folder)) expected))
                 (lookup :detailed)))
          (is (= (set (keep (fn [[folder p]] (when (= :has-permission p) folder)) expected))
                 (lookup :definite))))))))
