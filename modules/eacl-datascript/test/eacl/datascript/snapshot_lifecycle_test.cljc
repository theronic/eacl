(ns eacl.datascript.snapshot-lifecycle-test
  #?(:cljs (:require-macros [eacl.core :as eacl]))
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [clojure.set :as set]
            [datascript.core :as ds]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.backend.writer :as writer]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine]
            [eacl.execution :as execution]
            [eacl.proof-frame :as proof-frame]
            [eacl.request.counters :as request-counters]
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
    (is (zero? (:db-calls construction)))
    (is (empty? (:provider-calls construction)))
    (is (= :eacl/unsupported-capability
           (:type (ex-data (:failure failure)))))
    (is (= :write (:capability (ex-data (:failure failure)))))
    (is (zero? (:db-calls failure)))
    (is (empty? (:provider-calls failure)))))

(deftest direct-snapshot-admission-and-zero-acquisition-test
  (let [{:keys [conn client user account]} (fixture)
        native-db (ds/db conn)
        ledger (request-counters/make-ledger)
        {:keys [value provider-calls db-calls]}
        (binding [request-counters/*ledger* ledger]
          (observed-call
           conn
           #(let [snapshot (datascript/snapshot client native-db)]
              (try
                {:snapshot snapshot
                 :native-db (datascript/db snapshot)
                 :basis (eacl/basis snapshot)
                 :allowed? (eacl/can? snapshot user :admin account)}
                (finally
                  (eacl/release! snapshot))))))
        counts (request-counters/snapshot ledger)]
    (is (eacl/snapshot? (:snapshot value)))
    (is (identical? native-db (:native-db value)))
    (is (= :ordinary (get-in value [:basis :kind])))
    (is (true? (:allowed? value)))
    (is (zero? db-calls))
    (is (zero? (:acquisitions counts)))
    (is (= 1 (:context-constructions counts)))
    (is (= {:source-scope 1} provider-calls))))

(deftest direct-snapshot-refuses-inadmissible-and-foreign-values-first-test
  (let [{:keys [conn client]} (fixture)
        other-conn (datascript/create-conn)
        cases
        [[:filtered
          (ds/filter (ds/db conn) (fn [_ _] true))]
         [:foreign-source (ds/db other-conn)]
         [:foreign-backend {}]]]
    (doseq [[expected-kind value] cases]
      (let [ledger (request-counters/make-ledger)
            data
            (binding [request-counters/*ledger* ledger]
              (try
                (datascript/snapshot client value)
                nil
                (catch #?(:clj clojure.lang.ExceptionInfo
                          :cljs cljs.core.ExceptionInfo)
                       error
                  (ex-data error))))
            counts (request-counters/snapshot ledger)]
        (is (= :eacl/unsupported-database-value (:type data)))
        (is (= (:type data) (:eacl/error data)))
        (is (= expected-kind (:basis-kind data)))
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
          #{:conn :source :writer :selected-snapshot
            :acquire-current! :acquire-authoritative!
            :acquire-at-least! :acquire-exact!}
          (set (filter keyword? reachable)))))
    (is (not-any?
         (fn [value]
           (some #(identical? value %) forbidden-identities))
         reachable))
    (is (= #{:backend-id :schema :impl} (set (keys snapshot-api))))
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
