(ns eacl.cache-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
            :refer [deftest is testing]]
            #?(:clj [clojure.edn :as edn])
            [eacl.cache :as cache]
            [eacl.cache-identity :as cache-identity]
            [eacl.core :as eacl]
            [eacl.formal.current-cache-refinement :as cache-refinement]
            #?(:clj [eacl.secure-format :as secure-format])
            [eacl.subproblem-cache :as subproblem]
            #?(:clj [eacl.test-support.repo :as repo])
            [eacl.verified-kernel :as verified]))

(deftest successful-result-query-removes-only-invocation-controls-test
  (let [token (eacl/cancellation-token)
        semantic
        {:operation :lookup-resources
         :subject {:type :user :id "alice"}
         :permission :view
         :resource/type :document
         :first 17
         :after {:ordinal 41}
         :evaluation :complete-denotation
         :aggregate-limits {:candidate-window 23}
         :consistency :fully-consistent}
        controlled
        (assoc semantic
               :timeout-ms 173
               :cancellation-token token
               :cache? false
               :populate-cache? false)]
    (is (= #{:timeout-ms :cancellation-token :cache? :populate-cache?}
           cache-identity/invocation-control-keys))
    (is (= semantic
           (cache-identity/successful-result-query controlled)))
    (doseq [[key changed]
            [[:subject {:type :user :id "bob"}]
             [:permission :edit]
             [:resource/type :folder]
             [:first 18]
             [:after {:ordinal 42}]
             [:evaluation :demand]
             [:aggregate-limits {:candidate-window 22}]
             [:consistency :minimize-latency]]]
      (is (not=
           (cache-identity/successful-result-query controlled)
           (cache-identity/successful-result-query
            (assoc controlled key changed)))
          (name key)))))

(deftest lookup-page-identity-is-control-independent-but-boundary-sensitive-test
  (let [token-a (eacl/cancellation-token)
        token-b (eacl/cancellation-token)
        public
        {:subject {:type :user :id "alice"}
         :permission :view
         :resource/type :document
         :first 10
         :after "signed-transport-a"
         :consistency :fully-consistent
         :timeout-ms 100
         :cancellation-token token-a
         :cache? true
         :populate-cache? true}
        internal
        {:subject {:type :user :id 1}
         :permission :view
         :resource/type :document
         :first 10
         :after {:ordinal 7}
         :timeout-ms 100
         :cancellation-token token-a}
        identity (cache/lookup-page-query-identity public internal)
        varied-controls
        (cache/lookup-page-query-identity
         (assoc public
                :timeout-ms 999
                :cancellation-token token-b
                :cache? false
                :populate-cache? false)
         (assoc internal
                :timeout-ms 999
                :cancellation-token token-b
                :cache? false
                :populate-cache? false))]
    (is (= identity varied-controls))
    (is (not (contains? (:public identity) :after))
        "authenticated public transport is not semantic position")
    (is (= {:ordinal 7} (get-in identity [:internal :after])))
    (is (not=
         identity
         (cache/lookup-page-query-identity
          public (assoc internal :after {:ordinal 8}))))
    (is (not=
         identity
         (cache/lookup-page-query-identity
          (assoc public :first 11) (assoc internal :first 11))))))

(deftest current-cache-host-specialization-exhausts-generated-domain-test
  (doseq [stage [:eligibility :generation :exact-entry
                 :exact-only-entry :managed-entry]
          available? [false true]]
    (is (= (verified/decide
            subproblem/default-decision-kernel
            :current-cache-decision
            {:stage stage :available? available?})
           (cache/specialized-current-cache-action stage available?))
        (str stage " " available?))))

(deftest stale-or-incomplete-current-cache-refinement-is-not-authorized-test
  (is (cache/current-cache-specialization-authorized?
       subproblem/default-decision-kernel))
  (let [stale-kernel subproblem/default-decision-kernel
        stale-evidence (assoc subproblem/default-current-cache-refinement
                              :artifact-sha256 "stale")]
    (with-redefs [subproblem/default-decision-kernel stale-kernel
                  subproblem/default-current-cache-refinement stale-evidence]
      (is (false?
           (cache/current-cache-specialization-authorized? stale-kernel)))))
  (is (false?
       (cache-refinement/complete-mapping?
        (dissoc cache-refinement/current-cache-mapping
                [:managed-entry false])))))

