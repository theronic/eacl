(ns eacl.engine.stable-reducer-test
  "Differential and property gates for the production sealed planner and
  stable reducer (tasks 4.5, 5.5 groundwork).

  - Denotation equality against the frozen current-engine baselines
    (exploration/baselines/) for every fixture, both directions.
  - Physical-chunk-width and buffer-retention invariance of the exact
    result sequence (one-value logical release makes order chunk-invariant).
  - Target-prefix stability (pagination composes from one canonical
    sequence).
  - The interior-admission-key counterexample: entity-only grant keys would
    lose results on permission-alias chains."
  (:require [clojure.string :as string]
            [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            #?(:clj [eacl.baseline.capture :as capture])
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.engine.checkpoint-fixtures :as portable]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-reducer :as reducer]))

(defn- fixture-for
  [fixture-key]
  #?(:clj ((get capture/fixtures fixture-key))
     :cljs (portable/fixture fixture-key)))

(defn- seed-fixture-client!
  [fixture]
  #?(:clj (capture/seed-client! fixture)
     :cljs (portable/seed-client! fixture)))

(defn- seeded-adapter
  [fixture]
  (let [{:keys [conn]} (seed-fixture-client! fixture)
        db (ds/db conn)]
    {:db db
     :adapter (datascript-backend/basis-adapter
               db
               {:object-id->entid
                (fn [snapshot object-id]
                  (ds/entid snapshot [:eacl/id object-id]))
                :entid->object-id
                (fn [snapshot internal-id]
                  (:eacl/id (ds/entity snapshot internal-id)))})}))

(defn- eid [db external-id] (ds/entid db [:eacl/id external-id]))
(defn- external [db internal-id] (:eacl/id (ds/entity db internal-id)))

