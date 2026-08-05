(ns eacl.subproblem-cache-test
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require [eacl.subproblem-cache :as subproblem]
            [eacl.verified-kernel :as verified]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test])))

(defrecord ObservingKernel [calls decide-fn]
  verified/DecisionKernel
  (-decide [_ operation input]
    (swap! calls conj [operation input])
    (decide-fn operation input)))

(defn- expected-cache-action
  [_operation {:keys [decision] :as input}]
  (case decision
    :lookup
    (cond
      (:recursive-self? input) :bypass-recursive-self
      (= :missing (:candidate input)) :start-computation
      (= :computing (:candidate input)) :join-computation
      (= :complete (:candidate input)) :use-completed-value
      :else :start-computation)

    :admission
    (cond
      (:candidate-present? input) :join-existing
      (< (:represented-candidates input)
         (:maximum-candidates input)) :admit-computation
      :else :compute-without-admission)

    :publication
    (if (and (:ticket-current? input)
             (:complete? input)
             (:valid? input)
             (pos? (:weight input))
             (<= (:weight input) (:budget input)))
      :retain-publication
      :drop-publication)))

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

(deftest bounded-derived-proof-reuses-complete-managed-denotation-test
  (let [managed (subproblem/store)
        dependency [:relation/member :relation/parent]
        descriptor
        {:schema-stamp [7 "schema-a"]
         :dependency-stamp
         [[10 100 "member-a"]
          [11 101 "parent-a"]]}
        proof-calls (atom 0)
        compute-calls (atom 0)
        options {:valid? vector?
                 :weight-fn #(inc (count %))}
        bind-generation
        (fn [exact f]
          (binding [subproblem/*store* exact
                    subproblem/*managed-store* managed
                    subproblem/*managed-scope* {:source-id :primary}
                    subproblem/*managed-key-fn*
                    (fn [_]
                      (swap! proof-calls inc)
                      descriptor)]
            (f)))]
    (is (= [1 2 3]
           (:value
            (bind-generation
             (subproblem/store)
             #(subproblem/resolve-layered-bound!
               :denotation :closure options dependency
               (fn []
                 (swap! compute-calls inc)
                 [1 2 3]))))))
    (let [next-exact (subproblem/store)
          hit
          (bind-generation
           next-exact
           #(subproblem/lookup-layered-bound!
             :denotation :closure options dependency))]
      (is (= [1 2 3] (:value hit)))
      (is (= :managed-denotation (:cache-tier hit)))
      (is (= 1 (:managed-denotation-hits
                (subproblem/stats next-exact)))))
    (is (= 2 @proof-calls))
    (is (= 1 @compute-calls))
    (testing "an over-bound proof is exact-only without invoking its provider"
      (let [exact
            (subproblem/store {:managed-proof-max-atoms 1})
            before @proof-calls]
        (is (= [9]
               (:value
                (bind-generation
                 exact
                 #(subproblem/resolve-layered-bound!
                   :denotation :over-bound options dependency
                   (fn []
                     (swap! compute-calls inc)
                     [9]))))))
        (is (= before @proof-calls))
        (is (= 1 (:managed-proof-overflows
                  (subproblem/stats exact))))))))

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

(deftest managed-proof-adversarial-boundaries-test
  (let [managed (subproblem/store)
        computations (atom [])
        semantic-key [:direct-probe :user-1 :document-1]
        schema-stamp [7 "schema-a"]
        reader-dependency :relation/reader
        reader-proof-a
        {:schema-stamp schema-stamp
         :dependency-stamp [[101 12 "reader-a"]]}
        reader-proof-b
        {:schema-stamp schema-stamp
         :dependency-stamp [[101 12 "reader-b"]]}
        content-proof-b
        {:schema-stamp schema-stamp
         :dependency-stamp ["content-digest-b"]}
        resolve-generation
        (fn [scope dependency descriptor computed]
          (binding [subproblem/*store* (subproblem/store)
                    subproblem/*managed-store* managed
                    subproblem/*managed-scope* scope
                    subproblem/*managed-key-fn*
                    (constantly descriptor)]
            (:value
             (subproblem/resolve-layered-bound!
              :projection
              semantic-key
              {:valid? keyword?}
              dependency
              (fn []
                (swap! computations conj computed)
                computed)))))]
    (is (= :reader-v1
           (resolve-generation
            {:source-id :primary :branch :main}
            reader-dependency
            reader-proof-a
            :reader-v1)))

    (testing "an unrelated write preserves the complete relation proof"
      (is (= :reader-v1
             (resolve-generation
              {:source-id :primary :branch :main}
              reader-dependency
              reader-proof-a
              :must-not-run)))
      (is (= [:reader-v1] @computations)))

    (testing "the same endpoints under a different relation cannot collide"
      (is (= :auditor-v1
             (resolve-generation
              {:source-id :primary :branch :main}
              :relation/auditor
              reader-proof-a
              :auditor-v1)))
      (is (= [:reader-v1 :auditor-v1] @computations)))

    (testing "delete and recreate must carry a different mutation identity"
      (is (= :reader-v2
             (resolve-generation
              {:source-id :primary :branch :main}
              reader-dependency
              reader-proof-b
              :reader-v2)))
      (is (= [:reader-v1 :auditor-v1 :reader-v2] @computations)))

    (testing "a missing relation stamp is exact-only"
      (is (= :missing-a
             (resolve-generation
              {:source-id :primary :branch :main}
              reader-dependency
              nil
              :missing-a)))
      (is (= :missing-b
             (resolve-generation
              {:source-id :primary :branch :main}
              reader-dependency
              nil
              :missing-b))))

    (testing "branch and restored-source identities are cache boundaries"
      (is (= :feature-branch
             (resolve-generation
              {:source-id :primary :branch :feature}
              reader-dependency
              reader-proof-b
              :feature-branch)))
      (is (= :restored-copy
             (resolve-generation
              {:source-id :restored-copy :branch :main}
              reader-dependency
              reader-proof-b
              :restored-copy))))

    (testing "an unstamped raw write is invisible to a mutation proof"
      (let [before @computations]
        (is (= :reader-v2
               (resolve-generation
                {:source-id :primary :branch :main}
                reader-dependency
                reader-proof-b
                :dishonest-raw-write)))
        (is (= before @computations)
            "mutation-proof reuse requires the managed-writer contract"))
      (is (= :content-v3
             (resolve-generation
              {:source-id :primary :branch :main}
              reader-dependency
              content-proof-b
              :content-v3))
          "a full-content proof detects the same raw graph change"))

    (testing "reset expires the complete managed lifecycle"
      (subproblem/clear! managed)
      (is (= :after-reset
             (resolve-generation
              {:source-id :primary :branch :main}
              reader-dependency
              content-proof-b
              :after-reset))))))

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

(deftest recursive-lookup-of-own-flight-is-a-miss-test
  (let [store (subproblem/store)
        observed (atom :not-called)
        result
        (subproblem/resolve!
         store :denotation :recursive-lookup {}
         (fn []
           (reset!
            observed
            (subproblem/lookup!
             store :denotation :recursive-lookup {}))
           42))]
    (is (= 42 (:value result)))
    (is (nil? @observed))
    (is (= 1 (:self-bypasses (subproblem/stats store))))
    (is (= 1 (:lookup-misses (subproblem/stats store))))
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

(deftest authoritative-lookup-action-precedes-storage-mutation-test
  (let [store (subproblem/store)
        calls (atom [])
        observed-at-lookup (atom nil)
        kernel
        (->ObservingKernel
         calls
         (fn [operation input]
           (when (and (= :subproblem-cache-decision operation)
                      (= :lookup (:decision input)))
             (reset!
              observed-at-lookup
              {:registered-flights
               (:registered-flights (subproblem/stats store))
               :represented-entries
               (get-in (subproblem/stats store)
                       [:tiers :projection :entries])}))
           (expected-cache-action operation input)))
        result
        (binding
         [subproblem/*engine-selection*
          {:mode :verified-authoritative
           :kernel kernel}]
          (subproblem/resolve!
           store :projection :missing {}
           (constantly 42)))]
    (is (= 42 (:value result)))
    (is (false? (:cached? result)))
    (is (= {:registered-flights 0
            :represented-entries 0}
           @observed-at-lookup)
        "the generated lookup action runs before any flight is installed")
    (is (= 0 (:registered-flights (subproblem/stats store)))
        "completed flights are removed")
    (is (= 1 (get-in (subproblem/stats store)
                     [:tiers :projection :entries])))
    (is (= [:lookup :admission :publication]
           (mapv (comp :decision second) @calls)))))

(deftest verified-identical-transitions-are-memoized-per-request-test
  (let [store (subproblem/store)
        calls (atom [])
        kernel (->ObservingKernel calls expected-cache-action)
        selection {:mode :verified-authoritative
                   :kernel kernel}]
    (doseq [key [:first :second]]
      (subproblem/resolve!
       store :projection key {} (constantly key)))
    (binding [subproblem/*engine-selection* selection]
      (subproblem/with-decision-memo
       (fn []
         (is (= :first
                (:value
                 (subproblem/resolve!
                  store :projection :first {} (constantly :wrong)))))
         (is (= :second
                (:value
                 (subproblem/resolve!
                  store :projection :second {} (constantly :wrong))))))))
    (is (= 1 (count @calls))
        "one checked pure lookup transition serves identical states in one request")
    (is (= {:decision :lookup
            :recursive-self? false
            :candidate :complete}
           (second (first @calls))))
    (binding [subproblem/*engine-selection* selection]
      (subproblem/with-decision-memo
       #(subproblem/lookup!
         store :projection :first {})))
    (is (= 2 (count @calls))
        "a later request establishes its own generated decision evidence")))

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
           second-calls (atom 0)
           second-started (promise)
           second-work
           (future
             (:value
              (subproblem/resolve!
               store :projection :second {}
               (fn []
                 (swap! second-calls inc)
                 (deliver second-started true)
                 :second))))]
       (Thread/sleep 20)
       (is (zero? @second-calls))
       (is (= 1 (:inflight-rejections (subproblem/stats store))))
       (is (= 1 (:inflight (subproblem/stats store))))
       (is (= 1 (:active-computations (subproblem/stats store))))
       (is (nil? (subproblem/lookup!
                  store :projection :second {})))
       (deliver release-first true)
       (is (= :first @first-work))
       @second-started
       (is (= :second @second-work))
       (is (= 1 @second-calls))
       (is (= 0 (:inflight (subproblem/stats store))))
       (is (= 0 (:active-computations (subproblem/stats store)))))))

#?(:clj
   (deftest weighted-eviction-cannot-duplicate-a-running-key-test
     (let [store (subproblem/store
                  {:projection-max-weight 1
                   :denotation-max-weight 1
                   :max-inflight 2})
           a-started (promise)
           b-started (promise)
           release-a (promise)
           release-b (promise)
           a-calls (atom 0)
           b-calls (atom 0)
           compute-a
           (fn []
             (swap! a-calls inc)
             (deliver a-started true)
             @release-a
             :a)
           first-a
           (future
             (:value
              (subproblem/resolve!
               store :projection :a {} compute-a)))
           _ @a-started
           b
           (future
             (:value
              (subproblem/resolve!
               store :projection :b {}
               (fn []
                 (swap! b-calls inc)
                 (deliver b-started true)
                 @release-b
                 :b))))
           _ @b-started
           second-a
           (future
             (:value
              (subproblem/resolve!
               store :projection :a {} compute-a)))]
       (loop [attempt 0]
         (when (and (zero? (:single-flight-waits
                            (subproblem/stats store)))
                    (< attempt 1000))
           (Thread/sleep 1)
           (recur (inc attempt))))
       (is (= 1 @a-calls))
       (is (= 1 @b-calls))
       (is (= 2 (:active-computations (subproblem/stats store))))
       (is (= 1 (:inflight (subproblem/stats store))))
       (is (zero? (:evictions (subproblem/stats store))))
       (deliver release-a true)
       (deliver release-b true)
       (is (= :a @first-a))
       (is (= :a @second-a))
       (is (= :b @b))
       (is (= 1 @a-calls))
       (is (= 0 (:active-computations (subproblem/stats store)))))))

#?(:clj
   (deftest cache-unadmitted-fallbacks-still-share-one-flight-test
     (let [store
           (subproblem/store
            {:projection-max-weight 1
             :max-inflight 3})
           releases
           {:a (promise)
            :b (promise)
            :c (promise)}
           starts
           {:a (promise)
            :b (promise)
            :c (promise)}
           calls (atom {:a 0 :b 0 :c 0})
           decisions (atom [])
           kernel
           (->ObservingKernel
            decisions
            expected-cache-action)
           compute
           (fn [k]
             (fn []
               (swap! calls update k inc)
               (deliver (get starts k) true)
               @(get releases k)
               k))
           resolve
           (fn [k]
             (future
               (binding
                [subproblem/*engine-selection*
                 {:mode :verified-authoritative
                  :kernel kernel}]
                 (:value
                  (subproblem/resolve!
                   store :projection k {} (compute k))))))
           a (resolve :a)
           _ @(get starts :a)
           b (resolve :b)
           _ @(get starts :b)
           first-c (resolve :c)
           _ @(get starts :c)
           second-c (resolve :c)]
       (loop [attempt 0]
         (when (and (< (:single-flight-waits
                        (subproblem/stats store))
                       1)
                    (< attempt 1000))
           (Thread/sleep 1)
           (recur (inc attempt))))
       (is (= 1 (:c @calls)))
       (is (= 3 (:active-computations (subproblem/stats store))))
       (is (= 3 (:registered-flights (subproblem/stats store))))
       (is (= 1 (:inflight (subproblem/stats store))))
       (is (= [:missing :missing :missing :computing]
              (->> @decisions
                   (keep
                    (fn [[_ input]]
                      (when (= :lookup (:decision input))
                        (:candidate input))))
                   vec))
           "an unrepresented registered flight is still authoritative computing state")
       (doseq [release (vals releases)]
         (deliver release true))
       (is (= :a @a))
       (is (= :b @b))
       (is (= :c @first-c))
       (is (= :c @second-c))
       (is (= {:a 1 :b 1 :c 1} @calls))
       (is (= 0 (:registered-flights (subproblem/stats store)))))))

#?(:clj
   (deftest weighted-admission-never-evicts-an-inflight-candidate-test
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
       (is (= 2 (:active-computations (subproblem/stats store))))
       (is (= 0 (:evictions (subproblem/stats store))))
       (is (= 1 (get-in (subproblem/stats store)
                        [:tiers :projection :weight])))
       (deliver release-first true)
       (is (= :first @first-work))
       (is (= :first
              (:value
               (subproblem/lookup!
                store :projection :first {}))))
       (deliver release-second true)
       (is (= :second @second-work))
       (is (= 0 (:inflight (subproblem/stats store))))
       (is (= 0 (:active-computations (subproblem/stats store))))
       (is (= 1 (get-in (subproblem/stats store)
                        [:tiers :projection :entries]))))))

#?(:clj
   (deftest clear-starts-a-new-flight-lifecycle-test
     (let [store (subproblem/store {:max-inflight 2})
           old-started (promise)
           new-started (promise)
           release-old (promise)
           old-work
           (future
             (:value
              (subproblem/resolve!
               store :projection :same {}
               (fn []
                 (deliver old-started true)
                 @release-old
                 :old))))
           _ @old-started
           _ (subproblem/clear! store)
           new-work
           (future
             (:value
              (subproblem/resolve!
               store :projection :same {}
               (fn []
                 (deliver new-started true)
                 :new))))]
       @new-started
       (is (= :new @new-work))
       (deliver release-old true)
       (is (= :old @old-work))
       (is (= :new
              (:value
               (subproblem/lookup!
                store :projection :same {}))))
       (is (= 0 (:registered-flights (subproblem/stats store)))))))

#?(:clj
   (deftest new-lifecycle-same-key-still-respects-execution-bound-test
     (let [store (subproblem/store {:max-inflight 1})
           old-started (promise)
           new-started (promise)
           release-old (promise)
           old-work
           (future
             (:value
              (subproblem/resolve!
               store :projection :same {}
               (fn []
                 (deliver old-started true)
                 @release-old
                 :old))))
           _ @old-started
           _ (subproblem/clear! store)
           new-work
           (future
             (:value
              (subproblem/resolve!
               store :projection :same {}
               (fn []
                 (deliver new-started true)
                 :new))))]
       (Thread/sleep 20)
       (is (not (realized? new-started)))
       (is (= 1 (:active-computations (subproblem/stats store))))
       (is (= 2 (:registered-flights (subproblem/stats store))))
       (deliver release-old true)
       (is (= :old @old-work))
       @new-started
       (is (= :new @new-work))
       (is (= 0 (:active-computations (subproblem/stats store))))
       (is (= 0 (:registered-flights (subproblem/stats store)))))))

#?(:clj
   (deftest lifecycle-selection-is-linearized-before-recursive-binding-test
     (let [store (subproblem/store)
           entered-resolve (promise)
           work
           (locking store
             (let [work
                   (future
                     (deliver entered-resolve true)
                     (:value
                      (subproblem/resolve!
                       store :projection :same {}
                       (fn []
                         (:value
                          (subproblem/resolve!
                           store :projection :same {}
                           (constantly :recursive-result)))))))]
               @entered-resolve
               ;; Give the resolver the exact pre-fix opportunity to capture
               ;; the old lifecycle and then block before flight selection.
               (Thread/sleep 50)
               ;; `clear!` is re-entrant on this thread. Once the outer monitor
               ;; is released, selection must use only the new lifecycle for
               ;; both the flight key and the recursive binding.
               (subproblem/clear! store)
               work))]
       (try
         (is (= :recursive-result
                (deref work 2000 ::timed-out))
             "clear cannot split a recursive marker from its flight lifecycle")
         (is (= 0 (:registered-flights (subproblem/stats store))))
         (finally
           (future-cancel work))))))

#?(:clj
   (deftest flight-removal-serializes-with-lifecycle-selection-test
     (let [store (subproblem/store)
           started (promise)
           release (promise)
           returning (promise)
           work
           (future
             (:value
              (subproblem/resolve!
               store :projection :same {}
               (fn []
                 (deliver started true)
                 @release
                 (deliver returning true)
                 :value))))]
       @started
       (locking store
         (deliver release true)
         @returning
         (is (= 1 (:registered-flights (subproblem/stats store)))
             "completion cannot remove the selected flight outside the store linearization lock"))
       (is (= :value (deref work 2000 ::timed-out)))
       (is (= 0 (:registered-flights (subproblem/stats store)))))))

#?(:clj
   (deftest inherited-future-bindings-do-not-inherit-a-computation-slot-test
     (let [store (subproblem/store {:max-inflight 2})
           child-started (promise)
           release-child (promise)
           child-work (atom nil)
           outer
           (future
             (:value
              (subproblem/resolve!
               store :projection :outer {}
               (fn []
                 (let [child
                       (future
                         (:value
                          (subproblem/resolve!
                           store :projection :child {}
                           (fn []
                             (deliver child-started true)
                             @release-child
                             :child))))]
                   (reset! child-work child)
                   @child
                   :outer)))))]
       @child-started
       (is (= 2 (:active-computations (subproblem/stats store)))
           "a child thread inherits dynamic bindings but not its parent's slot")
       (deliver release-child true)
       (is (= :outer @outer))
       (is (= :child @@child-work))
       (is (= 0 (:active-computations (subproblem/stats store)))))))

#?(:clj
   (deftest inherited-same-key-self-bypass-acquires-a-child-slot-test
     (let [store (subproblem/store {:max-inflight 2})
           child-started (promise)
           release-child (promise)
           child-work (atom nil)
           outer
           (future
             (:value
              (subproblem/resolve!
               store :projection :same {}
               (fn []
                 (let [child
                       (future
                         (:value
                          (subproblem/resolve!
                           store :projection :same {}
                           (fn []
                             (deliver child-started true)
                             @release-child
                             :child))))]
                   (reset! child-work child)
                   @child
                   :outer)))))]
       @child-started
       (is (= 2 (:active-computations (subproblem/stats store)))
           "same-key self-bypass on a child thread owns a separate slot")
       (is (= 1 (:registered-flights (subproblem/stats store)))
           "recursive bypass does not create a second shared flight")
       (deliver release-child true)
       (is (= :outer @outer))
       (is (= :child @@child-work))
       (is (= 1 (:self-bypasses (subproblem/stats store))))
       (is (= 0 (:active-computations (subproblem/stats store)))))))
