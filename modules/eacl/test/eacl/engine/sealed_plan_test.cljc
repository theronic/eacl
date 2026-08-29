(ns eacl.engine.sealed-plan-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.engine.sealed-plan :as sealed-plan]))

(defn- permission-row
  [resource-type permission-name source target-type target-name]
  {:resource-type resource-type
   :permission-name permission-name
   :source-relation-name source
   :source-subject-type nil
   :target-type target-type
   :target-name target-name})

(defn- relation-row
  [relation-id resource-type relation-name subject-type]
  {:relation-id relation-id
   :resource-type resource-type
   :relation-name relation-name
   :subject-type subject-type})

(defn- plan-adapter
  [{:keys [permissions relations reverse-provider-order?]}]
  (let [permissions (if reverse-provider-order?
                      (vec (reverse permissions))
                      permissions)
        relations (if reverse-provider-order?
                    (vec (reverse relations))
                    relations)
        required-stubs
        (into {}
              (map #(vector % (fn [& _] nil)))
              backend/required-snapshot-operations)]
    (backend/make-adapter
     {:id :sealed-plan-test
      :capabilities backend/empty-capabilities
      :operations
      (merge
       required-stubs
       {:permission-defs
        (fn [resource-type permission-name]
          (filterv #(and (= resource-type (:resource-type %))
                         (= permission-name (:permission-name %)))
                   permissions))
        :relation-defs
        (fn [resource-type relation-name]
          (filterv #(and (= resource-type (:resource-type %))
                         (= relation-name (:relation-name %)))
                   relations))
        :all-permission-nodes
        (fn [] (set (map (juxt :resource-type :permission-name)
                         permissions)))})})))

(def ^:private alias-relations
  [(relation-row 10 :document :organization :organization)
   (relation-row 20 :organization :member :user)])

(def ^:private alias-permissions
  [(permission-row :document :view :organization :permission :base)
   (permission-row :document :view :organization :permission :alias)
   (permission-row :organization :base :self :relation :member)
   (permission-row :organization :alias :self :permission :base)])

(defn- alias-plan
  ([] (alias-plan false))
  ([reverse-provider-order?]
   (sealed-plan/seal-plan
    (plan-adapter {:permissions alias-permissions
                   :relations alias-relations
                   :reverse-provider-order? reverse-provider-order?})
    [:document :view])))

(deftest exact-alias-deduplicates-only-the-acyclic-execution-frontier-test
  (let [plan (alias-plan)
        semantic-root-rules (filterv #(= [:document :view] (:node %))
                                     (:rules plan))
        execution-root-rules
        (get-in plan [:indexes :reverse-rules [:document :view]])
        earliest-position (apply min (map :ordinal semantic-root-rules))]
    (is (= :least-path (:order-mode plan)))
    (is (= 2 (count semantic-root-rules))
        "the complete semantic graph is retained")
    (is (= 1 (count execution-root-rules))
        "only the equal normalized traversal identity is consolidated")
    (is (= [:organization :base]
           (:target-node (first execution-root-rules))))
    (is (= earliest-position (:ordinal (first execution-root-rules)))
        "the earliest provider-independent pre-normalization position wins")
    (is (contains? (set (:nodes plan)) [:organization :alias]))
    (is (some #(and (= [:organization :alias] (:node %))
                    (= :self-permission (:rule %)))
              (:rules plan))
        "alias nodes and reachability remain in the semantic graph")
    (is (= (:order-certificate (:execution-frontier plan))
           (mapv :ordinal (get-in plan [:execution-frontier :rules]))))))

