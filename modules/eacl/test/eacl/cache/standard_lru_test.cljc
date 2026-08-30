(ns eacl.cache.standard-lru-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [eacl.cache.standard-lru :as lru]
            [eacl.exact-integer :as exact-integer]))

(defn- invalid-config?
  [value]
  (try
    (lru/store value)
    false
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo) error
      (let [data (ex-data error)
            nan? #?(:clj (and (number? value) (Double/isNaN (double value)))
                    :cljs (js/Number.isNaN value))]
        (and (= {:type :eacl/invalid-config
                 :eacl/error :eacl/invalid-config
                 :option :max-entries
                 :maximum exact-integer/maximum}
                (dissoc data :value))
             (if nan?
               #?(:clj (Double/isNaN (double (:value data)))
                  :cljs (js/Number.isNaN (:value data)))
               (= value (:value data))))))))

(deftest capacity-is-a-positive-cross-runtime-safe-integer-test
  (doseq [capacity [1 17 exact-integer/maximum]]
    (let [store (lru/store capacity)]
      (is (lru/store? store))
      (is (= capacity (:max-entries store)))
      (is (zero? (lru/entry-count store)))))
  (doseq [capacity
          (concat [nil false "1" 0 -1
                   (inc exact-integer/maximum)]
                  #?(:clj [1.0 1/2 ##NaN ##Inf ##-Inf]
                     :cljs [0.5 js/NaN js/Infinity js/-Infinity]))]
    (is (invalid-config? capacity) (pr-str capacity))))

(deftest absence-is-distinct-from-nil-and-false-test
  (let [store (lru/store 3)]
    (is (= {:found? false :value nil}
           (lru/lookup! store :missing)))
    (is (true? (lru/put-if-absent! store :nil nil)))
    (is (true? (lru/put-if-absent! store :false false)))
    (is (= {:found? true :value nil}
           (lru/lookup! store :nil)))
    (is (= {:found? true :value false}
           (lru/lookup! store :false)))
    (is (false? (lru/put-if-absent! store :nil :replacement)))
    (is (= {:found? true :value nil}
           (lru/lookup! store :nil)))))

(deftest lookup-refreshes-lru-without-changing-the-held-value-test
  (let [store (lru/store 2)
        held-value {:immutable [:answer 1]}]
    (lru/put-if-absent! store :first held-value)
    (lru/put-if-absent! store :second :second-value)
    (let [held (lru/lookup! store :first)]
      (lru/put-if-absent! store :third :third-value)
      (is (= {:found? true :value held-value} held))
      (is (= {:found? true :value held-value}
             (lru/lookup! store :first)))
      (is (= {:found? false :value nil}
             (lru/lookup! store :second)))
      (is (= {:found? true :value :third-value}
             (lru/lookup! store :third))))))

(deftest publication-peek-does-not-refresh-lru-test
  (let [store (lru/store 2)]
    (lru/put-if-absent! store :first :first-value)
    (lru/put-if-absent! store :second :second-value)
    (is (= {:found? true :value :first-value}
           (lru/peek-entry store :first)))
    (lru/put-if-absent! store :third :third-value)
    (is (= {:found? false :value nil}
           (lru/lookup! store :first)))
    (is (= {:found? true :value :second-value}
           (lru/lookup! store :second)))
    (is (= {:found? true :value :third-value}
           (lru/lookup! store :third)))))

(deftest conditional-hit-touches-only-the-peeked-identity-test
  (let [store (lru/store 2)
        first-value (assoc {} :revision 1)
        equal-replacement (assoc {} :revision 1)]
    (is (= first-value equal-replacement))
    (is (not (identical? first-value equal-replacement)))
    (lru/put-if-absent! store :first first-value)
    (lru/put-if-absent! store :second :second)
    (is (false? (lru/hit-if-value! store :first equal-replacement))
        "an equal object not obtained from the resident peek cannot touch")
    (lru/put-if-absent! store :third :third)
    (is (= {:found? false :value nil} (lru/lookup! store :first)))
    (is (= {:found? true :value :second} (lru/lookup! store :second))))
  (let [store (lru/store 2)
        first-value (assoc {} :revision 1)]
    (lru/put-if-absent! store :first first-value)
    (lru/put-if-absent! store :second :second)
    (let [peeked (:value (lru/peek-entry store :first))]
      (is (true? (lru/hit-if-value! store :first peeked))))
    (lru/put-if-absent! store :third :third)
    (is (= {:found? true :value first-value} (lru/lookup! store :first)))
    (is (= {:found? false :value nil} (lru/lookup! store :second))))
  (let [store (lru/store 2)
        old-value (assoc {} :revision 1)
        equal-new-value (assoc {} :revision 1)]
    (lru/put-if-absent! store :key old-value)
    (let [peeked (:value (lru/peek-entry store :key))]
      ;; Model eviction/reinsertion between semantic eligibility and the touch.
      (lru/evict! store :key)
      (lru/put-if-absent! store :key equal-new-value)
      (is (false? (lru/hit-if-value! store :key peeked)))
      (let [retried (:value (lru/peek-entry store :key))]
        (is (identical? equal-new-value retried))
        (is (true? (lru/hit-if-value! store :key retried)))))))

