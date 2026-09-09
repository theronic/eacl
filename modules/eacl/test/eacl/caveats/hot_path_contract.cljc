(ns eacl.caveats.hot-path-contract
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is]]
            [eacl.core :as eacl]
            [eacl.causal-token :as token]
            [eacl.execution :as execution]
            [eacl.caveats.evaluator :as evaluator]
            [eacl.caveats.partial :as partial]
            [eacl.caveats.persistence-contract :as persistence]
            [eacl.relationships.qualifier :as qualifier]
            [eacl.relationships.qualifier-integrity :as integrity]))

(defn forbidden [& _] (throw (ex-info "Foundation code reached ordinary authorization." {})))

(defn measure [client]
  (let [clocks (atom {:monotonic 0 :wall 0})
        subject (eacl/spice-object :user "hot-path/subject")
        resource (eacl/spice-object :doc "hot-path/resource")]
    (with-redefs [qualifier/decode forbidden integrity/proof-input forbidden
                  evaluator/evaluate forbidden partial/evaluate forbidden partial/evaluate-prepared forbidden
                  token/now-seconds (fn [] (swap! clocks update :wall inc) 1000)]
      (binding [execution/*monotonic-nanos* (fn [] (swap! clocks update :monotonic inc) 1000000)]
        (is (true? (eacl/can? client subject :view resource)))
        (is (= 1 (count (:data (eacl/lookup-resources client {:subject subject :permission :view :resource/type :doc :first 10})))))
        (is (= 1 (count (:data (eacl/lookup-subjects client {:resource resource :permission :view :subject/type :user :first 10})))))))
    @clocks))

(defn check-ordinary! [{:keys [make-client transact!]}]
  (let [client (make-client)]
    (eacl/write-schema! client persistence/base-schema)
    (transact! [{:eacl/id "hot-path/subject"} {:eacl/id "hot-path/resource"}])
    (eacl/create-relationship! client (eacl/->Relationship (eacl/spice-object :user "hot-path/subject") :viewer
                                                           (eacl/spice-object :doc "hot-path/resource")))
    (let [baseline (measure (make-client))]
      (eacl/write-schema! client persistence/first-schema)
      (is (= baseline (measure (make-client)))
          "unused named Caveats add no authorization clocks or qualifier/evaluator work"))))
