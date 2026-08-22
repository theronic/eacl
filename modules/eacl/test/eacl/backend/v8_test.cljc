(ns eacl.backend.v8-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.v8 :as engine]
            [eacl.lazy-merge-sort :as lazy-sort]
            [eacl.spicedb.consistency :as consistency]
            [eacl.subproblem-cache :as subproblem]
            [eacl.verified-kernel :as verified]))

(defrecord RecordingKernel [calls result]
  verified/DecisionKernel
  (-decide [_ operation input]
    (swap! calls conj [operation input])
    (result operation input))

  verified/IndexedTraversalKernel
  (-compile-indexed-plan [_ input]
    (swap! calls conj [:indexed-traversal-compile input])
    {:compiled input})
  (-initialize-indexed [_ _ _]
    (throw (ex-info "not used by this recording kernel" {})))
  (-drive-indexed [_ _ _ _ _]
    (throw (ex-info "not used by this recording kernel" {})))
  (-resume-indexed [_ _ _ _ _]
    (throw (ex-info "not used by this recording kernel" {})))
  (-continue-indexed-page [_ _ _ _]
    (throw (ex-info "not used by this recording kernel" {})))
  (-read-indexed-result [_ _ _]
    (throw (ex-info "not used by this recording kernel" {}))))

(defn- operation-map []
  (assoc
   (into {}
         (map (fn [operation]
                [operation (fn [& args] [operation (vec args)])]))
         backend/required-snapshot-operations)
   :proof-frame
   (fn [relation-ids]
     {:schema-stamp 1
      :relation-stamps (mapv (fn [relation-id]
                               [relation-id 1])
                             relation-ids)})))

(defn- test-adapter []
  (backend/make-adapter
   {:id :test
    :capabilities {:consistency #{:fully-consistent}
                   :snapshots #{:current}
                   :cursor #{:forward :reverse}
                   :transactions #{}
                   :cache-proofs #{:ordered-generations :snapshot-bound}
                   :runtime #{#?(:clj :clj :cljs :cljs)}}
    :operations (operation-map)}))

(defn- generation-adapter [generation]
  (backend/make-adapter
   {:id :test
    :capabilities {:consistency #{:fully-consistent}
                   :snapshots #{:current}
                   :cursor #{:forward :reverse}
                   :transactions #{}
                   :cache-proofs #{:ordered-generations :snapshot-bound}
                   :runtime #{#?(:clj :clj :cljs :cljs)}}
    :operations
    (assoc (operation-map)
           :proof-frame
           (fn [relation-ids]
             {:schema-stamp generation
              :relation-stamps
              (mapv (fn [relation-id]
                      [relation-id generation])
                    relation-ids)})
           :source-scope (constantly {:source-id :one}))}))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (ex-data error))))

(deftest descending-merge-retains-maximum-eid-test
  (let [maximum-eid #?(:clj Long/MAX_VALUE
                       :cljs js/Number.MAX_SAFE_INTEGER)]
    (is (= [maximum-eid 9 8]
           (vec
            (lazy-sort/lazy-fold2-merge-dedupe-sorted-by-desc
             identity
             [[maximum-eid 9]
              [8]]))))
    (is (= [maximum-eid 9 8]
           (vec
            (lazy-sort/lazy-fold2-merge-dedupe-sorted-by-desc
             identity
             [[maximum-eid 9]
              [maximum-eid 8]]))))))

(deftest generic-merge-retains-nil-key-test
  (let [left-nil {:key nil :source :left}
        right-nil {:key nil :source :right}
        one {:key 1}
        two {:key 2}]
    (is (= [left-nil one two]
           (vec
            (lazy-sort/lazy-fold2-merge-dedupe-sorted-by
             :key
             [[left-nil one]
              [two]]))))
    (is (= [left-nil one two]
           (vec
            (lazy-sort/lazy-fold2-merge-dedupe-sorted-by
             :key
             [[left-nil one]
              [right-nil two]]))))))

