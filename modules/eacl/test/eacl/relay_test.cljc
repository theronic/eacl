(ns eacl.relay-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]
            [eacl.cursor :as cursor]
            [eacl.relay :as relay]))

(defn- operation-map
  [snapshot-id proof]
  (merge
   (into {}
         (map (fn [operation]
                [operation (fn [& _] nil)]))
         backend/required-snapshot-operations)
   {:snapshot-id (constantly snapshot-id)
    :source-scope (constantly {:source :relay-test})
    :graph-head
    (constantly
     {:graph-anchor snapshot-id
      :order-hint snapshot-id
      :exact-locator snapshot-id})
    :object-id->internal identity
    :internal-id->object identity
    :schema-proof (fn [& _] proof)
    :relation-proof (fn [_] proof)}))

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
    :capabilities {}
    :fingerprint {:adapter :relay-test}
    :deterministic? true
    :operations
    (assoc
     (operation-map snapshot-id nil)
     :select-exact (fn [& _] exact))}))

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
  (let [snapshot (adapter 1 nil true)
        original relay/dependency-context
        calls (atom 0)]
    (with-redefs [relay/dependency-context
                  (fn [adapter]
                    (swap! calls inc)
                    (original adapter))]
      (let [page
            (relay/externalize-page
             snapshot {} :lookup-resources lookup-query lookup-page)]
        (is (string? (get-in page [:page-info :start-cursor])))
        (is (string? (get-in page [:page-info :end-cursor])))
        (is (= 1 @calls)
            "both boundary tokens must reuse one immutable snapshot proof")))))

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

(deftest non-exact-continuation-recovers-on-current-graph-test
  (let [original (adapter 1 nil true)
        current (adapter 2 nil true)
        first-page
        (relay/externalize-page
         original {} :lookup-resources lookup-query lookup-page)
        token (get-in first-page [:page-info :end-cursor])
        prepared
        (relay/prepare-page-query
         current
         {:cursor-consistency-mode :fully-consistent}
         :lookup-resources
         (assoc lookup-query :after token))]
    (is (identical? current (:adapter prepared)))
    (is (= :rebased (:recovery prepared)))
    (is (= (assoc (:end-cursor (:page-info lookup-page))
                  :rebase? true)
           (get-in prepared [:query :after])))
    (is (= :rebased
           (get-in
            (relay/externalize-page
             current
             {:cursor-recovery (:recovery prepared)}
             :lookup-resources
             lookup-query
             lookup-page)
            [:page-info :cursor-recovery])))))

(deftest recursive-continuation-rebases-by-result-after-graph-change-test
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
        prepared
        (relay/prepare-page-query
         current
         {:cursor-consistency-mode :minimize-latency}
         :lookup-resources
         (assoc lookup-query
                :after
                (get-in first-page [:page-info :end-cursor])))]
    (is (= :rebased (:recovery prepared)))
    (is (= (-> (get-in recursive-page [:page-info :end-cursor])
               (assoc :rebase? true))
           (get-in prepared [:query :after])))))

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
         {:cursor-consistency-mode :at-exact-snapshot}
         :lookup-resources
         (assoc exact-query
                :after
                (get-in page [:page-info :end-cursor])))]
    (is (identical? exact (:adapter prepared)))
    (is (nil? (:recovery prepared)))))

(deftest one-page-builds-one-snapshot-context-test
  (let [graph-head-calls (atom 0)
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
           {:graph-head
            (fn []
              (swap! graph-head-calls inc)
              {:graph-anchor 1
               :order-hint 1
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
    (is (= 1 @graph-head-calls))
    (is (= 1 @snapshot-id-calls))
    (is (string? (get-in external [:page-info :start-cursor])))
    (is (string? (get-in external [:page-info :end-cursor])))))
