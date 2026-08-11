(ns eacl.datomic.contract-test
  (:require [clojure.test :refer [deftest is]]
            [datomic.api :as d]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]))

(defn- seed-objects!
  [conn]
  @(d/transact conn
     (mapv (fn [{:keys [id]}]
             {:db/id id
              :eacl/id id})
       contract/smoke-objects)))

(deftest removed-cache-coherence-options-are-unknown-test
  (with-mem-conn [conn schema/v7-schema]
    (doseq [[option values]
            [[:coherence-authority [:unknown :managed]]
             [:proof-mode [:auto :mutation :content :none]]]
            value values]
      (let [error
            (try
              (datomic/make-client conn {option value})
              nil
              (catch clojure.lang.ExceptionInfo cause
                (ex-data cause)))]
        (is (= :eacl/invalid-config (:type error)))
        (is (= [option] (:unknown-keys error)))))))

(deftest datomic-contract-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (datomic/make-client conn {:page-token-key "datomic-contract-test"})]
      (eacl/write-schema! client contract/smoke-schema)
      (seed-objects! conn)
      (eacl/create-relationships! client contract/smoke-relationships)
      (contract/assert-v8-seeded-contracts! client)
      (contract/assert-unified-filter-validation! client))))

(deftest datomic-recursive-contract-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client
          (datomic/make-client
           conn
           {:page-token-key "datomic-recursive-contract-test"
            :cache {:remember-answers true}})]
      (eacl/write-schema! client contract/recursive-schema)
      @(d/transact conn
                   (mapv (fn [{:keys [id]}]
                           {:db/id id
                            :eacl/id id})
                         contract/recursive-objects))
      (eacl/create-relationships! client contract/recursive-relationships)
      (contract/assert-v8-recursive-contracts! client)
      (doseq [limit-key [:max-derived-grants
                         :max-advanced-datoms
                         :max-queued-work]]
        (contract/assert-v8-recursive-safety-limit!
         (datomic/make-client
          conn
          {:page-token-key
           (str "datomic-recursive-safety-" (name limit-key))
           :cache cache/no-cache
           :recursive-traversal-limits {limit-key 1}}))))))