(deftest validated-v8-adapter-test
  (let [adapter (test-adapter)]
    (is (backend/adapter? adapter))
    (is (= :test (backend/backend-id adapter)))
    (is (backend/supports? adapter :consistency :fully-consistent))
    (is (not (backend/supports? adapter :consistency :at-exact-snapshot)))
    (is (= {:mode :fully-consistent}
           (backend/require-consistency!
            adapter consistency/fully-consistent)))
    (is (= {:schema-stamp 1 :relation-stamps []}
           (backend/invoke adapter :proof-frame [])))
    (testing "unsupported guarantees fail before execution"
      (is (= {:type :eacl/unsupported-capability
              :capability :consistency
              :requested :at-exact-snapshot}
             (select-keys
              (error-data
               #(backend/require-consistency!
                 adapter
                 (consistency/at-exact-snapshot "token")))
              [:type :capability :requested]))))
    (testing "missing optional operations are typed capabilities"
      (is (= {:type :eacl/unsupported-capability
              :capability :operation
              :requested :delete-object-tx}
             (select-keys
              (error-data
               #(backend/invoke adapter :delete-object-tx 1))
              [:type :capability :requested]))))))

(deftest schema-generation-registry-is-bounded-test
  (let [registry (atom {})]
    (doseq [generation (range 100)]
      (let [cache
            (engine/schema-cache-for!
             registry (generation-adapter generation))]
        (is (= generation (:schema-version cache)))))
    (is (= 64 (count @registry)))
    (is (every? #(= :one (get-in % [3 :source-id]))
                (keys @registry)))))

(deftest unavailable-proof-uses-a-fresh-request-local-schema-cache-test
  (let [registry (atom {})
        adapter
        (backend/make-adapter
         {:id :test
          :capabilities
          {:consistency #{:fully-consistent}
           :snapshots #{:current}
           :cursor #{:forward :reverse}
           :transactions #{}
           :cache-proofs #{:snapshot-bound}
           :runtime #{#?(:clj :clj :cljs :cljs)}}
          :operations (operation-map)})
        first-cache (engine/schema-cache-for! registry adapter)
        second-cache (engine/schema-cache-for! registry adapter)]
    (is (true? (:request-local? first-cache)))
    (is (nil? (:schema-version first-cache)))
    (is (some? (:parsed-schema first-cache)))
    (is (not (identical? first-cache second-cache)))
    (is (empty? @registry))))

(deftest invalid-v8-adapter-test
  (is (= :eacl/invalid-backend-adapter
         (:type
          (error-data
           #(backend/make-adapter
             {:id :broken
              :capabilities {}
              :operations {}})))))
  (is (= :eacl/invalid-backend-adapter
         (:type
          (error-data
           #(backend/make-adapter
             {:id :broken
              :capabilities {:consistency #{:eventually-maybe}}
              :operations (operation-map)}))))))

(deftest adapter-obligation-registry-test
  (is (= (conj backend/required-snapshot-operations :proof-frame)
         (set
          (keys
           (backend/certification-obligations)))))
  (is (contains?
       (backend/certification-obligations
        :subject->resources)
       :strict-order)))

