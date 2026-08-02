(ns eacl.relay-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.relay :as relay]
            [eacl.relationships.relay :as relationships-relay]))

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

(deftest relationship-pagination-rejects-nil-cursors-test
  (doseq [query [{:first 1 :after nil}
                 {:last 1 :before nil}]]
    (is (= :eacl.pagination/invalid-cursor
           (try
             (relationships-relay/paginate
              {}
              :read-relationships
              query
              {}
              [])
             nil
             (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
               (:eacl/error (ex-data error)))))
        (pr-str query))))
