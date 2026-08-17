(ns eacl.relay-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.cursor :as cursor]
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
    :source-scope
    (constantly {:source-id "relay-source" :branch nil})
    :source-lifecycle (constantly "relay-lifecycle")
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

(defn- adapter-with-exact
  [snapshot-id exact]
  (backend/make-adapter
   {:id :relay-test
    :capabilities {:consistency #{:at-exact-snapshot}
                   :snapshots #{:historical}}
    :fingerprint {:adapter :relay-test}
    :deterministic? true
    :operations
    (assoc
     (operation-map snapshot-id nil)
     :select-exact (fn [& _] exact))}))

(deftest snapshot-exact-key-requires-certified-exact-selection-test
  (let [current-only (adapter 1 nil true)
        exact-capable (adapter-with-exact 1 current-only)]
    (is (nil? (cache/snapshot-exact-key current-only))
        "a current-only or arbitrary immutable view cannot infer exact identity from revision equality")
    (is (= :ordinary-exact
           (:view-kind (cache/snapshot-exact-key exact-capable))))
    (is (= 1 (:exact-locator (cache/snapshot-exact-key exact-capable))))))

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

(deftest changed-proof-without-history-is-stale-test
  (let [original (adapter 1 nil true)
        current (adapter 2 nil true)
        first-page
        (relay/externalize-page
         original {} :lookup-resources lookup-query lookup-page)
        token (get-in first-page [:page-info :end-cursor])
        data
        (try
          (relay/prepare-page-query
           current
           {:cursor-consistency-mode :fully-consistent}
           :lookup-resources
           (assoc lookup-query :after token))
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
            (ex-data error)))]
    (is (= :eacl.pagination/stale-cursor (:type data)))
    (is (= :dependency-proof-changed (:reason data)))))

(deftest recursive-continuation-does-not-rebase-after-graph-change-test
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
         original {} :lookup-resources lookup-query recursive-page)
        data
        (try
          (relay/prepare-page-query
           current
           {:cursor-consistency-mode :minimize-latency}
           :lookup-resources
           (assoc lookup-query
                  :after
                  (get-in first-page [:page-info :end-cursor])))
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
            (ex-data error)))]
    (is (= :eacl.pagination/stale-cursor (:type data)))
    (is (= :dependency-proof-changed (:reason data)))))

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

(deftest exact-snapshot-continuation-never-rebases-test
  (let [exact (adapter 1 nil true)
        current (adapter-with-exact 2 exact)
        exact-query
        (assoc lookup-query
               :consistency
               {:consistency/mode :at-exact-snapshot
                :zed/token "exact-token"})
        page
        (relay/externalize-page
         exact {} :lookup-resources exact-query lookup-page)
        prepared
        (relay/prepare-page-query
         current
         {:cursor-consistency-mode :at-exact-snapshot
          :cursor-request-token
          {:revision 1
           :exact-locator 1}}
         :lookup-resources
         (assoc exact-query
                :after
                (get-in page [:page-info :end-cursor])))]
    (is (identical? exact (:adapter prepared)))
    (is (not (contains? prepared :recovery)))))

(deftest changed-current-schema-reaches-exact-cursor-fallback-test
  (let [exact (adapter 1 nil true)
        current (adapter-with-exact 2 exact)
        page
        (relay/externalize-page
         exact
         {:cursor-schema-stamp {:adapter exact :stamp (delay 10)}}
         :lookup-resources lookup-query lookup-page)
        prepared
        (relay/prepare-page-query
         current
         {:cursor-consistency-mode :minimize-latency
          :cursor-schema-stamp {:adapter current :stamp (delay 20)}}
         :lookup-resources
         (assoc lookup-query
                :after (get-in page [:page-info :end-cursor])))]
    (is (identical? exact (:adapter prepared))
        "schema proof changes must not masquerade as query-scope changes")))

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
              :v 1
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
         test-adapter {} :read-relationships
         {:subject/type :user :first 1}
         page)]
    (is (= 1 @native-revision-calls))
    (is (= 1 @snapshot-id-calls))
    (is (string? (get-in external [:page-info :start-cursor])))
    (is (string? (get-in external [:page-info :end-cursor])))))
