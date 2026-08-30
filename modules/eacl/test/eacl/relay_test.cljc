(ns eacl.relay-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
            :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.backend.source :as source]
            [eacl.core :as eacl]
            [eacl.cursor :as cursor]
            [eacl.execution :as execution]
            [eacl.proof-frame :as proof-frame]
            [eacl.relay :as relay]
            [eacl.request.context :as request-context]
            [eacl.verified-kernel :as verified]))

(defn- operation-map
  [snapshot-id proof]
  (merge
   (into {}
         (map (fn [operation]
                [operation (fn [& _] nil)]))
         backend/required-snapshot-operations)
   {:snapshot-id (constantly {:revision snapshot-id})
    :basis-kind (constantly :ordinary)
    :native-revision
    (constantly
     {:revision snapshot-id
      :exact-locator snapshot-id})
    :order-hint (constantly snapshot-id)
    :exact-locator (constantly snapshot-id)
    :object-id->internal identity
    :internal-id->object identity}))

(defn- adapter
  [snapshot-id proof deterministic?]
  (backend/make-adapter
   {:id :relay-test
    :capabilities {}
    :fingerprint {:adapter :relay-test}
    :deterministic? deterministic?
    :operations (operation-map snapshot-id proof)}))

(defn- basis-identity
  [revision]
  {:backend :relay-test
   :source-id "relay-source"
   :branch nil
   :source-lifecycle "relay-lifecycle"
   :basis-kind :ordinary
   :revision revision
   :exact-locator revision
   :backend-snapshot-id {:revision revision}})

(defn- proof-adapter
  [revision provider]
  (backend/make-adapter
   {:id :relay-test
    :capabilities {:cache-proofs #{:ordered-generations}}
    :fingerprint {:adapter :relay-test}
    :deterministic? true
    :operations
    (merge
     (operation-map revision nil)
     {:schema-generation (constantly 3)
      :proof-frame provider})}))

(defn- proof-opts
  [selected revision]
  (let [identity (basis-identity revision)]
    {:snapshot-semantic-identity identity
     :request-lineage (request-context/lineage-for-basis identity)
     :cursor-dependency-relation-ids (delay [1])
     :request-proof-frame
     (proof-frame/request-frame selected {:basis-identity identity})}))

(declare lookup-query lookup-page)

(deftest cursor-frame-identity-test
  (testing "even equal dependency proofs cannot lift across revisions"
    (let [first-context
          (relay/dependency-context
           (adapter 1 {:digest "same"} true))
          later-context
          (relay/dependency-context
           (adapter 2 {:digest "same"} true))]
      (is (not= (:frame first-context)
                (:frame later-context)))))

  (testing "the same exact snapshot has a stable identity"
    (let [first-context
          (relay/dependency-context (adapter 1 nil true))
          later-context
          (relay/dependency-context (adapter 1 {:different "proof"} true))]
      (is (= (:frame first-context)
             (:frame later-context)))))

  (testing "adapter determinism cannot enable cross-revision lifting"
    (let [first-context
          (relay/dependency-context
           (adapter 1 {:digest "same"} false))
          later-context
          (relay/dependency-context
           (adapter 2 {:digest "same"} false))]
      (is (not= (:frame first-context)
                (:frame later-context))))))

(deftest cursor-envelope-carries-canonical-frame-identity-test
  (let [selected (proof-adapter 10 (constantly [[1 5]]))
        page
        (relay/externalize-page
         selected (proof-opts selected 10)
         :lookup-resources lookup-query lookup-page)
        envelope
        (cursor/token->cursor
         (get-in page [:page-info :end-cursor]))]
    (is (= 13 (:v envelope)))
    (is (= (request-context/lineage-for-basis (basis-identity 10))
           (:lineage envelope)))
    (is (= {:schema-generation 3
            :dependency-identity [[1 5]]
            :dependency-stamp 5}
           (:frame envelope)))
    (is (string? (:closure-digest envelope)))
    (is (not (contains? envelope :dependency-scope-digest)))
    (is (not (contains? envelope :proof-digest)))))

(deftest request-lineage-must-match-selected-basis-test
  (let [selected (proof-adapter 10 (constantly [[1 5]]))
        error
        (try
          (relay/externalize-page
           selected
           (assoc-in (proof-opts selected 10)
                     [:request-lineage :source-lifecycle]
                     "another-lifecycle")
           :lookup-resources lookup-query lookup-page)
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo
                    :cljs cljs.core.ExceptionInfo) thrown
            thrown))]
    (is (= :eacl/backend-contract-violation
           (:type (ex-data error))))))

