(ns eacl.caveats.inspection-contract
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is]]
            [eacl.caveats.plan :as plan]
            [eacl.caveats.schema-admission-test :as schemas]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]))

(defn- check-renewal! [client relationship]
  (let [query {:subject/type :user :subject/id "inspection/u"
               :resource/type :doc :resource/id "inspection/0" :first 1}
        active #(seq (:data (eacl/read-relationships client (assoc query :relationship-state :expiry-active))))
        stored #(first (:data (eacl/read-relationships client query)))]
    (is (nil? (active)))
    (doseq [[deadline expected-active?] [[500 true] [200 false] [nil true]]]
      (let [replacement (cond-> (dissoc relationship :valid-until-ms)
                          deadline (assoc :valid-until-ms deadline))]
        (eacl/write-relationship! client (assoc replacement :operation :touch))
        (is (= expected-active? (boolean (active))))
        (is (= replacement (stored)))))))

(defn check! [{:keys [client writer now]}]
  (binding [orchestration/*qualified-authorization-enabled?* true]
    (eacl/write-schema! client (schemas/source "user | user with enabled"))
    (let [native (:native (writer))
          subject (eacl/spice-object :user "inspection/u")
          resources (mapv #(eacl/spice-object :doc (str "inspection/" %)) (range 4))
          qualifiers [{:valid-until-ms 100}
                      {:caveat "enabled" :valid-until-ms 200}
                      {:caveat "enabled" :caveat-context {"flag" false}}
                      {}]
          relationships (mapv (fn [resource qualifier]
                                (merge (eacl/->Relationship subject :viewer resource) qualifier))
                              resources qualifiers)]
      ((:transact! native) (mapv #(hash-map :eacl/id (:id %)) (cons subject resources)))
      (eacl/write-relationships! client (mapv #(hash-map :operation :create :relationship %) relationships))
      (with-redefs [plan/compile-plan (fn [& _] (throw (ex-info "Inspection must not compile a Caveat" {})))]
        (doseq [[time active-ids] [[99 #{"inspection/0" "inspection/1" "inspection/2" "inspection/3"}]
                                   [100 #{"inspection/1" "inspection/2" "inspection/3"}]
                                   [200 #{"inspection/2" "inspection/3"}]]]
          (reset! now time)
          (doseq [anchor [{:subject/type :user :subject/id "inspection/u"}
                          {:subject/type :user}
                          {:resource/type :doc}
                          {:resource/type :doc :resource/id "inspection/1"}
                          {:resource/type :doc :resource/id "inspection/1"
                           :subject/type :user :subject/id "inspection/u"}]
                  paging [{:first 20} {:last 20}]]
            (let [query (merge anchor paging)
                  expected (if (:resource/id anchor) #{"inspection/1"} (set (map :id resources)))
                  expected-active (if (:resource/id anchor)
                                    (if (contains? active-ids "inspection/1") #{"inspection/1"} #{}) active-ids)
                  stored (eacl/read-relationships client (assoc query :relationship-state :stored))
                  active (eacl/read-relationships client (assoc query :relationship-state :expiry-active))]
              (is (= expected (set (map (comp :id :resource) (:data stored)))))
              (is (= expected-active (set (map (comp :id :resource) (:data active)))))
              (is (= (set (filter #(contains? expected (get-in % [:resource :id])) relationships)) (set (:data stored))))
              (is (= :expiry-active (:relationship-state active)))
              (is (= time (:evaluation-time-ms active)))
              (is (= (:data active) (:data (eacl/read-relationships client (assoc query :relationship-state :expiry-active :cache? false)))))))))
      (check-renewal! client (first relationships)))))
