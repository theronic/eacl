(ns eacl.caveats.evaluator-test
  (:require [eacl.caveats.evaluator :as evaluator]
            [eacl.caveats.values :as values]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(deftest explicit-portable-capability-admission
  (let [calls (atom 0)
        supplied (reify evaluator/Evaluator
                   (descriptor [_] {:profile values/profile-id :capability-version 1
                                     :profile-fingerprint evaluator/profile-fingerprint
                                     :fingerprint "test-only-conforming-implementation"})
                   (-evaluate [_ _ _ _] (swap! calls inc) {:outcome :false}))]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (evaluator/require-matching! nil evaluator/profile-fingerprint)))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                 (evaluator/require-matching! supplied "different-profile")))
    (is (zero? @calls))
    (is (= supplied (evaluator/require-matching! supplied evaluator/profile-fingerprint)))
    (is (= {:outcome :false} (evaluator/evaluate supplied {} {} {})))
    (is (= 1 @calls))
    #?(:cljs (is (nil? (evaluator/default-evaluator))))))

(deftest admission-uses-one-complete-descriptor
  (let [reads (atom 0)
        calls (atom 0)
        admitted {:profile values/profile-id :capability-version 1
                  :profile-fingerprint evaluator/profile-fingerprint
                  :fingerprint "test/one-descriptor"}
        current (atom admitted)
        supplied (reify evaluator/Evaluator
                   (descriptor [_] (swap! reads inc) @current)
                   (-evaluate [_ _ _ _] (swap! calls inc) {:outcome :false}))]
    (is (= {:outcome :false} (evaluator/evaluate supplied {} {} {})))
    (is (= 1 @reads))
    (doseq [[field invalid] [[:profile :other] [:profile-fingerprint "other"]
                             [:capability-version 2] [:fingerprint ""]
                             [:fingerprint nil]]]
      (reset! reads 0)
      (reset! current (assoc admitted field invalid))
      (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
                   (evaluator/evaluate supplied {} {} {})))
      (is (= 1 @reads))
      (is (= 1 @calls)))))