(deftest exact-fallback-accepts-and-remints-by-identity-without-frame-read-test
  (let [original (proof-adapter 10 (constantly [[1 5]]))
        current (proof-adapter 11 (constantly [[1 6]]))
        exact-frame-reads (atom 0)
        exact
        (proof-adapter
         10
         (fn [_]
           (swap! exact-frame-reads inc)
           (throw (ex-info "historical frame must not be read" {}))))
        acquisition
        (fn [adapter]
          {:adapter adapter
           :ownership :borrowed
           :release-token nil})
        history-source
        (source/make-source
         {:id :relay-test
          :capabilities {:snapshots #{:exact}
                         :cache-proofs #{:ordered-generations}}
          :basis-ownership :borrowed
          :operations
          {:source-scope
           (constantly {:source-id "relay-source" :branch nil})
           :source-lifecycle (constantly "relay-lifecycle")
           :acquire-current! (fn [] (acquisition current))
           :acquire-authoritative! (fn [] (acquisition current))
           :acquire-at-least! (fn [& _] (acquisition current))
           :acquire-exact!
           (fn [revision _timeout-ms]
             (when-not (= {:revision 10 :exact-locator 10} revision)
               (throw (ex-info "wrong exact revision" {:revision revision})))
             (acquisition exact))
           :release! (fn [_] nil)}})
        mint-opts (proof-opts original 10)
        first-page
        (relay/externalize-page
         original mint-opts :lookup-resources lookup-query lookup-page)
        token (get-in first-page [:page-info :end-cursor])
        current-opts
        (assoc (proof-opts current 11)
               :authorization-target-kind :acl)
        prepared
        (binding [relay/*acl-cursor-recovery-source* history-source]
          (relay/prepare-page-query
           current current-opts :lookup-resources
           (assoc lookup-query :after token)))
        reminted
        (relay/externalize-page
         (:adapter prepared)
         (assoc current-opts
                :snapshot-semantic-identity (basis-identity 10)
                :cursor-dependency-context
                (:continuation-context prepared))
         :lookup-resources lookup-query lookup-page)
        original-envelope (cursor/token->cursor token)
        reminted-envelope
        (cursor/token->cursor
         (get-in reminted [:page-info :end-cursor]))]
    (is (identical? exact (:adapter prepared)))
    (is (zero? @exact-frame-reads))
    (is (= (select-keys original-envelope
                        [:lineage :native-revision :adapter-fingerprint
                         :identity-contract :frame :closure-digest])
           (select-keys reminted-envelope
                        [:lineage :native-revision :adapter-fingerprint
                         :identity-contract :frame :closure-digest])))))

(deftest proof-equivalent-continuation-reuses-one-request-frame-for-remint-test
  (let [original (proof-adapter 10 (constantly [[1 5]]))
        frame-reads (atom 0)
        current
        (proof-adapter
         11
         (fn [relation-ids]
           (swap! frame-reads inc)
           (mapv (fn [relation-id] [relation-id 5]) relation-ids)))
        first-page
        (relay/externalize-page
         original (proof-opts original 10)
         :lookup-resources lookup-query lookup-page)
        prepared
        (relay/prepare-page-query
         current (proof-opts current 11)
         :lookup-resources
         (assoc lookup-query
                :after (get-in first-page [:page-info :end-cursor])))
        reminted
        (relay/externalize-page
         (:adapter prepared)
         (assoc (proof-opts current 11)
                :cursor-dependency-context
                (:continuation-context prepared))
         :lookup-resources lookup-query lookup-page)
        envelope
        (cursor/token->cursor
         (get-in reminted [:page-info :end-cursor]))]
    (is (identical? current (:adapter prepared)))
    (is (= 1 @frame-reads)
        "cursor validation and re-minting share one closure resolution")
    (is (= {:revision 11 :exact-locator 11}
           (:native-revision envelope)))
    (is (= {:schema-generation 3
            :dependency-identity [[1 5]]
            :dependency-stamp 5}
           (:frame envelope)))))

(deftest contract-violating-cursor-proof-falls-back-exact-and-cannot-equal-test
  (let [producer (proof-adapter 10 (constantly [[1 5]]))
        invalid-at-10-adapter
        (proof-adapter 10 (constantly [[1 11]]))
        consumer (proof-adapter 11 (constantly [[1 12]]))
        query lookup-query
        first-page
        (relay/externalize-page
         producer (proof-opts producer 10)
         :lookup-resources query lookup-page)
        continuation-query
        (assoc query :after (get-in first-page [:page-info :end-cursor]))
        error
        (try
          (relay/internalize-page-query
           consumer (proof-opts consumer 11)
           :lookup-resources continuation-query)
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) cause
            (ex-data cause)))
        invalid-at-10
        (relay/dependency-context
         invalid-at-10-adapter [1])
        invalid-at-11
        (relay/dependency-context consumer [1])
        exact-at-10 (relay/dependency-context invalid-at-10-adapter)
        exact-at-11 (relay/dependency-context consumer)]
    (is (= :eacl.pagination/stale-cursor (:type error)))
    (is (= :frame-changed (:reason error)))
    (is (= (:closure-digest exact-at-10)
           (:closure-digest exact-at-11))
        "exact-bound cursors use one distinguished empty closure digest")
    (is (= (:closure-digest invalid-at-10)
           (:closure-digest invalid-at-11))
        "violated evidence is represented only by exact-snapshot fallback")
    (is (not= (:frame invalid-at-10)
              (:frame invalid-at-11))
        "equal violation status across revisions is never equality evidence")))

(def lookup-query
  {:subject {:type :user :id "user-1"}
   :permission :view
   :resource/type :document
   :first 1})

(def lookup-page
  {:data [{:type :document :id "document-1"}]
   :page-info
   {:start-cursor
    {:kind :lookup-eid
     :frontier-direction :asc
     :result-eid "document-1"}
    :end-cursor
    {:kind :lookup-eid
     :frontier-direction :asc
     :result-eid "document-1"}
    :has-next-page? true
    :has-previous-page? false}})

(deftest page-externalization-builds-one-snapshot-context-test
  ;; Every context build reads :native-revision exactly once, so the op count is
  ;; the context-build count — portable across CLJ and CLJS, unlike
  ;; re-rooting a multi-arity var.
  (let [native-revision-calls (atom 0)
        snapshot
        (backend/make-adapter
         {:id :relay-test
          :capabilities {}
          :fingerprint {:adapter :relay-test}
          :deterministic? true
          :operations
          (merge
           (operation-map 1 nil)
           {:native-revision
            (fn []
              (swap! native-revision-calls inc)
              {:revision 1
               :exact-locator 1})})})
        page
        (relay/externalize-page
         snapshot {} :lookup-resources lookup-query lookup-page)]
    (is (string? (get-in page [:page-info :start-cursor])))
    (is (string? (get-in page [:page-info :end-cursor])))
    (is (= 1 @native-revision-calls)
        "both boundary tokens must reuse one immutable snapshot proof")))

(deftest prepared-continuation-authenticates-token-once-test
  (let [snapshot (adapter 1 nil true)
        first-page
        (relay/externalize-page
         snapshot {} :lookup-resources lookup-query lookup-page)
        token (get-in first-page [:page-info :end-cursor])
        continuation-query (assoc lookup-query :after token)
        work (atom {})]
    (binding [cursor/*codec-work* work]
      (let [{:keys [adapter query]}
            (relay/prepare-page-query
             snapshot {} :lookup-resources continuation-query)]
        (is (identical? snapshot adapter))
        (is (= (:end-cursor (:page-info lookup-page))
               (:after query)))
        (is (= 1 (:decode-calls @work))
            "selection and internalization must share one authenticated decode")))))

(deftest cursor-is-bound-to-the-complete-semantic-query-test
  (let [snapshot (adapter 1 nil true)
        first-page
        (relay/externalize-page
         snapshot {} :lookup-resources lookup-query lookup-page)
        token (get-in first-page [:page-info :end-cursor])]
    (doseq [changed-query
            [(assoc-in lookup-query [:subject :id] "other-user")
             (assoc lookup-query :permission :edit)
             (assoc lookup-query :consistency :fully-consistent)]]
      (is (= :query-mismatch
             (try
               (relay/prepare-page-query
                snapshot
                {}
                :lookup-resources
                (assoc changed-query :after token))
               nil
               (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                   error
                 (:reason (ex-data error)))))
          (pr-str changed-query)))))

(deftest cursor-is-bound-to-normalized-traversal-limits-test
  (let [snapshot (adapter 1 nil true)
        original-limits {:max-derived-grants 100
                         :max-advanced-datoms 200
                         :max-queued-work 300}
        changed-limits (assoc original-limits :max-derived-grants 99)
        first-page
        (relay/externalize-page
         snapshot
         {:recursive-traversal-limits original-limits}
         :lookup-resources
         lookup-query
         lookup-page)
        token (get-in first-page [:page-info :end-cursor])]
    (is (= :query-mismatch
           (try
             (relay/prepare-page-query
              snapshot
              {:recursive-traversal-limits changed-limits}
              :lookup-resources
              (assoc lookup-query :after token))
             nil
             (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
               (:reason (ex-data error))))))))

(deftest changed-proof-on-snapshot-is-a-basis-conflict-test
  (let [original (adapter 1 nil true)
        current (adapter 2 nil true)
        first-page
        (relay/externalize-page
         original
         {:snapshot-semantic-identity (basis-identity 1)}
         :lookup-resources lookup-query lookup-page)
        token (get-in first-page [:page-info :end-cursor])
        data
        (try
          (relay/prepare-page-query
           current
           {:cursor-consistency-mode :fully-consistent
            :authorization-target-kind :snapshot
            :snapshot-semantic-identity (basis-identity 2)}
           :lookup-resources
           (assoc lookup-query :after token))
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
            (ex-data error)))]
    (is (= :eacl.consistency/basis-conflict (:type data)))
    (is (= :cursor (:source data)))))

(deftest recursive-snapshot-continuation-does-not-rebase-test
  (let [original (adapter 1 nil true)
        current (adapter 2 nil true)
        recursive-page
        (assoc-in
         lookup-page
         [:page-info :end-cursor]
         {:kind :lookup-eid
          :frontier-direction :asc
          :result-eid "document-1"})
        first-page
        (relay/externalize-page
         original
         {:snapshot-semantic-identity (basis-identity 1)}
         :lookup-resources lookup-query recursive-page)
        data
        (try
          (relay/prepare-page-query
           current
           {:cursor-consistency-mode :minimize-latency
            :authorization-target-kind :snapshot
            :snapshot-semantic-identity (basis-identity 2)}
           :lookup-resources
           (assoc lookup-query
                  :after
                  (get-in first-page [:page-info :end-cursor])))
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
            (ex-data error)))]
    (is (= :eacl.consistency/basis-conflict (:type data)))
    (is (= :cursor (:source data)))))

(deftest expired-cursor-reaches-the-kernel-decision-test
  ;; cursor-dependency-validity: the TTL check result is a computed input of
  ;; the verified continuation decision. The expired token flows TO the
  ;; kernel and is rejected THERE, with the public error unchanged.
  (let [snapshot (adapter 1 nil true)
        mint-opts {:cursor-ttl-seconds 10
                   :now-seconds 1000}
        page
        (relay/externalize-page
         snapshot mint-opts :lookup-resources lookup-query lookup-page)
        token (get-in page [:page-info :end-cursor])
        crossings (atom {})
        error
        (binding [verified/*kernel-crossing-stats* crossings]
          (try
            (relay/prepare-page-query
             snapshot
             {:now-seconds 2000}
             :lookup-resources
             (assoc lookup-query :after token))
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                   thrown
              thrown)))]
    (is (some? error) "an expired portable cursor must not resume")
    (is (= :eacl.pagination/expired-cursor (:type (ex-data error))))
    (is (= :eacl.pagination/expired-cursor (:eacl/error (ex-data error))))
    (is (= :expired (:reason (ex-data error))))
    (is (= 1010 (:expired-at (ex-data error))))
    (is (pos? (get @crossings :cursor-continuation 0))
        "the expired token was rejected by a :cursor-continuation kernel decision")))

(deftest cursor-without-configured-ttl-remains-age-valid-test
  (let [snapshot (adapter 1 nil true)
        page
        (relay/externalize-page
         snapshot {:now-seconds 1000}
         :lookup-resources lookup-query lookup-page)
        token (get-in page [:page-info :end-cursor])
        prepared
        (relay/prepare-page-query
         snapshot
         {:now-seconds 1000000}
         :lookup-resources
         (assoc lookup-query :after token))]
    (is (identical? snapshot (:adapter prepared)))
    (is (= {:frontier-direction :asc
            :kind :lookup-eid
            :result-eid "document-1"}
           (get-in prepared [:query :after]))
        "the old authenticated transport is internalized to its exact boundary")
    (is (not (contains? (:cursor-context (:opts prepared)) :exp))
        "elapsed age far beyond five minutes is irrelevant without an explicit TTL")))

(deftest one-page-builds-one-snapshot-context-test
  (let [native-revision-calls (atom 0)
        snapshot-id-calls (atom 0)
        test-adapter
        (backend/make-adapter
         {:id :relay-test
          :capabilities {}
          :fingerprint {:adapter :relay-test}
          :deterministic? true
          :operations
          (merge
           (operation-map 1 nil)
           {:native-revision
            (fn []
              (swap! native-revision-calls inc)
              {:revision 1
               :exact-locator 1})
            :snapshot-id
            (fn []
              (swap! snapshot-id-calls inc)
              1)
            :internal-id->object str})})
        edge {:kind :relationship-index
              :v 2
              :anchor :progress
              :scan-index 0
              :subject-id 10
              :resource-id 20}
        page
        {:data
         [(eacl/->Relationship
           (eacl/spice-object :user 10)
           :reader
           (eacl/spice-object :document 20))]
         :page-info
         {:start-cursor edge
          :end-cursor edge
          :has-next-page? false
          :has-previous-page? false}}
        external
        (relay/externalize-relationship-page
         test-adapter
         {:snapshot-semantic-identity (basis-identity 1)}
         :read-relationships
         {:subject/type :user :first 1}
         page)]
    (is (= 1 @native-revision-calls))
    (is (= 1 @snapshot-id-calls))
    (is (string? (get-in external [:page-info :start-cursor])))
    (is (string? (get-in external [:page-info :end-cursor])))))
