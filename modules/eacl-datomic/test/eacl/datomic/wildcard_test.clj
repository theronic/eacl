(ns eacl.datomic.wildcard-test
  (:require [clojure.test :refer [deftest is]]
            [datomic.api :as d]
            [eacl.cache :as cache]
            [eacl.caveats.jvm :as cel]
            [eacl.core :as eacl]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]
            [eacl.wildcard-contract-support :as contract]))

(defn- seed!
  [conn ids]
  @(d/transact conn (mapv (fn [id] {:eacl/id id}) ids)))

(defn- seed-objects!
  [conn]
  (seed! conn contract/objects))

(deftest datomic-wildcard-contract-test
  (with-mem-conn [conn schema/v8-schema]
    (seed-objects! conn)
    (contract/assert-wildcard-contract!
     (datomic/make-client conn {:security-key "datomic-wildcard-test00000000000"})
     #(seed! conn %))))

(deftest datomic-wildcard-speculative-contract-test
  (with-mem-conn [conn schema/v8-schema]
    (seed-objects! conn)
    (contract/assert-wildcard-speculative-contract!
     (datomic/make-client conn {:security-key "datomic-wildcard-test00000000000"}))))

(deftest datomic-wildcard-without-cache-test
  (with-mem-conn [conn schema/v8-schema]
    (seed-objects! conn)
    (contract/assert-wildcard-contract!
     (datomic/make-client conn {:security-key "datomic-wildcard-test00000000000"
                                :cache cache/no-cache})
     #(seed! conn %))))

(deftest datomic-wildcard-caveat-contract-test
  (with-mem-conn [conn schema/v8-schema]
    (seed-objects! conn)
    (let [now-ms (atom 1790000000000)]
      (contract/assert-wildcard-caveat-contract!
       (datomic/make-client conn {:security-key "datomic-wildcard-test00000000000"
                                  :caveat-evaluator (cel/evaluator)
                                  :clock #(deref now-ms)})
       now-ms))))

(deftest datomic-installs-wildcard-attributes-on-first-wildcard-schema-test
  ;; A database installed before wildcard support lacks the two additive
  ;; Relation attributes until its first wildcard schema write.
  (with-mem-conn [conn (filterv #(not (contains? #{:eacl.relation/wildcard-caveats
                                                    :eacl.relation/allows-unqualified-wildcard?}
                                                  (:db/ident %)))
                                schema/v8-schema)]
    (is (nil? (d/entid (d/db conn) :eacl.relation/allows-unqualified-wildcard?)))
    (seed-objects! conn)
    (let [client (datomic/make-client conn {:security-key "datomic-wildcard-test00000000000"})]
      (eacl/write-schema! client contract/wildcard-schema)
      (is (some? (d/entid (d/db conn) :eacl.relation/allows-unqualified-wildcard?)))
      (eacl/create-relationship! client (contract/->user "*") :viewer (contract/->area "a1"))
      (is (true? (eacl/can? client (contract/->user "alice") :view (contract/->area "a1")))))))
