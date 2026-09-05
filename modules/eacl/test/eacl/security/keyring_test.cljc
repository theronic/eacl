(ns eacl.security.keyring-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.string :as string]
            [eacl.core :as eacl]
            [eacl.secure-format :as secure]
            [eacl.security.keyring :as ring]
            [eacl.security.protocols :as protocols]))

(defn material [n] (vec (repeat 32 n)))
(defn controller [] (eacl/security-keyring {:keys {:a (material 1)} :active-kid :a}))
(defn outcome [f]
  (try {:value (f)}
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error
         (select-keys (ex-data error) [:type :reason :status]))))

(defn diagnostics [f]
  (try (f) nil
       (catch #?(:clj Throwable :cljs :default) error
         {:data (ex-data error)
          :printed #?(:clj (pr-str (Throwable->map error))
                      :cljs (str error " " (pr-str (ex-data error))))})))

(deftest diagnostics-do-not-retain-input-material-or-conversion-causes
  (let [secret "keyring-canary-material-never-in-diagnostics-0123456789"
        normalized (secure/normalize-key secret)
        fragments [secret (pr-str normalized) (secure/b64url-encode normalized)]
        c (eacl/security-keyring {:keys {:a secret} :active-kid :a})
        calls [#(eacl/security-keyring {:keys {:a secret} :active-kid :missing})
               #(eacl/security-keyring {:keys {:a secret} :active-kid :a :unexpected secret})
               #(eacl/add-security-key! c :b (lazy-seq (throw (ex-info secret {:material secret}))))
               #(eacl/replace-security-keyring! c {:expected-generation 99 :keys {:b secret} :active-kid :b})]]
    (doseq [call calls]
      (let [{:keys [data printed]} (diagnostics call)]
        (is (some? data))
        (is (every? #{:type :eacl/error :reason :status} (keys data)))
        (doseq [fragment fragments] (is (not (string/includes? printed fragment))))))))

(deftest closed-bounded-construction-and-secret-free-errors
  (let [secret "keyring-canary-material-never-in-diagnostics-0123456789"
        bad [{:keys {} :active-kid :a}
             {:keys {:a secret} :active-kid :missing}
             {:keys {:a secret} :active-kid :a :secret secret}
             {:keys {"" secret} :active-kid ""}
             {:keys {(apply str (repeat 1025 "a")) secret} :active-kid :a}
             {:keys {:a (repeat 5000 1)} :active-kid :a}
             {:keys {:a [1 2]} :active-kid :a}
             {:keys {:a (repeat 32 256)} :active-kid :a}
             {:keys {:a (repeat 32 1.5)} :active-kid :a}
             {:keys {:a secret} :active-kid :a :max-keys 0}
             {:keys {:a secret} :active-kid :a :max-retired-kids 65537}
             {:keys {:a secret :b secret} :active-kid :a :max-keys 1}]]
    (doseq [input bad]
      (let [result (outcome #(eacl/security-keyring input))]
        (is (= :eacl.keyring/invalid (:type result)))
        (is (not (string/includes? (pr-str result) secret)))))
    (let [c (eacl/security-keyring {:keys {:a secret} :active-kid :a})]
      (is (eacl/security-keyring? c))
      (is (= "#<EACL SecurityKeyring>" (pr-str c)))
      (is (= {:generation 0 :active-kid :a :accepted-kids #{:a} :retired-kids #{}}
             (eacl/security-keyring-status c))))))

(deftest separate-install-activation-retirement-and-no-id-revival
  (let [c (controller)]
    (is (= 1 (:generation (eacl/add-security-key! c :b (material 2)))))
    (is (= :a (:active-kid (eacl/security-keyring-status c))))
    (is (= 1 (:generation (eacl/add-security-key! c :b (material 2)))))
    (is (= :key-id-reuse (:reason (outcome #(eacl/add-security-key! c :b (material 3))))))
    (is (= :active-key-retirement (:reason (outcome #(eacl/retire-security-key! c :a)))))
    (is (= 2 (:generation (eacl/activate-security-key! c :b))))
    (is (= 2 (:generation (eacl/activate-security-key! c :b))))
    (is (= {:generation 3 :active-kid :b :accepted-kids #{:b} :retired-kids #{:a}}
           (eacl/retire-security-key! c :a)))
    (is (= 3 (:generation (eacl/retire-security-key! c :a))))
    (doseq [value [(material 1) (material 3)]]
      (is (= :retired-key-id (:reason (outcome #(eacl/add-security-key! c :a value))))))
    (is (= :unknown-key-id (:reason (outcome #(eacl/retire-security-key! c :missing)))))
    (is (= :active-key-unavailable (:reason (outcome #(eacl/activate-security-key! c :missing)))))
    (is (= 3 (:generation (eacl/security-keyring-status c))))))

(deftest complete-replacement-is-atomic-guarded-and-bounded
  (let [c (eacl/security-keyring {:keys {:a (material 1)} :active-kid :a :max-retired-kids 1})
        old (protocols/-snapshot c)
        replacement {:expected-generation 0 :keys {:b (material 2)} :active-kid :b}]
    (is (= {:generation 1 :active-kid :b :accepted-kids #{:b} :retired-kids #{:a}}
           (eacl/replace-security-keyring! c replacement)))
    (is (= :a (:active-kid old)) "captured states are immutable across replacement")
    (is (= (material 1) (get-in old [:keys :a])))
    (is (= :eacl.keyring/conflict (:type (outcome #(eacl/replace-security-keyring! c replacement)))))
    (is (= :retired-id-count
           (:reason (outcome #(eacl/replace-security-keyring! c {:expected-generation 1 :keys {:c (material 3)} :active-kid :c})))))
    (is (= 1 (:generation (eacl/security-keyring-status c))))
    (is (= :invalid-generation
           (:reason (outcome #(eacl/replace-security-keyring! c (dissoc replacement :expected-generation))))))))

(defn oracle-step [state [operation kid key]]
  (let [accepted (:keys state) retired (:retired-kids state)
        active (:active-kid state)
        fail (fn [reason] [state reason])
        advance #(-> % (update :generation inc))]
    (case operation
      :add (cond
             (contains? retired kid) (fail :retired-key-id)
             (and (contains? accepted kid) (not= key (get accepted kid))) (fail :key-id-reuse)
             (= key (get accepted kid)) [state nil]
             :else [(advance (assoc-in state [:keys kid] key)) nil])
      :activate (cond
                  (not (contains? accepted kid)) (fail :active-key-unavailable)
                  (= kid active) [state nil]
                  :else [(advance (assoc state :active-kid kid)) nil])
      :retire (cond
                (= kid active) (fail :active-key-retirement)
                (contains? retired kid) [state nil]
                (not (contains? accepted kid)) (fail :unknown-key-id)
                :else [(advance (-> state (update :keys dissoc kid) (update :retired-kids conj kid))) nil]))))

(deftest generated-transitions-match-a-test-only-oracle
  (let [c (controller)
        numbers (rest (iterate #(mod (* 48271 %) 2147483647) 20260905))]
    (loop [state {:generation 0 :keys {:a 1} :active-kid :a :retired-kids #{}}
           commands (take 1000 (partition 3 numbers))]
      (when-let [[a b value] (first commands)]
        (let [operation (nth [:add :activate :retire] (mod a 3))
              kid (nth [:a :b :c :d :e :f :g :h] (mod b 8))
              key (inc (mod value 3))
              [next reason] (oracle-step state [operation kid key])
              result (outcome #(case operation
                                 :add (eacl/add-security-key! c kid (material key))
                                 :activate (eacl/activate-security-key! c kid)
                                 :retire (eacl/retire-security-key! c kid)))]
          (is (= reason (:reason result)))
          (is (= (-> next (dissoc :keys) (assoc :accepted-kids (set (keys (:keys next)))))
                 (eacl/security-keyring-status c)))
          (recur next (rest commands)))))))

#?(:clj
   (deftest concurrent-replacements-have-one-linearization-winner
     (let [c (controller)
           ready (java.util.concurrent.CountDownLatch. 2)
           go (promise)
           update (fn [kid byte]
                    (future (.countDown ready) @go
                            (outcome #(eacl/replace-security-keyring! c
                                                                      {:expected-generation 0 :keys {kid (material byte)} :active-kid kid}))))
           a (update :b 2) b (update :c 3)]
       (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
       (deliver go true)
       (let [results [@a @b]]
         (is (= 1 (count (filter :value results))))
         (is (= [:eacl.keyring/conflict] (keep :type results)))
         (is (= 1 (:generation (eacl/security-keyring-status c))))))))

#?(:clj
   (deftest concurrent-installs-preserve-every-accepted-key
     (let [c (controller)
           go (promise)
           jobs (mapv (fn [n]
                        (future @go (eacl/add-security-key! c (str "epoch-" n) (material (+ 2 n)))))
                      (range 16))]
       (deliver go true)
       (doseq [job jobs] (is (map? (deref job 10000 nil))))
       (is (= 16 (:generation (eacl/security-keyring-status c))))
       (is (= (into #{:a} (map #(str "epoch-" %) (range 16)))
              (:accepted-kids (eacl/security-keyring-status c)))))))

(deftest native-byte-input-is-copied-before-publication
  (let [input #?(:clj (byte-array (repeat 32 (byte 7))) :cljs (js/Uint8Array. (clj->js (repeat 32 7))))
        c (eacl/security-keyring {:keys {:a input} :active-kid :a})]
    #?(:clj (aset-byte input 0 (byte 9)) :cljs (aset input 0 9))
    (is (= (material 7) (get-in (protocols/-snapshot c) [:keys :a])))
    (is (= 0 (:generation (eacl/add-security-key! c :a (material 7)))))))
