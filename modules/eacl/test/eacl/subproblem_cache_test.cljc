(ns eacl.subproblem-cache-test
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require [eacl.subproblem-cache :as subproblem]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test])))

(deftest weighted-tier-isolation-test
  (let [store (subproblem/store
               {:projection-max-weight 5
                :denotation-max-weight 9})]
    (testing "projection eviction cannot consume the denotation budget"
      (is (= :p1
             (:value
              (subproblem/resolve!
               store :projection :p1
               {:weight-fn (constantly 4)}
               (constantly :p1)))))
      (is (= :d1
             (:value
              (subproblem/resolve!
               store :denotation :d1
               {:weight-fn (constantly 8)}
               (constantly :d1)))))
      (is (= :p2
             (:value
              (subproblem/resolve!
               store :projection :p2
               {:weight-fn (constantly 4)}
               (constantly :p2)))))
      (let [stats (subproblem/stats store)]
        (is (<= (get-in stats [:tiers :projection :weight]) 5))
        (is (<= (get-in stats [:tiers :denotation :weight]) 9))
        (is (= 1 (get-in stats [:tiers :projection :entries])))
        (is (= 1 (get-in stats [:tiers :denotation :entries])))
        (is (= 1 (:evictions stats)))))))

(deftest failures-and-invalid-values-are-never-admitted-test
  (let [store (subproblem/store)]
    (testing "an exception removes its in-flight candidate"
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (subproblem/resolve!
                    store :projection :throws {}
                    #(throw (ex-info "boom" {})))))
      (is (= 0 (get-in (subproblem/stats store)
                       [:tiers :projection :entries]))))
    #?(:clj
       (testing "a non-Exception throwable cannot poison the candidate"
         (is (thrown? AssertionError
                      (subproblem/resolve!
                       store :projection :throws-error {}
                       #(throw (AssertionError. "boom")))))
         (is (= 0 (get-in (subproblem/stats store)
                          [:tiers :projection :entries])))))
    (testing "an invalid result is returned but not shared"
      (is (= :invalid
             (:value
              (subproblem/resolve!
               store :projection :invalid
               {:valid? #(not= :invalid %)}
               (constantly :invalid)))))
      (is (= 0 (get-in (subproblem/stats store)
                       [:tiers :projection :entries])))
      (is (= 1 (:invalid-results (subproblem/stats store)))))))

(deftest admission-and-publication-keep-weight-continuously-bounded-test
  (let [store (subproblem/store
               {:projection-max-weight 1
                :max-inflight 3})
        _ (subproblem/resolve!
           store :projection :first {}
           (constantly :first))
        second
        (subproblem/resolve!
         store :projection :second
         {:weight-fn (constantly 2)}
         (constantly :second))
        stats (subproblem/stats store)]
    (is (= :second (:value second)))
    (is (<= (get-in stats [:tiers :projection :weight])
            (get-in stats [:tiers :projection :max-weight])))
    (is (= 0 (get-in stats [:tiers :projection :entries])))
    (is (= 1 (:oversized-rejections stats)))
    (is (= 1 (:puts stats)))))

(deftest constant-time-hit-stamp-preserves-lru-eviction-order-test
  (let [store (subproblem/store {:projection-max-weight 2})]
    (doseq [key [:first :second]]
      (subproblem/resolve!
       store :projection key {} (constantly key)))
    (is (= :first
           (:value
            (subproblem/lookup!
             store :projection :first {}))))
    (subproblem/resolve!
     store :projection :third {} (constantly :third))
    (is (nil? (subproblem/lookup!
               store :projection :second {})))
    (is (= :first
           (:value
            (subproblem/lookup!
             store :projection :first {}))))
    (is (= :third
           (:value
            (subproblem/lookup!
             store :projection :third {}))))))

(deftest complete-private-entry-is-structurally-validated-once-test
  (let [store (subproblem/store)
        validations (atom 0)
        weights (atom 0)
        options {:valid? (fn [value]
                           (swap! validations inc)
                           (integer? value))
                 :weight-fn (fn [_]
                              (swap! weights inc)
                              1)}]
    (is (= 42
           (:value
            (subproblem/resolve!
             store :denotation :answer options (constantly 42)))))
    (is (= 42
           (:value
            (subproblem/resolve!
             store :denotation :answer options (constantly 0)))))
    (is (= 42
           (:value
            (subproblem/lookup!
             store :denotation :answer options))))
    (is (= 1 @validations))
    (is (= 1 @weights))))

(deftest managed-projection-reuse-is-relation-stamp-scoped-test
  (let [managed (subproblem/store)
        stamp (atom [[1 10 "mutation-a"]])
        proof-calls (atom 0)
        compute-calls (atom 0)
        options {:valid? integer?}
        resolve-in-generation
        (fn [exact key computed]
          (binding [subproblem/*store* exact
                    subproblem/*managed-store* managed
                    subproblem/*managed-scope* {:source-id :primary}
                    subproblem/*managed-key-fn*
                    (fn [_]
                      (swap! proof-calls inc)
                      {:schema-stamp [7 "schema-a"]
                       :dependency-stamp @stamp})]
            (:value
             (subproblem/resolve-layered-bound!
              :projection key options :relation/member
              (fn []
                (swap! compute-calls inc)
                computed)))))]
    (testing "a new exact generation reuses an unchanged relation portion"
      (is (= 42 (resolve-in-generation (subproblem/store) :probe 42)))
      (let [next-exact (subproblem/store)]
        (is (= 42 (resolve-in-generation next-exact :probe 0)))
        ;; The relation descriptor is itself exact-generation cached and reused
        ;; across distinct projection keys in this request generation.
        (is (= 7 (resolve-in-generation next-exact :other 7)))
        (is (= 2 @proof-calls))
        (is (= 2 @compute-calls))
        (is (= 1
               (:managed-projection-hits
                (subproblem/stats next-exact))))))
    (testing "a new mutation value cannot collide at the same numeric tx"
      (reset! stamp [[1 10 "mutation-b"]])
      (is (= 99
             (resolve-in-generation (subproblem/store) :probe 99)))
      (is (= 3 @compute-calls)))
    (testing "the same numeric stamps from another source cannot collide"
      (binding [subproblem/*store* (subproblem/store)
                subproblem/*managed-store* managed
                subproblem/*managed-scope* {:source-id :restored-copy}
                subproblem/*managed-key-fn*
                (constantly
                 {:schema-stamp [7 "schema-a"]
                  :dependency-stamp @stamp})]
        (is (= 123
               (:value
                (subproblem/resolve-layered-bound!
                 :projection :probe options :relation/member
                 (fn []
                   (swap! compute-calls inc)
                   123))))))
      (is (= 4 @compute-calls)))))

(deftest missing-or-failing-managed-proofs-fall-back-to-exact-computation-test
  (let [managed (subproblem/store)
        calls (atom 0)
        resolve-with
        (fn [provider]
          (binding [subproblem/*store* (subproblem/store)
                    subproblem/*managed-store* managed
                    subproblem/*managed-scope* {:source-id :primary}
                    subproblem/*managed-key-fn* provider]
            (:value
             (subproblem/resolve-layered-bound!
              :projection :probe {:valid? integer?} :relation/member
              (fn []
                (swap! calls inc)
                @calls)))))]
    (is (= 1 (resolve-with (constantly nil))))
    (is (= 2
           (resolve-with
            (fn [_]
              (throw (ex-info "proof unavailable" {}))))))
    (is (= 2 @calls))
    (is (zero? (get-in (subproblem/stats managed)
                       [:tiers :projection :entries])))))

(deftest absent-exact-store-bypasses-managed-state-test
  (let [managed (subproblem/store)
        proof-calls (atom 0)
        compute-calls (atom 0)]
    (binding [subproblem/*store* nil
              subproblem/*managed-store* managed
              subproblem/*managed-scope* {:source-id :primary}
              subproblem/*managed-key-fn*
              (fn [_]
                (swap! proof-calls inc)
                {:schema-stamp 1 :dependency-stamp 1})]
      (is (= 42
             (:value
              (subproblem/resolve-layered-bound!
               :projection :probe {} :relation/member
               (fn []
                 (swap! compute-calls inc)
                 42))))))
    (is (zero? @proof-calls))
    (is (= 1 @compute-calls))
    (is (zero? (get-in (subproblem/stats managed)
                       [:tiers :projection :entries])))))

(deftest recursive-self-wait-bypasses-shared-candidate-test
  (let [store (subproblem/store)
        calls (atom 0)
        result
        (subproblem/resolve!
         store :denotation :recursive {}
         (fn []
           (swap! calls inc)
           (:value
            (subproblem/resolve!
             store :denotation :recursive {}
             (fn []
               (swap! calls inc)
               42)))))]
    (is (= 42 (:value result)))
    (is (= 2 @calls))
    (is (= 1 (:self-bypasses (subproblem/stats store))))
    (is (= 1 (get-in (subproblem/stats store)
                     [:tiers :denotation :entries])))))

(deftest lookup-never-starts-a-missing-computation-test
  (let [store (subproblem/store)]
    (is (nil? (subproblem/lookup!
               store :denotation :missing {:valid? integer?})))
    (is (= 0 (:misses (subproblem/stats store))))
    (is (= 1 (:lookup-misses (subproblem/stats store))))
    (is (= 42
           (:value
            (subproblem/resolve!
             store :denotation :complete
             {:valid? integer?}
             (constantly 42)))))
    (is (= {:value 42
            :cached? true
            :cache-tier :exact-denotation}
           (subproblem/lookup!
            store :denotation :complete {:valid? integer?})))))

#?(:clj
   (deftest concurrent-identical-misses-single-flight-test
     (let [store (subproblem/store)
           calls (atom 0)
           ready (java.util.concurrent.CountDownLatch. 12)
           go (promise)
           started (promise)
           release (promise)
           compute (fn []
                     (swap! calls inc)
                     (deliver started true)
                     @release
                     42)
           workers (mapv (fn [_]
                           (future
                             (.countDown ready)
                             @go
                             (:value
                              (subproblem/resolve!
                               store :projection :shared {} compute))))
                         (range 12))]
       (.await ready)
       (deliver go true)
       @started
       (loop [attempt 0]
         (when (and (< (:hits (subproblem/stats store)) 11)
                    (< attempt 1000))
           (Thread/sleep 1)
           (recur (inc attempt))))
       (deliver release true)
       (is (= (vec (repeat 12 42)) (mapv deref workers)))
       (is (= 1 @calls))
       (is (= 1 (:misses (subproblem/stats store))))
       (is (= 11 (:hits (subproblem/stats store))))
       (is (= 0 (:inflight (subproblem/stats store))))
       (is (= 1 (get-in (subproblem/stats store)
                        [:tiers :projection :weight]))))))

#?(:clj
   (deftest distinct-inflight-work-is-bounded-test
     (let [store (subproblem/store {:max-inflight 1})
           first-started (promise)
           release-first (promise)
           first-work
           (future
             (:value
              (subproblem/resolve!
               store :projection :first {}
               (fn []
                 (deliver first-started true)
                 @release-first
                 :first))))
           _ @first-started
           second-calls (atom 0)]
       (is (= :second
              (:value
               (subproblem/resolve!
                store :projection :second {}
                (fn []
                  (swap! second-calls inc)
                  :second)))))
       (is (= 1 @second-calls))
       (is (= 1 (:inflight-rejections (subproblem/stats store))))
       (is (= 1 (:inflight (subproblem/stats store))))
       (is (nil? (subproblem/lookup!
                  store :projection :second {})))
       (deliver release-first true)
       (is (= :first @first-work))
       (is (= 0 (:inflight (subproblem/stats store)))))))

#?(:clj
   (deftest weighted-admission-may-evict-an-inflight-candidate-safely-test
     (let [store (subproblem/store
                  {:projection-max-weight 1
                   :max-inflight 3})
           first-started (promise)
           second-started (promise)
           release-first (promise)
           release-second (promise)
           first-work
           (future
             (:value
              (subproblem/resolve!
               store :projection :first {}
               (fn []
                 (deliver first-started true)
                 @release-first
                 :first))))
           _ @first-started
           second-work
           (future
             (:value
              (subproblem/resolve!
               store :projection :second {}
               (fn []
                 (deliver second-started true)
                 @release-second
                 :second))))
           _ @second-started]
       (is (= 1 (:inflight (subproblem/stats store))))
       (is (= 1 (:evictions (subproblem/stats store))))
       (is (= 1 (get-in (subproblem/stats store)
                        [:tiers :projection :weight])))
       (deliver release-first true)
       (is (= :first @first-work))
       (is (nil? (subproblem/lookup!
                  store :projection :first {})))
       (deliver release-second true)
       (is (= :second @second-work))
       (is (= 0 (:inflight (subproblem/stats store))))
       (is (= 1 (get-in (subproblem/stats store)
                        [:tiers :projection :entries]))))))
