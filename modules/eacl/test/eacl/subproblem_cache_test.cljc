(ns eacl.subproblem-cache-test
  #?(:cljs (:require-macros [cljs.test :refer [deftest is testing]]))
  (:require [eacl.subproblem-cache :as subproblem]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test])))

(deftest weighted-tier-isolation-test
  (let [store (subproblem/store
               {:projection-max-weight 5
                :denotation-max-weight 9})]
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
      (is (= 1 (:evictions stats))))))

(deftest failure-invalid-and-oversized-results-are-not-retained-test
  (let [store (subproblem/store {:projection-max-weight 2})]
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (subproblem/resolve!
                  store :projection :throws {}
                  #(throw (ex-info "boom" {})))))
    (is (= :invalid
           (:value
            (subproblem/resolve!
             store :projection :invalid
             {:valid? #(not= :invalid %)}
             (constantly :invalid)))))
    (is (= :large
           (:value
            (subproblem/resolve!
             store :projection :large
             {:weight-fn (constantly 3)}
             (constantly :large)))))
    (is (zero? (get-in (subproblem/stats store)
                       [:tiers :projection :entries])))
    (is (= 1 (:invalid-results (subproblem/stats store))))
    (is (= 1 (:oversized-rejections (subproblem/stats store))))))

(deftest lru-eviction-and-access-log-bound-test
  (let [capacity 32
        store (subproblem/store {:projection-max-weight capacity})]
    (doseq [key (range capacity)]
      (subproblem/resolve!
       store :projection key {} (constantly key)))
    (dotimes [_ 1200]
      (subproblem/lookup! store :projection 0 {}))
    (subproblem/resolve!
     store :projection capacity {} (constantly capacity))
    (let [stats (subproblem/stats store)]
      (is (= capacity (get-in stats [:tiers :projection :entries])))
      (is (= 1 (:evictions stats)))
      (is (= 1 (:eviction-probes stats)))
      (is (= 0 (:value (subproblem/lookup!
                        store :projection 0 {}))))
      (is (<= (get-in stats [:tiers :projection :lru-records]) 1024)))))

(deftest completed-entry-is-validated-once-before-publication-test
  (let [store (subproblem/store)
        validations (atom 0)
        weights (atom 0)
        options {:valid? (fn [value]
                           (swap! validations inc)
                           (integer? value))
                 :weight-fn (fn [_]
                              (swap! weights inc)
                              1)}]
    (is (= 42 (:value (subproblem/resolve!
                       store :denotation :answer options
                       (constantly 42)))))
    (is (= 42 (:value (subproblem/resolve!
                       store :denotation :answer options
                       (constantly 0)))))
    (is (= 42 (:value (subproblem/lookup!
                       store :denotation :answer options))))
    (is (= 1 @validations))
    (is (= 1 @weights))))

(deftest lookup-never-starts-work-test
  (let [store (subproblem/store)
        calls (atom 0)]
    (is (nil? (subproblem/lookup!
               store :denotation :missing {:valid? integer?})))
    (is (zero? @calls))
    (is (= 1 (:lookup-misses (subproblem/stats store))))))

(deftest malformed-private-entry-is-non-answering-test
  (let [store (subproblem/store)]
    (swap! (:state store)
           assoc-in [:projection :entries :malformed]
           {:value :unvalidated :weight 1 :access 0})
    (is (nil? (subproblem/lookup!
               store :projection :malformed {})))
    (is (zero? (get-in (subproblem/stats store)
                       [:tiers :projection :entries])))
    (is (= 1 (:invalid-results (subproblem/stats store))))))

#?(:clj
   (deftest independent-identical-misses-never-wait-test
     (let [store (subproblem/store)
           first-started (promise)
           release-first (promise)
           computations (atom 0)
           first-work
           (future
             (subproblem/resolve-independent!
              store :answer :same-key {}
              (fn []
                (swap! computations inc)
                (deliver first-started true)
                @release-first
                :first)))
           _ @first-started
           second-work
           (future
             (subproblem/resolve-independent!
              store :answer :same-key {}
              (fn []
                (swap! computations inc)
                :second)))
           second-result (deref second-work 1000 ::timed-out)]
       (is (not= ::timed-out second-result))
       (is (= :second (:value second-result)))
       (deliver release-first true)
       (is (= :first (:value @first-work)))
       (is (= 2 @computations))
       (is (= 1 (:publication-races (subproblem/stats store))))
       (is (= :second
              (:value (subproblem/lookup!
                       store :answer :same-key {})))))))

#?(:clj
   (deftest lifecycle-detachment-prevents-late-publication-test
     (let [store (subproblem/store)
           old-started (promise)
           release-old (promise)
           old-work
           (future
             (subproblem/resolve-independent!
              store :projection :same {}
              (fn []
                (deliver old-started true)
                @release-old
                :old)))]
       @old-started
       (subproblem/clear! store)
       (is (= :new
              (:value
               (subproblem/resolve-independent!
                store :projection :same {} (constantly :new)))))
       (deliver release-old true)
       (is (= :old (:value @old-work)))
       (is (= :detached
              (get-in @old-work [:publication :reason])))
       (is (= :new
              (:value
               (subproblem/lookup! store :projection :same {}))))
       (is (= 1 (:detached-publications (subproblem/stats store)))))))

(deftest managed-reuse-is-complete-proof-scoped-test
  (let [managed (subproblem/store)
        dependency-stamp (atom 10)
        computations (atom 0)
        resolve-in-generation
        (fn [exact computed]
          (binding [subproblem/*store* exact
                    subproblem/*managed-store* managed
                    subproblem/*managed-scope* {:source-id :primary}
                    subproblem/*managed-key-fn*
                    (fn [_]
                      {:schema-stamp 7
                       :dependency-stamp @dependency-stamp})]
            (:value
             (subproblem/resolve-layered-bound!
              :projection :probe {:valid? integer?} :relation/member
              (fn []
                (swap! computations inc)
                computed)))))]
    (is (= 42 (resolve-in-generation (subproblem/store) 42)))
    (is (= 42 (resolve-in-generation (subproblem/store) 0)))
    (is (= 1 @computations))
    (reset! dependency-stamp 11)
    (is (= 99 (resolve-in-generation (subproblem/store) 99)))
    (is (= 2 @computations))))

(deftest unknown-coordination-options-are-rejected-test
  (let [data
        (try
          (subproblem/store {:max-inflight 1})
          nil
          (catch #?(:clj Exception :cljs js/Error) error
            (ex-data error)))]
    (is (= :eacl/invalid-config (:type data)))
    (is (= [:max-inflight] (:unknown-keys data)))))