(deftest optional-runtime-guards-fail-closed-test
  (let [operations
        (assoc
         (operation-map)
         :subject->resources
         (fn [& _] [1 3 2]))
        guarded
        (backend/make-adapter
         {:id :dishonest
          :capabilities {}
          :runtime-guards? true
          :operations operations})
        unguarded
        (backend/with-runtime-guards guarded false)]
    (is (= :eacl/backend-contract-violation
           (:type
            (error-data
             #(backend/invoke
               guarded
               :subject->resources
               :user 1 2 :document
               {:direction :asc})))))
    (is (= [1 3 2]
           (backend/invoke
            unguarded
            :subject->resources
            :user 1 2 :document
            {:direction :asc})))
    (testing "inclusive and exclusive bounds are checked"
      (let [bound-violator
            (backend/make-adapter
             {:id :bound-violator
              :capabilities {}
              :runtime-guards? true
              :operations
              (assoc
               (operation-map)
               :resource->subjects
               (fn [& _] [2 3]))})]
        (is (= :inclusive-exclusive-bound
               (:obligation
                (error-data
                 #(backend/invoke
                   bound-violator
                   :resource->subjects
                   :document 4 2 :user
                   {:direction :asc
                    :bound-eid 2
                    :inclusive-bound? false})))))))
    (testing "every locally checkable extern result fails closed"
      (letfn [(violation [operation implementation args]
                (let [adapter
                      (backend/make-adapter
                       {:id :extern-violator
                        :capabilities {}
                        :runtime-guards? true
                        :operations
                        (assoc (operation-map)
                               operation implementation)})]
                  (error-data
                   #(apply backend/invoke adapter operation args))))]
        (doseq [[operation implementation args obligation]
                [[:object-id->internal
                  (fn [& _] (inc backend/maximum-exact-integer)) [:external]
                  :exact-integer]
                 [:order-hint
                  (fn [& _] (dec backend/minimum-exact-integer))
                  [] :exact-integer]
                 [:snapshot-id (fn [& _] :not-a-map) [] :map-shape]
                 [:source-scope (fn [& _] nil) [] :map-shape]
                 [:native-revision (fn [& _] []) [] :map-shape]
                 [:relation-defs (fn [& _] [:not-a-map])
                  [:document :reader] :finite-definition-sequence]
                 [:permission-defs (fn [& _] [nil])
                  [:document :view] :finite-definition-sequence]
                 [:all-permission-nodes (fn [& _] [])
                  [] :finite-node-set]
                 [:direct-match? (fn [& _] nil)
                  [:user 1 2 :document 3] :boolean-result]
                 [:select-current (fn [& _] {})
                  [] :adapter-or-unavailable]
                 [:select-authoritative (fn [& _] :snapshot)
                  [100] :adapter-or-unavailable]
                 [:select-at-least (fn [& _] false)
                  [{} 100] :adapter-or-unavailable]
                 [:select-exact (fn [& _] [])
                  [{} 100] :adapter-or-unavailable]]]
          (is (= obligation
                 (:obligation
                  (violation operation implementation args)))
              (str "guard " operation)))))))

(deftest runtime-guards-reject-negative-internal-eids-test
  (letfn [(violation [operation implementation args]
            (let [adapter
                  (backend/make-adapter
                   {:id :negative-eid-adapter
                    :capabilities {}
                    :runtime-guards? true
                    :operations
                    (assoc (operation-map)
                           operation implementation)})]
              (error-data
               #(apply backend/invoke adapter operation args))))]
    (doseq [[operation implementation args]
            [[:object-id->internal
              (fn [& _] -1)
              ["external"]]
             [:order-hint
              (fn [& _] -1)
              []]
             [:subject->resources
              (fn [& _] [-2 -1])
              [:user 1 2 :document {:direction :asc}]]
             [:resource->subjects
              (fn [& _] [-2 -1])
              [:document 1 2 :user {:direction :asc}]]]]
      (let [failure (violation operation implementation args)]
        (is (= :eacl/backend-contract-violation
               (:type failure))
            (str operation " " failure))
        (is (= :nonnegative (:obligation failure))
            (str operation " " failure))))))

(deftest recursive-routing-is-compiled-once-for-the-schema-generation-test
  (let [permission-defs
        {[:node :read]
         [{:permission-id 1
           :resource-type :node
           :permission-name :read
           :source-relation-name :parent
           :target-type :permission
           :target-name :read}]
         [:node :view]
         [{:permission-id 2
           :resource-type :node
           :permission-name :view
           :source-relation-name :self
           :target-type :permission
           :target-name :read}]
         [:node :edit]
         [{:permission-id 3
           :resource-type :node
           :permission-name :edit
           :source-relation-name :self
           :target-type :relation
           :target-name :editor}]
         [:node :cycle-a]
         [{:permission-id 5
           :resource-type :node
           :permission-name :cycle-a
           :source-relation-name :parent
           :target-type :permission
           :target-name :cycle-b}]
         [:node :cycle-b]
         [{:permission-id 6
           :resource-type :node
           :permission-name :cycle-b
           :source-relation-name :parent
           :target-type :permission
           :target-name :cycle-a}]
         [:node :cycle-view]
         [{:permission-id 7
           :resource-type :node
           :permission-name :cycle-view
           :source-relation-name :parent
           :target-type :permission
           :target-name :cycle-a}]}
        operations
        (merge
         (operation-map)
         {:snapshot-id (fn [] {:database-id :test :basis-t 1})
          ;; A real store mints one lifecycle per source; a constant here
          ;; would alias every test adapter into one plan-cache identity.
          :source-lifecycle
          (let [lifecycle (str (gensym "compiled-once-store-"))]
            (fn [] lifecycle))
          :source-scope (fn [] {:source-id :test :branch nil})
          :proof-frame
          (fn [relation-ids]
            {:schema-stamp 1
             :relation-stamps
             (mapv (fn [relation-id] [relation-id 1]) relation-ids)})
          :object-id->internal identity
          :internal-id->object identity
          :permission-defs
          (fn [resource-type permission-name]
            (get permission-defs
                 [resource-type permission-name]
                 []))
          :relation-defs
          (fn [resource-type relation-name]
            (case [resource-type relation-name]
              [:node :editor]
              [{:relation-id 4
                :resource-type :node
                :relation-name :editor
                :subject-type :user}]

              [:node :parent]
              [{:relation-id 8
                :resource-type :node
                :relation-name :parent
                :subject-type :node}]

              []))
          :relation-populated?
          (fn [_subject-type relation-id _resource-type]
            (= 8 relation-id))
          :all-permission-nodes
          (fn [] (set (keys permission-defs)))})
        adapter
        (backend/make-adapter
         {:id :test
          :capabilities
          {:consistency #{:fully-consistent}
           :snapshots #{:current}
           :cursor #{:forward :reverse}
           :transactions #{}
           :cache-proofs #{:ordered-generations :snapshot-bound}
           :runtime #{#?(:clj :clj :cljs :cljs)}}
          :operations operations})
        schema-cache (engine/make-schema-cache adapter 1)]
    (binding [engine/*schema-cache* schema-cache]
      ;; The retired routing analysis (traversal-permission?, SCC
      ;; component plans) is gone; recursion classification now lives on
      ;; the sealed plan as :recursive?, asserted below.
      (let [seals (atom 0)
            seal sealed-plan/seal-plan
            counting-seal (fn [snapshot root]
                            (swap! seals inc)
                            (seal snapshot root))
            query (fn [subject-eid]
                    {:subject {:type :user :id subject-eid}
                     :permission :read
                     :resource/type :node
                     :first 1})]
        (with-redefs [sealed-plan/seal-plan counting-seal]
          (is (= [] (:data (engine/lookup-resources
                            adapter (query 1001)))))
          (is (= [] (:data (engine/lookup-resources
                            adapter (query 1002)))))
          (is (= 1 @seals)
              "different principals share one immutable sealed plan")
          (is (= [] (:data (engine/lookup-resources
                            adapter (query 1001)))))
          (is (= 1 @seals)
              "repeated queries reuse the generation's sealed plan"))
        ;; The sealed plan carries recursion classification directly.
        (is (true? (:recursive?
                    (sealed-plan/seal-plan adapter [:node :cycle-view])))
            "a cyclic dependency graph seals as recursive")
        (is (false? (:recursive?
                     (sealed-plan/seal-plan adapter [:node :edit])))
            "an acyclic root seals as non-recursive")
        (with-redefs [sealed-plan/seal-plan counting-seal]
          (let [other (backend/make-adapter
                       {:id :test
                        :capabilities
                        {:consistency #{:fully-consistent}
                         :snapshots #{:current}
                         :cursor #{:forward :reverse}
                         :transactions #{}
                         :cache-proofs #{:ordered-generations
                                         :snapshot-bound}
                         :runtime #{#?(:clj :clj :cljs :cljs)}}
                        :operations
                        (assoc operations
                               :source-lifecycle
                               (let [lifecycle
                                     (str (gensym "compiled-once-other-"))]
                                 (fn [] lifecycle)))})]
            (is (= [] (:data (engine/lookup-resources
                              other (query 1003)))))
            (is (= 2 @seals)
                "a distinct source never shares another store's plan")))))))

(defn- bounded-values
  [values {:keys [direction bound-eid inclusive-bound?]}]
  (let [ordered (case direction
                  :asc values
                  :desc (reverse values))
        within?
        (case direction
          :asc (if inclusive-bound? >= >)
          :desc (if inclusive-bound? <= <))]
    (if bound-eid
      (filter #(within? % bound-eid) ordered)
      ordered)))

(defn- projection-test-adapter
  []
  (backend/make-adapter
   {:id :projection-test
    :capabilities
    {:consistency #{:fully-consistent}
     :snapshots #{:current}
     :cursor #{:forward :reverse}
     :transactions #{}
     :cache-proofs #{:ordered-generations :snapshot-bound}
     :runtime #{#?(:clj :clj :cljs :cljs)}}
    :operations
    (merge
     (operation-map)
     {:subject->resources
      (fn [_subject-type _subject-eid _relation-eid _resource-type opts]
        (bounded-values (range 1 101) opts))
      :resource->subjects
      (fn [_resource-type _resource-eid _relation-eid _subject-type opts]
        (bounded-values (range 201 301) opts))
      :direct-match?
      (fn [_subject-type _subject-eid _relation-eid _resource-type resource-eid]
        (even? resource-eid))})}))

(deftest direct-projections-never-use-cache-owned-chunking-test
  (let [adapter (projection-test-adapter)
        store (subproblem/store)
        work (atom {})]
    (binding [subproblem/*store* store
              engine/*backend-work-stats* work]
      (testing "a small ascending request realizes one bounded prefix"
        (is (= (vec (range 1 21))
               (vec
                (take 20
                      (engine/subject->resources
                       adapter :user 1 10 :document nil)))))
        (is (= 1 (:subject->resources-scans @work)))
        (is (= 0 (:fetched-projection-values
                   (subproblem/stats store)))))
      (testing "a distinct consumer issues the same exact adapter request"
        (is (= (vec (range 1 21))
               (vec
                (take 20
                      (engine/subject->resources
                       adapter :user 1 10 :document nil)))))
        (is (= 2 (:subject->resources-scans @work)))
        (is (= 0 (:projection-hits (subproblem/stats store))))
        (is (= 0 (:avoided-backend-operations
                  (subproblem/stats store)))))
      (testing "host demand does not create or widen cache chunks"
        (is (= (vec (range 1 41))
               (vec
                (take 40
                      (engine/subject->resources
                       adapter :user 1 10 :document nil)))))
        (is (= 3 (:subject->resources-scans @work)))
        (testing "a repeated demand remains one direct adapter invocation"
          (is (= (vec (range 1 41))
                 (vec
                  (take 40
                        (engine/subject->resources
                         adapter :user 1 10 :document nil)))))
          (is (= 4 (:subject->resources-scans @work)))))
      (testing "inclusive and exclusive bounds are distinct semantic keys"
        (is (= [40 41 42]
               (vec
                (take 3
                      (engine/subject->resources
                       adapter :user 1 10 :document
                       {:direction :asc
                        :bound-eid 40
                        :inclusive-bound? true})))))
        (is (= [41 42 43]
               (vec
                (take 3
                      (engine/subject->resources
                       adapter :user 1 10 :document
                       {:direction :asc
                        :bound-eid 40
                        :inclusive-bound? false}))))))
      (testing "descending reverse projections preserve strict order"
        (is (= [300 299 298 297 296]
               (vec
                (take 5
                      (engine/resource->subjects
                       adapter :document 500 10 :user
                       {:direction :desc}))))))
      (testing "terminal and empty chunks are reusable"
        (is (= [100]
               (vec
                (engine/subject->resources
                 adapter :user 1 10 :document
                 {:direction :asc
                  :bound-eid 99}))))
        (let [before (:subject->resources-scans @work)]
          (is (= [100]
                 (vec
                  (engine/subject->resources
                   adapter :user 1 10 :document
                   {:direction :asc
                    :bound-eid 99}))))
          (is (= (inc before) (:subject->resources-scans @work))))
        (is (= []
               (vec
                (engine/subject->resources
                 adapter :user 1 10 :document
                 {:direction :asc
                  :bound-eid 100}))))
        (let [before (:subject->resources-scans @work)]
          (is (= []
                 (vec
                  (engine/subject->resources
                   adapter :user 1 10 :document
                   {:direction :asc
                    :bound-eid 100}))))
          (is (= (inc before) (:subject->resources-scans @work)))))
      (testing "completed exact Boolean probes are reusable"
        (is (= [true]
               (engine/direct-match-datoms-in-relationship-index
                adapter :user 1 10 :document 2)))
        (is (= []
               (engine/direct-match-datoms-in-relationship-index
                adapter :user 1 10 :document 3)))
        (let [before (:direct-match-probes @work)]
          (is (= [true]
                 (engine/direct-match-datoms-in-relationship-index
                  adapter :user 1 10 :document 2)))
          (is (= []
                 (engine/direct-match-datoms-in-relationship-index
                  adapter :user 1 10 :document 3)))
          (is (= before (:direct-match-probes @work))))))))

(deftest projection-cache-free-path-has-no-cache-effects-test
  (let [adapter (projection-test-adapter)
        work (atom {})]
    (binding [subproblem/*store* nil
              engine/*backend-work-stats* work]
      (dotimes [_ 2]
        (is (= (vec (range 1 6))
               (vec
                (take 5
                      (engine/subject->resources
                       adapter :user 1 10 :document nil))))))
      (is (= 2 (:subject->resources-scans @work))))))

(deftest generated-certified-projection-traces-match-cache-free-results-test
  (doseq [seed (range 1 41)]
    (let [values
          (->> (range (+ 5 (mod (* seed 17) 90)))
               (map #(+ 1 (* seed 1000) (* % (+ 1 (mod seed 7)))))
               vec)
          calls (atom {:forward 0 :reverse 0})
          adapter
          (backend/make-adapter
           {:id :generated-projection
            :capabilities
            {:consistency #{:fully-consistent}
             :snapshots #{:current}
             :cursor #{:forward :reverse}
             :transactions #{}
             :cache-proofs #{:ordered-generations :snapshot-bound}
             :runtime #{#?(:clj :clj :cljs :cljs)}}
            :runtime-guards? true
            :operations
            (merge
             (operation-map)
             {:subject->resources
              (fn [& args]
                (swap! calls update :forward inc)
                (bounded-values values (last args)))
              :resource->subjects
              (fn [& args]
                (swap! calls update :reverse inc)
                (bounded-values values (last args)))})})
          store (subproblem/store)
          bounds [nil
                  (first values)
                  (nth values (quot (count values) 2))
                  (peek values)]]
      (binding [subproblem/*store* store]
        (doseq [direction [:asc :desc]
                inclusive? [false true]
                bound bounds
                :let [opts {:direction direction
                            :bound-eid bound
                            :inclusive-bound? inclusive?}
                      expected (vec (bounded-values values opts))]]
          (testing (str "seed=" seed " opts=" opts)
            (let [forward
                  (vec
                   (engine/subject->resources
                    adapter :user seed 10 :document opts))
                  reverse
                  (vec
                   (engine/resource->subjects
                    adapter :document seed 10 :user opts))
                  before @calls]
              (is (= expected forward reverse))
              (is (= forward
                     (vec
                      (engine/subject->resources
                       adapter :user seed 10 :document opts))))
              (is (= reverse
                     (vec
                      (engine/resource->subjects
                       adapter :document seed 10 :user opts))))
              (is (= (-> before
                         (update :forward inc)
                         (update :reverse inc))
                     @calls)
                  "direct projection helpers do not retain host-owned scan chunks"))))))))
