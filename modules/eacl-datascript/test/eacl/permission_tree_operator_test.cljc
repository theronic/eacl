(ns eacl.permission-tree-operator-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]))

(def ^:private operator-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission view = (reader + writer) & (reader - banned)
   }")

(defn- thrown-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo) error
      (ex-data error))))

(defn- intermediate-operation [tree]
  (get-in tree [:intermediate :operation]))

(defn- child-relations [tree]
  (mapv :expanded-relation (get-in tree [:intermediate :children])))

(deftest public-operator-tree-preserves-source-structure-and-snapshot-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})
        alice (eacl/spice-object :user "alice")
        document (eacl/spice-object :document "d0")
        query {:resource document :permission :view}]
    (binding [orchestration/*operator-expression-writes-enabled?* true]
      (eacl/write-schema! client operator-schema))
    (ds/transact! conn [{:eacl/id "alice"} {:eacl/id "d0"}])
    (eacl/create-relationships!
     client [(eacl/->Relationship alice :reader document)
             (eacl/->Relationship alice :writer document)])
    (let [snapshot (eacl/snapshot client)
          observed (atom [])]
      (try
        (eacl/create-relationship!
         client (eacl/->Relationship alice :banned document))
        (let [old-response
              (binding [backend/*invoke-observer*
                        #(swap! observed conj
                                (select-keys % [:operation :phase]))]
                (eacl/expand-permission-tree snapshot query))
              new-response (eacl/expand-permission-tree client query)
              old-root (:tree-root old-response)
              new-root (:tree-root new-response)
              [old-union old-exclusion]
              (get-in old-root [:intermediate :children])
              [_ new-exclusion]
              (get-in new-root [:intermediate :children])]
          (is (= :intersection (intermediate-operation old-root)))
          (is (= #{:union :exclusion}
                 (set (map intermediate-operation
                           [old-union old-exclusion]))))
          (is (= [:reader :banned] (child-relations old-exclusion))
              "exclusion children retain directed left/right order")
          (is (= [] (get-in old-exclusion
                            [:intermediate :children 1 :leaf :subjects])))
          (is (= [alice]
                 (get-in new-exclusion
                         [:intermediate :children 1 :leaf :subjects])))
          (is (string? (:expanded-at old-response)))
          (is (not-any? #{:subject->resources :direct-match?
                          :all-permission-nodes}
                        (map :operation @observed))
              "rendering never invokes authorization enumeration"))
        (finally
          (eacl/release! snapshot))))
    (let [limited (datascript/make-client
                   conn {:permission-tree-limits {:max-tree-nodes 2}})
          token (eacl/cancellation-token)]
      (is (= :eacl.permission-tree/limit-exceeded
             (:type (thrown-data
                     #(eacl/expand-permission-tree limited query)))))
      (eacl/cancel! token)
      (is (= :eacl.execution/cancelled
             (:type
              (thrown-data
               #(eacl/expand-permission-tree
                 client (assoc query :cancellation-token token)))))))))
