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
  (is (= [:eacl.cache/key-v3 :continuation [:tenant-a 7 :cursor-3]]
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
    (is (= :eacl.cache/key-v3 (first exact-answer)))
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

(deftest denotation-key-builder-equals-the-constructor
  (let [shared (assoc (dissoc base-identity :semantic) :tier :denotation)
        build (cache-key/exact-denotation-key-builder shared)
        error-type (fn [f]
                     (try (f) nil
                          (catch #?(:clj Throwable :cljs :default) error
                            (:type (ex-data error)))))]
    (doseq [semantic [:semantic [:membership-point 1 "plan" :user 7 9] {:k [1 2]}]]
      (is (= (cache-key/exact-denotation-key (assoc shared :semantic semantic))
             (build semantic))))
    (testing "the shared fields are validated once, when the builder is made"
      (is (= :eacl/invalid-cache-key
             (error-type #(cache-key/exact-denotation-key-builder (dissoc shared :abi)))))
      (is (= :eacl/invalid-cache-key
             (error-type #(cache-key/exact-denotation-key-builder (assoc shared :unknown 1))))))
    (testing "each key still requires its semantic identity"
      (is (= :eacl/invalid-cache-key (error-type #(build nil)))))))