(deftest repeated-hot-key-survives-cold-key-churn-test
  (let [store (lru/store 3)]
    (doseq [key [:hot :warm :cold]]
      (lru/put-if-absent! store key key))
    (dotimes [index 100]
      (is (= {:found? true :value :hot}
             (lru/lookup! store :hot)))
      (lru/put-if-absent! store [:churn index] index))
    (is (= {:found? true :value :hot}
           (lru/lookup! store :hot)))
    (is (= 3 (lru/entry-count store)))))

(deftest equal-keys-never-overwrite-the-resident-value-test
  (let [store (lru/store 2)
        first-key (with-meta [:equal :key] {:identity :first})
        equal-key (with-meta [:equal :key] {:identity :second})]
    (is (not (identical? first-key equal-key)))
    (is (= first-key equal-key))
    (is (true? (lru/put-if-absent! store first-key :first-value)))
    (is (false? (lru/put-if-absent! store equal-key :second-value)))
    (is (= {:found? true :value :first-value}
           (lru/lookup! store equal-key)))
    (is (= 1 (lru/entry-count store)))))

(deftest existing-key-publication-does-not-count-as-lru-use-test
  (let [store (lru/store 2)]
    (lru/put-if-absent! store :first :original)
    (lru/put-if-absent! store :second :second)
    (is (false? (lru/put-if-absent! store :first :replacement)))
    (lru/put-if-absent! store :third :third)
    (is (= {:found? false :value nil}
           (lru/lookup! store :first)))
    (is (= {:found? true :value :second}
           (lru/lookup! store :second)))
    (is (= {:found? true :value :third}
           (lru/lookup! store :third)))))

(deftest expected-value-replacement-is-atomic-and-lru-fresh-test
  (let [store (lru/store 2)
        first-value {:revision 1}]
    (lru/put-if-absent! store :first first-value)
    (lru/put-if-absent! store :second {:revision 2})
    (is (false? (lru/replace-if! store :missing nil {:revision 3})))
    (is (false? (lru/replace-if! store :first
                                 {:revision 0}
                                 {:revision 3})))
    (is (= {:found? true :value {:revision 1}}
           (lru/lookup! store :first)))
    (is (true? (lru/replace-if!
                store :first
                (:value (lru/peek-entry store :first))
                {:revision 3})))
    (is (= {:found? true :value {:revision 3}}
           (lru/lookup! store :first)))
    (lru/put-if-absent! store :third {:revision 4})
    (is (= {:found? false :value nil}
           (lru/lookup! store :second)))
    (is (= {:found? true :value {:revision 3}}
           (lru/lookup! store :first)))
    (is (= {:found? true :value {:revision 4}}
           (lru/lookup! store :third)))))

(deftest entries-support-sequential-restore-and-clear-test
  (let [source (lru/store 4)]
    (doseq [[key value] [[:a nil] [:b false] [:c 3] [:d {:answer 4}]]]
      (lru/put-if-absent! source key value))
    (let [snapshot (lru/entries source)
          restored (lru/store 4)]
      (is (vector? snapshot))
      (is (= #{[:a nil] [:b false] [:c 3] [:d {:answer 4}]}
             (set snapshot)))
      (doseq [[key value] snapshot]
        (is (true? (lru/put-if-absent! restored key value))))
      (is (= (set snapshot) (set (lru/entries restored))))
      (is (= 4 (lru/entry-count restored)))
      (is (nil? (lru/clear! restored)))
      (is (empty? (lru/entries restored)))
      (is (zero? (lru/entry-count restored))))))

(deftest eviction-is-explicit-and-idempotent-test
  (let [store (lru/store 1)]
    (lru/put-if-absent! store :key false)
    (is (true? (lru/evict! store :key)))
    (is (false? (lru/evict! store :key)))
    (is (= {:found? false :value nil}
           (lru/lookup! store :key)))))

