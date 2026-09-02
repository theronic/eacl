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
  ;; A failed request-owned build never enters the completed-value slot. The
  ;; next caller retries independently; success stays memoized.
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

(defn- independent-wave
  [request-count call]
  (let [ready (java.util.concurrent.CountDownLatch. request-count)
        release (java.util.concurrent.CountDownLatch. 1)
        builds (java.util.concurrent.atomic.AtomicLong.)
        workers
        (mapv
         (fn [index]
           (future
             (call
              (fn []
                (.incrementAndGet builds)
                (.countDown ready)
                (.await release)
                {:build index}))))
         (range request-count))]
    (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS)
        "every miss enters its own builder before any builder completes")
    (is (= request-count (.get builds)))
    (.countDown release)
    (mapv #(deref % 5000 ::timed-out) workers)))

(deftest concurrent-derived-misses-build-independently-test
  (let [slot (atom nil)
        results (independent-wave
                 8 #(engine/memoized-derived! slot %))]
    (is (= (set (map #(hash-map :build %) (range 8))) (set results))
        "publication losers use their own completed immutable values")
    (is (contains? (set results)
                   (engine/memoized-derived!
                    slot #(throw (ex-info "warm slot rebuilt" {})))))))

(deftest concurrent-sealed-plan-misses-never-share-a-delay-test
  (let [root [:document :view]
        schema-cache (unstamped-request-cache)
        request-count 8
        ready (java.util.concurrent.CountDownLatch. request-count)
        release (java.util.concurrent.CountDownLatch. 1)
        builds (java.util.concurrent.atomic.AtomicLong.)]
    (with-redefs [engine/permission-relationship-eids (fn [& _] [])
                  sealed-plan/seal-plan
                  (fn [& _]
                    (let [build (.getAndIncrement builds)]
                      (.countDown ready)
                      (.await release)
                      {:rules [] :build build}))]
      (let [workers
            (mapv (fn [_]
                    (future
                      (binding [engine/*schema-cache* schema-cache]
                        (engine/stable-plan :db root))))
                  (range request-count))]
        (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS)
            "all sealed-plan misses build before any publication")
        (is (= request-count (.get builds)))
        (.countDown release)
        (let [results (mapv #(deref % 5000 ::timed-out) workers)]
          (is (= (set (map #(hash-map :rules [] :build %)
                           (range request-count)))
                 (set results)))
          (binding [engine/*schema-cache* schema-cache]
            (is (contains? (engine/stable-plan :db root) :build))))))))
