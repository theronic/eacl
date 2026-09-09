(ns eacl.formal.qualified.qualification-bridge
  (:require [clojure.test :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.formal.qualified.model :as model]
            [eacl.relationships.qualifier :as qualifier]))

(def universe #{0 1})
(def kinds {:has-permission :has :no-permission :no
            :conditional-permission :conditional :evaluation-failure :failure})

(defn input-db [expiry bound damage]
  (let [semantic (cond-> {:caveat 2}
                   expiry (assoc :valid-until-ms expiry)
                   (some? bound) (assoc :caveat-context {"flag" bound}))
        db (assoc fixtures/fixture 3 (qualifier/entity-data 3 semantic fixtures/parameters))]
    (case damage
      :none db
      :missing (dissoc db 3)
      :format (assoc-in db [3 qualifier/marker-attribute] 0)
      :context (assoc-in db [3 qualifier/context-attribute] "malformed")
      :allowance (assoc-in db [1 :eacl.relation/caveats] #{77})
      :definition (dissoc db 2)
      :version db)))

(defn oracle-value [request bound]
  (cond
    (= request :wrong-type) (model/fault :evaluation)
    (some? bound) (model/value (if bound universe #{}))
    (nil? request) (model/atom-value universe 0)
    :else (model/value (if request universe #{}))))

(deftest exact-qualification-refines-temporal-leaf-model
  (doseq [expiry [nil 100]
          time [99 100 101]
          bound [nil false true]
          request [nil false true :wrong-type]
          damage [:none :missing :format :context :allowance :definition :version]]
    (let [reads (atom {}) calls (atom 0)
          expected (model/qualify universe
                                  {3 {:valid? (= damage :none) :expiry expiry
                                      :caveat (oracle-value request bound)}} 3 time)
          context (if (nil? request) {} {"flag" (if (= request :wrong-type) "wrong" request)})
          selected (fixtures/request {:db (input-db expiry bound damage) :time time
                                      :context context :reads reads :calls calls
                                      :version (constantly (if (= damage :version) 0 7))})
          actual (qualification/qualify selected 1 [10 3])
          again (qualification/qualify selected 1 [10 3])]
      (is (= (model/kind universe (:value expected)) (kinds (evidence/permissionship actual))))
      (is (= (:end expected) (evidence/valid-until actual)))
      (is (= (:complete? expected) (evidence/complete? actual)))
      (is (= actual again))
      (is (= 1 (get @reads 3)))
      (is (= (if (and (= damage :none) (model/before? time expiry)) 2 0) @calls)))))
