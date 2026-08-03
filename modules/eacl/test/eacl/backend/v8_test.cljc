(ns eacl.backend.v8-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
            :refer [deftest is testing]]
            [eacl.backend.spi :as legacy]
            [eacl.backend.v8 :as backend]
            [eacl.engine.v8 :as engine]
            [eacl.spicedb.consistency :as consistency]))

(defn- operation-map []
  (into {}
        (map (fn [operation]
               [operation (fn [& args] [operation (vec args)])]))
        backend/required-snapshot-operations))

(defn- test-adapter []
  (backend/make-adapter
   {:id :test
    :capabilities {:consistency #{:fully-consistent}
                   :snapshots #{:current}
                   :cursor #{:forward :reverse}
                   :transactions #{}
                   :cache-proofs #{:schema :relations :snapshot-bound}
                   :runtime #{#?(:clj :clj :cljs :cljs)}}
    :operations (operation-map)}))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (ex-data error))))

(deftest validated-v8-adapter-test
  (let [adapter (test-adapter)]
    (is (backend/adapter? adapter))
    (is (= :test (backend/backend-id adapter)))
    (is (backend/supports? adapter :consistency :fully-consistent))
    (is (not (backend/supports? adapter :consistency :at-exact-snapshot)))
    (is (= {:mode :fully-consistent}
           (backend/require-consistency!
            adapter consistency/fully-consistent)))
    (is (= [:schema-proof []]
           (backend/invoke adapter :schema-proof)))
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
  (is (= backend/required-snapshot-operations
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
                 [:graph-head (fn [& _] []) [] :map-shape]
                 [:relation-defs (fn [& _] [:not-a-map])
                  [:document :reader] :finite-definition-sequence]
                 [:permission-defs (fn [& _] [nil])
                  [:document :view] :finite-definition-sequence]
                 [:all-permission-nodes (fn [& _] [])
                  [] :finite-node-set]
                 [:contains-anchor? (fn [& _] :yes)
                  ["anchor"] :boolean-result]
                 [:direct-match? (fn [& _] nil)
                  [:user 1 2 :document 3] :boolean-result]
                 [:select-current (fn [& _] {})
                  [] :adapter-or-unavailable]
                 [:select-authoritative (fn [& _] :snapshot)
                  [100] :adapter-or-unavailable]
                 [:select-at-least (fn [& _] false)
                  [{} 100] :adapter-or-unavailable]
                 [:select-exact (fn [& _] [])
                  [{} 100] :adapter-or-unavailable]
                 [:frontier-key (fn [& _] nil)
                  [{:id 1}] :non-nil-key]]]
          (is (= obligation
                 (:obligation
                  (violation operation implementation args)))
              (str "guard " operation)))))))

(deftest legacy-six-function-spi-remains-compatible-test
  (let [calls (atom [])
        implementation
        {:cache-stamp (fn [] (swap! calls conj [:cache-stamp]) :stamp)
         :relation-defs (fn [& args]
                          (swap! calls conj [:relation-defs args])
                          :relations)
         :permission-defs (fn [& args]
                            (swap! calls conj [:permission-defs args])
                            :permissions)
         :subject->resources (fn [& args]
                               (swap! calls conj [:subject->resources args])
                               :resources)
         :resource->subjects (fn [& args]
                               (swap! calls conj [:resource->subjects args])
                               :subjects)
         :direct-match? (fn [& args]
                          (swap! calls conj [:direct-match? args])
                          true)}]
    (is (identical? implementation
                    (backend/validate-legacy-adapter! implementation)))
    (is (= :stamp (legacy/cache-stamp implementation)))
    (is (= :relations (legacy/relation-defs implementation :doc :reader)))
    (is (= :permissions
           (legacy/permission-defs implementation :doc :view)))
    (is (= :resources
           (legacy/subject->resources
            implementation :user 1 2 :doc {:direction :asc})))
    (is (= :subjects
           (legacy/resource->subjects
            implementation :doc 3 2 :user {:direction :desc})))
    (is (true?
         (legacy/direct-match?
          implementation :user 1 2 :doc 3)))
    (is (= [:cache-stamp] (first @calls)))
    (is (= 6 (count @calls)))))

