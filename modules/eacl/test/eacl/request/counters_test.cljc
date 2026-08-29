(ns eacl.request.counters-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.request.counters :as counters]))

(deftest request-counter-ledger-is-complete-and-exact
  (let [ledger (counters/make-ledger)
        result
        (counters/call-with-ledger
         ledger
         (fn []
           (counters/add! :acquisitions)
           (counters/add! :adapter-reads 2)
           (counters/add! :writer-submissions)
           (counters/add! :commands 3)
           :semantic-result))]
    (is (= :semantic-result result))
    (is (= (set counters/counter-keys)
           (set (keys (counters/snapshot ledger)))))
    (is (= 1 (:acquisitions (counters/snapshot ledger))))
    (is (= 2 (:adapter-reads (counters/snapshot ledger))))
    (is (= 1 (:writer-submissions (counters/snapshot ledger))))
    (is (= 3 (:commands (counters/snapshot ledger))))
    (is (zero? (:publications (counters/snapshot ledger))))))

(deftest preindexed-internal-counters-match-the-checked-path
  (let [ledger (counters/make-ledger)]
    (counters/call-with-ledger
     ledger
     #(do
        (counters/add-adapter-reads!)
        (counters/add-adapter-reads! 2)
        (counters/add-commands!)
        (counters/add-commands! 2)
        (counters/add-fetched-values! 4)
        (counters/add-candidates-examined! 5)
        (counters/add-probes! 6)))
    (is (= {:adapter-reads 3 :commands 3 :fetched-values 4
            :candidates-examined 5 :probes 6}
           (select-keys (counters/snapshot ledger)
                        [:adapter-reads :commands :fetched-values
                         :candidates-examined :probes])))
    (let [data (try (counters/add-commands! -1) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core.ExceptionInfo) error
                      (ex-data error)))]
      (is (= :eacl.request/invalid-counter-increment (:type data)))
      (is (= :commands (:counter data))))))

(deftest ledger-fast-path-preserves-dynamic-binding-semantics
  (let [outer (counters/make-ledger)
        inner (counters/make-ledger)]
    (counters/call-with-ledger
     outer
     #(do
        (counters/add-commands!)
        (binding [counters/*ledger* inner]
          (counters/add-commands!))
        (counters/add-commands!)))
    (is (= 2 (:commands (counters/snapshot outer))))
    (is (= 1 (:commands (counters/snapshot inner)))))
  #?(:clj
     (testing "conveyed bindings fall back correctly on another thread"
       (let [ledger (counters/make-ledger)]
         (counters/call-with-ledger
          ledger
          #(deref (future (counters/add-commands!))))
         (is (= 1 (:commands (counters/snapshot ledger))))))))

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
