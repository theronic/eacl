(ns eacl.datascript.snapshot-lifecycle-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.backend.snapshot-provider :as snapshot-provider]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.cursor :as cursor]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine]
            [eacl.execution :as execution]
            [eacl.proof-frame :as proof-frame]
            [eacl.request.counters :as request-counters]))

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
          (binding [snapshot-provider/*provider-op-stats* provider-calls]
            (f)))]
    {:value value
     :provider-calls @provider-calls
     :db-calls @db-calls}))

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

(deftest composed-snapshot-reuses-one-request-context-test
  (let [{:keys [conn client user account]} (fixture)
        cached-client (datascript/make-client conn {:cache {}})
        ledger (request-counters/make-ledger)
        {:keys [value provider-calls db-calls]}
        (binding [request-counters/*ledger* ledger]
          (observed-call
           conn
           #(eacl/with-snapshot
             cached-client
             (fn [view]
               {:uncached-decision
                (eacl/check-permission
                 view {:subject user
                       :permission :admin
                       :resource account
                       :cache? false})
                :cached-decisions
                (mapv
                 (fn [_]
                   (eacl/check-permission
                    view {:subject user
                          :permission :admin
                          :resource account}))
                 (range 2))
                :count
                (eacl/count-resources
                 view {:subject user
                       :resource/type :account
                       :permission :admin
                       :cache? false})}))))
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
    (is (= {:public-entries 1
            :acquisitions 1
            :context-constructions 1
            :releases 1}
           (select-keys counts
                        [:public-entries :acquisitions
                         :context-constructions :releases])))))

(deftest composed-snapshot-nested-read-cannot-renew-deadline-test
  (let [{:keys [conn client user account]} (fixture)
        now (atom 0)
        ledger (request-counters/make-ledger)
        {:keys [value provider-calls]}
        (binding [execution/*monotonic-nanos* #(deref now)
                  request-counters/*ledger* ledger]
          (observed-call
           conn
           #(eacl/with-snapshot
             client nil {:timeout-ms 10}
             (fn [view]
               (reset! now 11000000)
               (try
                 (eacl/can? view user :admin account)
                 nil
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                     error
                   (ex-data error)))))))
        counts (request-counters/snapshot ledger)]
    (is (= :eacl.execution/deadline-exceeded (:type value)))
    (is (= 1 (:acquire-current! provider-calls 0)))
    (is (= 1 (:release! provider-calls 0)))
    (is (= 1 (:context-constructions counts)))
    (is (= 1 (:releases counts)))))

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
    (testing "cursor-construction failures release the selected snapshot"
      (with-redefs
       [cursor/cursor->token
        (fn [& _]
          (throw (ex-info "injected cursor construction failure"
                          {:type :test/cursor-construction-failure})))]
        (assert-one-release!
         (observed-failure
          conn
          #(eacl/read-relationships
            client {:subject/type :user :first 1}))
         "injected cursor construction failure")))))

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
          (binding [snapshot-provider/*provider-op-stats* provider-calls]
            (eacl/delete-relationship!
             client (eacl/->Relationship user :owner account))))]
    (is (= 1 @release-count-at-commit))
    (is (= 1 (:acquire-current! @provider-calls 0)))
    (is (= 1 (:release! @provider-calls 0)))
    (is (string? (:zed/token response)))))