#?(:clj
   (deftest current-cache-refinement-artifact-binds-all-sources-test
     (let [hex-digest
           (fn [path]
             (let [digest (java.security.MessageDigest/getInstance "SHA-256")
                   bytes (.digest digest
                                  (.getBytes (slurp path) "UTF-8"))]
               (apply str
                      (map #(format "%02x" (bit-and (int %) 255)) bytes))))
           artifact-path
           (repo/file "formal" "verification"
                      "current-cache-specialization.edn")
           artifact (edn/read-string (slurp artifact-path))]
       (is (= cache-refinement/artifact-sha256
              (hex-digest artifact-path)))
       (is (= cache-refinement/current-cache-domain (:domain artifact)))
       (is (= cache-refinement/current-cache-mapping (:mapping artifact)))
       (is (= cache-refinement/mapping-digest
              (secure-format/canonical-digest
               cache-refinement/artifact-domain
               {:domain (:domain artifact) :mapping (:mapping artifact)})))
       (doseq [[path expected] (:source-digests artifact)]
         (is (= expected (hex-digest (repo/file path))) path)))))

(defn- snapshot-object
  []
  #?(:clj (Object.)
     :cljs (js-obj)))

(def ^:private test-source-scope
  {:backend :test
   :source-id :source
   :branch nil})

(def ^:private test-lineage
  {:source-scope test-source-scope
   :source-lifecycle :lifecycle-a})

(defn- basis-key
  ([revision]
   (basis-key :lifecycle-a revision :ordinary))
  ([lifecycle revision basis-kind]
   {:key-version 2
    :backend :test
    :basis-identity
    (assoc test-source-scope
           :source-lifecycle lifecycle
           :basis-kind basis-kind
           :revision revision
           :exact-locator revision
           :backend-snapshot-id {:basis-t revision})
    :adapter-fingerprint :adapter-v1
    :identity-contract :identity-v1}))

(defn- basis-context
  [snapshot revision]
  {:snapshot snapshot
   :snapshot-order revision
   :exact-basis-key (basis-key revision)
   :cache-basis revision
   :managed-subproblem-scope test-lineage})

(deftest portable-basis-cache-round-trip-test
  (let [options {:retained-bases 2
                 :subproblem-cache {:projection-max-weight 64
                                    :denotation-max-weight 64
                                    :answer-max-weight 4096}}
        original (cache/basis-cache options)
        restored (cache/basis-cache options)
        key (basis-key 7)
        answer (eacl/spice-object "document" "alpha")
        resolve
        (fn [store computed]
          (cache/resolve-exact!
           store
           {:snapshot (snapshot-object)
            :snapshot-order 7
            :exact-basis-key key
            :cache-basis {:basis-t 7}}
           [:check :alpha] :decision (constantly true)
           (constantly computed)))
        before (cache/cache-content-revision original)]
    (is (= answer (:value (resolve original answer))))
    (let [after-publication (cache/cache-content-revision original)]
      (is (> after-publication before))
      (is (true? (:cached? (resolve original :wrong))))
      (is (= after-publication (cache/cache-content-revision original))
          "exact hits and LRU touches do not dirty persistent content"))
    (let [snapshot (cache/export-basis-snapshot
                    original {:max-weight 8192 :max-entries 64})]
      (is (= cache/basis-snapshot-format (:format snapshot)))
      (is (= 1 (get-in snapshot [:generation-counts :exact])))
      (is (= 1 (:entry-count snapshot)))
      (is (nil? (get-in snapshot [:exact 0 :snapshot]))
          "backend snapshots are absent from the public value")
      (is (true? (:restored?
                  (cache/restore-basis-snapshot!
                   restored snapshot {:max-weight 8192 :max-entries 64}))))
      (is (= answer (:value (resolve restored :wrong)))
          "a matching exact basis can reuse the restored record value")
      (is (true? (:cached? (resolve restored :wrong))))
      (let [revision-before-failure (cache/cache-content-revision restored)]
        (is (= :eacl/cache-snapshot-incompatible
               (try
                 (cache/restore-basis-snapshot!
                  restored (assoc snapshot :entry-count 0)
                  {:max-weight 8192 :max-entries 64})
                 nil
                 (catch #?(:clj clojure.lang.ExceptionInfo
                           :cljs cljs.core.ExceptionInfo)
                        error
                   (:type (ex-data error))))))
        (is (= revision-before-failure
               (cache/cache-content-revision restored)))
        (is (true? (:cached? (resolve restored :wrong)))
            "failed validation leaves the visible lifecycle intact")))))

(deftest restored-timeout-bearing-answer-key-is-unreachable-test
  (let [options {:retained-bases 1
                 :subproblem-cache {:projection-max-weight 64
                                    :denotation-max-weight 64
                                    :answer-max-weight 8192}}
        context (basis-context (snapshot-object) 7)
        legacy-key
        {:operation :count-resources
         :query {:public {:subject :alice
                          :permission :view
                          :timeout-ms 100}
                 :internal {:subject 1 :permission :view}}
         :evaluation :demand
         :demand {:kind :exact-count}
         :engine-version 8
         :order-abi 2
         :compiler-plan-compatibility :test-plan
         :cache-value-abi 2}
        canonical-key
        (update-in legacy-key [:query :public] dissoc :timeout-ms)
        resolve
        (fn [store key computed]
          (cache/resolve-exact!
           store context key :count-resources (constantly true)
           (constantly computed)))
        round-trip
        (fn [entry-key value]
          (let [source (cache/basis-cache options)
                target (cache/basis-cache options)]
            (is (= value (:value (resolve source entry-key value))))
            (let [snapshot
                  (cache/export-basis-snapshot
                   source {:max-weight 16384 :max-entries 64})]
              (is (= :eacl.cache/basis-snapshot-v1 (:format snapshot)))
              (cache/restore-basis-snapshot!
               target snapshot {:max-weight 16384 :max-entries 64})
              target)))]
    (let [restored-legacy (round-trip legacy-key :legacy-timeout-value)
          canonical-result
          (resolve restored-legacy canonical-key :fresh-canonical-value)]
      (is (false? (:cached? canonical-result))
          "the corrected canonical key cannot reach a restored timeout key")
      (is (= :fresh-canonical-value (:value canonical-result)))
      (is (true? (:cached?
                  (resolve restored-legacy canonical-key :wrong-value)))))
    (let [restored-canonical
          (round-trip canonical-key :canonical-value)
          canonical-hit
          (resolve restored-canonical canonical-key :wrong-value)]
      (is (true? (:cached? canonical-hit))
          "an already canonical compatible snapshot entry remains reusable")
      (is (= :canonical-value (:value canonical-hit))))))

(deftest cache-content-revision-advances-on-expiry-test
  (let [store (cache/basis-cache)
        before (cache/cache-content-revision store)]
    (cache/expire-basis-cache! store)
    (is (> (cache/cache-content-revision store) before))))

(deftest basis-cache-is-client-private-test
  (let [native-cache (cache/basis-cache)]
    (is (= :client-private-cache-reuse
           (try
             (cache/basis-cache-for-option native-cache)
             nil
             (catch #?(:clj clojure.lang.ExceptionInfo
                       :cljs cljs.core.ExceptionInfo)
                    error
               (:reason (ex-data error))))))
    (is (not (identical? (cache/basis-cache-for-option nil)
                         (cache/basis-cache-for-option nil)))
        "each client option normalization creates a distinct native cache")))

