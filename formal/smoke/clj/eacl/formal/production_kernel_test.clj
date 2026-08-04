(ns eacl.formal.production-kernel-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eacl.backend.v8 :as backend]
   [eacl.cache :as cache]
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
  {:mode :verified-authoritative
   :kernel production/generated-java-kernel})

(defn- expected-consistency-plan
  [{:keys [mode capability-supported? managed-authority?]}]
  (cond
    (not capability-supported?)
    (case mode
      (:local-snapshot :minimize-latency) :unsupported-capability
      :at-exact-snapshot :exact-snapshot-unavailable
      :unsupported-head-barrier)
    (and (#{:at-least-as-fresh :at-exact-snapshot} mode)
         (not managed-authority?))
    :unsupported-head-barrier
    :else
    (case mode
      (:local-snapshot :minimize-latency) :select-current
      (:fully-consistent :synchronized-head) :select-authoritative
      :at-least-as-fresh :authenticate-and-select-at-least
      :at-exact-snapshot :authenticate-and-select-exact)))

(defn- expected-consistency-validation
  [{:keys [kind selection-present? selected-adapter?
           same-source-scope? anchor-satisfied?]}]
  (cond
    (not selection-present?)
    (if (= :exact kind)
      :exact-snapshot-unavailable
      :invalid-selected-adapter)
    (not selected-adapter?) :invalid-selected-adapter
    (not same-source-scope?) :incomparable-scope
    (and (#{:at-least :exact} kind)
         (not anchor-satisfied?))
    :history-divergence
    :else :accept))

(deftest generated-java-consistency-decisions-are-exhaustive
  (doseq [mode
          [:local-snapshot :minimize-latency
           :fully-consistent :synchronized-head
           :at-least-as-fresh :at-exact-snapshot]
          capability-supported? [false true]
          managed-authority? [false true]]
    (let [input {:mode mode
                 :capability-supported? capability-supported?
                 :managed-authority? managed-authority?}]
      (is (= (expected-consistency-plan input)
             (verified/decide
              selection
              :consistency-plan
              input
              #(throw (ex-info "legacy must not run" {})))))))
  (doseq [kind [:current :authoritative :at-least :exact]
          selection-present? [false true]
          selected-adapter? [false true]
          :when (or selection-present? (not selected-adapter?))
          same-source-scope? [false true]
          anchor-satisfied? [false true]]
    (let [input {:kind kind
                 :selection-present? selection-present?
                 :selected-adapter? selected-adapter?
                 :same-source-scope? same-source-scope?
                 :anchor-satisfied? anchor-satisfied?}]
      (is (= (expected-consistency-validation input)
             (verified/decide
              selection
              :consistency-validation
              input
              #(throw (ex-info "legacy must not run" {}))))))))

(defn- expected-consistency-work
  [path issue-response-token?]
  (let [response-scope (if issue-response-token? 1 0)
        common
        {:capability-observations 1
         :plan-decisions 1
         :authentication-attempts 0
         :backend-selection-calls 1
         :validation-decisions 1
         :contains-anchor-calls 0
         :graph-head-reads 1
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
       :contains-anchor-calls 0
       :graph-head-reads 0
       :order-hint-reads 0
       :exact-locator-reads 0}
      (:selected-current :authoritative)
      (assoc common :source-scope-reads (+ 2 response-scope))
      :at-least
      (assoc common
             :authentication-attempts 1
             :source-scope-reads (+ 3 response-scope)
             :contains-anchor-calls 1)
      :exact
      (assoc common
             :authentication-attempts 1
             :source-scope-reads (+ 3 response-scope)
             :graph-head-reads 2
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
     :source #{:stable-scope :graph-head
               :anchor-membership :order-hint :exact-locator}
     :cursor #{}
     :transactions #{}
     :cache-proofs #{}
     :runtime #{:clj}}
    :operations
    (into
     {}
     (map
      (fn [operation]
        [operation (fn [& _] nil)]))
     backend/required-snapshot-operations)}))

(defn- observed-generated-plan
  [source mode managed-authority?]
  (try
    [:planned
     (consistency/selection-plan
      source
      {:mode mode}
      {:coherence-authority
       (if managed-authority? :managed :unknown)
       :engine-selection selection})]
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
          [:local-snapshot :minimize-latency
           :fully-consistent :synchronized-head
           :at-least-as-fresh :at-exact-snapshot]
          capability-supported? [false true]
          managed-authority? [false true]]
    (let [input
          {:mode mode
           :capability-supported? capability-supported?
           :managed-authority? managed-authority?}]
      (is (=
           (expected-production-plan input)
           (observed-generated-plan
            (consistency-plan-adapter mode capability-supported?)
            mode
            managed-authority?))))))

(defn- generated-merge-two
  [direction left right]
  (loop [left (vec left)
         right (vec right)
         merged []]
    (if (or (empty? left) (empty? right))
      (into merged (if (empty? left) right left))
      (let [{:keys [values left-consumed right-consumed]}
            (verified/decide
             selection
             :ordered-merge-chunk
             {:direction direction
              :left left
              :right right}
             #(throw (ex-info "legacy must not run" {})))]
        (recur
         (subvec left left-consumed)
         (subvec right right-consumed)
         (into merged values))))))

(defn- power-set
  [values]
  (reduce
   (fn [subsets value]
     (into subsets (map #(conj % value)) subsets))
   [[]]
   values))

(defn- source-leapfrog-intersection
  [left right]
  (let [reseek-trace (atom [])
        exact-reseek
        (fn [side values]
          (fn [target]
            (swap! reseek-trace conj [side target])
            (drop-while #(< (long %) (long target)) values)))]
    {:intersects?
     (#'engine/sorted-eids-intersect?
      left (exact-reseek 0 left)
      right (exact-reseek 1 right))
     :reseek-trace @reseek-trace
     :reseeks (count @reseek-trace)}))

(defn- generated-leapfrog-intersection
  [left right]
  (production/acyclic-leapfrog-intersection
   {:left left :right right}))

(defn- bounded-ascending-values
  [values options]
  (if-let [bound (:bound-eid options)]
    (drop-while #(< (long %) (long bound)) values)
    values))

(defn- source-acyclic-arrow-path-decision
  [{:keys [intermediates direct-matches full-matches exhaustive?]}]
  (let [direct-intersection-phases (atom 0)
        full-candidate-checks (atom 0)
        substitutions
        {'get-permission-paths
         (fn [& _]
           [{:type :arrow
             :target-type :group
             :via-relation-eid 11
             :target-permission :member}])
         'resource->subjects
         (fn [& arguments]
           (bounded-ascending-values intermediates (last arguments)))
         'direct-grant-relations
         (fn [& _]
           (swap! direct-intersection-phases inc)
           {:relation-eids [22]
            :exhaustive? exhaustive?})
         'subject->resources
         (fn [& arguments]
           (bounded-ascending-values direct-matches (last arguments)))
         'can*
         (fn [_ _ _ _ _ intermediate-eid _]
           (swap! full-candidate-checks inc)
           (contains? full-matches intermediate-eid))}
        resolved
        (into
         {}
         (map
          (fn [[symbol replacement]]
            [(ns-resolve 'eacl.engine.v8 symbol) replacement])
          substitutions))
        allowed?
        (with-redefs-fn
          resolved
          #((ns-resolve 'eacl.engine.v8 'can-uncached*)
            nil :user 9 :view :document 10 #{}))]
    {:allowed? allowed?
     :direct-intersection-phases @direct-intersection-phases
     :full-candidate-checks @full-candidate-checks}))

(defn- generated-acyclic-arrow-path-decision
  [{:keys [intermediates direct-matches full-matches exhaustive?]}]
  (production/acyclic-arrow-path-decision
   {:full-candidate-matches
    (mapv #(contains? full-matches %) intermediates)
    :direct-intersects?
    (boolean (some (set direct-matches) intermediates))
    :exhaustive? exhaustive?}))

(defn- routing-node
  [[resource-type permission]]
  {:resource-type (pr-str resource-type)
   :permission (pr-str permission)})

(defn- generated-traversal-analysis
  [nodes edges]
  (let [permissions (mapv routing-node nodes)
        input-edges
        (mapv
         (fn [[head target]]
           {:head (routing-node head)
            :target (routing-node target)})
         edges)]
    (into
     {}
     (map
      (fn [root]
        [root
         (production/typed-traversal-permission?
          {:root (routing-node root)
           :edges input-edges
           :permissions permissions})])
      nodes))))

(defn- routing-test-adapter
  [nodes edges]
  (let [edge-records
        (map-indexed
         (fn [index [[source-type source-permission :as head]
                    [target-type target-permission]]]
           (let [relation-name
                 (keyword "formal.routing" (str "edge-" index))]
             {:head head
              :relation-name relation-name
              :relation
              (when (not= source-type target-type)
                {:relation-id (inc index)
                 :resource-type source-type
                 :relation-name relation-name
                 :subject-type target-type})
              :permission
              {:permission-id (+ 1000 index)
               :resource-type source-type
               :permission-name source-permission
               :source-relation-name
               (if (= source-type target-type)
                 :self
                 relation-name)
               :target-type :permission
               :target-name target-permission}}))
         edges)
        relations
        (into
         {}
         (keep
          (fn [{:keys [relation]}]
            (when relation
              [[(:resource-type relation)
                (:relation-name relation)]
               [relation]])))
         edge-records)
        permissions
        (->> edge-records
             (group-by :head)
             (into {}
                   (map
                    (fn [[head records]]
                      [head (mapv :permission records)]))))]
    (backend/make-adapter
     {:id :formal-routing-test
      :capabilities
      {:consistency #{:minimize-latency}
       :snapshots #{:current}
       :source #{:scoped}
       :cursor #{:forward :backward}
       :transactions #{}
       :cache-proofs #{:schema :relations}
       :runtime #{:clj}}
      :operations
      (merge
       (into {}
             (map
              (fn [operation]
                [operation (fn [& _] nil)]))
             backend/required-snapshot-operations)
       {:snapshot-id
        (constantly {:database-id :formal-routing :basis-t 1})
        :source-scope
        (constantly {:source-id :formal-routing :branch nil})
        :schema-proof
        (fn
          ([] :schema-proof)
          ([_] :schema-proof))
        :relation-defs
        (fn [resource-type relation-name]
          (get relations [resource-type relation-name] []))
        :permission-defs
        (fn [resource-type permission-name]
          (get permissions [resource-type permission-name] []))
        :all-permission-nodes
        (constantly (set nodes))})})))

(defn- source-traversal-analysis
  ([nodes edges]
   (source-traversal-analysis nodes edges nil))
  ([nodes edges engine-selection]
   (let [adapter (routing-test-adapter nodes edges)
         schema-cache (engine/make-schema-cache adapter :schema-proof)]
     (binding [engine/*schema-cache* schema-cache
               subproblem/*engine-selection* engine-selection]
       (into
        {}
        (map
         (fn [[resource-type permission :as node]]
           [node
            (engine/traversal-permission?
             adapter resource-type permission)])
         nodes))))))

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
          routing-certificate-input
          (constantly nil))))
  (is (= :invalid-dependency-edge
         (:reason
          (verified/decide
           selection
           :recursive-routing-certificate
           (assoc-in
            routing-certificate-input
            [:certificate :traversal]
            [false true])
           (constantly nil)))))
  (is (= :routing-path-edge-mismatch
         (:reason
          (verified/decide
           selection
           :recursive-routing-certificate
           (assoc-in
            routing-certificate-input
            [:path-descriptors 0]
            {:kind :relation :head 0})
           (constantly nil)))))
  (is (= :invalid-routing-path
         (:reason
          (verified/decide
           selection
           :recursive-routing-certificate
           (assoc-in
            routing-certificate-input
            [:path-descriptors 0 :target]
            2)
           (constantly nil)))))
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
             {:kind :arrow-permission :head 1 :target 1}])
           (constantly nil)))))
  (is (= :routing-path-edge-mismatch
         (:reason
          (verified/decide
           selection
           :recursive-routing-certificate
           (update
            routing-certificate-input
            :path-descriptors
            #(vec (reverse %)))
           (constantly nil)))))
  (is (= :invalid-component-witness
         (:reason
          (verified/decide
           selection
           :recursive-routing-certificate
           (assoc-in
            routing-certificate-input
            [:certificate :traversal-witness-edge]
            [-1 -1])
           (constantly nil)))))
  (is (= :accepted
         (:status
          (verified/decide
           selection
           :recursive-routing-certificate
           scc-routing-certificate-input
           (constantly nil)))))
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
               input
               (constantly nil))))))))

(deftest generated-java-routing-certificate-scales-linearly
  (let [node-count 4096
        decision
        (verified/decide
         selection
         :recursive-routing-certificate
         (chain-routing-certificate-input node-count)
         (constantly nil))]
    (is (= :accepted (:status decision)))
    (is (= node-count (:path-checks decision)))
    (is (= (* 2 node-count) (:node-checks decision)))
    (is (= node-count (:edge-checks decision)))
    (is (= node-count (count (:traversal decision))))
    (is (every? true? (:traversal decision)))))

(deftest generated-java-production-routing-is-certified-once-per-schema-generation
  (let [node-count 4096
        nodes
        (mapv
         #(vector :resource (keyword (str "permission-" %)))
         (range node-count))
        edges
        (conj
         (mapv vector nodes (subvec nodes 1))
         [(peek nodes) (peek nodes)])
        calls (atom 0)
        counting-kernel
        (reify
          verified/DecisionKernel
          (-decide [_ operation input]
            (when (= :recursive-routing-certificate operation)
              (swap! calls inc))
            (verified/-decide
             production/generated-java-kernel
             operation
             input)))
        certified
        (source-traversal-analysis
         nodes
         edges
         {:mode :verified-authoritative
          :kernel counting-kernel})]
    (is (= 1 @calls))
    (is (= node-count (count certified)))
    (is (every? true? (vals certified)))))

(deftest generated-java-certifies-production-path-to-edge-derivation
  (let [document-view [:document :view]
        document-edit [:document :edit]
        team-view [:team :view]
        nodes [document-view document-edit team-view]
        ordered-nodes (vec (sort-by pr-str nodes))
        node-index (zipmap ordered-nodes (range))
        captured (atom nil)
        capturing-kernel
        (reify
          verified/DecisionKernel
          (-decide [_ operation input]
            (when (= :recursive-routing-certificate operation)
              (reset! captured input))
            (verified/-decide
             production/generated-java-kernel
             operation
             input)))
        materialized-paths
        [{:type :relation}
         {:type :self-permission
          :target-permission :edit}
         {:type :arrow
          :target-type :team
          :target-relation :member}
         {:type :arrow
          :target-type :team
          :target-permission :view}]]
    (with-redefs [engine/get-permission-paths
                  (fn [_ resource-type permission]
                    (if (= [resource-type permission] document-view)
                      materialized-paths
                      []))]
      (is
       (every?
        false?
        (vals
         (source-traversal-analysis
          nodes
          []
          {:mode :verified-authoritative
           :kernel capturing-kernel})))))
    (let [head (get node-index document-view)
          document-target (get node-index document-edit)
          team-target (get node-index team-view)]
      (is
       (=
        [{:kind :relation :head head}
         {:kind :self-permission
          :head head
          :target document-target}
         {:kind :arrow-relation :head head}
         {:kind :arrow-permission
          :head head
          :target team-target}]
        (:path-descriptors @captured)))
      (is
       (=
        [{:head head :target document-target}
         {:head head :target team-target}]
        (:edges @captured))))
    (with-redefs [engine/get-permission-paths
                  (fn [_ resource-type permission]
                    (if (= [resource-type permission] document-view)
                      materialized-paths
                      []))]
      (let [failure
            (try
              (source-traversal-analysis
               [document-view]
               []
               selection)
              nil
              (catch clojure.lang.ExceptionInfo error
                (ex-data error)))]
        (is (= :invalid-routing-path (:reason failure)))))))

(deftest generated-java-classifies-production-recursive-routing
  (let [a [:alpha :read]
        b [:beta :read]
        c [:charlie :read]
        d [:delta :read]
        e [:echo :read]
        document-read [:document :read]
        team-view [:team :view]
        folder-view [:folder :view]
        cases
        [{:label "empty graph"
          :nodes [a b]
          :edges []
          :expected {a false b false}}
         {:label "singleton self-loop"
          :nodes [a b]
          :edges [[a a]]
          :expected {a true b false}}
         {:label "two-node SCC and acyclic ancestor"
          :nodes [a b c d]
          :edges [[a b] [b a] [c a]]
          :expected {a true b true c true d false}}
         {:label "disconnected recursive component"
          :nodes [a b c d]
          :edges [[a b] [b a] [c d]]
          :expected {a true b true c false d false}}
         {:label "acyclic diamond"
          :nodes [a b c d]
          :edges [[a b] [a c] [b d] [c d]]
          :expected {a false b false c false d false}}
         {:label "deep ancestor chain"
          :nodes [a b c d e]
          :edges [[a b] [b c] [c d] [d e] [e e]]
          :expected {a true b true c true d true e true}}
         {:label "same permission name on unrelated resource types"
          :nodes [document-read team-view folder-view]
          :edges [[document-read team-view]
                  [folder-view folder-view]]
          :expected
          {document-read false
           team-view false
           folder-view true}}]]
    (doseq [{:keys [label nodes edges expected]} cases]
      (testing label
        (let [source (source-traversal-analysis nodes edges)
              certified
              (source-traversal-analysis nodes edges selection)
              generated (generated-traversal-analysis nodes edges)]
          (is (= expected source))
          (is (= source certified))
          (is (= source generated)))))))

(deftest generated-java-exhausts-three-node-recursive-routing-graphs
  (let [nodes
        [[:document :view]
         [:team :view]
         [:folder :read]]
        possible-edges
        (vec
         (for [head nodes
               target nodes]
           [head target]))
        graphs (power-set possible-edges)]
    (is (= 512 (count graphs)))
    (doseq [edges graphs]
      (let [source (source-traversal-analysis nodes edges)]
        (is (= source
               (source-traversal-analysis nodes edges selection))
            (str "certificate mismatch for edges " (pr-str edges)))
        (is (= source
               (generated-traversal-analysis nodes edges))
            (str "routing mismatch for edges " (pr-str edges)))))))

(def authorization-input
  {:objects [{:type "user" :id "u1"}
             {:type "team" :id "t1"}
             {:type "folder" :id "f0"}
             {:type "folder" :id "f1"}]
   :schema
   {:relations
    [{:resource-type "folder"
      :relation "reader"
      :subject-type "user"}
     {:resource-type "folder"
      :relation "parent"
      :subject-type "folder"}
     {:resource-type "folder"
      :relation "team-reader"
      :subject-type "team"}]
    :permissions
    [{:resource-type "folder"
      :permission "read"}]
    :definitions
    [{:kind :direct-relation
      :resource-type "folder"
      :permission "read"
      :relation "reader"
      :subject-type "user"}
     {:kind :arrow-permission
      :resource-type "folder"
      :permission "read"
      :via-relation "parent"
      :target-permission "read"}
     {:kind :direct-relation
      :resource-type "folder"
      :permission "read"
      :relation "team-reader"
      :subject-type "team"}]}
   :relationships
   [{:resource {:type "folder" :id "f0"}
     :relation "reader"
     :subject {:type "user" :id "u1"}}
    {:resource {:type "folder" :id "f1"}
     :relation "parent"
     :subject {:type "folder" :id "f0"}}
    {:resource {:type "folder" :id "f1"}
     :relation "team-reader"
     :subject {:type "team" :id "t1"}}]
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
        direct
        {:kind :relation
         :head head
         :relation-eid 1
         :subject-type "user"}
        recursive
        {:kind :arrow-permission
         :head head
         :via-relation-eid 2
         :intermediate-type "folder"
         :target-node head}]
    {:relations
     [{:resource-type "folder"
       :relation "reader"
       :subject-type "user"}
      {:resource-type "folder"
       :relation "parent"
       :subject-type "folder"}]
     :permissions [head]
     :definitions
     [{:kind :direct-relation
       :resource-type "folder"
       :permission "read"
       :relation "reader"
       :subject-type "user"}
      {:kind :arrow-permission
       :resource-type "folder"
       :permission "read"
       :via-relation "parent"
       :target-permission "read"}]
     :relation-bindings
     [{:eid 1
       :relation
       {:resource-type "folder"
        :relation "reader"
        :subject-type "user"}}
      {:eid 2
       :relation
       {:resource-type "folder"
        :relation "parent"
        :subject-type "folder"}}]
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
     :source #{:scoped}
     :cursor #{:forward :backward}
     :transactions #{}
     :cache-proofs #{:schema :relations}
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
      :source-scope (constantly {:source "source"})
      :graph-head
      (constantly
       {:graph-anchor "graph-1"
        :order-hint 1
        :exact-locator "graph-1"})
      :contains-anchor? #(= "graph-1" %)
      :order-hint (constantly 1)
      :exact-locator (constantly "graph-1")
      :select-exact (fn [& _] nil)
      :object-id->internal
      #(case % "document-1" 1 "document-2" 2 nil)
      :internal-id->object
      #(case % 1 "document-1" 2 "document-2" nil)
      :schema-proof (constantly "schema-proof")
      :relation-proof
      (fn [relation-ids]
        (zipmap relation-ids (repeat "relation-proof")))})}))

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
       :source #{:scoped}
       :cursor #{:forward :backward}
       :transactions #{}
       :cache-proofs #{:schema :relations}
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
        :schema-proof
        (fn
          ([] :schema-proof)
          ([_] :schema-proof))
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

(deftest generated-java-certifies-production-recursive-plan
  (let [adapter (recursive-plan-test-adapter)
        schema-cache (engine/make-schema-cache adapter :schema-proof)
        stats (atom {})]
    (binding [engine/*schema-cache* schema-cache
              engine/*recursive-traversal-stats* stats
              subproblem/*engine-selection* selection]
      (is (seq
           (:components
            (engine/recursive-component-plan
             adapter :folder :read))))
      (is (= 1 (:plan-certification-runs @stats)))
      (is (= 4 (:plan-certification-rules @stats)))
      (is (= 4 (:plan-certification-definitions @stats)))
      (is (= 4 (:plan-certification-bindings @stats)))
      (is (= 2 (:plan-certification-seed-buckets @stats)))
      (is (= 3 (:plan-certification-kernel-calls @stats)))
      (engine/recursive-component-plan adapter :folder :read)
      (is (= 1 (:plan-certification-runs @stats))
          "the certified plan is reused for the schema proof/root"))))

(deftest generated-java-drives-production-recursive-pages
  (let [adapter (recursive-traversal-test-adapter)
        query
        {:subject {:type :user :id 1}
         :permission :read
         :resource/type :folder
         :first 2}
        run-forward
        (fn [engine-selection schema-cache query']
          (binding [engine/*schema-cache* schema-cache
                    subproblem/*engine-selection* engine-selection]
            (engine/lookup-resources adapter query')))
        legacy-cache
        (engine/make-schema-cache adapter :schema-proof)
        generated-cache
        (engine/make-schema-cache adapter :schema-proof)
        legacy-first
        (run-forward
         :legacy-authoritative legacy-cache query)
        generated-first
        (run-forward selection generated-cache query)
        after (get-in generated-first [:page-info :end-cursor])
        legacy-second
        (run-forward
         :legacy-authoritative
         legacy-cache
         (assoc query :after after))
        generated-second
        (run-forward
         selection generated-cache (assoc query :after after))
        reverse-query
        {:resource {:type :folder :id 20}
         :permission :read
         :subject/type :user
         :first 10}
        run-reverse
        (fn [engine-selection schema-cache]
          (binding [engine/*schema-cache* schema-cache
                    subproblem/*engine-selection* engine-selection]
            (engine/lookup-subjects adapter reverse-query)))
        legacy-reverse
        (run-reverse
         :legacy-authoritative
         (engine/make-schema-cache adapter :schema-proof))
        generated-reverse
        (run-reverse
         selection
         (engine/make-schema-cache adapter :schema-proof))
        run-operation
        (fn [engine-selection operation]
          (binding [engine/*schema-cache*
                    (engine/make-schema-cache adapter :schema-proof)
                    subproblem/*engine-selection* engine-selection]
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
           :subject/type :user})]
    (is (= legacy-first generated-first))
    (is (true? (get-in generated-first
                       [:page-info :has-next-page?])))
    (is (= legacy-second generated-second))
    (is (= legacy-reverse generated-reverse))
    (is (= [{:type :user :id 1}]
           (mapv
            #(select-keys % [:type :id])
            (:data generated-reverse))))
    (is (= (run-operation :legacy-authoritative can-operation)
           (run-operation selection can-operation)
           true))
    (is (= (run-operation
            :legacy-authoritative limited-count-operation)
           (run-operation selection limited-count-operation)
           {:count 2 :limit 2 :truncated? true}))
    (is (= (run-operation
            :legacy-authoritative all-count-operation)
           (run-operation selection all-count-operation)
           {:count 3 :limit -1}))
    (is (= (run-operation
            :legacy-authoritative reverse-count-operation)
           (run-operation selection reverse-count-operation)
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
                       :before :absent
             :has-legacy-limit? false
             :has-legacy-cursor? false}
             :default-size 1000
             :maximum-size 10000}
            #(throw (ex-info "legacy must not run" {}))))))
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
             :realized-count 21}
            #(throw (ex-info "legacy must not run" {}))))))
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
             :mode :exact-snapshot
             :cursor-graph 0
             :exact nil}
            #(throw (ex-info "legacy must not run" {}))))))
  (testing "recoverable cursor proof mismatch rebases to current"
    (is (= :rebase-current
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
             :mode :recover-current
             :cursor-graph 0
             :exact nil}
            #(throw (ex-info "legacy must not run" {}))))))
  (testing "cache future/sibling is rejected"
    (is (= {:status :miss :reason :future-or-sibling}
           (verified/decide
            selection
            :cache-validation
            {:deterministic? true
             :dependency-scope-nonempty? true
             :expected-key "key"
             :expected-source "source"
             :selected-graph 0
             :ancestors #{1}
             :selected-proof "proof"
             :entry {:status :candidate
                     :authenticated? true
                     :key "key"
                     :source "source"
                     :graph 2
                     :proof "proof"}}
            #(throw (ex-info "legacy must not run" {})))))))

(deftest generated-java-subproblem-cache-decisions
  (is (= :use-completed-value
         (verified/decide
          selection
          :subproblem-cache-decision
          {:decision :lookup
           :recursive-self? false
           :candidate :complete}
          #(throw (ex-info "legacy must not run" {})))))
  (is (= :compute-without-admission
         (verified/decide
          selection
          :subproblem-cache-decision
          {:decision :admission
           :candidate-present? false
           :represented-candidates 8
           :maximum-candidates 8}
          #(throw (ex-info "legacy must not run" {})))))
  (is (= :drop-publication
         (verified/decide
          selection
          :subproblem-cache-decision
          {:decision :publication
           :ticket-current? true
           :complete? true
           :valid? true
           :weight 1025
           :budget 1024}
          #(throw (ex-info "legacy must not run" {}))))))

(deftest generated-java-current-cache-decisions
  (doseq [[input expected]
          [[{:stage :eligibility :available? false}
            :bypass-current-cache]
           [{:stage :generation :available? true}
            :probe-exact-entry]
           [{:stage :exact-entry :available? false}
            :probe-managed-entry]
           [{:stage :managed-entry :available? true}
            :use-managed-entry]]]
    (is (= expected
           (verified/decide
            selection
            :current-cache-decision
            input
            #(throw (ex-info "legacy must not run" {})))))))

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
            input
            #(throw (ex-info "legacy must not run" {}))))))
  (is (= :eacl.verification/invalid-boundary
         (try
           (verified/decide
            selection
            :ordered-merge-step
            {:direction :asc :left-head -1 :right-head 2}
            #(throw (ex-info "legacy must not run" {})))
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
           :right [2 4 6]}
          #(throw (ex-info "legacy must not run" {})))))
  (is (= {:values [6 5 4 3 2]
          :left-consumed 3
          :right-consumed 2}
         (verified/decide
          selection
          :ordered-merge-chunk
          {:direction :desc
           :left [6 4 2]
           :right [5 3 1]}
          #(throw (ex-info "legacy must not run" {})))))
  (is (= :eacl.verification/invalid-boundary
         (try
           (verified/decide
            selection
            :ordered-merge-chunk
            {:direction :asc
             :left [2 1]
             :right [3 4]}
            #(throw (ex-info "legacy must not run" {})))
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

(deftest optimized-jvm-ordered-merge-refines-generated-chunks
  (doseq [seed (range 100)
          direction [:asc :desc]]
    (let [ascending-left
          (vec (filter #(zero? (mod (+ % seed) 3)) (range 80)))
          ascending-right
          (vec (filter #(zero? (mod (+ % (* 2 seed)) 5)) (range 80)))
          [left right]
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
            [left right]))]
      (is (= (generated-merge-two direction left right)
             actual)
          (str "seed=" seed " direction=" direction))))
  (doseq [seed (range 50)
          direction [:asc :desc]]
    (let [ascending-streams
          (mapv
           (fn [divisor]
             (vec
              (filter
               #(zero? (mod (+ % seed) divisor))
               (range 120))))
           [2 3 5 7 11])
          streams
          (if (= :asc direction)
            (vec (concat [[]] ascending-streams [[]]))
            (vec
             (concat
              [[]]
              (mapv #(vec (reverse %)) ascending-streams)
              [[]])))
          expected
          (->> streams
               (apply concat)
               distinct
               (sort (case direction :asc < :desc >))
               vec)
          actual
          (vec
           ((case direction
              :asc lazy-sort/lazy-fold2-merge-dedupe-sorted-by
              :desc lazy-sort/lazy-fold2-merge-dedupe-sorted-by-desc)
              identity
              streams))]
      (is (= expected actual)
          (str "balanced seed=" seed " direction=" direction))
      (is (= (production/production-ordered-fold
              {:direction direction
               :streams streams})
             actual)
          (str
           "modeled balanced seed=" seed
           " direction=" direction)))))

(deftest optimized-jvm-leapfrog-intersection-refines-bounded-proof
  (let [subsets (power-set [0 1 2 17 1000 9007199254740991])
        cases
        (concat
         (for [left subsets
               right subsets]
           [left right])
         [[(vec (range 18)) [17]]
          [(vec (range 513)) [512]]
          [(vec (range 512)) [700]]
          [(vec (range 0 512 2)) (vec (range 1 512 2))]])
        mismatches
        (into
         []
         (keep
          (fn [[left right :as input]]
            (let [expected (boolean (some (set left) right))
                  source (source-leapfrog-intersection left right)
                  generated (generated-leapfrog-intersection left right)]
              (when-not
               (and (= expected (:intersects? source))
                    (= expected (:intersects? generated))
                    (= (:reseeks source)
                       (:reseek-calls generated))
                    (= (:reseek-trace source)
                       (:reseek-trace generated))
                    (= (:reseek-calls generated)
                       (count (:reseek-trace generated)))
                    (<= (:iterations generated)
                        (+ (count left) (count right)))
                    (<= (:reseek-calls generated)
                        (:iterations generated))
                    (<= (:examined-heads generated)
                        (* 17 (:iterations generated))))
                {:input input
                 :expected expected
                 :source source
                 :generated generated}))))
         cases)
        skewed-source
        (source-leapfrog-intersection (vec (range 513)) [512])]
    (is (= 4100 (count cases)))
    (is (empty? mismatches) (pr-str (first mismatches)))
    (is (true? (:intersects? skewed-source)))
    (is (pos? (:reseeks skewed-source)))))

(deftest generated-java-acyclic-arrow-path-refines-source-control
  (doseq [{:keys [label expected] :as fixture}
          [{:label :empty
            :intermediates []
            :direct-matches []
            :full-matches #{}
            :exhaustive? false
            :expected
            {:allowed? false
             :direct-intersection-phases 0
             :full-candidate-checks 0}}
           {:label :singleton-hit
            :intermediates [10]
            :direct-matches []
            :full-matches #{10}
            :exhaustive? false
            :expected
            {:allowed? true
             :direct-intersection-phases 0
             :full-candidate-checks 1}}
           {:label :singleton-miss
            :intermediates [10]
            :direct-matches []
            :full-matches #{}
            :exhaustive? true
            :expected
            {:allowed? false
             :direct-intersection-phases 0
             :full-candidate-checks 1}}
           {:label :wide-direct-hit
            :intermediates [10 20 30]
            :direct-matches [30]
            :full-matches #{30}
            :exhaustive? false
            :expected
            {:allowed? true
             :direct-intersection-phases 1
             :full-candidate-checks 0}}
           {:label :wide-exhaustive-hit
            :intermediates [10 20 30]
            :direct-matches [20]
            :full-matches #{20}
            :exhaustive? true
            :expected
            {:allowed? true
             :direct-intersection-phases 1
             :full-candidate-checks 0}}
           {:label :wide-exhaustive-miss
            :intermediates [10 20 30]
            :direct-matches []
            :full-matches #{}
            :exhaustive? true
            :expected
            {:allowed? false
             :direct-intersection-phases 1
             :full-candidate-checks 0}}
           {:label :wide-fallback-hit
            :intermediates [10 20 30]
            :direct-matches []
            :full-matches #{20}
            :exhaustive? false
            :expected
            {:allowed? true
             :direct-intersection-phases 1
             :full-candidate-checks 2}}
           {:label :wide-fallback-miss
            :intermediates [10 20 30]
            :direct-matches []
            :full-matches #{}
            :exhaustive? false
            :expected
            {:allowed? false
             :direct-intersection-phases 1
             :full-candidate-checks 3}}]]
    (let [input (dissoc fixture :label :expected)
          source (source-acyclic-arrow-path-decision input)
          generated (generated-acyclic-arrow-path-decision input)]
      (is (= expected source generated)
          (pr-str {:label label
                   :source source
                   :generated generated})))))

(deftest generated-java-indexed-plan-certification-boundary
  (let [decide
        (fn [input]
          (verified/decide
           selection
           :indexed-plan-certification
           input
           #(throw (ex-info "legacy must not run" {}))))
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
           input
           #(throw (ex-info "legacy must not run" {}))))
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
           input
           #(throw (ex-info "legacy must not run" {}))))]
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

(deftest production-indexed-scan-gate-uses-generated-java
  (let [validate!
        (ns-resolve
         'eacl.engine.v8
         'validate-indexed-scan-response!)
        input
        {:command
         {:request-scope 41
          :request-id 3
          :projection
          {:kind :resource->subjects
           :resource-type ":document"
           :resource-eid 9
           :relation-eid 4
           :subject-type ":user"
           :bound-eid nil}
         :chunk-size 2}
         :response
         {:request-scope 41
          :request-id 3
          :values [2 5]
          :terminal? true
          :fetched-values 2}}]
    (binding [subproblem/*engine-selection* selection]
      (is (= {:status :accepted
              :values [2 5]
              :terminal? true
              :fetched-values 2}
             (validate! input)))
      (is (= :out-of-order
             (try
               (validate!
                (assoc-in input [:response :values] [5 2]))
               nil
               (catch clojure.lang.ExceptionInfo error
                 (:reason (ex-data error)))))))))

(deftest generated-traversal-request-scope-allocation-is-safe
  (let [allocate!
        (ns-resolve 'eacl.engine.v8 'next-generated-request-scope!)
        successor
        (ns-resolve 'eacl.engine.v8 'successor-generated-request-scope)
        scopes (doall (pmap (fn [_] (allocate!)) (range 256)))]
    (is (= 256 (count (distinct scopes))))
    (is (every?
         #(<= 0 % backend/maximum-exact-integer)
         scopes))
    (is (= backend/maximum-exact-integer
           (successor (dec backend/maximum-exact-integer))))
    (is (= :eacl/request-scope-exhausted
           (try
             (successor backend/maximum-exact-integer)
             nil
             (catch clojure.lang.ExceptionInfo error
               (:type (ex-data error))))))))

(def indexed-direct-rule
  {:kind :relation
   :head {:resource-type "folder" :permission "read"}
   :relation-eid 1
   :subject-type "user"})

(def indexed-limits
  {:max-derived-grants 100
   :max-advanced-datoms 100
   :max-queued-work 100})

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
                                 :denotation-max-weight 1024
                                 :max-inflight 1})
        computes (atom 0)]
    (binding [subproblem/*engine-selection* selection]
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
           (assoc authorization-input :request request)
           #(throw (ex-info "legacy must not run" {}))))
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
           :max-queued-work 1})
         #(throw (ex-info "legacy must not run" {})))]
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
           :max-queued-work 1})
         #(throw (ex-info "legacy must not run" {})))]
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

(deftest production-lookup-cursor-and-cache-use-generated-java-decisions
  (let [adapter (test-adapter)
        cursor-opts
        {:engine-selection selection
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
         (assoc query :after token))
        store (cache/local-store)
        calls (atom 0)
        resolve-cache
        #(cache/resolve!
          adapter store :semantic-key :can?
          {:permission-nodes #{[:document :view]}}
          [10]
          boolean?
          (fn [] (swap! calls inc) true)
          {:engine-selection selection})]
    (is (identical? adapter selected))
    (is (= {:kind :lookup-eid :result-eid 1}
           (:after internal-query)))
    (is (false? (:cached? (resolve-cache))))
    (is (true? (:cached? (resolve-cache))))
    (is (= 1 @calls))))
