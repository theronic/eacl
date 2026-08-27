(ns eacl.request.counters-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.request.counters :as counters]))

(deftest request-counter-ledger-is-complete-and-observation-only
  (let [ledger (counters/make-ledger)
        result
        (counters/call-with-ledger
         ledger
         (fn []
           (counters/add! :acquisitions)
           (counters/add! :commands 3)
           :semantic-result))]
    (is (= :semantic-result result))
    (is (= (set counters/counter-keys)
           (set (keys (counters/snapshot ledger)))))
    (is (= 1 (:acquisitions (counters/snapshot ledger))))
    (is (= 3 (:commands (counters/snapshot ledger))))
    (is (zero? (:publications (counters/snapshot ledger))))))

(deftest request-counter-validation-is-typed
  (testing "an unknown counter cannot silently fork the ledger schema"
    (let [error
          (try
            (counters/add! :not-a-counter)
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
              error))]
      (is (= :eacl.request/unknown-counter
             (:eacl/error (ex-data error))))))
  (testing "increments are natural numbers"
    (let [error
          (try
            (counters/add! :commands -1)
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
              error))]
      (is (= :eacl.request/invalid-counter-increment
             (:eacl/error (ex-data error)))))))

(deftest request-counter-deltas-cannot-go-backwards
  (let [before (assoc (counters/empty-counts) :commands 2)
        after (assoc before :commands 5)]
    (is (= 3 (:commands (counters/delta before after))))
    (is (thrown-with-msg?
         #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
         #"cannot move backwards"
         (counters/delta after before)))))
