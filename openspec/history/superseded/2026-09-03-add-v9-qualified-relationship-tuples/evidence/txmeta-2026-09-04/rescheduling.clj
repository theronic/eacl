(ns v9-txmeta-rescheduling
  (:require [datomic.api :as d]
            [clojure.pprint :as pp]))

(def tuple-types [:db.type/keyword :db.type/ref :db.type/keyword :db.type/ref])
(def schema
  [{:db/ident :probe/f :db/valueType :db.type/tuple :db/tupleTypes tuple-types
    :db/cardinality :db.cardinality/many :db/index true}
   {:db/ident :probe/r :db/valueType :db.type/tuple :db/tupleTypes tuple-types
    :db/cardinality :db.cardinality/many :db/index true}
   {:db/ident :probe/until :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :probe/id :db/valueType :db.type/string :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}])

(defn root-cause [e]
  (loop [x e]
    (if-let [cause (.getCause x)] (recur cause)
      {:class (.getName (class x)) :message (.getMessage x) :data (ex-data x)})))

(defn run-case! [uri-prefix mode]
  (let [uri (str uri-prefix "v9-txmeta-reschedule-" (random-uuid))
        _ (d/create-database uri)
        conn (d/connect uri)]
    (try
      @(d/transact conn schema)
      @(d/transact conn [{:probe/id "s"} {:probe/id "r"} {:probe/id "rel"}])
      (let [db (d/db conn)
            s (d/entid db [:probe/id "s"])
            r (d/entid db [:probe/id "r"])
            rel (d/entid db [:probe/id "rel"])
            f [:user rel :resource r]
            rev [:resource rel :user s]
            adds [[:db/add s :probe/f f] [:db/add r :probe/r rev]]
            retracts [[:db/retract s :probe/f f] [:db/retract r :probe/r rev]]
            fa (d/entid db :probe/f)
            ra (d/entid db :probe/r)
            meta200 {:db/id "datomic.tx" :probe/until 200}
            read-state
            (fn [db]
              (let [fv (first (d/datoms db :eavt s :probe/f))
                    rv (first (d/datoms db :eavt r :probe/r))
                    tx (:tx fv)
                    until (when tx (:probe/until (d/entity db tx)))
                    filtered (d/filter db (fn [raw datom]
                                           (or (not (#{fa ra} (:a datom)))
                                               (let [end (:probe/until (d/entity raw (:tx datom)))]
                                                 (or (nil? end) (< 150 end))))))]
                {:basis-t (d/basis-t db)
                 :forward-present (some? fv) :reverse-present (some? rv)
                 :forward-tx (:tx fv) :reverse-tx (:tx rv)
                 :until until
                 :visible-at-150 (boolean (seq (d/datoms filtered :eavt s :probe/f f)))}))
            first-report @(d/transact conn (conj adds {:db/id "datomic.tx" :probe/until 100}))
            before (read-state (:db-after first-report))]
        (try
          (let [gap (when (= mode :two-transactions)
                      (read-state (:db-after @(d/transact conn retracts))))
                data (case mode
                       :reassert (conj adds meta200)
                       :retract-then-assert (into [meta200] (concat retracts adds))
                       :assert-then-retract (into [meta200] (concat adds retracts))
                       :two-transactions (conj adds meta200))
                report @(d/transact conn data)
                after (read-state (:db-after report))]
            {:mode mode :before before :gap gap :after after
             :new-transaction-until (:probe/until (d/entity (:db-after report) (d/t->tx (d/basis-t (:db-after report)))))
             :same-assertion-tx (= (:forward-tx before) (:forward-tx after))
             :relationship-changes (mapv (fn [dt] {:e (:e dt) :a (:a dt) :v (:v dt) :tx (:tx dt) :added (:added dt)})
                                          (filter #(#{fa ra} (:a %)) (:tx-data report)))})
          (catch Exception e {:mode mode :before before :error (root-cause e)
                              :after (read-state (d/db conn))})))
      (finally
        (d/release conn)
        (d/delete-database uri)))))

(defn run-all! [uri-prefix output-path]
  (let [result {:uri-prefix uri-prefix :java (System/getProperty "java.version")
                :cases (mapv #(run-case! uri-prefix %)
                              [:reassert :retract-then-assert :assert-then-retract :two-transactions])}]
    (doseq [{:keys [mode before after gap error relationship-changes new-transaction-until]} (:cases result)]
      (assert (= 100 (:until before)))
      (case mode
        :reassert
        (do (assert (nil? error)) (assert (= 200 new-transaction-until))
            (assert (> (:basis-t after) (:basis-t before)))
            (assert (= (:forward-tx before) (:forward-tx after)))
            (assert (= 100 (:until after))) (assert (empty? relationship-changes)))
        (:retract-then-assert :assert-then-retract)
        (do (assert (= :db.error/datoms-conflict (get-in error [:data :db/error])))
            (assert (= before after)))
        :two-transactions
        (do (assert (nil? error)) (assert (false? (:forward-present gap)))
            (assert (false? (:reverse-present gap))) (assert (:visible-at-150 after))
            (assert (= 200 (:until after)))
            (assert (not= (:forward-tx before) (:forward-tx after))))))
    (spit output-path (with-out-str (pp/pprint result)))
    result))