(deftest storage-never-invokes-a-loader-or-validator-test
  (let [store (lru/store 2)
        computations (atom 0)
        validations (atom 0)
        compute (fn [] (swap! computations inc) :computed)
        value (compute)]
    (is (= 1 @computations))
    (is (true? (lru/put-if-absent! store :key value)))
    (let [{:keys [found? value]} (lru/lookup! store :key)]
      (is found?)
      (is (= :computed value))
      (is (= :valid
             ((fn [candidate]
                (swap! validations inc)
                (if (= :computed candidate) :valid :invalid))
              value))))
    (is (= 1 @computations))
    (is (= 1 @validations))))

#?(:clj
   (deftest lookup-contention-retries-only-library-transformations-test
     (let [store (lru/store 3)
           _ (lru/put-if-absent! store :held :held-value)
           entered (promise)
           release (promise)
           operation-thread (atom nil)
           transform-attempts (atom 0)
           validations (atom 0)
           block-first? (atom true)
           _ (set-validator!
              (:state store)
              (fn [_]
                (when (identical? @operation-thread (Thread/currentThread))
                  (swap! transform-attempts inc)
                  (when (compare-and-set! block-first? true false)
                    (deliver entered true)
                    @release))
                true))
           lookup-future
           (future
             (reset! operation-thread (Thread/currentThread))
             (lru/lookup! store :held))]
       (try
         (is (= true (deref entered 5000 ::timeout)))
         ;; Change the atom after lookup! read it, forcing swap! to retry its
         ;; pure has?/hit transform when the first CAS cannot succeed.
         (lru/put-if-absent! store :contender :contender-value)
         (deliver release true)
         (let [{:keys [found? value] :as result}
               (deref lookup-future 5000 ::timeout)]
           (is found?)
           (is (= {:found? true :value :held-value} result))
           (is (= :held-value
                  ((fn [candidate]
                     (swap! validations inc)
                     candidate)
                   value))))
         (is (= 2 @transform-attempts))
         (is (= 1 @validations))
         (finally
           (deliver release true)
           (set-validator! (:state store) nil)
           (future-cancel lookup-future))))))

#?(:clj
   (deftest publication-contention-never-repeats-computation-test
     (let [store (lru/store 2)
           computations (atom 0)
           entered (promise)
           release (promise)
           operation-thread (atom nil)
           transform-attempts (atom 0)
           block-first? (atom true)
           completed-value ((fn []
                              (swap! computations inc)
                              {:completed true}))
           _ (set-validator!
              (:state store)
              (fn [_]
                (when (identical? @operation-thread (Thread/currentThread))
                  (swap! transform-attempts inc)
                  (when (compare-and-set! block-first? true false)
                    (deliver entered true)
                    @release))
                true))
           publication
           (future
             (reset! operation-thread (Thread/currentThread))
             (lru/put-if-absent! store :answer completed-value))]
       (try
         (is (= true (deref entered 5000 ::timeout)))
         ;; Replacing the immutable policy value forces the publication CAS
         ;; loop to retry, but completed-value was computed before the loop.
         (lru/clear! store)
         (deliver release true)
         (is (true? (deref publication 5000 false)))
         (is (= 2 @transform-attempts))
         (is (= 1 @computations))
         (is (= {:found? true :value {:completed true}}
                (lru/lookup! store :answer)))
         (finally
           (deliver release true)
           (set-validator! (:state store) nil)
           (future-cancel publication))))))

#?(:cljs
   (deftest cljs-operation-instrumentation-keeps-callbacks-outside-cache-test
     (let [store (lru/store 2)
           transitions (atom [])
           computations (atom 0)
           validations (atom 0)
           completed-value ((fn []
                              (swap! computations inc)
                              :completed))]
       (add-watch (:state store) ::transitions
                  (fn [_ _ before after]
                    (swap! transitions conj [before after])))
       (is (true? (lru/put-if-absent! store :key completed-value)))
       (reset! transitions [])
       (let [{:keys [found? value]} (lru/lookup! store :key)]
         (is found?)
         (is (= :completed value))
         (is (true?
              ((fn [candidate]
                 (swap! validations inc)
                 (= :completed candidate))
               value))))
       (is (= 1 (count @transitions)))
       (is (not (identical? (ffirst @transitions)
                            (second (first @transitions)))))
       (is (= 1 @computations))
       (is (= 1 @validations))
       (remove-watch (:state store) ::transitions))))
