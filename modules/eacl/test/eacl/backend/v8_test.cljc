(ns eacl.backend.v8-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.spi :as legacy]
            [eacl.backend.v8 :as backend]
            [eacl.spicedb.consistency :as consistency]))

(defn- operation-map []
  (into {}
        (map (fn [operation]
               [operation (fn [& args] [operation (vec args)])]))
        backend/required-snapshot-operations))

(defn- test-adapter []
  (backend/make-adapter
   {:id :test
    :capabilities {:consistency #{:fully-consistent}
                   :snapshots #{:current}
                   :cursor #{:forward :reverse}
                   :transactions #{}
                   :cache-proofs #{:schema :relations :snapshot-bound}
                   :runtime #{#?(:clj :clj :cljs :cljs)}}
    :operations (operation-map)}))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (ex-data error))))

(deftest validated-v8-adapter-test
  (let [adapter (test-adapter)]
    (is (backend/adapter? adapter))
    (is (= :test (backend/backend-id adapter)))
    (is (backend/supports? adapter :consistency :fully-consistent))
    (is (not (backend/supports? adapter :consistency :at-exact-snapshot)))
    (is (= {:mode :fully-consistent}
           (backend/require-consistency!
            adapter consistency/fully-consistent)))
    (is (= [:schema-proof []]
           (backend/invoke adapter :schema-proof)))
    (testing "unsupported guarantees fail before execution"
      (is (= {:type :eacl/unsupported-capability
              :capability :consistency
              :requested :at-exact-snapshot}
             (select-keys
              (error-data
               #(backend/require-consistency!
                 adapter
                 (consistency/at-exact-snapshot "token")))
              [:type :capability :requested]))))
    (testing "missing optional operations are typed capabilities"
      (is (= {:type :eacl/unsupported-capability
              :capability :operation
              :requested :delete-object-tx}
             (select-keys
              (error-data
               #(backend/invoke adapter :delete-object-tx 1))
              [:type :capability :requested]))))))

(deftest invalid-v8-adapter-test
  (is (= :eacl/invalid-backend-adapter
         (:type
          (error-data
           #(backend/make-adapter
             {:id :broken
              :capabilities {}
              :operations {}})))))
  (is (= :eacl/invalid-backend-adapter
         (:type
          (error-data
           #(backend/make-adapter
             {:id :broken
              :capabilities {:consistency #{:eventually-maybe}}
              :operations (operation-map)}))))))

(deftest legacy-six-function-spi-remains-compatible-test
  (let [calls (atom [])
        implementation
        {:cache-stamp (fn [] (swap! calls conj [:cache-stamp]) :stamp)
         :relation-defs (fn [& args]
                          (swap! calls conj [:relation-defs args])
                          :relations)
         :permission-defs (fn [& args]
                            (swap! calls conj [:permission-defs args])
                            :permissions)
         :subject->resources (fn [& args]
                               (swap! calls conj [:subject->resources args])
                               :resources)
         :resource->subjects (fn [& args]
                               (swap! calls conj [:resource->subjects args])
                               :subjects)
         :direct-match? (fn [& args]
                          (swap! calls conj [:direct-match? args])
                          true)}]
    (is (identical? implementation
                    (backend/validate-legacy-adapter! implementation)))
    (is (= :stamp (legacy/cache-stamp implementation)))
    (is (= :relations (legacy/relation-defs implementation :doc :reader)))
    (is (= :permissions
           (legacy/permission-defs implementation :doc :view)))
    (is (= :resources
           (legacy/subject->resources
            implementation :user 1 2 :doc {:direction :asc})))
    (is (= :subjects
           (legacy/resource->subjects
            implementation :doc 3 2 :user {:direction :desc})))
    (is (true?
         (legacy/direct-match?
          implementation :user 1 2 :doc 3)))
    (is (= [:cache-stamp] (first @calls)))
    (is (= 6 (count @calls)))))
