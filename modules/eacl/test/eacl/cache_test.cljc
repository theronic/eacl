(ns eacl.cache-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [clojure.string :as str]
            [eacl.cache :as cache]
            [eacl.cache-identity :as cache-identity]
            [eacl.core :as eacl]
            [eacl.exact-integer :as exact-integer]
            [eacl.execution :as execution]
            [eacl.secure-format :as secure]
            [eacl.subproblem-cache :as subproblem]))

(defrecord TestProvider [])

(def ^:private default-source-id :source)
(def ^:private default-lifecycle :lifecycle-a)
(def ^:private default-fingerprint :adapter-v1)
(def ^:private default-identity-contract :identity-v1)

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Throwable :cljs :default) error
      (ex-data error))))

(defn- lineage
  ([lifecycle]
   (lineage lifecycle nil))
  ([lifecycle branch]
   {:source-scope
    {:backend :test
     :source-id default-source-id
     :branch branch}
    :source-lifecycle lifecycle}))

(defn- basis-key
  ([revision]
   (basis-key revision {}))
  ([revision {:keys [lifecycle branch basis-kind adapter-fingerprint
                     identity-contract source-id]
              :or {lifecycle default-lifecycle
                   basis-kind :ordinary
                   adapter-fingerprint default-fingerprint
                   identity-contract default-identity-contract
                   source-id default-source-id}}]
   {:key-version 2
    :backend :test
    :basis-identity
    {:backend :test
     :source-id source-id
     :branch branch
     :source-lifecycle lifecycle
     :basis-kind basis-kind
     :revision revision
     :exact-locator revision
     :backend-snapshot-id {:basis revision :branch branch}}
    :adapter-fingerprint adapter-fingerprint
    :identity-contract identity-contract}))

(defn- basis-context
  ([revision]
   (basis-context revision {}))
  ([revision {:keys [lifecycle branch] :as options}]
   {:exact-basis-key (basis-key revision options)}))

(defn- semantic-key
  ([id]
   (semantic-key id {}))
  ([id {:keys [adapter-fingerprint identity-contract]
        :or {adapter-fingerprint default-fingerprint
             identity-contract default-identity-contract}}]
   {:operation :read-relationships
    :id id
    :adapter-fingerprint adapter-fingerprint
    :identity-contract identity-contract}))

(defn- descriptor
  ([dependency-identity]
   (descriptor 1 dependency-identity))
  ([schema-generation dependency-identity]
   {:schema-generation schema-generation
    :dependency-identity dependency-identity
    :dependency-stamp
    (reduce (fn [frontier [_ generation]]
              (max frontier generation))
            0
            dependency-identity)}))

(def ^:private boxed-value-tag :eacl.cache-test/boxed-value)

(defn- page-shaped?
  [value]
  (and (map? value)
       (vector? (:data value))
       (map? (:page-info value))))

(defn- box-value
  [value]
  {:data [[boxed-value-tag value]]
   :page-info {:start-cursor nil
               :end-cursor nil
               :has-next-page? false
               :has-previous-page? false}})

(defn- unbox-result
  [result]
  (let [value (:value result)]
    (if (and (page-shaped? value)
             (= 1 (count (:data value)))
             (vector? (first (:data value)))
             (= 2 (count (first (:data value))))
             (= boxed-value-tag (first (first (:data value)))))
      (assoc result :value (second (first (:data value))))
      result)))

(defn- resolve-test-value!
  [store context semantic compute]
  (unbox-result
   (cache/resolve-basis!
    store context (assoc semantic :operation :read-relationships)
    #(let [value (compute)]
       (if (page-shaped? value) value (box-value value))))))

(defn- exact!
  ([store revision semantic compute]
   (exact! store revision semantic {} compute))
  ([store revision semantic options compute]
   (resolve-test-value!
    store (merge (basis-context revision options) options) semantic compute)))

(defn- exact-operation!
  [store revision semantic operation compute]
  (cache/resolve-basis!
   store (basis-context revision) (assoc semantic :operation operation) compute))

(defn- managed!
  [store revision proof semantic options compute]
  (resolve-test-value!
   store
   (merge (basis-context revision options)
          options
          {:managed-key-fn (constantly proof)})
   semantic compute))

(defn- completed-page
  [n]
  {:data (vec (range n))
   :page-info {:start-cursor nil
               :end-cursor nil
               :has-next-page? false
               :has-previous-page? false}})

(defn- rendered-page
  [n]
  {:format cache/rendered-page-entry-format
   :page
   {:data (mapv #(eacl/spice-object :document (str "document-" %))
                (range n))
    :page-info {:start-cursor nil
                :end-cursor nil
                :has-next-page? false
                :has-previous-page? false}}})

(defn- rendered-relationship-page
  [n]
  (assoc-in
   (rendered-page 0)
   [:page :data]
   (mapv
    (fn [index]
      (eacl/->Relationship
       (eacl/spice-object :user (str "user-" index))
       :reader
       (eacl/spice-object :document (str "document-" index))))
    (range n))))

(defrecord CustomCursorId [value])

(deftest concurrent-exact-hit-telemetry-counts-every-hit-test
  #?(:clj
     (let [store (cache/basis-cache {:max-entries 8})
           semantic (semantic-key :concurrent-hot)
           workers 8
           iterations 1000]
       (is (false? (:cached?
                    (exact! store 1 semantic (constantly :resident)))))
       (run!
        deref
        (doall
         (repeatedly
          workers
          #(future
             (dotimes [_ iterations]
               (when-not (= :resident
                            (:value
                             (exact! store 1 semantic
                                     (constantly :wrong))))
                 (throw
                  (ex-info "Concurrent exact hit changed value." {}))))))))
       (let [expected (* workers iterations)
             stats (cache/basis-cache-stats store)]
         (is (= expected (:hits stats)))
         (is (= expected (:exact-hits stats)))
         (is (zero? (:managed-hits stats)))
         (is (= expected (get-in stats [:subproblems :hits])))
         (is (= expected
                (get-in stats [:subproblems :answer-hits])))))
     :cljs
     (is true)))