(deftest basis-cache-test
  (let [store (cache/basis-cache {:max-entries 32})
        snapshot-1 (snapshot-object)
        snapshot-2 (snapshot-object)
        snapshot-3 (snapshot-object)
        snapshot-4 (snapshot-object)
        computations (atom 0)
        stamp-reads (atom 0)
        answer (atom true)
        context
        (fn [snapshot order schema-generation dependency-stamp]
          (assoc (basis-context snapshot order)
                 :managed-key-fn
                 (fn []
                   (swap! stamp-reads inc)
                   {:schema-generation schema-generation
                    :dependency-stamp dependency-stamp})))
        resolve
        (fn [current-context]
          (cache/resolve-basis!
           store current-context
           [:can? :query] :decision boolean?
           (fn []
             (swap! computations inc)
             @answer)))]
    (testing "the exact hot path does not read dependency stamps"
      (is (= {:value true :cached? false :cache-tier nil}
             (select-keys
              (resolve
               (context
                 snapshot-1 1
                 10
                 20))
              [:value :cached? :cache-tier])))
      (is (= {:value true :cached? true :cache-tier :exact-basis}
             (select-keys
              (resolve
               (context
                 snapshot-1 1
                 10
                 20))
              [:value :cached? :cache-tier])))
      (is (= 1 @computations))
      (is (= 1 @stamp-reads)))

    (testing "an unrelated transaction lifts through the managed tier"
      (is (= {:value true :cached? true :cache-tier :managed-current}
             (select-keys
              (resolve
               (context
                 snapshot-2 2
                 10
                 20))
              [:value :cached? :cache-tier])))
      (is (= 1 @computations))
      (is (= 2 @stamp-reads))
      (is (= :exact-basis
             (:cache-tier
              (resolve
               (context
                 snapshot-2 2
                 10
                 20)))))
      (is (= 2 @stamp-reads)
          "promotion makes the next hit independent of stamp extraction"))

    (testing "same-tx relation and schema mutation identities cannot collide"
      (reset! answer false)
      (is (false? (:cached?
                   (resolve
                    (context
                      snapshot-3 3
                      10
                      21)))))
      (reset! answer true)
      (is (false? (:cached?
                   (resolve
                    (context
                      snapshot-4 4
                      22
                      22)))))
      (is (= 3 @computations)))

    (testing "explicit bypass neither reads nor publishes"
      (let [before-stamps @stamp-reads
            bypass-context
            (assoc (basis-context snapshot-4 4)
                   :cacheable? false
                   :managed-key-fn
                   #(throw (ex-info "must not read stamps" {})))]
        (is (false? (:cached? (resolve bypass-context))))
        (is (= before-stamps @stamp-reads))
        (is (= 4 @computations))))))

(deftest basis-generation-late-publication-test
  (let [store (cache/basis-cache)
        old-snapshot (snapshot-object)
        new-snapshot (snapshot-object)
        key [:can? :late-publication]
        context
        (fn [snapshot order]
          (basis-context snapshot order))
        nested? (atom false)
        old-answer
        (cache/resolve-basis!
         store (context old-snapshot 1)
         key :decision boolean?
         (fn []
           (when (compare-and-set! nested? false true)
             (cache/resolve-basis!
              store (context new-snapshot 2)
              key :decision boolean?
              (constantly false)))
           true))]
    (is (true? (:value old-answer)))
    (is (false?
         (:value
          (cache/resolve-basis!
           store (context new-snapshot 2)
           key :decision boolean?
           (constantly true))))
        "old publication cannot repopulate the installed newer generation")
    (is (= :exact-basis
           (:cache-tier
            (cache/resolve-basis!
             store (context new-snapshot 2)
             key :decision boolean?
             (constantly true)))))))

(deftest exact-basis-completed-answer-cache-test
  (let [store (cache/basis-cache)
        current-snapshot (snapshot-object)
        semantic-key {:operation :can? :query [:alice :read :document]}
        key-1 (basis-key :lifecycle-a 1 :ordinary)
        key-2 (basis-key :lifecycle-a 2 :ordinary)
        key-1-replaced (basis-key :lifecycle-b 1 :ordinary)
        computations (atom 0)
        resolve-exact
        (fn [exact-basis-key value]
          (cache/resolve-exact!
           store
           {:snapshot (:basis-identity exact-basis-key)
            :exact-basis-key exact-basis-key
            :cache-basis
            (get-in exact-basis-key
                    [:basis-identity :backend-snapshot-id])}
           semantic-key :decision boolean?
           (fn []
             (swap! computations inc)
             value)))]
    (testing "a current answer seeds the matching authenticated exact tier"
      (cache/resolve-basis!
       store
       (assoc (basis-context current-snapshot 1)
              :exact-basis-key key-1
              :cache-basis {:basis-t 1})
       semantic-key :decision boolean? (constantly true))
      (let [answer (resolve-exact key-1 false)]
        (is (true? (:value answer)))
        (is (true? (:cached? answer)))
        (is (= :exact-basis (:cache-tier answer)))
        (is (zero? @computations))))

    (testing "different revisions and repeated numbers after lifecycle rotation cannot collide"
      (is (false? (:value (resolve-exact key-2 false))))
      (is (false? (:cached? (resolve-exact key-1-replaced false))))
      (is (= 2 @computations))
      (is (true? (:value (resolve-exact key-1 false)))
          "the older retained snapshot remains independently addressable")
      (is (false? (:value (resolve-exact key-2 true)))
          "the newer retained snapshot keeps its own completed answer"))

    (testing "explicit cache lifecycle expiry detaches every historical answer"
      (cache/expire-basis-cache! store)
      (is (false? (:cached? (resolve-exact key-1 false))))
      (is (false? (:value (resolve-exact key-1 false))))
      (is (= 3 @computations)))))

(defn- ordinary-exact-key
  [revision]
  (basis-key revision))

(deftest exact-basis-does-not-republish-on-every-hit-test
  (let [store (cache/basis-cache)
        snapshot (snapshot-object)
        semantic-key {:operation :can? :query [:alice :read :document]}
        current
        (fn []
          (cache/resolve-basis!
           store
           (assoc (basis-context snapshot 1)
                  :cache-basis {:basis-t 1})
           semantic-key :decision boolean? (constantly true)))]
    (dotimes [_ 8] (current))
    (let [stats (get-in (cache/basis-cache-stats store)
                        [:subproblems :tiers :answer])
          races (get-in (cache/basis-cache-stats store)
                        [:subproblems :publication-races])]
      (is (= 1 (:entries stats))
          "the first computation seeds exactly one basis generation")
      (is (zero? (or races 0))
          "a cache hit must not republish an answer the tier already holds"))))

(deftest exact-basis-bypasses-an-unkeyable-identity-test
  (let [store (cache/basis-cache)
        computations (atom 0)
        resolve-with
        (fn [snapshot-key]
          (cache/resolve-exact!
           store
           {:exact-basis-key snapshot-key :cache-basis {:basis-t 1}}
           {:operation :can?} :decision boolean?
           (fn [] (swap! computations inc) true)))]
    (testing "a view that cannot mint a canonical identity computes uncached"
      (doseq [unkeyable [nil
                         (dissoc (ordinary-exact-key 1) :adapter-fingerprint)
                         (assoc (ordinary-exact-key 1)
                                :basis-identity
                                (assoc (:basis-identity
                                        (ordinary-exact-key 1))
                                       :basis-kind :filtered))]]
        (let [answer (resolve-with unkeyable)]
          (is (true? (:value answer)))
          (is (false? (:cached? answer)))
          (is (nil? (:cache-tier answer))
              "an unkeyable snapshot bypasses the tier instead of failing"))))
    (is (= 3 @computations))
    (is (= 3 (:bypasses (cache/basis-cache-stats store))))))

(deftest exact-basis-hit-reports-the-selected-basis-test
  (let [store (cache/basis-cache)
        snapshot (snapshot-object)
        semantic-key {:operation :can? :query [:alice :read :document]}
        key-1 (ordinary-exact-key 1)]
    ;; Seed the tier from a current answer whose recorded basis is deliberately
    ;; older than the basis a later exact request selects, as a proof-lifted
    ;; managed answer would be.
    (cache/resolve-basis!
     store
     (assoc (basis-context snapshot 1)
            :cache-basis {:basis-t :stale-origin})
     semantic-key :decision boolean? (constantly true))
    (let [answer
          (cache/resolve-exact!
           store
           {:snapshot (:basis-identity key-1)
            :exact-basis-key key-1
            :cache-basis {:basis-t 1}}
           semantic-key :decision boolean? (constantly false))]
      (is (true? (:cached? answer)))
      (is (= {:basis-t 1} (:cache-basis answer))
          "cache basis is rebuilt from the selected snapshot, not copied"))))

(deftest exact-basis-generations-use-bounded-lru-retention-test
  (let [store (cache/basis-cache {:retained-bases 2})
        computations (atom {})
        resolve
        (fn [revision]
          (cache/resolve-basis!
           store (basis-context (snapshot-object) revision)
           :same-query :decision integer?
           (fn []
             (swap! computations update revision (fnil inc 0))
             revision)))]
    (resolve 1)
    (resolve 2)
    (is (= :exact-basis (:cache-tier (resolve 1)))
        "touching an older basis makes it most recently used")
    (resolve 3)
    (is (= 2 (:retained-bases (cache/basis-cache-stats store))))
    (is (false? (:cached? (resolve 2)))
        "the least-recently-used basis recomputes after eviction")
    (is (= {1 1, 2 2, 3 1} @computations))))

(deftest exact-generation-hits-are-nonserializing-with-optional-telemetry-test
  (let [store (cache/basis-cache {:retained-bases 2 :telemetry? false})
        computations (atom {})
        resolve
        (fn [revision]
          (cache/resolve-basis!
           store (basis-context (snapshot-object) revision)
           :same-query :decision integer?
           (fn []
             (swap! computations update revision (fnil inc 0))
             revision)))]
    (resolve 1)
    (resolve 2)
    (let [lifecycle @(:lifecycle store)
          bases (:bases lifecycle)
          basis-state @bases
          metrics-state @(:metrics store)]
      (is (= :exact-basis (:cache-tier (resolve 1))))
      (is (identical? basis-state @bases)
          "a generation hit does not swap the shared lifecycle state")
      (is (identical? metrics-state @(:metrics store))
          "disabled cache telemetry performs no observer mutation"))
    (resolve 3)
    (is (false? (:cached? (resolve 2)))
        "the coalesced exact-generation touch still determines eviction")
    (let [stats (cache/basis-cache-stats store)]
      (is (false? (:telemetry-enabled? stats)))
      (is (zero? (:exact-hits stats)))
      (is (false? (get-in stats
                          [:subproblems :telemetry-enabled?]))))))

(deftest basis-kind-is-part-of-exact-cache-identity-test
  (let [store (cache/basis-cache)
        ordinary (basis-key :lifecycle-a 7 :ordinary)
        as-of (basis-key :lifecycle-a 7 :as-of)
        resolve
        (fn [key value]
          (cache/resolve-exact!
           store
           {:snapshot (:basis-identity key)
            :exact-basis-key key
            :cache-basis
            (get-in key [:basis-identity :backend-snapshot-id])}
           :same-query :decision boolean? (constantly value)))]
    (is (true? (:value (resolve ordinary true))))
    (is (false? (:value (resolve as-of false)))
        "ordinary and as-of values at the same revision cannot collide")
    (is (true? (:value (resolve ordinary false))))
    (is (false? (:value (resolve as-of true))))
    (is (= 2 (:retained-bases (cache/basis-cache-stats store))))))

(deftest managed-lifting-is-revision-direction-agnostic-test
  (let [store (cache/basis-cache)
        computations (atom 0)
        context
        (fn [revision]
          (assoc (basis-context (snapshot-object) revision)
                 :managed-key-fn
                 (constantly {:schema-generation 10
                              :dependency-stamp 20})))
        resolve
        (fn [revision value]
          (cache/resolve-basis!
           store (context revision)
           :same-query :decision boolean?
           (fn []
             (swap! computations inc)
             value)))]
    (is (true? (:value (resolve 2 true))))
    (let [older (resolve 1 false)]
      (is (true? (:value older))
          "equal complete proofs preserve the answer at an older basis")
      (is (= :managed-current (:cache-tier older))))
    (is (= 1 @computations))))

(deftest nested-answers-weigh-more-than-scalar-answers-test
  (let [snapshot (snapshot-object)
        tree {:expanded-object {:type :document :id "doc"}
              :leaf {:subjects (mapv (fn [i]
                                       {:type :user :id (str "u" i)})
                                     (range 200))}}
        weight-of
        (fn [value]
          (let [store (cache/basis-cache)]
            (cache/resolve-basis!
             store
             (assoc (basis-context snapshot 1)
                    :cache-basis {:basis-t 1})
             {:operation :expand-permission-tree} :permission-tree
             map? (constantly value))
            (get-in (cache/basis-cache-stats store)
                    [:subproblems :tiers :answer :weight])))]
    (is (> (weight-of tree) (weight-of {:leaf {:subjects []}}))
        "a large nested answer must not weigh the same as an empty one")
    (is (> (weight-of tree) (* 100 128))
        "weight scales with the retained subjects, not a flat floor")))

(deftest basis-generation-expiry-test
  (let [store (cache/basis-cache)
        snapshot (snapshot-object)
        context (basis-context snapshot 1)
        calls (atom 0)
        resolve
        #(cache/resolve-basis!
          store context :key :decision boolean?
          (fn [] (swap! calls inc) true))]
    (is (false? (:cached? (resolve))))
    (is (true? (:cached? (resolve))))
    (cache/expire-basis-cache! store)
    (is (false? (:cached? (resolve))))
    (is (= 2 @calls))))

(deftest exact-generation-subproblem-lifecycle-test
  (let [store (cache/basis-cache)
        snapshot (snapshot-object)
        context (basis-context snapshot 1)
        projection-calls (atom 0)
        resolve
        (fn [top-level-key]
          (cache/resolve-basis!
           store context top-level-key :decision integer?
           (fn []
             (:value
              (subproblem/resolve-bound!
               :projection :shared-projection {}
               (fn []
                 (swap! projection-calls inc)
                 42))))))]
    (testing "distinct completed-answer keys share one exact projection"
      (is (= 42 (:value (resolve :top-level-a))))
      (is (= 42 (:value (resolve :top-level-b))))
      (is (= 1 @projection-calls))
      (is (zero? (:exact-hits (cache/basis-cache-stats store)))
          "different top-level keys must not be counted as final-answer hits")
      (is (= 1 (get-in (cache/basis-cache-stats store)
                       [:subproblems :projection-hits]))))
    (testing "expiry replaces the complete subproblem generation"
      (cache/expire-basis-cache! store)
      (is (= 42 (:value (resolve :top-level-c))))
      (is (= 2 @projection-calls))
      (is (zero? (get-in (cache/basis-cache-stats store)
                         [:subproblems :projection-hits]))))))

(deftest completed-answer-bypass-still-shares-current-subproblems-test
  (let [store (cache/basis-cache)
        snapshot (snapshot-object)
        context (assoc (basis-context snapshot 1)
                       :remember-answer? false)
        top-level-calls (atom 0)
        projection-calls (atom 0)
        resolve
        #(cache/resolve-basis!
          store context :same-top-level-key :decision integer?
          (fn []
            (swap! top-level-calls inc)
            (:value
             (subproblem/resolve-bound!
              :projection :shared-projection {}
              (fn []
                (swap! projection-calls inc)
                42)))))]
    (is (= 42 (:value (resolve))))
    (is (= 42 (:value (resolve))))
    (is (= 2 @top-level-calls)
        "remember-answer? false never reuses the completed answer")
    (is (= 1 @projection-calls)
        "the exact-basis subproblem remains reusable")
    (let [stats (cache/basis-cache-stats store)]
      (is (zero? (:exact-entries stats)))
      (is (= 2 (:bypasses stats)))
      (is (= 1 (get-in stats [:subproblems :projection-hits]))))))

