(ns eacl.permission-tree-test
  (:require [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.execution :as execution]
            [eacl.permission-tree :as permission-tree]))

(defn- thrown-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo) error
      (ex-data error))))

(defn- fake-adapter
  [{:keys [objects relations permissions scans internal->external
           runtime-guards? codec-counts scan-error]
    :or {objects {}
         relations {}
         permissions {}
         scans {}
         internal->external {}}}]
  (let [adapter
        (backend/make-adapter
         {:id :permission-tree-test
          :capabilities backend/empty-capabilities
          :runtime-guards? runtime-guards?
          :state {:db :immutable-test-snapshot}
          :operations
          {:snapshot-id (fn [] {:snapshot :one})
           :basis-kind (constantly :ordinary)
           :native-revision (fn [] {:revision 1 :exact-locator nil})
           :order-hint (fn [] 1)
           :exact-locator (constantly nil)
           :object-id->internal (fn [external-id]
                                  (get objects external-id))
           :internal-id->object (fn [internal-id]
                                  (when codec-counts
                                    (swap! codec-counts
                                           update internal-id (fnil inc 0)))
                                  (get internal->external internal-id))
           :relation-defs (fn [resource-type relation]
                            (get relations [resource-type relation] []))
           :permission-defs (fn [resource-type permission]
                              (get permissions
                                   [resource-type permission] []))
           :permission-expression (fn [& _] nil)
           :subject->resources (fn [& _] [])
           :resource->subjects
           (fn [resource-type resource-id relation-id subject-type _]
             (if scan-error
               (throw scan-error)
               (get scans
                    [resource-type resource-id relation-id subject-type]
                    [])))
           :direct-match? (fn [& _] false)
           :relation-populated? (fn [& _] false)
           :all-permission-nodes (fn [] #{})}})]
    adapter))

(defn- relation
  [id resource-type relation-name subject-type]
  {:relation-id id
   :resource-type resource-type
   :relation-name relation-name
   :subject-type subject-type})

(defn- component
  [id resource-type permission-name source target-kind target-name]
  {:permission-id id
   :resource-type resource-type
   :permission-name permission-name
   :source-relation-name source
   :target-type target-kind
   :target-name target-name})

(defn- expand
  ([adapter resource permission]
   (expand adapter resource permission permission-tree/default-limits))
  ([adapter resource permission limits]
   (permission-tree/expand
    adapter
    {:limits limits :execution-contract nil}
    resource
    permission)))

(def ^:private operator-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission view = (reader + writer) & (reader - banned)
   }")

(defn- intermediate-operation [tree]
  (get-in tree [:intermediate :operation]))

(defn- child-relations [tree]
  (mapv :expanded-relation (get-in tree [:intermediate :children])))

(deftest strict-request-contract
  (is (= {:resource (eacl/spice-object :document "d1")
          :permission :view
          :cache? false
          :populate-cache? false}
         (permission-tree/validate-request!
          {:resource (eacl/spice-object :document "d1")
           :permission :view
           :cache? false
           :populate-cache? false})))
  (doseq [request
          [nil
           {}
           {:resource (eacl/spice-object :document "d1")}
           {:resource (eacl/spice-object :document "d1")
            :permission :view
            :unsupported-control true}
           {:resource (eacl/spice-object :document "d1" :member)
            :permission :view}
           {:resource {:type :document :id nil}
            :permission :view}
           {:resource {:type :qualified/document :id "d1"}
            :permission :view}
           {:resource (eacl/spice-object :document "d1")
            :permission :qualified/view}]]
    (let [data (thrown-data
                #(permission-tree/validate-request! request))]
      (is (= :eacl.permission-tree/invalid-request (:type data)))
      (is (= (:type data) (:eacl/error data))))))

(deftest public-operator-tree-preserves-source-structure-and-snapshot-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})
        alice (eacl/spice-object :user "alice")
        document (eacl/spice-object :document "d0")
        query {:resource document :permission :view}]
    (binding [orchestration/*operator-expression-writes-enabled?* true]
      (eacl/write-schema! client operator-schema))
    (ds/transact! conn [{:eacl/id "alice"} {:eacl/id "d0"}])
    (eacl/create-relationships!
     client [(eacl/->Relationship alice :reader document)
             (eacl/->Relationship alice :writer document)])
    (let [snapshot (eacl/snapshot client)
          observed (atom [])]
      (try
        (eacl/create-relationship!
         client (eacl/->Relationship alice :banned document))
        (let [old-response
              (binding [backend/*invoke-observer*
                        #(swap! observed conj
                                (select-keys % [:operation :phase]))]
                (eacl/expand-permission-tree snapshot query))
              new-response (eacl/expand-permission-tree client query)
              old-root (:tree-root old-response)
              new-root (:tree-root new-response)
              [old-union old-exclusion]
              (get-in old-root [:intermediate :children])
              [_ new-exclusion]
              (get-in new-root [:intermediate :children])]
          (is (= :intersection (intermediate-operation old-root)))
          (is (= #{:union :exclusion}
                 (set (map intermediate-operation
                           [old-union old-exclusion]))))
          (is (= [:reader :banned] (child-relations old-exclusion))
              "exclusion children retain directed left/right order")
          (is (= [] (get-in old-exclusion
                            [:intermediate :children 1 :leaf :subjects])))
          (is (= [alice]
                 (get-in new-exclusion
                         [:intermediate :children 1 :leaf :subjects])))
          (is (string? (:expanded-at old-response)))
          (is (not-any? #{:subject->resources :direct-match?
                          :all-permission-nodes}
                        (map :operation @observed))
              "rendering never invokes authorization enumeration"))
        (finally
          (eacl/release! snapshot))))
    (let [limited (datascript/make-client
                   conn {:permission-tree-limits {:max-tree-nodes 2}})
          token (eacl/cancellation-token)]
      (is (= :eacl.permission-tree/limit-exceeded
             (:type (thrown-data
                     #(eacl/expand-permission-tree limited query)))))
      (eacl/cancel! token)
      (is (= :eacl.execution/cancelled
             (:type
              (thrown-data
               #(eacl/expand-permission-tree
                 client (assoc query :cancellation-token token)))))))))

(deftest limit-configuration-is-strict-and-portable
  (is (= (assoc permission-tree/default-limits :max-depth 3)
         (permission-tree/normalize-limits {:max-depth 3})))
  (doseq [limits [false
                  {:unknown 1}
                  {:max-depth 0}
                  {:max-depth -1}
                  {:max-depth 9007199254740992}]]
    (is (= :eacl/invalid-config
           (:type (thrown-data
                   #(permission-tree/normalize-limits limits)))))))

(deftest direct-empty-and-absent-resources
  (let [adapter
        (fake-adapter
         {:objects {"d1" 1 "alice" 10}
          :internal->external {10 "alice" 11 "bob"}
          :relations
          {[:document :viewer]
           [(relation 100 :document :viewer :user)]}
          :scans {[:document 1 100 :user] [10]}})]
    (is (= {:expanded-object (eacl/spice-object :document "d1")
            :expanded-relation :viewer
            :leaf {:subjects [(eacl/spice-object :user "alice")]}}
           (expand adapter (eacl/spice-object :document "d1") :viewer)))
    (is (= {:expanded-object (eacl/spice-object :document "missing")
            :expanded-relation :viewer
            :leaf {:subjects []}}
           (expand adapter
                   (eacl/spice-object :document "missing")
                   :viewer)))
    (is (= 9007199254740991
           (get-in
            (expand adapter
                    (eacl/spice-object :document 9007199254740991)
                    :viewer)
            [:expanded-object :id])))
    (is (= :eacl.permission-tree/unknown-root
           (:type
            (thrown-data
             #(expand adapter
                      (eacl/spice-object :document "d1")
                      :undefined)))))))

(deftest union-nesting-arrows-types-and-duplicate-paths
  (let [adapter
        (fake-adapter
         {:objects {"d1" 1}
          :internal->external {7 "same" 10 "alice"}
          :relations
          {[:document :viewer]
           [(relation 100 :document :viewer :user)]
           [:document :parent]
           [(relation 101 :document :parent :folder)
            (relation 102 :document :parent :team)]
           [:folder :reader]
           [(relation 200 :folder :reader :user)]
           [:team :reader]
           [(relation 300 :team :reader :user)]}
          :permissions
          {[:document :view]
           [(component 400 :document :view :self :relation :viewer)
            (component 401 :document :view :parent :relation :reader)
            (component 402 :document :view :self :relation :viewer)]}
          :scans
          {[:document 1 100 :user] [10]
           [:document 1 101 :folder] [7]
           [:document 1 102 :team] [7]
           [:folder 7 200 :user] []
           [:team 7 300 :user] []}})
        tree (expand adapter (eacl/spice-object :document "d1") :view)
        children (get-in tree [:intermediate :children])
        arrow (second children)]
    (is (= (eacl/spice-object :document "d1")
           (:expanded-object tree)))
    (is (= :view (:expanded-relation tree)))
    (is (= :union (get-in tree [:intermediate :operation])))
    (is (= (first children) (nth children 2)))
    (is (= :view (:expanded-relation arrow)))
    (is (= [(eacl/spice-object :folder "same")
            (eacl/spice-object :team "same")]
           (mapv :expanded-object
                 (get-in arrow [:intermediate :children]))))))

(deftest same-permission-boundaries-diamonds-and-cycles
  (let [base-relations
        {[:document :viewer]
         [(relation 100 :document :viewer :user)]}
        adapter
        (fake-adapter
         {:objects {"d1" 1}
          :relations base-relations
          :permissions
          {[:document :base]
           [(component 200 :document :base :self :relation :viewer)]
           [:document :diamond]
           [(component 201 :document :diamond :self :permission :base)
            (component 202 :document :diamond :self :permission :base)]}})
        tree (expand adapter (eacl/spice-object :document "d1") :diamond)
        children (get-in tree [:intermediate :children])]
    (is (= 2 (count children)))
    (is (= (first children) (second children)))
    (is (every? #(= :base (:expanded-relation %)) children)))
  (let [adapter
        (fake-adapter
         {:objects {"d1" 1}
          :permissions
          {[:document :a]
           [(component 1 :document :a :self :permission :b)]
           [:document :b]
           [(component 2 :document :b :self :permission :a)]}})]
    (is (= :eacl.permission-tree/cycle-detected
           (:type
            (thrown-data
             #(expand adapter
                      (eacl/spice-object :document "d1")
                      :a)))))))

(deftest all-limit-dimensions-fail-without-a-tree
  (let [adapter
        (fake-adapter
         {:objects {"d1" 1}
          :internal->external {10 "alice"}
          :relations
          {[:document :viewer]
           [(relation 100 :document :viewer :user)]}
          :permissions
          {[:document :view]
           [(component 200 :document :view :self :relation :viewer)]}
          :scans {[:document 1 100 :user] [10 11]}})
        cases
        [[:max-depth 1]
         [:max-schema-components 1]
         [:max-relationship-values 1]
         [:max-tree-nodes 1]
         [:max-leaf-subjects 1]]]
    (doseq [[dimension value] cases]
      (let [limits (assoc permission-tree/default-limits dimension value)
            data (thrown-data
                  #(expand adapter
                           (eacl/spice-object :document "d1")
                           :view
                           limits))]
        (is (= :eacl.permission-tree/limit-exceeded (:type data)))
        (is (not (contains? data :tree)))))))

(deftest codec-failure-is-redacted
  (let [adapter
        (fake-adapter
         {:objects {"d1" 1}
          :relations
          {[:document :viewer]
           [(relation 100 :document :viewer :user)]}
          :scans {[:document 1 100 :user] [987654321]}})
        data
        (thrown-data
         #(expand adapter (eacl/spice-object :document "d1") :viewer))]
    (is (= :eacl.permission-tree/codec-failure (:type data)))
    (is (not-any? #(= 987654321 %)
                  (tree-seq coll? seq data)))))

(deftest request-local-codec-memo-is-typed-and-deterministic
  (let [counts (atom {})
        adapter
        (fake-adapter
         {:objects {"d1" 1}
          :internal->external {10 "alice"}
          :codec-counts counts
          :relations
          {[:document :viewer]
           [(relation 100 :document :viewer :user)]}
          :permissions
          {[:document :view]
           [(component 200 :document :view :self :relation :viewer)
            (component 201 :document :view :self :relation :viewer)]}
          :scans {[:document 1 100 :user] [10]}})]
    (is (= 2
           (count
            (get-in
             (expand adapter (eacl/spice-object :document "d1") :view)
             [:intermediate :children]))))
    (is (= {10 1} @counts))))

(deftest relationship-scan-is-incremental
  (let [realized (atom 0)
        events (atom [])
        values (letfn [(items [value]
                        (lazy-seq
                         (swap! realized inc)
                         (cons value (items (inc value)))))]
                 (items 10))
        adapter
        (fake-adapter
         {:objects {"d1" 1}
          :internal->external {10 "a" 11 "b"}
          :relations
          {[:document :viewer]
           [(relation 100 :document :viewer :user)]}
          :scans {[:document 1 100 :user] values}})
        limits
        (assoc permission-tree/default-limits
               :max-relationship-values 1)]
    (is (= :eacl.permission-tree/limit-exceeded
           (:type
            (thrown-data
             #(binding [backend/*invoke-observer*
                        (fn [event]
                          (when (= :resource->subjects
                                   (:operation event))
                            (swap! events conj (:phase event))))]
                (expand adapter
                        (eacl/spice-object :document "d1")
                        :viewer
                        limits))))))
    (is (= 2 @realized))
    (is (= [:before :after :failed] @events))))

(deftest schema-definition-realization-is-incremental
  (let [realized (atom 0)
        definitions
        (letfn [(items [id]
                  (lazy-seq
                   (swap! realized inc)
                   (cons (relation id :document :viewer :user)
                         (items (inc id)))))]
          (items 100))
        adapter
        (fake-adapter
         {:objects {"d1" 1}
          :relations {[:document :viewer] definitions}})
        limits
        (assoc permission-tree/default-limits :max-schema-components 1)]
    (is (= :eacl.permission-tree/limit-exceeded
           (:type
            (thrown-data
             #(expand adapter
                      (eacl/spice-object :document "d1")
                      :viewer
                      limits)))))
    (is (= 2 @realized))))

(deftest over-depth-arrow-does-not-start-a-source-scan
  (let [realized (atom 0)
        values
        (lazy-seq
         (swap! realized inc)
         (list 10))
        adapter
        (fake-adapter
         {:objects {"d1" 1}
          :relations
          {[:document :parent]
           [(relation 100 :document :parent :folder)]
           [:folder :viewer]
           [(relation 200 :folder :viewer :user)]}
          :permissions
          {[:document :view]
           [(component 300 :document :view
                       :parent :relation :viewer)]}
          :scans {[:document 1 100 :folder] values}})
        limits
        (assoc permission-tree/default-limits :max-depth 1)]
    (is (= :eacl.permission-tree/limit-exceeded
           (:type
            (thrown-data
             #(expand adapter
                      (eacl/spice-object :document "d1")
                      :view
                      limits)))))
    (is (zero? @realized))))

(deftest over-node-budget-arrow-does-not-start-a-source-scan
  (let [realized (atom 0)
        values
        (lazy-seq
         (swap! realized inc)
         (list 10))
        adapter
        (fake-adapter
         {:objects {"d1" 1}
          :relations
          {[:document :parent]
           [(relation 100 :document :parent :folder)]
           [:folder :viewer]
           [(relation 200 :folder :viewer :user)]}
          :permissions
          {[:document :view]
           [(component 300 :document :view
                       :parent :relation :viewer)]}
          :scans {[:document 1 100 :folder] values}})
        limits
        (assoc permission-tree/default-limits :max-tree-nodes 1)]
    (is (= :eacl.permission-tree/limit-exceeded
           (:type
            (thrown-data
             #(expand adapter
                      (eacl/spice-object :document "d1")
                      :view
                      limits)))))
    (is (zero? @realized))))

(deftest deadline-after-one-running-realization-fails-closed
  (let [clock (atom 0)
        realized (atom 0)
        codec-counts (atom {})
        values
        (lazy-seq
         (swap! realized inc)
         ;; Model a synchronous realization that returns after the absolute
         ;; deadline. The implementation cannot hard-cancel it, but must not
         ;; render or schedule later work after it returns.
         (reset! clock 1000)
         (list 10))
        adapter
        (fake-adapter
         {:objects {"d1" 1}
          :internal->external {10 "alice"}
          :codec-counts codec-counts
          :relations
          {[:document :viewer]
           [(relation 100 :document :viewer :user)]}
          :scans {[:document 1 100 :user] values}})
        contract {:operation :expand-permission-tree
                  :configured-timeout-ms 1
                  :deadline-nanos 100}]
    (is (= :eacl.execution/deadline-exceeded
           (:type
            (thrown-data
             #(binding [execution/*monotonic-nanos*
                        (fn [] (swap! clock inc))]
                (permission-tree/expand
                 adapter
                 {:limits permission-tree/default-limits
                  :execution-contract contract}
                 (eacl/spice-object :document "d1")
                 :viewer))))))
    (is (= 1 @realized))
    (is (= {} @codec-counts))))

(deftest adapter-guard-errors-cannot-leak-scanned-identities
  (let [sensitive-a 987654321
        sensitive-b 123456789
        adapter
        (fake-adapter
         {:objects {"d1" 1}
          :runtime-guards? true
          :internal->external
          {sensitive-a "a" sensitive-b "b"}
          :relations
          {[:document :viewer]
           [(relation 100 :document :viewer :user)]}
          ;; Deliberately violates the strict ascending adapter contract.
          :scans {[:document 1 100 :user]
                  [sensitive-a sensitive-b]}})
        error
        (try
          (expand adapter (eacl/spice-object :document "d1") :viewer)
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo
                    :cljs cljs.core.ExceptionInfo) cause
            cause))
        data (ex-data error)]
    (is (= :eacl.permission-tree/adapter-contract-violation
           (:type data)))
    (is (nil? #?(:clj (.getCause error) :cljs (.-cause error))))
    (is (not-any? #{sensitive-a sensitive-b}
                  (tree-seq coll? seq data)))))

(deftest adapter-cannot-spoof-a-kernel-error
  (doseq [spoofed-type
          [:eacl.permission-tree/limit-exceeded
           :eacl.execution/deadline-exceeded]]
    (let [adapter
          (fake-adapter
           {:objects {"d1" 1}
            :relations
            {[:document :viewer]
             [(relation 100 :document :viewer :user)]}
            :scan-error
            (ex-info "secret-internal-id"
                     {:type spoofed-type
                      :internal-id 424242})})
          error
          (try
            (expand adapter (eacl/spice-object :document "d1") :viewer)
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo) cause
              cause))
          data (ex-data error)]
      (is (= :eacl.permission-tree/adapter-contract-violation
             (:type data)))
      (is (= :adapter-operation-failed (:reason data)))
      (is (nil? #?(:clj (.getCause error) :cljs (.-cause error))))
      (is (not-any? #{424242}
                    (tree-seq coll? seq data))))))

(deftest selected-token-errors-are-redacted
  (let [secret-id #?(:clj (Object.) :cljs (js-obj))
        data
        (thrown-data
         #(permission-tree/selected-basis-token
           {:snapshot-semantic-identity
            {:backend :test
             :source-id secret-id
             :branch nil
             :source-lifecycle "test-lifecycle"
             :basis-kind :ordinary
             :revision 1
             :exact-locator nil
             :backend-snapshot-id {:snapshot :one}}
            :format-options
            {:current-kid :test
             :keyring {:test (vec (range 32))}
             :token-ttl-seconds 60}}))]
    (is (= :eacl.permission-tree/adapter-contract-violation
           (:type data)))
    (is (= :adapter-operation-failed (:reason data)))
    (is (not-any? #{secret-id}
                  (tree-seq coll? seq data)))))

(deftest noncanonical-custom-external-ids-are-not-sorted-or-encoded
  (let [custom-id #?(:clj (Object.) :cljs (js-obj))
        adapter
        (fake-adapter
         {:objects {"d1" 1}
          :internal->external {10 custom-id}
          :relations
          {[:document :viewer]
           [(relation 100 :document :viewer :user)]}
          :scans {[:document 1 100 :user] [10]}})]
    (is (identical?
         custom-id
         (get-in
          (expand adapter (eacl/spice-object :document "d1") :viewer)
          [:leaf :subjects 0 :id])))))

(defn- reference-expand
  "Small independent evaluator used only by correspondence tests."
  [{:keys [objects relations permissions scans internal->external]}
   resource permission]
  (letfn [(descriptor [type internal-id]
            {:type type
             :internal-id internal-id
             :public (eacl/spice-object
                      type (get internal->external internal-id))})
          (relation-subjects [resource relation-name]
            (if (nil? (:internal-id resource))
              []
              (into
               []
               (mapcat
                (fn [{:keys [relation-id subject-type]}]
                  (map #(descriptor subject-type %)
                       (get scans
                            [(:type resource) (:internal-id resource)
                             relation-id subject-type]
                            []))))
               (get relations [(:type resource) relation-name] []))))
          (expand* [resource name active]
            (let [relation-definitions
                  (get relations [(:type resource) name] [])
                  permission-definitions
                  (get permissions [(:type resource) name] [])]
              (if (seq relation-definitions)
                {:expanded-object (:public resource)
                 :expanded-relation name
                 :leaf
                 {:subjects
                  (mapv :public
                        (relation-subjects resource name))}}
                (let [key [[(:type resource) (:internal-id resource)] name]]
                  (when (contains? active key)
                    (throw (ex-info "reference cycle" {:key key})))
                  {:expanded-object (:public resource)
                   :expanded-relation name
                   :intermediate
                   {:operation :union
                    :children
                    (mapv
                     (fn [{:keys [source-relation-name
                                  target-type target-name]}]
                       (if (= :self source-relation-name)
                         (expand* resource target-name (conj active key))
                         {:expanded-object (:public resource)
                          :expanded-relation name
                          :intermediate
                          {:operation :union
                           :children
                           (mapv
                            #(expand* % target-name (conj active key))
                            (relation-subjects
                             resource source-relation-name))}}))
                     permission-definitions)}}))))
          (root-descriptor []
            {:type (:type resource)
             :internal-id (get objects (:id resource))
             :public (eacl/spice-object (:type resource) (:id resource))})]
    (expand* (root-descriptor) permission #{})))

(defn- normalized-unordered-tree
  [tree]
  (if-let [leaf (:leaf tree)]
    (assoc tree :leaf
           (update leaf :subjects
                   #(vec (sort-by pr-str %))))
    (assoc-in
     tree
     [:intermediate :children]
     (->> (get-in tree [:intermediate :children])
          (map normalized-unordered-tree)
          (sort-by pr-str)
          vec))))

(defn- generated-fixture
  [seed]
  (let [users (vec (take (inc (mod seed 3)) [10 11 12]))
        components
        (cond->
         [(component 400 :document :view :self :relation :viewer)]
          (bit-test seed 0)
          (conj (component 401 :document :view
                           :self :permission :base))
          (bit-test seed 1)
          (conj (component 402 :document :view
                           :parent
                           (if (bit-test seed 4) :permission :relation)
                           (if (bit-test seed 4) :view :reader)))
          (bit-test seed 2)
          (conj (component 403 :document :view
                           :self :relation :viewer)))]
    {:objects {"d1" 1}
     :internal->external
     {2 "same" 10 "u10" 11 "u11" 12 "u12"}
     :relations
     {[:document :viewer]
      [(relation 100 :document :viewer :user)]
      [:document :parent]
      [(relation 101 :document :parent :folder)
       (relation 102 :document :parent :team)]
      [:folder :reader]
      [(relation 200 :folder :reader :user)]
      [:team :reader]
      [(relation 300 :team :reader :user)]}
     :permissions
     {[:document :base]
      [(component 350 :document :base :self :relation :viewer)]
      [:document :view] components
      [:folder :view]
      [(component 360 :folder :view :self :relation :reader)]
      [:team :view]
      [(component 361 :team :view :self :relation :reader)]}
     :scans
     {[:document 1 100 :user] users
      [:document 1 101 :folder] (if (bit-test seed 3) [2] [])
      [:document 1 102 :team] [2]
      [:folder 2 200 :user] (vec (reverse users))
      [:team 2 300 :user] users}}))

(deftest production-matches-independent-reference-and-permutations
  (doseq [seed (range 32)]
    (let [fixture (generated-fixture seed)
          resource (eacl/spice-object :document "d1")
          expected (reference-expand fixture resource :view)
          actual (expand (fake-adapter fixture) resource :view)
          permuted
          (update fixture :scans
                  (fn [scans]
                    (into {}
                          (map (fn [[key values]]
                                 [key (vec (reverse values))]))
                          scans)))
          permuted-actual
          (expand (fake-adapter permuted) resource :view)]
      (is (= expected actual) (str "reference seed=" seed))
      (is (every?
           (fn [node]
             (not= (contains? node :leaf)
                   (contains? node :intermediate)))
           (tree-seq
            #(contains? % :intermediate)
            #(get-in % [:intermediate :children])
            actual))
          (str "node oneof seed=" seed))
      (is (= (normalized-unordered-tree actual)
             (normalized-unordered-tree permuted-actual))
          (str "permutation seed=" seed)))))

(deftest pinned-spicedb-basic-any-arrow-golden
  (let [adapter
        (fake-adapter
         {:objects {"testdoc" 1}
          :internal->external
          {2 "testfolder1" 3 "testfolder2"
           10 "fred" 11 "tom" 12 "sarah"}
          :relations
          {[:document :folder]
           [(relation 100 :document :folder :folder)]
           [:folder :viewer]
           [(relation 200 :folder :viewer :user)]}
          :permissions
          {[:document :view]
           [(component 300 :document :view
                       :folder :relation :viewer)]}
          :scans
          {[:document 1 100 :folder] [2 3]
           [:folder 2 200 :user] [10 11]
           [:folder 3 200 :user] [12]}})]
    (is (= {:expanded-object
            (eacl/spice-object :document "testdoc")
            :expanded-relation :view
            :intermediate
            {:operation :union
             :children
             [{:expanded-object
               (eacl/spice-object :document "testdoc")
               :expanded-relation :view
               :intermediate
               {:operation :union
                :children
                [{:expanded-object
                  (eacl/spice-object :folder "testfolder1")
                  :expanded-relation :viewer
                  :leaf
                  {:subjects
                   [(eacl/spice-object :user "fred")
                    (eacl/spice-object :user "tom")]}}
                 {:expanded-object
                  (eacl/spice-object :folder "testfolder2")
                  :expanded-relation :viewer
                  :leaf
                  {:subjects
                   [(eacl/spice-object :user "sarah")]}}]}}]}}
           (expand
            adapter
            (eacl/spice-object :document "testdoc")
            :view)))))