(deftest rendered-pages-are-exact-read-through-values-test
  (let [store (cache/basis-cache {:max-entries 8})
        query (assoc (semantic-key :rendered) :operation :lookup-resources)
        first-context (basis-context 1)
        other-basis (basis-context 2)
        other-query (assoc query :id :other)
        page (rendered-page 3)]
    (is (nil? (cache/lookup-rendered-page!
               store first-context query)))
    (is (= {:published? true :reason :published}
           (cache/publish-rendered-page!
            store first-context query page)))
    (is (= {:value page
            :cached? true
            :cache-tier :exact-rendered-page
            :cache-basis {:basis 1 :branch nil}}
           (cache/lookup-rendered-page!
            store first-context query)))
    (is (nil? (cache/lookup-rendered-page!
               store other-basis query))
        "a public projection never crosses an exact basis")
    (is (nil? (cache/lookup-rendered-page!
               store first-context other-query))
        "the complete rendered request identity remains part of the key")
    (is (= {:published? false :reason :read-only}
           (cache/publish-rendered-page!
            store
            (assoc first-context :populate-cache? false)
            other-query page)))
    (is (nil? (cache/lookup-rendered-page!
               store first-context other-query)))
    (let [stats (cache/basis-cache-stats store)]
      (is (= 1 (:rendered-page-entries stats)))
      (is (= 1 (:rendered-page-hits stats))))))

(deftest rendered-page-size-guard-rejects-only-oversized-pages-test
  (let [store (cache/basis-cache {:max-entries 4})
        query (assoc (semantic-key :size-guard)
                     :operation :lookup-resources)]
    (is (true? (cache/rendered-page-entry-valid?
                (rendered-page 1000))))
    (is (true?
         (cache/rendered-page-entry-valid?
          (assoc-in (rendered-page 1)
                    [:page :page-info :end-cursor]
                    "eacl_c5_authenticated"))))
    (is (false?
         (cache/rendered-page-entry-valid?
          (assoc-in (rendered-page 1)
                    [:page :page-info :end-cursor]
                    {:unsigned :edge}))))
    (is (false? (cache/rendered-page-entry-valid?
                 (rendered-page 1001))))
    (is (= {:published? false :reason :invalid-value}
           (cache/publish-rendered-page!
            store (basis-context 1) query (rendered-page 1001))))
    (is (zero? (:rendered-page-entries
                (cache/basis-cache-stats store))))))

(deftest rendered-page-publication-enforces-operation-shape-test
  (let [store (cache/basis-cache {:max-entries 4})
        context (basis-context 1)
        lookup-key (assoc (semantic-key :lookup-shape)
                          :operation :lookup-resources)
        relationship-key (assoc (semantic-key :relationship-shape)
                                :operation :read-relationships)]
    (is (= {:published? false :reason :invalid-value}
           (cache/publish-rendered-page!
            store context lookup-key (rendered-relationship-page 1))))
    (is (= {:published? false :reason :invalid-value}
           (cache/publish-rendered-page!
            store context relationship-key (rendered-page 1))))
    (is (= {:published? true :reason :published}
           (cache/publish-rendered-page!
            store context relationship-key (rendered-relationship-page 1))))))

(deftest rendered-pages-reject-request-owned-metadata-test
  (let [store (cache/basis-cache {:max-entries 4})
        request-owned (atom :request)
        metadata-id
        (with-meta [:document 1] {:request-owned request-owned})
        page (assoc-in (rendered-page 1) [:page :data 0 :id] metadata-id)
        query (assoc (semantic-key :metadata) :operation :lookup-resources)]
    (is (false? (cache/rendered-page-entry-valid? page)))
    (is (= {:published? false :reason :invalid-value}
           (cache/publish-rendered-page!
            store (basis-context 1) query page)))
    (is (zero? (:rendered-page-entries
                (cache/basis-cache-stats store)))
        "the rendered store never retains the request-owned metadata atom")))

(deftest cursor-identities-reject-record-type-erasure-test
  (let [custom-id (->CustomCursorId "document-1")
        page (assoc-in (rendered-page 1) [:page :data 0 :id] custom-id)]
    (is (false? (cache/cursor-cache-data? custom-id))
        "canonical cursor bytes would erase a custom record's type")
    (is (false? (cache/rendered-page-entry-valid? page))
        "a custom record identity cannot enter exact transport retention")))

