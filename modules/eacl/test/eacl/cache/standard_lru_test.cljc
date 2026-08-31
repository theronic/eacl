(ns eacl.cache.standard-lru-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [eacl.cache.standard-lru :as lru]
            [eacl.exact-integer :as exact-integer])
  #?(:clj
     (:import [com.github.benmanes.caffeine.cache Cache Policy$Eviction])))

#?(:clj
   (defn- eviction-order
     [store]
     (let [^Cache storage (:state store)
           ^Policy$Eviction eviction (.get (.eviction (.policy storage)))]
       (.cleanUp storage)
       (vec (.keySet (.coldest eviction (:max-entries store)))))))

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

(deftest lookup-holds-value-across-concurrent-policy-eviction-test
  (let [store (lru/store 2)
        held-value {:immutable [:answer 1]}]
    (lru/put-if-absent! store :first held-value)
    (lru/put-if-absent! store :second :second-value)
    (let [held (lru/lookup! store :first)]
      (lru/put-if-absent! store :third :third-value)
      (is (= {:found? true :value held-value} held))
      ;; JVM Window TinyLFU may reject the new entry instead of selecting the
      ;; exact strict-LRU victim. Retention policy never changes a held value.
      (is (<= (lru/entry-count store) 2)))))

(deftest publication-peek-does-not-refresh-lru-test
  #?(:clj
     (let [store (lru/store 8)]
       (doseq [key (range 8)]
         (lru/put-if-absent! store key key))
       (let [before (eviction-order store)]
         (is (= {:found? true :value 0} (lru/peek-entry store 0)))
         (is (= before (eviction-order store))
             "a quiet peek does not alter Caffeine policy order")
         (is (= {:found? true :value 0} (lru/lookup! store 0)))
         (is (= 0 (peek (eviction-order store)))
             "an accepted lookup does record policy usage")))
     :cljs
     (let [store (lru/store 2)]
       (lru/put-if-absent! store :first :first-value)
       (lru/put-if-absent! store :second :second-value)
       (is (= {:found? true :value :first-value}
              (lru/peek-entry store :first)))
       (lru/put-if-absent! store :third :third-value)
       (is (= {:found? false :value nil} (lru/lookup! store :first))))))