(deftest recursive-routing-is-compiled-once-for-the-schema-generation-test
  (let [permission-defs
        {[:node :read]
         [{:permission-id 1
           :resource-type :node
           :permission-name :read
           :source-relation-name :self
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
           :source-relation-name :self
           :target-type :permission
           :target-name :cycle-b}]
         [:node :cycle-b]
         [{:permission-id 6
           :resource-type :node
           :permission-name :cycle-b
           :source-relation-name :self
           :target-type :permission
           :target-name :cycle-a}]
         [:node :cycle-view]
         [{:permission-id 7
           :resource-type :node
           :permission-name :cycle-view
           :source-relation-name :self
           :target-type :permission
           :target-name :cycle-a}]}
        operations
        (merge
         (operation-map)
         {:snapshot-id (fn [] {:database-id :test :basis-t 1})
          :source-scope (fn [] {:source-id :test :branch nil})
          :schema-proof (fn
                          ([] :schema-proof)
                          ([_] :schema-proof))
          :permission-defs
          (fn [resource-type permission-name]
            (get permission-defs
                 [resource-type permission-name]
                 []))
          :relation-defs
          (fn [resource-type relation-name]
            (if (= [:node :editor]
                   [resource-type relation-name])
              [{:relation-id 4
                :resource-type :node
                :relation-name :editor
                :subject-type :user}]
              []))
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
           :cache-proofs #{:schema :relations :snapshot-bound}
           :runtime #{#?(:clj :clj :cljs :cljs)}}
          :operations operations})
        schema-cache (engine/make-schema-cache adapter :schema-proof)]
    (binding [engine/*schema-cache* schema-cache]
      (is (true? (engine/traversal-permission? adapter :node :read)))
      (let [analysis-delay @(:traversal-analysis schema-cache)
            compiled @analysis-delay]
        (is (= {[:node :read] true
                [:node :view] true
                [:node :edit] false
                [:node :cycle-a] true
                [:node :cycle-b] true
                [:node :cycle-view] true}
               compiled))
        (is (false? (engine/traversal-permission?
                     adapter :node :edit)))
        (is (identical? analysis-delay
                        @(:traversal-analysis schema-cache))
            "another permission root reuses the generation-wide analysis"))
      (let [stats (atom {})
            query (fn [subject-eid]
                    {:subject {:type :user :id subject-eid}
                     :permission :read
                     :resource/type :node
                     :first 1})]
        (binding [engine/*recursive-traversal-stats* stats]
          (is (= [] (:data (engine/lookup-resources
                            adapter (query 1001)))))
          (is (= [] (:data (engine/lookup-resources
                            adapter (query 1002))))))
        (is (= 1 (:compiled-recursive-plans @stats))
            "different principals share one immutable recursive plan")
        (is (= 1 (count @(:recursive-plans schema-cache))))
        (engine/evict-permission-paths-cache! schema-cache)
        (binding [engine/*recursive-traversal-stats* stats]
          (is (= [] (:data (engine/lookup-resources
                            adapter (query 1003))))))
        (is (= 2 (:compiled-recursive-plans @stats))
            "schema-cache eviction forces plan recompilation")))))

(deftest recursive-page-stream-batches-track-the-requested-window-test
  (let [permission-defs
        [{:permission-id 1
          :resource-type :node
          :permission-name :read
          :source-relation-name :self
          :target-type :relation
          :target-name :reader}
         {:permission-id 2
          :resource-type :node
          :permission-name :read
          :source-relation-name :self
          :target-type :permission
          :target-name :read}]
        operations
        (merge
         (operation-map)
         {:snapshot-id (fn [] {:database-id :test :basis-t 1})
          :source-scope (fn [] {:source-id :test :branch nil})
          :schema-proof (fn
                          ([] :schema-proof)
                          ([_] :schema-proof))
          :object-id->internal identity
          :internal-id->object identity
          :permission-defs
          (fn [resource-type permission-name]
            (if (= [:node :read]
                   [resource-type permission-name])
              permission-defs
              []))
          :relation-defs
          (fn [resource-type relation-name]
            (if (= [:node :reader]
                   [resource-type relation-name])
              [{:relation-id 3
                :resource-type :node
                :relation-name :reader
                :subject-type :user}]
              []))
          :subject->resources
          (fn [_subject-type _subject-id _relation-id _resource-type
               {:keys [bound-eid]}]
            (range (inc (or bound-eid 0)) 2001))
          :all-permission-nodes
          (fn [] #{[:node :read]})})
        adapter
        (backend/make-adapter
         {:id :test
          :capabilities
          {:consistency #{:fully-consistent}
           :snapshots #{:current}
           :cursor #{:forward :reverse}
           :transactions #{}
           :cache-proofs #{:schema :relations :snapshot-bound}
           :runtime #{#?(:clj :clj :cljs :cljs)}}
          :operations operations})
        schema-cache (engine/make-schema-cache adapter :schema-proof)
        query
        (fn [page-size]
          {:subject {:type :user :id 1001}
           :permission :read
           :resource/type :node
           :first page-size})
        run-page
        (fn [page-size]
          (let [stats (atom {})
                page
                (binding [engine/*schema-cache* schema-cache
                          engine/*recursive-traversal-stats* stats]
                  (engine/lookup-resources
                   adapter (query page-size)))]
            {:page page
             :stats @stats}))]
    (doseq [[page-size expected-fills expected-fetched]
            [[20 2 34]
             [100 4 132]
             [300 5 325]]]
      (testing (str "page size " page-size)
        (let [{:keys [page stats]} (run-page page-size)]
          (is (= page-size (count (:data page))))
          (is (= expected-fills (:stream-fills stats)))
          (is (= expected-fetched (:fetched-stream-datoms stats))))))
    (is (= 1 (count @(:recursive-plans schema-cache)))
        "batch tuning never changes the schema-derived traversal plan")))
