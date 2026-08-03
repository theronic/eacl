(ns eacl.formal.cache-strategy-adversarial-test
  "Finite falsification checks for the proposed cache-free/reference strategy.

  These are not a proof of the production implementation. They make the frame
  assumptions executable, exhaust a small recursive graph, and retain mutants
  for every key field whose omission produced a stale hit."
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [clojure.set :as set]
            [eacl.authorization-oracle :as oracle]
            [eacl.core :as eacl]))

(defn- object
  [type id]
  (eacl/spice-object type id))

(def user-0 (object :user "u0"))
(def user-1 (object :user "u1"))
(def group-0 (object :group "g0"))
(def group-1 (object :group "g1"))
(def document (object :document "d0"))

(def objects
  [user-0 user-1 group-0 group-1 document])

(def rules
  {[:group :view]
   [:union
    [:relation :member]
    [:arrow :parent [:permission :view]]]

   [:document :view]
   [:union
    [:relation :reader]
    [:arrow :group [:permission :view]]]})

(def relation-subject-types
  {[:group :member] :user
   [:group :parent] :group
   [:document :reader] :user
   [:document :group] :group
   [:document :unrelated] :user})

(def possible-relationships
  [(eacl/->Relationship user-0 :member group-0)
   (eacl/->Relationship user-1 :member group-1)
   (eacl/->Relationship group-0 :parent group-1)
   (eacl/->Relationship group-1 :parent group-0)
   (eacl/->Relationship user-0 :reader document)
   (eacl/->Relationship user-1 :reader document)
   (eacl/->Relationship group-0 :group document)
   (eacl/->Relationship group-1 :group document)
   (eacl/->Relationship user-1 :unrelated document)])

