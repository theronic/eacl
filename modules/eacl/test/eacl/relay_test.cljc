(ns eacl.relay-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]
            [eacl.relay :as relay]))

(defn- operation-map
  [snapshot-id proof]
  (merge
   (into {}
         (map (fn [operation]
                [operation (fn [& _] nil)]))
         backend/required-snapshot-operations)
   {:snapshot-id (constantly snapshot-id)
    :source-scope (constantly {:source :relay-test})
    :graph-head
    (constantly
     {:graph-anchor snapshot-id
      :order-hint snapshot-id
      :exact-locator snapshot-id})
    :schema-proof (fn [& _] proof)
    :relation-proof (fn [_] proof)}))

(defn- adapter
  [snapshot-id proof deterministic?]
  (backend/make-adapter
   {:id :relay-test
    :capabilities {}
    :fingerprint {:adapter :relay-test}
    :deterministic? deterministic?
    :operations (operation-map snapshot-id proof)}))

(def dependencies
  {:schema-scope {:permission-nodes [[:document :view]]
                  :relation-ids [1]}
   :relation-ids [1]})

(deftest cursor-proof-identity-test
  (testing "even equal dependency proofs cannot lift across revisions"
    (let [first-context
          (relay/dependency-context
           (adapter 1 {:digest "same"} true)
           dependencies)
          later-context
          (relay/dependency-context
           (adapter 2 {:digest "same"} true)
           dependencies)]
      (is (not= (:proof-digest first-context)
                (:proof-digest later-context)))))

  (testing "the same exact snapshot has a stable identity"
    (let [first-context
          (relay/dependency-context (adapter 1 nil true) dependencies)
          later-context
          (relay/dependency-context (adapter 1 {:different "proof"} true)
                                    dependencies)]
      (is (= (:proof-digest first-context)
             (:proof-digest later-context)))))

  (testing "adapter determinism cannot enable cross-revision lifting"
    (let [first-context
          (relay/dependency-context
           (adapter 1 {:digest "same"} false)
           dependencies)
          later-context
          (relay/dependency-context
           (adapter 2 {:digest "same"} false)
           dependencies)]
      (is (not= (:proof-digest first-context)
                (:proof-digest later-context))))))

(deftest one-page-builds-one-snapshot-context-test
  (let [graph-head-calls (atom 0)
        snapshot-id-calls (atom 0)
        test-adapter
        (backend/make-adapter
         {:id :relay-test
          :capabilities {}
          :fingerprint {:adapter :relay-test}
          :deterministic? true
          :operations
          (merge
           (operation-map 1 nil)
           {:graph-head
            (fn []
              (swap! graph-head-calls inc)
              {:graph-anchor 1
               :order-hint 1
               :exact-locator 1})
            :snapshot-id
            (fn []
              (swap! snapshot-id-calls inc)
              1)
            :internal-id->object str})})
        edge {:kind :relationship-index
              :v 1
              :scan-index 0
              :subject-id 10
              :resource-id 20}
        page
        {:data
         [(eacl/->Relationship
           (eacl/spice-object :user 10)
           :reader
           (eacl/spice-object :document 20))]
         :page-info
         {:start-cursor edge
          :end-cursor edge
          :has-next-page? false
          :has-previous-page? false}}
        external
        (relay/externalize-relationship-page
         test-adapter {} :read-relationships
         {:subject/type :user :first 1}
         page)]
    (is (= 1 @graph-head-calls))
    (is (= 1 @snapshot-id-calls))
    (is (string? (get-in external [:page-info :start-cursor])))
    (is (string? (get-in external [:page-info :end-cursor])))))
