(ns eacl.operator.recursive-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [clojure.string :as str]
            [datascript.core :as ds]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.impl :as datascript-impl]
            [eacl.datascript.schema :as datascript-schema]
            [eacl.engine.v8 :as engine]
            [eacl.execution :as execution]
            [eacl.operator.plan :as plan]
            [eacl.operator.recursive :as recursive]
            [eacl.operator-engine.oracle :as oracle]))

(def recursive-schema
  "definition user {}
   definition folder {
     relation direct: user
     relation eligible: user
     relation banned: user
     relation parent: folder
     permission view = direct + (parent->view & eligible)
     permission blocked = banned
     permission allowed = view - blocked
   }")

(def recursive-relation-arrow-schema
  "definition user {}
   definition folder {
     relation direct: user
     relation eligible: user
     relation banned: user
     relation member: user
     relation parent: folder
     permission view = direct + (parent->view & parent->member & eligible)
     permission allowed = view - banned
   }")

(defn- object [type id]
  (eacl/spice-object type [:eacl/id id]))

(defn- seed-schema [schema-source relationships]
  (let [conn (datascript/create-conn)
        alice (object :user "alice")
        bob (object :user "bob")
        f0 (object :folder "f0")
        f1 (object :folder "f1")
        f2 (object :folder "f2")
        objects [alice bob f0 f1 f2]]
    (datascript-schema/write-schema! conn schema-source)
    (ds/transact!
     conn
     (map-indexed (fn [index value]
                    {:db/id (- (inc index))
                     :eacl/id (second (:id value))})
                  objects))
    (doseq [relationship relationships]
      (ds/transact!
       conn
       (datascript-impl/tx-update-relationship
        (ds/db conn) {:operation :touch :relationship relationship})))
    (let [db (ds/db conn)
          adapter (datascript-backend/basis-adapter db {})
          public-adapter
          (datascript-backend/basis-adapter
           db
           {:object-id->entid
            (fn [snapshot object-id]
              (ds/entid snapshot [:eacl/id object-id]))
            :entid->object-id
            (fn [snapshot internal-id]
              (:eacl/id (ds/entity snapshot internal-id)))})]
      {:adapter adapter
       :public-adapter public-adapter
       :client (datascript/make-client conn {})
       :plan (plan/seal-plan adapter [:folder :allowed])
       :view-plan (plan/seal-plan adapter [:folder :view])
       :alice alice :bob bob :f0 f0 :f1 f1 :f2 f2
       :eid #(ds/entid db (:id %))})))

(defn- seed [relationships]
  (seed-schema recursive-schema relationships))

(defn- chain-relationships [alice bob f0 f1 f2]
  [(eacl/->Relationship alice :direct f0)
   (eacl/->Relationship bob :direct f0)
   (eacl/->Relationship f0 :parent f1)
   (eacl/->Relationship alice :eligible f1)
   (eacl/->Relationship f1 :parent f2)
   (eacl/->Relationship alice :eligible f2)
   (eacl/->Relationship alice :banned f2)])

(defn- chain-fixture []
  (let [alice (object :user "alice")
        bob (object :user "bob")
        f0 (object :folder "f0")
        f1 (object :folder "f1")
        f2 (object :folder "f2")]
    (seed (chain-relationships alice bob f0 f1 f2))))

(defn- oracle-entity [value]
  [(:type value) (second (:id value))])

(defn- oracle-snapshot [objects relationships]
  {:objects (set (map oracle-entity objects))
   :relation-target-types {[:folder :parent] #{:folder}}
   :relationships
   (into #{}
         (map (fn [{:keys [subject relation resource]}]
                {:subject (oracle-entity subject)
                 :relation relation
                 :resource (oracle-entity resource)}))
         relationships)
   :permissions
   {[:folder :view]
    [:union
     [:relation :direct]
     [:intersection [:arrow :parent :view] [:relation :eligible]]]
    [:folder :blocked] [:relation :banned]
    [:folder :allowed]
    [:exclusion [:permission :view] [:permission :blocked]]}})

(defn- selected-relationship? [seed index]
  ;; Portable fixed-seed Boolean stream; all arithmetic remains far below the
  ;; exact-integer boundary in both runtimes.
  (zero? (mod (+ (* 97 (inc seed))
                 (* 53 (inc index))
                 (* 17 (inc seed) (inc index)))
              5)))

(defn- generated-relationships [seed users folders]
  (let [direct
        (for [user users
              folder folders
              relation [:direct :eligible :banned]]
          (eacl/->Relationship user relation folder))
        parents
        (for [child folders parent folders]
          (eacl/->Relationship child :parent parent))]
    (->> (concat direct parents)
         (map-indexed vector)
         (keep (fn [[index relationship]]
                 (when (selected-relationship? seed index)
                   relationship)))
         vec)))

(defn- candidates [{:keys [alice bob f0 f1 f2 eid]}]
  (vec
   (for [subject [alice bob]
         resource [f0 f1 f2]]
     {:direction :forward
      :subject-type :user
      :subject-eid (eid subject)
      :resource-eid (eid resource)})))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(deftest recursive-conjunction-and-stratified-exclusion-are-exact-test
  (let [{:keys [adapter plan] :as fixture} (chain-fixture)
        result (recursive/evaluate-many
                {:adapter adapter :plan plan
                 :candidates (candidates fixture)
                 :scope-identity :chain})]
    ;; Alice reaches every folder through the recursive conjunction, but the
    ;; completed lower blocked stratum excludes f2. Bob has only the seed.
    (is (= [true true false true false false] (:decisions result)))
    (is (= 2 (get-in result [:counters :strata])))
    (is (pos? (get-in result [:counters :anchor-states])))
    (is (<= (get-in result [:counters :anchor-states])
            (get-in result [:counters :facts])))
    (is (pos? (get-in result
                      [:counters :late-anchor-initialized-slots])))))

(deftest positive-cycle-and-unseeded-cycle-reach-the-least-fixed-point-test
  (let [alice (object :user "alice")
        bob (object :user "bob")
        f0 (object :folder "f0")
        f1 (object :folder "f1")
        relationships
        [(eacl/->Relationship alice :direct f0)
         (eacl/->Relationship alice :eligible f0)
         (eacl/->Relationship alice :eligible f1)
         (eacl/->Relationship bob :eligible f0)
         (eacl/->Relationship bob :eligible f1)
         (eacl/->Relationship f0 :parent f1)
         (eacl/->Relationship f1 :parent f0)]
        {:keys [adapter view-plan eid] :as fixture} (seed relationships)
        points
        (vec
         (for [subject [(:alice fixture) (:bob fixture)]
               resource [(:f0 fixture) (:f1 fixture)]]
           {:direction :forward :subject-type :user
            :subject-eid (eid subject) :resource-eid (eid resource)}))
        result (recursive/evaluate-many
                {:adapter adapter :plan view-plan
                 :candidates points :scope-identity :cycle})]
    (is (= [true true false false] (:decisions result)))
    (is (pos? (get-in result [:counters :duplicate-facts])))
    (is (every? #(= 2 (get-in % [1 :width]))
                (get-in result [:checkpoint :anchor-states])))))

(deftest recursive-relation-arrow-target-is-exact-in-both-directions-test
  (let [alice (object :user "alice")
        f0 (object :folder "f0")
        f1 (object :folder "f1")
        f2 (object :folder "f2")
        relationships
        [(eacl/->Relationship alice :direct f0)
         (eacl/->Relationship alice :member f0)
         (eacl/->Relationship alice :member f1)
         (eacl/->Relationship f0 :parent f1)
         (eacl/->Relationship f1 :parent f2)
         (eacl/->Relationship alice :eligible f1)
         (eacl/->Relationship alice :eligible f2)
         (eacl/->Relationship alice :banned f2)]
        {:keys [adapter plan eid]}
        (seed-schema recursive-relation-arrow-schema relationships)
        points
        (mapv (fn [resource]
                {:direction :forward :subject-type :user
                 :subject-eid (eid alice) :resource-eid (eid resource)})
              [f0 f1 f2])
        reverse-points (mapv #(assoc % :direction :reverse) points)]
    (is (= [true true false]
           (:decisions
            (recursive/evaluate-many
             {:adapter adapter :plan plan :candidates points
              :scope-identity :relation-arrow-forward}))))
    (is (= [true true false]
           (:decisions
            (recursive/evaluate-many
             {:adapter adapter :plan plan :candidates reverse-points
              :scope-identity :relation-arrow-reverse}))))))

(deftest recursive-intersection-crosses-portable-word-boundary-test
  (let [relation-names (mapv #(keyword (str "r" %)) (range 33))
        schema-source
        (str "definition user {}\n"
             "definition folder {\n"
             (str/join "\n"
                       (map #(str "relation " (name %) ": user")
                            relation-names))
             "\nrelation banned: user\n"
             "relation parent: folder\n"
             "permission view = ("
             (str/join " & " (map name relation-names))
             ") + parent->view\n"
             "permission allowed = view - banned\n}")
        alice (object :user "alice")
        f0 (object :folder "f0")
        f1 (object :folder "f1")
        complete-relationships
        (into [(eacl/->Relationship f0 :parent f1)]
              (map #(eacl/->Relationship alice % f0))
              relation-names)
        complete (seed-schema schema-source complete-relationships)
        incomplete (seed-schema schema-source (pop complete-relationships))
        run
        (fn [fixture scope]
          (recursive/evaluate-many
           {:adapter (:adapter fixture) :plan (:plan fixture)
            :candidates
            (mapv (fn [resource]
                    {:direction :forward :subject-type :user
                     :subject-eid ((:eid fixture) alice)
                     :resource-eid ((:eid fixture) resource)})
                  [f0 f1])
            :scope-identity scope}))
        complete-result (run complete :word-boundary-complete)
        incomplete-result (run incomplete :word-boundary-incomplete)]
    (is (= [true true] (:decisions complete-result)))
    (is (= [false false] (:decisions incomplete-result)))
    (is (some (fn [[_ {:keys [width words]}]]
                (and (= 33 width) (= 2 (count words))))
              (get-in complete-result [:checkpoint :anchor-states])))))

(deftest fixed-seed-recursive-differential-agrees-with-independent-oracle-test
  (doseq [seed-value (range 32)]
    (let [alice (object :user "alice")
          bob (object :user "bob")
          f0 (object :folder "f0")
          f1 (object :folder "f1")
          f2 (object :folder "f2")
          users [alice bob]
          folders [f0 f1 f2]
          relationships
          (generated-relationships seed-value users folders)
          evaluation
          (oracle/evaluate-stratified
           (oracle-snapshot (into users folders) relationships))
          {:keys [adapter plan eid] :as fixture} (seed relationships)
          points
          (vec
           (for [subject users resource folders]
             {:direction :forward :subject-type :user
              :subject-eid (eid subject) :resource-eid (eid resource)}))
          reverse-points (mapv #(assoc % :direction :reverse) points)
          expected
          (vec
           (for [subject users resource folders]
             (oracle/evaluated-check?
              evaluation (oracle-entity subject) :allowed
              (oracle-entity resource))))
          forward
          (:decisions
           (recursive/evaluate-many
            {:adapter adapter :plan plan :candidates points
             :scope-identity [:differential seed-value :forward]}))
          reverse
          (:decisions
           (recursive/evaluate-many
            {:adapter adapter :plan plan :candidates reverse-points
             :scope-identity [:differential seed-value :reverse]}))]
      (is (= expected forward)
          (str "forward seed " seed-value))
      (is (= expected reverse)
          (str "reverse seed " seed-value)))))

(deftest complete-portable-checkpoint-replays-without-backend-work-test
  (let [{:keys [adapter plan] :as fixture} (chain-fixture)
        options {:adapter adapter :plan plan
                 :candidates (candidates fixture)
                 :scope-identity {:proof :generation-a}
                 :undelivered-boundary [:cover :candidate-17]}
        first-run (recursive/evaluate-many options)
        replay-stats (atom nil)
        replay
        (binding [recursive/*recursive-stats* replay-stats]
          (recursive/evaluate-many
           (assoc options :checkpoint (:checkpoint first-run))))
        checkpoint (:checkpoint first-run)]
    (is (= (:decisions first-run) (:decisions replay)))
    (is (true? (:replayed? replay)))
    (is (nil? @replay-stats))
    (is (true? (:completed? checkpoint)))
    (is (= [0 1] (:completed-strata checkpoint)))
    (is (= [:cover :candidate-17]
           (:undelivered-boundary checkpoint)))
    (is (empty? (:pending-negative-questions checkpoint)))
    (is (empty? (:pending-commands checkpoint)))
    (is (not-any? fn? (tree-seq coll? seq checkpoint)))
    (is (= :eacl.operator/invalid-recursive-evaluation
           (:type
            (error-data
             #(recursive/evaluate-many
               (assoc options
                      :scope-identity {:proof :generation-b}
                      :checkpoint checkpoint))))))))

(deftest fact-arrival-order-does-not-change-results-or-retained-state-test
  (let [alice (object :user "alice")
        bob (object :user "bob")
        f0 (object :folder "f0")
        f1 (object :folder "f1")
        f2 (object :folder "f2")
        relationships (chain-relationships alice bob f0 f1 f2)
        left (seed relationships)
        right (seed (reverse relationships))
        left-result (recursive/evaluate-many
                     {:adapter (:adapter left) :plan (:plan left)
                      :candidates (candidates left)
                      :scope-identity :arrival})
        right-result (recursive/evaluate-many
                      {:adapter (:adapter right) :plan (:plan right)
                       :candidates (candidates right)
                       :scope-identity :arrival})]
    (is (= (:decisions left-result) (:decisions right-result)))
    (is (= (select-keys (:counters left-result)
                        [:facts :anchor-states :join-slots :join-words
                         :transitions :maximum-queue])
           (select-keys (:counters right-result)
                        [:facts :anchor-states :join-slots :join-words
                         :transitions :maximum-queue])))))

(deftest recursive-limits-and-cancellation-fail-before-completion-test
  (let [{:keys [adapter plan] :as fixture} (chain-fixture)
        options {:adapter adapter :plan plan
                 :candidates (candidates fixture)
                 :scope-identity :limits}]
    (let [error
          (error-data
           #(recursive/evaluate-many
             (assoc options :limits {:maximum-anchor-states 1})))]
      (is (= :eacl.operator/recursive-limit-exceeded (:type error)))
      (is (= :anchor-states (:dimension error)))
      (is (= 1 (get-in error [:counters :anchor-states]))))
    (let [token (execution/cancellation-token)
          contract (execution/normalize
                    {} :check-permission {:cancellation-token token})]
      (execution/cancel! token)
      (is (= :eacl.execution/cancelled
             (:type
              (error-data
               #(binding [execution/*contract* contract]
                  (recursive/evaluate-many options)))))))))

(defn- public-object [type id]
  (eacl/spice-object type id))

(defn- page-ids [page]
  (mapv :id (:data page)))

(deftest public-recursive-operator-routing-is-disabled-by-default-test
  (let [{:keys [public-adapter]} (chain-fixture)
        alice (public-object :user "alice")
        f0 (public-object :folder "f0")]
    (is (= :eacl.operator/routing-disabled
           (:type
            (error-data
             #(engine/can? public-adapter alice :allowed f0)))))
    (is (= :eacl.operator/routing-disabled
           (:type
            (error-data
             #(engine/lookup-resources
               public-adapter
               {:subject alice :permission :allowed
                :resource/type :folder :first 1})))))))

(deftest public-recursive-operator-operation-matrix-is-exact-test
  (let [{:keys [public-adapter alice bob f0 f1 f2 eid]} (chain-fixture)
        public-alice (public-object :user "alice")
        public-bob (public-object :user "bob")
        public-f0 (public-object :folder "f0")
        public-f1 (public-object :folder "f1")
        public-f2 (public-object :folder "f2")
        forward {:subject public-alice :permission :allowed
                 :resource/type :folder :first 64}
        reverse {:resource public-f0 :permission :allowed
                 :subject/type :user :first 64}]
    (binding [engine/*operator-routing-enabled?* true]
      (is (true? (engine/can? public-adapter public-alice :allowed
                              public-f0)))
      (is (true? (engine/can? public-adapter public-alice :allowed
                              public-f1)))
      (is (false? (engine/can? public-adapter public-alice :allowed
                               public-f2)))
      (is (true? (engine/can? public-adapter public-bob :allowed
                              public-f0)))
      (is (= [(eid f0) (eid f1)]
             (page-ids
              (engine/lookup-resources public-adapter forward))))
      (is (= [(eid alice) (eid bob)]
             (page-ids
              (engine/lookup-subjects public-adapter reverse))))
      (is (= {:count 2 :limit -1}
             (engine/count-resources public-adapter
                                     (dissoc forward :first))))
      (is (= {:count 1 :limit 1 :truncated? true}
             (engine/count-resources
              public-adapter
              (assoc (dissoc forward :first) :count-limit 1))))
      (is (= {:count 2 :limit -1}
             (engine/count-subjects public-adapter
                                    (dissoc reverse :first))))
      (is (= {:count 1 :limit 1 :truncated? true}
             (engine/count-subjects
              public-adapter
              (assoc (dissoc reverse :first) :count-limit 1)))))))

(deftest recursive-operator-pages-compose-and-cursors-bind-scope-test
  (let [{:keys [public-adapter f0 f1 eid]} (chain-fixture)
        base {:subject (public-object :user "alice")
              :permission :allowed :resource/type :folder}]
    (binding [engine/*operator-routing-enabled?* true]
      (let [first-page
            (engine/lookup-resources public-adapter (assoc base :first 1))
            edge (get-in first-page [:page-info :end-cursor])
            second-page
            (engine/lookup-resources
             public-adapter (assoc base :first 1 :after edge))]
        (is (= [(eid f0) (eid f1)]
               (into (page-ids first-page) (page-ids second-page))))
        (is (= :operator-recursive-edge (:kind edge)))
        (is (= recursive/checkpoint-version
               (:recursive-checkpoint-version edge)))
        (is (map? (:cover-edge edge)))
        (doseq [[field replacement]
                [[:fingerprint "wrong-plan"]
                 [:cover-fingerprint "wrong-cover"]
                 [:semantic-scope "wrong-scope"]
                 [:recursive-checkpoint-version 999]
                 [:traversal :reverse]
                 [:cover-edge nil]]]
          (is (contains?
               #{:eacl.pagination/invalid-cursor
                 :eacl.pagination/wrong-cursor-kind}
               (:eacl/error
                (error-data
                 #(engine/lookup-resources
                   public-adapter
                   (assoc base :first 1
                          :after (assoc edge field replacement))))))))))))

(deftest recursive-filtered-page-retains-empty-bounded-progress-test
  (let [{:keys [public-adapter f1 eid]} (chain-fixture)
        target (eid f1)
        base {:subject (public-object :user "alice")
              :permission :allowed :resource/type :folder :first 1}
        candidate-filter {:candidate-window 1
                          :accept? #(= target (:id %))}]
    (binding [engine/*operator-routing-enabled?* true]
      (loop [query base steps 0 saw-empty-progress? false]
        (is (< steps 16))
        (let [page (engine/lookup-resources
                    public-adapter query
                    {:candidate-filter candidate-filter})
              values (page-ids page)
              cursor (get-in page [:page-info :end-cursor])
              empty-progress?
              (and (empty? values)
                   (get-in page [:page-info :bounded?])
                   (some? cursor))]
          (if (seq values)
            (do
              (is (= [target] values))
              (is saw-empty-progress?))
            (do
              (is (get-in page [:page-info :has-next-page?]))
              (is (some? cursor))
              (recur (assoc base :after cursor)
                     (inc steps)
                     (or saw-empty-progress? empty-progress?)))))))))

(deftest authenticated-recursive-cursor-keeps-the-v8-envelope-test
  (let [{:keys [client]} (chain-fixture)
        base {:subject (public-object :user "alice")
              :permission :allowed :resource/type :folder}]
    (binding [engine/*operator-routing-enabled?* true]
      (let [first-page (eacl/lookup-resources client (assoc base :first 1))
            token (get-in first-page [:page-info :end-cursor])
            envelope (datascript/token->cursor token)
            second-page
            (eacl/lookup-resources client (assoc base :first 1 :after token))]
        (is (= ["f0"] (page-ids first-page)))
        (is (= ["f1"] (page-ids second-page)))
        (is (= 13 (:v envelope)))
        (is (= :operator-recursive-edge
               (get-in envelope [:edge :kind])))
        (is (= recursive/checkpoint-version
               (get-in envelope [:edge :recursive-checkpoint-version])))))))
