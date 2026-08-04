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
   [eacl.cache :as shared-cache]
   [eacl.core :as eacl]
   [eacl.datahike.core :as datahike]
   [eacl.datascript.core :as datascript]
   [eacl.datomic.cache :as datomic-cache]
   [eacl.datomic.core :as datomic]
   [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
   [eacl.datomic.schema :as datomic-schema]
   [eacl.formal.differential-runner :as differential]
   [eacl.formal.production-kernel :as production]
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

(defn- assert-recursive-shadow!
  [client limited-client reports]
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
        limit-error
        (try
          (eacl/count-resources
           limited-client
           (dissoc query :first))
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
    (is (= :eacl.recursive-traversal/limit-exceeded
           (:eacl/error (ex-data limit-error))))
    (is (= :derived-grants
           (:limit-kind (ex-data limit-error))))
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
          limited-client
          (datascript/make-client
           conn
           (assoc common
                  :recursive-traversal-limits
                  {:max-derived-grants 1}))]
      (eacl/write-schema! client authorization-schema)
      (ds/transact!
       conn
       [{:eacl/id "user"}
        {:eacl/id "document-1"}
        {:eacl/id "document-2"}])
      (assert-recursive-shadow! client limited-client reports)))

  (testing "Datahike"
    (let [conn (datahike/create-conn)
          reports (atom [])
          common
          {:cache shared-cache/no-cache
           :security-key
           "01234567890123456789012345678901"
           :engine-selection (shadow-selection reports)}
          client (datahike/make-client conn common)
          limited-client
          (datahike/make-client
           conn
           (assoc common
                  :recursive-traversal-limits
                  {:max-derived-grants 1}))]
      (eacl/write-schema! client authorization-schema)
      (dh/transact
       conn
       [{:eacl/id "user"}
        {:eacl/id "document-1"}
        {:eacl/id "document-2"}])
      (assert-recursive-shadow! client limited-client reports)))

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
            limited-client
            (datomic/make-client
             conn
             (assoc common
                    :recursive-traversal-limits
                    {:max-derived-grants 1}))]
        (eacl/write-schema! client authorization-schema)
        @(d/transact
          conn
          [{:db/id (d/tempid :db.part/user)
            :eacl/id "user"}
           {:db/id (d/tempid :db.part/user)
            :eacl/id "document-1"}
           {:db/id (d/tempid :db.part/user)
            :eacl/id "document-2"}])
        (assert-recursive-shadow! client limited-client reports)))))
