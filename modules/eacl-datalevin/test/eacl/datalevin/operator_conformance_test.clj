(ns eacl.datalevin.operator-conformance-test
  (:require [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datalevin.core :as datalevin]
            [eacl.engine.v8 :as engine]))

(def ^:private test-key "01234567890123456789012345678901")

(def ^:private operator-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission base = reader + writer
     permission view = base & reader - banned
   }")

(def ^:private recursive-schema
  "definition user {}
   definition folder {
     relation direct: user
     relation eligible: user
     relation banned: user
     relation parent: folder
     permission view = direct + (parent->view & eligible)
     permission allowed = view - banned
   }")

(defn- object [type id]
  (eacl/spice-object type id))

(defn- ids [page]
  (mapv :id (:data page)))

(defn- write-operator-schema! [client schema-source]
  (binding [orchestration/*operator-expression-writes-enabled?* true]
    (eacl/write-schema! client schema-source)))

(defn- with-client [f]
  (let [dir (u/tmp-dir (str "eacl-operator-conformance-" (random-uuid)))
        conn (datalevin/create-conn dir)
        watermark (atom 0)
        client
        (datalevin/make-client
         conn
         {:revision-watermark watermark
          :advance-revision-watermark! #(swap! watermark max %)
          :source-lifecycle (str (random-uuid))
          :security-key test-key})]
    (try
      (f conn client)
      (finally
        (d/close conn)
        (u/delete-files dir)))))

(deftest public-intersection-and-exclusion-operation-matrix-test
  (with-client
    (fn [conn client]
      (let [alice (object :user "alice")
            bob (object :user "bob")
            documents (mapv #(object :document %) ["d0" "d1" "d2" "d3"])
            [d0 d1 d2 d3] documents
            relationships
            [(eacl/->Relationship alice :reader d0)
             (eacl/->Relationship alice :reader d1)
             (eacl/->Relationship alice :banned d1)
             (eacl/->Relationship alice :writer d2)
             (eacl/->Relationship alice :reader d3)
             (eacl/->Relationship alice :writer d3)
             (eacl/->Relationship bob :reader d1)]
            forward {:subject alice :permission :view
                     :resource/type :document}
            reverse {:resource d1 :permission :view :subject/type :user}]
        (write-operator-schema! client operator-schema)
        (d/transact! conn (mapv #(hash-map :eacl/id %)
                                ["alice" "bob" "d0" "d1" "d2" "d3"]))
        (eacl/create-relationships! client relationships)
        (binding [engine/*operator-routing-enabled?* true]
          (is (= [true false false true]
                 (mapv #(eacl/can? client {:subject alice :permission :view
                                           :resource %})
                       documents)))
          (is (= ["d0" "d3"]
                 (ids (eacl/lookup-resources client (assoc forward :first 20)))))
          (is (= ["bob"]
                 (ids (eacl/lookup-subjects client (assoc reverse :first 20)))))
          (is (= 2 (:count (eacl/count-resources client forward))))
          (is (= {:count 1 :limit 1 :truncated? true}
                 (select-keys
                  (eacl/count-resources client
                                        (assoc forward :count-limit 1))
                  [:count :limit :truncated?])))
          (let [page-1 (eacl/lookup-resources client (assoc forward :first 1))
                page-2
                (eacl/lookup-resources
                 client (assoc forward :first 1
                               :after (get-in page-1
                                              [:page-info :end-cursor])))]
            (is (= ["d0" "d3"] (into (ids page-1) (ids page-2))))))))))

(deftest public-recursive-conjunction-and-exclusion-operation-matrix-test
  (with-client
    (fn [conn client]
      (let [alice (object :user "alice")
            bob (object :user "bob")
            folders (mapv #(object :folder %) ["f0" "f1" "f2"])
            [f0 f1 f2] folders
            relationships
            [(eacl/->Relationship alice :direct f0)
             (eacl/->Relationship bob :direct f0)
             (eacl/->Relationship f0 :parent f1)
             (eacl/->Relationship alice :eligible f1)
             (eacl/->Relationship f1 :parent f2)
             (eacl/->Relationship alice :eligible f2)
             (eacl/->Relationship alice :banned f2)]
            forward {:subject alice :permission :allowed
                     :resource/type :folder}
            reverse {:resource f0 :permission :allowed :subject/type :user}]
        (write-operator-schema! client recursive-schema)
        (d/transact! conn (mapv #(hash-map :eacl/id %)
                                ["alice" "bob" "f0" "f1" "f2"]))
        (eacl/create-relationships! client relationships)
        (binding [engine/*operator-routing-enabled?* true]
          (is (= [true true false]
                 (mapv #(eacl/can? client {:subject alice
                                           :permission :allowed
                                           :resource %})
                       folders)))
          (is (= [true false false]
                 (mapv #(eacl/can? client {:subject bob
                                           :permission :allowed
                                           :resource %})
                       folders)))
          (is (= ["f0" "f1"]
                 (ids (eacl/lookup-resources client (assoc forward :first 20)))))
          (is (= ["alice" "bob"]
                 (ids (eacl/lookup-subjects client (assoc reverse :first 20)))))
          (is (= 2 (:count (eacl/count-resources client forward))))
          (is (= 2 (:count (eacl/count-subjects client reverse)))))))))
