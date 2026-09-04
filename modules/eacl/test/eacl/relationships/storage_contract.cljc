(ns eacl.relationships.storage-contract
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [eacl.core :as eacl]
            [eacl.relationships.endpoint-pair :as pair]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.upgrade-test :refer [error-data]]))

(def schema
  "definition user {}
   definition document {
     relation viewer: user
     permission view = viewer
   }")

(defn exercise-qualified-corruption!
  "One native graph exercises unsupported data, duplicate identity, exact
  identity-only deletion, and qualifier-preserving object cleanup."
  [{:keys [client snapshot transact! entid rows safe-retract! stamp direct-probe plan-create read-identity]}]
  (eacl/write-schema! client schema)
  (transact! [{:eacl/id "alice"} {:eacl/id "document"} {:eacl/id "qualifier"} {:eacl/id "qualifier-2"}])
  (let [transact! (fn [operations]
                    (let [relations (into #{} (keep #(when (and (vector? %) (storage/attributes (nth % 2 nil)))
                                                       (nth (nth % 3) 1))) operations)]
                      (transact! (cond-> (vec operations) stamp (into (map stamp relations))))))
        subject (eacl/spice-object :user "alice")
        resource (eacl/spice-object :document "document")
        relationship (eacl/->Relationship subject :viewer resource)]
    (eacl/create-relationship! client relationship)
    (let [db (snapshot)
          a (entid db [:eacl/id "alice"])
          doc (entid db [:eacl/id "document"])
          q (entid db [:eacl/id "qualifier"])
          q2 (entid db [:eacl/id "qualifier-2"])
          native-relationship (eacl/->Relationship (eacl/spice-object :user a) :viewer
                                                    (eacl/spice-object :document doc))
          forward (:v (first (rows db storage/forward-attribute)))
          relation (nth forward 1)
          reverse (pair/reverse-value :document relation :user a)
          fq (assoc forward 4 q)
          rq (assoc reverse 4 q)
          qualified [[:db/add a storage/forward-attribute fq]
                     [:db/add doc storage/reverse-attribute rq]]
          clear-nil (pair/retractions :user a relation :document doc)]
      (is (= 5 (count forward)))
      (is (nil? (peek forward)))
      (is (true? (eacl/can? client subject :view resource)))
      (when direct-probe
        (is (true? (direct-probe db :user a relation :document doc)))
        (is (false? (direct-probe db :user a relation :document q))))
      (transact! (into clear-nil qualified))
      (when direct-probe
        (is (= :eacl/unsupported-qualifier
               (:type (error-data #(direct-probe (snapshot) :user a relation :document doc)))))
        (is (false? (direct-probe (snapshot) :user a relation :document q))))
      (when read-identity
        (is (= :eacl/unsupported-qualifier (:type (error-data #(read-identity (snapshot) native-relationship))))))
      (testing "every serving direction refuses unsupported qualifiers"
        (doseq [[index operation] (map-indexed vector [#(eacl/can? client subject :view resource)
                                                       #(eacl/lookup-resources client {:subject subject :permission :view :resource/type :document :first 1})
                                                       #(eacl/lookup-subjects client {:resource resource :permission :view :subject/type :user :last 1})
                                                       #(eacl/read-relationships client {:subject/type :user :first 1})
                                                       #(eacl/read-relationships client {:resource/type :document :last 1})])]
          (is (= :eacl/unsupported-qualifier (:type (error-data operation))) (str "operation " index))))
      (is (= :eacl/relationship-conflict
             (:type (error-data #(eacl/create-relationship! client relationship)))))
      (is (= :eacl/unsupported-qualifier
             (:type (error-data #(eacl/write-relationship! client :touch subject :viewer resource)))))
      (eacl/delete-relationship! client relationship)
      (is (empty? (rows (snapshot) storage/forward-attribute)))
      (is (empty? (rows (snapshot) storage/reverse-attribute)))
      (eacl/create-relationship! client relationship)
      (transact! (into qualified [[:db/add a storage/forward-attribute (assoc fq 4 q2)]
                                  [:db/add doc storage/reverse-attribute (assoc rq 4 q2)]]))
      (is (= :duplicate-identity
             (:reason (error-data #(eacl/can? client subject :view resource)))))
      (eacl/delete-relationship! client relationship)
      (is (empty? (rows (snapshot) storage/forward-attribute)))
      (is (empty? (rows (snapshot) storage/reverse-attribute)))
      (when plan-create
        (let [planned (plan-create (snapshot) native-relationship)]
          (transact! [(first qualified)])
          (is (= :eacl/unsupported-qualifier (:type (error-data #(transact! planned)))))
          (is (= [fq] (mapv :v (rows (snapshot) storage/forward-attribute))))
          (is (empty? (rows (snapshot) storage/reverse-attribute)))
          (eacl/delete-relationship! client relationship)))
      (transact! qualified)
      (safe-retract! [:eacl/id "alice"])
      (is (empty? (rows (snapshot) storage/forward-attribute)))
      (is (empty? (rows (snapshot) storage/reverse-attribute)))
      ;; A peer-only ghost is still repairable by its known numeric endpoint.
      (transact! [[:db/add doc storage/reverse-attribute rq]])
      (safe-retract! a)
      (is (empty? (rows (snapshot) storage/forward-attribute)))
      (is (empty? (rows (snapshot) storage/reverse-attribute))))))
