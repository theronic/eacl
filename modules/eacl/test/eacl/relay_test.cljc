(ns eacl.relay-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
            :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]
            [eacl.cursor :as cursor]
            [eacl.proof-frame :as proof-frame]
            [eacl.relay :as relay]
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
     :cursor-dependency-relation-ids (delay [1])
     :request-proof-frame
     (proof-frame/request-frame selected {:basis-identity identity})}))

(declare lookup-query lookup-page)

(deftest cursor-proof-identity-test
  (testing "even equal dependency proofs cannot lift across revisions"
    (let [first-context
          (relay/dependency-context
           (adapter 1 {:digest "same"} true))
          later-context
          (relay/dependency-context
           (adapter 2 {:digest "same"} true))]
      (is (not= (:proof-digest first-context)
                (:proof-digest later-context)))))

  (testing "the same exact snapshot has a stable identity"
    (let [first-context
          (relay/dependency-context (adapter 1 nil true))
          later-context
          (relay/dependency-context (adapter 1 {:different "proof"} true))]
      (is (= (:proof-digest first-context)
             (:proof-digest later-context)))))

  (testing "adapter determinism cannot enable cross-revision lifting"
    (let [first-context
          (relay/dependency-context
           (adapter 1 {:digest "same"} false))
          later-context
          (relay/dependency-context
           (adapter 2 {:digest "same"} false))]
      (is (not= (:proof-digest first-context)
                (:proof-digest later-context))))))

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
    (is (= :dependency-scope-changed (:reason error)))
    (is (= (:dependency-scope-digest exact-at-10)
           (:dependency-scope-digest invalid-at-10)
           (:dependency-scope-digest exact-at-11)
           (:dependency-scope-digest invalid-at-11))
        "violated evidence is represented only by exact-snapshot fallback")
    (is (not= (:proof-digest invalid-at-10)
              (:proof-digest invalid-at-11))
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

(deftest completed-page-cache-is-partitioned-by-consistency-mode-test
  (let [snapshot (adapter 1 nil true)
        page-cache (relay/page-navigation-cache)
        opts {:page-navigation-cache page-cache
              :completed-cache? true
              :snapshot-semantic-identity (basis-identity 1)}
        at-least-query
        (assoc lookup-query :consistency
               {:consistency/mode :at-least-as-fresh
                :zed/token "opaque-test-token"})
        authoritative-query
        (assoc lookup-query :consistency :fully-consistent)
        public-page
        (relay/externalize-page
         snapshot opts :lookup-resources at-least-query lookup-page)]
    (relay/remember-visited-page!
     snapshot opts :lookup-resources at-least-query public-page)
    (is (some? (relay/lookup-visited-page
                snapshot opts :lookup-resources at-least-query)))
    (is (nil? (relay/lookup-visited-page
               snapshot opts :lookup-resources authoritative-query))
        "a cached public cursor must never cross consistency query scope")))

(deftest read-without-publication-can-read-but-not-write-visited-pages-test
  (let [snapshot (adapter 1 nil true)
        page-cache (relay/page-navigation-cache)
        base-opts {:page-navigation-cache page-cache
                   :completed-cache? true
                   :snapshot-semantic-identity (basis-identity 1)}
        read-only-opts (assoc base-opts :populate-cache-request? false)
        public-page
        (relay/externalize-page
         snapshot base-opts :lookup-resources lookup-query lookup-page)]
    (relay/remember-visited-page!
     snapshot read-only-opts :lookup-resources lookup-query public-page)
    (is (nil? (relay/lookup-visited-page
               snapshot base-opts :lookup-resources lookup-query)))
    (relay/remember-visited-page!
     snapshot base-opts :lookup-resources lookup-query public-page)
    (is (some? (relay/lookup-visited-page
                snapshot read-only-opts :lookup-resources lookup-query))
        "publication control must not disable visited-page lookup")))

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
