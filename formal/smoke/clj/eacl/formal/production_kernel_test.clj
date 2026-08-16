(ns eacl.formal.production-kernel-test
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]]
   [eacl.backend.v8 :as backend]
   [eacl.consistency :as consistency]
   [eacl.core :refer [spice-object]]
   [eacl.engine.v8 :as engine]
   [eacl.engine.relationships :as relationship-engine]
   [eacl.formal.production-kernel :as production]
   [eacl.lazy-merge-sort :as lazy-sort]
   [eacl.relay :as relay]
   [eacl.subproblem-cache :as subproblem]
   [eacl.verified-kernel :as verified]))

(def selection
  {:kernel production/generated-java-kernel})

(defn- cross-runtime-vectors
  []
  (edn/read-string
   (slurp "formal/cross-runtime/vectors.edn")))

(defn- expected-consistency-plan
  [{:keys [mode capability-supported?]}]
  (cond
    (not capability-supported?)
    (case mode
      :minimize-latency :unsupported-capability
      :at-exact-snapshot :exact-snapshot-unavailable
      :unsupported-head-barrier)
    :else
    (case mode
      :minimize-latency :select-current
      :fully-consistent :select-authoritative
      :at-least-as-fresh :authenticate-and-select-at-least
      :at-exact-snapshot :authenticate-and-select-exact)))

