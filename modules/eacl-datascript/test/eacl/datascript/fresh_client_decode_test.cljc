(ns eacl.datascript.fresh-client-decode-test
  "A host that builds a fresh client per request (one per immutable database
  value) decodes an unchanged schema's stored expressions once per process,
  not once per client."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.cache.standard-lru :as lru]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.schema.expression :as expression]
            [eacl.schema.expression-persistence :as persistence]))

(def ^:private schema
  "definition user {}
   definition doc {
     relation reader: user
     relation writer: user
     permission edit = writer
     permission view = reader + edit
   }")

(deftest fresh-clients-reuse-completed-expression-decodes-test
  (let [conn (datascript/create-conn)
        writer (datascript/make-client conn {})
        alice (eacl/spice-object :user "alice")
        doc (eacl/spice-object :doc "d")
        query {:subject alice :permission :view :resource doc}
        computed (atom 0)
        decode expression/decode]
    (eacl/write-schema! writer schema)
    (ds/transact! conn [{:eacl/id "alice"} {:eacl/id "d"}])
    (eacl/create-relationship! writer (eacl/->Relationship alice :reader doc))
    (binding [persistence/*content-decodes* (lru/store 16)]
      (with-redefs [expression/decode
                    (fn [& args]
                      ;; The one-argument entry delegates to the two-argument
                      ;; var; count each codec invocation once.
                      (when (= 2 (count args)) (swap! computed inc))
                      (apply decode args))]
        (is (true? (eacl/can? (datascript/make-client conn {}) query)))
        (let [first-client @computed]
          (is (pos? first-client) "the first client decodes the schema")
          (is (true? (eacl/can? (datascript/make-client conn {}) query)))
          (is (= first-client @computed)
              "a second fresh client over the same schema decodes nothing"))))))