#?(:clj
   (deftest delayed-subproblem-publication-cannot-resurrect-expired-generation-test
     (let [store (cache/basis-cache)
           snapshot-1 (snapshot-object)
           snapshot-2 (snapshot-object)
           context
           (fn [snapshot order]
             (basis-context snapshot order))
           started (promise)
           release (promise)
           old
           (future
             (cache/resolve-basis!
              store (context snapshot-1 1) :old :decision integer?
              (fn []
                (:value
                 (subproblem/resolve-bound!
                  :projection :shared {}
                  (fn []
                    (deliver started true)
                    @release
                    41))))))
           _ @started]
       (cache/expire-basis-cache! store)
       (is (= 42
              (:value
               (cache/resolve-basis!
                store (context snapshot-2 2) :new-a :decision integer?
                (fn []
                  (:value
                   (subproblem/resolve-bound!
                    :projection :shared {}
                    (constantly 42))))))))
       (deliver release true)
       (is (= 41 (:value @old))
           "the already selected request may finish on its old snapshot")
       (is (= 42
              (:value
               (cache/resolve-basis!
                store (context snapshot-2 2) :new-b :decision integer?
                (fn []
                  (:value
                   (subproblem/resolve-bound!
                    :projection :shared {}
                    (constantly 99)))))))
           "late work remains reachable only from the expired lifecycle")
       (is (= 1 (get-in (cache/basis-cache-stats store)
                        [:subproblems :projection-hits]))))))

