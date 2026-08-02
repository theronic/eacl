(ns eacl.formal.production-kernel-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [eacl.backend.v8 :as backend]
   [eacl.cache :as cache]
   [eacl.core :refer [spice-object]]
   [eacl.engine.relationships :as relationship-engine]
   [eacl.formal.production-kernel :as production]
   [eacl.relay :as relay]
   [eacl.verified-kernel :as verified]))

(def selection
  {:mode :verified-authoritative
   :kernel production/generated-java-kernel})

(def authorization-input
  {:objects [{:type "user" :id "u1"}
             {:type "team" :id "t1"}
             {:type "folder" :id "f0"}
             {:type "folder" :id "f1"}]
   :schema
   {:relations
    [{:resource-type "folder"
      :relation "reader"
      :subject-type "user"}
     {:resource-type "folder"
      :relation "parent"
      :subject-type "folder"}
     {:resource-type "folder"
      :relation "team-reader"
      :subject-type "team"}]
    :permissions
    [{:resource-type "folder"
      :permission "read"}]
    :definitions
    [{:kind :direct-relation
      :resource-type "folder"
      :permission "read"
      :relation "reader"
      :subject-type "user"}
     {:kind :arrow-permission
      :resource-type "folder"
      :permission "read"
      :via-relation "parent"
      :target-permission "read"}
     {:kind :direct-relation
      :resource-type "folder"
      :permission "read"
      :relation "team-reader"
      :subject-type "team"}]}
   :relationships
   [{:resource {:type "folder" :id "f0"}
     :relation "reader"
     :subject {:type "user" :id "u1"}}
    {:resource {:type "folder" :id "f1"}
     :relation "parent"
     :subject {:type "folder" :id "f0"}}
    {:resource {:type "folder" :id "f1"}
     :relation "team-reader"
     :subject {:type "team" :id "t1"}}]
   :request
   {:operation :lookup-resources
    :subject {:type "user" :id "u1"}
    :permission "read"
    :resource-type "folder"}
   :limits {:max-derived-grants 1000
            :max-advanced-datoms 1000
            :max-queued-work 1000}})

