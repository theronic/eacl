(ns eacl.formal.state-trace-differential-test
  "Public-client state traces with generated cache/cursor authority.

  The same trace runs with cache enabled and disabled on every JVM adapter.
  Expected authorization sets come from the declarative fixture, while all
  decoded cache and cursor decisions pass through generated Java."
  (:require
   [clojure.test :refer [deftest is testing]]
   [datahike.api :as dh]
   [datomic.api :as d]
   [datascript.core :as ds]
   [eacl.backend.v8 :as backend]
   [eacl.cache :as shared-cache]
   [eacl.core :as eacl]
   [eacl.datahike.backend :as datahike-backend]
   [eacl.datahike.core :as datahike]
   [eacl.datascript.backend :as datascript-backend]
   [eacl.datascript.core :as datascript]
   [eacl.datomic.backend :as datomic-backend]
   [eacl.datomic.cache :as datomic-cache]
   [eacl.datomic.core :as datomic]
   [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
   [eacl.datomic.schema :as datomic-schema]
   [eacl.engine.v8 :as engine]
   [eacl.formal.differential-runner :as differential]
   [eacl.formal.production-kernel :as production]
   [eacl.subproblem-cache :as subproblem]
   [eacl.verified-kernel :as verified]))

(def authorization-schema
  "definition user {}
   definition group {
     relation member: user
   }
   definition document {
     relation reader: user
     relation parent: document
     relation group: group
     permission base = reader
     permission view = base + parent->view + group->member
   }")

(def user (eacl/spice-object :user "user"))
(def document-1 (eacl/spice-object :document "document-1"))
(def document-2 (eacl/spice-object :document "document-2"))
(def group (eacl/spice-object :group "group"))
(def relationship-1
  (eacl/->Relationship user :reader document-1))
(def relationship-2
  (eacl/->Relationship user :reader document-2))
(def recursive-parent-relationship
  (eacl/->Relationship document-1 :parent document-2))
(def group-member-relationship
  (eacl/->Relationship user :member group))
(def document-group-relationship
  (eacl/->Relationship group :group document-2))
(def support-relationships
  [recursive-parent-relationship
   group-member-relationship
   document-group-relationship])

(def engine-selection
  {:mode :verified-authoritative
   :kernel production/generated-java-kernel})

(def formal-objects
  [{:type "user" :id "user"}
   {:type "document" :id "document-1"}
   {:type "document" :id "document-2"}
   {:type "group" :id "group"}])

(def formal-schema
  {:relations
   [{:resource-type "document"
     :relation "reader"
     :subject-type "user"}
    {:resource-type "document"
     :relation "parent"
     :subject-type "document"}
    {:resource-type "document"
     :relation "group"
     :subject-type "group"}
    {:resource-type "group"
     :relation "member"
     :subject-type "user"}]
   :permissions
   [{:resource-type "document"
     :permission "base"}
    {:resource-type "document"
     :permission "view"}]
   :definitions
   [{:kind :direct-relation
     :resource-type "document"
     :permission "base"
     :relation "reader"
     :subject-type "user"}
    {:kind :self-permission
     :resource-type "document"
     :permission "view"
     :target-permission "base"}
    {:kind :arrow-permission
     :resource-type "document"
     :permission "view"
     :via-relation "parent"
     :target-permission "view"}
    {:kind :arrow-relation
     :resource-type "document"
     :permission "view"
     :via-relation "group"
     :target-relation "member"
     :subject-type "user"}]})

(defrecord CountingGeneratedKernel [delegate calls]
  verified/DecisionKernel
  (-decide [_ operation input]
    (swap! calls update operation (fnil inc 0))
    (verified/-decide delegate operation input))

  verified/IndexedTraversalKernel
  (-compile-indexed-plan [_ input]
    (swap! calls update :indexed-traversal-compile (fnil inc 0))
    (verified/-compile-indexed-plan delegate input))
  (-initialize-indexed [_ direction input]
    (swap! calls update :indexed-traversal-initialize (fnil inc 0))
    (verified/-initialize-indexed delegate direction input))
  (-drive-indexed [_ direction state limits fuel]
    (swap! calls update :indexed-traversal-drive (fnil inc 0))
    (verified/-drive-indexed delegate direction state limits fuel))
  (-resume-indexed [_ direction state response limits]
    (swap! calls update :indexed-traversal-resume (fnil inc 0))
    (verified/-resume-indexed delegate direction state response limits))
  (-read-indexed-result [_ direction state]
    (swap! calls update :indexed-traversal-read (fnil inc 0))
    (verified/-read-indexed-result delegate direction state)))

(defn- counting-engine-selection
  [calls]
  {:mode :verified-authoritative
   :kernel
   (->CountingGeneratedKernel production/generated-java-kernel calls)})

(defn- call-count
  [calls]
  (reduce + 0 (vals @calls)))

(def formal-limits
  {:max-derived-grants 1000
   :max-advanced-datoms 1000
   :max-queued-work 1000})

(defn- formal-object
  [object]
  {:type (name (:type object))
   :id (:id object)})

(defn- formal-relationship
  [{:keys [subject relation resource]}]
  {:subject (formal-object subject)
   :relation (name relation)
   :resource (formal-object resource)})

(defn- formal-evaluate
  [relationships request]
  (verified/decide
   engine-selection
   :authorization-evaluation
   {:objects formal-objects
    :schema formal-schema
    :relationships (mapv formal-relationship relationships)
    :request request
    :limits formal-limits}
   #(throw (ex-info "legacy authorization oracle must not run" {}))))

(defn- public-object-set
  [page]
  (into #{} (map formal-object) (:data page)))

(defn- assert-public-authorization!
  [label cached uncached relationships]
  (let [resource-query
        {:subject user
         :permission :view
         :resource/type :document
         :first 10}
        formal-resource-query
        {:operation :lookup-resources
         :subject (formal-object user)
         :permission "view"
         :resource-type "document"}
        reverse-query
        {:resource document-2
         :permission :view
         :subject/type :user
         :first 10}
        formal-reverse-query
        {:operation :lookup-subjects
         :resource (formal-object document-2)
         :permission "view"
         :subject-type "user"}
        formal-resources
        (formal-evaluate relationships formal-resource-query)
        formal-subjects
        (formal-evaluate relationships formal-reverse-query)
        formal-count-resources
        (formal-evaluate
         relationships
         (assoc formal-resource-query
                :operation :count-resources
                :count-limit 1))
        formal-count-subjects
        (formal-evaluate
         relationships
         (assoc formal-reverse-query
                :operation :count-subjects
                :count-limit 1))]
    (doseq [[mode client]
            [[:cache-enabled cached]
             [:cache-disabled uncached]]]
      (let [actual-resources
            (eacl/lookup-resources client resource-query)
            actual-subjects
            (eacl/lookup-subjects client reverse-query)
            actual-count-resources
            (eacl/count-resources
             client
             (-> resource-query
                 (dissoc :first)
                 (assoc :count-limit 1)))
            actual-count-subjects
            (eacl/count-subjects
             client
             (-> reverse-query
                 (dissoc :first)
                 (assoc :count-limit 1)))]
        (is (= :passed
               (:status
                (differential/compare-values!
                 {:seed 820084
                  :case-id [label mode :lookup-resources]
                  :values
                  [[:verified-generated-java
                    (set (:items formal-resources))]
                   [:public-client
                    (public-object-set actual-resources)]]}))))
        (is (= :passed
               (:status
                (differential/compare-values!
                 {:seed 820084
                  :case-id [label mode :lookup-subjects]
                  :values
                  [[:verified-generated-java
                    (set (:items formal-subjects))]
                   [:public-client
                    (public-object-set actual-subjects)]]}))))
        (is (= :passed
               (:status
                (differential/compare-values!
                 {:seed 820084
                  :case-id [label mode :count-resources]
                  :values
                  [[:verified-generated-java
                    (select-keys
                     formal-count-resources
                     [:count :truncated?])]
                   [:public-client
                    (select-keys
                     actual-count-resources
                     [:count :truncated?])]]}))))
        (is (= :passed
               (:status
                (differential/compare-values!
                 {:seed 820084
                  :case-id [label mode :count-subjects]
                  :values
                  [[:verified-generated-java
                    (select-keys
                     formal-count-subjects
                     [:count :truncated?])]
                   [:public-client
                    (select-keys
                     actual-count-subjects
                     [:count :truncated?])]]}))))))
    (doseq [resource [document-1 document-2]]
      (let [formal-result
            (formal-evaluate
             relationships
             {:operation :can?
              :subject (formal-object user)
              :permission "view"
              :resource (formal-object resource)})]
        (is (= :passed
               (:status
                (differential/compare-values!
                 {:seed 820084
                  :case-id [label :can? (:id resource)]
                  :values
                  [[:verified-generated-java
                    (:allowed? formal-result)]
                   [:public-cache-enabled
                    (eacl/can? cached user :view resource)]
                   [:public-cache-disabled
                    (eacl/can? uncached user :view resource)]]}))))))))

(defn- ids
  [page]
  (mapv :id (:data page)))

(defn- assert-public-trace!
  [label cached uncached unrelated-write! calls]
  (testing label
    (eacl/create-relationships!
     cached
     (into [relationship-1 relationship-2]
           support-relationships))
    (let [all-query
          {:subject user
           :permission :view
           :resource/type :document
           :first 10}
          first-query
          (assoc all-query :first 1)
          first-cached (eacl/lookup-resources cached all-query)
          calls-before-uncached (call-count calls)
          first-uncached (eacl/lookup-resources uncached all-query)
          calls-after-uncached (call-count calls)
          exact-hit (eacl/lookup-resources cached all-query)
          page-1 (eacl/lookup-resources cached first-query)
          page-2
          (eacl/lookup-resources
           cached
           (assoc first-query
                  :after
                  (get-in page-1 [:page-info :end-cursor])))]
      (is (= ["document-1" "document-2"]
             (ids first-cached)
             (ids first-uncached)))
      (is (< calls-before-uncached calls-after-uncached)
          "an explicit cache bypass must still route recursive traversal
           through generated authority")
      (is (= :passed
             (:status
              (differential/compare-values!
               {:seed 820084
                :case-id [label :initial]
                :values
                [[:formal-semantics
                  ["document-1" "document-2"]]
                 [:public-cache-enabled (ids first-cached)]
                 [:public-cache-disabled (ids first-uncached)]]}))))
      (is (false? (:cached? first-cached)))
      (is (false? (:cached? first-uncached)))
      (is (true? (:cached? exact-hit)))
      (is (pos?
           (get @calls :current-cache-decision 0))
          "cache eligibility and hit/miss selection cross generated authority")
      (is (pos?
           (get @calls :relationship-page 0))
          "lookup pagination normalization crosses generated authority")
      (is (= ["document-1" "document-2"]
             (into (ids page-1) (ids page-2))))
      (is (false? (get-in page-2
                          [:page-info :has-next-page?])))
      (unrelated-write!)
      (let [lifted (eacl/lookup-resources cached all-query)
            fresh (eacl/lookup-resources uncached all-query)]
        (is (= :passed
               (:status
                (differential/compare-values!
                 {:seed 820084
                  :case-id [label :unrelated-write]
                  :values
                  [[:formal-semantics
                    ["document-1" "document-2"]]
                   [:public-cache-enabled (ids lifted)]
                   [:public-cache-disabled (ids fresh)]]}))))
        (is (true? (:cached? lifted))))
      (assert-public-authorization!
       label
       cached
       uncached
       (into [relationship-1 relationship-2]
             support-relationships))
      (eacl/delete-relationship! cached relationship-1)
      (let [after-revocation
            (eacl/lookup-resources cached all-query)
            fresh-after-revocation
            (eacl/lookup-resources uncached all-query)]
        (is (= :passed
               (:status
                (differential/compare-values!
                 {:seed 820084
                  :case-id [label :revocation]
                  :values
                  [[:formal-semantics ["document-2"]]
                   [:public-cache-enabled
                    (ids after-revocation)]
                   [:public-cache-disabled
                    (ids fresh-after-revocation)]]}))))
        (is (false? (:cached? after-revocation)))
        (is (false?
             (eacl/can?
              cached user :view document-1)))
        (is (true?
             (eacl/can?
              cached user :view document-2))))
      (assert-public-authorization!
       label
       cached
       uncached
       (into [relationship-2]
             support-relationships)))))

(deftest generated-cache-and-cursor-state-traces-across-jvm-adapters
  (testing "DataScript"
    (let [conn (datascript/create-conn)
          calls (atom {})
          common
          {:engine-selection (counting-engine-selection calls)
           :coherence-authority :managed
           :security-key
           "01234567890123456789012345678901"
           :exact-snapshot-registry-size 16}
          cached (datascript/make-client conn common)
          uncached
          (datascript/make-client
           conn (assoc common :cache shared-cache/no-cache))]
      (eacl/write-schema! cached authorization-schema)
      (ds/transact!
       conn
       [{:eacl/id "user"}
        {:eacl/id "document-1"}
        {:eacl/id "document-2"}
        {:eacl/id "group"}])
      (assert-public-trace!
       "DataScript generated authority"
       cached uncached
       #(ds/transact!
         conn [{:db/doc "unrelated"}])
       calls)))

  (testing "Datahike"
    (let [conn (datahike/create-conn)
          calls (atom {})
          common
          {:engine-selection (counting-engine-selection calls)
           :coherence-authority :managed
           :security-key
           "01234567890123456789012345678901"}
          cached (datahike/make-client conn common)
          uncached
          (datahike/make-client
           conn (assoc common :cache shared-cache/no-cache))]
      (eacl/write-schema! cached authorization-schema)
      (dh/transact
       conn
       [{:eacl/id "user"}
        {:eacl/id "document-1"}
        {:eacl/id "document-2"}
        {:eacl/id "group"}])
      (assert-public-trace!
       "Datahike generated authority"
       cached uncached
       #(dh/transact
         conn [{:db/doc "unrelated"}])
       calls)))

  (testing "Datomic"
    (with-mem-conn [conn datomic-schema/v7-schema]
      (let [calls (atom {})
            common
            {:engine-selection (counting-engine-selection calls)
             :coherence-authority :managed
             :page-token-key
             "01234567890123456789012345678901"
             :zed-token-key
             "12345678901234567890123456789012"}
            cached
            (datomic/make-client
             conn
             (assoc common
                    :cache {:remember-answers true}))
            uncached
            (datomic/make-client
             conn
             (assoc common :cache datomic-cache/no-cache))]
        (eacl/write-schema! cached authorization-schema)
        @(d/transact
          conn
          [{:db/id (d/tempid :db.part/user)
            :eacl/id "user"}
           {:db/id (d/tempid :db.part/user)
            :eacl/id "document-1"}
           {:db/id (d/tempid :db.part/user)
            :eacl/id "document-2"}
           {:db/id (d/tempid :db.part/user)
            :eacl/id "group"}])
        (assert-public-trace!
         "Datomic generated authority"
         cached uncached
         #(deref
           (d/transact
            conn
            [{:db/id (d/tempid :db.part/user)
              :db/doc "unrelated"}]))
         calls)))))

(deftest generated-mode-does-not-reorder-acyclic-multipath-pages
  (let [conn (datascript/create-conn)
        calls (atom {})
        client
        (datascript/make-client
         conn
         {:cache shared-cache/no-cache
          :security-key
          "01234567890123456789012345678901"
          :engine-selection (counting-engine-selection calls)})
        user (eacl/spice-object :user "ordered-user")
        documents
        (mapv
         #(eacl/spice-object :document (format "document-%02d" %))
         (range 1 41))
        expected-ids (mapv :id documents)]
    (eacl/write-schema!
     client
     "definition user {}
      definition document {
        relation owner: user
        relation viewer: user
        permission view = owner + viewer
      }")
    (ds/transact!
     conn
     (mapv
      (fn [object] {:eacl/id (:id object)})
      (into [user] documents)))
    (eacl/create-relationships!
     client
     (mapv
      (fn [index document]
        (eacl/->Relationship
         user
         (if (odd? index) :owner :viewer)
         document))
      (range 1 41)
      documents))
    (let [query
          {:subject user
           :permission :view
           :resource/type :document
           :first 20}
          first-page (eacl/lookup-resources client query)
          second-page
          (eacl/lookup-resources
           client
           (assoc query
                  :after
                  (get-in first-page [:page-info :end-cursor])))]
      (is (= (subvec expected-ids 0 20)
             (ids first-page)))
      (is (= (subvec expected-ids 20 40)
             (ids second-page)))
      (is (= {:count 40 :limit -1
              :cached? false :cache-basis nil}
             (eacl/count-resources
              client
              (dissoc query :first))))
      (is (true?
           (eacl/can?
            client user :view (peek documents))))
      (is (zero?
           (get @calls :indexed-traversal-compile 0))
          "acyclic paths do not use the fixed-point discovery-order worklist"))))

(defn- shadow-selection
  [reports]
  {:mode :verified-shadow
   :kernel production/generated-java-kernel
   :report-divergence #(swap! reports conj %)})

(defn- selected-graph-identity
  [adapter]
  (let [graph-head (backend/invoke adapter :graph-head)]
    {:snapshot-id (backend/invoke adapter :snapshot-id)
     ;; Exact locators are client-local reconstruction capabilities. DataScript
     ;; clients legitimately mint different registry handles for the same
     ;; immutable graph, so graph identity is the authenticated anchor/order
     ;; pair in one source scope, not the locator representation.
     :graph-head (select-keys graph-head [:graph-anchor :order-hint])
     :source-scope (backend/invoke adapter :source-scope)}))

(defn- page-info-shadow-view
  [page-info]
  (cond->
   (select-keys
    page-info
    [:has-next-page? :has-previous-page?])
    (contains? page-info :start-cursor)
    (assoc :start-cursor? (some? (:start-cursor page-info)))

    (contains? page-info :end-cursor)
    (assoc :end-cursor? (some? (:end-cursor page-info)))))

(defn- public-value-shadow-view
  [value]
  (if (map? value)
    (cond-> (dissoc value :cached? :cache-basis :data :page-info)
      (contains? value :data)
      (assoc :data
             (mapv
              (fn [object]
                (select-keys object [:type :id]))
              (:data value)))

      (contains? value :page-info)
      (assoc :page-info
             (page-info-shadow-view (:page-info value))))
    value))

(defn- public-result-shadow-view
  [selected-graph result]
  (if (and (map? result) (contains? result :outcome))
    result
    {:outcome :value
     :value (public-value-shadow-view result)
     :cache-provenance
     (when (map? result)
       {:cached? (:cached? result)
        :cache-basis (:cache-basis result)})
     :selected-graph selected-graph}))

(defn- invoke-public-shadow
  [selected-graph-fn operation call]
  (try
    (let [value (call)]
      (public-result-shadow-view (selected-graph-fn) value))
    (catch Exception error
      (assoc
       (verified/error-shadow-view error)
       :selected-graph (selected-graph-fn)
       :operation operation))))

(defn- compare-public-shadow!
  [reports operation legacy-graph verified-graph legacy-call verified-call]
  (let [legacy
        (invoke-public-shadow legacy-graph operation legacy-call)]
    (verified/compare-shadow!
     (shadow-selection reports)
     operation
     legacy
     #(invoke-public-shadow verified-graph operation verified-call))))

(defn- assert-public-provenance-and-graph-shadow!
  [label legacy verified legacy-graph verified-graph unrelated-write!]
  (testing label
    (eacl/create-relationships!
     legacy
     (into [relationship-1 relationship-2]
           support-relationships))
    (let [query
          {:subject user
           :permission :view
           :resource/type :document
           :first 10}
          first-query (assoc query :first 1)
          reports (atom [])
          compare!
          (fn [operation legacy-call verified-call]
            (compare-public-shadow!
             reports operation legacy-graph verified-graph
             legacy-call verified-call))]
      (compare!
       :public-lookup-resources-cache-miss
       #(eacl/lookup-resources legacy query)
       #(eacl/lookup-resources verified query))
      (compare!
       :public-lookup-resources-cache-hit
       #(eacl/lookup-resources legacy query)
       #(eacl/lookup-resources verified query))
      (compare!
       :public-count-resources-cache-miss
       #(eacl/count-resources legacy (dissoc query :first))
       #(eacl/count-resources verified (dissoc query :first)))
      (compare!
       :public-count-resources-cache-hit
       #(eacl/count-resources legacy (dissoc query :first))
       #(eacl/count-resources verified (dissoc query :first)))
      (compare!
       :public-can
       #(eacl/can? legacy user :view document-2)
       #(eacl/can? verified user :view document-2))
      (let [legacy-first (eacl/lookup-resources legacy first-query)
            verified-first (eacl/lookup-resources verified first-query)]
        (verified/compare-shadow!
         (shadow-selection reports)
         :public-forward-page-1
         (public-result-shadow-view (legacy-graph) legacy-first)
         #(public-result-shadow-view
           (verified-graph) verified-first))
        (compare!
         :public-forward-page-2
         #(eacl/lookup-resources
           legacy
           (assoc first-query
                  :after
                  (get-in legacy-first
                          [:page-info :end-cursor])))
         #(eacl/lookup-resources
           verified
           (assoc first-query
                  :after
                  (get-in verified-first
                          [:page-info :end-cursor])))))
      (compare!
       :public-invalid-page-error
       #(eacl/lookup-resources legacy (assoc query :first 0))
       #(eacl/lookup-resources verified (assoc query :first 0)))
      (unrelated-write!)
      (compare!
       :public-causal-proof-lift
       #(eacl/lookup-resources legacy query)
       #(eacl/lookup-resources verified query))
      (is (empty? @reports)
          (str "complete public shadow divergence: "
               (pr-str @reports))))))

(deftest public-shadow-compares-cache-provenance-and-selected-graph
  (testing "DataScript"
    (let [conn (datascript/create-conn)
          common
          {:coherence-authority :managed
           :security-key
           "01234567890123456789012345678901"
           :exact-snapshot-registry-size 16}
          legacy (datascript/make-client conn common)
          verified
          (datascript/make-client
           conn
           (assoc common :engine-selection engine-selection))]
      (eacl/write-schema! legacy authorization-schema)
      (ds/transact!
       conn
       [{:eacl/id "user"}
        {:eacl/id "document-1"}
        {:eacl/id "document-2"}
        {:eacl/id "group"}])
      (assert-public-provenance-and-graph-shadow!
       "DataScript public provenance and graph"
       legacy verified
       #(selected-graph-identity
         (datascript-backend/snapshot-adapter
          (ds/db conn) (:opts legacy)))
       #(selected-graph-identity
         (datascript-backend/snapshot-adapter
          (ds/db conn) (:opts verified)))
       #(ds/transact! conn [{:db/doc "unrelated-shadow"}]))))

  (testing "Datahike"
    (let [conn (datahike/create-conn)
          common
          {:coherence-authority :managed
           :security-key
           "01234567890123456789012345678901"}
          legacy (datahike/make-client conn common)
          verified
          (datahike/make-client
           conn
           (assoc common :engine-selection engine-selection))]
      (eacl/write-schema! legacy authorization-schema)
      (dh/transact
       conn
       [{:eacl/id "user"}
        {:eacl/id "document-1"}
        {:eacl/id "document-2"}
        {:eacl/id "group"}])
      (assert-public-provenance-and-graph-shadow!
       "Datahike public provenance and graph"
       legacy verified
       #(selected-graph-identity
         (datahike-backend/snapshot-adapter
          (dh/db conn) (:opts legacy)))
       #(selected-graph-identity
         (datahike-backend/snapshot-adapter
          (dh/db conn) (:opts verified)))
       #(dh/transact conn [{:db/doc "unrelated-shadow"}]))))

  (testing "Datomic"
    (with-mem-conn [conn datomic-schema/v7-schema]
      (let [common
            {:coherence-authority :managed
             :cache {:remember-answers true}
             :page-token-key
             "01234567890123456789012345678901"
             :zed-token-key
             "12345678901234567890123456789012"}
            legacy (datomic/make-client conn common)
            verified
            (datomic/make-client
             conn
             (assoc common :engine-selection engine-selection))]
        (eacl/write-schema! legacy authorization-schema)
        @(d/transact
          conn
          [{:db/id (d/tempid :db.part/user)
            :eacl/id "user"}
           {:db/id (d/tempid :db.part/user)
            :eacl/id "document-1"}
           {:db/id (d/tempid :db.part/user)
            :eacl/id "document-2"}
           {:db/id (d/tempid :db.part/user)
            :eacl/id "group"}])
        (assert-public-provenance-and-graph-shadow!
         "Datomic public provenance and graph"
         legacy verified
         #(selected-graph-identity
           (datomic-backend/snapshot-adapter
            (d/db conn) (:opts legacy)))
         #(selected-graph-identity
           (datomic-backend/snapshot-adapter
            (d/db conn) (:opts verified)))
         #(deref
           (d/transact
            conn
            [{:db/id (d/tempid :db.part/user)
              :db/doc "unrelated-shadow"}])))))))

(defn- assert-recursive-shadow!
  [client limited-clients reports]
  (eacl/create-relationships!
   client [relationship-1 relationship-2])
  (let [query
        {:subject user
         :permission :view
         :resource/type :document
         :first 1}
        page-1 (eacl/lookup-resources client query)
        page-2
        (eacl/lookup-resources
         client
         (assoc query
                :after
                (get-in page-1 [:page-info :end-cursor])))
        reverse-page
        (eacl/lookup-subjects
         client
         {:resource document-2
          :permission :view
          :subject/type :user
          :first 1})
        limit-errors
        (into
         {}
         (map
          (fn [[limit-kind limited-client]]
            [limit-kind
             (try
               (eacl/count-resources
                limited-client
                (dissoc query :first))
               nil
               (catch Exception error
                 error))]))
         limited-clients)
        page-error
        (try
          (eacl/lookup-resources client (assoc query :first 0))
          nil
          (catch Exception error
            error))]
    (is (= ["document-1"] (ids page-1)))
    (is (= ["document-2"] (ids page-2)))
    (is (= ["user"] (ids reverse-page)))
    (is (= 2
           (:count
            (eacl/count-resources
             client
             (dissoc query :first)))))
    (is (= 1
           (:count
            (eacl/count-subjects
             client
             {:resource document-2
              :permission :view
              :subject/type :user}))))
    (is (true? (eacl/can? client user :view document-1)))
    (doseq [[limit-kind limit-error] limit-errors]
      (is (= :eacl.recursive-traversal/limit-exceeded
             (:eacl/error (ex-data limit-error))))
      (is (= limit-kind
             (:limit-kind (ex-data limit-error))))
      (is (= 1
             (:limit (ex-data limit-error)))))
    (is (= {:size 0} (ex-data page-error)))
    (is (empty? @reports)
        (str "generated recursive shadow divergence: "
             (pr-str @reports)))))

(deftest recursive-shadow-compares-complete-public-results
  (testing "DataScript"
    (let [conn (datascript/create-conn)
          reports (atom [])
          common
          {:cache shared-cache/no-cache
           :security-key
           "01234567890123456789012345678901"
           :engine-selection (shadow-selection reports)}
          client (datascript/make-client conn common)
          limited-clients
          (into
           {}
           (map
            (fn [[limit-kind limit-option]]
              [limit-kind
               (datascript/make-client
                conn
                (assoc common
                       :recursive-traversal-limits
                       {limit-option 1}))]))
           {:derived-grants :max-derived-grants
            :advanced-datoms :max-advanced-datoms
            :queued-work :max-queued-work})]
      (eacl/write-schema! client authorization-schema)
      (ds/transact!
       conn
       [{:eacl/id "user"}
        {:eacl/id "document-1"}
        {:eacl/id "document-2"}])
      (assert-recursive-shadow! client limited-clients reports)))

  (testing "Datahike"
    (let [conn (datahike/create-conn)
          reports (atom [])
          common
          {:cache shared-cache/no-cache
           :security-key
           "01234567890123456789012345678901"
           :engine-selection (shadow-selection reports)}
          client (datahike/make-client conn common)
          limited-clients
          (into
           {}
           (map
            (fn [[limit-kind limit-option]]
              [limit-kind
               (datahike/make-client
                conn
                (assoc common
                       :recursive-traversal-limits
                       {limit-option 1}))]))
           {:derived-grants :max-derived-grants
            :advanced-datoms :max-advanced-datoms
            :queued-work :max-queued-work})]
      (eacl/write-schema! client authorization-schema)
      (dh/transact
       conn
       [{:eacl/id "user"}
        {:eacl/id "document-1"}
        {:eacl/id "document-2"}])
      (assert-recursive-shadow! client limited-clients reports)))

  (testing "Datomic"
    (with-mem-conn [conn datomic-schema/v7-schema]
      (let [reports (atom [])
            common
            {:cache datomic-cache/no-cache
             :page-token-key
             "01234567890123456789012345678901"
             :zed-token-key
             "12345678901234567890123456789012"
             :engine-selection (shadow-selection reports)}
            client (datomic/make-client conn common)
            limited-clients
            (into
             {}
             (map
              (fn [[limit-kind limit-option]]
                [limit-kind
                 (datomic/make-client
                  conn
                  (assoc common
                         :recursive-traversal-limits
                         {limit-option 1}))]))
             {:derived-grants :max-derived-grants
              :advanced-datoms :max-advanced-datoms
              :queued-work :max-queued-work})]
        (eacl/write-schema! client authorization-schema)
        @(d/transact
          conn
          [{:db/id (d/tempid :db.part/user)
            :eacl/id "user"}
           {:db/id (d/tempid :db.part/user)
            :eacl/id "document-1"}
           {:db/id (d/tempid :db.part/user)
            :eacl/id "document-2"}])
        (assert-recursive-shadow! client limited-clients reports)))))

(deftest recursive-shadow-compares-stale-cursor-error-shape
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn
         {:cache shared-cache/no-cache
          :security-key
          "01234567890123456789012345678901"})
        reports (atom [])
        selection (shadow-selection reports)
        query
        {:subject user
         :permission :view
         :resource/type :document
         :first 1}]
    (eacl/write-schema! client authorization-schema)
    (ds/transact!
     conn
     [{:eacl/id "user"}
      {:eacl/id "document-1"}
      {:eacl/id "document-2"}])
    (eacl/create-relationships!
     client
     [relationship-1 relationship-2])
    (let [adapter
          (datascript-backend/snapshot-adapter
           (ds/db conn)
           (:opts client))
          page
          (binding [subproblem/*engine-selection* selection]
            (engine/lookup-resources adapter query))
          bound (get-in page [:page-info :end-cursor])]
      (eacl/delete-relationships! client [relationship-1])
      (let [changed-adapter
            (datascript-backend/snapshot-adapter
             (ds/db conn)
             (:opts client))
            error
            (try
              (binding [subproblem/*engine-selection* selection]
                (engine/lookup-resources
                 changed-adapter
                 (assoc query :after bound)))
              nil
              (catch Exception exception
                exception))]
        (is (= [(get-in bound [:result :eid])]
               (mapv :id (:data page))))
        (is (= :eacl.pagination/stale-cursor
               (:eacl/error (ex-data error))))
        (is (= {:eacl/error :eacl.pagination/stale-cursor}
               (ex-data error)))
        (is (empty? @reports)
            (str "generated stale-cursor shadow divergence: "
                 (pr-str @reports)))))))

(deftest recursive-shadow-queue-limit-is-query-local
  (let [conn (datascript/create-conn)
        reports (atom [])
        client
        (datascript/make-client
         conn
         {:cache shared-cache/no-cache
          :security-key
          "01234567890123456789012345678901"
          :engine-selection (shadow-selection reports)
          :recursive-traversal-limits
          {:max-queued-work 1}})
        subject (eacl/spice-object :user "u1")
        unrelated-subject (eacl/spice-object :team "t1")
        folder-0 (eacl/spice-object :folder "f0")
        folder-1 (eacl/spice-object :folder "f1")]
    (eacl/write-schema!
     client
     "definition user {}
      definition team {}
      definition folder {
        relation reader: user
        relation parent: folder
        relation team_reader: team
        permission read = reader + parent->read + team_reader
      }")
    (ds/transact!
     conn
     [{:eacl/id "u1"}
      {:eacl/id "t1"}
      {:eacl/id "f0"}
      {:eacl/id "f1"}])
    (eacl/create-relationships!
     client
     [(eacl/->Relationship subject :reader folder-0)
      (eacl/->Relationship folder-0 :parent folder-1)
      (eacl/->Relationship unrelated-subject :team_reader folder-1)])
    (let [result
          (eacl/count-resources
           client
           {:subject subject
            :permission :read
            :resource/type :folder
            :count-limit 10})]
      (is (= 2 (:count result)))
      (is (false? (:truncated? result)))
      (is (empty? @reports)
          (str "query-local queue-limit shadow divergence: "
               (pr-str @reports))))))