(deftest request-boundary-capture-prevents-pre-lookup-expiry-reattachment-test
  (let [store (cache/basis-cache)
        captured (cache/capture-cache-lifecycle store)
        context (basis-context 7 7)
        resolve-projection
        (fn [request-context top-level-key value]
          (:value
           (cache/resolve-basis!
            store request-context top-level-key :decision integer?
            (fn []
              (:value
               (subproblem/resolve-bound!
                :projection :shared-projection {}
                (constantly value)))))))]
    ;; Model expiry after a request selected its source snapshot but before it
    ;; reached resolve-basis!.  Reusing the numeric revision after a restore
    ;; must not let that old request populate the replacement lifecycle.
    (cache/expire-basis-cache! store)
    (is (= 41
           (resolve-projection
            (assoc context :cache-lifecycle captured)
            :old-request
            41)))
    (is (= 42
           (resolve-projection context :new-request-a 42)))
    (is (= 42
           (resolve-projection context :new-request-b 99))
        "new requests share only the replacement lifecycle's projection")))

#?(:clj
   (deftest generation-expiry-detaches-old-publication-without-blocking-test
     (let [store (cache/basis-cache)
           context
           (fn [snapshot order]
             (basis-context snapshot order))
           old-started (promise)
           release-old (promise)
           new-started (promise)
           new-snapshot (snapshot-object)
           old-work
           (future
             (cache/resolve-basis!
              store (context (snapshot-object) 1) :old :decision keyword?
              (fn []
                (:value
                 (subproblem/resolve-bound!
                  :projection :old-projection {}
                  (fn []
                    (deliver old-started true)
                    @release-old
                    :old))))))
           _ @old-started
           _ (cache/expire-basis-cache! store)
           new-work
           (future
             (cache/resolve-basis!
              store (context new-snapshot 2) :new :decision keyword?
              (fn []
                (:value
                 (subproblem/resolve-bound!
                  :projection :new-projection {}
                  (fn []
                    (deliver new-started true)
                    :new))))))]
       (is (= true (deref new-started 1000 ::timed-out)))
       (is (= :new (:value @new-work)))
       (deliver release-old true)
       (is (= :old (:value @old-work)))
       (is (= :new
              (:value
               (cache/resolve-basis!
                store (context new-snapshot 2)
                :new :decision keyword? (constantly :wrong))))))))

