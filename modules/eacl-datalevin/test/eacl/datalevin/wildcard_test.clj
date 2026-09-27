(ns eacl.datalevin.wildcard-test
  (:require [clojure.test :refer [deftest]]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.caveats.jvm :as cel]
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
                 options)))
      (finally
        (d/close conn)
        (u/delete-files dir)))))

(deftest datalevin-wildcard-contract-test
  (with-client {} contract/assert-wildcard-contract!))

(deftest datalevin-wildcard-caveat-contract-test
  (let [now-ms (atom 1790000000000)]
    (with-client {:caveat-evaluator (cel/evaluator) :clock #(deref now-ms)}
      #(contract/assert-wildcard-caveat-contract! % now-ms))))
