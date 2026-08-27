(ns eacl.consistency-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [eacl.backend.v8 :as backend]
            [eacl.causal-token :as causal-token]
            [eacl.consistency :as consistency]
            [eacl.spicedb.consistency :as public-consistency]))

(def format-options
  {:current-kid :test
   :keyring {:test (vec (range 32))}
   :token-ttl-seconds 60})

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
           error
      (ex-data error))))

(deftest public-consistency-descriptors-reject-unknown-fields-test
  (doseq [descriptor
          [(assoc
            (public-consistency/at-least-as-fresh "token")
            :unexpected true)
           (assoc
            (public-consistency/at-exact-snapshot "token")
            :unexpected true)]]
    (let [failure
          (error-data #(public-consistency/descriptor descriptor))]
      (is (= :eacl/unsupported-consistency (:type failure)))
      (is (= (:type failure) (:eacl/error failure)))
      (is (= descriptor (:consistency failure))))))

(deftest public-consistency-has-four-canonical-modes-test
  (is (= {:mode :minimize-latency}
         (public-consistency/descriptor nil)))
  (is (= {:mode :minimize-latency}
         (public-consistency/descriptor
          public-consistency/minimize-latency)))
  (is (= {:mode :fully-consistent}
         (public-consistency/descriptor
          public-consistency/fully-consistent)))
  (doseq [removed-mode [:local-snapshot :synchronized-head]]
    (let [failure
          (error-data #(public-consistency/descriptor removed-mode))]
      (is (= :eacl/unsupported-consistency (:type failure)))
      (is (= (:type failure) (:eacl/error failure)))
      (is (= removed-mode (:consistency failure))))))

(deftest selected-basis-token-uses-only-closed-basis-identity-test
  (let [basis
        {:backend :test
         :source-id "source"
         :branch nil
         :source-lifecycle "test-lifecycle"
         :basis-kind :ordinary
         :revision 41
         :exact-locator 41
         :backend-snapshot-id {:database-id :test :basis-t 41}}
        adapter-reads (atom {})
        issued
        (binding [backend/*backend-op-stats* adapter-reads]
          (consistency/selected-basis-token
           basis {:format-options format-options}))
        payload (causal-token/token-data format-options issued)]
    (is (= 41 (:revision payload)))
    (is (= 41 (:exact-locator payload)))
    (is (= "source" (:source-id payload)))
    (is (= "test-lifecycle" (:source-lifecycle payload)))
    (is (empty? @adapter-reads))))
