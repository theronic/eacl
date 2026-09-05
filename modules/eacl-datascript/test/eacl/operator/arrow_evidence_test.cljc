(ns eacl.operator.arrow-evidence-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.evidence-test :as symbolic]
            [eacl.authorization.qualification :as qualification]
            [eacl.backend.v8 :as backend]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.datascript.schema :as schema]
            [eacl.operator.evaluator :as scalar]
            [eacl.operator.evaluator-test :as fixtures]
            [eacl.operator.plan :as plan]
            [eacl.operator.seekable-evidence-test :as errors]
            [eacl.relationships.edge :as edge]
            [eacl.relationships.staged :as staged]))

(defn fixture []
  (let [conn (schema/create-conn {})]
    (schema/write-schema! conn fixtures/arrow-schema)
    (ds/transact! conn (mapv #(hash-map :eacl/id %) ["user" "group0" "group1" "document"]))
    (let [[user g0 g1 document] (mapv #(ds/entid (ds/db conn) [:eacl/id %])
                                     ["user" "group0" "group1" "document"])
          groups [g0 g1]
          relation (fn [rt r st] (ds/entid (ds/db conn)
                                          [:eacl.relation/resource-type+relation-name+subject-type [rt r st]]))
          parent (relation :document :parent :group)
          member (relation :group :member :user)
          writer (qualifiers/writer conn)]
      (doseq [group groups]
        (staged/write! writer :create [:group group parent :document document] {:valid-until-ms 1000})
        (staged/write! writer :create [:user user member :group group] {:valid-until-ms 1000}))
      (staged/write! writer :create [:user user (relation :document :reader :user) :document document] nil)
      (let [db (ds/db conn)]
        {:db db :conn conn :adapter (datascript-backend/basis-adapter db {})
         :user user :groups groups :document document :member member
         :qids (into {} (mapcat (fn [[i group]]
                                 [[(edge/qualifier-id (impl/direct-edge db :group group parent :document document)) [:via i]]
                                  [(edge/qualifier-id (impl/direct-edge db :user user member :group group)) [:target i]]])
                               (map-indexed vector groups)))}))))

(defn options [{:keys [adapter db user document groups]} name]
  (let [permission [:document name] sealed (plan/seal-plan adapter permission)
        node-id (first (keep (fn [[id p]] (when (= :arrow-membership (:instruction p)) id))
                             (get (:predicate-programs sealed) permission)))
        request (fixtures/qualified-request db 99 {})]
    {:adapter adapter :plan sealed :permission permission :node-id node-id
     :subject-type :user :subject-eid user :resource-eid document :qualification request
     :arrow-witness {:point [permission node-id :user user document] :partition 0
                     :intermediate (first groups) :scope (qualification/exact-reuse-identity request)
                     :evidence false}}))

(defn resolver [qids semantic]
  (fn [_ _ compact]
    (if (vector? compact) (get semantic (get qids (edge/qualifier-id compact))) (some? compact))))

(deftest a-known-arrow-binding-is-completed-without-rechecking-its-target
  (let [{:keys [qids groups member] :as env} (fixture)
        x (evidence/with-certificate symbolic/x 100 true)
        y (evidence/with-certificate symbolic/y 110 true)
        semantic {[:via 0] true [:via 1] true [:target 0] x [:target 1] y}
        invoker backend/direct-edge-invoker]
    (doseq [permission [:view :direct_inherited]]
      (let [options (assoc-in (options env permission) [:arrow-witness :evidence] x)
            calls (atom [])
            result (with-redefs [qualification/qualify (resolver qids semantic)
                                 backend/direct-edge-invoker
                                 (fn [adapter]
                                   (let [invoke (invoker adapter)]
                                     (fn [st s r rt o]
                                       (when (= member r) (swap! calls conj o))
                                       (invoke st s r rt o))))]
                     (scalar/check-eids options))]
        (is (= (evidence/combine :union x y) result))
        (is (= [(second groups)] @calls))
        (with-redefs [backend/scan-invoker (fn [& _] (fn [& _] (throw (ex-info "Decisive seed needs no scan" {}))))]
          (is (= true (scalar/check-eids (assoc-in options [:arrow-witness :evidence] true))))
          (let [fault (evidence/fault :eacl.qualifier/invalid :missing)]
            (is (= fault (scalar/check-eids (assoc-in options [:arrow-witness :evidence] fault))))))
        (is (= :arrow-witness-scope
               (:reason (errors/error-data #(scalar/check-eids (assoc-in options [:arrow-witness :point 3] 999))))))
        (is (= :arrow-witness-scope
               (:reason (errors/error-data #(scalar/check-eids
                                             (update options :qualification
                                                     (fn [request] (qualification/request (assoc request :time 100)))))))))
        (is (= :arrow-witness-binding
               (:reason (errors/error-data #(scalar/check-eids (assoc-in options [:arrow-witness :partition] 1))))))
        (is (= :expired-arrow-witness
               (:reason (errors/error-data #(scalar/check-eids (assoc-in options [:arrow-witness :evidence]
                                                                       (evidence/with-certificate symbolic/x 99 true)))))))))))
