(ns v9-txmeta-followup
  (:require [datomic.api :as d]
            [v9-txmeta-benchmark :as b]))

(defn run! []
  (let [{:keys [db ids s rel attrs]} @b/state
        fa (attrs :bench/f4) ra (attrs :bench/r4)
        f (fn [i] [:user rel :resource (nth ids i)])
        r (fn [_] [:resource rel :user s])
        tx (-> (d/datoms db :eavt s fa (f 20)) first :tx)
        visible (fn [raw owner attr value t]
                  (let [filtered (d/filter raw
                                          (fn [raw dt]
                                            (or (not (#{fa ra} (:a dt)))
                                                (let [e (d/entity raw (:tx dt))]
                                                  (b/active? (:bench/from e) (:bench/until e) t)))))]
                    (boolean (seq (d/datoms filtered :eavt owner attr value)))))
        before {:basis-t (d/basis-t db) :assertion-tx tx
                :forward-at-1000 (visible db s fa (f 20) 1000)
                :forward-at-1200 (visible db s fa (f 20) 1200)
                :reverse-at-1000 (visible db (nth ids 20) ra (r 20) 1000)
                :reverse-at-1200 (visible db (nth ids 20) ra (r 20) 1200)}
        amended (:db-after (d/with db [[:db/add tx :bench/from 900]]))
        amended-result {:method :speculative-d-with
                        :first-edge-visible (visible amended s fa (f 20) 1000)
                        :other-edge-same-tx-visible (visible amended s fa (f 60) 1000)
                        :original-db-still-inactive (not (visible db s fa (f 20) 1000))}
        old-f (into (f 20) [nil nil 1100 nil])
        old-r (into (r 20) [nil nil 1100 nil])
        new-f (into (f 20) [nil nil 900 nil])
        new-r (into (r 20) [nil nil 900 nil])
        replaced (:db-after (d/with db [[:db/retract s :bench/f8 old-f]
                                       [:db/retract (nth ids 20) :bench/r8 old-r]
                                       [:db/add s :bench/f8 new-f]
                                       [:db/add (nth ids 20) :bench/r8 new-r]]))
        inline-result {:method :speculative-d-with
                       :old-forward-absent (empty? (seq (d/datoms replaced :eavt s :bench/f8 old-f)))
                       :old-reverse-absent (empty? (seq (d/datoms replaced :eavt (nth ids 20) :bench/r8 old-r)))
                       :new-forward-present (boolean (seq (d/datoms replaced :eavt s :bench/f8 new-f)))
                       :new-reverse-present (boolean (seq (d/datoms replaced :eavt (nth ids 20) :bench/r8 new-r)))}]
    (assert (false? (:forward-at-1000 before)))
    (assert (true? (:forward-at-1200 before)))
    (assert (= (:forward-at-1000 before) (:reverse-at-1000 before)))
    (assert (= (:forward-at-1200 before) (:reverse-at-1200 before)))
    (assert (= tx (-> (d/datoms db :eavt s fa (f 60)) first :tx)))
    (assert (every? true? (vals (dissoc amended-result :method))))
    (assert (every? true? (vals (dissoc inline-result :method))))
    (b/save! "followup" {:clock-only-change before :amend-original-tx amended-result
                         :inline-replacement inline-result :status :passed})))