(deftest alias-resolution-stop-conditions-property-test
  (let [self-alias (fn [permission target]
                     [(permission-row :organization permission :self
                                      :permission target)])
        relation-body (fn [permission]
                        [(permission-row :organization permission :self
                                         :relation :member)])]
    (testing "arbitrary finite exact-alias chains resolve to their terminal"
      (doseq [length (range 1 17)]
        (let [names (mapv #(keyword (str "alias-" %)) (range length))
              terminal :base
              bodies
              (into {[:organization terminal] (relation-body terminal)}
                    (map-indexed
                     (fn [index permission]
                       [[:organization permission]
                        (self-alias permission
                                    (if (= index (dec length))
                                      terminal
                                      (nth names (inc index))))])
                     names))
              result (sealed-plan/resolve-pure-alias
                      bodies [:organization (first names)])]
          (is (= [:organization terminal] (:target result)))
          (is (:changed? result))
          (is (= :relation-dependent (:stop result))))))
    (testing "missing evidence never authorizes a rewrite"
      (is (= {:target [:organization :alias]
              :changed? false
              :stop :missing}
             (sealed-plan/resolve-pure-alias
              {} [:organization :alias]))))
    (testing "a composite terminal stops without erasing its alias prefix"
      (let [bodies
            {[:organization :alias] (self-alias :alias :composite)
             [:organization :composite]
             [(permission-row :organization :composite :self
                              :relation :member)
              (permission-row :organization :composite :self
                              :relation :admin)]}]
        (is (= {:target [:organization :composite]
                :changed? true
                :stop :composite}
               (sealed-plan/resolve-pure-alias
                bodies [:organization :alias])))))
    (testing "a relation-dependent body is not an alias"
      (is (= {:target [:organization :base]
              :changed? false
              :stop :relation-dependent}
             (sealed-plan/resolve-pure-alias
              {[:organization :base] (relation-body :base)}
              [:organization :base]))))
    (testing "cycles return the original target"
      (let [bodies {[:organization :a] (self-alias :a :b)
                    [:organization :b] (self-alias :b :a)}]
        (is (= {:target [:organization :a]
                :changed? false
                :stop :cycle}
               (sealed-plan/resolve-pure-alias
                bodies [:organization :a])))))))

(deftest provider-row-permutation-does-not-change-alias-frontier-test
  (let [forward (alias-plan false)
        reversed (alias-plan true)]
    (is (= forward reversed))
    (is (= (:fingerprint forward) (:fingerprint reversed)))
    (is (= (get-in forward [:execution-frontier :order-certificate])
           (get-in reversed [:execution-frontier :order-certificate])))))

(deftest fingerprint-changes-only-when-the-alias-frontier-changes-test
  (let [canonical (alias-plan)
        reference
        (with-redefs [sealed-plan/resolve-pure-alias
                      (fn [_ target]
                        {:target target :changed? false :stop :disabled})]
          (alias-plan))
        recomputed (sealed-plan/plan-fingerprint
                    (dissoc canonical :fingerprint))]
    (is (= recomputed (:fingerprint canonical)))
    (is (not= (:fingerprint reference) (:fingerprint canonical)))
    (is (nil? (:execution-frontier reference)))
    (is (some? (:execution-frontier canonical)))
    (is (not=
         recomputed
         (sealed-plan/plan-fingerprint
          (-> canonical
              (dissoc :fingerprint)
              (assoc-in [:execution-frontier :normalization]
                        :mutated-alias-contract))))
        "the frontier compatibility record is a real fingerprint input")))

(deftest one-rank-contract-drives-execution-metadata-and-fingerprint-test
  (let [baseline (alias-plan)
        mutated-contract
        (assoc-in sealed-plan/rank-contract
                  [:local-read-costs :relation] 7)
        mutated
        (with-redefs [sealed-plan/rank-contract mutated-contract]
          (alias-plan))
        relation-ranks
        (fn [plan]
          (mapv :rank (filter #(= :relation (:rule %)) (:rules plan))))]
    (is (= (:local-read-costs sealed-plan/rank-contract)
           (get-in baseline [:order-contract :rank-costs])))
    (is (= (:local-read-costs mutated-contract)
           (get-in mutated [:order-contract :rank-costs])))
    (is (not= (relation-ranks baseline) (relation-ranks mutated)))
    (is (not= (:fingerprint baseline) (:fingerprint mutated)))))

(deftest complete-frontier-identity-prevents-over-deduplication-test
  (let [permissions
        (into alias-permissions
              [(permission-row :document :view :backup-organization
                               :permission :alias)])
        relations
        (into alias-relations
              [(relation-row 11 :document :backup-organization
                             :organization)])
        plan (sealed-plan/seal-plan
              (plan-adapter {:permissions permissions
                             :relations relations})
              [:document :view])
        root-rules (get-in plan [:indexes :reverse-rules
                                 [:document :view]])]
    (is (= 2 (count root-rules)))
    (is (= #{10 11} (set (map :via-relation-eid root-rules)))
        "different physical paths remain distinct after target resolution")))

(deftest recursive-alias-graph-is-never-rewritten-test
  (let [permissions
        [(permission-row :document :view :organization :permission :a)
         (permission-row :document :view :organization :permission :b)
         (permission-row :organization :a :self :permission :b)
         (permission-row :organization :b :self :permission :a)]
        plan (sealed-plan/seal-plan
              (plan-adapter {:permissions permissions
                             :relations
                             [(relation-row 10 :document :organization
                                            :organization)]})
              [:document :view])]
    (is (:recursive? plan))
    (is (= :first-discovery (:order-mode plan)))
    (is (nil? (:execution-frontier plan)))
    (is (= 2 (count (get-in plan [:indexes :reverse-rules
                                  [:document :view]]))))))

(deftest missing-arrow-target-still-fails-closed-test
  (let [adapter
        (plan-adapter
         {:permissions
          [(permission-row :document :view :organization
                           :permission :missing)]
          :relations
          [(relation-row 10 :document :organization :organization)]})]
    (try
      (sealed-plan/seal-plan adapter [:document :view])
      (is false "missing target permission must fail plan compilation")
      (catch #?(:clj Exception :cljs :default) error
        (is (= :eacl.plan/compile-error
               (:eacl/error (ex-data error))))))))