(defn- expected-consistency-validation
  [{:keys [kind selection-present? selected-adapter?
           same-source-scope? revision-satisfied?]}]
  (cond
    (not selection-present?)
    (if (= :exact kind)
      :exact-snapshot-unavailable
      :invalid-selected-adapter)
    (not selected-adapter?) :invalid-selected-adapter
    (not same-source-scope?) :incomparable-scope
    (and (#{:at-least :exact} kind)
         (not revision-satisfied?))
    :history-divergence
    :else :accept))

(deftest generated-java-acyclic-boundary-cross-runtime-vectors
  (doseq [{:keys [operation input expected]}
          (:production-acyclic-decisions
           (cross-runtime-vectors))]
    (is (= expected
           (verified/decide selection operation input))
        (pr-str [operation input]))))

(deftest generated-java-consistency-decisions-are-exhaustive
  (doseq [mode
          [:minimize-latency :fully-consistent
           :at-least-as-fresh :at-exact-snapshot]
          capability-supported? [false true]]
    (let [input {:mode mode
                 :capability-supported? capability-supported?}]
      (is (= (expected-consistency-plan input)
             (verified/decide
              selection
              :consistency-plan
              input)))))
  (doseq [kind [:current :authoritative :at-least :exact]
          selection-present? [false true]
          selected-adapter? [false true]
          :when (or selection-present? (not selected-adapter?))
          same-source-scope? [false true]
          revision-satisfied? [false true]]
    (let [input {:kind kind
                 :selection-present? selection-present?
                 :selected-adapter? selected-adapter?
                 :same-source-scope? same-source-scope?
                 :revision-satisfied? revision-satisfied?}]
      (is (= (expected-consistency-validation input)
             (verified/decide
              selection
              :consistency-validation
              input))))))

(defn- expected-consistency-work
  [path issue-response-token?]
  (let [response-scope (if issue-response-token? 1 0)
        common
        {:capability-observations 1
         :plan-decisions 1
         :authentication-attempts 0
         :backend-selection-calls 1
         :validation-decisions 1
         :revision-validation-calls 0
         :native-revision-reads 1
         :order-hint-reads 1
         :exact-locator-reads 1}]
    (case path
      :captured-current
      {:capability-observations 1
       :plan-decisions 1
       :authentication-attempts 0
       :backend-selection-calls 0
       :validation-decisions 0
       :source-scope-reads 0
       :revision-validation-calls 0
       :native-revision-reads 0
       :order-hint-reads 0
       :exact-locator-reads 0}
      (:selected-current :authoritative)
      (assoc common :source-scope-reads (+ 2 response-scope))
      :at-least
      (assoc common
             :authentication-attempts 1
             :source-scope-reads (+ 3 response-scope)
             :revision-validation-calls 1
             :native-revision-reads 2
             :order-hint-reads 2
             :exact-locator-reads 2)
      :exact
      (assoc common
             :authentication-attempts 1
             :source-scope-reads (+ 3 response-scope)
             :revision-validation-calls 1
             :native-revision-reads 2
             :order-hint-reads 2
             :exact-locator-reads 2))))

(deftest generated-java-consistency-work-is-dimensionally-exact
  (doseq [path
          [:captured-current :selected-current :authoritative
           :at-least :exact]
          issue-response-token? [false true]]
    (is (= (expected-consistency-work path issue-response-token?)
           (production/consistency-selection-work
            path issue-response-token?)))))

(defn- consistency-plan-adapter
  [mode capability-supported?]
  (backend/make-adapter
   {:id :generated-consistency-plan-test
    :capabilities
    {:consistency (if capability-supported? #{mode} #{})
     :snapshots #{:current}
     :source #{:stable-scope :source-lifecycle
               :native-revision :order-hint :exact-locator}
     :cursor #{}
     :transactions #{}
     :cache-proofs #{}
     :runtime #{:clj}}
    :operations
    (merge
     (into
      {}
      (map
       (fn [operation]
         [operation (fn [& _] nil)]))
      backend/required-snapshot-operations)
     {:snapshot-id (constantly {:revision 1})
      :source-scope
      (constantly {:source-id "generated-plan" :branch nil})
      :source-lifecycle (constantly "generated-plan-lifecycle")
      :native-revision
      (constantly {:revision 1 :exact-locator 1})
      :order-hint (constantly 1)
      :exact-locator (constantly 1)})}))

(defn- observed-generated-plan
  [source mode]
  (try
    [:planned
     (consistency/selection-plan
      source
      {:mode mode}
      {:decision-kernel selection})]
    (catch clojure.lang.ExceptionInfo error
      [:rejected (:type (ex-data error))])))

(defn- expected-production-plan
  [input]
  (let [decision (expected-consistency-plan input)]
    (if (contains?
         #{:select-current
           :select-authoritative
           :authenticate-and-select-at-least
           :authenticate-and-select-exact}
         decision)
      [:planned decision]
      [:rejected
       (case decision
         :unsupported-capability :eacl/unsupported-capability
         :exact-snapshot-unavailable
         :eacl.consistency/exact-snapshot-unavailable
         :eacl.consistency/unsupported-head-barrier)])))

(deftest generated-java-plan-refines-production-fact-extraction
  (doseq [mode
          [:minimize-latency :fully-consistent
           :at-least-as-fresh :at-exact-snapshot]
          capability-supported? [false true]]
    (let [input
          {:mode mode
           :capability-supported? capability-supported?}]
      (is (=
           (expected-production-plan input)
           (observed-generated-plan
            (consistency-plan-adapter mode capability-supported?)
            mode))))))

(defn- power-set
  [values]
  (reduce
   (fn [subsets value]
     (into subsets (map #(conj % value) subsets)))
   [[]]
   values))

(def routing-certificate-input
  {:node-count 2
   :path-descriptors
   [{:kind :self-permission :head 0 :target 1}
    {:kind :arrow-permission :head 1 :target 1}]
   :edges [{:head 0 :target 1}
           {:head 1 :target 1}]
   :certificate
   {:component-root [0 1]
    :forward-parent-edge [-1 -1]
    :reverse-parent-edge [-1 -1]
    :forward-depth [0 0]
    :reverse-depth [0 0]
    :component-rank [0 1]
    :multiple-member-witness [-1 -1]
    :self-loop-witness-edge [-1 1]
    :traversal [true true]
    :traversal-witness-edge [0 -1]}})

(def scc-routing-certificate-input
  {:node-count 2
   :path-descriptors
   [{:kind :self-permission :head 0 :target 1}
    {:kind :arrow-permission :head 1 :target 0}]
   :edges [{:head 0 :target 1}
           {:head 1 :target 0}]
   :certificate
   {:component-root [0 0]
    :forward-parent-edge [-1 0]
    :reverse-parent-edge [-1 1]
    :forward-depth [0 1]
    :reverse-depth [0 1]
    :component-rank [0 0]
    :multiple-member-witness [1 -1]
    :self-loop-witness-edge [-1 -1]
    :traversal [true true]
    :traversal-witness-edge [-1 -1]}})

(defn- chain-routing-certificate-input
  [node-count]
  (let [last-node (dec node-count)
        edges
        (conj
         (mapv
          (fn [node]
            {:head node :target (inc node)})
          (range last-node))
         {:head last-node :target last-node})]
    {:node-count node-count
     :path-descriptors
     (mapv
      (fn [{:keys [head target]}]
        {:kind :self-permission
         :head head
         :target target})
      edges)
     :edges edges
     :certificate
     {:component-root (vec (range node-count))
      :forward-parent-edge (vec (repeat node-count -1))
      :reverse-parent-edge (vec (repeat node-count -1))
      :forward-depth (vec (repeat node-count 0))
      :reverse-depth (vec (repeat node-count 0))
      :component-rank (vec (range node-count))
      :multiple-member-witness (vec (repeat node-count -1))
      :self-loop-witness-edge
      (assoc (vec (repeat node-count -1)) last-node last-node)
      :traversal (vec (repeat node-count true))
      :traversal-witness-edge
      (conj (vec (range last-node)) -1)}}))

(deftest generated-java-checks-linear-routing-certificates
  (is (= {:status :accepted
          :traversal [true true]
          :path-checks 2
          :node-checks 4
          :edge-checks 2}
         (verified/decide
          selection
          :recursive-routing-certificate
          routing-certificate-input)))
  (is (= :invalid-dependency-edge
         (:reason
          (verified/decide
           selection
           :recursive-routing-certificate
           (assoc-in
            routing-certificate-input
            [:certificate :traversal]
            [false true])))))
  (is (= :routing-path-edge-mismatch
         (:reason
          (verified/decide
           selection
           :recursive-routing-certificate
           (assoc-in
            routing-certificate-input
            [:path-descriptors 0]
            {:kind :relation :head 0})))))
  (is (= :invalid-routing-path
         (:reason
          (verified/decide
           selection
           :recursive-routing-certificate
           (assoc-in
            routing-certificate-input
            [:path-descriptors 0 :target]
            2)))))
  (is (= :accepted
         (:status
          (verified/decide
           selection
           :recursive-routing-certificate
           (assoc
            routing-certificate-input
            :path-descriptors
            [{:kind :relation :head 0}
             {:kind :arrow-relation :head 1}
             {:kind :self-permission :head 0 :target 1}
             {:kind :arrow-permission :head 1 :target 1}])))))
  (is (= :routing-path-edge-mismatch
         (:reason
          (verified/decide
           selection
           :recursive-routing-certificate
           (update
            routing-certificate-input
            :path-descriptors
            #(vec (reverse %)))))))
  (is (= :invalid-component-witness
         (:reason
          (verified/decide
           selection
           :recursive-routing-certificate
           (assoc-in
            routing-certificate-input
            [:certificate :traversal-witness-edge]
            [-1 -1])))))
  (is (= :accepted
         (:status
          (verified/decide
           selection
           :recursive-routing-certificate
           scc-routing-certificate-input))))
  (doseq [[label input expected-reason]
          [["split one SCC into two claimed components"
            (-> scc-routing-certificate-input
                (assoc-in [:certificate :component-root] [0 1])
                (assoc-in [:certificate :forward-parent-edge] [-1 -1])
                (assoc-in [:certificate :reverse-parent-edge] [-1 -1]))
            :invalid-dependency-edge]
           ["use a reverse edge as a forward parent"
            (assoc-in
             scc-routing-certificate-input
             [:certificate :forward-parent-edge 1]
             1)
            :invalid-component-witness]
           ["omit the multi-member SCC witness"
            (assoc-in
             scc-routing-certificate-input
             [:certificate :multiple-member-witness 0]
             -1)
            :invalid-component-witness]
           ["hide traversal through a recursive SCC"
            (assoc-in
             scc-routing-certificate-input
             [:certificate :traversal]
             [false false])
            :invalid-component-witness]]]
    (testing label
      (is (= expected-reason
             (:reason
              (verified/decide
               selection
               :recursive-routing-certificate
               input)))))))

(deftest generated-java-routing-certificate-scales-linearly
  (let [node-count 4096
        decision
        (verified/decide
         selection
         :recursive-routing-certificate
         (chain-routing-certificate-input node-count))]
    (is (= :accepted (:status decision)))
    (is (= node-count (:path-checks decision)))
    (is (= (* 2 node-count) (:node-checks decision)))
    (is (= node-count (:edge-checks decision)))
    (is (= node-count (count (:traversal decision))))
    (is (every? true? (:traversal decision)))))

(def authorization-input
  {:objects [{:type "user" :id "u1"}
             {:type "team" :id "t1"}
             {:type "folder" :id "f0"}
             {:type "folder" :id "f1"}]
   :schema
   {:relations
    [{:resource-type "folder" :relation "reader" :subject-type "user"}
     {:resource-type "folder" :relation "parent" :subject-type "folder"}
     {:resource-type "folder" :relation "team-reader" :subject-type "team"}]
    :permissions [{:resource-type "folder" :permission "read"}]
    :definitions
    [{:kind :direct-relation
      :resource-type "folder" :permission "read"
      :relation "reader" :subject-type "user"}
     {:kind :arrow-permission
      :resource-type "folder" :permission "read"
      :via-relation "parent" :target-permission "read"}
     {:kind :direct-relation
      :resource-type "folder" :permission "read"
      :relation "team-reader" :subject-type "team"}]}
   :relationships
   [{:resource {:type "folder" :id "f0"}
     :relation "reader" :subject {:type "user" :id "u1"}}
    {:resource {:type "folder" :id "f1"}
     :relation "parent" :subject {:type "folder" :id "f0"}}
    {:resource {:type "folder" :id "f1"}
     :relation "team-reader" :subject {:type "team" :id "t1"}}]
   :request
   {:operation :lookup-resources
    :subject {:type "user" :id "u1"}
    :permission "read"
    :resource-type "folder"}
   :limits {:max-derived-grants 1000
            :max-advanced-datoms 1000
            :max-queued-work 1000}})

(def indexed-plan-input
  (let [head {:resource-type "folder" :permission "read"}
        direct {:kind :relation :head head
                :relation-eid 1 :subject-type "user"}
        recursive {:kind :arrow-permission :head head
                   :via-relation-eid 2
                   :intermediate-type "folder"
                   :target-node head}]
    {:relations
     [{:resource-type "folder" :relation "reader" :subject-type "user"}
      {:resource-type "folder" :relation "parent" :subject-type "folder"}]
     :permissions [head]
     :definitions
     [{:kind :direct-relation
       :resource-type "folder" :permission "read"
       :relation "reader" :subject-type "user"}
      {:kind :arrow-permission
       :resource-type "folder" :permission "read"
       :via-relation "parent" :target-permission "read"}]
     :relation-bindings
     [{:eid 1
       :relation {:resource-type "folder"
                  :relation "reader" :subject-type "user"}}
      {:eid 2
       :relation {:resource-type "folder"
                  :relation "parent" :subject-type "folder"}}]
     :indexed-rules [direct recursive]}))

(def indexed-seed-input
  {:indexed-rules (:indexed-rules indexed-plan-input)
   :seed-rules [(first (:indexed-rules indexed-plan-input))]
   :subject-type "user"})

(defn- test-adapter
  []
  (backend/make-adapter
   {:id :formal-production-test
    :capabilities
    {:consistency #{:minimize-latency}
     :snapshots #{:current :exact}
     :source #{:stable-scope :source-lifecycle
               :native-revision :order-hint :exact-locator}
     :cursor #{:forward :backward}
     :transactions #{}
     :cache-proofs #{:ordered-generations}
     :runtime #{:clj}}
    :fingerprint {:adapter :formal-production-test}
    :identity-contract :formal-production-test/v1
    :operations
    (merge
     (into {}
           (map (fn [operation]
                  [operation (fn [& _] nil)]))
           backend/required-snapshot-operations)
     {:snapshot-id (constantly {:basis 1})
      :source-scope
      (constantly {:source-id "source" :branch nil})
      :source-lifecycle (constantly "formal-production-lifecycle")
      :native-revision
      (constantly {:revision 1 :exact-locator 1})
      :order-hint (constantly 1)
      :exact-locator (constantly 1)
      :select-exact (fn [& _] nil)
      :object-id->internal
      #(case % "document-1" 1 "document-2" 2 nil)
      :internal-id->object
      #(case % 1 "document-1" 2 "document-2" nil)
      :proof-frame
      (fn [relation-ids]
        {:schema-stamp 1
         :relation-stamps
         (mapv (fn [relation-id] [relation-id 1]) relation-ids)})})}))

(defn- recursive-plan-test-adapter
  []
  (let [relations
        {[:folder :reader]
         [{:relation-id 1
           :resource-type :folder
           :relation-name :reader
           :subject-type :user}]
         [:folder :parent]
         [{:relation-id 2
           :resource-type :folder
           :relation-name :parent
           :subject-type :folder}]
         [:folder :team]
         [{:relation-id 3
           :resource-type :folder
           :relation-name :team
           :subject-type :team}]
         [:team :member]
         [{:relation-id 4
           :resource-type :team
           :relation-name :member
           :subject-type :user}]}
        permissions
        [{:permission-id 11
          :resource-type :folder
          :permission-name :read
          :source-relation-name :self
          :target-type :relation
          :target-name :reader}
         {:permission-id 12
          :resource-type :folder
          :permission-name :read
          :source-relation-name :self
          :target-type :permission
          :target-name :read}
         {:permission-id 13
          :resource-type :folder
          :permission-name :read
          :source-relation-name :parent
          :target-type :permission
          :target-name :read}
         {:permission-id 14
          :resource-type :folder
          :permission-name :read
          :source-relation-name :team
          :target-type :relation
          :target-name :member}]]
    (backend/make-adapter
     {:id :formal-recursive-plan-test
      :capabilities
      {:consistency #{:minimize-latency}
       :snapshots #{:current}
       :source #{:stable-scope :source-lifecycle
                 :native-revision :order-hint :exact-locator}
       :cursor #{:forward :backward}
       :transactions #{}
       :cache-proofs #{:ordered-generations}
       :runtime #{:clj}}
      :operations
      (merge
       (into {}
             (map
              (fn [operation]
                [operation (fn [& _] nil)]))
             backend/required-snapshot-operations)
       {:snapshot-id
        (constantly {:database-id :formal :basis-t 1})
        :source-scope
        (constantly {:source-id :formal :branch nil})
        :source-lifecycle
        (constantly "formal-recursive-plan-lifecycle")
        :native-revision
        (constantly {:revision 1 :exact-locator 1})
        :order-hint (constantly 1)
        :exact-locator (constantly 1)
        :proof-frame
        (fn [relation-ids]
          {:schema-stamp 1
           :relation-stamps
           (mapv (fn [relation-id] [relation-id 1]) relation-ids)})
        :relation-defs
        (fn [resource-type relation-name]
          (get relations [resource-type relation-name] []))
        :permission-defs
        (fn [resource-type permission-name]
          (if (= [:folder :read]
                 [resource-type permission-name])
            permissions
            []))
        :all-permission-nodes
        (constantly #{[:folder :read]})})})))

(defn- recursive-traversal-test-adapter
  []
  (let [base (recursive-plan-test-adapter)
        forward
        {[:user 1 1 :folder] [10]
         [:folder 10 2 :folder] [20]
         [:team 1 3 :folder] [30]
         [:user 1 4 :team] [1]}
        reverse
        {[:folder 10 1 :user] [1]
         [:folder 20 2 :folder] [10]
         [:folder 30 3 :team] [1]
         [:team 1 4 :user] [1]}
        after-bound
        (fn [values {:keys [bound-eid inclusive-bound?]}]
          (if (some? bound-eid)
            (drop-while
             #(if inclusive-bound?
                (< % bound-eid)
                (<= % bound-eid))
             values)
            values))]
    (assoc
     base
     ::backend/operations
     (merge
      (::backend/operations base)
      {:object-id->internal identity
       :internal-id->object identity
       :relation-populated?
       (fn [_subject-type relation-id _resource-type]
         (= 2 relation-id))
       :subject->resources
       (fn [subject-type subject-eid relation-eid resource-type opts]
         (after-bound
          (get
           forward
           [subject-type subject-eid relation-eid resource-type]
           [])
          opts))
       :resource->subjects
       (fn [resource-type resource-eid relation-eid subject-type opts]
         (after-bound
          (get
           reverse
           [resource-type resource-eid relation-eid subject-type]
           [])
          opts))}))))

(deftest generated-java-drives-production-recursive-pages
  (let [adapter (recursive-traversal-test-adapter)
        query
        {:subject {:type :user :id 1}
         :permission :read
         :resource/type :folder
         :first 2}
        run-forward
        (fn [schema-cache query']
          (binding [engine/*schema-cache* schema-cache
                    subproblem/*decision-kernel* selection]
            (engine/lookup-resources adapter query')))
        generated-cache
        (engine/make-schema-cache adapter 1)
        generated-first
        (run-forward generated-cache query)
        after (get-in generated-first [:page-info :end-cursor])
        generated-second
        (run-forward generated-cache (assoc query :after after))
        reverse-query
        {:resource {:type :folder :id 20}
         :permission :read
         :subject/type :user
         :first 10}
        run-reverse
        (fn [schema-cache]
          (binding [engine/*schema-cache* schema-cache
                    subproblem/*decision-kernel* selection]
            (engine/lookup-subjects adapter reverse-query)))
        generated-reverse
        (run-reverse (engine/make-schema-cache adapter 1))
        run-operation
        (fn [operation]
          (binding [engine/*schema-cache*
                    (engine/make-schema-cache adapter 1)
                    subproblem/*decision-kernel* selection]
            (operation)))
        can-operation
        #(engine/can?
          adapter
          {:type :user :id 1}
          :read
          {:type :folder :id 20})
        count-query
        {:subject {:type :user :id 1}
         :permission :read
         :resource/type :folder}
        limited-count-operation
        #(engine/count-resources
          adapter (assoc count-query :count-limit 2))
        all-count-operation
        #(engine/count-resources adapter count-query)
        reverse-count-operation
        #(engine/count-subjects
          adapter
          {:resource {:type :folder :id 20}
           :permission :read
           :subject/type :user})
        {:keys [first-page continuation-page]}
        (:production-recursive-pages (cross-runtime-vectors))]
    (is (= first-page
           (mapv :id (:data generated-first))))
    (is (true? (get-in generated-first
                       [:page-info :has-next-page?])))
    (is (= continuation-page
           (mapv :id (:data generated-second))))
    (is (= [{:type :user :id 1}]
           (mapv
            #(select-keys % [:type :id])
            (:data generated-reverse))))
    (is (true? (run-operation can-operation)))
    (is (= (run-operation limited-count-operation)
           {:count 2 :limit 2 :truncated? true}))
    (is (= (run-operation all-count-operation)
           {:count 3 :limit -1}))
    (is (= (run-operation reverse-count-operation)
           {:count 1 :limit -1}))))

(deftest generated-java-production-decision-boundary
  (testing "page normalization and exact window"
    (is (= {:status :valid
            :direction :asc
            :size 2
            :start 1
            :end 3
            :has-next? true
            :has-previous? true}
           (verified/decide
            selection
            :relationship-page
            {:length 4
             :request {:first 2
                       :last :absent
                       :after 0
                       :before :absent}
             :default-size 1000
             :maximum-size 10000}))))
  (testing "keyset page lookahead"
    (is (= {:take-count 20
            :reverse? false
            :has-next? true
            :has-previous? false}
           (verified/decide
            selection
            :relationship-keyset-page
            {:direction :asc
             :size 20
             :bound? false
             :realized-count 21}))))
  (testing "exact cursor proof mismatch is fail-closed"
    (is (= :snapshot-unavailable
           (verified/decide
            selection
            :cursor-continuation
            {:authenticated? true
             :scope-matches? true
             :expired? false
             :source "source"
             :cursor-source "source"
             :current-proof "new"
             :cursor-proof "old"
             :cursor-graph 0
             :exact nil}))))
  (testing "changed cursor proof requires exact fallback"
    (is (= :snapshot-unavailable
           (verified/decide
            selection
            :cursor-continuation
            {:authenticated? true
             :scope-matches? true
             :expired? false
             :source "source"
             :cursor-source "source"
             :current-proof "new"
             :cursor-proof "old"
             :cursor-graph 0
             :exact nil}))))
  )

(deftest generated-java-subproblem-cache-decisions
  (is (= :use-completed-value
         (verified/decide
          selection
          :subproblem-cache-decision
          {:decision :lookup
           :candidate :complete})))
  (is (= :skip-publication
         (verified/decide
          selection
           :subproblem-cache-decision
          {:decision :admission
           :candidate-present? false
           :attempted-publications 8
           :maximum-attempts 8})))
  (is (= :drop-publication
         (verified/decide
          selection
          :subproblem-cache-decision
          {:decision :publication
           :ticket-current? true
           :complete? true
           :valid? true
           :weight 1025
           :budget 1024}))))

(deftest generated-java-current-cache-decisions
  (doseq [[input expected]
          [[{:stage :eligibility :available? false}
            :bypass-current-cache]
           [{:stage :generation :available? true}
            :probe-exact-entry]
           [{:stage :exact-entry :available? false}
            :probe-managed-entry]
           [{:stage :snapshot-exact-entry :available? true}
            :use-snapshot-exact-entry]
           [{:stage :snapshot-exact-entry :available? false}
            :compute-snapshot-exact-value]
           [{:stage :managed-entry :available? true}
            :use-managed-entry]]]
    (is (= expected
           (verified/decide
            selection
            :current-cache-decision
            input)))))

(deftest generated-java-ordered-merge-step-decisions
  (doseq [[input expected]
          [[{:direction :asc :left-head nil :right-head nil}
            :left-exhausted]
           [{:direction :asc :left-head 1 :right-head nil}
            :right-exhausted]
           [{:direction :asc :left-head 1 :right-head 2}
            :take-left]
           [{:direction :asc :left-head 2 :right-head 1}
            :take-right]
           [{:direction :asc :left-head 1 :right-head 1}
            :take-both]
           [{:direction :desc :left-head 2 :right-head 1}
            :take-left]
           [{:direction :desc :left-head 1 :right-head 2}
            :take-right]]]
    (is (= expected
           (verified/decide
            selection
            :ordered-merge-step
            input))))
  (is (= :eacl.verification/invalid-boundary
         (try
           (verified/decide
            selection
            :ordered-merge-step
            {:direction :asc :left-head -1 :right-head 2})
           nil
           (catch clojure.lang.ExceptionInfo error
             (:type (ex-data error))))))
  (is (= {:values [1 2 3 4 5]
          :left-consumed 3
          :right-consumed 2}
         (verified/decide
          selection
          :ordered-merge-chunk
          {:direction :asc
           :left [1 3 5]
           :right [2 4 6]})))
  (is (= {:values [6 5 4 3 2]
          :left-consumed 3
          :right-consumed 2}
         (verified/decide
          selection
          :ordered-merge-chunk
          {:direction :desc
           :left [6 4 2]
           :right [5 3 1]})))
  (is (= :eacl.verification/invalid-boundary
         (try
           (verified/decide
            selection
            :ordered-merge-chunk
            {:direction :asc
             :left [2 1]
             :right [3 4]})
           nil
           (catch clojure.lang.ExceptionInfo error
             (:type (ex-data error)))))))

(deftest production-jvm-two-stream-merge-refines-exact-source-model
  (let [subsets
        (power-set [0 1 2 3 17 9007199254740991])]
    (doseq [ascending-left subsets
            ascending-right subsets
            direction [:asc :desc]]
      (let [[left right]
            (if (= :asc direction)
              [ascending-left ascending-right]
              [(vec (reverse ascending-left))
               (vec (reverse ascending-right))])
            actual
            (vec
             ((case direction
                :asc lazy-sort/lazy-fold2-merge-dedupe-sorted-by
                :desc lazy-sort/lazy-fold2-merge-dedupe-sorted-by-desc)
              identity
              [left right]))
            modeled
            (production/production-ordered-merge
             {:direction direction
              :left left
              :right right})]
        (is (= modeled actual)
            (str
             "left=" left
             " right=" right
             " direction=" direction))))))

(deftest generated-java-indexed-plan-certification-boundary
  (let [decide
        (fn [input]
          (verified/decide
           selection
           :indexed-plan-certification
           input))
        direct (first (:indexed-rules indexed-plan-input))]
    (is (= {:status :certified}
           (decide indexed-plan-input)))
    (is (= {:status :rejected
            :reason :compiled-rule-mismatch}
           (decide
            (update indexed-plan-input
                    :indexed-rules
                    pop))))
    (is (= {:status :rejected
            :reason :duplicate-indexed-rule}
           (decide
            (update indexed-plan-input
                    :indexed-rules
                    conj direct))))
    (is (= :eacl.verification/invalid-boundary
           (try
             (decide (assoc indexed-plan-input :unknown true))
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))

(deftest generated-java-indexed-seed-certification-boundary
  (let [decide
        (fn [input]
          (verified/decide
           selection
           :indexed-seed-certification
           input))
        direct (first (:indexed-rules indexed-seed-input))]
    (is (= {:status :certified}
           (decide indexed-seed-input)))
    (is (= {:status :rejected
            :reason :seed-bucket-mismatch}
           (decide
            (assoc indexed-seed-input :seed-rules []))))
    (is (= {:status :rejected
            :reason :duplicate-seed-rule}
           (decide
            (update indexed-seed-input
                    :seed-rules
                    conj direct))))))

(deftest generated-java-indexed-scan-response-boundary
  (let [base
        {:command
         {:request-scope 31
          :request-id 7
          :projection
          {:kind :subject->resources
           :subject-type "user"
           :subject-eid 1
           :relation-eid 2
           :resource-type "document"
           :bound-eid 10}
          :chunk-size 3}
         :response
         {:request-scope 31
          :request-id 7
          :values [11 13 18]
          :terminal? false
          :fetched-values 4}}
        decide
        (fn [input]
          (verified/decide
           selection
           :indexed-scan-response
           input))]
    (is (= {:status :accepted
            :values [11 13 18]
            :terminal? false
            :fetched-values 4}
           (decide base)))
    (doseq [[reason response]
            [[:mismatched-request
              {:request-scope 31
               :request-id 8
               :values [11 13 18]
               :terminal? false
               :fetched-values 4}]
             [:mismatched-request-scope
              {:request-scope 32
               :request-id 7
               :values [11 13 18]
               :terminal? false
               :fetched-values 4}]
             [:oversized-chunk
              {:request-scope 31
               :request-id 7
               :values [11 13 18 21]
               :terminal? true
               :fetched-values 4}]
             [:non-progressing-response
              {:request-scope 31
               :request-id 7
               :values []
               :terminal? false
               :fetched-values 1}]
             [:invalid-eid
              {:request-scope 31
               :request-id 7
               :values [-1]
               :terminal? true
               :fetched-values 1}]
             [:out-of-order
              {:request-scope 31
               :request-id 7
               :values [11 11]
               :terminal? true
               :fetched-values 2}]
             [:bound-violation
              {:request-scope 31
               :request-id 7
               :values [10]
               :terminal? true
               :fetched-values 1}]
             [:invalid-fetched-count
              {:request-scope 31
               :request-id 7
               :values [11 13 18]
               :terminal? false
               :fetched-values 3}]]]
      (is (= {:status :rejected :reason reason}
             (decide (assoc base :response response)))))))

(def indexed-direct-rule
  {:kind :relation
   :head {:resource-type "folder" :permission "read"}
   :relation-eid 1
   :subject-type "user"})

(def indexed-limits
  {:max-derived-grants 100
   :max-advanced-datoms 100
   :max-queued-work 100})

(defn- empty-scan-response
  [command]
  {:request-scope (:request-scope command)
   :request-id (:request-id command)
   :values []
   :terminal? true
   :fetched-values 0})

(defn- first-page-scan-outcome
  [selection direction page-size]
  (let [stream-count 4
        rules
        (mapv
         (fn [relation-eid]
           (assoc indexed-direct-rule :relation-eid relation-eid))
         (range 1 (inc stream-count)))
        compiled-plan
        (verified/compile-indexed-plan
         selection
         {:indexed-rules rules
          :seed-rules-by-subject-type {"user" rules}})
        initialization
        (merge
         {:compiled-plan compiled-plan
          :request-scope 72
          :subject-type "user"
          :root-node {:resource-type "folder" :permission "read"}
          :result-type (if (= :forward direction) "folder" "user")
          :render {:kind :page :size page-size :bound nil}
          :chunk-size 2
          :limits indexed-limits}
         (case direction
           :forward {:subject-eid 7}
           :reverse {:root-resource-eid 10}))
        initialized
        (verified/initialize-indexed
         selection direction initialization)]
    (verified/drive-indexed
     selection direction (:state initialized) indexed-limits 256)))

(defn- batched-forward-crossing-trace
  ([selection stream-count]
   (batched-forward-crossing-trace selection stream-count 256))
  ([selection stream-count fuel]
   (let [limits (assoc indexed-limits :max-queued-work (+ stream-count 64))
         rules
         (mapv
          (fn [relation-eid]
            (assoc indexed-direct-rule :relation-eid relation-eid))
          (range 1 (inc stream-count)))
         compiled-plan
         (verified/compile-indexed-plan
          selection
          {:indexed-rules rules
           :seed-rules-by-subject-type {"user" rules}})
         initialized
         (verified/initialize-indexed
          selection
          :forward
          {:compiled-plan compiled-plan
           :request-scope 73
           :subject-type "user"
           :subject-eid 7
           :root-node {:resource-type "folder" :permission "read"}
           :result-type "folder"
           :render {:kind :all-count}
           :chunk-size 2
           :limits limits})]
     (loop [state (:state initialized)
            crossings 0
            waves []]
       (when (> crossings (+ (* 4 stream-count) 16))
         (throw
          (ex-info "Forward fuel-cut trace did not make progress."
                   {:stream-count stream-count
                    :fuel fuel
                    :crossings crossings})))
       (let [driven
             (verified/drive-indexed
              selection :forward state limits fuel)]
         (case (:status driven)
           :need-scans
           (let [commands (:commands driven)
                 resumed
                 (verified/resume-indexed
                  selection :forward (:state driven)
                  (mapv empty-scan-response commands)
                  limits)]
             (recur (:state resumed)
                    (+ crossings 2)
                    (conj waves commands)))

           :yielded
           (recur (:state driven) (inc crossings) waves)

           :complete
           {:crossings (inc crossings)
            :waves waves
            :result
            (verified/read-indexed-result
             selection :forward (:state driven))}))))))

(defn- batched-reverse-crossing-trace
  [selection stream-count fuel]
  (let [limits (assoc indexed-limits :max-queued-work (+ stream-count 64))
        rules
        (mapv
         (fn [relation-eid]
           (assoc indexed-direct-rule :relation-eid relation-eid))
         (range 1 (inc stream-count)))
        compiled-plan
        (verified/compile-indexed-plan
         selection
         {:indexed-rules rules
          :seed-rules-by-subject-type {"user" rules}})
        initialized
        (verified/initialize-indexed
         selection
         :reverse
         {:compiled-plan compiled-plan
          :request-scope 74
          :subject-type "user"
          :root-node {:resource-type "folder" :permission "read"}
          :root-resource-eid 10
          :result-type "user"
          :render {:kind :all-count}
          :chunk-size 2
          :limits limits})]
    (loop [state (:state initialized)
           crossings 0
           waves []]
      (when (> crossings (+ (* 4 stream-count) 16))
        (throw
         (ex-info "Reverse fuel-cut trace did not make progress."
                  {:stream-count stream-count
                   :fuel fuel
                   :crossings crossings})))
      (let [driven
            (verified/drive-indexed
             selection :reverse state limits fuel)]
        (case (:status driven)
          :need-scans
          (let [commands (:commands driven)
                resumed
                (verified/resume-indexed
                 selection :reverse (:state driven)
                 (mapv empty-scan-response commands)
                 limits)]
            (recur (:state resumed)
                   (+ crossings 2)
                   (conj waves commands)))

          :yielded
          (recur (:state driven) (inc crossings) waves)

          :complete
          {:crossings (inc crossings)
           :waves waves
           :result
           (verified/read-indexed-result
            selection :reverse (:state driven))})))))

(deftest generated-java-batches-independent-scan-waves
  (let [stream-count 128
        batch-size 64
        {:keys [crossings waves result]}
        (batched-forward-crossing-trace selection stream-count)]
    (is (= [64 64] (mapv count waves)))
    (is (= (vec (range 1 (inc stream-count)))
           (mapv #(get-in % [:projection :relation-eid])
                 (mapcat identity waves)))
        "ordered response folding preserves deterministic command emission")
    (is (<= crossings
            (inc (* 2 (quot (+ stream-count (dec batch-size))
                            batch-size))))
        "crossings <= 2*ceil(streams/batch)+1")
    (is (= {:status :count :count 0 :truncated? false}
           (select-keys result [:status :count :truncated?])))))

(deftest generated-java-page-scan-policy-is-render-owned
  (doseq [direction [:forward :reverse]
          page-size [1 2 100]]
    (let [outcome
          (first-page-scan-outcome selection direction page-size)]
      (is (= :need-scan (:status outcome))
          (str (name direction) " page " page-size))
      (is (map? (:command outcome))
          "the generated page driver publishes exactly one command")
      (is (nil? (:commands outcome))
          "no host-selected speculative page wave is exposed"))))

(deftest generated-java-publishes-fuel-cut-scan-waves
  (doseq [[direction trace expected-wave-sizes]
          [[:forward batched-forward-crossing-trace [10 8]]
           [:reverse batched-reverse-crossing-trace [9 9]]]]
    (let [stream-count 18
          fuel 10
          {:keys [waves result]} (trace selection stream-count fuel)]
      (is (= expected-wave-sizes (mapv count waves)) (name direction))
      (is (= (vec (range 1 (inc stream-count)))
             (mapv #(get-in % [:projection :relation-eid])
                   (mapcat identity waves)))
          (str (name direction) " fuel cuts preserve command order and progress"))
      (is (= {:status :count :count 0 :truncated? false}
             (select-keys result [:status :count :truncated?]))
          (name direction)))))

(deftest generated-java-all-count-retains-no-rendered-results
  (let [compiled-plan
        (verified/compile-indexed-plan
         selection
         {:indexed-rules [indexed-direct-rule]
          :seed-rules-by-subject-type
          {"user" [indexed-direct-rule]}})
        initialized
        (verified/initialize-indexed
         selection
         :forward
         {:compiled-plan compiled-plan
          :request-scope 50
          :subject-type "user"
          :subject-eid 7
          :root-node
          {:resource-type "folder" :permission "read"}
          :result-type "folder"
          :render {:kind :all-count}
          :chunk-size 2
          :limits indexed-limits})
        drive
        (verified/drive-indexed
         selection :forward (:state initialized)
         indexed-limits 100)
        command (:command drive)
        resumed
        (verified/resume-indexed
         selection :forward (:state drive)
         {:request-scope (:request-scope command)
          :request-id 0
          :values [10 20]
          :terminal? true
          :fetched-values 2}
         indexed-limits)
        complete
        (verified/drive-indexed
         selection :forward (:state resumed)
         indexed-limits 100)
        result
        (verified/read-indexed-result
         selection :forward (:state complete))]
    (is (= {:status :count
            :count 2
            :truncated? false}
           (select-keys result [:status :count :truncated?])))
    ;; Two grants remain in the traversal seen-set and two emitted EIDs remain
    ;; in the traversal deduplication set. The all-count renderer itself keeps
    ;; only its scalar ordinal: neither emitted nor delivered result sequences.
    (is (= 4 (:retained-logical-units result)))))

(deftest generated-java-owns-opaque-indexed-traversal-state
  (let [compiled-plan
        (verified/compile-indexed-plan
         selection
         {:indexed-rules [indexed-direct-rule]
          :seed-rules-by-subject-type
          {"user" [indexed-direct-rule]}})
        forward-init
        (verified/initialize-indexed
         selection
         :forward
         {:compiled-plan compiled-plan
          :request-scope 51
          :subject-type "user"
          :subject-eid 7
          :root-node
          {:resource-type "folder" :permission "read"}
          :result-type "folder"
          :render {:kind :page :size 2 :bound nil}
          :chunk-size 2
          :limits indexed-limits})
        forward-drive
        (verified/drive-indexed
         selection :forward (:state forward-init)
         indexed-limits 100)
        command (:command forward-drive)
        malformed
        (verified/resume-indexed
         selection :forward (:state forward-drive)
         {:request-scope (:request-scope command)
          :request-id 0
          :values [20 10]
          :terminal? true
          :fetched-values 2}
         indexed-limits)
        forward-resume
        (verified/resume-indexed
         selection :forward (:state forward-drive)
         {:request-scope (:request-scope command)
          :request-id 0
          :values [10 20]
          :terminal? true
          :fetched-values 2}
         indexed-limits)
        forward-complete
        (verified/drive-indexed
         selection :forward (:state forward-resume)
         indexed-limits 100)
        forward-result
        (verified/read-indexed-result
         selection :forward (:state forward-complete))
        reverse-init
        (verified/initialize-indexed
         selection
         :reverse
         {:compiled-plan compiled-plan
          :request-scope 52
          :subject-type "user"
          :root-node
          {:resource-type "folder" :permission "read"}
          :root-resource-eid 10
          :result-type "user"
          :render {:kind :page :size 1 :bound nil}
          :chunk-size 2
          :limits indexed-limits})
        reverse-drive
        (verified/drive-indexed
         selection :reverse (:state reverse-init)
         indexed-limits 100)
        reverse-command (:command reverse-drive)
        reverse-resume
        (verified/resume-indexed
         selection :reverse (:state reverse-drive)
         {:request-scope (:request-scope reverse-command)
          :request-id 0
          :values [7]
          :terminal? true
          :fetched-values 1}
         indexed-limits)
        reverse-complete
        (verified/drive-indexed
         selection :reverse (:state reverse-resume)
         indexed-limits 100)
        reverse-result
        (verified/read-indexed-result
         selection :reverse (:state reverse-complete))]
    (is (= :initialized (:status forward-init)))
    (is (= {:status :need-scan
            :command
            {:request-scope 51
             :request-id 0
             :projection
             {:kind :subject->resources
              :subject-type "user"
              :subject-eid 7
              :relation-eid 1
              :resource-type "folder"
              :bound-eid nil}
             :chunk-size 2}}
           (dissoc forward-drive :state)))
    (is (= {:status :scan-rejected :reason :out-of-order}
           malformed))
    (is (= :complete (:status forward-complete)))
    (is (= {:status :page
            :items [10 20]
            :start-ordinal 0
            :has-next? false
            :has-previous? false}
           (select-keys
            forward-result
            [:status :items :start-ordinal
             :has-next? :has-previous?])))
    (is (= {:backend-commands 1
            :adapter-fetched-values 2
            :engine-consumed-values 2
            :unique-grants 2
            :emitted-results 2}
           (select-keys
            (:counters forward-result)
            [:backend-commands :adapter-fetched-values
             :engine-consumed-values :unique-grants
             :emitted-results])))
    (is (pos? (:retained-logical-units forward-result)))
    (is (= :resource->subjects
           (get-in reverse-drive [:command :projection :kind])))
    (is (= {:status :page
            :items [7]
            :start-ordinal 0
            :has-next? false
            :has-previous? false}
           (select-keys
            reverse-result
            [:status :items :start-ordinal
             :has-next? :has-previous?])))))

(deftest generated-java-continues-pages-from-verified-lookahead
  (let [compiled-plan
        (verified/compile-indexed-plan
         selection
         {:indexed-rules [indexed-direct-rule]
          :seed-rules-by-subject-type
          {"user" [indexed-direct-rule]}})
        initialized
        (verified/initialize-indexed
         selection
         :forward
         {:compiled-plan compiled-plan
          :request-scope 71
          :subject-type "user"
          :subject-eid 7
          :root-node
          {:resource-type "folder" :permission "read"}
          :result-type "folder"
          :render {:kind :page :size 1 :bound nil}
          :chunk-size 2
          :limits indexed-limits})
        need-first-scan
        (verified/drive-indexed
         selection :forward (:state initialized) indexed-limits 100)
        first-command (:command need-first-scan)
        resumed
        (verified/resume-indexed
         selection :forward (:state need-first-scan)
         {:request-scope (:request-scope first-command)
          :request-id (:request-id first-command)
          :values [10 20]
          :terminal? false
          :fetched-values 3}
         indexed-limits)
        first-complete
        (verified/drive-indexed
         selection :forward (:state resumed) indexed-limits 100)
        first-result
        (verified/read-indexed-result
         selection :forward (:state first-complete))
        rejected
        (verified/continue-indexed-page
         selection :forward (:state first-complete)
         {:size 1 :bound {:ordinal 0 :eid 11}})
        continued
        (verified/continue-indexed-page
         selection :forward (:state first-complete)
         {:size 1 :bound {:ordinal 0 :eid 10}})
        need-second-scan
        (verified/drive-indexed
         selection :forward (:state continued) indexed-limits 100)
        second-command (:command need-second-scan)
        second-resume
        (verified/resume-indexed
         selection :forward (:state need-second-scan)
         {:request-scope (:request-scope second-command)
          :request-id (:request-id second-command)
          :values [30]
          :terminal? true
          :fetched-values 1}
         indexed-limits)
        second-complete
        (verified/drive-indexed
         selection :forward (:state second-resume) indexed-limits 100)
        second-result
        (verified/read-indexed-result
         selection :forward (:state second-complete))]
    (is (= {:status :page
            :items [10]
            :start-ordinal 0
            :has-next? true
            :has-previous? false}
           (select-keys
            first-result
            [:status :items :start-ordinal
             :has-next? :has-previous?])))
    (is (= {:status :rejected :reason :boundary-mismatch}
           rejected))
    (is (= :continued (:status continued)))
    (is (= 20
           (get-in need-second-scan
                   [:command :projection :bound-eid]))
        "the continued state scans strictly after retained lookahead")
    (is (= {:status :page
            :items [20]
            :start-ordinal 1
            :has-next? true
            :has-previous? true}
           (select-keys
            second-result
            [:status :items :start-ordinal
             :has-next? :has-previous?])))))

(deftest generated-java-continues-reverse-pages-from-verified-lookahead
  (let [compiled-plan
        (verified/compile-indexed-plan
         selection
         {:indexed-rules [indexed-direct-rule]
          :seed-rules-by-subject-type
          {"user" [indexed-direct-rule]}})
        initialized
        (verified/initialize-indexed
         selection
         :reverse
         {:compiled-plan compiled-plan
          :request-scope 72
          :subject-type "user"
          :root-node
          {:resource-type "folder" :permission "read"}
          :root-resource-eid 10
          :result-type "user"
          :render {:kind :page :size 1 :bound nil}
          :chunk-size 2
          :limits indexed-limits})
        need-first-scan
        (verified/drive-indexed
         selection :reverse (:state initialized) indexed-limits 100)
        first-command (:command need-first-scan)
        resumed
        (verified/resume-indexed
         selection :reverse (:state need-first-scan)
         {:request-scope (:request-scope first-command)
          :request-id (:request-id first-command)
          :values [7 8]
          :terminal? false
          :fetched-values 3}
         indexed-limits)
        first-complete
        (verified/drive-indexed
         selection :reverse (:state resumed) indexed-limits 100)
        continued
        (verified/continue-indexed-page
         selection :reverse (:state first-complete)
         {:size 1 :bound {:ordinal 0 :eid 7}})
        need-second-scan
        (verified/drive-indexed
         selection :reverse (:state continued) indexed-limits 100)
        second-command (:command need-second-scan)
        second-resume
        (verified/resume-indexed
         selection :reverse (:state need-second-scan)
         {:request-scope (:request-scope second-command)
          :request-id (:request-id second-command)
          :values [9]
          :terminal? true
          :fetched-values 1}
         indexed-limits)
        second-complete
        (verified/drive-indexed
         selection :reverse (:state second-resume) indexed-limits 100)
        second-result
        (verified/read-indexed-result
         selection :reverse (:state second-complete))]
    (is (= :continued (:status continued)))
    (is (= 8
           (get-in need-second-scan
                   [:command :projection :bound-eid])))
    (is (= {:status :page
            :items [8]
            :start-ordinal 1
            :has-next? true
            :has-previous? true}
           (select-keys
            second-result
            [:status :items :start-ordinal
             :has-next? :has-previous?])))))

(deftest production-subproblem-store-uses-generated-java-decisions
  (let [store (subproblem/store {:projection-max-weight 1024
                                 :denotation-max-weight 1024})
        computes (atom 0)]
    (binding [subproblem/*decision-kernel* selection]
      (is (= 7
             (:value
              (subproblem/resolve!
               store :projection :key {}
               #(do (swap! computes inc) 7)))))
      (is (= 7
             (:value
              (subproblem/resolve!
               store :projection :key {}
               #(do (swap! computes inc) 8))))))
    (is (= 1 @computes))
    (is (= 1 (:hits (subproblem/stats store))))))

(deftest generated-java-full-authorization-boundary
  (let [evaluate
        (fn [request]
          (verified/decide
           selection
           :authorization-evaluation
           (assoc authorization-input :request request)))
        lookup
        (evaluate (:request authorization-input))
        can-result
        (evaluate
         {:operation :can?
          :subject {:type "user" :id "u1"}
          :permission "read"
          :resource {:type "folder" :id "f1"}})
        reverse-result
        (evaluate
         {:operation :lookup-subjects
          :resource {:type "folder" :id "f1"}
          :permission "read"
          :subject-type "user"})
        count-result
        (evaluate
         {:operation :count-resources
          :subject {:type "user" :id "u1"}
          :permission "read"
          :resource-type "folder"
          :count-limit 1})
        reverse-count-result
        (evaluate
         {:operation :count-subjects
          :resource {:type "folder" :id "f1"}
          :permission "read"
          :subject-type "user"
          :count-limit 1})]
    (is (= [{:type "folder" :id "f0"}
            {:type "folder" :id "f1"}]
           (:items lookup)))
    (is (true? (:allowed? can-result)))
    (is (= [{:type "user" :id "u1"}]
           (:items reverse-result)))
    (is (= {:count 1 :truncated? true}
           (select-keys count-result [:count :truncated?])))
    (is (= {:count 1 :truncated? false}
           (select-keys
            reverse-count-result
            [:count :truncated?])))))

(deftest generated-materialized-queue-limit-is-instantaneous
  (let [queue-input
        (-> authorization-input
            (update
             :objects
             #(vec (remove (fn [object] (= "team" (:type object))) %)))
            (update-in
             [:schema :relations]
             #(vec (remove (fn [relation]
                             (= "team-reader" (:relation relation)))
                           %)))
            (update-in
             [:schema :definitions]
             #(vec (remove (fn [definition]
                             (= "team-reader" (:relation definition)))
                           %)))
            (update
             :relationships
             #(vec (remove (fn [relationship]
                             (= "team-reader" (:relation relationship)))
                           %))))
        result
        (verified/decide
         selection
         :authorization-evaluation
         (assoc
          queue-input
          :request
          {:operation :count-resources
           :subject {:type "user" :id "u1"}
           :permission "read"
           :resource-type "folder"
           :count-limit 10}
          :limits
          {:max-derived-grants 1000
           :max-advanced-datoms 1000
           :max-queued-work 1}))]
    (is (= :complete (:status result)))
    (is (= {:count 2 :truncated? false}
           (select-keys result [:count :truncated?])))
    (is (= 1 (get-in result [:counters :queued-work])))))

(deftest materialized-resource-limits-are-whole-closure-scoped
  (let [result
        (verified/decide
         selection
         :authorization-evaluation
         (assoc
          authorization-input
          :request
          {:operation :count-resources
           :subject {:type "user" :id "u1"}
           :permission "read"
           :resource-type "folder"
           :count-limit 10}
          :limits
          {:max-derived-grants 1000
           :max-advanced-datoms 1000
           :max-queued-work 1}))]
    (is (= :limit-exceeded (:status result)))
    (is (= :queued-work (:limit-kind result)))
    (is (= 0 (get-in result [:counters :queued-work])))))

(deftest production-relationship-pages-use-generated-java-decisions
  (let [scan-specs [{:idx 0 :scan-kind :forward-anchored}]
        rows (mapv (fn [id]
                     {:spec-idx 0
                      :subject-id 1
                      :resource-id id
                      :relationship {:id id}})
                   (range 1 5))
        scan
        (fn [spec edge direction]
          (let [ordered (if (= :desc direction)
                          (reverse rows)
                          rows)]
            (drop-while
             #(not
               (relationship-engine/beyond-cursor?
                (:scan-kind spec) direction edge %))
             ordered)))
        first-page
        (relationship-engine/execute-page
         scan-specs {:first 2} selection scan)
        second-page
        (relationship-engine/execute-page
         scan-specs
         {:first 2
          :after (get-in first-page [:page-info :end-cursor])}
         selection
         scan)]
    (is (= [{:id 1} {:id 2}] (:data first-page)))
    (is (true? (get-in first-page [:page-info :has-next-page?])))
    (is (= [{:id 3} {:id 4}] (:data second-page)))
    (is (false? (get-in second-page [:page-info :has-next-page?])))
    (is (true? (get-in second-page
                       [:page-info :has-previous-page?])))))

(deftest production-lookup-cursor-uses-generated-java-decisions
  ;; The authenticated-envelope provider path this test also exercised was
  ;; deleted by trusted-surface-hygiene 11.1; cursor continuation remains
  ;; the generated-decision surface under test here.
  (let [adapter (test-adapter)
        cursor-opts
        {:decision-kernel selection
         :cursor-dependencies
         {:schema-scope {:permission-nodes #{[:document :view]}}
          :relation-ids [10]}
         :cursor-consistency-mode :minimize-latency}
        query {:subject (spice-object :user "user-1")
               :permission :view
               :resource/type :document
               :first 1}
        internal-page
        {:data [{:type :document :id 1}]
         :page-info
         {:start-cursor {:kind :lookup-eid :result-eid 1}
          :end-cursor {:kind :lookup-eid :result-eid 1}
          :has-next-page? true
          :has-previous-page? false}}
        external
        (relay/externalize-page
         adapter cursor-opts :lookup-resources query internal-page)
        token (get-in external [:page-info :end-cursor])
        selected
        (relay/select-continuation-adapter
         adapter cursor-opts :lookup-resources
         (assoc query :after token))
        internal-query
        (relay/internalize-page-query
         selected cursor-opts :lookup-resources
         (assoc query :after token))]
    (is (identical? adapter selected))
    (is (= {:kind :lookup-eid :result-eid 1}
           (:after internal-query)))))
