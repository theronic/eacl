(ns eacl.datascript.adapter-certification-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.adapter-certification :as certification]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]))

(defn- seed-adapter
  [fixture]
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client (:schema fixture))
    (ds/transact!
     conn
     (map-indexed
      (fn [index {:keys [id]}]
        {:db/id (- (inc index))
         :eacl/id id})
      (:objects fixture)))
    (eacl/create-relationships! client (:relationships fixture))
    (let [db (ds/db conn)]
      (datascript-backend/snapshot-adapter
       db
       {:object-id->entid
        (fn [snapshot object-id]
          (ds/entid snapshot [:eacl/id object-id]))
        :entid->object-id
        (fn [snapshot internal-id]
          (:eacl/id (ds/entity snapshot internal-id)))
        :conn conn
        :coherence-authority :managed
        :proof-mode :content}))))

(deftest datascript-adapter-certification-test
  (doseq [fixture (certification/coherent-fixtures [820084])]
    (testing (str "seed " (:seed fixture))
      (let [report
            (certification/certify
             {:adapter (seed-adapter fixture)
              :fixture fixture
              :runtime #?(:clj :clj :cljs :cljs)})]
        (is (:passed? report)
            (pr-str (:checks report)))))))

(deftest current-db-reference-identity-test
  (testing "the exact-current cache can use immutable DB object identity"
    (let [conn (datascript/create-conn)
          before-1 (ds/db conn)
          before-2 (ds/db conn)
          _ (ds/transact!
             conn
             [{:eacl/id "datascript-reference-identity"}])
          after-1 (ds/db conn)
          after-2 (ds/db conn)]
      (is (identical? before-1 before-2)
          "an unchanged connection must return the same immutable DB object")
      (is (not (identical? before-1 after-1))
          "a committed transaction must replace the immutable DB object")
      (is (identical? after-1 after-2)
          "the replacement remains stable until the next commit"))))