(defn- test-adapter
  []
  (backend/make-adapter
   {:id :formal-production-test
    :capabilities
    {:consistency #{:minimize-latency}
     :snapshots #{:current :exact}
     :source #{:scoped}
     :cursor #{:forward :backward}
     :transactions #{}
     :cache-proofs #{:schema :relations}
     :runtime #{:clj}}
    :fingerprint {:adapter :formal-production-test}
    :identity-contract :formal-production-test/v1
    :operations
    (merge
     (into {}
           (map (fn [operation]
                  [operation (fn [& _] nil)]))
           backend/required-snapshot-operations)
     {:snapshot-id (constantly {:basis 1})
      :source-scope (constantly {:source "source"})
      :graph-head
      (constantly
       {:graph-anchor "graph-1"
        :order-hint 1
        :exact-locator "graph-1"})
      :contains-anchor? #(= "graph-1" %)
      :order-hint (constantly 1)
      :exact-locator (constantly "graph-1")
      :select-exact (fn [& _] nil)
      :object-id->internal
      #(case % "document-1" 1 "document-2" 2 nil)
      :internal-id->object
      #(case % 1 "document-1" 2 "document-2" nil)
      :schema-proof (constantly "schema-proof")
      :relation-proof
      (fn [relation-ids]
        (zipmap relation-ids (repeat "relation-proof")))})}))

(deftest generated-java-production-decision-boundary
  (testing "page normalization and exact window"
    (is (= {:status :valid
            :direction :asc
            :size 2
            :start 1
            :end 3
            :has-next? true
            :has-previous? true}
           (verified/decide
            selection
            :relationship-page
            {:length 4
             :request {:first 2
                       :last :absent
                       :after 0
                       :before :absent
             :has-legacy-limit? false
             :has-legacy-cursor? false}
             :default-size 1000
             :maximum-size 10000}
            #(throw (ex-info "legacy must not run" {}))))))
  (testing "keyset page lookahead"
    (is (= {:take-count 20
            :reverse? false
            :has-next? true
            :has-previous? false}
           (verified/decide
            selection
            :relationship-keyset-page
            {:direction :asc
             :size 20
             :bound? false
             :realized-count 21}
            #(throw (ex-info "legacy must not run" {}))))))
  (testing "cursor proof mismatch is fail-closed"
    (is (= :snapshot-unavailable
           (verified/decide
            selection
            :cursor-continuation
            {:authenticated? true
             :scope-matches? true
             :expired? false
             :source "source"
             :cursor-source "source"
             :current-proof "new"
             :cursor-proof "old"
             :mode :minimize-latency
             :cursor-graph 0
             :exact nil}
            #(throw (ex-info "legacy must not run" {}))))))
  (testing "cache future/sibling is rejected"
    (is (= {:status :miss :reason :future-or-sibling}
           (verified/decide
            selection
            :cache-validation
            {:deterministic? true
             :dependency-scope-nonempty? true
             :expected-key "key"
             :expected-source "source"
             :selected-graph 0
             :ancestors #{1}
             :selected-proof "proof"
             :entry {:status :candidate
                     :authenticated? true
                     :key "key"
                     :source "source"
                     :graph 2
                     :proof "proof"}}
            #(throw (ex-info "legacy must not run" {})))))))

(deftest generated-java-full-authorization-boundary
  (let [evaluate
        (fn [request]
          (verified/decide
           selection
           :authorization-evaluation
           (assoc authorization-input :request request)
           #(throw (ex-info "legacy must not run" {}))))
        lookup
        (evaluate (:request authorization-input))
        can-result
        (evaluate
         {:operation :can?
          :subject {:type "user" :id "u1"}
          :permission "read"
          :resource {:type "folder" :id "f1"}})
        reverse-result
        (evaluate
         {:operation :lookup-subjects
          :resource {:type "folder" :id "f1"}
          :permission "read"
          :subject-type "user"})
        count-result
        (evaluate
         {:operation :count-resources
          :subject {:type "user" :id "u1"}
          :permission "read"
          :resource-type "folder"
          :count-limit 1})
        reverse-count-result
        (evaluate
         {:operation :count-subjects
          :resource {:type "folder" :id "f1"}
          :permission "read"
          :subject-type "user"
          :count-limit 1})]
    (is (= [{:type "folder" :id "f0"}
            {:type "folder" :id "f1"}]
           (:items lookup)))
    (is (true? (:allowed? can-result)))
    (is (= [{:type "user" :id "u1"}]
           (:items reverse-result)))
    (is (= {:count 1 :truncated? true}
           (select-keys count-result [:count :truncated?])))
    (is (= {:count 1 :truncated? false}
           (select-keys
            reverse-count-result
            [:count :truncated?])))))

(deftest production-relationship-pages-use-generated-java-decisions
  (let [scan-specs [{:idx 0 :scan-kind :forward-anchored}]
        rows (mapv (fn [id]
                     {:spec-idx 0
                      :subject-id 1
                      :resource-id id
                      :relationship {:id id}})
                   (range 1 5))
        scan
        (fn [spec edge direction]
          (let [ordered (if (= :desc direction)
                          (reverse rows)
                          rows)]
            (drop-while
             #(not
               (relationship-engine/beyond-cursor?
                (:scan-kind spec) direction edge %))
             ordered)))
        first-page
        (relationship-engine/execute-page
         scan-specs {:first 2} selection scan)
        second-page
        (relationship-engine/execute-page
         scan-specs
         {:first 2
          :after (get-in first-page [:page-info :end-cursor])}
         selection
         scan)]
    (is (= [{:id 1} {:id 2}] (:data first-page)))
    (is (true? (get-in first-page [:page-info :has-next-page?])))
    (is (= [{:id 3} {:id 4}] (:data second-page)))
    (is (false? (get-in second-page [:page-info :has-next-page?])))
    (is (true? (get-in second-page
                       [:page-info :has-previous-page?])))))

(deftest production-lookup-cursor-and-cache-use-generated-java-decisions
  (let [adapter (test-adapter)
        cursor-opts
        {:engine-selection selection
         :cursor-dependencies
         {:schema-scope {:permission-nodes #{[:document :view]}}
          :relation-ids [10]}
         :cursor-consistency-mode :minimize-latency}
        query {:subject (spice-object :user "user-1")
               :permission :view
               :resource/type :document
               :first 1}
        internal-page
        {:data [{:type :document :id 1}]
         :page-info
         {:start-cursor {:kind :lookup-eid :result-eid 1}
          :end-cursor {:kind :lookup-eid :result-eid 1}
          :has-next-page? true
          :has-previous-page? false}}
        external
        (relay/externalize-page
         adapter cursor-opts :lookup-resources query internal-page)
        token (get-in external [:page-info :end-cursor])
        selected
        (relay/select-continuation-adapter
         adapter cursor-opts :lookup-resources
         (assoc query :after token))
        internal-query
        (relay/internalize-page-query
         selected cursor-opts :lookup-resources
         (assoc query :after token))
        store (cache/local-store)
        calls (atom 0)
        resolve-cache
        #(cache/resolve!
          adapter store :semantic-key :can?
          {:permission-nodes #{[:document :view]}}
          [10]
          boolean?
          (fn [] (swap! calls inc) true)
          {:engine-selection selection})]
    (is (identical? adapter selected))
    (is (= {:kind :lookup-eid :result-eid 1}
           (:after internal-query)))
    (is (false? (:cached? (resolve-cache))))
    (is (true? (:cached? (resolve-cache))))
    (is (= 1 @calls))))
