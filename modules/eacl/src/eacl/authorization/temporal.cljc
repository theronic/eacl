(ns eacl.authorization.temporal
  "Completed point-answer intervals, independent of retention and wall timers."
  (:require [eacl.authorization.evidence :as evidence]
            [eacl.caveats.values :as values]))

(def point-format :eacl.authorization/temporal-point-v1)

(defn point-answer [time proof]
  (evidence/throw-if-fault! proof)
  {:format point-format :start-ms time
   :valid-until-ms (evidence/valid-until proof)
   :complete? (evidence/complete? proof)
   :kind (evidence/permissionship proof)
   :value (evidence/encode proof)})

(defn point-answer-valid? [answer]
  (try
    (and (map? answer)
         (= #{:format :start-ms :valid-until-ms :complete? :kind :value} (set (keys answer)))
         (= point-format (:format answer))
         (values/valid-time? (:start-ms answer))
         (evidence/before? (:start-ms answer) (:valid-until-ms answer))
         (let [proof (evidence/decode (:value answer))]
           (and (not (evidence/fault? proof))
                (= (:kind answer) (evidence/permissionship proof))
                (= (:valid-until-ms answer) (evidence/valid-until proof))
                (= (:complete? answer) (evidence/complete? proof)))))
    (catch #?(:clj Exception :cljs :default) _ false)))

(defn reusable?
  "Checks a validated resident entry at the captured time. Missing completeness
   permits only its original time and exact basis, never a temporal promotion."
  [answer time exact-basis?]
  (and (values/valid-time? time)
       (<= (:start-ms answer) time)
       (evidence/before? time (:valid-until-ms answer))
       (or (:complete? answer) (and exact-basis? (= time (:start-ms answer))))))

(defn supersedes?
  "A later computation can replace a resident interval it could not reuse.
   Older pinned computations never evict a newer answer under the same key."
  [prior next]
  (and (= point-format (:format prior) (:format next))
       (< (:start-ms prior) (:start-ms next))
       (not (reusable? prior (:start-ms next) true))))