(deftest cursor-identities-use-one-canonical-sequential-type-test
  (is (true? (cache/cursor-cache-data? ["user" 1])))
  (is (false? (cache/cursor-cache-data? '("user" 1)))
      "list/vector equality must not alias custom identity authority")
  (is (true? (cache/canonical-cursor-identity? ["user" 1])))
  (is (false? (cache/canonical-cursor-identity? '("user" 1))))
  #?(:clj
     (do
       (is (false? (cache/canonical-cursor-identity? (subvec [0 1] 1)))
           "subvec/vector equality must not alias custom identity authority")
       (is (false? (cache/canonical-cursor-identity? 1N)))
       (is (false? (cache/canonical-cursor-identity? (int 1))))
       (is (true? (cache/canonical-cursor-identity? (long 1)))))
     :cljs
     (is (false? (cache/canonical-cursor-identity? (js/Number "-0")))
         "signed zero must not alias ordinary zero"))
  (doseq [value
          [{:b 2 :a 1}
           #{2 1}
           (secure/canonicalize {:b 2 :a 1})
           (secure/canonicalize #{2 1})]]
    (is (false? (cache/canonical-cursor-identity? value))
        "map/set IDs are rejected because comparator state is not portable"))
  #?(:clj
     (do
       (is (false?
            (cache/canonical-cursor-identity?
             (sorted-map-by #(compare %2 %1)))))
       (is (false?
            (cache/canonical-cursor-identity?
             (sorted-set-by #(compare %2 %1))))))
     :cljs
     (is true)))

(deftest cursor-identities-are-bounded-before-cache-key-construction-test
  (let [deep (nth (iterate vector "id") 40)
        wide (vec (range 1100))
        long-string (apply str (repeat 4097 "x"))]
    (doseq [identity [deep wide long-string]]
      (is (false? (cache/canonical-cursor-identity? identity))))
    (is (false? (cache/cursor-cache-data? deep)))
    (is (true? (cache/cursor-cache-data? wide))
        "the cursor envelope may support more data than the hot cache key")))

(deftest rendered-page-publication-rechecks-request-after-validation-test
  (doseq [mode [:cancelled :deadline-expired]]
    (let [store (cache/basis-cache {:max-entries 4})
          context (basis-context 1)
          query (assoc (semantic-key mode) :operation :lookup-resources)
          page (rendered-page 1)
          clock (atom 0)
          token (execution/cancellation-token)
          contract
          (binding [execution/*monotonic-nanos* #(deref clock)]
            (execution/normalize
             {:execution-timeout-ms 1}
             :lookup-resources
             {:cancellation-token token}))]
      (with-redefs [cache/rendered-page-entry-valid?
                    (fn
                      ([_value]
                       (case mode
                         :cancelled (execution/cancel! token)
                         :deadline-expired (reset! clock 1000000))
                       true)
                      ([_semantic-key _value]
                       (case mode
                         :cancelled (execution/cancel! token)
                         :deadline-expired (reset! clock 1000000))
                       true))]
        (is (= {:published? false :reason :execution-unavailable}
               (binding [execution/*contract* contract
                         execution/*monotonic-nanos* #(deref clock)]
                 (cache/publish-rendered-page!
                  store context query page)))
            (name mode)))
      (is (zero? (:rendered-page-entries
                  (cache/basis-cache-stats store)))
          (name mode)))))

(deftest rendered-pages-rotate-with-lifecycle-and-are-not-portable-test
  (let [store (cache/basis-cache {:max-entries 8})
        context (basis-context 1)
        captured (cache/capture-cache-lifecycle store)
        captured-context (assoc context :cache-lifecycle captured)
        query (assoc (semantic-key :lifecycle)
                     :operation :lookup-resources)
        page (rendered-page 2)
        bounds {:max-entries 8}]
    (cache/publish-rendered-page! store captured-context query page)
    (cache/resolve-basis!
     store context query (constantly (completed-page 1)))
    (let [snapshot (cache/export-basis-snapshot store bounds)]
      (is (= 1 (:entry-count snapshot))
          "rendered process-local values are absent from portable export")
      (cache/restore-basis-snapshot! store snapshot bounds))
    (is (nil? (cache/lookup-rendered-page! store context query))
        "a new lifecycle starts with a fresh rendered store")
    (is (= page
           (:value
            (cache/lookup-rendered-page!
             store captured-context query)))
        "an already-running request remains internally lifecycle-consistent")
    (cache/publish-rendered-page!
     store captured-context (assoc query :id :late) page)
    (is (nil? (cache/lookup-rendered-page!
               store context (assoc query :id :late)))
        "late publication into a captured retired lifecycle cannot leak")))

(deftest completed-answer-validation-is-operation-and-query-aware-test
  (let [unbounded
        {:operation :count-resources
         :query {:public {:count-request :unbounded}
                 :internal {:count-request :unbounded}}}
        bounded
        {:operation :count-resources
         :query {:public {:count-limit 3}
                 :internal {:count-limit 3}}}
        page
        {:data [1 2]
         :page-info
         {:start-cursor {:ordinal 1}
          :end-cursor {:ordinal 2}
          :has-next-page? true
          :has-previous-page? false
          :bounded? false}}
        subject (eacl/spice-object :user "alice")
        resource (eacl/spice-object :document "one")
        leaf
        {:expanded-object resource
         :expanded-relation :viewer
         :leaf {:subjects [subject]}}
        tree
        {:expanded-object resource
         :expanded-relation :view
         :intermediate
         {:operation :exclusion
          :children [leaf (assoc leaf :expanded-relation :editor)]}}]
    (testing "unbounded counts have one closed safe shape"
      (is (cache/completed-answer-value-valid?
           :count-resources unbounded {:count 7 :limit -1}))
      (doseq [invalid
              [{:count -7}
               {:count -7 :limit -1}
               {:count 7}
               {:count 7 :limit -1 :truncated? false}
               {:count 7 :limit 7}
               {:count (inc exact-integer/maximum) :limit -1}]]
        (is (false?
             (cache/completed-answer-value-valid?
              :count-resources unbounded invalid))
            (pr-str invalid))))
    (testing "bounded counts retain their exact limit and sentinel invariant"
      (doseq [valid
              [{:count 2 :limit 3 :truncated? false}
               {:count 3 :limit 3 :truncated? false}
               {:count 3 :limit 3 :truncated? true}]]
        (is (cache/completed-answer-value-valid?
             :count-resources bounded valid)
            (pr-str valid)))
      (doseq [invalid
              [{:count 2 :limit 3 :truncated? true}
               {:count 4 :limit 3 :truncated? false}
               {:count 3 :limit 2 :truncated? false}
               {:count 3 :limit 3}
               {:count 3 :limit 3 :truncated? nil}
               {:count 3 :limit 3 :truncated? false :extra true}]]
        (is (false?
             (cache/completed-answer-value-valid?
              :count-resources bounded invalid))
            (pr-str invalid))))
    (testing "pages have a closed internal transport shape"
      (is (cache/completed-answer-value-valid?
           :lookup-resources
           {:operation :lookup-resources :query {}}
           page))
      (doseq [invalid
              [(update page :page-info dissoc :start-cursor)
               (assoc-in page [:page-info :has-next-page?] 1)
               (assoc-in page [:page-info :end-cursor] "public-token")
               (assoc-in page [:page-info :unknown] true)
               (assoc page :cached? false)]]
        (is (false?
             (cache/completed-answer-value-valid?
              :lookup-resources
              {:operation :lookup-resources :query {}}
              invalid))
            (pr-str invalid))))
    (testing "permission trees are closed and recursively shaped"
      (is (cache/completed-answer-value-valid?
           :expand-permission-tree
           {:operation :expand-permission-tree :query {}}
           tree))
      (doseq [invalid
              [(assoc-in tree [:intermediate :operation] :difference)
               (update-in tree [:intermediate :children] pop)
               (assoc-in tree [:intermediate :children 0 :leaf :subjects]
                         [{:type :user :id "alice"}])
               (assoc leaf :unexpected true)]]
        (is (false?
             (cache/completed-answer-value-valid?
              :expand-permission-tree
              {:operation :expand-permission-tree :query {}}
              invalid))
            (pr-str invalid))))
    (is (false?
         (cache/completed-answer-value-valid?
          :count-subjects unbounded {:count 7 :limit -1})))
    (is (cache/completed-answer-value-valid?
         :can? {:operation :can? :query {}} false))))

(deftest forged-completed-answer-families-fail-publication-and-restore-test
  (let [subject (eacl/spice-object :user "alice")
        resource (eacl/spice-object :document "one")
        valid-page
        {:data [1]
         :page-info
         {:start-cursor {:ordinal 1}
          :end-cursor {:ordinal 1}
          :has-next-page? false
          :has-previous-page? false}}
        valid-tree
        {:expanded-object resource
         :expanded-relation :viewer
         :leaf {:subjects [subject]}}
        cases
        [[:count-resources
          {:operation :count-resources
           :query {:public {} :internal {}}}
          {:count -7}
          {:count 1 :limit -1}]
         [:count-subjects
          {:operation :count-subjects
           :query {:public {:count-limit 1}
                   :internal {:count-limit 1}}}
          {:count 0 :limit 1 :truncated? true}
          {:count 1 :limit 1 :truncated? true}]
         [:read-relationships
          {:operation :read-relationships :query {}}
          (assoc-in valid-page [:page-info :has-next-page?] :yes)
          valid-page]
         [:lookup-resources
          {:operation :lookup-resources :query {}}
          (assoc valid-page :cached? true)
          valid-page]
         [:lookup-subjects
          {:operation :lookup-subjects :query {}}
          (assoc-in valid-page [:page-info :end-cursor] "public-token")
          valid-page]
         [:expand-permission-tree
          {:operation :expand-permission-tree :query {}}
          (assoc valid-tree :unexpected true)
          valid-tree]]
        bounds {:max-entries 4}]
    (doseq [[operation semantic forged valid] cases]
      (testing (name operation)
        (let [live-store (cache/basis-cache {:max-entries 4})
              calls (atom 0)
              rejected
              (cache/resolve-basis!
               live-store (basis-context 1) semantic (constantly forged))]
          (is (false? (:cached? rejected)))
          (is (= forged (:value rejected)))
          (is (zero? (:entry-count
                      (cache/export-basis-snapshot live-store bounds))))
          (let [recomputed
                (cache/resolve-basis!
                 live-store (basis-context 1) semantic
                 #(do (swap! calls inc) valid))]
            (is (false? (:cached? recomputed)))
            (is (= valid (:value recomputed)))
            (is (= 1 @calls)))
          (is (true?
               (:cached?
                (cache/resolve-basis!
                 live-store (basis-context 1) semantic
                 #(do (swap! calls inc) valid)))))
          (is (= 1 @calls))
          (is (= 1 @calls)
              "an accepted resident value is not recomputed per hit"))
        (let [source (cache/basis-cache {:max-entries 4})
              target (cache/basis-cache {:max-entries 4})]
          (cache/resolve-basis!
           source (basis-context 1) semantic (constantly valid))
          (let [snapshot
                (assoc-in
                 (cache/export-basis-snapshot source bounds)
                 [:entries 0 :value :value]
                 forged)
                before (cache/export-basis-snapshot target bounds)
                revision-before (cache/cache-content-revision target)
                error
                (error-data
                 #(cache/restore-basis-snapshot!
                   target snapshot bounds))]
            (is (= :eacl/incompatible-cache-snapshot (:type error)))
            (is (= before (cache/export-basis-snapshot target bounds)))
            (is (= revision-before
                   (cache/cache-content-revision target)))))))))

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
    (is (not (contains? (:public identity) :after)))
    (is (= {:ordinal 7} (get-in identity [:internal :after])))
    (is (not=
         identity
         (cache/lookup-page-query-identity
          public (assoc internal :after {:ordinal 8}))))
    (is (not=
         identity
         (cache/lookup-page-query-identity
          (assoc public :first 11) (assoc internal :first 11))))))

(deftest cache-configuration-is-count-only-and-client-private-test
  (is (nil? (cache/basis-cache-for-option cache/no-cache)))
  (is (cache/basis-cache? (cache/basis-cache-for-option nil)))
  (is (= :client-private-cache-reuse
         (:reason
          (error-data
           #(cache/basis-cache-for-option (cache/basis-cache))))))
  (is (= :unsupported-provider-store
         (:reason
          (error-data
           #(cache/basis-cache-for-option (->TestProvider))))))
  (doseq [options
          [{:max-weight 1024}
           {:retained-bases 2}
           {:repeat-admission-threshold 2}
           {:publication-attempts 4}
           {:store {}}
           {:subproblem-cache {}}
           {:subproblem-cache {:projection-max-weight 100}}
           {:subproblem-cache {:projection-max-entries 100}}
           {:subproblem-cache {:managed-proof-max-atoms 100}}
           {:subproblem-cache {:answer-max-weight 100}}]]
    (is (= :eacl/invalid-config
           (:type (error-data #(cache/basis-cache-for-option options))))
        (pr-str options)))
  (doseq [capacity [0 -1 1.5 (inc exact-integer/maximum)]]
    (is (= :eacl/invalid-config
           (:type
            (error-data #(cache/basis-cache {:max-entries capacity}))))
        (pr-str capacity)))
  (let [store
        (cache/basis-cache
         {:max-entries 3
          :denotation-max-entries 5})
        stats (cache/basis-cache-stats store)]
    (is (= 3 (get-in stats [:subproblems :tiers :answer :max-entries])))
    (is (= 5 (get-in stats [:subproblems :tiers :denotation :max-entries])))
    (is (not (contains? (get-in stats [:subproblems :tiers]) :projection)))
    (is (not (contains? stats :retained-weight)))
    (is (not (str/includes? (pr-str stats) "max-weight")))))

(deftest heterogeneous-untrusted-map-keys-preserve-typed-boundaries-test
  (let [options {:max-entries 2
                 1 :numeric-unknown
                 "unknown" :string-unknown}]
    (doseq [construct [#(cache/basis-cache options)
                       #(cache/basis-cache-for-option options)]]
      (let [error (error-data construct)]
        (is (= :eacl/invalid-config (:type error)))
        (is (= #{1 "unknown"} (set (:unknown-keys error)))))))
  (let [source (cache/basis-cache {:max-entries 2})
        target (cache/basis-cache {:max-entries 2})
        bounds {:max-entries 2}
        snapshot (cache/export-basis-snapshot source bounds)
        before (cache/export-basis-snapshot target bounds)
        revision-before (cache/cache-content-revision target)
        heterogeneous-bounds
        (assoc bounds 1 :numeric-unknown "unknown" :string-unknown)
        heterogeneous-snapshot
        (assoc snapshot 1 :numeric-unknown "unknown" :string-unknown)
        bounds-error
        (error-data
         #(cache/export-basis-snapshot source heterogeneous-bounds))
        snapshot-error
        (error-data
         #(cache/restore-basis-snapshot!
           target heterogeneous-snapshot bounds))]
    (is (= :eacl/invalid-config (:type bounds-error)))
    (is (= #{1 "unknown"} (set (:unknown-keys bounds-error))))
    (is (= :eacl/incompatible-cache-snapshot (:type snapshot-error)))
    (is (= before (cache/export-basis-snapshot target bounds)))
    (is (= revision-before (cache/cache-content-revision target)))))

(deftest complete-exact-basis-is-the-sole-ordinary-basis-authority-test
  (doseq [[case-name invalid-context]
          [[:missing-inner-field
            (update-in (basis-context 7)
                       [:exact-basis-key :basis-identity]
                       dissoc :exact-locator)]
           [:unknown-inner-field-at-the-same-count
            (update-in (basis-context 7)
                       [:exact-basis-key :basis-identity]
                       #(-> %
                            (dissoc :exact-locator)
                            (assoc :unknown-locator 7)))]
           [:unknown-outer-field-at-the-same-count
            (update (basis-context 7) :exact-basis-key
                    #(-> %
                         (dissoc :key-version)
                         (assoc :unknown-version 2)))]]]
    (let [store (cache/basis-cache)
          calls (atom 0)
          invoke
          #(resolve-test-value!
            store invalid-context (semantic-key case-name)
            (fn [] (swap! calls inc) :computed))]
      (dotimes [_ 2]
        (let [result (invoke)]
          (is (false? (:cached? result)) (name case-name))
          (is (= :computed (:value result)) (name case-name))))
      (is (= 2 @calls) (name case-name))
      (is (zero? (get-in (cache/basis-cache-stats store)
                         [:subproblems :tiers :answer :entries]))
          (name case-name))))
  (let [store (cache/basis-cache)
        context
        ;; Legacy duplicate fields are deliberately irrelevant: the complete
        ;; exact key is the only revision, source, and cache-basis authority.
        (assoc (basis-context 7)
               :snapshot-order 999
               :cache-basis {:basis 999}
               :source-lineage (lineage :wrong-lifecycle :wrong-branch))
        result
        (cache/resolve-basis!
         store context
         {:operation :can? :id :single-authority}
         (constantly true))]
    (is (= {:basis 7 :branch nil} (:cache-basis result)))
    (is (= {:basis 7 :branch nil}
           (:cache-basis
            (cache/resolve-basis!
             store context
             {:operation :can? :id :single-authority}
             (constantly false)))))))

(deftest exact-answer-retention-preserves-hot-historical-basis-test
  (let [store (cache/basis-cache {:max-entries 16})
        calls (atom [])
        resolve
        (fn [revision value]
          (exact!
           store revision (semantic-key :same)
           #(do (swap! calls conj revision) value)))]
    (doseq [revision (range 2 17)]
      (is (= [:cold revision]
             (:value (resolve revision [:cold revision])))))
    (is (= :one (:value (resolve 1 :one))))
    ;; Caffeine uses Window TinyLFU admission with frequency and recency; it
    ;; deliberately does not expose a strict-LRU victim. Exercise the product
    ;; property instead: a genuinely hot answer survives one-hit cold churn.
    ;; The CLJS adapter's true LRU obeys the same trace.
    (dotimes [candidate 100]
      (dotimes [_ 10]
        (resolve 1 :wrong))
      (let [revision (+ candidate 17)
            value [:cold revision]]
        (is (= value (:value (resolve revision value))))))
    (let [hot (resolve 1 :wrong)
          stats (cache/basis-cache-stats store)]
      (is (true? (:cached? hot)))
      (is (= :one (:value hot)))
      (is (= (vec (concat (range 2 17) [1] (range 17 117))) @calls)
          "only the initial answer and one-hit candidates are computed")
      (is (= 1001 (:exact-hits stats)))
      (is (= 116 (:misses stats)))
      (is (= 16 (get-in stats
                        [:subproblems :tiers :answer :entries]))))))

(deftest exact-hit-does-not-acquire-managed-proof-or-dirty-content-test
  (let [store (cache/basis-cache)
        proof-reads (atom 0)
        compute-calls (atom 0)
        options
        (assoc (basis-context 7)
               :managed-key-fn
               #(do (swap! proof-reads inc)
                    (descriptor [[1 4]])))
        invoke
        #(resolve-test-value!
          store options (semantic-key :lazy-proof)
          (fn [] (swap! compute-calls inc) :answer))]
    (is (= :answer (:value (invoke))))
    (is (= 1 @proof-reads)
        "the first exact miss may acquire the managed publication key")
    (let [revision (cache/cache-content-revision store)]
      (is (true? (:cached? (invoke))))
      (is (= :exact-basis (:cache-tier (invoke))))
      (is (= 1 @proof-reads))
      (is (= 1 @compute-calls))
      (is (= revision (cache/cache-content-revision store))
          "LRU touches are private policy state, not snapshot content"))))

(deftest cancellation-observed-by-exact-lookup-stops-managed-proof-test
  (let [store (cache/basis-cache)
        clock (atom 0)
        token (execution/cancellation-token)
        contract
        (binding [execution/*monotonic-nanos* #(deref clock)]
          (execution/normalize
           {:execution-timeout-ms 100}
           :can?
           {:cancellation-token token}))
        proof-reads (atom 0)
        compute-context (atom nil)
        original-lookup! subproblem/lookup!
        result
        (with-redefs [subproblem/lookup!
                      (fn [& args]
                        ;; The outer resolve check passed, but cancellation is
                        ;; observed by the exact-store lookup guard itself.
                        (execution/cancel! token)
                        (apply original-lookup! args))]
          (binding [execution/*contract* contract
                    execution/*monotonic-nanos* #(deref clock)]
            (cache/resolve-basis!
             store
             (assoc (basis-context 7)
                    :managed-key-fn
                    #(do
                       (swap! proof-reads inc)
                       (descriptor [[1 5]])))
             {:operation :can? :id :cancelled-exact-miss}
             #(do
                (reset! compute-context
                        {:store subproblem/*store*
                         :key-fn subproblem/*exact-denotation-key-fn*
                         :populate? subproblem/*populate?*})
                true))))]
    (is (false? (:cached? result)))
    (is (true? (:value result)))
    (is (zero? @proof-reads)
        "a guarded exact miss cannot fall through into proof acquisition")
    (is (= {:store nil :key-fn nil :populate? false}
           @compute-context)
        "post-cancellation evaluation is isolated from inherited cache state")
    (is (zero? (get-in (cache/basis-cache-stats store)
                       [:subproblems :lookup-probes])))))

(deftest complete-managed-proof-separates-relevant-writes-test
  (let [store (cache/basis-cache {:max-entries 12})
        calls (atom 0)
        query (semantic-key :managed)
        proof-a (descriptor 2 [[10 4] [20 7]])
        proof-b (descriptor 2 [[10 4] [20 12]])
        invoke
        (fn [revision proof value]
          (managed!
           store revision proof query {}
           #(do (swap! calls inc) value)))]
    (is (= :initial (:value (invoke 10 proof-a :initial))))
    (let [unrelated (invoke 11 proof-a :wrong)]
      (is (true? (:cached? unrelated)))
      (is (= :managed-current (:cache-tier unrelated)))
      (is (= :initial (:value unrelated))))
    (let [relevant (invoke 12 proof-b :changed)]
      (is (false? (:cached? relevant)))
      (is (= :changed (:value relevant))))
    (is (= 2 @calls))))

(deftest empty-managed-proof-follows-forward-only-contract-test
  (let [store (cache/basis-cache)
        calls (atom 0)
        empty-proof (descriptor 1 [])
        query (semantic-key :empty-proof)
        invoke
        (fn [revision value]
          (managed!
           store revision empty-proof query {}
           #(do (swap! calls inc) value)))]
    (is (= :computed (:value (invoke 3 :computed))))
    (is (= :computed (:value (invoke 4 :wrong))))
    (is (= 1 @calls))))

(deftest future-sibling-and-replaced-lifecycle-managed-candidates-fail-closed-test
  (testing "a future candidate cannot flow backward"
    (let [store (cache/basis-cache)
          proof (descriptor 1 [[1 5]])
          query (semantic-key :future)]
      (is (= :future
             (:value (managed! store 20 proof query {} (constantly :future)))))
      (let [past
            (managed! store 19 proof query {} (constantly :past))]
        (is (false? (:cached? past)))
        (is (= :past (:value past))))))
  (testing "branch and lifecycle are part of the managed source"
    (doseq [[label first-options second-options]
            [[:branch
              {:branch :main}
              {:branch :sibling}]
             [:lifecycle
              {:lifecycle :lifecycle-a}
              {:lifecycle :lifecycle-b}]]]
      (let [store (cache/basis-cache)
            proof (descriptor 1 [[1 5]])
            query (semantic-key label)]
        (managed! store 7 proof query first-options (constantly :first))
        (let [second
              (managed! store 7 proof query second-options
                        (constantly :second))]
          (is (false? (:cached? second)) (name label))
          (is (= :second (:value second)) (name label)))))))

(deftest identity-contract-is-part-of-managed-answer-source-test
  (let [store (cache/basis-cache)
        proof (descriptor 1 [[1 5]])
        query {:operation :contract-sensitive}
        v1 {:identity-contract :identity-v1}
        v2 {:identity-contract :identity-v2}]
    (is (= :v1
           (:value
            (managed! store 7 proof query v1 (constantly :v1)))))
    (let [changed
          (managed! store 8 proof query v2 (constantly :v2))]
      (is (false? (:cached? changed)))
      (is (= :v2 (:value changed)))))
  (testing "read-only reuse requires the same complete adapter identity"
    (let [store (cache/basis-cache)
          proof (descriptor 1 [[1 5]])
          query (semantic-key :read-only)]
      (managed! store 7 proof query {} (constantly :resident))
      (let [hit
            (cache/resolve-managed-read-only!
             store
             {:snapshot-order 8
              :managed-source
              (cache/managed-source-identity
               (lineage default-lifecycle)
               default-fingerprint
               default-identity-contract)
              :managed-key-fn (constantly proof)}
             query (constantly :wrong))]
        (is (true? (:cached? hit)))
        (is (= (box-value :resident) (:value hit))))
      (let [miss
            (cache/resolve-managed-read-only!
             store
             {:snapshot-order 8
              :managed-source
              (cache/managed-source-identity
               (lineage default-lifecycle)
               default-fingerprint
              :identity-v2)
              :managed-key-fn (constantly proof)}
             query (constantly :uncached))]
        (is (false? (:cached? miss)))
        (is (= :uncached (:value miss)))))))

(deftest cache-bypass-isolates-inherited-denotation-context-test
  (let [store (cache/basis-cache)
        inherited-store (subproblem/store)
        observed (fn []
                   {:store subproblem/*store*
                    :key-fn subproblem/*exact-denotation-key-fn*
                    :populate? subproblem/*populate?*})
        bypass-context (atom nil)
        managed-miss-context (atom nil)
        exact-context (atom nil)]
    (binding [subproblem/*store* inherited-store
              subproblem/*exact-denotation-key-fn* (constantly :inherited)
              subproblem/*populate?* true]
      (cache/resolve-basis!
       store {:exact-basis-key :incomplete}
       (semantic-key :ordinary-bypass)
       #(do (reset! bypass-context (observed)) :ordinary-bypass))
      (cache/resolve-managed-read-only!
       store
       {:snapshot-order 8
        :managed-source
        (cache/managed-source-identity
         (lineage default-lifecycle)
         default-fingerprint
         default-identity-contract)
        :managed-key-fn (constantly (descriptor 1 [[1 5]]))}
       (semantic-key :managed-miss)
       #(do (reset! managed-miss-context (observed)) :managed-miss))
      (exact!
       store 8 (semantic-key :ordinary-cached)
       #(do (reset! exact-context (observed)) :ordinary-cached)))
    (doseq [context [@bypass-context @managed-miss-context]]
      (is (nil? (:store context)))
      (is (nil? (:key-fn context)))
      (is (false? (:populate? context))))
    (is (identical? (:subproblems (cache/capture-cache-lifecycle store))
                    (:store @exact-context))
        "a cache-enabled nested call installs its selected lifecycle store")
    (is (ifn? (:key-fn @exact-context))
        "a cache-enabled nested call installs its own complete key constructor")
    (is (true? (:populate? @exact-context)))))

(deftest completed-page-retention-guard-is-common-and-nondestructive-test
  (doseq [[size expected-computations expected-second-hit?]
          [[1000 1 true]
           [1001 2 false]
           [10000 2 false]]]
    (let [store (cache/basis-cache {:max-entries 8})
          calls (atom 0)
          page (completed-page size)
          invoke
          #(exact!
            store size (semantic-key [:page size])
            (fn [] (swap! calls inc) page))]
      (is (= page (:value (invoke))) (str size))
      (let [second (invoke)]
        (is (= page (:value second)) (str size))
        (is (= expected-second-hit? (:cached? second)) (str size)))
      (is (= expected-computations @calls) (str size))))
  (testing "the same guard applies to managed publication"
    (let [store (cache/basis-cache)
          proof (descriptor 1 [[1 1]])
          page (completed-page 1001)
          calls (atom 0)
          invoke
          (fn [revision]
            (managed!
             store revision proof (semantic-key :large-managed) {}
             #(do (swap! calls inc) page)))]
      (is (= page (:value (invoke 2))))
      (is (= page (:value (invoke 3))))
      (is (= 2 @calls))))
  (testing "non-page values are unaffected"
    (doseq [[id value]
            [[:scalar true]
             [:count 10000]
             [:tree {:allowed? true :children (vec (range 1100))}]]]
      (let [store (cache/basis-cache)
            calls (atom 0)
            invoke
            #(exact! store 1 (semantic-key id)
                     (fn [] (swap! calls inc) value))]
        (is (= value (:value (invoke))))
        (is (true? (:cached? (invoke))))
        (is (= 1 @calls))))))

(deftest exact-and-managed-answers-share-one-lru-capacity-test
  (let [store (cache/basis-cache {:max-entries 2})
        proof (descriptor 1 [[1 1]])]
    (managed! store 1 proof (semantic-key :one) {} (constantly :one))
    (is (= 2 (get-in (cache/basis-cache-stats store)
                     [:subproblems :tiers :answer :entries]))
        "one exact and one managed mapping consume the same two-slot tier")
    (exact! store 2 (semantic-key :two) (constantly :two))
    (let [stats (cache/basis-cache-stats store)]
      (is (= 2 (get-in stats [:subproblems :tiers :answer :entries])))
      (is (= 2 (+ (:exact-entries stats) (:managed-entries stats)))))))

(deftest publication-suppression-preserves-results-test
  (let [store (cache/basis-cache)
        query (semantic-key :bypass)
        calls (atom 0)
        invoke
        (fn [options value]
          (resolve-test-value!
           store (merge (basis-context 1) options) query
           #(do (swap! calls inc) value)))]
    (is (= :suppressed
           (:value (invoke {:populate-cache? false} :suppressed))))
    (is (= :still-missing
           (:value (invoke {:populate-cache? false} :still-missing))))
    (is (= 2 @calls))
    (is (zero? (get-in (cache/basis-cache-stats store)
                       [:subproblems :tiers :answer :entries])))))

(deftest snapshot-v2-is-flat-deterministic-and-policy-free-test
  (let [options {:max-entries 4}
        left (cache/basis-cache options)
        right (cache/basis-cache options)
        bounds {:max-entries 4}]
    (exact-operation! left 2 (semantic-key :two) :can?
                      (constantly false))
    (exact-operation! left 1 (semantic-key :one) :can?
                      (constantly true))
    (exact-operation! right 1 (semantic-key :one) :can?
                      (constantly true))
    (exact-operation! right 2 (semantic-key :two) :can?
                      (constantly false))
    (let [snapshot (cache/export-basis-snapshot left bounds)
          other (cache/export-basis-snapshot right bounds)
          encoded (pr-str snapshot)]
      (is (= snapshot other))
      (is (= cache/basis-snapshot-format (:format snapshot)))
      (is (= 2 (:entry-count snapshot)))
      (is (= #{:format :entries :entry-count} (set (keys snapshot))))
      (doseq [forbidden ["weight" "priority" "tick" "tombstone"
                         "generation-count" "access-queue"]]
        (is (not (str/includes? encoded forbidden)) forbidden))
      (let [restored (cache/basis-cache options)]
        (is (= {:restored? true :entry-count 2}
               (cache/restore-basis-snapshot! restored snapshot bounds)))
        (is (= true
               (:value
                (exact-operation!
                 restored 1 (semantic-key :one) :can?
                 (constantly false)))))
        (is (= snapshot (cache/export-basis-snapshot restored bounds)))))))

(deftest promoted-managed-answer-restores-through-its-managed-key-test
  (let [source (cache/basis-cache {:max-entries 8})
        target (cache/basis-cache {:max-entries 8})
        forged-target (cache/basis-cache {:max-entries 8})
        proof (descriptor 1 [[1 4]])
        query (assoc (semantic-key :promoted-snapshot)
                     :operation :can?)
        invoke
        (fn [store revision value]
          (cache/resolve-basis!
           store
           (assoc (basis-context revision)
                  :managed-key-fn (constantly proof))
           query (constantly value)))
        bounds {:max-entries 8}
        first-result (invoke source 7 false)
        snapshot-before-promotion
        (cache/export-basis-snapshot source bounds)
        revision-before-promotion (cache/cache-content-revision source)
        promoted (invoke source 8 true)
        revision-after-promotion (cache/cache-content-revision source)
        snapshot (cache/export-basis-snapshot source bounds)
        exact-entry-index
        (first
         (keep-indexed
          (fn [index {:keys [key]}]
            (when (= :exact (second (nth key 2))) index))
          (:entries snapshot)))
        managed-entry-index
        (first
         (keep-indexed
          (fn [index {:keys [key]}]
            (when (= :managed (second (nth key 2))) index))
          (:entries snapshot)))
        promoted-entry
        (some
         (fn [{:keys [key] :as entry}]
           (let [[_ mode _ _ _ reuse] (nth key 2)]
             (when (and (= :exact mode)
                        (= 8 (get-in reuse [:basis-identity :revision])))
               entry)))
         (:entries snapshot))]
    (is (false? (:cached? first-result)))
    (is (true? (:cached? promoted)))
    (is (= :managed-current (:cache-tier promoted)))
    (is (< revision-before-promotion revision-after-promotion)
        "a local managed-to-exact promotion conservatively dirties content")
    (is (= snapshot-before-promotion snapshot)
        "the promoted exact mapping has no independent portable origin")
    (is (nil? promoted-entry)
        "process-local promoted exact entries have no portable origin proof")
    (is (= :eacl/incompatible-cache-snapshot
           (:type
            (error-data
             #(cache/restore-basis-snapshot!
               forged-target
               (update-in snapshot
                          [:entries exact-entry-index :value
                           :computed-revision]
                          dec)
               bounds))))
        "a lower-revision value cannot masquerade as a portable exact entry")
    (let [maximum-locator-string
          (str/join (repeat 4096 "x"))
          oversized-locator-string
          (str/join (repeat 4097 "x"))]
      (doseq [locator [nil 0 exact-integer/maximum
                       maximum-locator-string]
              [label forged]
              [[:exact-key-locator
                (-> snapshot
                    (assoc-in [:entries exact-entry-index :key 2 5
                               :basis-identity :exact-locator]
                              locator)
                    (assoc-in [:entries exact-entry-index :value
                               :computed-exact-locator]
                              locator))]
               [:managed-value-locator
                (assoc-in snapshot
                          [:entries managed-entry-index :value
                           :computed-exact-locator]
                          locator)]]]
        (is (= {:restored? true :entry-count 2}
               (cache/restore-basis-snapshot!
                forged-target forged bounds))
            (str (name label) " accepts " (pr-str locator))))
      (doseq [locator [-1 (inc exact-integer/maximum) ""
                       oversized-locator-string :invalid-locator
                       {:forged :portable-but-invalid}]
              [label forged]
              [[:exact-key-locator
                (-> snapshot
                    (assoc-in [:entries exact-entry-index :key 2 5
                               :basis-identity :exact-locator]
                              locator)
                    (assoc-in [:entries exact-entry-index :value
                               :computed-exact-locator]
                              locator))]
               [:managed-value-locator
                (assoc-in snapshot
                          [:entries managed-entry-index :value
                           :computed-exact-locator]
                          locator)]]]
        (is (= :eacl/incompatible-cache-snapshot
               (:type
                (error-data
                 #(cache/restore-basis-snapshot!
                   forged-target forged bounds))))
            (str (name label) " rejects " (pr-str locator)))))
    (is (= {:restored? true :entry-count 2}
           (cache/restore-basis-snapshot! target snapshot bounds)))
    (let [restored-hit (invoke target 8 true)]
      (is (true? (:cached? restored-hit)))
      (is (= :managed-current (:cache-tier restored-hit)))
      (is (false? (:value restored-hit))))))

(deftest snapshot-restore-fails-closed-and-installs-nothing-test
  (let [source (cache/basis-cache {:max-entries 4})
        target (cache/basis-cache {:max-entries 4})
        bounds {:max-entries 4}]
    (exact-operation! source 1 (semantic-key :one) :can?
                      (constantly true))
    (let [snapshot (cache/export-basis-snapshot source bounds)
          before (cache/export-basis-snapshot target bounds)
          revision (cache/cache-content-revision target)
          v1 (assoc snapshot :format :eacl.cache/basis-snapshot-v1)
          malformed-value
          (assoc-in snapshot [:entries 0 :value]
                    {:format :attacker/partial-value})]
      (is (= :eacl/incompatible-cache-snapshot
             (:type
              (error-data
               #(cache/restore-basis-snapshot! target v1 bounds)))))
      (is (contains?
           #{:eacl/incompatible-cache-snapshot
             :eacl/cache-snapshot-incompatible}
           (:type
            (error-data
             #(cache/restore-basis-snapshot!
               target malformed-value bounds)))))
      (is (= before (cache/export-basis-snapshot target bounds)))
      (is (= revision (cache/cache-content-revision target))))))

(deftest optional-telemetry-does-not-disable-required-lru-hits-test
  (let [store (cache/basis-cache {:max-entries 2 :telemetry? false})]
    (exact! store 1 (semantic-key :hot) (constantly :hot))
    (exact! store 2 (semantic-key :cold) (constantly :cold))
    (dotimes [_ 10]
      (is (= :hot
             (:value
              (exact! store 1 (semantic-key :hot) (constantly :wrong))))))
    (exact! store 3 (semantic-key :new) (constantly :new))
    (is (true?
         (:cached?
          (exact! store 1 (semantic-key :hot) (constantly :wrong)))))
    (let [query (assoc (semantic-key :rendered-telemetry-off)
                       :operation :lookup-resources)]
      (cache/publish-rendered-page!
       store (basis-context 1) query (rendered-page 1))
      (is (some? (cache/lookup-rendered-page!
                  store (basis-context 1) query))))
    (let [stats (cache/basis-cache-stats store)]
      (is (<= (:entries stats) 2)
          "settled adaptive eviction remains within the configured capacity")
      (is (false? (:telemetry-enabled? stats)))
      (is (zero? (:hits stats)))
      (is (zero? (:exact-hits stats)))
      (is (zero? (:managed-hits stats)))
      (is (zero? (:rendered-page-hits stats)))
      (is (zero? (:misses stats))))))

(deftest proof-contract-violation-disables-only-managed-lifting-test
  (let [reported (atom [])
        store
        (cache/basis-cache
         {:proof-contract-reporter #(swap! reported conj %)})
        diagnostic
        {:status :contract-violation
         :reason :noncanonical-relation-ids}]
    (cache/record-proof-diagnostic! store diagnostic)
    (cache/record-proof-diagnostic! store diagnostic)
    (is (= [diagnostic] @reported))
    (is (true? (:managed-lifting-disabled?
                (cache/basis-cache-stats store))))
    (let [proof-calls (atom 0)
          result
          (managed!
           store 2 (descriptor [[1 1]]) (semantic-key :disabled-managed)
           {:managed-key-fn
            #(do (swap! proof-calls inc) (descriptor [[1 1]]))}
           (constantly :fresh))]
      (is (= :fresh (:value result)))
      (is (zero? @proof-calls)))
    (is (= :exact
           (:value
            (exact! store 3 (semantic-key :exact-still-works)
                    (constantly :exact)))))))

#?(:clj
   (deftest proof-contract-reporter-has-one-first-sighting-winner-test
     (let [reports (atom [])
           store
           (cache/basis-cache
            {:proof-contract-reporter #(swap! reports conj %)})
           diagnostic
           {:status :contract-violation
            :reason :concurrent-first-sighting}
           claimed (:reported-contract-violations store)
           original-cas compare-and-set!
           ready (java.util.concurrent.CountDownLatch. 2)
           release (java.util.concurrent.CountDownLatch. 1)
           guarded-cas
           (fn [target expected replacement]
             (when (identical? target claimed)
               (.countDown ready)
               (.await release))
             (original-cas target expected replacement))]
       (with-redefs [compare-and-set! guarded-cas]
         (let [calls
               (mapv
                (fn [_]
                  (future
                    (cache/record-proof-diagnostic! store diagnostic)))
                (range 2))]
           (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
           (.countDown release)
           (doseq [call calls]
             (is (not= ::timeout (deref call 5000 ::timeout))))))
       (is (= [diagnostic] @reports))
       (is (= #{:concurrent-first-sighting} @claimed)))))
