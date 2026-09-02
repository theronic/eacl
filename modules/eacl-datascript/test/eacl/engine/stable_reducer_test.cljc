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

(deftest zero-and-one-successor-fast-paths-refine-general-scheduling-test
  (let [base (fn [] {:stack [] :admitted (transient #{}) :admissions 0
                     :max-admissions 100 :max-stack 100 :maximum-stack 0})
        item {:kind :grant :rule {:node [:a :b]} :resource-eid 7}
        residual {:kind :grant :rule {:node [:r :p]} :resource-eid 9}
        freeze (fn [state]
                 (assoc state :admitted (persistent! (:admitted state))))]
    (is (= (freeze (reducer/schedule (base) residual []))
           (freeze (reducer/schedule (base) residual [nil nil]))))
    (is (= (freeze (reducer/schedule (base) residual [item]))
           (freeze (reducer/schedule (base) residual [item nil]))))
    (is (= [9 7]
           (mapv :resource-eid
                 (:stack (reducer/schedule (base) residual [item])))))
    (is (= 7
           (:resource-eid
            (peek (:stack (reducer/schedule (base) residual [item]))))))))

(deftest exact-count-sink-retains-no-result-history-test
  (let [fixture (fixture-for :group-star)
        {:keys [db adapter]} (seeded-adapter fixture)
        plan (sealed-plan/seal-plan
              adapter [(:resource-type fixture) (:permission fixture)])
        principal (val (first (:principals fixture)))
        collected
        (reducer/run-forward
         {:adapter adapter :plan plan :subject-type :user
          :subject-eid (eid db (:id principal))
          :target reducer/exhaustion-target})
        counted
        (reducer/run-forward
         {:adapter adapter :plan plan :subject-type :user
          :subject-eid (eid db (:id principal))
          :target reducer/exhaustion-target
          :result-sink :count})]
    (is (= (count (:results collected)) (:discovered counted)))
    (is (= [] (:results counted)))
    (is (= (:admissions collected) (:admissions counted)))
    (is (= (:commands collected) (:commands counted)))
    (is (= (:fetched-values collected) (:fetched-values counted)))))

(deftest bounded-window-sink-retention-is-prefix-independent-test
  (let [rule {:rule :relation
              :node [:document :view]
              :resource-type :document
              :permission :view
              :relation-eid 101
              :subject-type :user
              :ordinal 0
              :rank 1}
        plan {:root [:document :view]
              :indexes {:forward-seeds {:user [rule]}
                        :forward-consumers {}}}
        value-count 257
        fetch-fn
        (fn [{:keys [bound-eid limit]}]
          (let [start (inc (or bound-eid -1))]
            (vec (range start (min value-count (+ start limit))))))
        options {:fetch-fn fetch-fn :plan plan :subject-type :user
                 :subject-eid 1 :target reducer/exhaustion-target
                 :physical-chunk-size 17}
        collected (reducer/run-forward options)
        windowed (reducer/run-forward
                  (assoc options :result-sink :window
                         :result-window-size 7))]
    (is (= (subvec (:results collected) (- value-count 7))
           (:results windowed)))
    (is (= value-count (:discovered windowed)))
    (is (= 7 (count (:results windowed))))
    (is (= (:admissions collected) (:admissions windowed)))
    (is (= (:commands collected) (:commands windowed)))
    (is (= (:fetched-values collected) (:fetched-values windowed)))))

(deftest staged-limit-failure-commits-neither-fast-nor-general-path-test
  (let [item (fn [eid]
               {:kind :grant :rule {:node [:document :view]}
                :resource-eid eid})
        assert-unchanged!
        (fn [successors]
          (let [state {:stack [:sentinel]
                       :admitted (transient #{})
                       :admissions 9 :max-admissions 9
                       :max-stack 100 :maximum-stack 1}]
            (try
              (reducer/schedule state nil successors)
              (is false "the admission limit must reject the transition")
              (catch #?(:clj clojure.lang.ExceptionInfo
                        :cljs cljs.core.ExceptionInfo) error
                (is (= :max-admissions (:limit (ex-data error))))
                (is (= [:sentinel] (:stack state)))
                (is (empty? (persistent! (:admitted state))))))))]
    (assert-unchanged! [(item 1)])
    (assert-unchanged! [(item 1) (item 2)])))

(deftest sidecar-uses-indexed-vectors-and-incremental-bounds-test
  (let [fixture (fixture-for :group-star)
        {:keys [db adapter]} (seeded-adapter fixture)
        plan (sealed-plan/seal-plan
              adapter [(:resource-type fixture) (:permission fixture)])
        principal (val (first (:principals fixture)))
        adapter-fetch (reducer/adapter-fetch-fn adapter)
        routed-vectors (atom [])
        observed-states (atom [])
        fetch-fn (fn [descriptor]
                   (let [values (into [] (take (:limit descriptor))
                                      (adapter-fetch descriptor))]
                     (swap! routed-vectors conj values)
                     values))
        result
        (reducer/run-forward
         {:fetch-fn fetch-fn :plan plan :subject-type :user
          :subject-eid (eid db (:id principal))
          :target 3 :physical-chunk-size 7 :sidecar-cap 2
          :cut-point! #(swap! observed-states conj %)})
        states (conj @observed-states result)
        entries (vals (:sidecar result))
        current-oracle
        (reduce + 0
                (map #(- (count (:values %)) (:index %)) entries))
        pending-order (- (count (:sidecar-order result))
                         (:sidecar-order-index result))]
    (is (every? vector? (map :values entries)))
    (is (every? pos-int? (map :index entries)))
    (is (every? #(identical? % (reducer/bounded-vector % 7))
                @routed-vectors)
        "a bounded routed vector is reused rather than recopied")
    (is (= current-oracle (:current-sidecar-values result)))
    (is (<= (:current-sidecar-values result)
            (:maximum-sidecar-values result)))
    (is (<= (count entries) 2))
    (is (<= pending-order 4)
        "generation-stamped recency metadata stays capacity-bounded")
    (loop [states states
           maximum-values 0
           maximum-buffers 0]
      (when-let [state (first states)]
        (let [buffers (vals (:sidecar state))
              current (reduce + 0
                              (map #(- (count (:values %)) (:index %))
                                   buffers))
              maximum-values (max maximum-values current)
              maximum-buffers (max maximum-buffers (count buffers))]
          (is (= current (:current-sidecar-values state)))
          (is (= maximum-values (:maximum-sidecar-values state)))
          (is (= maximum-buffers (:maximum-sidecar-buffers state)))
          (is (<= (- (count (:sidecar-order state))
                     (:sidecar-order-index state))
                  4))
          (recur (next states) maximum-values maximum-buffers))))))

(deftest consuming-one-physical-buffer-does-not-readmit-it-test
  (let [rule {:rule :relation
              :node [:document :view]
              :resource-type :document
              :permission :view
              :relation-eid 101
              :subject-type :user
              :ordinal 0
              :rank 1}
        plan {:root [:document :view]
              :indexes {:forward-seeds {:user [rule]}
                        :forward-consumers {}}}
        result
        (reducer/run-forward
         {:fetch-fn (fn [_] [0 1 2 3 4 5 6])
          :plan plan
          :subject-type :user
          :subject-eid 9
          :target 3
          :physical-chunk-size 7
          :sidecar-cap 2})
        entry (first (vals (:sidecar result)))]
    (is (= [0 1 2] (:results result)))
    (is (= 1 (:commands result)))
    (is (= 1 (count (:sidecar-order result)))
        "one fetched buffer has one recency admission, not one per value")
    (is (= 3 (:index entry)))
    (is (= 4 (:current-sidecar-values result)))))
