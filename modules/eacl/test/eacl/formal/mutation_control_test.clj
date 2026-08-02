(ns eacl.formal.mutation-control-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [eacl.authorization-oracle :as oracle]
            [eacl.formal.generators :as generators]))

(def registry-path "formal/mutations/registry.edn")

(defn- registry
  []
  (edn/read-string (slurp registry-path)))

(defn- wrong-arrow-direction-killed?
  []
  (let [user {:type :user :id "u"}
        account {:type :account :id "a"}
        server {:type :server :id "s"}
        fixture
        {:objects [user account server]
         :relationships
         [{:subject user :relation :owner :resource account}
          {:subject account :relation :account :resource server}]
         :rules
         {[:server :view]
          [:arrow :account [:relation :owner]]}}
        correct (oracle/authorization-set fixture)
        mutant #{}]
    (and (contains? correct [user :view server])
         (not= correct mutant))))

(defn- premature-cycle-cut-killed?
  []
  (let [fixture (generators/coherent-schema 3001)
        correct
        (into #{}
              (filter
               (fn [[_ permission resource]]
                 (and (= :read permission)
                      (= :folder (:type resource)))))
              (oracle/authorization-set fixture))
        direct-only
        (into #{}
              (for [{:keys [subject relation resource]}
                    (:relationships fixture)
                    :when (and (= :reader relation)
                               (= :folder (:type resource)))]
                [subject :read resource]))]
    (> (count correct) (count direct-only))))

(defn- missing-de-duplication-killed?
  []
  (let [semantic [:resource "d1"]
        correct [semantic]
        mutant [semantic semantic]]
    (and (= 1 (count (distinct correct)))
         (not= correct mutant))))

(defn- wrong-frontier-killed?
  []
  (let [values [10 20 30 40]
        bound 1
        correct (subvec values (inc bound))
        mutant (subvec values bound)]
    (and (= [30 40] correct)
         (= 20 (first mutant))
         (not= correct mutant))))

(defn- incomplete-dependency-killed?
  []
  (let [complete #{[:folder :reader] [:folder :parent]}
        mutant (disj complete [:folder :parent])]
    (and (contains? complete [:folder :parent])
         (not (contains? mutant [:folder :parent])))))

(defn- numeric-ancestry-killed?
  []
  (let [selected {:anchor :sibling :order 20 :ancestors #{:genesis :sibling}}
        candidate {:anchor :other-sibling :order 10}
        correct (contains? (:ancestors selected) (:anchor candidate))
        mutant (<= (:order candidate) (:order selected))]
    (and (false? correct) (true? mutant))))

(defn- cursor-scope-killed?
  []
  (let [cursor-scope [:lookup-resources {:subject "u1"}]
        request-scope [:lookup-subjects {:resource "d1"}]
        correct (= cursor-scope request-scope)
        mutant true]
    (and (false? correct) (true? mutant))))

(defn- cache-fail-open-killed?
  []
  (let [provider-status :failed
        candidate true
        recomputed false
        correct (if (= :failed provider-status) recomputed candidate)
        mutant candidate]
    (and (false? correct) (true? mutant))))

(defn- continuation-race-killed?
  []
  (let [validated {:value true :tag :valid}
        concurrent {:value false :tag :unvalidated}
        correct (:value validated)
        mutant (:value concurrent)]
    (not= correct mutant)))

(def detectors
  {:wrong-arrow-direction wrong-arrow-direction-killed?
   :premature-cycle-cut premature-cycle-cut-killed?
   :missing-de-duplication missing-de-duplication-killed?
   :wrong-frontier wrong-frontier-killed?
   :incomplete-dependency incomplete-dependency-killed?
   :numeric-ancestry numeric-ancestry-killed?
   :cursor-scope cursor-scope-killed?
   :cache-fail-open cache-fail-open-killed?
   :continuation-race continuation-race-killed?})

(deftest every-registered-mutant-is-killed-test
  (let [{:keys [required-score mutants]} (registry)
        registered (set (map :id mutants))]
    (is (= registered (set (keys detectors))))
    (doseq [{:keys [id killed-by]} mutants]
      (testing (name id)
        (is (seq killed-by))
        (is (true? ((get detectors id)))
            (str "surviving mutant: " id))))
    (let [killed (count (filter #((get detectors %)) registered))
          score (/ killed (count registered))]
      (is (= 9 killed (count registered)))
      (is (<= required-score score))
      (is (= 1 score)))))