(defn- power-set
  [values]
  (reduce
   (fn [subsets value]
     (into subsets (map #(conj % value) subsets)))
   [#{}]
   values))

(defn- node-dependencies
  [node]
  (letfn [(walk-rule [resource-type rule seen]
            (let [[operator & operands] rule]
              (case operator
                :relation
                {:relations #{[resource-type (first operands)]}
                 :permissions #{}}

                :permission
                (walk-permission
                 [resource-type (first operands)]
                 seen)

                :union
                (reduce
                 (fn [result operand]
                   (merge-with
                    set/union
                    result
                    (walk-rule resource-type operand seen)))
                 {:relations #{} :permissions #{}}
                 operands)

                :arrow
                (let [[via target] operands
                      subject-type
                      (get relation-subject-types
                           [resource-type via])
                      target-result
                      (if subject-type
                        (walk-rule subject-type target seen)
                        {:relations #{} :permissions #{}})]
                  (update
                   target-result
                   :relations
                   conj
                   [resource-type via]))

                (throw
                 (ex-info
                  "Unknown adversarial rule operator."
                  {:operator operator
                   :rule rule})))))

          (walk-permission [[resource-type permission :as permission-node]
                            seen]
            (if (contains? seen permission-node)
              {:relations #{} :permissions #{}}
              (let [seen' (conj seen permission-node)
                    result
                    (walk-rule
                     resource-type
                     (get rules permission-node)
                     seen')]
                (update
                 result
                 :permissions
                 conj
                 permission-node))))]
    (walk-permission node #{})))

(def view-dependencies
  (:relations (node-dependencies [:document :view])))

(defn- relationship-dependency
  [relationship]
  [(get-in relationship [:resource :type])
   (:relation relationship)])

(defn- project-relationships
  [dependencies relationships]
  (into
   #{}
   (filter
    #(contains?
      dependencies
      (relationship-dependency %)))
   relationships))

(defn- authorization-result
  [relationships]
  (->> (oracle/authorization-set
        {:objects objects
         :relationships relationships
         :rules rules})
       (filter
        (fn [[_subject permission resource]]
          (and (= :view permission)
               (= document resource))))
       (map first)
       set))

(def exhaustive-states
  (mapv
   (fn [relationships]
     {:relationships relationships
      :projection
      (project-relationships
       view-dependencies
       relationships)
      :result (authorization-result relationships)})
   (power-set possible-relationships)))

(defn- managed-key
  [{:keys [query dependency-stamp result-layer]}]
  [query dependency-stamp result-layer])

(defn- cache-namespace
  [{:keys [semantic-abi adapter-abi lifecycle-generation
           schema-generation]}]
  [semantic-abi adapter-abi lifecycle-generation schema-generation])

(defn- command-traces
  [commands length]
  (if (zero? length)
    [[]]
    (for [prefix (command-traces commands (dec length))
          command commands]
      (conj prefix command))))

(defn- authorization-at-version
  [version]
  (odd? version))

(defn- race-key
  [versioned? version]
  (if versioned?
    [:query version]
    :query))

(defn- cache-race-step
  [versioned? state command]
  (case command
    :start
    (update
     state
     :jobs
     conj
     {:version (:head state)
      :value (authorization-at-version (:head state))})

    :write
    (update state :head inc)

    :publish
    (if-let [job (first (:jobs state))]
      (-> state
          (assoc-in
           [:cache (race-key versioned? (:version job))]
           (:value job))
          (update :jobs #(vec (rest %))))
      state)

    :invalidate
    (update
     state
     :cache
     dissoc
     (race-key versioned? (:head state)))

    :lookup
    (let [key (race-key versioned? (:head state))]
      (if (contains? (:cache state) key)
        (update
         state
         :observations
         conj
         {:actual (get (:cache state) key)
          :expected (authorization-at-version (:head state))})
        state))))

(defn- single-flight-step
  [versioned? state command]
  (case command
    :start
    (let [version (:head state)
          key (race-key versioned? version)]
      (if (contains? (:flights state) key)
        (update-in state [:flights key :waiters] conj version)
        (assoc-in
         state
         [:flights key]
         {:computed-version version
          :waiters [version]})))

    :write
    (update state :head inc)

    :complete
    (if-let [[key flight] (first (:flights state))]
      (-> state
          (update
           :observations
           into
           (map
            (fn [waiter-version]
              {:actual
               (authorization-at-version
                (:computed-version flight))
               :expected
               (authorization-at-version waiter-version)})
            (:waiters flight)))
          (update :flights dissoc key))
      state)))

(defn- stale-observation?
  [state]
  (some
   (fn [{:keys [actual expected]}]
     (not= actual expected))
   (:observations state)))

(deftest dependency-frame-exhaustive-test
  (testing "the complete relation projection determines the finite recursive result"
    (is (= #{[:document :group]
             [:document :reader]
             [:group :member]
             [:group :parent]}
           view-dependencies))
    (is (= 512 (count exhaustive-states)))
    (doseq [[projection states]
            (group-by :projection exhaustive-states)]
      (is (= 1 (count (set (map :result states))))
          (str "equal dependency projection changed authorization: "
               (pr-str projection)))))

  (testing "an omitted target relation has a retained counterexample"
    (let [mutant-dependencies
          (disj view-dependencies [:group :member])
          collisions
          (->> exhaustive-states
               (group-by
                #(project-relationships
                  mutant-dependencies
                  (:relationships %)))
               vals
               (filter
                #(not=
                  1
                  (count (set (map :result %))))))]
      (is (seq collisions)
          "the missing-dependency mutant must be killed"))))

(deftest versioned-publication-race-test
  (let [base
        {:query [:can? user-0 :view document]
         :result-layer :decision}
        old (assoc base :dependency-stamp 1)
        current (assoc base :dependency-stamp 2)
        late-publication {(managed-key old) false}]
    (is (nil? (get late-publication (managed-key current)))
        "an old computation published late is unreachable from a new epoch")
    (is (false?
         (get
          {[:stable (:query base)] false}
          [:stable (:query base)]))
        "a stable-key invalidation mutant republishes the stale denial")))

(deftest publication-and-single-flight-traces-exhaustive-test
  (let [cache-traces
        (command-traces
         [:start :write :publish :invalidate :lookup]
         6)
        initial-cache
        {:head 0 :jobs [] :cache {} :observations []}
        correct-cache-states
        (map
         #(reduce
           (partial cache-race-step true)
           initial-cache
           %)
         cache-traces)
        mutant-cache-states
        (map
         #(reduce
           (partial cache-race-step false)
           initial-cache
           %)
         cache-traces)
        flight-traces
        (command-traces [:start :write :complete] 5)
        initial-flight
        {:head 0 :flights {} :observations []}
        correct-flight-states
        (map
         #(reduce
           (partial single-flight-step true)
           initial-flight
           %)
         flight-traces)
        mutant-flight-states
        (map
         #(reduce
           (partial single-flight-step false)
           initial-flight
           %)
         flight-traces)]
    (is (not-any? stale-observation? correct-cache-states)
        "full versioned keys survive every bounded publication trace")
    (is (some stale-observation? mutant-cache-states)
        "stable-key publication retains a bounded stale trace")
    (is (not-any? stale-observation? correct-flight-states)
        "single-flight keyed by the full version cannot mix requests")
    (is (some stale-observation? mutant-flight-states)
        "query-only single-flight retains a bounded stale trace")))

(deftest every-managed-frame-component-is-semantic-test
  (let [namespace
        {:semantic-abi :eacl-v8-build-a
         :adapter-abi :datascript-1
         :lifecycle-generation :client-generation-a
         :schema-generation :schema-a}
        base
        {:query [:can? user-0 :view document]
         :dependency-stamp 1
         :result-layer :decision}]
    (doseq [[label changed]
            [[:semantic-abi
              (assoc namespace
                     :semantic-abi
                     :eacl-v8-build-b)]
             [:adapter-abi
              (assoc namespace
                     :adapter-abi
                     :datascript-2)]
             [:lifecycle-generation
              (assoc namespace
                     :lifecycle-generation
                     :client-generation-b)]
             [:schema
              (assoc namespace
                     :schema-generation
                     :schema-b)]]]
      (is (not= (cache-namespace namespace)
                (cache-namespace changed))
          (str "cache namespace omitted " label)))

    (doseq [[label changed]
            [[:query
              (assoc base :query [:can? user-1 :view document])]
             [:dependency
              (assoc base
                     :dependency-stamp
                     2)]
             [:result-layer
              (assoc base :result-layer :subject-set)]]]
      (is (not= (managed-key base)
                (managed-key changed))
          (str "managed key omitted " label))))

  (testing "writer discipline is an explicit, indispensable assumption"
    (let [before
          {:query [:can? user-0 :view document]
           :dependency-stamp 1
           :result-layer :decision
           :content #{}}
          dishonest-after
          (assoc before
                 :content
                 #{(eacl/->Relationship
                    user-0 :reader document)})]
      (is (= (managed-key before)
             (managed-key dishonest-after))
          "an unstamped raw write is indistinguishable to an epoch cache")
      (is (not=
           (authorization-result (:content before))
           (authorization-result (:content dishonest-after)))
          "the unstamped writer mutant changes authorization"))))

(deftest scalar-current-transaction-stamp-finite-test
  (testing "any relevant forward transaction raises the dependency maximum"
    (doseq [stamps
            (for [reader (range 4)
                  member (range 4)
                  parent (range 4)]
              [reader member parent])
            changed-index (range (count stamps))]
      (let [new-transaction 4
            changed
            (assoc stamps changed-index new-transaction)]
        (is (< (apply max stamps)
               (apply max changed))))))

  (testing "an unrelated write does not change the relevant maximum"
    (is (= (apply max [3 1 2])
           (apply max [3 1 2]))))

  (testing "rewind can reuse stamps and therefore requires lifecycle expiry"
    (let [before
          {:query :q
           :dependency-stamp 7
           :result-layer :decision}
          after-rewind
          (assoc before :content :different)]
      (is (= (managed-key before)
             (managed-key after-rewind))
          "managed cache does not support reset/force/history rewrite"))))

(deftest managed-hit-renders-the-selected-snapshot-test
  (let [entry
        {:semantic-value true
         :computation-snapshot 10}
        selected-snapshot 11
        render
        (fn [selected cached]
          {:value (:semantic-value cached)
           :selected-snapshot selected})
        stale-envelope-mutant
        (fn [_selected cached]
          {:value (:semantic-value cached)
           :selected-snapshot
           (:computation-snapshot cached)})]
    (is (= {:value true :selected-snapshot 11}
           (render selected-snapshot entry))
        "semantic hits are rendered against the selected snapshot")
    (is (not=
         (render selected-snapshot entry)
         (stale-envelope-mutant selected-snapshot entry))
        "copying computation-snapshot metadata retains a mismatch")))

(deftest completed-cache-is-current-snapshot-only-test
  (let [current-cache
        {:mode :current
         :generation 100
         :entries {[:can? :q] true}}
        exact-request
        {:mode :at-exact-snapshot
         :generation 90
         :query [:can? :q]}
        completed-cache-lookup
        (fn [cache request]
          (when (and (= :current (:mode request))
                     (= (:generation cache)
                        (:generation request)))
            (get-in cache [:entries (:query request)])))]
    (is (nil? (completed-cache-lookup
               current-cache
               exact-request))
        "at-exact-snapshot always bypasses the completed-answer cache")
    (is (nil? (completed-cache-lookup
               current-cache
               {:mode :current
                :generation 101
                :query [:can? :q]}))
        "a newer current snapshot cannot read the old exact generation")
    (is (true? (completed-cache-lookup
                current-cache
                {:mode :current
                 :generation 100
                 :query [:can? :q]}))
        "the current generation remains a hot cache")
    (is (= [:can? :q]
           (first (keys (:entries current-cache))))
        "database identity is bound by the client cache instance, not every key")))
