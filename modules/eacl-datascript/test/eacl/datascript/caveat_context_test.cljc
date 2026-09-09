(ns eacl.datascript.caveat-context-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.authorization.context :as caveat-context]
            [eacl.authorization.context-test :as context-test]
            [eacl.backend.source :as source]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.request.context :as context]))

(defn fixture []
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {:clock (constantly 99)})
        user (eacl/spice-object :user "user")
        doc (eacl/spice-object :doc "doc")]
    (eacl/write-schema! client "definition user {}
                               definition doc {
                                 relation reader: user
                                 permission view = reader
                               }")
    (ds/transact! conn [{:eacl/id "user"} {:eacl/id "doc"}])
    (eacl/create-relationship! client (eacl/->Relationship user :reader doc))
    {:client client :check {:subject user :permission :view :resource doc}
     :forward {:subject user :resource/type :doc :permission :view :first 10}
     :reverse {:resource doc :subject/type :user :permission :view :first 10}}))

(defn operations [{:keys [check forward reverse]} target input]
  (let [with-context #(assoc % :caveat-context input)]
    [#(eacl/check-permission target (with-context check))
     #(eacl/check-permissions target (with-context {:checks [check check check]}))
     #(eacl/lookup-resources target (with-context forward))
     #(eacl/lookup-subjects target (with-context reverse))
     #(eacl/count-resources target (with-context (dissoc forward :first)))
     #(eacl/count-subjects target (with-context (dissoc reverse :first)))
     #(eacl/read-relationships target (with-context {:subject/type :user :first 10}))]))

(deftest public-operations-prepare-one-complete-context
  (let [{:keys [client] :as data} (fixture)
        prepare caveat-context/prepare
        with-context context/call-with-context
        prepared (atom [])
        seen (atom [])]
    (with-redefs [caveat-context/prepare
                  (fn [input]
                    (let [result (prepare input)]
                      (swap! prepared conj result)
                      result))
                  context/call-with-context
                  (fn [ctx f]
                    (swap! seen conj (:caveat-context (context/active-state ctx)))
                    (with-context ctx f))]
      (let [snapshot (eacl/snapshot client)]
        (try
          (doseq [target [client snapshot]
                  input [{"flag" true "unused" 3} {"flag" false} {}]
                  operation (operations data target input)]
            (reset! prepared [])
            (reset! seen [])
            (operation)
            (is (<= (count @prepared) 1))
            (is (= 1 (count @seen)))
            (is (identical? (or (first @prepared) (prepare {})) (first @seen)))
            (is (= input (caveat-context/value (first @seen)))))
          (finally (eacl/release! snapshot)))))))

(deftest invalid-context-fails-before-selection-even-on-warm-or-empty-requests
  (let [{:keys [client check] :as data} (fixture)]
    (is (:allowed? (eacl/check-permission client check)))
    (with-redefs [source/acquire! (fn [& _] (throw (ex-info "Unexpected snapshot selection" {})))]
      (doseq [input context-test/invalid-contexts
              operation (conj (operations data client input)
                              #(eacl/check-permissions client {:checks [] :caveat-context input}))]
        (is (= :eacl.caveat/invalid
               (:type (context-test/error-data operation))))))
    (is (= :per-demand-control
           (:reason (context-test/error-data
                     #(eacl/check-permissions client
                                              {:checks [(assoc check :caveat-context {"flag" true})]})))))))