(deftest conditional-hit-touches-only-the-peeked-identity-test
  (let [store (lru/store 2)
        first-value (assoc {} :revision 1)
        equal-replacement (assoc {} :revision 1)]
    (is (= first-value equal-replacement))
    (is (not (identical? first-value equal-replacement)))
    (lru/put-if-absent! store :first first-value)
    (lru/put-if-absent! store :second :second)
    (let [before #?(:clj (eviction-order store) :cljs nil)]
      (is (false? (lru/hit-if-value! store :first equal-replacement))
          "an equal object not obtained from the resident peek cannot touch")
      #?(:clj
         (is (= before (eviction-order store)))
         :cljs
         (do
           (lru/put-if-absent! store :third :third)
           (is (= {:found? false :value nil}
                  (lru/lookup! store :first)))))))
  (let [store (lru/store 2)
        first-value (assoc {} :revision 1)]
    (lru/put-if-absent! store :first first-value)
    (lru/put-if-absent! store :second :second)
    (let [peeked (:value (lru/peek-entry store :first))]
      (is (true? (lru/hit-if-value! store :first peeked))))
    #?(:clj
       (is (= :first (peek (eviction-order store)))
           "an identity-confirmed hit records policy usage")
       :cljs
       (do
         (lru/put-if-absent! store :third :third)
         (is (= {:found? true :value first-value}
                (lru/lookup! store :first)))))
    (is (= 2 (lru/entry-count store))))
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
  #?(:clj
     (let [store (lru/store 16)]
       (doseq [key (range 15)]
         (is (true? (lru/put-if-absent! store [:cold key] key))))
       (is (true? (lru/put-if-absent! store :hot :hot)))
       ;; Settle the initial writes, then interleave a genuine hot workload with
       ;; one-hit candidates as Caffeine's buffered policy expects in practice.
       (is (= 16 (lru/entry-count store)))
       (dotimes [candidate 100]
         (dotimes [_ 10]
           (lru/lookup! store :hot))
         (lru/put-if-absent! store [:candidate candidate] candidate))
       (is (= {:found? true :value :hot} (lru/lookup! store :hot)))
       (is (= 16 (lru/entry-count store))))
     :cljs
     (let [store (lru/store 3)]
       (doseq [key [:hot :warm :cold]]
         (lru/put-if-absent! store key key))
       (dotimes [index 100]
         (is (= {:found? true :value :hot}
                (lru/lookup! store :hot)))
         (lru/put-if-absent! store [:churn index] index))
       (is (= {:found? true :value :hot}
              (lru/lookup! store :hot)))
       (is (= 3 (lru/entry-count store))))))

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
  #?(:clj
     (let [store (lru/store 8)]
       (doseq [key (range 8)]
         (lru/put-if-absent! store key key))
       (let [before (eviction-order store)]
         (is (false? (lru/put-if-absent! store 0 :replacement)))
         (is (= before (eviction-order store))
             "a sequential losing publication does not alter policy order")
         (is (= {:found? true :value 0} (lru/lookup! store 0)))))
     :cljs
     (let [store (lru/store 2)]
       (lru/put-if-absent! store :first :original)
       (lru/put-if-absent! store :second :second)
       (is (false? (lru/put-if-absent! store :first :replacement)))
       (lru/put-if-absent! store :third :third)
       (is (= {:found? false :value nil} (lru/lookup! store :first))))))

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
    #?(:clj
       (is (= :first (peek (eviction-order store)))
           "a successful replacement records fresh policy usage")
       :cljs
       (do
         (lru/put-if-absent! store :third {:revision 4})
         (is (= {:found? true :value {:revision 3}}
                (lru/lookup! store :first)))))
    (is (= 2 (lru/entry-count store)))))

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
   (deftest concurrent-lookups-return-the-held-immutable-value-test
     (let [store (lru/store 64)
           held-value {:completed (vec (range 64))}
           _ (lru/put-if-absent! store :held held-value)
           start (java.util.concurrent.CountDownLatch. 1)
           readers
           (mapv
            (fn [_]
              (future
                (.await start)
                (loop [remaining 20000]
                  (if (zero? remaining)
                    true
                    (let [{:keys [found? value]} (lru/lookup! store :held)]
                      (if (and found? (identical? held-value value))
                        (recur (dec remaining))
                        false))))))
            (range 8))]
       (.countDown start)
       (is (every? true? (map #(deref % 10000 ::timeout) readers)))
       (is (= 1 (lru/entry-count store))))))

#?(:clj
   (deftest concurrent-publication-computes-independently-and-never-overwrites-test
     (let [publisher-count 16
           store (lru/store 32)
           computations (atom 0)
           ready (java.util.concurrent.CountDownLatch. publisher-count)
           start (java.util.concurrent.CountDownLatch. 1)
           publishers
           (mapv
            (fn [publisher]
              (future
                ;; The completed value exists before entering cache storage.
                (let [completed-value
                      ((fn []
                         (swap! computations inc)
                         {:publisher publisher :completed true}))]
                  (.countDown ready)
                  (.await start)
                  {:published?
                   (lru/put-if-absent! store :answer completed-value)
                   :value completed-value})))
            (range publisher-count))]
       (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
       (.countDown start)
       (let [results (mapv #(deref % 10000 ::timeout) publishers)
             winners (filterv :published? results)
             resident (:value (lru/lookup! store :answer))]
         (is (= publisher-count @computations))
         (is (= 1 (count winners)))
         (is (identical? (:value (first winners)) resident))
         (is (= 1 (lru/entry-count store)))))))

#?(:clj
   (deftest concurrent-conditional-replacement-has-one-identity-winner-test
     (let [replacement-count 16
           store (lru/store 8)
           original {:revision 1}
           _ (lru/put-if-absent! store :answer original)
           expected (:value (lru/peek-entry store :answer))
           ready (java.util.concurrent.CountDownLatch. replacement-count)
           start (java.util.concurrent.CountDownLatch. 1)
           replacements
           (mapv
            (fn [revision]
              (future
                (let [replacement {:revision revision}]
                  (.countDown ready)
                  (.await start)
                  {:replaced?
                   (lru/replace-if! store :answer expected replacement)
                   :value replacement})))
            (range 2 (+ 2 replacement-count)))]
       (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
       (.countDown start)
       (let [results (mapv #(deref % 10000 ::timeout) replacements)
             winners (filterv :replaced? results)
             resident (:value (lru/lookup! store :answer))]
         (is (= 1 (count winners)))
         (is (identical? (:value (first winners)) resident))
         (is (= 1 (lru/entry-count store)))))))

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
