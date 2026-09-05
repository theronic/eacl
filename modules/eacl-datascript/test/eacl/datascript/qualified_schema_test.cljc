(ns eacl.datascript.qualified-schema-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.caveats.definition-test :as errors]
            [eacl.caveats.evaluator :as evaluator]
            [eacl.caveats.schema-admission-test :as schemas]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datascript.core :as api]
            [eacl.backend.v8 :as backend]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.datascript.schema :as schema]))

(def unvisited-schema
  (str (schemas/source "user")
       "\ndefinition secret { relation viewer: user with enabled\n permission view = viewer\n}"))

(deftest schema-admission-requires-evaluator-before-writes-or-empty-reads
  (let [conn (schema/create-conn)
        reader (api/make-client conn {:caveat-evaluator nil})
        client (api/make-client conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))})
        subject (eacl/spice-object :user "schema/u")
        resource (eacl/spice-object :doc "schema/doc")
        request {:subject subject :permission :view :resource resource}]
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (eacl/write-schema! reader {:schema (schemas/source "user")})
      (let [before @conn]
        (is (= :eacl.caveat/evaluator-unavailable
               (errors/error-type #(eacl/write-schema! reader {:schema unvisited-schema}))))
        (is (identical? before @conn)))
      (ds/transact! conn [{:eacl/id "schema/u"} {:eacl/id "schema/doc"}])
      (is (false? (eacl/can? reader request)))
      (eacl/write-schema! client {:schema unvisited-schema})
      (is (false? (eacl/can? client request)))
      (doseq [run [#(eacl/can? reader request)
                   #(eacl/check-permission reader request)
                   #(eacl/lookup-resources reader {:subject subject :permission :view :resource/type :doc})
                   #(eacl/count-resources reader {:subject subject :permission :view :resource/type :doc})
                   #(eacl/lookup-subjects reader {:subject/type :user :permission :view :resource resource})
                   #(eacl/count-subjects reader {:subject/type :user :permission :view :resource resource})]]
        (is (= :eacl.caveat/evaluator-unavailable (errors/error-type run))))
      (let [parent (eacl/snapshot client)]
        (try
          (let [child (eacl/with-schema parent (schemas/source "user with enabled"))]
            (try
              (is (false? (:eacl.relation/allows-unqualified? (first (:relations (eacl/read-schema child))))))
              (finally (eacl/release! child))))
          (finally (eacl/release! parent)))))))

(deftest qualified-publication-capability-is-required-at-schema-boundaries
  (let [conn (schema/create-conn)
        client (api/make-client conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))})]
    (is (= :prepared (:strategy (qualifiers/publication-capability @conn))))
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (with-redefs [qualifiers/publication-capability (constantly nil)]
        (let [before @conn]
          (is (= :eacl/unsupported-capability
                 (errors/error-type #(eacl/write-schema! client {:schema unvisited-schema}))))
          (is (identical? before @conn)))))))

(deftest mismatched-custom-evaluator-is-rejected-at-client-construction
  (let [bad (reify evaluator/Evaluator
              (descriptor [_] {:profile "eacl-cel/1" :profile-fingerprint "wrong"
                               :fingerprint "custom" :capability-version 1})
              (-evaluate [_ _ _ _] true))]
    (is (= :eacl.caveat/evaluator-unavailable
           (errors/error-type #(api/make-client (schema/create-conn) {:caveat-evaluator bad}))))))

(deftest unsupported-publication-is-rejected-before-a-warm-answer
  (let [conn (schema/create-conn)
        client (api/make-client conn {:clock (constantly 100) :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
        request {:subject (eacl/spice-object :user "warm/u")
                 :resource (eacl/spice-object :doc "warm/doc") :permission :view}]
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (eacl/write-schema! client {:schema (schemas/source "user")})
      (ds/transact! conn [{:eacl/id "warm/u"} {:eacl/id "warm/doc"}])
      (eacl/create-relationship! client (eacl/->Relationship (:subject request) :viewer (:resource request)))
      (is (true? (eacl/can? client request)))
      (is (true? (:cached? (eacl/check-permission client request))))
      (let [capabilities backend/capabilities]
        (with-redefs [backend/capabilities #(dissoc (capabilities %) :qualified-publication)]
          (is (= :eacl/unsupported-capability (errors/error-type #(eacl/can? client request)))))))))
