(ns eacl.datahike.wildcard-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [eacl.cache :as cache]
            [eacl.caveats.jvm :as cel]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.schema :as schema]
            [eacl.datahike.storage :as storage]
            [eacl.wildcard-contract-support :as contract]))

(defn- seed-objects!
  [conn]
  (d/transact conn (vec (map-indexed (fn [index id] {:db/id (- (inc index)) :eacl/id id})
                                     contract/objects))))

(deftest datahike-wildcard-contract-test
  (let [conn (datahike/create-conn)]
    (seed-objects! conn)
    (contract/assert-wildcard-contract! (datahike/make-client conn {}))))

(deftest datahike-wildcard-without-cache-test
  (let [conn (datahike/create-conn)]
    (seed-objects! conn)
    (contract/assert-wildcard-contract!
     (datahike/make-client conn {:cache cache/no-cache}))))

(deftest datahike-wildcard-caveat-contract-test
  (let [conn (datahike/create-conn)
        now-ms (atom 1790000000000)]
    (seed-objects! conn)
    (contract/assert-wildcard-caveat-contract!
     (datahike/make-client conn {:caveat-evaluator (cel/evaluator)
                                 :clock #(deref now-ms)})
     now-ms)))

(defn- conn-without-wildcard-attributes
  "A store installed from the EACL schema as it was before wildcards."
  []
  (let [cfg (-> schema/default-config
                (assoc-in [:store :id] (random-uuid))
                (assoc schema/live-source-id-key (random-uuid)))]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn (filterv #(not (contains? #{:eacl.relation/wildcard-caveats
                                                    :eacl.relation/allows-unqualified-wildcard?}
                                                  (:db/ident %)))
                                schema/datahike-schema))
      (storage/bootstrap! conn)
      conn)))

(deftest datahike-installs-wildcard-attributes-on-first-wildcard-schema-test
  (let [conn (conn-without-wildcard-attributes)]
    (is (nil? (ddb/entid (d/db conn) :eacl.relation/allows-unqualified-wildcard?)))
    (seed-objects! conn)
    (let [client (datahike/make-client conn {})]
      (eacl/write-schema! client contract/wildcard-schema)
      (is (some? (ddb/entid (d/db conn) :eacl.relation/allows-unqualified-wildcard?)))
      (eacl/create-relationship! client (contract/->user "*") :viewer (contract/->area "a1"))
      (is (true? (eacl/can? client (contract/->user "alice") :view (contract/->area "a1")))))))
