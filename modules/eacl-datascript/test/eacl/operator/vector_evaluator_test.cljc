(ns eacl.operator.vector-evaluator-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.authorization.evidence-test :as evidence-fixtures]
            [eacl.backend.direct-membership :as direct]
            [eacl.cache.key :as cache-key]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.impl :as datascript-impl]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.datascript.schema :as datascript-schema]
            [eacl.operator.evaluator :as scalar]
            [eacl.operator.evaluator-test :as scalar-fixtures]
            [eacl.operator.plan :as plan]
            [eacl.operator.vector-evaluator :as vector-evaluator]
            [eacl.subproblem-cache :as subproblem]
            [eacl.relationships.staged :as staged]))

(defn- test-exact-key
  [semantic]
  (let [identity {:tier :denotation
                  :source-lifecycle {:source :test :lifecycle :operator}
                  :abi :test-authorization-v2
                  :semantic semantic
                  :reuse [:basis 1]}]
    (cache-key/exact-denotation-key identity)))

(def schema
  "definition user {}
   definition document {
     relation a: user
     relation b: user
     relation c: user
     relation banned: user
     permission view = ((a & b) + (a & c)) - banned
   }")

(defn- object [type id]
  (eacl/spice-object type [:eacl/id id]))

(defn- fixture []
  (let [conn (datascript/create-conn)
        users [(object :user "u1") (object :user "u2")]
        documents (mapv #(object :document (str "d" %)) (range 40))
        objects (into users documents)]
    (datascript-schema/write-schema! conn schema)
    (ds/transact!
     conn
     (map-indexed (fn [index value]
                    {:db/id (- (inc index))
                     :eacl/id (second (:id value))})
                  objects))
    (doseq [[index document] (map-indexed vector documents)
            relationship
            (cond-> [(eacl/->Relationship (first users) :a document)]
              (even? index)
              (conj (eacl/->Relationship (first users) :b document))
              (zero? (mod index 3))
              (conj (eacl/->Relationship (first users) :c document))
              (zero? (mod index 5))
              (conj (eacl/->Relationship (first users) :banned document)))]
      (ds/transact!
       conn
       (datascript-impl/tx-update-relationship
        (ds/db conn) {:operation :touch :relationship relationship})))
    (let [db (ds/db conn)
          eid #(ds/entid db (:id %))]
      {:conn conn :db db :adapter (datascript-backend/basis-adapter db {})
       :user (first users)
       :documents documents
       :eid eid})))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(def ^:private differential-seeds
  [104729 130363 155921 196613 262147 327673 393241 458789
   524309 589867 655373 720899 786433 851971 917519 983063])

(def ^:private differential-schema-cases
  [{:id :shared-left-union
    :source schema}
   {:id :union-under-intersection
    :source
    "definition user {}
     definition document {
       relation a: user
       relation b: user
       relation c: user
       relation banned: user
       permission view = ((a + b) & c) - banned
     }"}
   {:id :exclusion-under-intersection
    :source
    "definition user {}
     definition document {
       relation a: user
       relation b: user
       relation c: user
       relation banned: user
       permission view = (a - banned) & (b + c)
     }"}])

(defn- selected-relationship?
  [seed relation-index subject-index resource-index]
  (< (mod (+ seed
             (* 97 (inc relation-index))
             (* 193 (inc subject-index))
             (* 389 (inc resource-index))
             (* 17 (inc seed) (inc relation-index)
                (inc resource-index)))
          11)
     5))

(defn- differential-fixture [seed schema-source]
  (let [conn (datascript/create-conn)
        users (mapv #(object :user (str "u" %)) (range 2))
        documents (mapv #(object :document (str "d" %)) (range 8))
        relations [:a :b :c :banned]
        relationships
        (vec
         (for [[relation-index relation] (map-indexed vector relations)
               [subject-index subject] (map-indexed vector users)
               [resource-index resource] (map-indexed vector documents)
               :when (selected-relationship?
                      seed relation-index subject-index resource-index)]
           (eacl/->Relationship subject relation resource)))]
    (datascript-schema/write-schema! conn schema-source)
    (ds/transact!
     conn
     (map-indexed (fn [index value]
                    {:db/id (- (inc index))
                     :eacl/id (second (:id value))})
                  (into users documents)))
    (doseq [relationship relationships]
      (ds/transact!
       conn
       (datascript-impl/tx-update-relationship
        (ds/db conn) {:operation :touch :relationship relationship})))
    (let [db (ds/db conn)
          eid #(ds/entid db (:id %))]
      {:adapter (datascript-backend/basis-adapter db {})
       :users users
       :documents documents
       :relationships (set relationships)
       :eid eid})))

(defn- independent-direct-result
  [schema-id relationships subject resource]
  (let [present?
        (fn [relation]
          (contains? relationships
                     (eacl/->Relationship subject relation resource)))]
    (case schema-id
      :shared-left-union
      (and (or (and (present? :a) (present? :b))
               (and (present? :a) (present? :c)))
           (not (present? :banned)))

      :union-under-intersection
      (and (or (present? :a) (present? :b))
           (present? :c)
           (not (present? :banned)))

      :exclusion-under-intersection
      (and (present? :a)
           (not (present? :banned))
           (or (present? :b) (present? :c))))))

(deftest vector-equals-scalar-and-uses-aligned-masks-test
  (let [{:keys [adapter user documents eid]} (fixture)
        operator-plan (plan/seal-plan adapter [:document :view])
        candidates
        (mapv (fn [document]
                {:direction :forward
                 :subject-type :user :subject-eid (eid user)
                 :resource-type :document :resource-eid (eid document)})
              documents)
        expected
        (mapv (fn [candidate]
                (scalar/check-eids
                 {:adapter adapter :plan operator-plan
                  :subject-type (:subject-type candidate)
                  :subject-eid (:subject-eid candidate)
                  :resource-eid (:resource-eid candidate)}))
              candidates)
        stats (atom {})
        actual
        (binding [vector-evaluator/*vector-stats* stats]
          (vector-evaluator/check-many-eids
           {:adapter adapter :plan operator-plan
            :candidates candidates}))]
    (is (= expected actual))
    (is (= 40 (:candidate-count @stats)))
    (is (= 8 (:mask-word-count @stats)))
    (is (= (set (keep-indexed #(when %2 %1) actual))
           (set (for [index (range 40)
                      :let [word (quot index 32)
                            bit (mod index 32)]
                      :when (not (zero?
                                  (bit-and
                                   (nth (get-in @stats
                                                [:root-masks :known-true
                                                 :words]) word)
                                   (bit-shift-left 1 bit))))]
                  index))))))

(deftest fixed-seed-scalar-vector-direction-and-cache-differential-test
  (doseq [[case-index seed] (map-indexed vector differential-seeds)]
    (let [{:keys [id source]}
          (nth differential-schema-cases
               (mod case-index (count differential-schema-cases)))
          {:keys [adapter users documents relationships eid]}
          (differential-fixture seed source)
          operator-plan (plan/seal-plan adapter [:document :view])
          candidates
          (vec
           (for [subject users resource documents]
             {:direction :forward
              :subject-type :user :subject-eid (eid subject)
              :resource-type :document :resource-eid (eid resource)}))
          expected
          (vec
           (for [subject users resource documents]
             (independent-direct-result
              id relationships subject resource)))
          scalar-result
          (mapv
           (fn [candidate]
             (scalar/check-eids
              {:adapter adapter :plan operator-plan
               :subject-type (:subject-type candidate)
               :subject-eid (:subject-eid candidate)
               :resource-eid (:resource-eid candidate)}))
           candidates)
          vector-result
          (vector-evaluator/check-many-eids
           {:adapter adapter :plan operator-plan :candidates candidates})
          reverse-result
          (vector-evaluator/check-many-eids
           {:adapter adapter :plan operator-plan
            :candidates (mapv #(assoc % :direction :reverse) candidates)})
          cache-free
          (binding [subproblem/*store* nil]
            (vector-evaluator/check-cached-many-eids
             {:adapter adapter :plan operator-plan :candidates candidates
              :scope-identity [:fixed-seed seed]}))
          store (subproblem/store)
          cached-options
          {:adapter adapter :plan operator-plan :candidates candidates
           :scope-identity [:fixed-seed seed]}
          cold (binding [subproblem/*store* store
                         subproblem/*exact-denotation-key-fn* test-exact-key]
                 (vector-evaluator/check-cached-many-eids cached-options))
          warm (binding [subproblem/*store* store
                         subproblem/*exact-denotation-key-fn* test-exact-key]
                 (vector-evaluator/check-cached-many-eids cached-options))]
      (is (= expected scalar-result vector-result reverse-result
             cache-free cold warm)
          (str "fixed differential schema " id ", seed " seed))
      (is (= (count candidates)
             (get-in (subproblem/stats store)
                     [:tiers :denotation :entries]))
          (str "complete aligned points published for schema " id
               ", seed " seed)))))

(deftest reverse-witness-boundary-and-malformed-vector-test
  (let [{:keys [adapter user documents eid]} (fixture)
        operator-plan (plan/seal-plan adapter [:document :view])
        root-key [[:document :view]
                  (get-in operator-plan [:expressions 0 :root])]
        candidate {:direction :reverse
                   :subject-type :user :subject-eid (eid user)
                   :resource-type :document
                   :resource-eid (eid (first documents))}]
    (is (= [true]
           (vector-evaluator/check-many-eids
            {:adapter adapter :plan operator-plan
             :candidates [(assoc candidate :true-nodes #{root-key})]})))
    (is (= :duplicate-candidate
           (:reason
            (error-data
             #(vector-evaluator/check-many-eids
               {:adapter adapter :plan operator-plan
                :candidates [candidate candidate]})))))
    (is (= :candidate-width
           (:reason
            (error-data
             #(vector-evaluator/check-many-eids
               {:adapter adapter :plan operator-plan
                :candidates (vec (repeat 257 candidate))})))))))

(deftest completed-acyclic-vector-decisions-reuse-only-compatible-proofs-test
  (let [{:keys [adapter user documents eid]} (fixture)
        operator-plan (plan/seal-plan adapter [:document :view])
        candidates
        (mapv (fn [document]
                {:direction :forward
                 :subject-type :user :subject-eid (eid user)
                 :resource-type :document :resource-eid (eid document)})
              (take 16 documents))
        store (subproblem/store)
        run
        (fn [scope stats]
          (binding [subproblem/*store* store
                    subproblem/*exact-denotation-key-fn* test-exact-key
                    vector-evaluator/*vector-stats* stats]
            (vector-evaluator/check-cached-many-eids
             {:adapter adapter :plan operator-plan
              :candidates candidates :scope-identity scope})))
        first-stats (atom {})
        first-result (run :proof-a first-stats)
        second-stats (atom {})
        second-result (run :proof-a second-stats)
        changed-stats (atom {})
        changed-result (run :proof-b changed-stats)]
    (is (= first-result second-result changed-result))
    (is (= 16 (:point-cache-hits @second-stats)))
    (is (zero? (:point-cache-misses @second-stats)))
    (is (nil? (:candidate-count @second-stats))
        "a complete point hit never enters vector evaluation")
    (is (zero? (:point-cache-hits @changed-stats)))
    (is (= 16 (:point-cache-misses @changed-stats)))
    (is (not (contains? (subproblem/stats store) :projection-hits))
        "physical direct decisions are not retained as shared subproblems")
    (is (not (contains? (:tiers (subproblem/stats store)) :projection)))))

(deftest failed-acyclic-vector-publishes-neither-leaves-nor-points-test
  (let [{:keys [adapter user documents eid]} (fixture)
        operator-plan (plan/seal-plan adapter [:document :view])
        candidate {:direction :forward
                   :subject-type :user :subject-eid (eid user)
                   :resource-type :document
                   :resource-eid (eid (first documents))}
        store (subproblem/store)
        failing
        (assoc-in
         adapter [:eacl.backend.v8/operations :direct-match?]
         (fn [& _]
           (throw (ex-info "Injected vector provider failure."
                           {:type :eacl.test/injected-provider-failure}))))
        error
        (binding [subproblem/*store* store
                  subproblem/*exact-denotation-key-fn* test-exact-key]
          (error-data
           #(vector-evaluator/check-cached-many-eids
             {:adapter failing :plan operator-plan
              :candidates [candidate] :scope-identity :failure})))
        stats (subproblem/stats store)]
    (is (= :eacl.test/injected-provider-failure (:type error)))
    (is (not (contains? (:tiers stats) :projection)))
    (is (zero? (get-in stats [:tiers :denotation :entries])))))

(deftest acyclic-point-cache-eviction-never-changes-denotation-test
  (let [{:keys [adapter user documents eid]} (fixture)
        operator-plan (plan/seal-plan adapter [:document :view])
        candidates
        (mapv (fn [document]
                {:direction :forward
                 :subject-type :user :subject-eid (eid user)
                 :resource-type :document :resource-eid (eid document)})
              (take 16 documents))
        store (subproblem/store {:denotation-max-entries 4})
        options {:adapter adapter :plan operator-plan
                 :candidates candidates :scope-identity :eviction}
        first-result
        (binding [subproblem/*store* store
                  subproblem/*exact-denotation-key-fn* test-exact-key]
          (vector-evaluator/check-cached-many-eids options))
        after-first (subproblem/stats store)
        second-result
        (binding [subproblem/*store* store
                  subproblem/*exact-denotation-key-fn* test-exact-key]
          (vector-evaluator/check-cached-many-eids options))]
    (is (= first-result second-result))
    (is (= {:entries 4 :max-entries 4}
           (get-in after-first [:tiers :denotation])))))

#?(:clj
   (deftest concurrent-acyclic-misses-compute-independently-and-publish-safely-test
     (let [{:keys [adapter user documents eid]} (fixture)
           operator-plan (plan/seal-plan adapter [:document :view])
           candidate
           (fn [document]
             {:direction :forward
              :subject-type :user :subject-eid (eid user)
              :resource-type :document :resource-eid (eid document)})
           run-pair
           (fn [left right]
             (let [entered (java.util.concurrent.CountDownLatch. 2)
                   release (java.util.concurrent.CountDownLatch. 1)
                   calls (atom 0)
                   original
                   (get-in adapter
                           [:eacl.backend.v8/operations :direct-match?])
                   concurrent-adapter
                   (assoc-in
                    adapter [:eacl.backend.v8/operations :direct-match?]
                    (fn [& arguments]
                      (swap! calls inc)
                      (.countDown entered)
                      (when (.await entered 5
                                    java.util.concurrent.TimeUnit/SECONDS)
                        (.countDown release))
                      (.await release 5
                              java.util.concurrent.TimeUnit/SECONDS)
                      (apply original arguments)))
                   store (subproblem/store)
                   evaluate
                   (fn [value]
                     (binding [subproblem/*store* store
                               subproblem/*exact-denotation-key-fn* test-exact-key]
                       (first
                        (vector-evaluator/check-cached-many-eids
                         {:adapter concurrent-adapter :plan operator-plan
                          :candidates [value]
                          :scope-identity :concurrent})) ))
                   left-result (future (evaluate left))
                   right-result (future (evaluate right))]
               {:results [@left-result @right-result]
                :calls @calls
                :stats (subproblem/stats store)}))
           identical
           (run-pair (candidate (nth documents 2))
                     (candidate (nth documents 2)))
           different
           (run-pair (candidate (nth documents 2))
                     (candidate (nth documents 6)))]
       (is (= [true true] (:results identical)))
       (is (= [true true] (:results different)))
       (is (>= (:calls identical) 2)
           "identical misses do not wait on a cache flight")
       (is (>= (:calls different) 2))
       (is (zero? (:failures (:stats identical) 0)))
       (is (zero? (:failures (:stats different) 0))))))

(deftest qualified-vectors-retain-alignment-and-exact-cache-scope
  (let [{:keys [conn user documents eid]} (fixture)
        documents (vec (take 8 documents))]
    (datascript-schema/write-schema! conn (str "caveat enabled(flag bool) { flag }\n" schema))
    (let [relation (fn [name] (ds/entid (ds/db conn)
                                     [:eacl.relation/resource-type+relation-name+subject-type
                                      [:document name :user]]))
          caveat (ds/entid (ds/db conn) [:eacl.caveat/name "enabled"])
          writer (qualifiers/writer conn)]
      (ds/transact! conn [{:db/id (relation :a) :eacl.relation/caveats [caveat]
                          :eacl.relation/allows-unqualified? true}])
      (doseq [[index document] (map-indexed vector documents)]
        (staged/write! writer :replace [:user (eid user) (relation :a) :document (eid document)]
                       {:caveat caveat})
        (when (zero? (mod index 5))
          (staged/write! writer :replace [:user (eid user) (relation :banned) :document (eid document)]
                         {:valid-until-ms 100})))
      (let [db (ds/db conn)
            adapter (datascript-backend/basis-adapter db {})
            sealed (plan/seal-plan adapter [:document :view])
            candidates (mapv (fn [document]
                               {:direction :forward :subject-type :user :subject-eid (eid user)
                                :resource-type :document :resource-eid (eid document)}) documents)
            store (subproblem/store)
            run (fn [time context]
                  (let [stats (atom {})
                        options {:adapter adapter :plan sealed :candidates candidates :scope-identity :qualified
                                 :qualification (scalar-fixtures/qualified-request db time context)}
                        result (binding [subproblem/*store* store
                                         subproblem/*exact-denotation-key-fn* test-exact-key
                                         vector-evaluator/*vector-stats* stats]
                                 (vector-evaluator/check-cached-many-eids options))]
                    {:result result :stats @stats}))
            before (run 99 {})
            warm (run 99 {})
            after (run 100 {})
            granted (run 100 {"flag" true})
            denied (run 100 {"flag" false})
            prior-entries (get-in (subproblem/stats store) [:tiers :denotation :entries])
            fault (run 100 {"flag" "wrong-type"})]
        (is (= (:result before) (:result warm)))
        (is (= 8 (get-in warm [:stats :point-cache-hits])))
        (is (= 8 (get-in after [:stats :point-cache-misses])))
        (is (= [:no-permission :no-permission :conditional-permission :conditional-permission
                :conditional-permission :no-permission :conditional-permission :no-permission]
               (mapv evidence/permissionship (:result before))))
        (is (= :conditional-permission (evidence/permissionship (first (:result after)))))
        (is (= [true false true true true false true false] (:result granted)))
        (is (every? false? (:result denied)))
        (is (every? evidence/fault? (:result fault)))
        (is (= prior-entries (get-in (subproblem/stats store) [:tiers :denotation :entries])))
        (let [reverse-options {:adapter adapter :plan sealed
                               :candidates (mapv #(assoc % :direction :reverse) candidates)
                               :qualification (scalar-fixtures/qualified-request db 100 {})}]
          (is (= (:result after) (vector-evaluator/check-many-eids reverse-options))))))))

(deftest exact-evidence-witnesses-avoid-rechecking-proven-nodes
  (let [{:keys [adapter db user documents eid]} (fixture)
        sealed (plan/seal-plan adapter [:document :view])
        root-key [[:document :view] (get (plan/expression-roots sealed) [:document :view])]
        candidate {:direction :forward :subject-type :user :subject-eid (eid user)
                   :resource-type :document :resource-eid (eid (nth documents 2))}
        request (scalar-fixtures/qualified-request db 99 {})
        options {:adapter adapter :plan sealed :qualification request :candidates [candidate]}
        scope (qualification/exact-reuse-identity request)]
    (doseq [leaf (list true false evidence-fixtures/x
                       (evidence/with-certificate true 100 true)
                       (evidence/with-certificate evidence-fixtures/x 100 false)
                       (evidence/fault :test/failure :invalid))]
      (let [expected (with-redefs [qualification/qualify (fn [_ _ edge] (if edge leaf false))]
                       (vector-evaluator/check-many-eids options))
            witnessed (assoc candidate :evidence-witnesses {root-key (first expected)})]
        (with-redefs [direct/dispatch-edges (fn [& _] (throw (ex-info "Already proved" {})))]
          (is (= expected (vector-evaluator/check-many-eids
                           (assoc options :candidates [witnessed] :witness-scope scope)))))))
    (let [witnessed (assoc candidate :evidence-witnesses {root-key true})
          options (assoc options :candidates [witnessed] :witness-scope scope)]
      (doseq [changed [(assoc options :witness-scope nil)
                       (assoc options :qualification (scalar-fixtures/qualified-request db 100 {}))
                       (assoc options :qualification (scalar-fixtures/qualified-request db 99 {"flag" true}))
                       (assoc options :qualification (qualification/request (assoc request :basis {:source :another :revision 1})))
                       (assoc options :qualification (qualification/request (assoc request :evaluator nil)))]]
        (is (= :witness-scope (:reason (error-data #(vector-evaluator/check-many-eids changed))))))
      (is (= :qualified-witness-required
             (:reason (error-data #(vector-evaluator/check-many-eids (dissoc options :qualification))))))
      (is (= :expired-witness
             (:reason (error-data #(vector-evaluator/check-many-eids
                                    (assoc options :candidates [(assoc candidate :evidence-witnesses
                                                                       {root-key (evidence/with-certificate true 99 true)})]))))))
      (with-redefs [vector-evaluator/maximum-evidence-witnesses 0]
        (is (= :witness-limit (:reason (error-data #(vector-evaluator/check-many-eids options))))))
      (with-redefs [vector-evaluator/maximum-evidence-witness-bytes 0]
        (is (= :witness-size (:reason (error-data #(vector-evaluator/check-many-eids options))))))
      (let [store (subproblem/store)]
        (binding [subproblem/*store* store subproblem/*exact-denotation-key-fn* test-exact-key]
          (is (= [true] (vector-evaluator/check-cached-many-eids options)))
          (let [leaf-key [[:document :view]
                          (first (remove #{(second root-key)}
                                         (keys (get-in sealed [:predicate-programs [:document :view]]))))]
                fault (evidence/fault :test/failure :invalid)]
            (with-redefs [direct/dispatch-edges (fn [& _] (throw (ex-info "Fault already encountered" {})))]
              (is (= [fault] (vector-evaluator/check-cached-many-eids
                              (assoc options :candidates [(assoc candidate :evidence-witnesses {leaf-key fault})]))))))
          (is (= :witness-scope
                 (:reason (error-data #(vector-evaluator/check-cached-many-eids
                                        (dissoc options :witness-scope)))))))))
    (is (= :invalid-witness-node
           (:reason (error-data #(vector-evaluator/check-many-eids
                                  (assoc options :witness-scope scope
                                         :candidates [(assoc candidate :evidence-witnesses {[[:absent :view] 0] true})]))))))))
