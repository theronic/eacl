(ns eacl.datalevin.safe-retraction-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.core :as eacl]
            [eacl.datalevin.backend :as backend]
            [eacl.datalevin.core :as datalevin]
            [eacl.datalevin.safe-retraction :as safe-retraction]
            [eacl.relationships.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]))

(def ^:private test-key "01234567890123456789012345678901")

(def ^:private logical-schema
  "definition user {}
   definition folder {
     relation viewer: user
     permission view = viewer
   }")

(defn- with-system
  [extra-schema f]
  (let [dir (u/tmp-dir (str "eacl-datalevin-safe-retraction-"
                            (random-uuid)))
        conn (datalevin/create-conn dir extra-schema)
        watermark (atom 0)]
    (try
      (let [client
            (datalevin/make-client
             conn
             {:security-key test-key
              :source-lifecycle "safe-retraction-test"
              :revision-watermark watermark
              :advance-revision-watermark! #(swap! watermark max %)
              :datalevin-topology
              backend/certified-topology-declaration})]
        (eacl/write-schema! client logical-schema)
        (f conn client))
      (finally
        (d/close conn)
        (u/delete-files dir)))))

(defn- halves
  [db eid]
  {:forward (mapv :v (d/datoms db :eav eid storage/forward-attribute))
   :reverse (mapv :v (d/datoms db :eav eid storage/reverse-attribute))})

(deftest direct-safe-retraction-uses-the-transaction-database-schema-test
  (with-system
    {:test/component {:db/valueType :db.type/ref
                      :db/cardinality :db.cardinality/one
                      :db/isComponent true}}
    (fn [conn client]
      (d/transact! conn [{:eacl/id "alice"}
                         {:eacl/id "child"}
                         {:eacl/id "parent"
                          :test/component [:eacl/id "child"]}
                         {:eacl/id "other-folder"}])
      (eacl/create-relationships!
       client
       [(eacl/->Relationship
         (eacl/spice-object :user "alice")
         :viewer
         (eacl/spice-object :folder "child"))
        (eacl/->Relationship
         (eacl/spice-object :user "alice")
         :viewer
         (eacl/spice-object :folder "other-folder"))])
      (let [before (d/db conn)
            parent (d/entid before [:eacl/id "parent"])
            child (d/entid before [:eacl/id "child"])
            alice (d/entid before [:eacl/id "alice"])
            unrelated (d/entid before [:eacl/id "other-folder"])]
        (is (seq (:reverse (halves before child))))
        (is (seq (:forward (halves before alice))))
        (d/transact!
         conn
         (safe-retraction/direct-retract-entity-tx-data parent))
        (let [after (d/db conn)]
          (is (empty? (d/datoms after :eav parent)))
          (is (empty? (d/datoms after :eav child)))
          (is (empty? (:reverse (halves after child))))
          (is (= 1 (count (:forward (halves after alice)))))
          (is (seq (:reverse (halves after unrelated)))))))))

(deftest only-direct-in-process-invocation-is-advertised-test
  (with-system
    nil
    (fn [conn _]
      (is (nil? (d/entid (d/db conn) safe/function-ident)))
      (is (= :direct (:mode (safe-retraction/support-descriptor))))
      (is (= {:installed? false :state :direct}
             (select-keys (safe-retraction/prepare! conn)
                          [:installed? :state])))
      (is (= :eacl.safe-retraction/installation-unavailable
             (:type
              (try
                (safe-retraction/install! conn)
                nil
                (catch clojure.lang.ExceptionInfo error
                  (ex-data error))))))
      (testing "the direct function executes against the serialized writer DB"
        (d/transact! conn [{:eacl/id "victim"}])
        (let [eid (d/entid (d/db conn) [:eacl/id "victim"])]
          (d/transact!
           conn
           (safe-retraction/retract-entity-tx-data [:eacl/id "victim"]))
          (is (empty? (d/datoms (d/db conn) :eav eid))))))))
