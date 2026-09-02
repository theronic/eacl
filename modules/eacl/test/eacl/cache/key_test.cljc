(ns eacl.cache.key-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.cache.key :as cache-key]))

(def ^:private base-identity
  {:tier :answer
   :source-lifecycle
   {:backend :datascript :source-id :tenant-a :source-lifecycle 7}
   :abi
   {:engine 8 :schema 3 :adapter :portable :value 2}
   :semantic
   {:operation :lookup-resources
    :query {:resource-type :document :permission :view}}
   :reuse
   {:basis-kind :current
    :revision 19
    :backend-snapshot-id {:basis 19}}})

(deftest domain-key-is-versioned-and-opaque
  (is (= [:eacl.cache/key-v2 :continuation [:tenant-a 7 :cursor-3]]
         (cache-key/domain-key :continuation [:tenant-a 7 :cursor-3])))
  (doseq [bad-domain [nil "continuation" 1]
          :let [error (try
                        (cache-key/domain-key bad-domain [:identity])
                        nil
                        (catch #?(:clj Throwable :cljs :default) error
                          error))]]
    (is (= :eacl/invalid-cache-key (:type (ex-data error)))))
  (is (thrown? #?(:clj Throwable :cljs :default)
               (cache-key/domain-key :continuation nil))))

(deftest authorization-constructors-separate-domain-and-answer-reuse-mode
  (let [exact-answer (cache-key/exact-answer-key base-identity)
        managed-answer
        (cache-key/managed-answer-key
         (assoc base-identity :reuse
                {:schema-generation 3
                 :dependency-identity [[1 17] [4 19]]
                 :dependency-stamp 19}))
        exact-subproblem
        (cache-key/exact-denotation-key
         (assoc base-identity :tier :denotation))]
    (is (= :eacl.cache/key-v2 (first exact-answer)))
    (is (= 6 (count (nth exact-answer 2))))
    (is (= :exact (second (nth exact-answer 2))))
    (is (= :managed (second (nth managed-answer 2))))
    (is (= :authorization-answer (second exact-answer)))
    (is (= :authorization-subproblem (second exact-subproblem)))
    (is (= 3 (count #{exact-answer managed-answer exact-subproblem})))))

(deftest every-authorization-dimension-affects-full-equality
  (let [base (cache-key/exact-answer-key base-identity)]
    (doseq [[field replacement]
            [[:tier :denotation]
             [:source-lifecycle
              {:backend :datascript :source-id :tenant-a :source-lifecycle 8}]
             [:abi {:engine 9 :schema 3 :adapter :portable :value 2}]
             [:semantic {:operation :lookup-resources
                         :query {:resource-type :document
                                 :permission :edit}}]
             [:reuse {:basis-kind :current
                      :revision 20
                      :backend-snapshot-id {:basis 20}}]]]
      (is (not= base
                (cache-key/exact-answer-key
                 (assoc base-identity field replacement)))
          (name field)))))

(deftest authorization-shape-fails-closed
  (doseq [invalid
          [(dissoc base-identity :abi)
           (assoc base-identity :unknown true)
           (-> base-identity (dissoc :abi) (assoc :unknown :abi))
           (assoc base-identity :semantic nil)
           (assoc base-identity :tier "answer")]]
    (let [error (try
                  (cache-key/exact-answer-key invalid)
                  nil
                  (catch #?(:clj Throwable :cljs :default) error error))]
      (is (= :eacl/invalid-cache-key (:type (ex-data error)))
          (pr-str invalid)))))
