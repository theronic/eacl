(ns eacl.datahike.adapter-certification-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.adapter-certification :as certification]
            [eacl.core :as eacl]
            [eacl.datahike.backend :as datahike-backend]
            [eacl.datahike.core :as datahike]))

(defn- seed-adapter
  [fixture config]
  (let [conn (datahike/create-conn nil config)
        client (datahike/make-client conn {})]
    (eacl/write-schema! client (:schema fixture))
    (d/transact
     conn
     (vec
      (map-indexed
       (fn [index {:keys [id]}]
         {:db/id (- (inc index))
          :eacl/id id})
       (:objects fixture))))
    (eacl/create-relationships! client (:relationships fixture))
    (let [db (d/db conn)]
      (datahike-backend/snapshot-adapter
       db
       {:object-id->entid
        (fn [snapshot object-id]
          (:db/id (d/entity snapshot [:eacl/id object-id])))
        :entid->object-id
        (fn [snapshot internal-id]
          (:eacl/id (d/entity snapshot internal-id)))
        :conn conn
        }))))

(deftest datahike-adapter-certification-test
  (doseq [[label config]
          [["attribute keywords" nil]
           ["numeric attribute refs" {:attribute-refs? true}]]
          fixture (certification/coherent-fixtures [820084])]
    (testing (str label ", seed " (:seed fixture))
      (let [report
            (certification/certify
             {:adapter (seed-adapter fixture config)
              :fixture fixture
              :runtime :clj})]
        (is (:passed? report)
            (pr-str (:checks report)))))))

(deftest current-db-reference-identity-test
  (testing "the exact-current cache can use immutable DB object identity"
    (let [conn (datahike/create-conn)
          before-1 (d/db conn)
          before-2 (d/db conn)
          _ (d/transact conn [{:eacl/id "datahike-reference-identity"}])
          after-1 (d/db conn)
          after-2 (d/db conn)]
      (is (identical? before-1 before-2)
          "an unchanged connection must return the same immutable DB object")
      (is (not (identical? before-1 after-1))
          "a committed transaction must replace the immutable DB object")
      (is (identical? after-1 after-2)
          "the replacement remains stable until the next commit"))))