(defn- strip-type [ref] (second (string/split ref #":" 2)))

(defn- forward-ids
  [{:keys [db adapter]} plan subject-id options]
  (if-let [subject-eid (eid db subject-id)]
    (let [result (reducer/run-forward
                  (merge {:adapter adapter :plan plan
                          :subject-type :user
                          :subject-eid subject-eid
                          :target 100000}
                         options))]
      (mapv #(external db %) (:results result)))
    ;; an unknown external principal has no internal identity; the public
    ;; route resolves-or-returns-empty before the reducer runs
    []))

(defn- reverse-ids
  [{:keys [db adapter]} plan resource-id options]
  (let [result (reducer/run-reverse
                (merge {:adapter adapter :plan plan
                        :subject-type :user
                        :resource-eid (eid db resource-id)
                        :target 100000}
                       options))]
    (mapv #(external db %) (:results result))))

(def ^:private semantic-state-keys
  #{:stack :admitted :admissions :transitions :commands :fetched-values
    :discovered :maximum-stack})

(defn- closed-checkpoint-data?
  [value]
  (cond
    (or (nil? value)
        (boolean? value)
        (number? value)
        (string? value)
        (keyword? value))
    true

    #?(:clj (instance? eacl.engine.stable_reducer.AdmissionKey value)
       :cljs false)
    true

    (vector? value)
    (every? closed-checkpoint-data? value)

    (map? value)
    (and (every? closed-checkpoint-data? (keys value))
         (every? closed-checkpoint-data? (vals value)))

    (set? value)
    (every? closed-checkpoint-data? value)

    :else false))

(deftest history-free-state-is-closed-semantic-data-test
  (let [fixture (fixture-for :group-star)
        {:keys [db adapter] :as seeded} (seeded-adapter fixture)
        plan (sealed-plan/seal-plan
              adapter [(:resource-type fixture) (:permission fixture)])
        principal (val (first (:principals fixture)))
        finished
        (reducer/run-forward
         {:adapter adapter
          :plan plan
          :subject-type :user
          :subject-eid (eid db (:id principal))
          :target 4})
        checkpoint (reducer/history-free finished)]
    (is (= semantic-state-keys (set (keys checkpoint))))
    (is (closed-checkpoint-data? checkpoint))
    (is (not-any? #(contains? checkpoint %)
                  [:fetch-fn :results :buffers :pending :adapter :db
                   :reader :configuration]))
    (is (not (fn? checkpoint)))
    (is (not (seq? checkpoint)))
    (is (seq (:admitted checkpoint)))
    (is (every?
         #?(:clj #(instance?
                   eacl.engine.stable_reducer.AdmissionKey %)
            :cljs vector?)
         (:admitted checkpoint))
        "AdmissionKey is closed and hash-stable on each runtime")
    (is (= (mapv #(external db %) (:results finished))
           (forward-ids seeded plan (:id principal) {:target 4})))))

#?(:clj
   (deftest frozen-baseline-denotation-differential-test
     (doseq [fixture-key (keys capture/fixtures)]
       (testing (str fixture-key)
         (let [fixture ((get capture/fixtures fixture-key))
               snapshot (capture/read-snapshot fixture-key)
               seeded (seeded-adapter fixture)
               plan (sealed-plan/seal-plan
                     (:adapter seeded)
                     [(:resource-type fixture) (:permission fixture)])]
           (testing "forward denotations equal the frozen current engines"
             (doseq [[principal-key principal] (:principals fixture)]
               (let [frozen (mapv strip-type
                                  (get-in snapshot
                                          [:forward principal-key :denotation]))
                     fresh (forward-ids seeded plan (:id principal) {})]
                 (is (= (vec (sort frozen)) (vec (sort (distinct fresh))))
                     (str fixture-key " " principal-key))
                 (is (= (count fresh) (count (distinct fresh)))
                     (str fixture-key " " principal-key " duplicate-free")))))
           (testing "reverse denotations equal the frozen current engines"
             (doseq [[label resource] (:reverse-resources fixture)]
               (let [frozen-result (get-in snapshot [:reverse label])]
                 (when (= :ok (:outcome frozen-result))
                   (let [frozen (mapv strip-type (:denotation frozen-result))
                         fresh (reverse-ids seeded plan (:id resource) {})]
                     (is (= (vec (sort frozen))
                            (vec (sort (distinct fresh))))
                         (str fixture-key " " label))
                     (is (= (count fresh) (count (distinct fresh)))
                         (str fixture-key " " label " duplicate-free"))))))))))))

(deftest physical-width-and-retention-invariance-test
  (doseq [fixture-key [:explorer-acyclic :group-star :mutual-mixed
                       :cyclic-data :broad-union]]
    (testing (str fixture-key)
      (let [fixture (fixture-for fixture-key)
            seeded (seeded-adapter fixture)
            plan (sealed-plan/seal-plan
                  (:adapter seeded)
                  [(:resource-type fixture) (:permission fixture)])
            principal (val (first (:principals fixture)))
            reference (forward-ids seeded plan (:id principal)
                                   {:physical-chunk-size 1})]
        (doseq [options [{:physical-chunk-size 2}
                         {:physical-chunk-size 7}
                         {:physical-chunk-size 64}
                         {:physical-chunk-size 64 :sidecar-cap 0}
                         {:physical-chunk-size 7 :sidecar-cap 1}]]
          (is (= reference (forward-ids seeded plan (:id principal) options))
              (str fixture-key " " options)))))))

(deftest target-prefix-stability-test
  (let [fixture (fixture-for :explorer-acyclic)
        seeded (seeded-adapter fixture)
        plan (sealed-plan/seal-plan
              (:adapter seeded)
              [(:resource-type fixture) (:permission fixture)])
        principal (val (first (:principals fixture)))
        complete (forward-ids seeded plan (:id principal) {})]
    (doseq [target (range 1 (inc (count complete)))]
      (is (= (subvec complete 0 target)
             (forward-ids seeded plan (:id principal) {:target target}))
          (str "prefix at target " target)))))

(def alias-schema
  "The interior-admission-key counterexample: base/left/right form a
  permission-alias cycle; an entity-only interior grant key would merge the
  :base and :left grants for one entity and never reach the root emission."
  "definition user {}

   definition account {
     relation owner: user
     permission base = owner
     permission left = base + right
     permission right = left
   }")

(deftest interior-admission-keys-are-node-qualified-test
  (let [owner (eacl/spice-object :user "owner-1")
        account (eacl/spice-object :account "alias-account")
        fixture {:schema alias-schema
                 :objects [owner account]
                 :relationships [(eacl/->Relationship owner :owner account)]
                 :resource-type :account
                 :permission :left
                 :principals {:owner owner}
                 :reverse-resources {}}
        seeded (seeded-adapter fixture)
        plan (sealed-plan/seal-plan (:adapter seeded) [:account :left])]
    (is (= ["alias-account"] (forward-ids seeded plan "owner-1" {}))
        "the aliased account is emitted exactly once at the root")
    (is (= ["owner-1"] (reverse-ids seeded plan "alias-account" {}))
        "reverse traversal terminates the alias cycle and finds the owner")))

(deftest schedule-admits-each-work-id-once-per-batch-test
  ;; StableReducer.Admit folds `seen` through one successor batch, so two
  ;; equal successors admit once; `schedule` must refine that literally
  ;; (a batch-internal duplicate is one admission and one stack entry) and
  ;; must skip nil items without truncating the batch.
  (let [schedule reducer/schedule
        state {:stack [] :admitted (transient #{}) :admissions 0
               :max-admissions 100 :max-stack 100 :maximum-stack 0}
        item {:kind :grant :rule {:node [:a :b]} :resource-eid 7}
        other {:kind :grant :rule {:node [:a :b]} :resource-eid 8}
        scheduled (schedule state nil [nil item item other nil])]
    (is (= 2 (:admissions scheduled)))
    (is (= [8 7] (mapv :resource-eid (:stack scheduled)))
        "canonical order: the first fresh item is on top")
    (is (= 7 (:resource-eid (peek (:stack scheduled)))))
    (let [again (schedule (assoc scheduled
                                 :admitted (transient (persistent! (:admitted scheduled))))
                          nil [item other])]
      (is (= 2 (:admissions again)) "already-admitted work is not re-admitted"))))
