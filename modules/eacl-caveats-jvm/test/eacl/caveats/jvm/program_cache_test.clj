(ns eacl.caveats.jvm.program-cache-test
  (:require [clojure.test :refer [deftest is]]
            [eacl.caveats.jvm.program-cache :as cache])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(deftest bounded-retention-and-failed-build-retry
  (let [store (cache/store {:max-entries 2 :max-builds 1})]
    (doseq [k (range 20)] (is (= k (cache/get-or-build! store k (constantly k)))))
    (is (<= (:entries (cache/stats store)) 2))
    (is (thrown? Exception (cache/get-or-build! store :bad #(throw (ex-info "failed" {})))))
    (is (= :recovered (cache/get-or-build! store :bad (constantly :recovered))))
    (is (= 0 (:in-flight (cache/stats store))))))

(deftest concurrent-miss-coalescing
  (let [store (cache/store {}) entered (promise) release (promise)
        start (CountDownLatch. 12) calls (atom 0)
        jobs (mapv (fn [_] (future (.countDown start) (.await start 10 TimeUnit/SECONDS)
                                   (cache/get-or-build! store :one
                                                        #(do (swap! calls inc) (deliver entered true) @release :program)))) (range 12))]
    (try
      (is (= true (deref entered 10000 :timeout)))
      (finally (deliver release true)))
    (is (= (repeat 12 :program) (mapv #(deref % 10000 :timeout) jobs)))
    (is (= 1 @calls))
    (is (= 1 (:entries (cache/stats store))))))

(deftest distinct-builds-wait-without-changing-results
  (let [store (cache/store {:max-builds 2}) release (promise)
        entered (CountDownLatch. 2) active (atom 0) peak (atom 0)
        jobs (mapv (fn [k] (future (cache/get-or-build! store k
                                                        #(try (let [n (swap! active inc)] (swap! peak max n)
                                                                   (.countDown entered) @release k)
                                                              (finally (swap! active dec)))))) (range 8))]
    (try
      (is (.await entered 10 TimeUnit/SECONDS))
      (is (= 2 (:in-flight (cache/stats store))))
      (finally (deliver release true)))
    (is (= (vec (range 8)) (mapv #(deref % 10000 :timeout) jobs)))
    (is (= 2 @peak))
    (is (= 0 (:in-flight (cache/stats store))))))

(deftest failed-coalesced-build-wakes-every-caller
  (let [store (cache/store {}) entered (promise) release (promise) joined (promise)
        failure (ex-info "expected failure" {})
        build #(do (deliver entered true) @release (throw failure))
        call #(try (cache/get-or-build! store :failure build) (catch Exception e e))
        first-job (future (call))]
    (is (= true (deref entered 10000 :timeout)))
    (add-watch (:counters store) ::joined
               (fn [_ _ _ counters] (when (= 7 (:coalesced counters)) (deliver joined true))))
    (let [waiters (mapv (fn [_] (future (call))) (range 7))]
      (try
        (is (= true (deref joined 10000 :timeout)))
        (finally (deliver release true) (remove-watch (:counters store) ::joined)))
      (is (every? #(identical? failure (deref % 10000 :timeout)) (conj waiters first-job)))
      (is (= :retry (cache/get-or-build! store :failure (constantly :retry))))
      (is (= 0 (:in-flight (cache/stats store)))))))
