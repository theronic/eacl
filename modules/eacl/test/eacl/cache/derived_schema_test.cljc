(ns eacl.cache.derived-schema-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.cache.derived-schema :as derived]
            [eacl.cache.key :as cache-key]
            [eacl.cache.standard-lru :as standard-lru]
            [eacl.exact-integer :as exact-integer]))

(defn- cache-identity
  ([] (cache-identity 7))
  ([schema-generation]
   {:abi {:engine 8 :derived :v1}
    :source {:backend :test
             :source-id :source-a
             :branch nil
             :source-lifecycle "lifecycle-a"}
    :adapter {:backend :test
              :fingerprint {:adapter :test-v1}
              :identity-contract :immutable-v1
              :operator-capability {:mode :scalar}}
    :schema-generation schema-generation}))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (ex-data error))))

(def accept-any-publication
  (constantly true))

(deftest complete-domain-key-separates-every-derived-dimension-test
  (let [store (derived/store 16)
        base (cache-identity)
        key-for
        (fn [candidate-identity artifact semantic]
          (derived/entry-key
           (derived/artifact-partition store candidate-identity artifact)
           semantic))
        baseline (key-for base :sealed-plans [:document :view])]
    (is (= cache-key/key-format (first baseline)))
    (is (= :derived-schema (second baseline)))
    (doseq [candidate
            [(key-for (assoc-in base [:source :source-id] :source-b)
                      :sealed-plans [:document :view])
             (key-for (assoc-in base [:source :branch] :branch-b)
                      :sealed-plans [:document :view])
             (key-for (assoc-in base [:source :source-lifecycle]
                                "lifecycle-b")
                      :sealed-plans [:document :view])
             (key-for (assoc-in base [:adapter :fingerprint]
                                {:adapter :test-v2})
                      :sealed-plans [:document :view])
             (key-for (assoc base :schema-generation 8)
                      :sealed-plans [:document :view])
             (key-for base :permission-paths [:document :view])
             (key-for base :sealed-plans [:document :edit])]]
      (is (not= baseline candidate)))))

(deftest derived-identity-is-closed-and-safe-test
  (let [store (derived/store)]
    (doseq [[candidate expected-part expected-field]
            [[(dissoc (cache-identity) :source) :root nil]
             [(assoc-in (cache-identity) [:source :extra] true) :source nil]
             [(assoc-in (cache-identity) [:adapter :identity-contract] "loose")
              :adapter :identity-contract]
             [(assoc (cache-identity) :schema-generation -1)
              :root :schema-generation]
             [(assoc (cache-identity) :schema-generation
                     (inc exact-integer/maximum))
              :root :schema-generation]]]
      (let [data
            (error-data
             #(derived/artifact-partition
               store candidate :sealed-plans))]
        (is (= :eacl/invalid-cache-key (:type data)))
        (is (= expected-part (:identity-part data)))
        (when expected-field
          (is (= expected-field (:field data))))))))

(deftest standard-lru-retains-a-hot-derived-entry-test
  (let [store (derived/store 2)
        partition
        (derived/artifact-partition store (cache-identity) :sealed-plans)]
    (is (true? (derived/publish!
                partition :hot nil accept-any-publication)))
    (is (true? (derived/publish!
                partition :cold false accept-any-publication)))
    (dotimes [_ 100]
      (is (= {:found? true :value nil}
             (derived/lookup! partition :hot))))
    ;; JVM Caffeine applies buffered policy observations asynchronously;
    ;; stats settles them before testing the resulting admission decision.
    (is (= {:entry-count 2 :max-entries 2}
           (derived/stats store)))
    (is (true? (derived/publish!
                partition :new {:plan :new} accept-any-publication)))
    (is (= {:found? true :value nil}
           (derived/lookup! partition :hot)))
    (is (= {:entry-count 2 :max-entries 2}
           (derived/stats store)))))

(deftest publication-validates-once-and-resident-hits-are-ordinary-test
  (let [store (derived/store 4)
        partition
        (derived/artifact-partition store (cache-identity) :parsed-schema)
        publication-calls (atom 0)]
    (is (true?
         (derived/publish!
          partition :schema {:definitions []}
          #(do (swap! publication-calls inc) (map? %)))))
    (is (= 1 @publication-calls))
    (is (= {:found? true :value {:definitions []}}
           (derived/lookup! partition :schema)))
    (is (= {:found? true :value {:definitions []}}
           (derived/lookup! partition :schema)))
    (is (= 1 @publication-calls)
        "resident hits do not repeat the artifact validator")
    (testing "publication validator failures skip insertion"
      (let [calls (atom 0)]
        (is (false?
             (derived/publish!
              partition :rejected :bad
              (fn [_] (swap! calls inc) false))))
        (is (= 1 @calls))
        (is (= {:found? false :value nil}
               (derived/lookup! partition :rejected)))))
    (testing "publication validator exceptions skip insertion"
      (let [calls (atom 0)]
        (is (false?
             (derived/publish!
              partition :throws :bad
              (fn [_]
                (swap! calls inc)
                (throw (ex-info "invalid" {}))))))
        (is (= 1 @calls))
        (is (= {:found? false :value nil}
               (derived/lookup! partition :throws)))))
    (testing "publication has no implicit trusting validator"
      (is (thrown? #?(:clj clojure.lang.ArityException :cljs js/Error)
                   (apply derived/publish!
                          [partition :unchecked {:plan :unchecked}])))
      (is (= :eacl/invalid-cache-key
             (:type
              (error-data
               #(derived/publish!
                 partition :non-callable {:plan :unchecked} nil)))))
      (is (= {:found? false :value nil}
             (derived/lookup! partition :unchecked)))
      (is (= {:found? false :value nil}
             (derived/lookup! partition :non-callable))))))

(deftest same-key-publication-keeps-the-first-completed-value-test
  (let [store (derived/store 2)
        partition
        (derived/artifact-partition store (cache-identity) :sealed-plans)]
    (is (true? (derived/publish!
                partition :plan {:request :first} map?)))
    (is (false? (derived/publish!
                 partition :plan {:request :second} map?)))
    (is (= {:found? true :value {:request :first}}
           (derived/lookup! partition :plan)))
    (derived/clear! store)
    (is (= {:entry-count 0 :max-entries 2}
           (derived/stats store)))))

(deftest private-store-errors-are-ordinary-derived-misses-test
  (let [store (derived/store 2)
        partition
        (derived/artifact-partition store (cache-identity) :sealed-plans)]
    (with-redefs [standard-lru/lookup!
                  (fn [_ _]
                    (throw (ex-info "lookup failed" {})))
                  standard-lru/put-if-absent!
                  (fn [_ _ _]
                    (throw (ex-info "publication failed" {})))]
      (is (= {:found? false :value nil}
             (derived/lookup! partition :plan)))
      (is (false?
           (derived/publish! partition :plan {:plan :computed} map?))))))
