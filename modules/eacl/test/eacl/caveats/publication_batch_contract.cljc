(ns eacl.caveats.publication-batch-contract
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is]]
            [eacl.caveats.persistence-contract :as persistence]
            [eacl.caveats.publication-contract :as publication]
            [eacl.relationships.staged :as staged]
            [eacl.relationships.storage :as storage]))

(defn check! [{:keys [write-schema! writer entid strategy allowance-stamps cas-attribute]}]
  (write-schema! persistence/first-schema)
  (let [w (writer) native (:native w) with-db (:with-snapshot native) tx! (:transact! native)
        relation (with-db #(entid % [:eacl.relation/resource-type+relation-name+subject-type [:doc :viewer :user]]))
        caveat (with-db #(entid % [:eacl.caveat/name "enabled"]))]
    (tx! (into [{:eacl/id "batch/u"} {:eacl/id "batch/a"} {:eacl/id "batch/b"}
                {:db/id relation :eacl.relation/caveats [caveat] :eacl.relation/allows-unqualified? true}]
               (when allowance-stamps (with-db allowance-stamps))))
    (let [subject (with-db #(entid % [:eacl/id "batch/u"]))
          a (with-db #(entid % [:eacl/id "batch/a"]))
          b (with-db #(entid % [:eacl/id "batch/b"]))
          identity-a [:user subject relation :doc a]
          identity-b [:user subject relation :doc b]
          rows (fn [attribute] (with-db #(mapv (juxt :e :v) ((:all-rows native) % attribute))))
          qualifier-count #(with-db (fn [db] (count (seq ((:all-rows native) db :eacl.relationship-qualifier/format-version)))))
          cas-attribute (if cas-attribute (with-db #(cas-attribute % :app/flag)) :app/flag)
          value {:caveat caveat :caveat-context {"flag" true} :valid-until-ms 1000}
          updates [{:operation :create :relationship identity-a :value value}
                   {:operation :create :relationship identity-b :value (assoc value :valid-until-ms 2000)}]
          prepared (staged/prepare-batch! w updates)
          plan (staged/plan-batch-current w prepared [[:db/add subject :app/flag 1]])]
      (is (empty? (rows storage/forward-attribute)))
      (is (empty? (rows storage/reverse-attribute)))
      (is (= (if (= :prepared strategy) 2 0) (qualifier-count)))
      (is (some? (publication/error-data
                  #(tx! (conj (:tx-data plan) [:db.fn/cas subject cas-attribute -1 1])))))
      (is (empty? (rows storage/forward-attribute)))
      (is (empty? (rows storage/reverse-attribute)))
      (tx! (:tx-data plan))
      (let [forward (rows storage/forward-attribute)
            reverse (rows storage/reverse-attribute)
            qids (set (map #(nth (second %) 4) forward))]
        (is (= 2 (count forward) (count reverse) (count qids) (qualifier-count)))
        (is (every? #(and (integer? %) (pos? %)) qids))
        (is (= qids (set (map #(nth (second %) 4) reverse))))
        (is (= 1 (with-db #(:app/flag ((:entity native) % subject)))))
        (let [changes (staged/prepare-batch!
                       w [{:operation :touch :relationship identity-a :value {:valid-until-ms 3000}}
                          {:operation :delete :relationship identity-b :value nil}])]
          (tx! (:tx-data (staged/plan-batch-current w changes []))))
        (let [forward (rows storage/forward-attribute)
              reverse (rows storage/reverse-attribute)
              qid (nth (second (first forward)) 4)]
          (is (= 1 (count forward) (count reverse) (qualifier-count)))
          (is (= [subject [:user relation :doc a qid]] (first forward)))
          (is (= [a [:doc relation :user subject qid]] (first reverse)))
          (is (not (contains? qids qid)))
          (is (= 3000 (with-db #(get ((:entity native) % qid) :eacl.relationship-qualifier/valid-until-ms))))
          (doseq [old qids] (is (nil? (with-db #((:entity native) % old)))))
          (let [clear (staged/prepare-batch! w [{:operation :touch :relationship identity-a :value nil}])]
            (tx! (:tx-data (staged/plan-batch-current w clear []))))
          (is (= [[subject [:user relation :doc a nil]]] (rows storage/forward-attribute)))
          (is (zero? (qualifier-count)))))
      (let [next-updates [{:operation :touch :relationship identity-a :value value}
                          {:operation :create :relationship identity-b :value value}]
            reused-id (if (#{:datomic :datascript} (:backend native)) "duplicate" -900000)
            invalid-writer (assoc-in w [:native :tempid] (constantly reused-id))]
        (is (= (if (= :prepared strategy) :duplicate-temporary-id :qualifier-reuse)
               (:reason
                (publication/error-data
                 #(staged/plan-batch-current invalid-writer
                                             (staged/prepare-batch! invalid-writer next-updates) [])))))
        (is (= :unsupported-batch-fences
               (:reason (publication/error-data
                         #(staged/prepare-batch! (update w :native dissoc :schema-fence) next-updates)))))
        (let [stale (staged/prepare-batch! w next-updates)
              stale-plan (staged/plan-batch-current w stale [])]
          (staged/write! w :touch identity-a {:valid-until-ms 4000})
          (is (some? (publication/error-data #(tx! (:tx-data stale-plan)))))
          (is (= 1 (count (rows storage/forward-attribute))))
          (when (= :prepared strategy)
            (doseq [entry stale] (staged/cleanup! w (:value entry)))))
        (let [stale (staged/prepare-batch! w next-updates)
              stale-plan (staged/plan-batch-current w stale [])]
          (staged/write! w :delete identity-a nil)
          (is (some? (publication/error-data #(tx! (:tx-data stale-plan)))))
          (is (empty? (rows storage/forward-attribute)))
          (is (empty? (rows storage/reverse-attribute)))
          (when (= :prepared strategy)
            (doseq [entry stale] (staged/cleanup! w (:value entry))))))
      (is (= :duplicate-batch-identity
             (:reason (publication/error-data #(staged/prepare-batch! w [(first updates) (first updates)]))))))))
