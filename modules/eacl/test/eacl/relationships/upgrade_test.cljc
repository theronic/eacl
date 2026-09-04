(ns eacl.relationships.upgrade-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [eacl.relationships.upgrade :as upgrade]
            [eacl.relationships.storage :as storage]))

(defn error-data [f]
  (try (f) nil
       (catch #?(:clj Exception :cljs :default) error
         #?(:cljs (ex-data error)
            :clj (some (fn [cause] (let [data (ex-data cause)]
                                    (when (or (:type data) (:reason data)) data)))
                       (take-while some? (iterate ex-cause error)))))))

(def identities #{[1 :user 10 :document 20] [2 :user 10 :document 20] [1 :user 11 :document 21]})

(deftest generated-pair-conversion-preserves-identity-test
  ;; Deterministic generated graphs run unchanged on the JVM and in JS.
  (doseq [seed (range 200)]
    (let [identities (set (for [n (range (mod seed 31))]
                            [(inc (mod (+ seed (* n 7)) 17))
                             (nth [:user :team] (mod n 2))
                             (+ 100 (mod n 5)) :document
                             (+ 200 (mod (+ n seed) 13))]))
          plan (upgrade/batch-plan identities)
          pairs (fn [operation]
                  (reduce (fn [result [op e a v]]
                            (if (= op operation)
                              (update result (if (#{storage/forward-attribute
                                                    :eacl.v7.relationship/subject-type+relation+resource-type+resource} a)
                                               :forward :reverse)
                                      conj {:e e :v v})
                              result))
                          {:forward [] :reverse []} (:tx-data plan)))]
      (is (= identities (upgrade/inspect-pairs (pairs :db/add) 9)))
      (is (= identities (upgrade/inspect-pairs (pairs :db/retract) 7)))
      (is (= (* 4 (count identities)) (count (:tx-data plan))))
      (is (= (set (map #(nth % 2) identities)) (:relations plan)))
      (is (= (upgrade/certificate identities)
             (upgrade/certificate (reverse (sort identities))))))))

(deftest reference-validation-and-corruption-test
  (let [identity #{[1 :user 10 :document 20]}
        relation {:eacl.relation/subject-type :user
                  :eacl.relation/resource-type :document
                  :eacl.relation/relation-name :viewer}
        invalid-relations [nil {} (assoc relation :eacl.relation/subject-type :team)
                           (assoc relation :eacl.relation/resource-type :account)
                           (dissoc relation :eacl.relation/relation-name)]
        invalid-values [nil [] [:user 10 :document] [:user 10 :document 20 nil]
                        [:user "relation" :document 20] [:user 10 :document -1]]]
    (is (= identity (upgrade/validate-references! identity #{1 20} (constantly relation))))
    (doseq [present [#{1} #{20} #{}]]
      (let [error (error-data #(upgrade/validate-references! identity present (constantly relation)))]
        (is (= :missing-endpoint (:reason error)))))
    (doseq [definition invalid-relations]
      (let [error (error-data #(upgrade/validate-references! identity #{1 20} (constantly definition)))]
        (is (= :invalid-relation (:reason error)))))
    (doseq [value invalid-values]
      (let [error (error-data #(upgrade/canonical-identity :forward 1 value 7))]
        (is (= :malformed-pair (:reason error)))))))

(deftest exact-conversion-and-independent-parity-test
  (let [plan (upgrade/batch-plan identities)
        rows (fn [operation attribute]
               (for [[op e a v] (:tx-data plan) :when (and (= op operation) (= a attribute))]
                 {:e e :v v}))
        target {:forward (rows :db/add storage/forward-attribute)
                :reverse (rows :db/add storage/reverse-attribute)}]
    (is (= #{10 11} (:relations plan)))
    (is (= 12 (count (:tx-data plan))))
    (is (= identities (upgrade/inspect-pairs target 9)))
    (is (= :pair-mismatch
           (:reason (error-data #(upgrade/inspect-pairs (update target :reverse rest) 9)))))
    (is (= :duplicate-identity
           (:reason (error-data #(upgrade/inspect-pairs (update target :forward concat (:forward target)) 9)))))
    (is (= :malformed-pair
           (:reason (error-data #(upgrade/inspect-pairs
                                  (assoc target :forward [{:e 1 :v [:user 10 :document 20 99]}]) 9)))))))

(deftest durable-transitions-and-certificates-test
  (let [initial (merge (upgrade/bootstrap-state 100)
                       {:phase :preflight :start-revision 90}
                       (upgrade/certificate identities))]
    (is (= initial (upgrade/decode-state (upgrade/encode-state initial))))
    (is (= :converting (:phase (upgrade/transition initial :converting))))
    (doseq [phase [:preflight :verifying :cleaning :complete :unknown]]
      (is (= :invalid-transition (:reason (error-data #(upgrade/transition initial phase))))))
    (is (= :concurrent-write (:reason (error-data #(upgrade/assert-head! initial 101)))))
    (is (= :invalid-state
           (:reason (error-data #(upgrade/decode-state
                                  (upgrade/encode-state (assoc initial :phase :unknown)))))))
    (is (upgrade/verify! initial #{} identities (constantly 91)))
    (is (= :source-not-empty (:reason (error-data #(upgrade/verify! initial identities #{} (constantly 91))))))
    (is (= :missing-relation-stamp (:reason (error-data #(upgrade/verify! initial #{} identities (constantly 90))))))
    (is (= :content-mismatch (:reason (error-data #(upgrade/verify! initial #{} (disj identities (first identities)) (constantly 91))))))))
