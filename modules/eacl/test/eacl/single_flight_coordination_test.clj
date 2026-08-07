(ns eacl.single-flight-coordination-test
  "Wedge-free single-flight regression (single-flight-coordination spec).

  The deterministic schedule below permanently deadlocked the
  pre-fix coordinator (empirically reproduced in the v8 audit): the
  flight body acquired the fair computation semaphore INSIDE the
  delay's lock, flight registration was unbounded, and permit-holding
  threads cross-joined flights whose owners were permit-queued.

  Post-fix (i-b): owners acquire strictly before deref, every flight
  body runs under *computation-owner* (so nested resolves compute
  inline, and a joiner that steals a queued owner's body inherits the
  skip), and permit accounting must balance to zero leaked slots and
  zero lingering flights. JVM-only: the schedule needs real threads."
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.subproblem-cache :as sub])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- await-latch! [^CountDownLatch latch]
  (is (.await latch 5 TimeUnit/SECONDS) "latch must open"))

(defn- coordinator-drained? [store]
  (let [coordinator (:computation-coordinator store)]
    (and (zero? @(:active coordinator))
         (empty? @(:flights coordinator))
         (= (:maximum coordinator)
            (.availablePermits
             ^java.util.concurrent.Semaphore
             (:semaphore coordinator))))))

(deftest wedge-schedule-completes-test
  ;; max-inflight 1; Thread A owns :projection [:p], holds the sole slot
  ;; inside its compute, and cross-joins :denotation [:d]; Thread B owns
  ;; [:d] and (pre-fix) parked in acquire INSIDE [:d]'s delay while
  ;; holding its lock -> cycle {A -> monitor(:d) -> B -> semaphore -> A}.
  ;; Post-fix B parks in acquire holding nothing; A steals [:d]'s body,
  ;; computes it inline, and both complete.
  (let [store (sub/store {:max-inflight 1})
        a-in (CountDownLatch. 1)
        a-go (CountDownLatch. 1)
        b-owner (CountDownLatch. 1)
        a (future
            (:value
             (sub/resolve!
              store :projection [:p] {}
              (fn []
                (.countDown a-in)
                (is (.await a-go 10 TimeUnit/SECONDS))
                (:value
                 (sub/resolve!
                  store :denotation [:d] {}
                  (constantly :d-value)))))))
        _ (await-latch! a-in)
        b (future
            (.countDown b-owner)
            (:value
             (sub/resolve!
              store :denotation [:d] {}
              (constantly :d-value))))]
    (await-latch! b-owner)
    ;; Give B time to register the [:d] flight and park on the slot.
    (Thread/sleep 200)
    (.countDown a-go)
    (testing "both requests complete instead of wedging"
      (is (= :d-value (deref a 5000 :wedged)))
      (is (= :d-value (deref b 5000 :wedged))))
    (testing "no leaked permits, active slots, or lingering flights"
      (is (coordinator-drained? store)))
    (testing "the cross-thread body steal is observable"
      (is (pos? (:stolen-computations (sub/stats store)))
          "A stole B's queued [:d] body (or vice versa) exactly once"))))

(deftest randomized-nested-soak-test
  ;; 16 threads x 1000 mixed resolve!/lookup! ops over 8 denotation
  ;; roots whose computes each resolve 4 shared projection chunks plus a
  ;; proof-shaped key, max-inflight 2. Any wedge fails the deadline;
  ;; accounting must balance afterward.
  (let [store (sub/store {:max-inflight 2})
        threads 16
        ops-per-thread 1000
        completed (atom 0)
        worker
        (fn [t]
          (dotimes [n ops-per-thread]
            (let [root (mod (+ t n) 8)]
              (if (zero? (mod n 7))
                (sub/lookup! store :denotation [:root root] {})
                (sub/resolve!
                 store :denotation [:root root] {}
                 (fn []
                   (dotimes [c 4]
                     (sub/resolve!
                      store :projection [:chunk root c] {}
                      (constantly [c])))
                   (sub/resolve!
                    store :projection [:managed-proof root] {}
                    (constantly {:stamp root}))
                   (vec (range root)))))
              (swap! completed inc))))
        futures (mapv #(future (worker %)) (range threads))]
    (testing "all workers finish within the deadline (no wedge)"
      (doseq [f futures]
        (is (not= :timed-out (deref f 60000 :timed-out)))))
    (is (= (* threads ops-per-thread) @completed))
    (testing "permit and flight accounting balance after the storm"
      (is (coordinator-drained? store)))))
