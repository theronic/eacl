(ns eacl.operator.delegated-recursion-test
  "Routing of recursive operator plans whose recursion lies inside union-only
  operands, or passes through linearly guarded operators: operands are
  decided by the union engine and guarded members by the guarded search, and
  candidates come from the delegated operand's own sealed plan or else from
  the flattened generator. Cursors minted under any other generator are
  rejected. Plans that recurse through a non-linear operator keep the tabled
  recursive evaluator."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.authorization.evidence :as evidence]
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
            [eacl.operator.evaluator-test :as evaluator-fixtures]
            [eacl.operator.plan :as plan]
            [eacl.operator.recursive :as recursive]
            [eacl.request.counters :as counters]))

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
     permission removable_top = deleter + (granted & readable)
     permission prunable_top = deleter + (granted - readable)
     permission either = (granted + readable) & (reader + deleter + eligible)
     permission gated = eligible & readable
     permission parent_removable = deleter + parent->removable
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

;; f0 is the root of f0 -> f1 -> f2 -> f3. Alice deletes from f0 down, reads
;; from f1 down, and is eligible at f2.
(def ^:private flattened-roots
  "Roots whose anchor chains end at no single delegated operand, with their
  flattened generator rows as [source target-type target] and their results."
  {:removable_top {:rows #{[:self :relation :deleter] [:self :permission :granted]}
                   :results #{"f0" "f1" "f2" "f3"}}
   :prunable_top {:rows #{[:self :relation :deleter] [:self :permission :granted]}
                  :results #{"f0"}}
   :either {:rows #{[:self :permission :granted] [:self :permission :readable]}
            :results #{"f0" "f1" "f2"}}
   :gated {:rows #{[:self :relation :eligible]}
           :results #{"f2"}}
   :parent_removable {:rows #{[:self :relation :deleter]
                              [:parent :permission
                               [:eacl.operator.generator/node [:folder :removable]]]}
                      :results #{"f0" "f2" "f3"}}})

(defn- generator-rows [operator-plan permission]
  (set (map (juxt :source-relation-name :target-type :target-name)
            (cover-plan/generator-definitions
             operator-plan (plan/delegated-permissions operator-plan) permission))))

(deftest flattened-generators-follow-covers-test
  (let [{:keys [adapter client]} (fixture)
        alice (eacl/spice-object :user "alice")
        walk (fn [permission]
               (set (map :id (:data (eacl/lookup-resources
                                     client {:subject alice :permission permission
                                             :resource/type :folder :first 10})))))]
    (doseq [[permission {:keys [rows results]}] flattened-roots]
      (testing (name permission)
        (let [operator-plan (plan/seal-plan adapter [:folder permission])
              delegated (plan/delegated-permissions operator-plan)]
          (is (some? delegated))
          (is (nil? (plan/delegated-generator operator-plan (:root operator-plan) delegated)))
          (is (= rows (generator-rows operator-plan [:folder permission])))
          (is (= results (walk permission)))
          (is (= results (with-redefs [plan/delegated-permissions (constantly nil)]
                           (walk permission)))
              "the tabled route agrees"))))
    (testing "an operator permission an arrow reaches gets its own node"
      (is (= #{[:self :permission :granted]}
             (generator-rows (plan/seal-plan adapter [:folder :parent_removable])
                             [:folder :removable]))))))

(deftest flattened-generator-cursors-test
  (let [{:keys [adapter]} (fixture)
        query {:subject (eacl/spice-object :user "alice") :permission :removable_top
               :resource/type :folder}
        operator-plan (plan/seal-plan adapter [:folder :removable_top])
        generator (cover-plan/seal-generator
                   adapter operator-plan (plan/delegated-permissions operator-plan))
        first-page (engine/lookup-resources adapter (assoc query :first 1))
        edge (get-in first-page [:page-info :end-cursor])
        rest-page (engine/lookup-resources adapter (assoc query :first 10 :after edge))]
    (testing "the flattened generator's fingerprint is the cursors' cover"
      (is (= :operator-recursive-edge (:kind edge)))
      (is (= (:fingerprint generator) (:cover-fingerprint edge))))
    (testing "the walk continues from its own cursor"
      (is (= 4 (count (into (set (map :id (:data first-page)))
                            (map :id (:data rest-page)))))))
    (testing "a cursor minted under the per-node cover is rejected"
      (let [per-node (cover-plan/seal-plan adapter operator-plan)]
        (is (not= (:fingerprint per-node) (:fingerprint generator)))
        (is (= :eacl.pagination/invalid-cursor
               (:eacl/error
                (error-data
                 #(engine/lookup-resources
                   adapter
                   (assoc query :first 1
                          :after (assoc edge :cover-fingerprint
                                        (:fingerprint per-node))))))))))
    (testing "a generator that would read outside the root's closure is rejected"
      (is (= :eacl.operator/invalid-cover
             (:type (error-data
                     #(cover-plan/seal-generator
                       adapter
                       (assoc-in operator-plan
                                 [:relation-closures [:folder :removable_top] :all] [])
                       (plan/delegated-permissions operator-plan)))))))))

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

(deftest guarded-recursion-uses-the-guarded-search-test
  (let [{:keys [adapter client]} (fixture)
        alice (eacl/spice-object :user "alice")
        recursive-stats (atom {})
        membership-stats (atom {})
        folders (mapv #(eacl/spice-object :folder %) ["f0" "f1" "f2" "f3"])]
    (is (nil? (plan/delegated-permissions (plan/seal-plan adapter [:folder :inherited]))))
    (is (= #{[:folder :inherited]}
           (:members (plan/guarded-delegation (plan/seal-plan adapter [:folder :inherited])))))
    (binding [recursive/*recursive-stats* recursive-stats
              stable-route/*membership-stats* membership-stats]
      (is (= #{"f1" "f2"}
             (set (map :id (:data (eacl/lookup-resources
                                   client {:subject alice :permission :inherited
                                           :resource/type :folder :first 10}))))))
      (is (= [false true true false]
             (mapv #(eacl/can? client {:subject alice :permission :inherited :resource %})
                   folders)))
      (is (= 2 (:count (eacl/count-resources
                        client {:subject alice :permission :inherited
                                :resource/type :folder})))))
    (testing "the guarded search decides it; the tabled evaluator is never entered"
      (is (empty? @recursive-stats))
      (is (pos? (:decided @membership-stats))))))

(deftest non-linear-recursion-keeps-the-tabled-evaluator-test
  ;; `shared`'s intersection has two children in its component, so it is not
  ;; linearly guarded.
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})
        alice (eacl/spice-object :user "alice")
        folders (mapv #(eacl/spice-object :folder %) ["f0" "f1" "f2"])
        recursive-stats (atom {})]
    (eacl/write-schema!
     client
     "definition user {}
      definition folder {
        relation parent: folder
        relation reader: user
        relation eligible: user
        permission shared = reader + (parent->shared & gate)
        permission gate = eligible + parent->shared
      }")
    (ds/transact! conn (mapv #(hash-map :eacl/id %) ["alice" "f0" "f1" "f2"]))
    (eacl/create-relationships!
     client
     [(eacl/->Relationship (folders 0) :parent (folders 1))
      (eacl/->Relationship (folders 1) :parent (folders 2))
      (eacl/->Relationship alice :reader (folders 0))])
    (binding [recursive/*recursive-stats* recursive-stats]
      (is (= #{"f0" "f1" "f2"}
             (set (map :id (:data (eacl/lookup-resources
                                   client {:subject alice :permission :shared
                                           :resource/type :folder :first 10})))))))
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

(deftest relation-leaves-decide-from-the-subjects-holdings-test
  ;; `removable_top = deleter + (granted & readable)`: its `deleter` leaf is
  ;; decided for a whole batch from alice's deleter holdings. Her grant on f0
  ;; expires at 200 and her grant on f2 is caveated and expires at 300, so
  ;; the leaf's decisions carry certificates and a residual with a deadline.
  ;; The detailed lookup must still carry exactly what each check returns,
  ;; with or without the holdings path.
  (let [conn (datascript/create-conn)
        client (datascript/make-client
                conn {:clock (constantly 100)
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
      (eacl/->Relationship alice :reader (folders 3))])
    (let [db (ds/db conn)
          eid #(ds/entid db [:eacl/id %])
          deleter (ds/entid db [:eacl.relation/resource-type+relation-name+subject-type
                                [:folder :deleter :user]])
          caveat (ds/entid db [:eacl.caveat/name "enabled"])
          writer (qualifiers/writer conn)]
      (ds/transact! conn [{:db/id deleter :eacl.relation/caveats [caveat]
                          :eacl.relation/allows-unqualified? true}])
      (staged/write! writer :create [:user (eid "alice") deleter :folder (eid "f0")]
                     {:valid-until-ms 200})
      (staged/write! writer :create [:user (eid "alice") deleter :folder (eid "f2")]
                     {:caveat caveat :valid-until-ms 300}))
    (doseq [context [{} {"flag" true} {"flag" false}]
            limit [stable-route/holdings-limit 1]]
      (testing (str "context " context ", holdings limit " limit)
        (with-redefs [stable-route/holdings-limit limit]
          (let [checks (into {}
                             (map (fn [folder]
                                    [folder (select-keys
                                             (eacl/check-permission
                                              client {:subject alice :permission :removable_top
                                                      :resource folder :caveat-context context})
                                             [:permissionship :missing-fields :residual])]))
                             folders)
                ledger (counters/make-ledger)
                items (counters/call-with-ledger
                       ledger
                       #(:data (eacl/lookup-resources
                                client {:subject alice :permission :removable_top
                                        :resource/type :folder :first 10
                                        :caveat-context context :result-policy :detailed
                                        :cache? false})))
                probes (:probes (counters/snapshot ledger))]
            (is (= (into {} (remove #(= :no-permission (:permissionship (val %)))) checks)
                   (into {} (map (fn [item]
                                   [(:object item)
                                    (select-keys item [:permissionship :missing-fields
                                                       :residual])]))
                         items)))
            (if (= 1 limit)
              (is (pos? probes) "incomplete holdings fall back to probes")
              (is (zero? probes) "the leaf reads alice's holdings once"))))))))

(def ^:private guarded-schema
  "caveat enabled(flag bool) { flag }
   definition user {}
   definition folder {
     relation parent: folder
     relation reader: user
     relation eligible: user
     relation blocked: user
     permission inherited = reader + (parent->inherited & eligible)
     permission pruned = reader + (parent->pruned - blocked)
   }")

(deftest guarded-members-carry-conditional-and-temporal-evidence-test
  ;; f0 is the root of f0 -> f1 -> f2 -> f3, and alice reads f0. Her
  ;; `eligible` guard is plain on f1, expires at 200 on f2 and is caveated
  ;; on f3. Her `blocked` guard, which `pruned` subtracts, expires at 300 on
  ;; f1 and is caveated on f2; either makes the guarded search defer to the
  ;; exact evaluation. Every check and detailed lookup item must equal the
  ;; tabled evaluator's.
  (let [conn (datascript/create-conn)
        now (atom 100)
        client (datascript/make-client
                conn {:clock #(deref now)
                      :caveat-evaluator (qualification-fixtures/portable-evaluator (atom 0))})
        alice (eacl/spice-object :user "alice")
        folders (mapv #(eacl/spice-object :folder %) ["f0" "f1" "f2" "f3"])]
    (eacl/write-schema! client guarded-schema)
    (ds/transact! conn (mapv #(hash-map :eacl/id %) ["alice" "f0" "f1" "f2" "f3"]))
    (eacl/create-relationships!
     client
     [(eacl/->Relationship (folders 0) :parent (folders 1))
      (eacl/->Relationship (folders 1) :parent (folders 2))
      (eacl/->Relationship (folders 2) :parent (folders 3))
      (eacl/->Relationship alice :reader (folders 0))
      (eacl/->Relationship alice :eligible (folders 1))])
    (let [db (ds/db conn)
          eid #(ds/entid db [:eacl/id %])
          relation (fn [name]
                     (ds/entid db [:eacl.relation/resource-type+relation-name+subject-type
                                   [:folder name :user]]))
          caveat (ds/entid db [:eacl.caveat/name "enabled"])
          writer (qualifiers/writer conn)]
      (ds/transact! conn (vec (for [name [:eligible :blocked]]
                                {:db/id (relation name) :eacl.relation/caveats [caveat]
                                 :eacl.relation/allows-unqualified? true})))
      (staged/write! writer :create [:user (eid "alice") (relation :eligible) :folder (eid "f2")]
                     {:valid-until-ms 200})
      (staged/write! writer :create [:user (eid "alice") (relation :eligible) :folder (eid "f3")]
                     {:caveat caveat})
      (staged/write! writer :create [:user (eid "alice") (relation :blocked) :folder (eid "f1")]
                     {:valid-until-ms 300})
      (staged/write! writer :create [:user (eid "alice") (relation :blocked) :folder (eid "f2")]
                     {:caveat caveat}))
    (testing "the guarded search certifies the widest witness through its guards"
      (let [db (ds/db conn)
            eid #(ds/entid db [:eacl/id %])
            adapter (datascript-backend/basis-adapter db {})
            operator-plan (plan/seal-plan adapter [:folder :inherited])]
        (is (= [true 200]
               (mapv #(if (true? %) true (evidence/valid-until %))
                     (stable-route/check-many-eids
                      {:adapter adapter
                       :plan (plan/guarded-program operator-plan [:folder :inherited])
                       :subject-type :user :subject-eid (eid "alice")
                       :resource-eids [(eid "f1") (eid "f2")]
                       :qualification (evaluator-fixtures/qualified-request db 100 {})
                       :context (stable-route/membership-context)}))))))
    (testing "the fixture exercises conditional results and deferred resources"
      (is (= :conditional-permission
             (:permissionship (eacl/check-permission
                               client {:subject alice :permission :inherited
                                       :resource (folders 3) :caveat-context {}
                                       :cache? false}))))
      (let [stats (atom {})]
        (binding [stable-route/*membership-stats* stats]
          (eacl/lookup-resources client {:subject alice :permission :pruned
                                         :resource/type :folder :first 10
                                         :caveat-context {} :cache? false}))
        (is (pos? (:fallbacks @stats 0))
            "a subtracted guard that expires or is caveated defers")))
    (doseq [time [100 250 350]
            context [{} {"flag" true} {"flag" false}]
            permission [:inherited :pruned]]
      (reset! now time)
      (testing (str permission " at " time ", context " context)
        (let [answers
              (fn []
                {:checks (into {}
                               (map (fn [folder]
                                      [folder (select-keys
                                               (eacl/check-permission
                                                client {:subject alice :permission permission
                                                        :resource folder :caveat-context context
                                                        :cache? false})
                                               [:permissionship :missing-fields :residual])]))
                               folders)
                 :items (into {}
                              (map (fn [item]
                                     [(:object item)
                                      (select-keys item [:permissionship :missing-fields
                                                         :residual])]))
                              (:data (eacl/lookup-resources
                                      client {:subject alice :permission permission
                                              :resource/type :folder :first 10
                                              :caveat-context context :result-policy :detailed
                                              :cache? false})))})
              guarded (answers)
              tabled (with-redefs [plan/guarded-delegation (constantly nil)] (answers))]
          (is (= tabled guarded))
          (is (= (into {} (remove #(= :no-permission (:permissionship (val %))))
                       (:checks guarded))
                 (:items guarded))))))))


(deftest conditional-results-carry-the-point-checks-residual-test
  ;; `granted` holds on f0 through a direct deleter grant until 200 and
  ;; through its parent's until 500: the point check certifies its first
  ;; witness (200), the batched search the widest (500). `readable` is
  ;; caveated, so `removable` is conditional, and its residual carries the
  ;; certificate; the detailed lookup must carry the check's.
  (let [conn (datascript/create-conn)
        client (datascript/make-client
                conn {:clock (constantly 100)
                      :caveat-evaluator (qualification-fixtures/portable-evaluator (atom 0))})
        alice (eacl/spice-object :user "alice")
        f0 (eacl/spice-object :folder "f0")]
    (eacl/write-schema! client (str "caveat enabled(flag bool) { flag }\n" schema))
    (ds/transact! conn (mapv #(hash-map :eacl/id %) ["alice" "f0" "p"]))
    (eacl/create-relationships!
     client [(eacl/->Relationship (eacl/spice-object :folder "p") :parent f0)])
    (let [db (ds/db conn)
          eid #(ds/entid db [:eacl/id %])
          relation (fn [name]
                     (ds/entid db [:eacl.relation/resource-type+relation-name+subject-type
                                   [:folder name :user]]))
          caveat (ds/entid db [:eacl.caveat/name "enabled"])
          writer (qualifiers/writer conn)]
      (ds/transact! conn [{:db/id (relation :reader) :eacl.relation/caveats [caveat]
                          :eacl.relation/allows-unqualified? true}])
      (staged/write! writer :create [:user (eid "alice") (relation :deleter) :folder (eid "f0")]
                     {:valid-until-ms 200})
      (staged/write! writer :create [:user (eid "alice") (relation :deleter) :folder (eid "p")]
                     {:valid-until-ms 500})
      (staged/write! writer :create [:user (eid "alice") (relation :reader) :folder (eid "f0")]
                     {:caveat caveat}))
    (testing "the batched and point certificates of `granted` differ"
      (let [db (ds/db conn)
            eid #(ds/entid db [:eacl/id %])
            adapter (datascript-backend/basis-adapter db {})
            options {:adapter adapter :plan (sealed-plan/seal-plan adapter [:folder :granted])
                     :subject-type :user :subject-eid (eid "alice")
                     :qualification (evaluator-fixtures/qualified-request db 100 {})}]
        (is (= 200 (evidence/valid-until
                    (stable-route/check-eids (assoc options :resource-eid (eid "f0"))))))
        (is (= [500] (mapv evidence/valid-until
                           (stable-route/check-many-eids
                            (assoc options :resource-eids [(eid "f0")]
                                   :context (stable-route/membership-context))))))))
    (let [check (eacl/check-permission client {:subject alice :permission :removable
                                               :resource f0 :caveat-context {}})
          item (first (:data (eacl/lookup-resources
                              client {:subject alice :permission :removable
                                      :resource/type :folder :first 10
                                      :caveat-context {} :result-policy :detailed})))]
      (is (= :conditional-permission (:permissionship check)))
      (is (= f0 (:object item)))
      (is (= (select-keys check [:permissionship :missing-fields :residual])
             (select-keys item [:permissionship :missing-fields :residual]))))))
