(ns eacl.datascript.qualifier-cache-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.cache :as answer-cache]
            [eacl.authorization.qualifier-cache :as cache]
            [eacl.authorization.qualification-test :as qualification-fixtures]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.qualified-check-test :as fixtures]
            [eacl.relationships.qualifier :as qualifier]))

(defn- cache-state [client]
  @(get-in client [:runtime :runtime-lifecycle-state]))

(deftest public-decode-reuse-rechecks-unknown-native-writers
  (let [{:keys [conn client now check]} (fixtures/fixture)
        request (assoc check :caveat-context {"flag" true})
        decodes (atom 0) decode qualifier/decode
        qid (:e (first (ds/datoms (ds/db conn) :aevt qualifier/caveat-attribute)))]
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (with-redefs [qualifier/decode (fn [& args] (swap! decodes inc) (apply decode args))]
        (is (true? (eacl/can? client request)))
        (is (= 1 @decodes))
        (reset! now 100)
        (is (true? (eacl/can? client request)))
        (is (= 1 @decodes) "same basis reuses decoded data at a different time")
        (ds/transact! conn [{:db/id [:eacl/id "folder"] :app/noise "unrelated"}])
        (reset! now 101)
        (is (true? (eacl/can? client request)))
        (is (= 1 @decodes) "equal complete content reuses decoding across a native write")
        (ds/transact! conn [[:db/add qid qualifier/expiration-attribute 101]])
        (is (false? (eacl/can? client request)))
        (is (= 2 @decodes) "in-place mutation with unchanged marker and Relation stamp misses")
        (is (= (eacl/can? client request) (eacl/can? client (assoc request :cache? false))))))))

(deftest public-decode-retention-respects-request-and-lifecycle-controls
  (let [{:keys [client now check]} (fixtures/fixture)
        request (assoc check :caveat-context {"flag" true})
        decodes (atom 0) decode qualifier/decode]
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (with-redefs [qualifier/decode (fn [& args] (swap! decodes inc) (apply decode args))]
        (dotimes [_ 2] (is (true? (eacl/can? client (assoc request :populate-cache? false)))))
        (is (= 2 @decodes))
        (is (true? (eacl/can? client request)))
        (is (= 3 @decodes))
        (reset! now 100)
        (is (true? (eacl/can? client (assoc request :populate-cache? false))))
        (is (= 3 @decodes) "read-only cache requests may use a retained decode")
        (is (true? (eacl/can? client (assoc request :cache? false))))
        (is (= 4 @decodes))
        (let [before (:qualifier-decode-cache (cache-state client))]
          (datascript/expire-cache! client "qualifier-lifecycle-reset")
          (is (not (identical? before (:qualifier-decode-cache (cache-state client))))))
        (is (true? (eacl/can? client request)))
        (is (= 5 @decodes))))))

(deftest optional-decode-cache-configuration-is-uniform
  (let [{:keys [conn now check]} (fixtures/fixture)]
    (doseq [options [{:qualifier-cache false} {:cache answer-cache/no-cache}]]
      (let [client (datascript/make-client conn (merge options (hash-map :clock #(deref now) :caveat-evaluator (qualification-fixtures/portable-evaluator (atom 0)))))
            decodes (atom 0) decode qualifier/decode]
        (is (nil? (:qualifier-decode-cache (cache-state client))))
        (binding [orchestration/*qualified-authorization-enabled?* true]
          (with-redefs [qualifier/decode (fn [& args] (swap! decodes inc) (apply decode args))]
            (dotimes [_ 2]
              (swap! now inc)
              (is (true? (eacl/can? client (assoc check :caveat-context {"flag" true} :populate-cache? false)))))
            (is (= 2 @decodes))))))
    (is (cache/cache? (:qualifier-decode-cache (cache-state (datascript/make-client conn {:qualifier-cache {:max-entries 3}})))))))