(deftest cache-disabled-request-bypasses-subproblem-store-test
  (let [store (cache/basis-cache)
        snapshot (snapshot-object)
        cached-context (basis-context snapshot 1)
        projection-calls (atom 0)
        projection
        #(subproblem/resolve-bound!
          :projection :projection {}
          (fn []
            (swap! projection-calls inc)
            7))
        top-level
        (fn [key context]
          (cache/resolve-basis!
           store context key :decision integer?
           #(-> (projection) :value)))]
    (is (= 7 (:value (top-level :cached cached-context))))
    (let [before (:subproblems (cache/basis-cache-stats store))]
      (is (= 7 (:value
                (top-level :disabled
                           (assoc cached-context :cacheable? false)))))
      (is (= 2 @projection-calls))
      (is (= before
             (:subproblems (cache/basis-cache-stats store)))))))

(deftest completed-answer-only-cache-option-test
  (let [store
        (cache/basis-cache
         {:subproblem-cache {:enabled? false}})
        snapshot (snapshot-object)
        context (basis-context snapshot 1)
        calls (atom 0)
        resolve
        (fn [key]
          (cache/resolve-basis!
           store context key :decision integer?
           (fn []
             (:value
              (subproblem/resolve-bound!
               :projection :shared {}
               (fn []
                 (swap! calls inc)
                 9))))))]
    (is (= 9 (:value (resolve :a))))
    (is (= 9 (:value (resolve :b))))
    (is (= 2 @calls))
    (is (zero? (get-in (cache/basis-cache-stats store)
                       [:subproblems :projection-hits])))))

(deftest managed-descriptor-is-lazy-after-exact-lookup-test
  (let [store (cache/basis-cache)
        snapshot-1 (snapshot-object)
        snapshot-2 (snapshot-object)
        descriptor-reads (atom 0)
        context
        (fn [snapshot order]
          (assoc (basis-context snapshot order)
                 :managed-key-fn
                 (fn []
                   (swap! descriptor-reads inc)
                   {:schema-generation 10
                    :dependency-stamp 20})))
        resolve
        (fn [semantic-key current-context]
          (cache/resolve-basis!
           store current-context semantic-key :decision integer?
           (constantly 7)))]
    (resolve :query-a (context snapshot-1 1))
    (is (= 1 @descriptor-reads)
        "an exact miss acquires one descriptor")
    (resolve :query-a (context snapshot-1 1))
    (is (= 1 @descriptor-reads)
        "an exact hit acquires no descriptor")
    (resolve :query-b (context snapshot-1 1))
    (is (= 2 @descriptor-reads)
        "another semantic miss owns another request frame")
    (resolve :query-b (context snapshot-2 2))
    (is (= 3 @descriptor-reads)
        "a new immutable snapshot must revalidate its proof")))

(deftest unavailable-proof-cannot-reuse-or-publish-managed-answer-test
  (let [store (cache/basis-cache)
        snapshot-1 (snapshot-object)
        snapshot-2 (snapshot-object)
        compute-calls (atom 0)
        resolve
        (fn [snapshot order managed-key value]
          (cache/resolve-basis!
           store
           (assoc (basis-context snapshot order)
                  :managed-key-fn (constantly managed-key))
           :proof-fallback
           :decision
           boolean?
           (fn []
             (swap! compute-calls inc)
             value)))]
    (is (true? (:value (resolve snapshot-1 1
                               {:schema-generation 10
                                :dependency-stamp 20}
                               true))))
    (let [fallback (resolve snapshot-2 2 nil false)]
      (is (false? (:value fallback))
          "an unavailable proof must not return the older managed ALLOW")
      (is (false? (:cached? fallback)))
      (is (nil? (:cache-tier fallback))))
    (is (= 2 @compute-calls))
    (is (false? (:value (resolve snapshot-2 2 nil true)))
        "the exact result computed during fallback remains reusable")))

(deftest proof-unavailable-diagnostics-are-typed-telemetry-test
  (let [store (cache/basis-cache)]
    (cache/record-proof-unavailable!
     store {:status :unavailable :reason :missing-generation})
    (cache/record-proof-unavailable!
     store {:status :unavailable :reason :provider-failure})
    (cache/record-proof-unavailable!
     store {:status :unavailable :reason :missing-generation})
    (let [stats (cache/basis-cache-stats store)]
      (is (= 3 (:proof-unavailable stats)))
      (is (= {:missing-generation 2 :provider-failure 1}
             (:proof-unavailable-reasons stats))))))

(deftest read-without-publication-preserves-lookups-and-suppresses-writes-test
  (let [store (cache/basis-cache)
        first-snapshot (snapshot-object)
        second-snapshot (snapshot-object)
        computations (atom 0)
        publication-view
        (fn []
          (let [stats (cache/basis-cache-stats store)]
            {:puts (:puts stats)
             :exact-entries (:exact-entries stats)
             :retained-bases (:retained-bases stats)
             :managed-entries (:managed-entries stats)
             :managed-generations (:managed-generations stats)
             :exact-subproblem-puts
             (get-in stats [:exact-subproblems :puts])
             :managed-subproblem-puts
             (get-in stats [:managed-subproblems :puts])}))
        resolve
        (fn [snapshot revision semantic-key populate? value]
          (cache/resolve-basis!
           store
           (assoc (basis-context snapshot revision)
                  :populate-cache? populate?
                  :managed-key-fn
                  (constantly
                   {:schema-generation 10 :dependency-stamp 20}))
           semantic-key
           :decision
           boolean?
           (fn []
             (swap! computations inc)
             value)))]
    (is (false? (:cached?
                 (resolve first-snapshot 1 :answer true true))))
    (let [after-warm (publication-view)
          exact-hit (resolve first-snapshot 1 :answer false false)]
      (is (true? (:cached? exact-hit)))
      (is (= :exact-basis (:cache-tier exact-hit)))
      (is (= after-warm (publication-view))
          "an exact read-only hit publishes and installs nothing"))
    (let [before-managed-read (publication-view)
          managed-hit (resolve second-snapshot 2 :answer false false)]
      (is (true? (:cached? managed-hit)))
      (is (= :managed-current (:cache-tier managed-hit)))
      (is (= before-managed-read (publication-view))
          "a managed read-only hit is neither promoted nor generation-creating"))
    (let [before-misses (publication-view)
          first-miss (resolve second-snapshot 2 :unseen false false)
          second-miss (resolve second-snapshot 2 :unseen false true)]
      (is (false? (:cached? first-miss)))
      (is (false? (:cached? second-miss)))
      (is (= [false true] [(:value first-miss) (:value second-miss)]))
      (is (= before-misses (publication-view))
          "read-only misses evaluate independently without publication"))
    (is (= 3 @computations)
        "the warm request and both read-only misses compute")))

(deftest proof-contract-violation-disables-only-managed-lifting-test
  (let [reports (atom [])
        store
        (cache/basis-cache
         {:proof-contract-reporter #(swap! reports conj %)})
        first-snapshot (snapshot-object)
        second-snapshot (snapshot-object)
        third-snapshot (snapshot-object)
        descriptor-reads (atom 0)
        computations (atom 0)
        resolve
        (fn [snapshot revision value]
          (cache/resolve-basis!
           store
           (assoc
            (basis-context snapshot revision)
            :managed-key-fn
            (fn []
              (swap! descriptor-reads inc)
              {:schema-generation 10 :dependency-stamp 20}))
           :contract-violation
           :decision
           boolean?
           (fn []
             (swap! computations inc)
             value)))]
    (is (true? (:value (resolve first-snapshot 1 true))))
    (is (= 1 @descriptor-reads))
    (cache/record-proof-diagnostic!
     store
     {:status :contract-violation
      :reason :relation-generation-above-revision})
    (cache/record-proof-diagnostic!
     store
     {:status :contract-violation
      :reason :relation-generation-above-revision})
    (let [fallback (resolve second-snapshot 2 false)]
      (is (false? (:value fallback)))
      (is (false? (:cached? fallback)))
      (is (= 1 @descriptor-reads)
          "sticky disablement skips all subsequent managed proof reads"))
    (is (false? (:value (resolve second-snapshot 2 true)))
        "exact-basis cache hits remain available while lifting is disabled")
    (is (= 2 @computations))
    (let [stats (cache/basis-cache-stats store)]
      (is (true? (:managed-lifting-disabled? stats)))
      (is (= 2 (:proof-contract-violations stats)))
      (is (= {:relation-generation-above-revision 2}
             (:proof-contract-violation-reasons stats))))
    (is (= 1 (count @reports))
        "the reporter runs once per reason in a lifecycle")
    (cache/expire-basis-cache! store)
    (is (false? (:managed-lifting-disabled?
                 (cache/basis-cache-stats store))))
    (is (true? (:value (resolve third-snapshot 3 true))))
    (is (= 2 @descriptor-reads)
        "expiry restores managed proof acquisition")))

(deftest basis-generation-two-hit-admission-test
  (let [store (cache/basis-cache {:admit-on-repeat? true})
        snapshot (snapshot-object)
        context (basis-context snapshot 1)
        calls (atom 0)
        resolve
        #(cache/resolve-basis!
          store context :key :decision boolean?
          (fn [] (swap! calls inc) true))]
    (is (false? (:cached? (resolve)))
        "the first sighting is not retained")
    (is (false? (:cached? (resolve)))
        "the second sighting demonstrates reuse and is retained")
    (is (true? (:cached? (resolve))))
    (is (= 2 @calls))
    (is (= 1 (:exact-entries (cache/basis-cache-stats store))))
    (cache/expire-basis-cache! store)
    (is (false? (:cached? (resolve)))
        "explicit expiry also resets admission history")
    (is (= 0 (:exact-entries (cache/basis-cache-stats store))))))

(deftest authenticated-page-query-identity-ignores-signed-transport-test
  (let [base-public
        {:subject {:type :user :id "user"}
         :permission :view
         :resource/type :document
         :first 20}
        base-internal
        {:subject {:type :user :id 1}
         :permission :view
         :resource/type :document
         :first 20}
        boundary {:kind :lookup-eid :resource 42}
        original
        (cache/lookup-page-query-identity
         (assoc base-public
                :after "signed-snapshot-a"
                :cancellation-token (eacl/cancellation-token))
         (assoc base-internal
                :after boundary
                :cancellation-token (eacl/cancellation-token)))
        recovered
        (cache/lookup-page-query-identity
         (assoc base-public
                :after "signed-snapshot-b"
                :cache? true
                :populate-cache? false
                :cancellation-token (eacl/cancellation-token))
         (assoc base-internal
                :after boundary
                :populate-cache? true
                :cancellation-token (eacl/cancellation-token)))]
    (is (= original recovered)
        "signed transport bytes are not page semantics")
    (is (not=
         original
         (cache/lookup-page-query-identity
          (assoc base-public :after "signed-snapshot-c")
          (assoc base-internal
                 :after (assoc boundary :resource 43))))
        "the authenticated internal boundary still distinguishes pages")
    (is (not=
         original
         (cache/lookup-page-query-identity
          (assoc base-public :after "signed-snapshot-d" :first 50)
          (assoc base-internal :after boundary :first 50)))
        "page size remains semantic")))

(deftest completed-answer-hot-key-survives-churn-test
  ;; R6 regression scenario "hot key survives churn": the deleted
  ;; completed-answer map evicted in hash-iteration order, so a repeatedly
  ;; accessed key was as likely to die as any cold key. The weighted
  ;; :answer tier evicts least-recently-used.
  (let [store (cache/basis-cache
               {:subproblem-cache {:answer-max-weight 4096}})
        snapshot (snapshot-object)
        context (assoc
                 (basis-context snapshot 1)
                 ;; Fixed per-answer weight: the tier retains 8 answers.
                 :answer-weight-fn (constantly 512))
        resolve
        (fn [key]
          (cache/resolve-basis!
           store context key :decision boolean? (constantly true)))]
    (resolve :hot)
    (dotimes [i 64]
      (resolve [:cold i])
      (is (true? (:cached? (resolve :hot)))
          "the repeatedly accessed answer stays resident through churn"))
    (let [tier (get-in (cache/basis-cache-stats store)
                       [:subproblems :tiers :answer])]
      (is (<= (:weight tier) 4096)
          "retained answer weight never exceeds the configured budget")
      (is (false? (:cached? (resolve [:cold 0])))
          "cold keys evicted; only the hot answer and the newest survive"))))

(deftest completed-answer-byte-budget-and-oversized-rejection-test
  ;; R6 regression scenario "page-heavy workload stays within budget": the
  ;; deleted answer map was entry-bounded only (weight fn accepted and
  ;; ignored), measured at 95.5 MB retained. The :answer tier is
  ;; weight-bounded with a budget/4 per-entry ceiling.
  (let [store (cache/basis-cache
               {:subproblem-cache {:answer-max-weight 8192}})
        snapshot (snapshot-object)
        page (fn [n] {:data (vec (range n))})
        context (basis-context snapshot 1)
        resolve
        (fn [key value]
          (cache/resolve-basis!
           store context key :lookup-resources map?
           (fn [] value)))]
    ;; Saturate with page answers under the default row-count weight
    ;; (512 + 128/row: 1792 each, so at most four fit).
    (dotimes [i 32]
      (resolve [:page i] (page 10)))
    (let [tier (get-in (cache/basis-cache-stats store)
                       [:subproblems :tiers :answer])]
      (is (pos? (:entries tier)))
      (is (<= (:weight tier) 8192)
          "retained answer weight stays within the configured budget"))
    ;; One answer heavier than budget/4 is rejected at publication and
    ;; recomputed on the next request instead of retained unbounded.
    (resolve :oversized (page 100))
    (is (pos? (get-in (cache/basis-cache-stats store)
                      [:subproblems :oversized-rejections])))
    (is (false? (:cached? (resolve :oversized (page 100))))
        "an oversized answer is never served from cache")))

(deftest repeat-admission-survives-large-keyspace-churn-test
  ;; R6 regression scenario "repeat admission at scale": the deleted
  ;; admissions map evicted its hash-trie-order minimum, freezing into a
  ;; fixed hash-lucky sighting set (measured 2.3% admit at 50x keyspace).
  ;; The first-in-first-out sighting window forgets oldest first sightings,
  ;; so admission tracks access recency at any keyspace size.
  (let [window 64
        store (cache/basis-cache
               {:max-entries window
                :admit-on-repeat? true})
        snapshot (snapshot-object)
        context (basis-context snapshot 1)
        resolve
        (fn [key]
          (cache/resolve-basis!
           store context key :decision boolean? (constantly true)))]
    ;; Churn 50x the window in distinct single-sighting keys.
    (dotimes [i (* 50 window)]
      (resolve [:cold i]))
    ;; A key then seen twice in close succession is admitted.
    (resolve :fresh)
    (resolve :fresh)
    (is (true? (:cached? (resolve :fresh)))
        "a second sighting within the window admits the answer")
    ;; A second sighting within window-many distinct first sightings still
    ;; admits; one spaced past the window is forgotten and starts over.
    (resolve :spaced)
    (dotimes [i (dec window)]
      (resolve [:filler i]))
    (resolve :spaced)
    (is (true? (:cached? (resolve :spaced)))
        "the sighting window spans max-entries distinct first sightings")
    (resolve :forgotten)
    (dotimes [i (inc window)]
      (resolve [:beyond i]))
    ;; Had the first sighting survived the window, this call would admit
    ;; and store, making the next call a cache hit. It restarts instead.
    (resolve :forgotten)
    (is (false? (:cached? (resolve :forgotten)))
        "a sighting spaced past the window restarts admission")
    (is (<= (:admission-entries (cache/basis-cache-stats store)) window)
        "sighting state stays bounded by the window at 50x keyspace")))
