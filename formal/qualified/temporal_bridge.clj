(ns eacl.formal.qualified.temporal-bridge
  (:require [clojure.test :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.temporal :as temporal]
            [eacl.formal.qualified.model :as model]))

(deftest point-answer-time-acceptance-refines-the-independent-reuse-model
  (let [universe #{0 1}
        scope (zipmap model/scope-fields (repeat 1))]
    (doseq [[production oracle] [[true (model/value universe)]
                                 [false (model/value #{})]
                                 [(evidence/conditional [:field] ["field"]) (model/value #{1})]]
            start [98 99 100]
            end [101 nil]
            complete? [true false]
            time [97 98 99 100 101 102]
            basis [1 2]]
      (let [proof (evidence/with-certificate production end complete?)
            answer (temporal/point-answer start proof)
            entry {:authenticated? true :scope scope :basis 1 :start start
                   :evidence (assoc (model/evidence oracle end) :complete? complete?)
                   :kind (model/kind universe oracle)}
            selected {:scope scope :basis basis :ancestors #{1} :time time}]
        (is (= (model/accept-cache? universe entry selected)
               (temporal/reusable? answer time (= 1 basis))))))))

(deftest cursor-time-acceptance-refines-the-independent-reuse-model
  (let [universe #{0 1} scope (zipmap model/scope-fields (repeat 1))]
    (doseq [mode [:live :pinned] start [98 99 100] end [101 nil]
            complete? [true false] time [97 98 99 100 101 102]]
      (let [proof (assoc (model/evidence (model/value universe) end) :complete? complete?)
            cursor {:entry {:authenticated? true :scope scope :basis 1 :start start :evidence proof :kind :has}
                    :mode mode :token-expiry 1000 :retained-complete? complete?}
            selected {:scope scope :basis 1 :ancestors #{} :time time :wall-time 100 :key-available? true}
            certificate (temporal/cursor-certificate mode start nil (temporal/interval start end complete?))]
        (is (= (= :continue (model/cursor-decision universe cursor selected))
               (temporal/cursor-time-valid? certificate time)))))))
