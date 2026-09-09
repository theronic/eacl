(ns eacl.relationships.safe-retraction-contract
  "Native control-role refinement contract, shared by every supported mode."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [eacl.core :as eacl]
            [eacl.relationships.qualifier :as qualifier]
            [eacl.relationships.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]))

(def schema-source
  "caveat enabled(flag bool) { flag }
   caveat standby(flag bool) { flag }
   definition user {}
   definition doc {
     relation viewer: user | user with enabled | user with standby
     permission view = viewer
   }")

(defn reasons [error]
  (loop [error error found #{}]
    (if error
      (recur #?(:clj (ex-cause error) :cljs (.-cause error))
             (conj found (:reason (ex-data error))))
      found)))

(defn exercise!
  [{:keys [client snapshot transact! entid rows facts revision retract!]}]
  (eacl/write-schema! client {:schema schema-source})
  (transact! [{:db/id -1 :eacl/id "control/u"} {:db/id -2 :eacl/id "control/d"}])
  (let [subject (eacl/spice-object :user "control/u")
        resource (eacl/spice-object :doc "control/d")
        request {:subject subject :resource resource :permission :view
                 :caveat-context {"flag" false} :cache? false}]
    (eacl/create-relationship! client
      (assoc (eacl/->Relationship subject :viewer resource)
             :caveat "enabled" :valid-until-ms 100))
    (let [db (snapshot)
          caveat (entid db [:eacl.caveat/name "enabled"])
          qid (nth (:v (first (rows db storage/forward-attribute))) 4)
          reject! (fn [target]
                    (let [before (snapshot)
                          error (try (retract! target) nil
                                     (catch #?(:clj Exception :cljs :default) e e))
                          after (snapshot)]
                      (is (contains? (reasons error) :protected-control-entity))
                      (is (= (revision before) (revision after)))
                      (is (= (facts before caveat) (facts after caveat)))
                      (is (= (facts before qid) (facts after qid)))
                      (is (= :no-permission (:permissionship (eacl/check-permission client request))))))
          partial-facts {:eacl.caveat/name "partial"
                         :eacl.caveat/parameters-payload "[]"
                         :eacl.caveat/expression-source "false"
                         :eacl.caveat/profile-version "damaged"
                         :eacl.relationship-qualifier/format-version 1
                         :eacl.relationship-qualifier/caveat caveat
                         :eacl.relationship-qualifier/caveat-context "{}"
                         :eacl.relationship-qualifier/valid-until-ms 100}]
      (is (= (set (keys partial-facts)) safe/qualified-control-attributes))
      (is (some? (entid db [:eacl.caveat/name "standby"])))
      (is (not-any? #(= qualifier/context-attribute (first %)) (facts db qid)))
      (is (= :no-permission (:permissionship (eacl/check-permission client request))))
      (reject! caveat)
      (reject! qid)
      (doseq [[attribute value] partial-facts]
        (testing (str "partially populated " attribute)
          (transact! [{:db/id -1 :eacl/id (str attribute) attribute value}])
          (reject! [:eacl/id (str attribute)])))
      (doseq [child [caveat qid]]
        (transact! [{:db/id -1 :eacl/id "control/parent" :test/component child}])
        (reject! [:eacl/id "control/parent"])
        (is (some? (entid (snapshot) [:eacl/id "control/parent"])))
        (transact! [[:db/retract [:eacl/id "control/parent"] :test/component child]]))
      (doseq [ident safe/qualified-schema-idents
              :let [eid (entid (snapshot) [:db/ident ident])]
              :when eid]
        (reject! eid))
      (transact! [{:db/id -1 :db/ident :review/application}])
      (let [eid (entid (snapshot) [:db/ident :review/application])]
        (retract! [:db/ident :review/application])
        (is (empty? (facts (snapshot) eid))))
      (retract! [:db/ident :review/missing])
      (retract! [:eacl/id "control/parent"])
      (is (nil? (entid (snapshot) [:eacl/id "control/parent"]))))))
