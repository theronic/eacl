(ns eacl.formal.qualified.mutation-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [eacl.formal.qualified.model :as m]
            [eacl.formal.qualified.model-test :as contract]))

(defn changed-qualify [alter]
  (let [original m/qualify]
    (fn [u qs qid time] (alter u qs qid time (original u qs qid time)))))

(defn run-controls []
  (let [u contract/universe
        q {:valid? true :expiry 100 :caveat (m/value u)}
        point #(m/qualify u {1 q} 1 %)
        truth (m/evidence (m/value u) nil)
        temporary (m/evidence (m/value u) 100)
        ban-denial (m/combine u :exclusion truth (point 90))
        late (assoc contract/selected :time 100)
        prior {:source 1 :basis 10 :qid 1 :format 1 :version 1
               :relation 1 :writer-certified? true}
        cache-with-omission (fn [field]
                              (let [original m/accept-cache?]
                                (fn [universe entry selected]
                                  (original universe
                                            (update entry :scope dissoc field)
                                            (update selected :scope dissoc field)))))]
    {:omitted-qualifier-read
     (with-redefs [m/qualify (fn [universe _ _ _] (m/evidence (m/value universe) nil))]
       (not= :no (m/kind u (:value (point 100)))))
     :missing-qualifier-is-plain
     (with-redefs [m/qualify (changed-qualify
                              (fn [universe qs qid _ result]
                                (if (contains? qs qid) result (m/evidence (m/value universe) nil))))]
       (not= :failure (m/kind u (:value (m/qualify u {} 1 0)))))
     :inclusive-expiry
     (with-redefs [m/before? (fn [time end] (or (nil? end) (<= time end)))]
       (not= :no (m/kind u (:value (point 100)))))
     :expired-caveat-still-runs
     (with-redefs [m/qualify (changed-qualify
                              (fn [_ qs qid _ result]
                                (if-let [caveat (:caveat (get qs qid))] (assoc result :value caveat) result)))]
       (not= :no (m/kind u (:value (m/qualify u {1 (assoc q :caveat (m/fault :evaluator))} 1 100)))))
     :conditional-is-true
     (let [original m/kind]
       (with-redefs [m/kind (fn [universe x]
                             (let [k (original universe x)] (if (= k :conditional) :has k)))]
         (not= :conditional (m/kind u (m/atom-value u 0)))))
     :fault-is-absence
     (let [original m/compose]
       (with-redefs [m/compose (fn [op a b]
                                (original op (if (:fault a) (m/value #{}) a)
                                          (if (:fault b) (m/value #{}) b)))]
         (not= :failure (m/kind u (m/compose :exclusion (m/value u) (m/fault :invalid))))))
     :maximum-instead-of-minimum-horizon
     (with-redefs [m/meet (fn [a b] (if (and a b) (max a b) (or a b)))]
       (not (contract/certificate-contract? :intersection q (assoc q :expiry 110) 90 100 #{})))
     :emitted-only-cursor-horizon
     (with-redefs [m/cursor-certificate (fn [examined _ complete?]
                                        {:end (:end (last examined)) :complete? complete?})]
       (not= 100 (:end (m/cursor-certificate [ban-denial truth] [] true))))
     :incomplete-certificate-publishes
     (with-redefs [m/publishable? (fn [e time] (m/before? time (:end e)))]
       (m/publishable? (assoc truth :complete? false) 100))
     :time-only-removes-permission
     (let [original m/accept-cache?]
       (with-redefs [m/accept-cache? (fn [universe entry selected]
                                     (or (= :no (:kind entry)) (original universe entry selected)))]
         (m/accept-cache? u (assoc contract/entry :evidence ban-denial :kind :no) late)))
     :context-omitted-from-cache
     (with-redefs [m/accept-cache? (cache-with-omission :context)
                   m/scope-valid? (constantly true)]
       (m/accept-cache? u contract/entry (assoc-in contract/selected [:scope :context] [123])))
     :evaluator-omitted-from-cache
     (with-redefs [m/accept-cache? (cache-with-omission :evaluator)
                   m/scope-valid? (constantly true)]
       (m/accept-cache? u contract/entry (assoc-in contract/selected [:scope :evaluator] [123])))
     :conditional-cache-alias
     (let [original m/accept-cache?]
       (with-redefs [m/accept-cache? (fn [universe entry selected]
                                     (original universe (assoc entry :kind (m/kind universe (get-in entry [:evidence :value]))) selected))]
         (m/accept-cache? u (assoc contract/entry :evidence (m/evidence (m/atom-value u 0) 100)) contract/selected)))
     :qid-only-decode-cache
     (with-redefs [m/accept-decode? (fn [a b] (= (:qid a) (:qid b)))]
       (m/accept-decode? prior (assoc prior :source 2 :basis 20)))
     :raw-clock-regresses
     (with-redefs [m/capture-time (fn [_ sample] sample)]
       (< (m/capture-time 110 90) 110))
     :live-cursor-pins-old-time
     (let [original m/cursor-decision]
       (with-redefs [m/cursor-decision (fn [universe cursor selected]
                                        (original universe cursor (assoc selected :time (get-in cursor [:entry :start]))))]
         (= :continue (m/cursor-decision u contract/cursor late))))
     :recursion-stops-before-certificate-fixpoint
     (let [base {0 (m/evidence (m/value #{}) nil) 1 ban-denial}
           rules {0 [[truth 1]]}
           initial (zipmap [0 1] (repeat (m/evidence (m/value #{}) nil)))
           first-step (m/recursive-step u base rules initial)
           full (m/fixed-point u base rules 10)]
       (and (= (update-vals first-step :value) (update-vals initial :value))
            (not= (get-in first-step [0 :end]) (get-in full [:values 0 :end]))))}))

(deftest all-registered-controls-are-executed-and-killed
  (let [registered (:controls (edn/read-string (slurp "formal/qualified/mutations.edn")))
        results (run-controls)]
    (is (= 17 (count registered)))
    (is (= (set registered) (set (keys results))))
    (doseq [[id killed?] results] (is (true? killed?) (name id)))))
