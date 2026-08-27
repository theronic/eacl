(ns eacl.engine.schema-generation-reuse-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.v8 :as engine]))

(defn- unstamped-request-cache
  []
  {:schema-version nil
   :request-local? true
   :sealed-plans (atom {})})

(deftest unstamped-values-seal-each-root-once-per-request-test
  (let [stable-plan @#'engine/stable-plan
        seals (atom 0)]
    (with-redefs [engine/permission-relationship-eids
                  (fn [& _] [])
                  sealed-plan/seal-plan
                  (fn [_db _root]
                    (swap! seals inc)
                    {:rules []})]
      (testing "one request-local floor returns one plan instance"
        (binding [engine/*schema-cache* (unstamped-request-cache)]
          (let [first-plan (stable-plan :unstamped [:document :view])
                second-plan (stable-plan :unstamped [:document :view])]
            (is (identical? first-plan second-plan))
            (is (= 1 @seals)))))
      (testing "another request gets an isolated floor and seals once again"
        (binding [engine/*schema-cache* (unstamped-request-cache)]
          (let [next-plan (stable-plan :unstamped [:document :view])]
            (is (some? next-plan))
            (is (= 2 @seals))))))))

(deftest transient-derivation-failure-does-not-poison-the-slot-test
  ;; Review finding F13: a Clojure delay caches a thrown exception, so one
  ;; transient adapter read failure inside a derived-artifact build poisoned
  ;; the slot for the rest of the schema generation. A failed build must
  ;; clear the slot so the next caller retries; success stays memoized.
  (let [slot (atom nil)
        builds (atom 0)
        build (fn []
                (let [attempt (swap! builds inc)]
                  (if (= 1 attempt)
                    (throw (ex-info "transient adapter read failure"
                                    {:attempt attempt}))
                    {:plan :derived :attempt attempt})))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"transient adapter read failure"
         (engine/memoized-derived! slot build)))
    (is (nil? @slot) "the failed delay is cleared for retry")
    (is (= {:plan :derived :attempt 2}
           (engine/memoized-derived! slot build))
        "the next caller retries and succeeds")
    (is (= {:plan :derived :attempt 2}
           (engine/memoized-derived! slot build))
        "success is memoized")
    (is (= 2 @builds))))
