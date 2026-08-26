(ns eacl.operator.feature-gate-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine]))

(def ^:private union-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     permission view = reader + writer
   }")

(def ^:private operator-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission view = reader & writer - banned
   }")

(defn- object [type id]
  (eacl/spice-object type id))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (ex-data error))))

(defn- seed-objects! [conn]
  (ds/transact! conn [{:eacl/id "alice"} {:eacl/id "d0"}]))

(deftest union-only-public-behavior-is-independent-of-operator-gates-test
  (doseq [write-enabled? [false true]
          route-enabled? [false true]]
    (testing (str "write=" write-enabled? ", route=" route-enabled?)
      (let [conn (datascript/create-conn)
            client (datascript/make-client conn {})
            alice (object :user "alice")
            document (object :document "d0")]
        (binding [orchestration/*operator-expression-writes-enabled?*
                  write-enabled?
                  engine/*operator-routing-enabled?* route-enabled?]
          (eacl/write-schema! client union-schema)
          (seed-objects! conn)
          (eacl/create-relationship!
           client (eacl/->Relationship alice :reader document))
          (is (true? (eacl/can? client {:subject alice
                                        :permission :view
                                        :resource document}))))))))

(deftest operator-expression-write-and-query-gates-are-independent-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})
        alice (object :user "alice")
        document (object :document "d0")]
    (is (= :eacl.schema/operator-expression-writes-disabled
           (:type (error-data #(eacl/write-schema! client operator-schema)))))
    (binding [orchestration/*operator-expression-writes-enabled?* true]
      (eacl/write-schema! client operator-schema))
    (seed-objects! conn)
    (eacl/create-relationships!
     client [(eacl/->Relationship alice :reader document)
             (eacl/->Relationship alice :writer document)])
    (is (= :eacl.operator/routing-disabled
           (:type
            (error-data
             #(eacl/can? client {:subject alice
                                 :permission :view
                                 :resource document})))))
    (binding [engine/*operator-routing-enabled?* true]
      (is (true? (eacl/can? client {:subject alice
                                    :permission :view
                                    :resource document}))))))
