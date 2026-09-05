(ns eacl.caveats.publication-contract
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is]]
            [eacl.caveats.persistence-contract :as persistence]
            [eacl.relationships.staged :as staged]
            [eacl.relationships.qualifier-integrity :as integrity]
            [eacl.relationships.storage :as storage]))

(defn error-data [f]
  (try (f) nil
       (catch #?(:clj Throwable :cljs :default) error
         (loop [error error]
           (let [data (ex-data error) cause #?(:clj (.getCause ^Throwable error) :cljs (ex-cause error))]
             (if (and cause (not (:eacl/error data))) (recur cause) data))))))

(defn check-publication! [{:keys [write-schema! writer entid strategy interleave! allowance-stamps]}]
  (write-schema! persistence/first-schema)
  (let [w (writer) native (:native w) snapshot (:snapshot native) tx! (:transact! native)
        entity (:entity native) rows (:rows native)
        relation (entid (snapshot) [:eacl.relation/resource-type+relation-name+subject-type [:doc :viewer :user]])
        caveat (entid (snapshot) [:eacl.caveat/name "enabled"])]
    (tx! (into [{:eacl/id "publication/subject"} {:eacl/id "publication/resource"} {:eacl/id "publication/other"}
                {:db/id relation :eacl.relation/caveats [caveat] :eacl.relation/allows-unqualified? true}]
               (when allowance-stamps (allowance-stamps (snapshot)))))
    (let [subject (entid (snapshot) [:eacl/id "publication/subject"])
          resource (entid (snapshot) [:eacl/id "publication/resource"])
          other (entid (snapshot) [:eacl/id "publication/other"])
          identity [:user subject relation :doc resource]
          different [:user subject relation :doc other]
          semantic {:caveat caveat :caveat-context {"flag" true} :valid-until-ms 1000}
          forward #(mapv :v (rows (snapshot) subject storage/forward-attribute [:user relation :doc resource nil]))
          reverse #(mapv :v (rows (snapshot) resource storage/reverse-attribute [:doc relation :user subject nil]))
          current-qid #(nth (first (forward)) 4 nil)
          proof #((:with-snapshot native) (fn [db] (integrity/proof-input native db)))
          prepared (staged/prepare! w identity semantic)
          planned (staged/plan-current w :create identity prepared [[:db/add subject :app/flag 1]])
          qid (:qualifier-eid planned)]
      (is (= strategy (:strategy (staged/capability w))))
      (is (false? (:serving? (staged/capability w))))
      (is (and (integer? qid) (pos? qid)))
      (is (empty? (forward)) "preparation has no forward authorization edge")
      (is (empty? (reverse)) "preparation has no reverse authorization edge")
      (is (= 1 (:eacl.relationship-qualifier/format-version (entity (snapshot) qid))))
      (is (= :invalid-temporary-id
             (:reason (error-data #(staged/prepare! (assoc-in w [:native :tempid] (constantly qid)) identity semantic)))))
      (let [frame (proof) report (integrity/report frame)]
        (is (= :healthy (:status report)))
        (is (= 1 (get-in report [:counts :unattached-qualifier])))
        (is (= [qid] (:cleanup-candidates report)))
        (is (some? (get-in frame [:source :id]))))
      (is (= :prepared-owner-mismatch
             (:reason (error-data #(staged/plan-current w :create different prepared)))))
      (when (= :prepared strategy)
        (is (= :prepared-qualifier-required
               (:reason (error-data #(staged/plan-current w :create identity semantic))))))
      (let [before (entity (snapshot) relation)
            invalid (staged/plan-current w :create identity prepared
                                 [[:db.fn/cas subject :app/flag 42 1]])]
        (is (some? (error-data #(tx! (:tx-data invalid)))))
        (is (empty? (forward)))
        (is (= before (entity (snapshot) relation)) "failed publication leaves Relation stamps unchanged"))
      (tx! (:tx-data planned))
      (is (= [[:user relation :doc resource qid]] (forward)))
      (is (= [[:doc relation :user subject qid]] (reverse)))
      (is (= 1 (:app/flag (entity (snapshot) subject))) "caller datoms publish with the pair")
      (let [frame (proof)]
        (is (= :healthy (:status (integrity/report frame))))
        (is (some? (get-in frame [:relations relation :generation])))
        (is (= [identity] (get-in frame [:references qid :forward]))))
      (is (some? (error-data #(tx! (:tx-data planned)))) "a stale second publication loses the native Relation fence")
      (is (= :qualifier-attached (:reason (error-data #(staged/cleanup! w prepared)))))
      (staged/write! w :replace identity {:valid-until-ms 2000})
      (let [replacement (current-qid)]
        (is (and (integer? replacement) (pos? replacement) (not= qid replacement)))
        (is (nil? (entity (snapshot) qid)) "replacement retracts the old qualifier")
        (is (= 2000 (:eacl.relationship-qualifier/valid-until-ms (entity (snapshot) replacement))))
        (is (= replacement (nth (first (reverse)) 4)))
        (staged/write! w :delete identity nil)
        (is (empty? (forward)))
        (is (empty? (reverse)))
        (is (nil? (entity (snapshot) replacement))))
      (let [orphan (staged/prepare! w identity {:valid-until-ms 3000})
            q (:qualifier-eid (staged/plan-current w :create identity orphan))]
        (staged/cleanup! w orphan)
        (is (nil? (entity (snapshot) q))))
      (let [orphan (staged/prepare! w identity {:valid-until-ms 4000})
            plan (staged/plan-current w :create identity orphan)
            q (:qualifier-eid plan)
            before (proof)]
        (tx! [[:db/add q :eacl.relationship-qualifier/valid-until-ms 4001]])
        (is (= 1 (get-in (integrity/report (proof) {:before before}) [:counts :mutable-qualifier])))
        (is (= :qualifier-changed-at-commit (:reason (error-data #(tx! (:tx-data plan))))))
        (is (empty? (forward)))
        (is (= :prepared-qualifier-changed (:reason (error-data #(staged/cleanup! w orphan)))))
        (tx! [[:db/retractEntity q]]))
      (when interleave!
        (let [pending (atom nil)]
          (is (= :eacl.schema/concurrent-write
                 (:type (error-data #(interleave!
                                       (fn [] (reset! pending (staged/prepare! w identity semantic)))
                                       (fn [] (write-schema! persistence/base-schema)))))))
          (is (some? (entid (snapshot) [:eacl.caveat/name "enabled"])))
          (staged/cleanup! w @pending)))
      (let [tempid ((:tempid native))
            report (tx! [{:db/id tempid :eacl.relationship-qualifier/valid-until-ms 123}])
            q (get (:tempids report) tempid)]
        (is (= 1 (get-in (integrity/report (proof)) [:counts :malformed-qualifier])))
        (tx! [[:db/retractEntity q]]))
      (let [first (staged/prepare! w identity {:valid-until-ms 5000})
            _second (staged/prepare! w different {:valid-until-ms 6000})
            cleanup ((:with-snapshot native) #(integrity/cleanup-plan native % {:batch-size 1}))]
        (is (= 1 (count (:qualifiers cleanup))))
        (staged/write! w :create identity first)
        (is (some? (error-data #(tx! (:tx-data cleanup)))) "publication invalidates the orphan scan's exact head")
        (is (= 1 (count (forward))))
        (let [collected (integrity/cleanup-orphans! (writer))]
          (is (= 1 (count (:qualifiers collected))))
          (is (= 1 (count (forward))) "orphan cleanup never retracts the attached qualifier"))
        (staged/write! w :delete identity nil)))))
