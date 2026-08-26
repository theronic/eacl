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
            [eacl.operator-engine.oracle :as oracle]
            [eacl.subproblem-cache :as subproblem]))

(def recursive-schema
  "definition user {}
   definition folder {
     relation direct: user
     relation eligible: user
     relation banned: user
     relation unrelated: user
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

(def recursive-mutual-conjunction-schema
  "definition user {}
   definition folder {
     relation seed_a: user
     relation seed_b: user
     relation gate_a: user
     relation gate_b: user
     relation banned: user
     relation parent: folder
     permission a = seed_a + (parent->b & gate_a)
     permission b = seed_b + (parent->a & gate_b)
     permission view = a
     permission allowed = a - banned
   }")

(def recursive-typed-collision-schema
  "definition user {}
   definition service {}
   definition folder {
     relation user_direct: user
     relation service_direct: service
     relation parent: folder
     permission user_view = user_direct + parent->user_view
     permission service_view = service_direct + parent->service_view
     permission view = user_view
     permission allowed = user_view - service_view
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
    (is (zero? (get-in result [:counters :duplicate-facts]))
        "a witnessed recursive union does not fetch its redundant cycle edge")
    (is (every? #(= 2 (get-in % [1 :width]))
                (get-in result [:checkpoint :anchor-states])))))

(deftest recursive-conjunction-star-propagates-from-one-anchor-test
  (let [alice (object :user "alice")
        f0 (object :folder "f0")
        f1 (object :folder "f1")
        f2 (object :folder "f2")
        relationships
        [(eacl/->Relationship alice :direct f0)
         (eacl/->Relationship f0 :parent f1)
         (eacl/->Relationship f0 :parent f2)
         (eacl/->Relationship alice :eligible f1)
         (eacl/->Relationship alice :eligible f2)]
        {:keys [adapter view-plan eid]}
        (seed relationships)
        result
        (recursive/evaluate-many
         {:adapter adapter :plan view-plan
          :candidates
          (mapv (fn [resource]
                  {:direction :forward :subject-type :user
                   :subject-eid (eid alice) :resource-eid (eid resource)})
                [f0 f1 f2])
          :scope-identity :recursive-star})]
    (is (= [true true true] (:decisions result)))
    (is (<= (get-in result [:counters :anchor-states])
            (get-in result [:counters :facts])))))

(deftest recursive-demand-discovery-skips-blocked-arrows-and-stops-at-a-witness-test
  (let [{:keys [adapter plan alice bob f0 f1 eid]} (chain-fixture)
        point
        (fn [subject resource]
          {:direction :forward :subject-type :user
           :subject-eid (eid subject) :resource-eid (eid resource)})
        forbidden-scan
        (assoc-in
         adapter [:eacl.backend.v8/operations :resource->subjects]
         (fn [& _]
           (throw (ex-info "A false intersection anchor read its arrow."
                           {:type :eacl.test/unexpected-arrow-read}))))
        gated
        (recursive/evaluate-many
         {:adapter forbidden-scan :plan plan
          :candidates [(point bob f1)]
          :scope-identity :false-anchor-skips-arrow})
        scan-calls (atom 0)
        high-fanout (into [(eid f0)] (range 1000 1128))
        high-fanout-adapter
        (assoc-in
         adapter [:eacl.backend.v8/operations :resource->subjects]
         (fn [& _]
           (swap! scan-calls inc)
           high-fanout))
        witnessed
        (recursive/evaluate-many
         {:adapter high-fanout-adapter :plan plan
          :candidates [(point alice f1)]
          :scope-identity :first-chunk-witness
          :limits {:physical-chunk-size 16}})]
    (is (= [false] (:decisions gated)))
    (is (zero? (get-in gated [:counters :commands])))
    (is (zero? (get-in gated [:counters :values])))
    (is (= [true] (:decisions witnessed)))
    (is (= 1 @scan-calls))
    (is (= 1 (get-in witnessed [:counters :commands])))
    (is (= 16 (get-in witnessed [:counters :values]))
        "the first recursive witness prevents reading the remaining fanout")))

(deftest mutual-recursive-conjunction-agrees-with-naive-stratified-oracle-test
  (let [alice (object :user "alice")
        f0 (object :folder "f0")
        f1 (object :folder "f1")
        f2 (object :folder "f2")
        relationships
        [(eacl/->Relationship alice :seed_a f0)
         (eacl/->Relationship f0 :parent f1)
         (eacl/->Relationship alice :gate_b f1)
         (eacl/->Relationship f1 :parent f2)
         (eacl/->Relationship alice :gate_a f2)]
        {:keys [adapter plan eid]}
        (seed-schema recursive-mutual-conjunction-schema relationships)
        points
        (mapv (fn [resource]
                {:direction :forward :subject-type :user
                 :subject-eid (eid alice) :resource-eid (eid resource)})
              [f0 f1 f2])
        snapshot
        {:objects (set (map oracle-entity [alice f0 f1 f2]))
         :relation-target-types {[:folder :parent] #{:folder}}
         :relationships
         (into #{}
               (map (fn [{:keys [subject relation resource]}]
                      {:subject (oracle-entity subject)
                       :relation relation
                       :resource (oracle-entity resource)}))
               relationships)
         :permissions
         {[:folder :a]
          [:union [:relation :seed_a]
           [:intersection [:arrow :parent :b] [:relation :gate_a]]]
          [:folder :b]
          [:union [:relation :seed_b]
           [:intersection [:arrow :parent :a] [:relation :gate_b]]]
          [:folder :allowed]
          [:exclusion [:permission :a] [:relation :banned]]}}
        evaluated (oracle/evaluate-stratified snapshot)
        expected
        (mapv #(oracle/evaluated-check?
                evaluated (oracle-entity alice) :allowed (oracle-entity %))
              [f0 f1 f2])
        actual
        (:decisions
         (recursive/evaluate-many
          {:adapter adapter :plan plan :candidates points
           :scope-identity :mutual-conjunction}))]
    (is (= [true false true] expected))
    (is (= expected actual))))

(deftest recursive-facts-keep-equal-eids-separated-by-subject-type-test
  (let [user-alice (object :user "alice")
        service-alice (object :service "alice")
        f0 (object :folder "f0")
        f1 (object :folder "f1")
        relationships
        [(eacl/->Relationship user-alice :user_direct f0)
         (eacl/->Relationship service-alice :service_direct f0)
         (eacl/->Relationship f0 :parent f1)]
        {:keys [adapter plan eid]}
        (seed-schema recursive-typed-collision-schema relationships)
        shared-eid (eid user-alice)
        resource-eids (mapv eid [f0 f1])
        run
        (fn [subject-type]
          (:decisions
           (recursive/evaluate-many
            {:adapter adapter :plan plan
             :candidates
             (mapv (fn [resource-eid]
                     {:direction :forward :subject-type subject-type
                      :subject-eid shared-eid :resource-eid resource-eid})
                   resource-eids)
             :scope-identity [:typed-collision subject-type]})))]
    (is (= shared-eid (eid service-alice)))
    (is (= [true true] (run :user)))
    (is (= [false false] (run :service)))))

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

(deftest completed-direct-decisions-reuse-proof-compatible-cache-test
  (let [{:keys [adapter plan] :as fixture} (chain-fixture)
        options {:adapter adapter :plan plan
                 :candidates (candidates fixture)
                 :scope-identity :cached-direct-decisions}
        store (subproblem/store)
        first-run (binding [subproblem/*store* store]
                    (recursive/evaluate-many options))
        second-run (binding [subproblem/*store* store]
                     (recursive/evaluate-many options))
        before-disabled (subproblem/stats store)
        disabled-run (binding [subproblem/*store* nil]
                       (recursive/evaluate-many options))
        after-disabled (subproblem/stats store)
        read-only-store (subproblem/store)
        read-only-run
        (binding [subproblem/*store* read-only-store
                  subproblem/*populate?* false]
          (recursive/evaluate-many options))]
    (is (= (:decisions first-run) (:decisions second-run)
           (:decisions disabled-run) (:decisions read-only-run)))
    (is (pos? (get-in first-run [:counters :direct-adapter-commands])))
    (is (zero? (get-in second-run [:counters :direct-adapter-commands])))
    (is (pos? (get-in second-run [:counters :direct-cache-hits])))
    (is (pos? (get-in first-run [:counters :commands])))
    (is (zero? (get-in second-run [:counters :commands])))
    (is (pos? (get-in second-run [:counters :shared-scan-cache-hits])))
    (is (= (get-in first-run [:counters :values])
           (get-in second-run [:counters :values]))
        "cached scan responses retain the same logical value charge")
    (is (= before-disabled after-disabled)
        "cache-disabled execution performs no operator cache work")
    (is (pos? (get-in read-only-run [:counters :direct-adapter-commands])))
    (is (zero? (get-in (subproblem/stats read-only-store)
                       [:tiers :projection :entries])))))

(deftest completed-recursive-points-are-reused-only-in-their-proof-scope-test
  (let [{:keys [adapter plan] :as fixture} (chain-fixture)
        store (subproblem/store)
        base {:adapter adapter :plan plan
              :candidates (candidates fixture)}
        first-run
        (binding [subproblem/*store* store]
          (recursive/evaluate-cached-many
           (assoc base :scope-identity :proof-a)))
        second-run
        (binding [subproblem/*store* store]
          (recursive/evaluate-cached-many
           (assoc base :scope-identity :proof-a)))
        changed-proof-run
        (binding [subproblem/*store* store]
          (recursive/evaluate-cached-many
           (assoc base :scope-identity :proof-b)))]
    (is (= (:decisions first-run) (:decisions second-run)
           (:decisions changed-proof-run)))
    (is (:point-cached? second-run))
    (is (= 6 (get-in second-run [:counters :point-cache-hits])))
    (is (zero? (get-in second-run [:counters :point-cache-misses])))
    (is (zero? (get-in second-run
                       [:counters :direct-adapter-commands] 0)))
    (is (false? (:point-cached? changed-proof-run)))
    (is (= 6 (get-in changed-proof-run
                     [:counters :point-cache-misses])))
    (is (pos? (get-in changed-proof-run
                      [:counters :direct-cache-hits])))))

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

(deftest recursive-provider-failure-publishes-no-point-or-leaf-decisions-test
  (let [{:keys [adapter plan] :as fixture} (chain-fixture)
        store (subproblem/store)
        failing-adapter
        (assoc-in
         adapter
         [:eacl.backend.v8/operations :direct-match?]
         (fn [& _]
           (throw (ex-info "Injected direct-membership failure."
                           {:type :eacl.test/injected-provider-failure}))))
        error
        (binding [subproblem/*store* store]
          (error-data
           #(recursive/evaluate-cached-many
             {:adapter failing-adapter :plan plan
              :candidates (candidates fixture)
              :scope-identity :provider-failure})))
        stats (subproblem/stats store)
        recovery
        (binding [subproblem/*store* store]
          (recursive/evaluate-many
           {:adapter adapter :plan plan
            :candidates (candidates fixture)
            :scope-identity :provider-failure}))]
    (is (= :eacl.test/injected-provider-failure (:type error)))
    (is (zero? (get-in stats [:tiers :denotation :entries])))
    (is (pos? (get-in recovery [:counters :direct-adapter-commands]))
        "the failed aligned leaf vector published no individual decisions")))

(deftest later-demand-round-failure-publishes-no-earlier-direct-decisions-test
  (let [{:keys [adapter plan alice f0 f1 eid]} (chain-fixture)
        original-direct
        (get-in adapter [:eacl.backend.v8/operations :direct-match?])
        failing-adapter
        (assoc-in
         adapter [:eacl.backend.v8/operations :direct-match?]
         (fn [subject-type subject-eid relation-eid
              resource-type resource-eid]
           (if (= (eid f0) resource-eid)
             (throw
              (ex-info "Injected post-arrow direct-membership failure."
                       {:type :eacl.test/injected-late-provider-failure}))
             (original-direct subject-type subject-eid relation-eid
                              resource-type resource-eid))))
        options
        {:plan plan
         :candidates
         [{:direction :forward :subject-type :user
           :subject-eid (eid alice) :resource-eid (eid f1)}]
         :scope-identity :late-provider-failure}
        store (subproblem/store)
        error
        (binding [subproblem/*store* store]
          (error-data
           #(recursive/evaluate-many
             (assoc options :adapter failing-adapter))))
        cache-free
        (binding [subproblem/*store* nil]
          (recursive/evaluate-many (assoc options :adapter adapter)))
        recovery
        (binding [subproblem/*store* store]
          (recursive/evaluate-many (assoc options :adapter adapter)))]
    (is (= :eacl.test/injected-late-provider-failure (:type error)))
    (is (= (:decisions cache-free) (:decisions recovery)))
    (is (= (get-in cache-free [:counters :direct-adapter-commands])
           (get-in recovery [:counters :direct-adapter-commands]))
        "successful direct decisions preceding the late failure stayed private")
    (is (pos? (get-in recovery [:counters :shared-scan-cache-hits]))
        "a complete exact arrow chunk remains independently reusable")))

(defn- public-object [type id]
  (eacl/spice-object type id))

(defn- page-ids [page]
  (mapv :id (:data page)))

(deftest public-recursive-operator-routing-can-be-disabled-test
  (let [{:keys [public-adapter]} (chain-fixture)
        alice (public-object :user "alice")
        f0 (public-object :folder "f0")]
    (binding [engine/*operator-routing-enabled?* false]
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
                  :resource/type :folder :first 1}))))))))

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

(deftest managed-operator-cache-lifts-only-across-unrelated-writes-test
  (let [{:keys [client]} (chain-fixture)
        alice (public-object :user "alice")
        f0 (public-object :folder "f0")
        f2 (public-object :folder "f2")
        f0-query {:subject alice :permission :allowed :resource f0}
        f2-query {:subject alice :permission :allowed :resource f2}]
    (binding [engine/*operator-routing-enabled?* true]
      (let [cold (eacl/check-permission client f0-query)
            warm (eacl/check-permission client f0-query)
            before (datascript/cache-stats client)]
        (is (true? (:allowed? cold)))
        (is (false? (:cached? cold)))
        (is (true? (:cached? warm)))
        (is (pos? (:managed-generations before)))
        (is (zero? (:stamp-failures before)))

        (eacl/create-relationship!
         client (eacl/->Relationship alice :unrelated f2))
        (let [lifted (eacl/check-permission client f0-query)
              after-unrelated (datascript/cache-stats client)]
          (is (true? (:allowed? lifted)))
          (is (true? (:cached? lifted)))
          (is (= (inc (:managed-hits before))
                 (:managed-hits after-unrelated)))
          (is (= (:misses before) (:misses after-unrelated)))
          (is (= (:stamp-failures before)
                 (:stamp-failures after-unrelated))))

        (let [excluded (eacl/check-permission client f2-query)]
          (is (false? (:allowed? excluded)))
          (is (false? (:cached? excluded))))
        (eacl/delete-relationship!
         client (eacl/->Relationship alice :banned f2))
        (let [after-relevant (eacl/check-permission client f2-query)]
          (is (true? (:allowed? after-relevant)))
          (is (false? (:cached? after-relevant))
              "a new negative-absence result must not reuse the excluded answer"))

        (let [before-bypass (datascript/cache-stats client)
              bypass
              (eacl/check-permission
               client (assoc f0-query :cache? false :populate-cache? true))
              after-bypass (datascript/cache-stats client)]
          (is (true? (:allowed? bypass)))
          (is (false? (:cached? bypass)))
          (is (= (dissoc before-bypass :bypasses)
                 (dissoc after-bypass :bypasses))
              "operator bypass performs no lookup, lifting, or publication")
          (is (= (inc (:bypasses before-bypass))
                 (:bypasses after-bypass))))))))

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
