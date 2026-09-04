(ns eacl.datascript.snapshot-lifecycle-test
  #?(:cljs (:require-macros [eacl.core :as eacl]))
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.string :as str]
            [datascript.core :as ds]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.backend.writer :as writer]
            [eacl.cache :as cache]
            [eacl.causal-token :as causal-token]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine]
            [eacl.execution :as execution]
            [eacl.proof-frame :as proof-frame]
            [eacl.request.context :as request-context]
            [eacl.request.counters :as request-counters]
            [eacl.secure-format :as secure]
            [eacl.spicedb.consistency :as consistency]))

(def schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(defn- fixture
  []
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {:cache cache/no-cache})
        user (eacl/spice-object :user "user")
        account (eacl/spice-object :account "account")]
    (eacl/write-schema! client schema)
    (ds/transact! conn [{:eacl/id "user"}
                        {:eacl/id "account"}])
    (eacl/create-relationship!
     client (eacl/->Relationship user :owner account))
    {:conn conn :client client :user user :account account}))

(def ^:private authorization-scan-schema
  "definition user {}
   definition document {
     relation candidate: user
     relation viewer: user
     permission view = viewer
   }")

(defn- authorization-scan-fixture
  []
  (let [conn (datascript/create-conn)
        writer-client (datascript/make-client conn {:cache cache/no-cache})
        marker (eacl/spice-object :user "marker")
        sparse (eacl/spice-object :user "sparse")
        documents
        (mapv #(eacl/spice-object :document (str "document-" %)) (range 4))]
    (eacl/write-schema! writer-client authorization-scan-schema)
    (ds/transact!
     conn
     (mapv (fn [id] {:eacl/id id})
           (concat ["marker" "sparse"] (map :id documents))))
    (eacl/create-relationships!
     writer-client
     (vec
      (concat
       (map #(eacl/->Relationship marker :candidate %) documents)
       [(eacl/->Relationship sparse :viewer (nth documents 0))
        (eacl/->Relationship sparse :viewer (nth documents 3))])))
    {:conn conn :sparse sparse}))

(defn- authorization-scan-summary
  [page]
  {:resource-ids (mapv #(get-in % [:resource :id]) (:data page))
   :bounded? (get-in page [:page-info :bounded?])
   :has-next-page? (get-in page [:page-info :has-next-page?])
   :cached? (:cached? page)})

(defn- structural-children
  [value]
  (cond
    (map? value) (concat (keys value) (vals value))
    (sequential? value) value
    (set? value) value
    #?(:clj (instance? clojure.lang.IAtom value)
       :cljs (satisfies? cljs.core/IAtom value)) [@value]
    :else nil))

(defn- reachable-values
  [root]
  (tree-seq #(boolean (seq (structural-children %)))
            structural-children
            root))

(defn- observed-call
  [conn f]
  (let [provider-calls (atom {})
        db-calls (atom 0)
        original-db ds/db
        value
        (with-redefs [ds/db (fn [candidate]
                              (when (identical? conn candidate)
                                (swap! db-calls inc))
                              (original-db candidate))]
          (binding [source/*source-op-stats* provider-calls]
            (f)))]
    {:value value
     :provider-calls @provider-calls
     :db-calls @db-calls}))

(defn- runtime-cache-lifecycle
  [client]
  @(get-in client [:runtime :runtime-lifecycle-state]))

(defn- lifecycle-children
  [lifecycle]
  (into {}
        (filter (comp some? val))
        (select-keys lifecycle
                     [:basis-cache-store :continuation-cache-store
                      :cursor-codec-cache :cursor-construction-cache
                      :derived-schema-caches])))

(defn- late-publication-context
  [source-lifecycle]
  {:exact-basis-key
   {:key-version 2
    :backend :datascript
    :basis-identity
    {:backend :datascript
     :source-id :runtime-lifecycle-test
     :branch nil
     :source-lifecycle source-lifecycle
     :basis-kind :ordinary
     :revision 1
     :exact-locator 1
    :backend-snapshot-id {:runtime-lifecycle-test 1}}
    :adapter-fingerprint :runtime-lifecycle-test
    :identity-contract :runtime-lifecycle-test}})

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Throwable :cljs :default) error
      (ex-data error))))

(defn- completed-answer-entry-index
  ([snapshot operation]
   (completed-answer-entry-index snapshot operation nil))
  ([snapshot operation limited?]
   (first
    (keep-indexed
     (fn [index {:keys [tier key]}]
       (let [[entry-operation semantic-key] (get-in key [2 4])
             answer-query
             (or (get-in semantic-key [:query :internal])
                 (get-in semantic-key [:query :public]))]
         (when (and (= :answer tier)
                    (= operation entry-operation)
                    (= operation (:operation semantic-key))
                    (or (nil? limited?)
                        (and (map? answer-query)
                             (= limited?
                                (contains? answer-query :count-limit)))))
           index)))
     (:entries snapshot)))))

(declare observed-failure)

(deftest every-public-read-has-one-current-snapshot-scope-test
  (let [{:keys [conn client user account]} (fixture)
        operations
        [[:can? #(eacl/can? client user :admin account)]
         [:check-permission
          #(eacl/check-permission client user :admin account)]
         [:read-schema #(eacl/read-schema client)]
         [:read-relationships
          #(eacl/read-relationships
            client {:subject/type :user :first 10})]
         [:lookup-resources
          #(eacl/lookup-resources
            client
            {:subject user
             :resource/type :account
             :permission :admin
             :first 10})]
         [:count-resources
          #(eacl/count-resources
            client
            {:subject user
             :resource/type :account
             :permission :admin})]
         [:lookup-subjects
          #(eacl/lookup-subjects
            client
            {:resource account
             :subject/type :user
             :permission :admin
             :first 10})]
         [:count-subjects
          #(eacl/count-subjects
            client
            {:resource account
             :subject/type :user
             :permission :admin})]
         [:expand-permission-tree
          #(eacl/expand-permission-tree
            client {:resource account :permission :admin})]]]
    (doseq [[operation f] operations]
      (testing (name operation)
        (let [ledger (request-counters/make-ledger)
              {:keys [value provider-calls db-calls]}
              (binding [request-counters/*ledger* ledger]
                (observed-call conn f))
              counts (request-counters/snapshot ledger)]
          (is (some? value))
          (is (= 1 (:acquire-current! provider-calls 0)))
          (is (= 1 (:release! provider-calls 0)))
          (is (= 1 db-calls))
          (is (= 1 (:public-entries counts)))
          (is (= 1 (:acquisitions counts)))
          (is (= 1 (:context-constructions counts)))
          (is (= 1 (:releases counts))))))))

(deftest retained-snapshot-reuses-one-basis-across-request-contexts-test
  (let [{:keys [conn client user account]} (fixture)
        cached-client (datascript/make-client conn {:cache {}})
        ledger (request-counters/make-ledger)
        {:keys [value provider-calls db-calls]}
        (binding [request-counters/*ledger* ledger]
          (observed-call
           conn
           #(eacl/with-snapshot [snapshot (eacl/snapshot cached-client)]
              {:uncached-decision
               (eacl/check-permission
                snapshot {:subject user
                          :permission :admin
                          :resource account
                          :cache? false})
               :cached-decisions
               (mapv
                (fn [_]
                  (eacl/check-permission
                   snapshot {:subject user
                             :permission :admin
                             :resource account}))
                (range 2))
               :count
               (eacl/count-resources
                snapshot {:subject user
                          :resource/type :account
                          :permission :admin
                          :cache? false})})))
        counts (request-counters/snapshot ledger)]
    (is (= {:allowed? true
            :cached? false
            :cache-basis nil
            :evaluation :demand}
           (:uncached-decision value)))
    (is (= [false true]
           (mapv :cached? (:cached-decisions value))))
    (is (every? :allowed? (:cached-decisions value)))
    (is (= 1 (get-in value [:count :count])))
    (is (false? (get-in value [:count :cached?])))
    (is (= 1 (:acquire-current! provider-calls 0)))
    (is (= 1 (:release! provider-calls 0)))
    (is (= 1 db-calls))
    (is (= {:public-entries 4
            :acquisitions 1
            :context-constructions 4
            :releases 1}
           (select-keys counts
                        [:public-entries :acquisitions
                         :context-constructions :releases])))))

(deftest retained-snapshot-read-honours-request-cancellation-test
  (let [{:keys [conn client user account]} (fixture)
        cancellation-token (eacl/cancellation-token)
        _ (eacl/cancel! cancellation-token)
        ledger (request-counters/make-ledger)
        {:keys [value provider-calls]}
        (binding [request-counters/*ledger* ledger]
          (observed-call
           conn
           #(eacl/with-snapshot [snapshot (eacl/snapshot client)]
              (try
                (eacl/can?
                 snapshot
                 {:subject user
                  :permission :admin
                  :resource account
                  :cancellation-token cancellation-token})
                nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                       error
                  (ex-data error))))))
        counts (request-counters/snapshot ledger)]
    (is (= :eacl.execution/cancelled (:type value)))
    (is (= 1 (:acquire-current! provider-calls 0)))
    (is (= 1 (:release! provider-calls 0)))
    (is (= 1 (:context-constructions counts)))
    (is (= 1 (:releases counts)))))

(deftest acl-construction-and-read-only-refusal-acquire-nothing-test
  (let [conn (datascript/create-conn)
        construction (observed-call
                      conn
                      #(datascript/make-client
                        conn {:cache cache/no-cache :read-only? true}))
        acl (:value construction)
        failure (observed-failure
                 conn
                 #(eacl/write-schema! acl schema))]
    (is (eacl/acl? acl))
    (is (= 1 (:db-calls construction)) "One bounded storage admission read.")
    (is (empty? (:provider-calls construction)))
    (is (= :eacl/unsupported-capability
           (:type (ex-data (:failure failure)))))
    (is (= :write (:capability (ex-data (:failure failure)))))
    (is (zero? (:db-calls failure)))
    (is (empty? (:provider-calls failure)))))

(deftest raw-database-snapshot-constructor-is-not-public-test
  #?(:clj
     (is (nil? (ns-resolve 'eacl.datascript.core 'snapshot)))
     :cljs
     (is true))
  (let [{:keys [conn]} (fixture)
        other-conn (datascript/create-conn)
        cases
        [(ds/db conn)
         (ds/filter (ds/db conn) (fn [_ _] true))
         (ds/db other-conn)
         (ds/db-with (ds/db conn) [{:eacl/id "speculative-marker"}])]]
    (doseq [value cases]
      (let [ledger (request-counters/make-ledger)
            data
            (binding [request-counters/*ledger* ledger]
              (try
                (eacl/snapshot value)
                nil
                (catch #?(:clj clojure.lang.ExceptionInfo
                          :cljs cljs.core.ExceptionInfo)
                       error
                  (ex-data error))))
            counts (request-counters/snapshot ledger)]
        (is (= :eacl/unsupported-capability (:type data)))
        (is (= (:type data) (:eacl/error data)))
        (is (= :snapshot (:capability data)))
        (is (= :non-eacl (:target data)))
        (is (zero? (:acquisitions counts)))
        (is (zero? (:context-constructions counts)))
        (is (zero? (:adapter-reads counts)))))))

(deftest retained-snapshot-assertion-errors-precede-all-backend-work-test
  (let [{:keys [conn client]} (fixture)
        old-snapshot (eacl/snapshot client)
        _ (ds/transact! conn [{:eacl/id "unrelated-advance"}])
        new-snapshot (eacl/snapshot client)
        newer-token (eacl/basis-token new-snapshot)
        cases
        [[:eacl.consistency/selection-required
          #(eacl/read-schema
            old-snapshot {:consistency consistency/fully-consistent})]
         [:eacl.consistency/freshness-unavailable
          #(eacl/read-schema
            old-snapshot
            {:consistency (consistency/at-least-as-fresh newer-token)})]
         [:eacl.consistency/basis-conflict
          #(eacl/read-schema
            old-snapshot
            {:consistency (consistency/at-exact-snapshot newer-token)})]]]
    (try
      (doseq [[expected-type operation] cases]
        (let [ledger (request-counters/make-ledger)
              backend-ops (atom {})
              source-ops (atom {})
              data
              (binding [request-counters/*ledger* ledger
                        backend/*backend-op-stats* backend-ops
                        source/*source-op-stats* source-ops]
                (try
                  (operation)
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo
                            :cljs cljs.core.ExceptionInfo)
                         error
                    (ex-data error))))
              counts (request-counters/snapshot ledger)]
          (is (= expected-type (:type data)))
          (is (= (:type data) (:eacl/error data)))
          (is (empty? @source-ops))
          (is (empty? @backend-ops))
          (is (zero? (:acquisitions counts)))
          (is (zero? (:context-constructions counts)))
          (is (zero? (:adapter-reads counts)))
          (is (zero? (:writer-submissions counts)))))
      (finally
        (eacl/release! old-snapshot)
        (eacl/release! new-snapshot)))))

(deftest retained-snapshot-structure-and-lifecycle-test
  (let [{:keys [conn client user account]} (fixture)
        ledger (request-counters/make-ledger)
        backend-ops (atom {})
        {:keys [value provider-calls db-calls]}
        (binding [request-counters/*ledger* ledger
                  backend/*backend-op-stats* backend-ops]
          (observed-call
           conn
           #(let [snapshot (eacl/snapshot client)
                  adapter (get-in snapshot [:basis :adapter])
                  forbidden-operation-errors
                  (mapv
                   (fn [operation]
                     (try
                       (backend/operation adapter operation)
                       nil
                       (catch #?(:clj clojure.lang.ExceptionInfo
                                 :cljs cljs.core.ExceptionInfo)
                              error
                         (:type (ex-data error)))))
                   [:select-current :select-authoritative
                    :select-at-least :select-exact
                    :source-scope :source-lifecycle])]
              (try
                (let [basis (eacl/basis snapshot)
                      token? (string? (eacl/basis-token snapshot))
                      decisions [(eacl/can? snapshot user :admin account)
                                 (eacl/can? snapshot user :admin account)]
                      write-error
                      (try
                        (eacl/delete-object! snapshot account)
                        nil
                        (catch #?(:clj clojure.lang.ExceptionInfo
                                  :cljs cljs.core.ExceptionInfo)
                               error
                          (ex-data error)))
                      first-release (eacl/release! snapshot)
                      second-release (eacl/release! snapshot)
                      released? (eacl/released? snapshot)
                      post-release
                      (try
                        (eacl/read-schema snapshot)
                        nil
                        (catch #?(:clj clojure.lang.ExceptionInfo
                                  :cljs cljs.core.ExceptionInfo)
                               error
                          (ex-data error)))]
                  {:snapshot snapshot
                   :basis basis
                   :token? token?
                   :decisions decisions
                   :forbidden-operation-errors forbidden-operation-errors
                   :write-error write-error
                   :first-release first-release
                   :second-release second-release
                   :released? released?
                   :post-release post-release})
                (finally
                  (eacl/release! snapshot))))))
        snapshot (:snapshot value)
        runtime (:runtime snapshot)
        snapshot-api (:api snapshot)
        source-role (:source client)
        writer-role (:writer client)
        forbidden-identities
        (into [conn source-role writer-role]
              (concat
               (vals (::source/operations source-role))
               (vals (::writer/operations writer-role))))
        reachable (reachable-values runtime)
        counts (request-counters/snapshot ledger)]
    (is (eacl/snapshot? snapshot))
    (is (not (eacl/acl? snapshot)))
    (is (= :ordinary (get-in value [:basis :kind])))
    (is (:token? value))
    (is (= [true true] (:decisions value)))
    (is (= (repeat 6 :eacl/unsupported-capability)
           (:forbidden-operation-errors value)))
    (is (empty?
         (set/intersection
          #{:conn :writer :selected-snapshot
            :acquire-current! :acquire-authoritative!
            :acquire-at-least! :acquire-exact!}
          (set (filter keyword? reachable)))))
    (is (not-any?
         (fn [value]
           (some #(identical? value %) forbidden-identities))
         reachable))
    (is (= #{:backend-id :schema :impl
             :basis-adapter :basis-adapter-config-keys
             :native-with :normalize-report-datom
             :schema-storage-datom? :transaction-datom?
             :relation-version-attribute :prepare-relationship-tx}
           (set (keys snapshot-api))))
    (is (= :eacl/unsupported-capability
           (get-in value [:write-error :type])))
    (is (= :write (get-in value [:write-error :capability])))
    (is (true? (:first-release value)))
    (is (false? (:second-release value)))
    (is (true? (:released? value)))
    (is (= :eacl/snapshot-released
           (get-in value [:post-release :type])))
    (is (= 1 (:acquire-current! provider-calls 0)))
    (is (= 1 (:release! provider-calls 0)))
    (is (= 1 db-calls))
    (is (= 1 (:acquisitions counts)))
    (is (= 2 (:context-constructions counts)))
    (is (= 1 (:releases counts)))))

(deftest retained-snapshot-after-lifecycle-rotation-cannot-repopulate-runtime-test
  (let [{:keys [conn user account]} (fixture)
        client (datascript/make-client conn {:cache {}})
        selected (eacl/snapshot client)]
    (try
      (is (true? (eacl/can? selected user :admin account)))
      (is (pos? (+ (get (datascript/cache-stats client) :exact-entries 0)
                   (get (datascript/cache-stats client) :managed-entries 0))))
      (datascript/expire-cache! client "rotated-lifecycle")
      (is (zero? (+ (get (datascript/cache-stats client) :exact-entries 0)
                    (get (datascript/cache-stats client) :managed-entries 0))))
      (is (true? (eacl/can? selected user :admin account))
          "rotation does not invalidate the retained immutable basis")
      (is (zero? (+ (get (datascript/cache-stats client) :exact-entries 0)
                    (get (datascript/cache-stats client) :managed-entries 0)))
          "the old lineage remains unreachable from the cleared runtime")
      (finally
        (eacl/release! selected)))))

(deftest retained-snapshot-reattaches-only-after-cache-store-rotation-test
  (let [{:keys [conn user account]} (fixture)
        client (datascript/make-client conn {:cache {:max-entries 16}})
        selected (eacl/snapshot client)
        prepared-options
        (get-in selected
                [:runtime ::orchestration/prepared-runtime-options])
        prepared-lifecycle (:runtime-cache-lifecycle prepared-options)
        context-input (atom nil)
        original-make-context request-context/make-context]
    (try
      ;; A retained snapshot must normalize deadline/cancellation state from
      ;; each read; it cannot reuse the contract from snapshot acquisition.
      (is (not (contains? prepared-options :execution-contract)))
      ;; Publication advances the lifecycle record's content revision while
      ;; retaining the installed stores. The next hit must keep using the
      ;; prepared map rather than rebuilding it for that bookkeeping change.
      (is (true? (eacl/can? selected user :admin account)))
      (let [published (runtime-cache-lifecycle client)]
        (is (not (identical? prepared-lifecycle published)))
        (is (identical? (:basis-cache-store prepared-lifecycle)
                        (:basis-cache-store published)))
        (with-redefs
         [request-context/make-context
          (fn [input]
            (reset! context-input input)
            (original-make-context input))]
          (is (true? (eacl/can? selected user :admin account))))
        (is (identical?
             prepared-lifecycle
             (get-in @context-input [:runtime :runtime-cache-lifecycle]))))

      ;; A narrow clear installs a genuinely new answer store. The retained
      ;; basis remains valid and must attach that store before evaluating.
      (orchestration/clear-answer-cache! client)
      (let [cleared (runtime-cache-lifecycle client)]
        (reset! context-input nil)
        (with-redefs
         [request-context/make-context
          (fn [input]
            (reset! context-input input)
            (original-make-context input))]
          (is (true? (eacl/can? selected user :admin account))))
        (is (identical?
             cleared
             (get-in @context-input [:runtime :runtime-cache-lifecycle])))
        (is (identical?
             (:basis-cache-store cleared)
             (get-in @context-input [:runtime :basis-cache-store]))))
      (finally
        (eacl/release! selected)))))

(deftest retained-snapshot-detects-same-token-full-source-incarnation-test
  (let [{:keys [conn user account]} (fixture)
        client (datascript/make-client conn {:cache {:max-entries 16}})
        selected (eacl/snapshot client)
        initial (runtime-cache-lifecycle client)
        source-lifecycle (:source-lifecycle initial)]
    (try
      (is (identical? (:source-incarnation initial)
                      (get-in selected [:basis :source-incarnation])))
      (is (true? (eacl/can? selected user :admin account)))

      ;; Narrow answer clearing preserves the source incarnation, so this
      ;; retained immutable basis may populate the newly installed answer LRU.
      (orchestration/clear-answer-cache! client)
      (let [narrowed (runtime-cache-lifecycle client)]
        (is (identical? (:source-incarnation initial)
                        (:source-incarnation narrowed)))
        (is (zero? (:entries (datascript/cache-stats client))))
        (is (true? (eacl/can? selected user :admin account)))
        (is (pos? (:entries (datascript/cache-stats client))))

        ;; A full rotation may intentionally reuse the coordinated external
        ;; token. Its private incarnation still retires the retained basis and
        ;; keeps every newly installed cache child unreachable from it.
        (datascript/expire-cache! client source-lifecycle)
        (let [rotated (runtime-cache-lifecycle client)]
          (is (= source-lifecycle (:source-lifecycle rotated)))
          (is (not (identical? (:source-incarnation narrowed)
                               (:source-incarnation rotated))))
          (is (zero? (:entries (datascript/cache-stats client))))
          (is (true? (eacl/can? selected user :admin account)))
          (is (zero? (:entries (datascript/cache-stats client))))))
      (finally
        (eacl/release! selected)))))

(deftest source-selection-and-cache-capture-share-one-runtime-lifecycle-test
  (let [{:keys [conn user account]} (fixture)
        client (datascript/make-client conn {:cache {:max-entries 16}})
        before (runtime-cache-lifecycle client)
        rotated? (atom false)
        context-input (atom nil)
        original-source-lifecycle source/source-lifecycle
        original-make-context request-context/make-context]
    (with-redefs
     [source/source-lifecycle
      (fn [basis-source]
        (when (compare-and-set! rotated? false true)
          (datascript/expire-cache! client "selection-race-l1"))
        (original-source-lifecycle basis-source))
      request-context/make-context
      (fn [input]
        (reset! context-input input)
        (original-make-context input))]
      (is (true? (eacl/can? client user :admin account))))
    (let [after (runtime-cache-lifecycle client)
          captured-runtime (:runtime @context-input)]
      (is @rotated?)
      (is (= "selection-race-l1" (:source-lifecycle after)))
      (is (= "selection-race-l1"
             (get-in @context-input
                     [:basis-identity :source-lifecycle])))
      (is (identical? (:basis-cache-store after)
                      (:basis-cache-store captured-runtime)))
      (is (identical? (:continuation-cache-store after)
                      (:continuation-cache-store captured-runtime)))
      (is (identical? (:derived-schema-caches after)
                      (:derived-registry @context-input)))
      (is (not (identical? (:basis-cache-store before)
                           (:basis-cache-store captured-runtime))))
      (is (pos? (:entries (datascript/cache-stats client)))))))

(deftest same-token-full-rotation-retries-source-selection-on-new-incarnation-test
  (let [{:keys [conn user account]} (fixture)
        client (datascript/make-client conn {:cache {:max-entries 16}})
        before (runtime-cache-lifecycle client)
        source-lifecycle (:source-lifecycle before)
        rotated? (atom false)
        context-input (atom nil)
        original-source-lifecycle source/source-lifecycle
        original-make-context request-context/make-context]
    (with-redefs
     [source/source-lifecycle
      (fn [basis-source]
        (when (compare-and-set! rotated? false true)
          ;; The external token is deliberately unchanged. The private
          ;; incarnation must still force selection to release and retry.
          (datascript/expire-cache! client source-lifecycle))
        (original-source-lifecycle basis-source))
      request-context/make-context
      (fn [input]
        (reset! context-input input)
        (original-make-context input))]
      (is (true? (eacl/can? client user :admin account))))
    (let [after (runtime-cache-lifecycle client)
          captured-runtime (:runtime @context-input)]
      (is @rotated?)
      (is (= source-lifecycle (:source-lifecycle after)))
      (is (not (identical? (:source-incarnation before)
                           (:source-incarnation after))))
      (is (identical? (:basis-cache-store after)
                      (:basis-cache-store captured-runtime)))
      (is (not (identical? (:basis-cache-store before)
                           (:basis-cache-store captured-runtime)))))))

(deftest narrow-clear-rotates-only-authorization-and-continuation-test
  (let [{:keys [conn]} (fixture)
        client (datascript/make-client conn {:cache {:max-entries 16}})
        before (runtime-cache-lifecycle client)
        revision-before (orchestration/cache-content-revision client)]
    (orchestration/clear-answer-cache! client)
    (let [after (runtime-cache-lifecycle client)]
      (is (not (identical? before after)))
      (is (not (identical? (:token before) (:token after))))
      (is (identical? (:source-incarnation before)
                      (:source-incarnation after)))
      (is (= (:source-lifecycle before) (:source-lifecycle after)))
      (is (not (identical? (:basis-cache-store before)
                           (:basis-cache-store after))))
      (is (not (identical? (:continuation-cache-store before)
                           (:continuation-cache-store after))))
      (doseq [child [:cursor-codec-cache :cursor-construction-cache
                     :derived-schema-caches]]
        (is (identical? (get before child) (get after child)) (name child)))
      (is (> (orchestration/cache-content-revision client)
             revision-before)))))

(deftest full-rotation-detaches-every-runtime-cache-child-test
  (let [{:keys [conn]} (fixture)
        client
        (datascript/make-client conn {:cache {:max-entries 16}})
        before (runtime-cache-lifecycle client)]
    (datascript/expire-cache! client "full-rotation-l1")
    (let [after (runtime-cache-lifecycle client)]
      (is (= "full-rotation-l1" (:source-lifecycle after)))
      (is (not (identical? (:source-incarnation before)
                           (:source-incarnation after))))
      (doseq [[child old-value] (lifecycle-children before)]
        (is (some? old-value) (str child " precondition"))
        (is (not (identical? old-value (get after child))) (name child))))))

(deftest late-old-runtime-publication-is-unreachable-test
  (let [{:keys [conn]} (fixture)
        client (datascript/make-client conn {:cache {:max-entries 16}})
        before (runtime-cache-lifecycle client)
        old-store (:basis-cache-store before)
        old-cache-lifecycle (cache/capture-cache-lifecycle old-store)
        context
        (assoc (late-publication-context (:source-lifecycle before))
               :cache-lifecycle old-cache-lifecycle)]
    (datascript/expire-cache! client "late-publication-l1")
    (is (= true
           (:value
            (cache/resolve-basis!
             old-store context {:operation :can?
                                :id :late-runtime-publication}
             (constantly true)))))
    (is (pos? (:entries (cache/basis-cache-stats old-store))))
    (let [installed (runtime-cache-lifecycle client)]
      (is (not (identical? old-store (:basis-cache-store installed))))
      (is (zero? (:entries
                  (cache/basis-cache-stats
                   (:basis-cache-store installed))))))))

(deftest publication-racing-rotation-cannot-regress-runtime-revision-test
  (let [{:keys [conn]} (fixture)
        client (datascript/make-client conn {:cache {:max-entries 16}})
        before (runtime-cache-lifecycle client)
        old-store (:basis-cache-store before)
        old-cache-lifecycle (cache/capture-cache-lifecycle old-store)
        context
        (assoc (late-publication-context (:source-lifecycle before))
               :cache-lifecycle old-cache-lifecycle)
        revision-before (orchestration/cache-content-revision client)
        revision-after-publication (atom nil)
        injected? (atom false)
        original-factory #?(:clj cache/basis-cache-for-option :cljs nil)]
    ;; The first candidate is fully constructed off-side. Before the outer CAS
    ;; can install it, publish into the still-current child. That publication
    ;; must invalidate the rotation CAS so the retry incorporates its revision.
    #?(:clj
       (with-redefs
        [cache/basis-cache-for-option
         (fn
           ([value]
            (original-factory value))
           ([value options]
            (let [candidate (original-factory value options)]
              (when (compare-and-set! injected? false true)
                (cache/resolve-basis!
                 old-store context {:operation :can? :id :before-rotation}
                 (constantly true))
                (reset! revision-after-publication
                        (orchestration/cache-content-revision client)))
              candidate)))]
         (orchestration/clear-answer-cache! client))
       :cljs
       (do
         ;; JavaScript has no parallel publication thread; preserve the same
         ;; revision and detachment assertions around a sequential boundary.
         (cache/resolve-basis!
          old-store context {:operation :can? :id :before-rotation}
          (constantly true))
         (reset! injected? true)
         (reset! revision-after-publication
                 (orchestration/cache-content-revision client))
         (orchestration/clear-answer-cache! client)))
    (let [revision-after-rotation
          (orchestration/cache-content-revision client)]
      (is @injected?)
      (is (> @revision-after-publication revision-before))
      (is (> revision-after-rotation @revision-after-publication))
      ;; A later publication through the detached child remains valid for its
      ;; in-flight owner, but cannot dirty the newly installed outer lifecycle.
      (cache/resolve-basis!
       old-store context {:operation :can? :id :after-rotation}
       (constantly true))
      (is (= revision-after-rotation
             (orchestration/cache-content-revision client))))))

(deftest restore-failure-never-installs-candidate-runtime-test
  (let [{:keys [conn]} (fixture)
        client (datascript/make-client conn {:cache {:max-entries 16}})
        bounds {:max-entries 16}
        snapshot (orchestration/export-cache-snapshot client bounds)
        before (runtime-cache-lifecycle client)
        error
        (error-data
         #(orchestration/restore-cache-snapshot!
           client (assoc snapshot :format :eacl.cache/basis-snapshot-v1)
           bounds))]
    (is (= :eacl/incompatible-cache-snapshot (:type error)))
    (is (identical? before (runtime-cache-lifecycle client)))))

(deftest tampered-completed-answer-restore-fails-off-side-test
  (let [{:keys [conn user account]} (fixture)
        client (datascript/make-client
                conn
                {:cache {:max-entries 64}
                 ;; Keep this snapshot-validation fixture on the portable
                 ;; semantic-answer tier; non-expiring cursors use the faster
                 ;; process-local rendered transport tier instead.
                 :cursor-ttl-seconds 3600})
        base-query
        {:subject user
         :resource/type :account
         :permission :admin}
        _ (eacl/count-resources client base-query)
        _ (eacl/count-resources client (assoc base-query :count-limit 1))
        _ (eacl/read-relationships
           client {:subject/type :user :first 10})
        _ (eacl/expand-permission-tree
           client {:resource account :permission :admin})
        bounds {:max-entries 64}
        snapshot (orchestration/export-cache-snapshot client bounds)
        unbounded-index
        (completed-answer-entry-index
         snapshot :count-resources false)
        bounded-index
        (completed-answer-entry-index
         snapshot :count-resources true)
        page-index
        (completed-answer-entry-index snapshot :read-relationships)
        tree-index
        (completed-answer-entry-index snapshot :expand-permission-tree)
        page-value (get-in snapshot [:entries page-index :value :value])
        tree-value (get-in snapshot [:entries tree-index :value :value])]
    (is (some? unbounded-index))
    (is (some? bounded-index))
    (is (some? page-index))
    (is (some? tree-index))
    (doseq [[label index invalid-value]
            [[:negative-and-missing-contract
              unbounded-index {:count -7}]
             [:unbounded-truncation-field
              unbounded-index
              {:count 1 :limit -1 :truncated? false}]
             [:wrong-unbounded-limit
              unbounded-index {:count 1 :limit 1}]
             [:wrong-bounded-limit
              bounded-index
              {:count 1 :limit 2 :truncated? false}]
             [:truncated-without-reaching-limit
              bounded-index
              {:count 0 :limit 1 :truncated? true}]
             [:bounded-count-exceeds-limit
              bounded-index
              {:count 2 :limit 1 :truncated? false}]
             [:missing-bounded-truncation-field
              bounded-index {:count 1 :limit 1}]
             [:page-flag-is-not-boolean
              page-index
              (assoc-in page-value
                        [:page-info :has-next-page?]
                        :forged)]
             [:page-shape-is-not-closed
              page-index
              (assoc page-value :cached? true)]
             [:tree-shape-is-not-closed
              tree-index
              (assoc tree-value :forged true)]]]
      (testing (name label)
        (let [before (runtime-cache-lifecycle client)
              revision-before
              (orchestration/cache-content-revision client)
              tampered
              (assoc-in snapshot
                        [:entries index :value :value]
                        invalid-value)
              error
              (error-data
               #(orchestration/restore-cache-snapshot!
                 client tampered bounds))]
          (is (= :eacl/incompatible-cache-snapshot (:type error)))
          (is (identical? before (runtime-cache-lifecycle client)))
          (is (= revision-before
                 (orchestration/cache-content-revision client))))))
    (testing "exact answer value basis must agree with its composite key"
      (let [before (runtime-cache-lifecycle client)
            revision-before (orchestration/cache-content-revision client)
            tampered
            (assoc-in snapshot
                      [:entries unbounded-index :value :cache-basis]
                      {:forged-snapshot-id true})
            error
            (error-data
             #(orchestration/restore-cache-snapshot!
               client tampered bounds))]
        (is (= :eacl/incompatible-cache-snapshot (:type error)))
        (is (identical? before (runtime-cache-lifecycle client)))
        (is (= revision-before
               (orchestration/cache-content-revision client)))))))

(deftest successful-restore-installs-one-fresh-complete-runtime-test
  (let [{:keys [conn]} (fixture)
        client
        (datascript/make-client conn {:cache {:max-entries 16}})
        bounds {:max-entries 16}
        snapshot (orchestration/export-cache-snapshot client bounds)
        before (runtime-cache-lifecycle client)
        revision-before (orchestration/cache-content-revision client)]
    (is (= {:restored? true :entry-count 0}
           (orchestration/restore-cache-snapshot! client snapshot bounds)))
    (let [after (runtime-cache-lifecycle client)]
      (is (= (:source-lifecycle before) (:source-lifecycle after)))
      (is (not (identical? (:source-incarnation before)
                           (:source-incarnation after))))
      (doseq [[child old-value] (lifecycle-children before)]
        (is (not (identical? old-value (get after child))) (name child)))
      (is (> (orchestration/cache-content-revision client)
             revision-before)))))

(deftest restored-answers-never-cross-client-expression-limit-policy-test
  (let [{:keys [conn user account]} (fixture)
        permissive
        (datascript/make-client conn {:cache {:max-entries 16}})
        demand {:subject user :permission :admin :resource account}
        _ (is (true? (eacl/can? permissive demand)))
        bounds {:max-entries 16}
        snapshot
        (orchestration/export-cache-snapshot permissive bounds)
        strict
        (datascript/make-client
         conn
         {:cache {:max-entries 16}
          :expression-limits {:maximum-source-nodes 0}})]
    (is (pos? (:entry-count snapshot)))
    (is (= (:entry-count snapshot)
           (:entry-count
            (orchestration/restore-cache-snapshot!
             strict snapshot bounds))))
    (let [failure (error-data #(eacl/can? strict demand))]
      (is (= :eacl.schema/expression-limit (:type failure)))
      (is (= :node-count (:dimension failure)))
      (is (= 0 (:maximum failure)))
      (is (pos? (:actual failure))))))

(deftest restored-answers-never-cross-client-candidate-window-policy-test
  (let [{:keys [conn sparse]} (authorization-scan-fixture)
        permissive
        (datascript/make-client
         conn
         {:cache {:max-entries 32}
          :aggregate-limits {:candidate-window 10}})
        strict
        (datascript/make-client
         conn
         {:cache {:max-entries 32}
          :aggregate-limits {:candidate-window 2}})
        query
        {:resource/type :document
         :resource/relation :candidate
         :authorization
         {:subject sparse :permission :view :on :resource}
         :first 2}
        permissive-page (eacl/read-relationships permissive query)
        strict-cache-free-page
        (eacl/read-relationships strict (assoc query :cache? false))
        bounds {:max-entries 32}
        snapshot
        (orchestration/export-cache-snapshot permissive bounds)]
    (is (= {:resource-ids ["document-0" "document-3"]
            :bounded? false
            :has-next-page? false
            :cached? false}
           (authorization-scan-summary permissive-page)))
    (is (= {:resource-ids ["document-0"]
            :bounded? true
            :has-next-page? true
            :cached? false}
           (authorization-scan-summary strict-cache-free-page)))
    (is (pos? (:entry-count snapshot)))
    (is (= (:entry-count snapshot)
           (:entry-count
            (orchestration/restore-cache-snapshot!
             strict snapshot bounds))))
    ;; The normalized client default is part of the answer key. Restored
    ;; mappings computed with a larger candidate window cannot alias this
    ;; strict client's shorter, bounded page.
    (is (= (authorization-scan-summary strict-cache-free-page)
           (authorization-scan-summary
            (eacl/read-relationships strict query))))))

(deftest restore-validates-once-across-same-lineage-cas-loss-test
  (let [{:keys [conn]} (fixture)
        client (datascript/make-client conn {:cache {:max-entries 16}})
        bounds {:max-entries 16}
        snapshot (orchestration/export-cache-snapshot client bounds)
        before (runtime-cache-lifecycle client)
        restore-calls (atom 0)
        intervening (atom nil)
        original-restore cache/restore-basis-snapshot!
        result
        (with-redefs
         [cache/restore-basis-snapshot!
          (fn [store candidate-snapshot candidate-bounds]
            (swap! restore-calls inc)
            (let [result
                  (original-restore
                   store candidate-snapshot candidate-bounds)]
              (orchestration/clear-answer-cache! client)
              (reset! intervening (runtime-cache-lifecycle client))
              result))]
          (orchestration/restore-cache-snapshot! client snapshot bounds))
        after (runtime-cache-lifecycle client)]
    (is (= {:restored? true :entry-count 0} result))
    (is (= 1 @restore-calls))
    (is (not (identical? before @intervening)))
    (is (not (identical? @intervening after)))
    (is (= (:source-lifecycle before) (:source-lifecycle after)))))

(deftest telemetry-disabled-lifecycle-operations-do-not-mutate-observers-test
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn {:cache {:max-entries 4 :telemetry? false}})
        bounds {:max-entries 4}
        snapshot (orchestration/export-cache-snapshot client bounds)
        runtime-metrics
        (get (:runtime client)
             ::orchestration/runtime-cache-lifecycle-metrics)
        observer-mutations (atom 0)
        watch-key ::telemetry-disabled]
    (add-watch runtime-metrics watch-key
               (fn [& _] (swap! observer-mutations inc)))
    (try
      (orchestration/clear-answer-cache! client)
      (orchestration/restore-cache-snapshot! client snapshot bounds)
      (datascript/expire-cache! client "telemetry-disabled-lifecycle")
      (is (zero? @observer-mutations)
          "disabled telemetry performs no cumulative observer mutation")
      (let [stats (datascript/cache-stats client)]
        (is (false? (:telemetry-enabled? stats)))
        (is (zero? (:expirations stats)))
        (is (zero? (:restores stats))))
      (finally
        (remove-watch runtime-metrics watch-key)))))

(deftest lifecycle-accounting-never-serializes-resident-keys-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {:cache {:max-entries 4}})
        bounds {:max-entries 4}
        empty-snapshot (orchestration/export-cache-snapshot client bounds)
        oversized-id (str/join (repeat (inc (* 1024 1024)) "x"))
        publish!
        (fn []
          (let [lifecycle (runtime-cache-lifecycle client)
                store (:basis-cache-store lifecycle)
                result
                (cache/resolve-basis!
                 store
                 (late-publication-context (:source-lifecycle lifecycle))
                 {:operation :can? :id oversized-id}
                 (constantly true))]
            (is (true? (:value result)))
            (is (= 1 (:entries (cache/basis-cache-stats store))))))]
    (publish!)
    (is (map? (datascript/cache-stats client)))
    (let [large-snapshot
          (orchestration/export-cache-snapshot client bounds)]
      (is (= 1 (:entry-count large-snapshot)))
      (is (nil? (orchestration/clear-answer-cache! client)))
      (is (= {:restored? true :entry-count 1}
             (orchestration/restore-cache-snapshot!
              client large-snapshot bounds)))
      (is (= 1 (:entries
                (cache/basis-cache-stats
                 (:basis-cache-store
                  (runtime-cache-lifecycle client)))))))
    ;; Restoring another snapshot must also account for a detached store whose
    ;; resident key is larger than the canonical codec's ordinary token bound.
    (is (= {:restored? true :entry-count 0}
           (orchestration/restore-cache-snapshot!
            client empty-snapshot bounds)))
    (publish!)
    ;; Full expiry has the same detached-store accounting obligation.
    (is (nil? (datascript/expire-cache!
               client "oversized-key-lifecycle-expiry")))
    (publish!)
    (is (nil? (orchestration/clear-answer-cache! client)))
    (is (zero? (:entries
                (cache/basis-cache-stats
                 (:basis-cache-store
                  (runtime-cache-lifecycle client))))))))

(deftest narrow-clear-preserves-sticky-proof-health-test
  (testing "disablement and per-reason reporting survive narrow clear"
    (let [conn (datascript/create-conn)
          reports (atom [])
          client
          (datascript/make-client
           conn {:cache {:max-entries 4}
                 :proof-contract-reporter #(swap! reports conj %)})
          diagnostic
          {:status :contract-violation :reason :malformed-frame}
          before-store
          (:basis-cache-store (runtime-cache-lifecycle client))]
      (cache/record-proof-diagnostic! before-store diagnostic)
      (orchestration/clear-answer-cache! client)
      (let [after-store
            (:basis-cache-store (runtime-cache-lifecycle client))]
        (is (not (identical? before-store after-store)))
        (is (identical? (:managed-lifting-disabled? before-store)
                        (:managed-lifting-disabled? after-store)))
        (is (identical? (:reported-contract-violations before-store)
                        (:reported-contract-violations after-store)))
        (is (true? (:managed-lifting-disabled?
                    (cache/basis-cache-stats after-store))))
        (cache/record-proof-diagnostic! after-store diagnostic)
        (is (= [diagnostic] @reports)))
      (datascript/expire-cache! client "proof-health-full-expiry")
      (is (false? (:managed-lifting-disabled?
                   (cache/basis-cache-stats
                    (:basis-cache-store
                     (runtime-cache-lifecycle client)))))
          "full expiry begins a fresh proof-health lifecycle")))
  (testing "a detached in-flight violation disables the installed store"
    (let [conn (datascript/create-conn)
          reports (atom [])
          client
          (datascript/make-client
           conn {:cache {:max-entries 4}
                 :proof-contract-reporter #(swap! reports conj %)})
          diagnostic
          {:status :contract-violation :reason :future-generation}
          captured-store
          (:basis-cache-store (runtime-cache-lifecycle client))]
      (orchestration/clear-answer-cache! client)
      (let [installed-store
            (:basis-cache-store (runtime-cache-lifecycle client))]
        (is (false? (:managed-lifting-disabled?
                     (cache/basis-cache-stats installed-store))))
        ;; Models an in-flight request that captured the detached store before
        ;; clear and finishes proof validation after the replacement CAS.
        (cache/record-proof-diagnostic! captured-store diagnostic)
        (is (true? (:managed-lifting-disabled?
                    (cache/basis-cache-stats installed-store))))
        (cache/record-proof-diagnostic! installed-store diagnostic)
        (is (= [diagnostic] @reports))))))

(deftest restore-loses-to-concurrent-source-rotation-test
  (let [{:keys [conn]} (fixture)
        client (datascript/make-client conn {:cache {:max-entries 16}})
        bounds {:max-entries 16}
        snapshot (orchestration/export-cache-snapshot client bounds)
        restore-calls (atom 0)
        winner (atom nil)
        source-lifecycle
        (:source-lifecycle (runtime-cache-lifecycle client))
        original-restore cache/restore-basis-snapshot!
        error
        (with-redefs
         [cache/restore-basis-snapshot!
          (fn [store candidate-snapshot candidate-bounds]
            (swap! restore-calls inc)
            (let [result
                  (original-restore
                   store candidate-snapshot candidate-bounds)]
              (datascript/expire-cache! client source-lifecycle)
              (reset! winner (runtime-cache-lifecycle client))
              result))]
          (error-data
           #(orchestration/restore-cache-snapshot!
             client snapshot bounds)))]
    (is (= :eacl/cache-restore-lifecycle-conflict (:type error)))
    (is (= 1 @restore-calls))
    (is (identical? @winner (runtime-cache-lifecycle client)))
    (is (= source-lifecycle
           (:source-lifecycle (runtime-cache-lifecycle client))))))

(deftest cursor-consumption-dispatches-on-snapshot-target-test
  (let [{:keys [conn client user]} (fixture)
        account-2 (eacl/spice-object :account "account-2")
        _ (ds/transact! conn [{:eacl/id "account-2"}])
        _ (eacl/create-relationship!
           client (eacl/->Relationship user :owner account-2))
        old-snapshot (eacl/snapshot client)]
    (try
      (let [query {:subject/type :user :first 1}
            first-page (eacl/read-relationships old-snapshot query)
            cursor (get-in first-page [:page-info :end-cursor])
            _ (ds/transact! conn [{:eacl/id "unrelated-advance"}])
            new-snapshot (eacl/snapshot client)
            source-ops (atom {})]
        (try
          (is (string? cursor))
          (is (true? (get-in first-page [:page-info :has-next-page?])))
          (binding [source/*source-op-stats* source-ops]
            (let [same-page
                  (eacl/read-relationships
                   old-snapshot (assoc query :after cursor))
                  conflict
                  (try
                    (eacl/read-relationships
                     new-snapshot (assoc query :after cursor))
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core.ExceptionInfo)
                           error
                      (ex-data error)))]
              (is (= 1 (count (:data same-page))))
              (is (= :eacl.consistency/basis-conflict (:type conflict)))
              (is (= (:type conflict) (:eacl/error conflict)))
              (is (= :cursor (:source conflict)))))
          (is (empty? @source-ops)
              "neither same- nor different-Snapshot continuation may acquire")
          (finally
            (eacl/release! new-snapshot))))
      (finally
        (eacl/release! old-snapshot)))))

(defn- retained-snapshot-cursor-consistency-result
  [request-consistency]
  (let [{:keys [conn client user]} (fixture)
        account-2 (eacl/spice-object :account "consistency-account-2")
        _ (ds/transact! conn [{:eacl/id (:id account-2)}])
        _ (eacl/create-relationship!
           client (eacl/->Relationship user :owner account-2))
        first-snapshot (eacl/snapshot client)]
    (try
      (let [query {:subject user
                   :permission :admin
                   :resource/type :account
                   :first 1
                   :cache? false}
            first-token (eacl/basis-token first-snapshot)
            first-page
            (eacl/lookup-resources
             first-snapshot
             (assoc query :consistency
                    (request-consistency first-token)))
            cursor (get-in first-page [:page-info :end-cursor])
            _ (ds/transact! conn [{:eacl/id "consistency-unrelated"}])
            second-snapshot (eacl/snapshot client)]
        (try
          (let [second-token (eacl/basis-token second-snapshot)
                error
                (try
                  (eacl/lookup-resources
                   second-snapshot
                   (assoc query
                          :after cursor
                          :consistency
                          (request-consistency second-token)))
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo
                            :cljs cljs.core.ExceptionInfo) thrown
                    (ex-data thrown)))]
            {:cursor cursor
             :has-next-page?
             (get-in first-page [:page-info :has-next-page?])
             :error error})
          (finally
            (eacl/release! second-snapshot))))
      (finally
        (eacl/release! first-snapshot)))))

(deftest retained-snapshot-at-least-floor-governs-cursor-reuse-test
  (let [{:keys [cursor has-next-page? error]}
        (retained-snapshot-cursor-consistency-result
         consistency/at-least-as-fresh)]
    (is (string? cursor))
    (is (true? has-next-page?))
    (is (= :eacl.consistency/cursor-consistency-conflict (:type error)))
    (is (= (:type error) (:eacl/error error)))))

(deftest retained-snapshot-exact-request-governs-cursor-reuse-test
  (let [{:keys [cursor has-next-page? error]}
        (retained-snapshot-cursor-consistency-result
         consistency/at-exact-snapshot)]
    (is (string? cursor))
    (is (true? has-next-page?))
    (is (= :eacl.consistency/cursor-consistency-conflict (:type error)))
    (is (= (:type error) (:eacl/error error)))))

(deftest transient-acl-at-least-token-is-authenticated-once-test
  (let [{:keys [conn user]} (fixture)
        client (datascript/make-client conn {:cache {:max-entries 16}})
        selected (eacl/snapshot client)
        token (try
                (eacl/basis-token selected)
                (finally
                  (eacl/release! selected)))
        request {:subject user
                 :permission :admin
                 :resource/type :account
                 :consistency (consistency/at-least-as-fresh token)}
        _ (eacl/count-resources client request)
        calls (atom 0)
        original-decode (volatile! secure/decode-authenticated)
        hit
        (with-redefs [secure/decode-authenticated
                      (fn [options token]
                        (swap! calls inc)
                        (@original-decode options token))]
          (eacl/count-resources client request))]
    (is (= 1 (:count hit)))
    (is (true? (:cached? hit)))
    (is (= 1 @calls)
        "ACL selection authenticates once; delegated Snapshot reads reuse it")))

(deftest retained-snapshot-authenticates-every-read-token-test
  (let [{:keys [client user]} (fixture)
        selected (eacl/snapshot client)
        token (eacl/basis-token selected)
        request {:subject user
                 :permission :admin
                 :resource/type :account
                 :cache? false
                 :consistency (consistency/at-least-as-fresh token)}
        tampered-token
        (str (subs token 0 (dec (count token)))
             (if (= "A" (subs token (dec (count token)))) "B" "A"))
        calls (atom 0)
        original-decode (volatile! secure/decode-authenticated)]
    (try
      (with-redefs [secure/decode-authenticated
                    (fn [options token]
                      (swap! calls inc)
                      (@original-decode options token))]
        (is (= 1 (:count (eacl/count-resources selected request))))
        (is (= 1 (:count (eacl/count-resources selected request))))
        (is (= :eacl/invalid-zed-token
               (:type
                (error-data
                 #(eacl/count-resources
                   selected
                   (assoc request
                          :consistency
                          (consistency/at-least-as-fresh tampered-token)))))))
        (is (= 3 @calls)
            "retained Snapshots independently authenticate every raw token"))
      (finally
        (eacl/release! selected)))))

(deftest selected-snapshot-releases-on-public-validation-error-test
  (let [{:keys [conn client user account]} (fixture)
        error (atom nil)
        {:keys [provider-calls db-calls]}
        (observed-call
         conn
         #(try
            (eacl/can? client user :missing-permission account)
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo)
                   failure
              (reset! error (ex-data failure)))))]
    (is (keyword? (:type @error)))
    (is (= 1 (:acquire-current! provider-calls 0)))
    (is (= 1 (:release! provider-calls 0)))
    (is (= 1 db-calls))))

(deftest selected-snapshot-releases-when-context-construction-fails-test
  (let [{:keys [conn client user account]} (fixture)
        error (atom nil)
        {:keys [provider-calls db-calls]}
        (with-redefs
         [execution/check!
          (fn
            ([stage]
             (when (= :consistency-selected stage)
               (throw
                (ex-info "injected context failure"
                         {:type :test/context-construction}))))
            ([contract stage]
             (if (= :consistency-selected stage)
               (throw
                (ex-info "injected context failure"
                         {:type :test/context-construction}))
               nil))
            ([contract stage consumed-work]
             (if (= :consistency-selected stage)
               (throw
                (ex-info "injected context failure"
                         {:type :test/context-construction}))
               nil)))]
          (observed-call
           conn
           #(try
              (eacl/can? client user :admin account)
              (catch #?(:clj clojure.lang.ExceptionInfo
                        :cljs cljs.core.ExceptionInfo)
                     failure
                (reset! error (ex-data failure))))))]
    (is (= :test/context-construction (:type @error)))
    (is (= 1 (:acquire-current! provider-calls 0)))
    (is (= 1 (:release! provider-calls 0)))
    (is (= 1 db-calls))))

(defn- observed-failure
  [conn f]
  (let [failure (atom nil)
        observation
        (observed-call
         conn
         #(try
            (f)
            (catch #?(:clj Throwable :cljs :default) error
              (reset! failure error))))]
    (assoc observation :failure @failure)))

(defn- failure-message
  [failure]
  #?(:clj (.getMessage ^Throwable failure)
     :cljs (.-message failure)))

(defn- fail-execution-stage
  [target-stage error]
  (fn
    ([stage]
     (if (= target-stage stage)
       (throw error)
       execution/*contract*))
    ([contract stage]
     (if (= target-stage stage)
       (throw error)
       contract))
    ([contract stage consumed-work]
     (if (= target-stage stage)
       (throw error)
       contract))))

(deftest selected-snapshot-releases-across-post-selection-fault-boundaries-test
  (let [{:keys [conn client user account]} (fixture)
        assert-one-release!
        (fn [{:keys [failure provider-calls db-calls]} expected-message]
          (is (= expected-message (failure-message failure)))
          (is (= 1 (:acquire-current! provider-calls 0)))
          (is (= 1 (:release! provider-calls 0)))
          (is (= 1 db-calls)))]
    (testing "backend evaluation failures release the selected snapshot"
      (let [error (ex-info "injected foreign failure"
                           {:type :test/foreign-failure})]
        (with-redefs
         [engine/can? (fn [& _] (throw error))]
          (assert-one-release!
           (observed-failure
            conn #(eacl/can? client user :admin account))
           "injected foreign failure"))))
    (testing "post-selection cancellation releases the selected snapshot"
      (let [token (eacl/cancellation-token)]
        (with-redefs
         [execution/check!
         (fail-execution-stage
           :consistency-selected
           (ex-info
            "EACL authorization execution was cancelled."
            {:type :eacl.execution/cancelled
             :eacl/error :eacl.execution/cancelled}))]
          (assert-one-release!
           (observed-failure
            conn
            #(eacl/can?
              client {:subject user
                      :permission :admin
                      :resource account
                      :cancellation-token token}))
           "EACL authorization execution was cancelled."))))
    (testing "proof failures release the selected snapshot"
      (let [proof-client (datascript/make-client conn {:cache {}})]
        (with-redefs
         [proof-frame/resolve!
          (fn [& _]
            (throw (ex-info "injected proof failure"
                            {:type :test/proof-failure})))]
          (let [{:keys [value provider-calls db-calls]}
                (observed-call
                 conn #(eacl/can? proof-client user :admin account))]
            (is (true? value)
                "unavailable proof falls back to exact evaluation")
            (is (= 1 (:acquire-current! provider-calls 0)))
            (is (= 1 (:release! provider-calls 0)))
            (is (= 1 db-calls))))))
    (testing "cache-publication failures release the selected snapshot"
      (let [cached-client
            (datascript/make-client conn {:cache {}})]
        (with-redefs
         [execution/check!
         (fail-execution-stage
           :cache-publication
           (ex-info "injected cache publication failure"
                    {:type :test/cache-publication-failure}))]
          (assert-one-release!
           (observed-failure
            conn #(eacl/can? cached-client user :admin account))
           "injected cache publication failure"))))
    (testing "cursor-encoding failures release the selected snapshot"
      (with-redefs
       [execution/check!
        (fail-execution-stage
         :cursor-encode
         (ex-info "injected cursor encoding failure"
                  {:type :test/cursor-encoding-failure}))]
        (assert-one-release!
         (observed-failure
          conn
          #(eacl/read-relationships
            client {:subject/type :user :first 1}))
         "injected cursor encoding failure")))))

(deftest write-planning-snapshot-releases-before-commit-test
  (let [{:keys [conn client user account]} (fixture)
        provider-calls (atom {})
        release-count-at-commit (atom nil)
        original-transact! ds/transact!
        response
        (with-redefs [ds/transact!
                      (fn [candidate tx]
                        (reset! release-count-at-commit
                                (:release! @provider-calls 0))
                        (original-transact! candidate tx))]
          (binding [source/*source-op-stats* provider-calls]
            (eacl/delete-relationship!
             client (eacl/->Relationship user :owner account))))]
    (is (= 1 @release-count-at-commit))
    (is (= 1 (:acquire-current! @provider-calls 0)))
    (is (= 1 (:release! @provider-calls 0)))
    (is (string? (:zed/token response)))))
