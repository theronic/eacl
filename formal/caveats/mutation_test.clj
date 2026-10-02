(ns eacl.formal.caveats.mutation-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [eacl.formal.caveats.model :as m]
            [eacl.formal.caveats.model-test :as contract]))

(defn changed-transition [f]
  (let [original m/transition]
    (fn [s action] (f s action (original s action)))))

(defn kills-transition? [before action mutate]
  (with-redefs [m/transition (changed-transition mutate)]
    (not (contract/lifecycle-check before action))))

(defn run-controls []
  (let [i (first contract/identities) other (second contract/identities)
        prepared (:state (m/transition m/empty-state [:prepare nil 1 {:caveat 1} nil]))
        published (:state (m/transition prepared [:publish i 1 nil #{:fact}]))
        selected {:generation 0 :definitions {} :allowances {}}
        definition {:name "c" :parameters [["a" :bool]] :plan [:param "a"] :profile-version "eacl-cel/1"}]
    {:bound-context-loses
     (not= {:outcome :true} (m/evaluate {"a" :bool} [:param "a"] {"a" true} {"a" false}))
     :returned-error-is-truthy
     (with-redefs [m/partial-value (constantly (m/known :bool (ex-info "returned error" {})))]
       (not= {:outcome :error :reason :missing-map-key}
             (m/evaluate {"m" [:map :string :bool]} [:index [:param "m"] [:literal :string "absent"]] {"m" {}} {})))
     :short-circuit-keeps-residual
     (with-redefs [m/logical (fn [_ a _] a)]
       (not= {:outcome :true}
             (m/evaluate {"a" :bool} [:or [:param "a"] [:literal :bool true]] {} {})))
     :qualifier-mutates-in-place
     (kills-transition? published [:prepare nil 2 {:caveat 1} nil]
                        (fn [_ _ r] (assoc-in r [:state :qualifiers 1 :value :caveat] 2)))
     :one-half-publication
     (kills-transition? prepared [:publish i 1 nil #{:fact}]
                        (fn [s _ r] (assoc-in r [:state :reverse] (:reverse s))))
     :missing-qualifier-becomes-nil
     (let [damaged (update published :qualifiers dissoc 1)
           silently-normalized (-> damaged (assoc-in [:forward i] nil) (assoc-in [:reverse i] nil))]
       (not= (m/healthy? damaged) (m/healthy? silently-normalized)))
     :schema-generation-stalls
     (let [r (m/schema-result selected 0 [definition] {} #{})
           mutant (assoc-in r [:selected :generation] (:generation selected))]
       (not= 1 (get-in mutant [:selected :generation])))
     :prepared-qualifier-authorizes
     (kills-transition? m/empty-state [:prepare nil 1 {:caveat 1} nil]
                        (fn [_ _ r] (-> r (assoc-in [:state :forward i] 1) (assoc-in [:state :reverse i] 1))))
     :publication-stamp-stalls
     (kills-transition? prepared [:publish i 1 nil #{:fact}]
                        (fn [s _ r] (assoc-in r [:state :generation] (:generation s))))
     :qualifier-is-shared
     (kills-transition? published [:publish other nil nil #{}]
                        (fn [_ _ r] (-> r (assoc-in [:state :forward other] 1) (assoc-in [:state :reverse other] 1))))
     :required-caveat-omitted
     (let [original m/allowed?]
       (with-redefs [m/allowed? (fn [schema relation caveat] (or (nil? caveat) (original schema relation caveat)))]
         (not= false (m/allowed? {:allowances {:viewer #{"c"}}} :viewer nil))))
     :fault-beats-deciding-element
     (let [original m/comprehension-value]
       (with-redefs [m/comprehension-value
                     (fn [parameters [_ range variable predicate :as plan] context scope]
                       (let [r (m/partial-value parameters range context scope)
                             faults (when (m/has-value? r)
                                      (keep #(:fault (m/partial-value parameters predicate context
                                                                      (assoc scope variable [(m/bound-type (:type r)) %])))
                                            (m/elements (:type r) (:value r))))]
                         (if (seq faults) (m/fault (first (sort faults))) (original parameters plan context scope))))]
         (not= {:outcome :true}
               (m/evaluate {"xs" [:list :string] "m" [:map :string :bool]}
                           [:exists [:param "xs"] "x" [:index [:param "m"] [:var "x"]]]
                           {"xs" ["zz" "a"] "m" {"a" true}} {}))))
     :absent-range-reports-predicate-fields
     (let [original m/comprehension-value
           parameters {"inventory" [:list :string] "sensitive" [:list :string]}
           plan [:not [:exists [:param "inventory"] "item" [:in [:var "item"] [:param "sensitive"]]]]]
       (with-redefs [m/comprehension-value
                     (fn [parameters [_ range _ predicate :as plan] context scope]
                       (let [result (original parameters plan context scope)
                             named (keep #(when (and (vector? %) (= :param (first %))) (second %))
                                         (tree-seq vector? seq predicate))]
                         (if (:missing (m/partial-value parameters range context scope))
                           (update result :missing into (remove #(contains? context %) named))
                           result)))]
         (not= #{"inventory"} (:missing-fields (m/evaluate parameters plan {} {})))))
     :comprehension-charged-once
     (let [original m/estimate-work]
       (with-redefs [m/estimate-work
                     (fn charged-once
                       ([parameters plan context] (charged-once parameters plan context {}))
                       ([parameters [op a b c :as plan] context scope]
                        (if (and (m/comprehensions op) (= :param (first a)) (contains? context (second a)))
                          (let [t (get parameters (second a)) v (get context (second a))]
                            (original parameters [op [:literal t (if (map? v) (select-keys v (take 1 (keys v))) (vec (take 1 v)))] b c]
                                      context scope))
                          (original parameters plan context scope))))]
         (not= {:outcome :error :reason :resource-limit}
               (m/evaluate {"xs" [:list :string] "ys" [:list :string]}
                           [:exists [:param "xs"] "x" [:in [:var "x"] [:param "ys"]]] {"xs" ["a" "b"]} {}))))}))

(deftest registered-mutations-are-executed-and-killed
  (let [registered (:controls (edn/read-string (slurp "formal/caveats/mutations.edn")))
        results (run-controls)]
    (is (= 14 (count registered)))
    (is (= (set (map :id registered)) (set (keys results))))
    (doseq [[id killed?] results] (is (true? killed?) (name id)))))
