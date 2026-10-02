(ns eacl.datalevin.wildcard-test
  (:require [clojure.test :refer [deftest]]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.cache :as cache]
            [eacl.datalevin.core :as datalevin]
            [eacl.wildcard-contract-support :as contract]))

(def ^:private test-key "01234567890123456789012345678901")

(defn- with-client
  [options f]
  (let [dir (u/tmp-dir (str "eacl-datalevin-wildcard-" (random-uuid)))
        conn (datalevin/create-conn dir)
        watermark (atom 0)]
    (try
      (d/transact! conn (mapv (fn [id] {:eacl/id id}) contract/objects))
      (f (datalevin/make-client
          conn
          (merge {:source-lifecycle #uuid "8d2b5f3e-9f4e-4a5f-8c3e-1f2a3b4c5d6e"
                  :security-key test-key
                  :revision-watermark watermark
                  :advance-revision-watermark! (fn [revision] (swap! watermark max revision))}
                 options))
         (fn [ids] (d/transact! conn (mapv (fn [id] {:eacl/id id}) ids))))
      (finally
        (d/close conn)
        (u/delete-files dir)))))

(deftest datalevin-wildcard-contract-test
  (with-client {} contract/assert-wildcard-contract!))

(deftest datalevin-wildcard-without-cache-test
  (with-client {:cache cache/no-cache} contract/assert-wildcard-contract!))

(deftest datalevin-wildcard-caveat-contract-test
  (let [now-ms (atom 1790000000000)]
    ;; The Datalevin classpath has no JVM CEL module; core's portable plan
    ;; evaluator serves the same Caveat.
    (with-client {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                  :clock #(deref now-ms)}
      (fn [client _seed!] (contract/assert-wildcard-caveat-contract! client now-ms)))))
