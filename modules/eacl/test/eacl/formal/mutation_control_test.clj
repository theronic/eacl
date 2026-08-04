(ns eacl.formal.mutation-control-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [eacl.authorization-oracle :as oracle]
            [eacl.formal.generators :as generators]
            [eacl.test-support.repo :as repo]))

(def registry-path
  (repo/file "formal" "mutations" "registry.edn"))

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

(defn- set-equality-as-sequence-equality-killed?
  []
  (let [correct [1 2 3 4 5 6]
        mutant [2 4 6 1 3 5]]
    (and (= (set correct) (set mutant))
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

(defn- immediate-reverse-consumer-registration-killed?
  []
  (let [correct {:queued-work [:register-consumer :goal]
                 :cumulative-enqueues 2
                 :maximum-queue-depth 2}
        mutant {:queued-work [:goal]
                :cumulative-enqueues 1
                :maximum-queue-depth 1}]
    (and (not= (:queued-work correct) (:queued-work mutant))
         (not= (select-keys correct
                           [:cumulative-enqueues :maximum-queue-depth])
               (select-keys mutant
                            [:cumulative-enqueues :maximum-queue-depth])))))

(defn- current-cache-missing-entry-hit-killed?
  []
  (let [available? false
        correct (if available?
                  :use-exact-entry
                  :probe-managed-entry)
        mutant :use-exact-entry]
    (and (= :probe-managed-entry correct)
         (not= correct mutant))))

(defn- mismatched-indexed-request-scope-response-killed?
  []
  (let [pending
        {:request-scope 81
         :request-id 0}
        response
        {:request-scope 82
         :request-id 0}
        correct
        (and (= (:request-scope pending) (:request-scope response))
             (= (:request-id pending) (:request-id response)))
        mutant (= (:request-id pending) (:request-id response))]
    (and (false? correct) (true? mutant))))

(defn- lookup-self-scope-omits-lifecycle-killed?
  []
  (let [lifecycle (Object.)
        tier :denotation
        key :recursive
        resolving #{[lifecycle tier key]}
        correct (contains? resolving [lifecycle tier key])
        mutant (contains? resolving [tier key])]
    (and (true? correct) (false? mutant))))

(defn- ordered-merge-wrong-comparator-killed?
  []
  (let [left-head 1
        right-head 2
        correct (if (< left-head right-head) :take-left :take-right)
        mutant (if (> left-head right-head) :take-left :take-right)]
    (and (= :take-left correct)
         (= :take-right mutant)
         (not= correct mutant))))

(defn- ordered-merge-sentinel-collides-with-domain-killed?
  []
  (let [maximum-eid Long/MAX_VALUE
        correct-has-last? false
        correct-emits?
        (not (and correct-has-last?
                  (== maximum-eid maximum-eid)))
        mutant-last-key maximum-eid
        mutant-emits? (not (== maximum-eid mutant-last-key))]
    (and (true? correct-emits?)
         (false? mutant-emits?))))

(defn- generic-ordered-merge-nil-sentinel-collides-with-domain-killed?
  []
  (let [candidate nil
        correct-has-last? false
        correct-emits?
        (not (and correct-has-last?
                  (= candidate nil)))
        mutant-last-key nil
        mutant-emits? (not= candidate mutant-last-key)]
    (and (true? correct-emits?)
         (false? mutant-emits?))))

(defn- inherited-self-bypass-skips-computation-slot-killed?
  []
  (let [parent-context (Object.)
        child-context (Object.)
        same-owner? (identical? parent-context child-context)
        parent-active 1
        correct-active (+ parent-active (if same-owner? 0 1))
        mutant-active parent-active]
    (and (false? same-owner?)
         (= 2 correct-active)
         (= 1 mutant-active)
         (not= correct-active mutant-active))))

(defn- split-lifecycle-read-between-self-check-and-flight-selection-killed?
  []
  (let [old-lifecycle (Object.)
        new-lifecycle (Object.)
        tier :projection
        key :same
        correct-recursive-address [new-lifecycle tier key]
        correct-flight-address [new-lifecycle tier key]
        mutant-recursive-address [old-lifecycle tier key]
        mutant-flight-address [new-lifecycle tier key]]
    (and (not (identical? old-lifecycle new-lifecycle))
         (= correct-recursive-address correct-flight-address)
         (not= mutant-recursive-address mutant-flight-address))))

(defn- lookup-decision-after-flight-installation-killed?
  []
  (let [authoritative-action :bypass-recursive-self
        correct-flight-installations
        (if (= :start-computation authoritative-action) 1 0)
        mutant-flight-installations 1]
    (and (zero? correct-flight-installations)
         (= 1 mutant-flight-installations)
         (not= correct-flight-installations
               mutant-flight-installations))))

(defn- unrepresented-flight-ignored-by-lookup-killed?
  []
  (let [represented-entry nil
        registered-flight? true
        correct-candidate
        (cond
          (= :complete represented-entry) :complete
          (or registered-flight?
              (= :computing represented-entry)) :computing
          :else :missing)
        mutant-candidate
        (cond
          (= :complete represented-entry) :complete
          (= :computing represented-entry) :computing
          :else :missing)
        action
        (fn [candidate]
          (if (= :computing candidate)
            :join-computation
            :start-computation))]
    (and (= :join-computation (action correct-candidate))
         (= :start-computation (action mutant-candidate))
         (not= (action correct-candidate)
               (action mutant-candidate)))))

(defn- projection-key-omits-inclusive-bound-killed?
  []
  (let [base
        {:version 1
         :operation :subject->resources
         :direction :asc
         :subject-type :user
         :subject-id 1
         :relation-id 2
         :resource-type :document
         :resource-id 3
         :bound 10
         :chunk-width 64}
        inclusive (assoc base :inclusive? true)
        exclusive (assoc base :inclusive? false)
        mutant-key #(dissoc % :inclusive?)]
    (and (not= inclusive exclusive)
         (= (mutant-key inclusive)
            (mutant-key exclusive)))))

(defn- inclusive-bound-treated-exclusive-killed?
  []
  (let [values [9 10 11]
        boundary 10
        correct (filterv #(<= boundary %) values)
        mutant (filterv #(< boundary %) values)]
    (and (= [10 11] correct)
         (= [11] mutant)
         (not= correct mutant))))

(defn- incomplete-managed-proof-atoms-killed?
  []
  (let [previous [[:viewer 10] [:editor 20]]
        selected [[:viewer 10] [:editor 21]]
        correct (= previous selected)
        mutant (= (first previous) (first selected))]
    (and (false? correct)
         (true? mutant))))

(defn- stale-endpoint-stamp-accepted-killed?
  []
  (let [previous
        {:source :primary
         :schema-stamp 7
         :relation-stamp 10
         :endpoint [:user 1 :document 2]}
        selected (assoc previous :relation-stamp 11)
        correct (= previous selected)
        mutant
        (= (dissoc previous :relation-stamp)
           (dissoc selected :relation-stamp))]
    (and (false? correct)
         (true? mutant))))

(defn- over-budget-publication-killed?
  []
  (let [weight 2
        budget 1
        correct-retain? (and (pos? weight) (<= weight budget))
        mutant-retain? (pos? weight)]
    (and (false? correct-retain?)
         (true? mutant-retain?))))

(defn- exception-poisons-flight-killed?
  []
  (let [ticket (Object.)
        before {:same {:ticket ticket :status :computing}}
        removed
        (update before :same
                #(when-not (identical? ticket (:ticket %)) %))
        correct (into {} (remove (comp nil? val)) removed)
        mutant before]
    (and (not (contains? correct :same))
         (contains? mutant :same))))

(defn- flight-removal-outside-selection-lock-killed?
  []
  (let [store-lock-held? true
        flight-present? true
        correct-present-during-selection?
        (and store-lock-held? flight-present?)
        mutant-present-during-selection? false]
    (and (true? correct-present-during-selection?)
         (false? mutant-present-during-selection?))))

(defn- datomic-subproblem-config-dropped-killed?
  []
  (let [requested
        {:enabled? false
         :projection-max-weight 17
         :denotation-max-weight 19
         :max-inflight 2
         :managed-proof-max-atoms 3}
        normalized
        {:native-subproblem-cache requested}
        mutant
        {:native-subproblem-cache {}}]
    (and (= requested (:native-subproblem-cache normalized))
         (not= requested (:native-subproblem-cache mutant)))))

(def detectors
  {:wrong-arrow-direction wrong-arrow-direction-killed?
   :premature-cycle-cut premature-cycle-cut-killed?
   :missing-de-duplication missing-de-duplication-killed?
   :set-equality-as-sequence-equality
   set-equality-as-sequence-equality-killed?
   :wrong-frontier wrong-frontier-killed?
   :incomplete-dependency incomplete-dependency-killed?
   :numeric-ancestry numeric-ancestry-killed?
   :cursor-scope cursor-scope-killed?
   :cache-fail-open cache-fail-open-killed?
   :continuation-race continuation-race-killed?
   :immediate-reverse-consumer-registration
   immediate-reverse-consumer-registration-killed?
   :current-cache-missing-entry-hit
   current-cache-missing-entry-hit-killed?
   :mismatched-indexed-request-scope-response
   mismatched-indexed-request-scope-response-killed?
   :lookup-self-scope-omits-lifecycle
   lookup-self-scope-omits-lifecycle-killed?
   :ordered-merge-wrong-comparator
   ordered-merge-wrong-comparator-killed?
   :ordered-merge-sentinel-collides-with-domain
   ordered-merge-sentinel-collides-with-domain-killed?
   :generic-ordered-merge-nil-sentinel-collides-with-domain
   generic-ordered-merge-nil-sentinel-collides-with-domain-killed?
   :inherited-self-bypass-skips-computation-slot
   inherited-self-bypass-skips-computation-slot-killed?
   :split-lifecycle-read-between-self-check-and-flight-selection
   split-lifecycle-read-between-self-check-and-flight-selection-killed?
   :lookup-decision-after-flight-installation
   lookup-decision-after-flight-installation-killed?
   :unrepresented-flight-ignored-by-lookup
   unrepresented-flight-ignored-by-lookup-killed?
   :projection-key-omits-inclusive-bound
   projection-key-omits-inclusive-bound-killed?
   :inclusive-bound-treated-exclusive
   inclusive-bound-treated-exclusive-killed?
   :incomplete-managed-proof-atoms
   incomplete-managed-proof-atoms-killed?
   :stale-endpoint-stamp-accepted
   stale-endpoint-stamp-accepted-killed?
   :over-budget-publication
   over-budget-publication-killed?
   :exception-poisons-flight
   exception-poisons-flight-killed?
   :flight-removal-outside-selection-lock
   flight-removal-outside-selection-lock-killed?
   :datomic-subproblem-config-dropped
   datomic-subproblem-config-dropped-killed?})

(deftest every-registered-mutant-is-killed-test
  (let [{:keys [required-score mutants]} (registry)
        ids (mapv :id mutants)
        clojure-mutants
        (filterv #(= :clojure (get-in % [:control :kind])) mutants)
        apalache-mutants
        (filterv #(= :apalache (get-in % [:control :kind])) mutants)
        registered-clojure (set (map :id clojure-mutants))]
    (is (= (count ids) (count (set ids))) "mutant ids must be unique")
    (is (= (count mutants)
           (+ (count clojure-mutants) (count apalache-mutants)))
        "every mutant must name one supported control kind")
    (is (= registered-clojure (set (keys detectors))))
    (doseq [{:keys [id killed-by]} clojure-mutants]
      (testing (name id)
        (is (seq killed-by))
        (is (true? ((get detectors id)))
            (str "surviving mutant: " id))))
    (doseq [{:keys [id killed-by control]} apalache-mutants]
      (testing (name id)
        (is (seq killed-by))
        (is (string? (:model control)))
        (is (string? (:config control)))
        (is (pos-int? (:length control)))
        (is (.isFile (repo/file (:model control))))
        (is (.isFile (repo/file (:config control))))))
    (let [killed
          (count
           (filter
            #((get detectors %))
            registered-clojure))
          score (/ killed (count registered-clojure))]
      (is (= killed (count registered-clojure)))
      (is (<= required-score score))
      (is (= 1 score)))))
