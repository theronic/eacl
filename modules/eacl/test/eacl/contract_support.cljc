(ns eacl.contract-support
  #?(:cljs (:require-macros [eacl.core :as eacl]))
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [clojure.string :as str]
            [eacl.authorization-oracle :as oracle]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-route :as stable-route]
            [eacl.request.counters :as request-counters]
            [eacl.spicedb.consistency :as consistency]))

(def ->user (partial eacl/spice-object :user))
(def ->platform (partial eacl/spice-object :platform))
(def ->account (partial eacl/spice-object :account))
(def ->server (partial eacl/spice-object :server))

(defn portable-store
  "Returns the explicit default private-cache configuration used by shared
  contract tests. Application-supplied cache stores are deliberately not part
  of the public client contract."
  []
  {})

(def smoke-schema
  "definition user {}

   definition platform {
     relation super_admin: user
   }

   definition account {
     relation platform: platform
     relation owner: user

     permission admin = owner + platform->super_admin
     permission view = admin
   }

   definition server {
     relation account: account

     permission view = account->view
     permission reboot = account->admin
   }")

(def smoke-objects
  [(->user "user-1")
   (->user "user-2")
   (->user "super-user")
   (->platform "platform-1")
   (->account "account-1")
   (->server "server-1")
   (->server "server-2")])

(def smoke-relationships
  [(eacl/->Relationship (->user "user-1") :owner (->account "account-1"))
   (eacl/->Relationship (->user "super-user") :super_admin (->platform "platform-1"))
   (eacl/->Relationship (->platform "platform-1") :platform (->account "account-1"))
   (eacl/->Relationship (->account "account-1") :account (->server "server-1"))
   (eacl/->Relationship (->account "account-1") :account (->server "server-2"))])

(def boundary-counter-keys
  "Independent source/adapter/writer lifecycle counters asserted by shared
  backend contracts."
  [:acquisitions :adapter-reads :writer-submissions :releases])

(defn boundary-counts
  [ledger]
  (select-keys (request-counters/snapshot ledger) boundary-counter-keys))

(declare normalize-permission-tree)

(defn assert-authorization-target-matrix!
  "Runs every public read over writable/read-only acls plus captured, selected,
  and direct snapshots. `snapshot-db` and `direct-snapshot` are the backend's
  native accessor and direct constructor."
  [{:keys [writable read-only snapshot-db direct-snapshot]}]
  (let [captured (eacl/snapshot writable)
        selected
        (eacl/snapshot writable consistency/minimize-latency)
        direct (direct-snapshot writable (snapshot-db captured))
        targets
        [[:writable-acl writable]
         [:read-only-acl read-only]
         [:captured-snapshot captured]
         [:selected-snapshot selected]
         [:direct-snapshot direct]]
        subject (->user "user-1")
        resource (->server "server-1")
        demand {:subject subject :permission :view :resource resource}
        operations
        [[:can?
          #(eacl/can? % demand)
          identity]
         [:check-permission
          #(eacl/check-permission % demand)
          :allowed?]
         [:check-permissions
          #(eacl/check-permissions % {:checks [demand demand]})
          (fn [decisions] (mapv :allowed? decisions))]
         [:read-schema
          #(eacl/read-schema %)
          identity]
         [:read-relationships
          #(eacl/read-relationships
            % {:resource/type :server :first 100})
          :data]
         [:lookup-resources
          #(eacl/lookup-resources
            % {:subject subject
               :permission :view
               :resource/type :server
               :first 100})
          :data]
         [:lookup-subjects
          #(eacl/lookup-subjects
            % {:resource resource
               :permission :view
               :subject/type :user
               :first 100})
          :data]
         [:count-resources
          #(eacl/count-resources
            % {:subject subject
               :permission :view
               :resource/type :server})
          (fn [result]
            (select-keys result [:count :limit :truncated?]))]
         [:count-subjects
          #(eacl/count-subjects
            % {:resource resource
               :permission :view
               :subject/type :user})
          (fn [result]
            (select-keys result [:count :limit :truncated?]))]
         [:expand-permission-tree
          #(eacl/expand-permission-tree
            % {:resource resource :permission :view})
          (fn [result]
            (normalize-permission-tree (:tree-root result)))]]]
    (try
      (is (every? eacl/acl? [writable read-only]))
      (is (every? eacl/snapshot? [captured selected direct]))
      (is (identical? (:runtime captured) (:runtime selected)))
      (is (identical? (:runtime captured) (:runtime direct)))
      (doseq [[operation call project] operations]
        (testing (name operation)
          (let [observed
                (mapv
                 (fn [[target-kind target]]
                   [target-kind (project (call target))])
                 targets)]
            (is (apply = (map second observed))
                (pr-str observed)))))
      (finally
        (eacl/release! direct)
        (eacl/release! selected)
        (eacl/release! captured)))))

(defn assert-public-api-arity-contract!
  "Pins every public authorization read/write entry point and convenience arity.

  The fixture must already contain `smoke-schema`, `smoke-objects`, and
  `smoke-relationships`. Mutations use user-2's otherwise-empty owner edge and
  restore that edge after every assertion, so callers may continue using the
  seeded fixture afterwards."
  [client]
  (let [subject (->user "user-1")
        denied (->user "user-2")
        account (->account "account-1")
        resource (->server "server-1")
        relationship (eacl/->Relationship denied :owner account)
        demand {:subject subject
                :permission :view
                :resource resource}
        resource-query {:subject subject
                        :permission :view
                        :resource/type :server
                        :first 2}
        subject-query {:resource resource
                       :permission :view
                       :subject/type :user
                       :first 2}
        relationship-query {:resource/type :account
                            :resource/id "account-1"
                            :resource/relation :owner
                            :subject/type :user
                            :subject/id "user-2"
                            :first 2}
        consistency consistency/minimize-latency]
    (testing "every public read and convenience arity preserves its response"
      (is (every? true?
                  [(eacl/can? client subject :view resource)
                   (eacl/can? client subject :view resource consistency)
                   (eacl/can? client demand)]))
      (is (every? true?
                  (map :allowed?
                       [(eacl/check-permission client demand)
                        (eacl/check-permission client subject :view resource)
                        (eacl/check-permission
                         client subject :view resource consistency)])))
      (is (= 3
             (count
              (eacl/check-permissions
               client
               {:checks [demand demand (assoc demand :subject denied)]}))))
      (is (map? (eacl/read-schema client)))
      (is (map? (eacl/read-relationships client relationship-query)))
      (is (map? (eacl/lookup-resources client resource-query)))
      (is (map? (eacl/count-resources client (dissoc resource-query :first))))
      (is (map? (eacl/lookup-subjects client subject-query)))
      (is (map? (eacl/count-subjects client (dissoc subject-query :first))))
      (is (map? (eacl/expand-permission-tree
                 client {:resource resource :permission :view}))))

    (testing "every public mutation and convenience arity remains callable"
      (is (map? (eacl/write-schema! client smoke-schema)))

      (is (map? (eacl/write-relationships!
                 client [(eacl/->RelationshipUpdate :touch relationship)])))
      (is (map? (eacl/delete-relationship!
                 client denied :owner account)))

      (is (map? (eacl/write-relationship!
                 client :create denied :owner account)))
      (is (map? (eacl/delete-relationship! client relationship)))

      (is (map? (eacl/write-relationship!
                 client {:operation :create
                         :subject denied
                         :relation :owner
                         :resource account})))
      (is (map? (eacl/delete-relationship!
                 client denied :owner account)))

      (is (map? (eacl/create-relationship!
                 client denied :owner account)))
      (is (map? (eacl/delete-relationship! client relationship)))

      (is (map? (eacl/create-relationship! client relationship)))
      (is (map? (eacl/delete-relationship!
                 client denied :owner account)))

      (is (map? (eacl/create-relationships! client [relationship])))
      (is (map? (eacl/delete-relationships! client [relationship])))

      (is (map? (eacl/create-relationships! client [relationship])))
      (let [page (eacl/read-relationships client relationship-query)]
        (is (= [relationship] (:data page)))
        (is (map? (eacl/delete-relationships! client page))))

      (is (map? (eacl/create-relationship! client relationship)))
      (let [response (eacl/delete-object! client denied)]
        (is (map? response))
        (is (pos? (:retracted-datoms response))))
      (is (false? (eacl/can? client denied :reboot resource))))))

(def batch-property-seed
  "Portable seed retained with the cross-runtime batch oracle fixture."
  424242)

(def ^:private batch-oracle-demands
  [{:subject (->user "user-1")
    :permission :reboot
    :resource (->server "server-1")}
   {:subject (->user "super-user")
    :permission :reboot
    :resource (->server "server-2")}
   {:subject (->user "user-2")
    :permission :reboot
    :resource (->server "server-1")}
   {:subject (->user "missing-user")
    :permission :reboot
    :resource (->server "server-1")}
   {:subject (->user "user-1")
    :permission :view
    :resource (->account "account-1")}
   {:subject (->user "user-2")
    :permission :admin
    :resource (->account "account-1")}
   {:subject (->user "super-user")
    :permission :admin
    :resource (->account "account-1")}
   {:subject (->user "user-1")
    :permission :view
    :resource (->server "missing-server")}])

(defn- next-portable-seed
  [seed]
  ;; The product stays below 2^53, making this LCG identical on JVM longs and
  ;; JavaScript numbers for every retained step.
  (mod (* 48271 seed) 2147483647))

(defn- seeded-batch-vectors
  [seed case-count demands]
  (loop [state seed
         remaining case-count
         batches []]
    (if (zero? remaining)
      batches
      (let [state (next-portable-seed state)
            size (mod state 13)
            [state checks]
            (loop [state state
                   index 0
                   checks []]
              (if (= index size)
                [state checks]
                (let [state (next-portable-seed state)]
                  (recur state
                         (inc index)
                         (conj checks
                               (nth demands
                                    (mod state (count demands))))))))]
        (recur state (dec remaining) (conj batches checks))))))

(defn- assert-one-batch-oracle-case!
  [authorization checks evaluation]
  (let [request {:checks checks
                 :cache? false
                 :evaluation evaluation}
        oracle
        (mapv
         #(eacl/check-permission
           authorization
           (assoc % :cache? false :evaluation evaluation))
         checks)
        actual (eacl/check-permissions authorization request)]
    (is (= oracle actual)
        (str "seed=" batch-property-seed
             " evaluation=" evaluation
             " checks=" (pr-str checks)))))

(defn assert-v8-batch-contract!
  "Seeded ordered-scalar differential contract shared by every backend and
  the CLJS runner. Snapshot-composable clients execute each generated case
  against one retained view; Datomic's immutable current DB values use the
  same unchanged native revision for the scalar and batch arms."
  [client]
  (testing (str "seeded batch scalar oracle, seed=" batch-property-seed)
    (doseq [[index checks]
            (map-indexed vector
                         (seeded-batch-vectors
                          batch-property-seed 24 batch-oracle-demands))]
      (let [evaluation (if (even? index)
                         :demand
                         :complete-denotation)]
        (if (eacl/acl? client)
          (eacl/with-snapshot [snapshot (eacl/snapshot client)]
            (assert-one-batch-oracle-case!
             snapshot checks evaluation))
          (assert-one-batch-oracle-case!
           client checks evaluation))))))

(defn- walk-pages
  [read-page query]
  (loop [query query
         pages []]
    (let [page (read-page query)
          pages (conj pages page)]
      (if (get-in page [:page-info :has-next-page?])
        (recur (assoc query :after (get-in page [:page-info :end-cursor]))
               pages)
        pages))))

(defn- relationship-resource-ids
  [pages]
  (mapv #(get-in % [:resource :id]) (into [] cat (map :data pages))))

(defn- object-ids
  [pages]
  (mapv :id (into [] cat (map :data pages))))

(defn assert-v8-aggregate-pagination-contract!
  "Backend-neutral batch, scan, and enumerate-route conformance over the
  shared smoke fixture. The page oracle is the stable physical stream filtered
  by scalar authorization and direct relationship membership on one snapshot."
  [client]
  (let [subject (->user "user-1")
        denied (->user "user-2")
        account (->account "account-1")
        missing-account (->account "missing-account")
        scan-query
        {:subject/type :account
         :subject/id "account-1"
         :resource/type :server
         :resource/relation :account
         :authorization {:subject subject
                         :permission :view
                         :on :resource}
         :first 1
         :aggregate-limits {:candidate-window 1}}
        enumerate-query
        {:subject subject
         :permission :view
         :resource/type :server
         :resource/relationship {:relation :account :subject account}
         :first 1
         :aggregate-limits {:candidate-window 1}}
        expected ["server-1" "server-2"]]
    (testing "scan and enumerate routes refine the same filter-then-window oracle"
      (let [scan-pages
            (walk-pages #(eacl/read-relationships client %) scan-query)
            enumerate-pages
            (walk-pages #(eacl/lookup-resources client %) enumerate-query)]
        (is (= expected (relationship-resource-ids scan-pages)))
        (is (= expected (object-ids enumerate-pages)))
        (is (= (set (relationship-resource-ids scan-pages))
               (set (object-ids enumerate-pages))))
        (is (= [true false]
               (mapv #(get-in % [:page-info :bounded?]) scan-pages)))
        (is (= [true false]
               (mapv #(get-in % [:page-info :bounded?]) enumerate-pages)))))

    (testing "all-rejected windows progress without converting work bounds to denial"
      (let [scan-pages
            (walk-pages
             #(eacl/read-relationships client %)
             (assoc-in scan-query [:authorization :subject] denied))
            enumerate-pages
            (walk-pages
             #(eacl/lookup-resources client %)
             (assoc-in enumerate-query
                       [:resource/relationship :subject]
                       missing-account))]
        (is (empty? (relationship-resource-ids scan-pages)))
        (is (empty? (object-ids enumerate-pages)))
        (is (= [true false]
               (mapv #(get-in % [:page-info :bounded?]) scan-pages)))
        (is (= [true false]
               (mapv #(get-in % [:page-info :bounded?]) enumerate-pages)))))

    (testing "enumerate performs exactly one relationship probe per candidate"
      (let [ledger (request-counters/make-ledger)
            page
            (binding [request-counters/*ledger* ledger]
              (eacl/lookup-resources
               client
               (assoc enumerate-query
                      :aggregate-limits {:candidate-window 3})))
            counters (request-counters/snapshot ledger)]
        (is (= ["server-1"] (mapv :id (:data page))))
        (is (= 2 (:candidates-examined counters)))
        (is (= (:candidates-examined counters) (:probes counters)))
        (is (= 1 (:acquisitions counters)))
        (is (= 1 (:context-constructions counters)))
        (is (= 1 (:public-entries counters)))))

    (testing "backward pages use the same sentinel, budget, and cursor scope"
      (let [backward
            (-> enumerate-query
                (dissoc :first)
                (assoc :last 1 :evaluation :complete-denotation))
            page-2 (eacl/lookup-resources client backward)
            page-1
            (eacl/lookup-resources
             client
             (assoc backward
                    :before (get-in page-2 [:page-info :start-cursor])))]
        (is (= ["server-2"] (mapv :id (:data page-2))))
        (is (= ["server-1"] (mapv :id (:data page-1))))))

    (when (eacl/acl? client)
      (testing "a composed read-only snapshot view preserves every aggregate route"
        (eacl/with-snapshot [snapshot (eacl/snapshot client)]
           (is (= expected
                  (relationship-resource-ids
                   (walk-pages #(eacl/read-relationships snapshot %)
                               scan-query))))
           (is (= expected
                  (object-ids
                   (walk-pages #(eacl/lookup-resources snapshot %)
                               enumerate-query))))
           (let [checks [{:subject subject
                          :permission :view
                          :resource (->server "server-1")}
                         {:subject denied
                          :permission :view
                          :resource (->server "server-1")}]]
             (is (= (mapv #(eacl/check-permission
                            snapshot (assoc % :cache? false))
                          checks)
                    (eacl/check-permissions
                     snapshot {:checks checks :cache? false})))))))))

(def plan-invalidation-schema
  "definition user {}

   definition platform {
     relation super_admin: user
   }

   definition account {
     relation platform: platform
     relation owner: user

     permission admin = platform->super_admin
     permission view = admin
   }

   definition server {
     relation account: account

     permission view = account->view
     permission reboot = account->admin
   }")

(defn- definition-read-count
  [stats]
  (+ (get stats :permission-defs 0)
     (get stats :relation-defs 0)))

(defn- uncached-reboot-decision
  [client]
  (:allowed?
   (eacl/check-permission
    client
    {:subject (->user "user-2")
     :permission :reboot
     :resource (->server "server-1")
     :cache? false})))

(defn assert-certified-generation-plan-reuse!
  "Checks the real public path's plan reuse and schema invalidation contract.

  `client` must be freshly seeded with `smoke-schema`, `smoke-objects`, and
  `smoke-relationships`; no authorization read may have run yet."
  [client]
  (let [original-seal sealed-plan/seal-plan
        original-check stable-route/check-eids
        seals (atom 0)
        evaluated-plans (atom [])
        first-stats (atom {})
        advanced-stats (atom {})
        repeated-stats (atom {})
        changed-stats (atom {})]
    (with-redefs
      [sealed-plan/seal-plan
       (fn [& args]
         (swap! seals inc)
         (apply original-seal args))
       stable-route/check-eids
       (fn [options]
         (swap! evaluated-plans conj (:plan options))
         (original-check options))]
      (is (false?
           (binding [backend/*backend-op-stats* first-stats]
             (uncached-reboot-decision client))))
      (is (pos? (definition-read-count @first-stats)))
      (is (= 1 @seals))

      ;; This advances the native basis without changing the certified schema
      ;; generation. The decision changes because relationship data changed,
      ;; while the evaluator must receive the exact same immutable plan.
      (eacl/create-relationship!
       client (->user "user-2") :owner (->account "account-1"))
      (is (true?
           (binding [backend/*backend-op-stats* advanced-stats]
             (uncached-reboot-decision client))))
      (is (zero? (definition-read-count @advanced-stats)))
      (is (= 1 @seals))
      (is (identical? (nth @evaluated-plans 0)
                      (nth @evaluated-plans 1))
          "two native bases of one certified generation use one plan instance")

      ;; A second identical request performs neither a definition read nor a
      ;; seal, and reaches the same plan instance again.
      (is (true?
           (binding [backend/*backend-op-stats* repeated-stats]
             (uncached-reboot-decision client))))
      (is (zero? (definition-read-count @repeated-stats)))
      (is (= 1 @seals))
      (is (identical? (nth @evaluated-plans 1)
                      (nth @evaluated-plans 2)))

      ;; A managed schema write advances the certified generation. Reusing the
      ;; old plan would incorrectly preserve the owner grant, so the denial
      ;; also kills the stale-plan mutation.
      (eacl/write-schema! client plan-invalidation-schema)
      (is (false?
           (binding [backend/*backend-op-stats* changed-stats]
             (uncached-reboot-decision client))))
      (is (pos? (definition-read-count @changed-stats)))
      (is (= 2 @seals))
      (is (not (identical? (nth @evaluated-plans 2)
                           (nth @evaluated-plans 3)))
          "a managed schema write installs a new plan instance"))))

(def permission-tree-golden-schema
  "definition user {}
   definition folder {
     relation viewer: user
   }
   definition document {
     relation folder: folder
     permission view = folder->viewer
   }")

(def permission-tree-golden-objects
  [(eacl/spice-object :user "fred")
   (eacl/spice-object :user "tom")
   (eacl/spice-object :user "sarah")
   (eacl/spice-object :folder "testfolder1")
   (eacl/spice-object :folder "testfolder2")
   (eacl/spice-object :document "testdoc")])

(def permission-tree-golden-relationships
  [(eacl/->Relationship
    (eacl/spice-object :folder "testfolder1") :folder
    (eacl/spice-object :document "testdoc"))
   (eacl/->Relationship
    (eacl/spice-object :folder "testfolder2") :folder
    (eacl/spice-object :document "testdoc"))
   (eacl/->Relationship
    (eacl/spice-object :user "tom") :viewer
    (eacl/spice-object :folder "testfolder1"))
   (eacl/->Relationship
    (eacl/spice-object :user "fred") :viewer
    (eacl/spice-object :folder "testfolder1"))
   (eacl/->Relationship
    (eacl/spice-object :user "sarah") :viewer
    (eacl/spice-object :folder "testfolder2"))])

(defn- normalize-permission-tree
  [tree]
  (if-let [leaf (:leaf tree)]
    (assoc tree :leaf
           (update leaf :subjects #(vec (sort-by pr-str %))))
    (assoc-in tree [:intermediate :children]
              (->> (get-in tree [:intermediate :children])
                   (map normalize-permission-tree)
                   (sort-by pr-str)
                   vec))))

(defn assert-pinned-permission-tree-golden!
  [client]
  (let [expected
        {:expanded-object (eacl/spice-object :document "testdoc")
         :expanded-relation :view
         :intermediate
         {:operation :union
          :children
          [{:expanded-object (eacl/spice-object :document "testdoc")
            :expanded-relation :view
            :intermediate
            {:operation :union
             :children
             [{:expanded-object
               (eacl/spice-object :folder "testfolder1")
               :expanded-relation :viewer
               :leaf
               {:subjects [(eacl/spice-object :user "fred")
                           (eacl/spice-object :user "tom")]}}
              {:expanded-object
               (eacl/spice-object :folder "testfolder2")
               :expanded-relation :viewer
               :leaf
               {:subjects [(eacl/spice-object :user "sarah")]}}]}}]}}
        actual
        (:tree-root
         (eacl/expand-permission-tree
          client
          {:resource (eacl/spice-object :document "testdoc")
           :permission :view}))]
    (is (= (normalize-permission-tree expected)
           (normalize-permission-tree actual)))))

(def safe-retraction-schema
  "definition user {
     relation peer: user
   }
   definition account {
     relation owner: user
   }
   definition server {
     relation account: account
   }
   definition folder {
     relation parent: folder
   }")

(def safe-retraction-objects
  [(eacl/spice-object :user "user-1")
   (eacl/spice-object :user "user-2")
   (eacl/spice-object :user "unrelated-user")
   (eacl/spice-object :account "target-account")
   (eacl/spice-object :account "unrelated-account")
   (eacl/spice-object :server "server-1")
   (eacl/spice-object :folder "self-folder")])

(def safe-retraction-relationships
  [(eacl/->Relationship (eacl/spice-object :user "user-1")
                        :owner
                        (eacl/spice-object :account "target-account"))
   (eacl/->Relationship (eacl/spice-object :account "target-account")
                        :account
                        (eacl/spice-object :server "server-1"))
   (eacl/->Relationship (eacl/spice-object :user "user-1")
                        :peer
                        (eacl/spice-object :user "user-2"))
   (eacl/->Relationship (eacl/spice-object :folder "self-folder")
                        :parent
                        (eacl/spice-object :folder "self-folder"))
   (eacl/->Relationship (eacl/spice-object :user "unrelated-user")
                        :owner
                        (eacl/spice-object :account "unrelated-account"))])

(def safe-retraction-target
  (eacl/spice-object :account "target-account"))

(def safe-retraction-expected-remaining
  (set (drop 2 safe-retraction-relationships)))

(defn assert-safe-retraction-result!
  [{:keys [target-exists? remaining-relationships unresolved-no-op?
           existing-ghost-preserved?]}]
  (is (false? target-exists?) "the target entity is retracted")
  (is (= safe-retraction-expected-remaining
         (set remaining-relationships))
      "all and only relationships touching the target are removed")
  (is (true? unresolved-no-op?)
      "unresolved eid/lookup-ref invocation is a no-op")
  (is (true? existing-ghost-preserved?)
      "bounded safe retraction does not scan for a pre-existing peer-only ghost"))


(defn- read-relationships-data
  [client query]
  (:data (eacl/read-relationships client query)))

(defn- actual-authorization-set
  [client objects rules]
  (into
   #{}
   (for [[[resource-type permission]] rules
         resource objects
         :when (= resource-type (:type resource))
         subject objects
         :when (eacl/can? client subject permission resource)]
     [subject permission resource])))

(defn- assert-authorization-oracle!
  [client fixture]
  (let [expected (oracle/authorization-set fixture)
        actual (actual-authorization-set client (:objects fixture) (:rules fixture))]
    (is (= expected actual)
        (str "authorization oracle mismatch; seed=" oracle/fixture-seed
             " fixture=" (pr-str fixture)))))

(defn- error-category
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (let [data (ex-data error)]
        (or (:eacl/error data) (:type data))))))

(defn- read-relationships-error-data
  [client filters]
  (try
    (eacl/read-relationships client filters)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(defn assert-unified-filter-validation!
  "The unified read-relationships filter/error contract
  (backend-unification 9.1). Value-presence anchor semantics: an anchor key
  present with a nil value throws `:eacl.filters/missing-anchor` naming it
  in `:nil-anchor-keys` — nil type/relation filters never widen to
  match-everything wildcards — and pagination/unknown keys classify
  identically on every backend. Call with a client whose schema defines the
  smoke `:server` resource type."
  [client]
  (testing "nil id anchor throws instead of scanning or reading empty"
    (let [data (read-relationships-error-data
                client {:subject/id nil :first 5})]
      (is (= :eacl.filters/missing-anchor (:eacl/error data)))
      (is (= [:subject/id] (:nil-anchor-keys data)))))
  (testing "nil type anchor throws instead of wildcarding every relation"
    (let [data (read-relationships-error-data
                client {:resource/type nil :first 5})]
      (is (= :eacl.filters/missing-anchor (:eacl/error data)))
      (is (= [:resource/type] (:nil-anchor-keys data)))))
  (testing "nil relation filter throws even beside a valid anchor"
    (let [data (read-relationships-error-data
                client {:resource/type :server
                        :resource/relation nil
                        :first 5})]
      (is (= :eacl.filters/missing-anchor (:eacl/error data)))
      (is (= [:resource/relation] (:nil-anchor-keys data)))))
  (testing "nil id filter throws even beside a valid type anchor"
    (let [data (read-relationships-error-data
                client {:subject/type :user
                        :subject/id nil
                        :first 5})]
      (is (= :eacl.filters/missing-anchor (:eacl/error data)))
      (is (= [:subject/id] (:nil-anchor-keys data)))))
  (testing "a concrete subject id requires its subject type"
    (let [data (read-relationships-error-data
                client {:subject/id "user-1" :first 5})]
      (is (= :eacl.filters/missing-subject-type (:eacl/error data)))
      (is (= :subject/id (:filter data)))
      (is (= :subject/type (:required-filter data)))))
  (testing "an anchorless read names no nil keys but still fails closed"
    (let [data (read-relationships-error-data client {})]
      (is (= :eacl.filters/missing-anchor (:eacl/error data)))
      (is (= [] (:nil-anchor-keys data)))))
  (testing "v6-era pagination options classify identically on every backend"
    (is (= :eacl.pagination/unsupported-filter
           (:eacl/error
            (read-relationships-error-data
             client {:resource/type :server :limit 5}))))
    (is (= :eacl.pagination/unsupported-filter
           (:eacl/error
            (read-relationships-error-data
             client {:resource/type :server :cursor "opaque"})))))
  (testing "unknown keys fail loudly with the shared classification"
    (let [data (read-relationships-error-data
                client {:resource/type :server
                        :resouce/id "typo"
                        :first 5})]
      (is (= :eacl.filters/unknown-filter (:eacl/error data)))
      (is (= [:resouce/id] (:unknown-keys data)))))
  (testing "a valid anchored read still succeeds"
    (is (vector?
         (:data (eacl/read-relationships
                 client {:resource/type :server :first 5}))))))

(defn assert-seeded-contracts!
  [client]
  (testing "schema round-trips through the logical representation"
    (let [{:keys [relations permissions]} (eacl/read-schema client)]
      (is (= 4 (count relations)))
      (is (= 5 (count permissions)))))

  (testing "permission checks traverse direct and arrow relations"
    (is (true? (eacl/can? client (->user "user-1") :reboot (->server "server-1"))))
    (is (true? (eacl/can? client (->user "super-user") :reboot (->server "server-2"))))
    (is (false? (eacl/can? client (->user "user-2") :reboot (->server "server-1"))))
    (is (false? (eacl/can? client (->user "missing-user") :reboot (->server "server-1")))))

  (testing "unknown lookup anchors return canonical empty pages"
    (let [forward-query {:subject       (->user "missing-user")
                         :permission    :view
                         :resource/type :server
                         :limit         100}
          reverse-query {:resource     (->server "missing-server")
                         :permission   :view
                         :subject/type :user
                         :limit        100}]
      (is (= {:data [] :cursor nil}
             (eacl/lookup-resources client forward-query)))
      (is (= {:count 0 :limit 100 :cursor nil}
             (eacl/count-resources client forward-query)))
      (is (= {:data [] :cursor nil}
             (eacl/lookup-subjects client reverse-query)))
      (is (= {:count 0 :limit 100 :cursor nil}
             (eacl/count-subjects client reverse-query)))))

  (testing "lookup-resources and count-resources share cursor semantics"
    (let [{page1-data :data page1-cursor :cursor}
          (eacl/lookup-resources client {:subject       (->user "user-1")
                                         :permission    :view
                                         :resource/type :server
                                         :limit         1})
          {page2-data :data}
          (eacl/lookup-resources client {:subject       (->user "user-1")
                                         :permission    :view
                                         :resource/type :server
                                         :limit         1
                                         :cursor        page1-cursor})
          {count :count count-cursor :cursor}
          (eacl/count-resources client {:subject       (->user "user-1")
                                        :permission    :view
                                        :resource/type :server
                                        :limit         1})]
      (is (= [(->server "server-1")] page1-data))
      (is (= [(->server "server-2")] page2-data))
      (is (= 1 count))
      (is (string? page1-cursor))
      (is (string? count-cursor))))

  (testing "lookup-subjects and count-subjects enumerate reverse access"
    (let [subjects (->> (eacl/lookup-subjects client {:resource     (->server "server-1")
                                                      :permission   :reboot
                                                      :subject/type :user})
                        :data
                        set)
          {count :count cursor :cursor}
          (eacl/count-subjects client {:resource     (->server "server-1")
                                       :permission   :reboot
                                       :subject/type :user
                                       :limit        1})]
      (is (= #{(->user "user-1") (->user "super-user")} subjects))
      (is (= 1 count))
      (is (string? cursor))))

  (testing "relationship writes and reads remain part of the contract"
    (let [{initial-data :data initial-cursor :cursor}
          (eacl/read-relationships client {:resource/type     :account
                                           :resource/id       "account-1"
                                           :resource/relation :owner
                                           :subject/type      :user
                                           :subject/id        "user-1"})]
      (is (= [(eacl/->Relationship (->user "user-1") :owner (->account "account-1"))]
             initial-data))
      (is (string? initial-cursor)))

    (let [{page-1-data :data page-1-cursor :cursor}
          (eacl/read-relationships client {:subject/type      :account
                                           :subject/id        "account-1"
                                           :resource/type     :server
                                           :resource/relation :account
                                           :limit             1})
          {page-2-data :data}
          (eacl/read-relationships client {:subject/type      :account
                                           :subject/id        "account-1"
                                           :resource/type     :server
                                           :resource/relation :account
                                           :limit             1
                                           :cursor            page-1-cursor})]
      (is (= [(eacl/->Relationship (->account "account-1") :account (->server "server-1"))]
             page-1-data))
      (is (= [(eacl/->Relationship (->account "account-1") :account (->server "server-2"))]
             page-2-data))
      (is (string? page-1-cursor)))

    (eacl/create-relationship! client (->user "user-2") :owner (->account "account-1"))
    (is (true? (eacl/can? client (->user "user-2") :reboot (->server "server-1"))))
    (let [read-result (eacl/read-relationships client {:resource/type     :account
                                                       :resource/id       "account-1"
                                                       :resource/relation :owner
                                                       :subject/type      :user
                                                       :subject/id        "user-2"})]
      (is (= [(eacl/->Relationship (->user "user-2") :owner (->account "account-1"))]
             (:data read-result)))
      (eacl/delete-relationships! client read-result))
    (is (= [] (read-relationships-data client {:resource/type     :account
                                               :resource/id       "account-1"
                                               :resource/relation :owner
                                               :subject/type      :user
                                               :subject/id        "user-2"})))
    (is (false? (eacl/can? client (->user "user-2") :reboot (->server "server-1"))))))

(defn assert-v8-seeded-contracts!
  "The shared contract expressed through the v8 Relay-style pagination API.
  The legacy contract above remains stable for the existing DataScript and
  Datahike adapters while they adopt the v8 pagination surface."
  [client]
  (assert-public-api-arity-contract! client)

  (testing "authorization results match the independent curated oracle"
    (assert-authorization-oracle!
     client
     {:objects smoke-objects
      :relationships smoke-relationships
      :rules oracle/smoke-rules}))

  (assert-v8-batch-contract! client)
  (assert-v8-aggregate-pagination-contract! client)

  (testing "schema round-trips through the logical representation"
    (let [{:keys [relations permissions]} (eacl/read-schema client)]
      (is (= 4 (count relations)))
      (is (= 5 (count permissions)))))

  (testing "permission checks traverse direct and arrow relations"
    (is (true? (eacl/can? client (->user "user-1") :reboot (->server "server-1"))))
    (is (true? (eacl/can? client (->user "super-user") :reboot (->server "server-2"))))
    (is (false? (eacl/can? client (->user "user-2") :reboot (->server "server-1"))))
    (is (false? (eacl/can? client (->user "missing-user") :reboot (->server "server-1")))))

  (testing "lookup-resources and count-resources share v8 behavior"
    (let [query {:subject       (->user "user-1")
                 :permission    :view
                 :resource/type :server
                 :first         1}
          page-1 (eacl/lookup-resources client query)
          page-2 (eacl/lookup-resources
                  client
                  (assoc query :after (get-in page-1 [:page-info :end-cursor])))
          page-1-hit (eacl/lookup-resources client query)
          previous (eacl/lookup-resources
                    client
                    (-> query
                        (dissoc :first :after)
                        (assoc
                         :last 1
                         :before
                         (get-in page-2 [:page-info :start-cursor]))))
          count-result
          (eacl/count-resources client
                                {:subject       (->user "user-1")
                                 :permission    :view
                                 :resource/type :server})]
      (is (= [(->server "server-1")] (:data page-1)))
      (is (= [(->server "server-2")] (:data page-2)))
      (is (= (:data page-1) (:data previous)))
      (is (string? (get-in page-1 [:page-info :end-cursor])))
      (is (= 2 (:count count-result)))
      (when (contains? page-1 :cached?)
        (is (boolean? (:cached? page-1)))
        (is (boolean? (:cached? page-1-hit))))))

  (testing "opaque cursors reject malformed data and a changed query scope"
    (let [query {:subject (->user "user-1")
                 :permission :view
                 :resource/type :server
                 :first 1}
          cursor (get-in (eacl/lookup-resources client query)
                         [:page-info :end-cursor])]
      (is (= :eacl.pagination/invalid-cursor
             (error-category
              #(eacl/lookup-resources
                client
                (assoc query :after "not-an-eacl-cursor")))))
      (is (= :eacl.pagination/invalid-cursor
             (error-category
              #(eacl/lookup-resources
                client
                (assoc query
                       :subject (->user "user-2")
                       :after cursor)))))))

  (testing "cancellation tokens are execution controls, not cursor identity"
    (let [first-token (eacl/cancellation-token)
          next-token (eacl/cancellation-token)
          query {:subject (->user "user-1")
                 :permission :view
                 :resource/type :server
                 :first 1}
          page-1
          (eacl/lookup-resources
           client (assoc query :cancellation-token first-token))
          page-2
          (eacl/lookup-resources
           client
           (assoc query
                  :cancellation-token next-token
                  :after (get-in page-1 [:page-info :end-cursor])))]
      (is (= [(->server "server-1")] (:data page-1)))
      (is (= [(->server "server-2")] (:data page-2)))))

  (testing "unknown anchors and bounded counts use canonical v8 shapes"
    (let [forward
          (eacl/lookup-resources
           client
           {:subject (->user "missing-user")
            :permission :view
            :resource/type :server
            :first 10})
          reverse
          (eacl/lookup-subjects
           client
           {:resource (->server "missing-server")
            :permission :view
            :subject/type :user
            :first 10})
          bounded
          (eacl/count-resources
           client
           {:subject (->user "user-1")
            :permission :view
            :resource/type :server
            :count-limit 1})]
      (is (= [] (:data forward)))
      (is (= [] (:data reverse)))
      (is (= {:start-cursor nil
              :end-cursor nil
              :has-next-page? false
              :has-previous-page? false}
             (:page-info forward)
             (:page-info reverse)))
      (is (= {:count 1 :limit 1 :truncated? true}
             (select-keys bounded [:count :limit :truncated?])))))

  (testing "lookup-subjects and count-subjects enumerate reverse access"
    (let [query {:resource     (->server "server-1")
                 :permission   :reboot
                 :subject/type :user}
          subjects (->> (eacl/lookup-subjects client (assoc query :first 10))
                        :data
                        set)
          count-result (eacl/count-subjects client query)]
      (is (= #{(->user "user-1") (->user "super-user")} subjects))
      (is (= 2 (:count count-result)))))

  (testing "relationship writes and Relay-style reads remain part of the contract"
    (let [initial
          (eacl/read-relationships client {:resource/type     :account
                                           :resource/id       "account-1"
                                           :resource/relation :owner
                                           :subject/type      :user
                                           :subject/id        "user-1"
                                           :first             10})]
      (is (= [(eacl/->Relationship (->user "user-1") :owner (->account "account-1"))]
             (:data initial))))

    (let [query {:subject/type      :account
                 :subject/id        "account-1"
                 :resource/type     :server
                 :resource/relation :account
                 :first             1}
          page-1 (eacl/read-relationships client query)
          page-2 (eacl/read-relationships
                  client
                  (assoc query :after (get-in page-1 [:page-info :end-cursor])))]
      (is (= [(eacl/->Relationship (->account "account-1") :account (->server "server-1"))]
             (:data page-1)))
      (is (= [(eacl/->Relationship (->account "account-1") :account (->server "server-2"))]
             (:data page-2))))

    (eacl/create-relationship! client (->user "user-2") :owner (->account "account-1"))
    (is (true? (eacl/can? client (->user "user-2") :reboot (->server "server-1"))))
    (let [read-result
          (eacl/read-relationships client {:resource/type     :account
                                           :resource/id       "account-1"
                                           :resource/relation :owner
                                           :subject/type      :user
                                           :subject/id        "user-2"
                                           :first             10})]
      (is (= [(eacl/->Relationship (->user "user-2") :owner (->account "account-1"))]
             (:data read-result)))
      (eacl/delete-relationships! client (:data read-result)))
    (is (= []
           (read-relationships-data client {:resource/type     :account
                                            :resource/id       "account-1"
                                            :resource/relation :owner
                                            :subject/type      :user
                                            :subject/id        "user-2"
                                            :first             10})))
    (is (false? (eacl/can? client (->user "user-2") :reboot (->server "server-1")))))

  (testing "pre-cancelled tokens stop every bounded public read"
    (let [token (eacl/cancellation-token)
          _ (eacl/cancel! token)
          subject (->user "user-1")
          resource (->server "server-1")
          calls
          [[:can?
            #(eacl/can?
              client
              {:subject subject
               :permission :view
               :resource resource
               :cancellation-token token})]
           [:check-permission
            #(eacl/check-permission
              client
              {:subject subject
               :permission :view
               :resource resource
               :cancellation-token token})]
           [:lookup-resources
            #(eacl/lookup-resources
              client
              {:subject subject
               :permission :view
               :resource/type :server
               :first 1
               :cancellation-token token})]
           [:count-resources
            #(eacl/count-resources
              client
              {:subject subject
               :permission :view
               :resource/type :server
               :count-limit 1
               :cancellation-token token})]
           [:lookup-subjects
            #(eacl/lookup-subjects
              client
              {:resource resource
               :permission :view
               :subject/type :user
               :first 1
               :cancellation-token token})]
           [:count-subjects
            #(eacl/count-subjects
              client
              {:resource resource
               :permission :view
               :subject/type :user
               :count-limit 1
               :cancellation-token token})]
           [:read-relationships
            #(eacl/read-relationships
              client
              {:resource/type :server
               :first 1
               :cancellation-token token})]
           [:expand-permission-tree
            #(eacl/expand-permission-tree
              client
              {:resource resource
               :permission :view
               :cancellation-token token})]]]
      (doseq [[label call] calls]
        (is (= :eacl.execution/cancelled (error-category call))
            (name label))))))

(defn assert-v8-permission-tree-contract!
  "Cross-backend shallow expansion contract over `smoke-schema`."
  [client]
  (testing "direct relation roots remain terminal leaves"
    (let [response
          (eacl/expand-permission-tree
           client
           {:resource (->account "account-1")
            :permission :owner})]
      (is (string? (:expanded-at response)))
      (is (= {:expanded-object (->account "account-1")
              :expanded-relation :owner
              :leaf {:subjects [(->user "user-1")]}}
             (:tree-root response)))
      (is (= (:tree-root response)
             (:tree-root
              (eacl/expand-permission-tree
               client
               {:resource (->account "account-1")
                :permission :owner
                :consistency
                (consistency/at-least-as-fresh
                 (:expanded-at response))}))))
      (is (= (:tree-root response)
             (:tree-root
              (eacl/expand-permission-tree
               client
               {:resource (->account "account-1")
                :permission :owner
                :consistency consistency/fully-consistent}))))))

  (testing "same-resource permissions and arrows preserve every union boundary"
    (is (= {:expanded-object (->server "server-1")
            :expanded-relation :reboot
            :intermediate
            {:operation :union
             :children
             [{:expanded-object (->server "server-1")
               :expanded-relation :reboot
               :intermediate
               {:operation :union
                :children
                [{:expanded-object (->account "account-1")
                  :expanded-relation :admin
                  :intermediate
                  {:operation :union
                   :children
                   [{:expanded-object (->account "account-1")
                     :expanded-relation :owner
                     :leaf {:subjects [(->user "user-1")]}}
                    {:expanded-object (->account "account-1")
                     :expanded-relation :admin
                     :intermediate
                     {:operation :union
                      :children
                      [{:expanded-object (->platform "platform-1")
                        :expanded-relation :super_admin
                        :leaf
                        {:subjects [(->user "super-user")]}}]}}]}}]}}]}}
           (:tree-root
            (eacl/expand-permission-tree
             client
             {:resource (->server "server-1")
              :permission :reboot})))))

  (testing "absent ids retain the exact root and empty arrow topology"
    (is (= {:expanded-object (->server "absent")
            :expanded-relation :reboot
            :intermediate
            {:operation :union
             :children
             [{:expanded-object (->server "absent")
               :expanded-relation :reboot
               :intermediate {:operation :union :children []}}]}}
           (:tree-root
            (eacl/expand-permission-tree
             client
             {:resource (->server "absent")
              :permission :reboot})))))

  (testing "request validation is identical before backend reads"
    (is (map?
         (eacl/expand-permission-tree
          client
          {:resource (->server "server-1")
           :permission :reboot
           :cache? false}))
        "the permission-tree read accepts the common cache control")
    (is (= :eacl.execution/invalid-contract
           (error-category
            #(eacl/expand-permission-tree
              client
              {:resource (->server "server-1")
               :permission :reboot
               :timeout-ms 0}))))))

(def recursive-schema
  "definition user {}

   definition folder {
     relation parent: folder
     relation reader: user
     relation editor: user
     relation auditor: user

     permission selfread = reader + parent->selfread
     permission read = reader + editor + parent->write
     permission write = read
     permission duplicate = read + reader + parent->read
   }")

(def recursive-schema-with-audit
  (str recursive-schema
       "

        definition audit_log {
          relation folder: folder
          permission view = folder->duplicate
        }"))

(def recursive-schema-with-relevant-audit
  (str
   (str/replace
    recursive-schema
    "permission read = reader + editor + parent->write"
    "permission read = reader + editor + auditor + parent->write")
   "

    definition audit_log {
      relation folder: folder
      permission view = folder->duplicate
    }"))

(def recursive-connected-folder-count 12)

(def recursive-objects
  (into [(->user "recursive-user")
         (->user "denied-user")]
        (map #(eacl/spice-object :folder (str "folder-" %))
             (range (inc recursive-connected-folder-count)))))

(def recursive-relationships
  (into
   [(eacl/->Relationship
     (->user "recursive-user")
     :reader
     (eacl/spice-object :folder "folder-0"))
    (eacl/->Relationship
     (->user "recursive-user")
     :editor
     (eacl/spice-object :folder "folder-0"))]
   (map (fn [index]
          (eacl/->Relationship
           (eacl/spice-object :folder (str "folder-" index))
           :parent
           (eacl/spice-object :folder (str "folder-" (inc index)))))
        (range (dec recursive-connected-folder-count)))))

(def recursive-batch-property-seed 7331)

(defn- recursive-batch-demands
  []
  (let [subject (->user "recursive-user")
        denied (->user "denied-user")
        folder #(eacl/spice-object :folder (str "folder-" %))
        last-folder (folder (dec recursive-connected-folder-count))]
    [{:subject subject :permission :selfread :resource (folder 0)}
     {:subject subject :permission :selfread :resource last-folder}
     {:subject subject :permission :read :resource last-folder}
     {:subject denied :permission :read :resource last-folder}
     {:subject subject :permission :write :resource last-folder}
     {:subject subject :permission :duplicate :resource last-folder}
     {:subject denied :permission :duplicate :resource (folder 0)}
     {:subject subject :permission :read
      :resource (eacl/spice-object :folder "missing-folder")}]))

(defn- assert-v8-recursive-batch-contract!
  [client]
  (testing (str "seeded recursive batch oracle, seed="
                recursive-batch-property-seed)
    (doseq [[index checks]
            (map-indexed
             vector
             (seeded-batch-vectors
              recursive-batch-property-seed
              16
              (recursive-batch-demands)))]
      (let [evaluation (if (even? index)
                         :demand
                         :complete-denotation)]
        (if (eacl/acl? client)
          (eacl/with-snapshot [snapshot (eacl/snapshot client)]
            (assert-one-batch-oracle-case!
             snapshot checks evaluation))
          (assert-one-batch-oracle-case!
           client checks evaluation))))))

(defn- lookup-all-resource-pages
  [client query]
  (loop [pages []
         after nil]
    (let [page
          (eacl/lookup-resources
           client
           (cond-> query
             after (assoc :after after)))
          pages (conj pages page)]
      (if (get-in page [:page-info :has-next-page?])
        (recur pages (get-in page [:page-info :end-cursor]))
        pages))))

(defn- ordered-generation-proofs?
  [reader]
  (letfn [(supported? [snapshot]
            (backend/supports?
             (get-in snapshot [:basis :adapter])
             :cache-proofs
             :ordered-generations))]
    (if (eacl/snapshot? reader)
      (supported? reader)
      (eacl/with-snapshot [snapshot (eacl/snapshot reader)]
        (supported? snapshot)))))

(defn assert-v8-recursive-contracts!
  [client]
  (let [subject (->user "recursive-user")
        denied (->user "denied-user")
        folder #(eacl/spice-object :folder (str "folder-" %))
        query {:subject subject
               :permission :read
               :resource/type :folder
               :first 2}
        pages (lookup-all-resource-pages client query)]
    (assert-v8-recursive-batch-contract! client)

    (testing "recursive results match an independent least-fixed-point oracle"
      (assert-authorization-oracle!
       client
       {:objects recursive-objects
        :relationships recursive-relationships
        :rules oracle/recursive-rules}))

    (testing "self and mutual cycles reach a deterministic fixed point"
      (is (true?
           (eacl/can?
            client subject :selfread
            (folder (dec recursive-connected-folder-count)))))
      (is (true?
           (eacl/can?
            client subject :read
            (folder (dec recursive-connected-folder-count)))))
      (is (false?
           (eacl/can?
            client denied :read
            (folder (dec recursive-connected-folder-count)))))
      (is (= (mapv folder (range recursive-connected-folder-count))
             (into [] cat (map :data pages)))))

    (testing "recursive forward/reverse pages and duplicate paths deduplicate"
      (is (= recursive-connected-folder-count
             (:count
              (eacl/count-resources
               client
               (dissoc query :first)))))
      (is (= [subject]
             (:data
              (eacl/lookup-subjects
               client
               {:resource (folder (dec recursive-connected-folder-count))
                :permission :duplicate
                :subject/type :user
                :first 10}))))
      (is (= recursive-connected-folder-count
             (:count
              (eacl/count-resources
               client
               {:subject subject
                :permission :duplicate
                :resource/type :folder})))))

    (testing "demand answers reuse proved generations and changed cursors fail closed"
      (let [all-query (assoc query :first 20)
            miss (eacl/lookup-resources client all-query)
            hit (eacl/lookup-resources client all-query)]
        (is (false? (:cached? miss)))
        (is (true? (:cached? hit)))

        (eacl/create-relationship!
         client denied :auditor (folder 0))
        (let [after-unrelated-write
              (eacl/lookup-resources client all-query)
              ordered-proofs? (ordered-generation-proofs? client)]
          (is (= ordered-proofs? (:cached? after-unrelated-write))
              (if ordered-proofs?
                "an unrelated stamped write preserves the dependency frontier"
                "a backend without ordered proofs recomputes after any revision"))
          (is (= (mapv folder (range recursive-connected-folder-count))
                 (:data after-unrelated-write))))

        (eacl/create-relationship!
         client
         (folder (dec recursive-connected-folder-count))
         :parent
         (folder recursive-connected-folder-count))

        (let [stale-cursor
              (get-in (last pages) [:page-info :end-cursor])
              outcome
              (try
                {:page
                 (eacl/lookup-resources
                  client
                  (assoc query :after stale-cursor))}
                (catch #?(:clj Exception :cljs :default) thrown
                  {:error (ex-data thrown)}))]
          (if-let [error (:error outcome)]
            (is (= :eacl.pagination/stale-cursor (:type error))
                "a current-only backend rejects a cursor from an older basis")
            (do
              (is (empty? (get-in outcome [:page :data]))
                  "an immutable time-travel backend may resume the exact old snapshot")
              (is (nil? (get-in outcome
                                [:page :page-info :cursor-recovery]))))))

        (let [after-write (eacl/lookup-resources client all-query)]
          (is (false? (:cached? after-write)))
          (is (= (mapv folder
                       (range (inc recursive-connected-folder-count)))
                 (:data after-write))))

        (eacl/write-schema! client recursive-schema-with-audit)
        (let [after-unrelated-schema-write
              (eacl/lookup-resources client all-query)]
          (is (false? (:cached? after-unrelated-schema-write))
              "every schema write drops the managed generation")
          (is (= (mapv folder
                       (range (inc recursive-connected-folder-count)))
                 (:data after-unrelated-schema-write))))

        (eacl/write-schema! client recursive-schema-with-relevant-audit)
        (let [after-relevant-schema-write
              (eacl/lookup-resources client all-query)]
          (is (false? (:cached? after-relevant-schema-write)))
          (is (= (mapv folder
                       (range (inc recursive-connected-folder-count)))
                 (:data after-relevant-schema-write)))))

      (eacl/delete-object! client (folder recursive-connected-folder-count))
      (let [after-delete
            (eacl/lookup-resources client (assoc query :first 20))]
        (is (false? (:cached? after-delete)))
        (is (= (mapv folder (range recursive-connected-folder-count))
               (:data after-delete))))
      (is (false?
           (eacl/can?
            client subject :read
            (folder recursive-connected-folder-count)))))))

(defn assert-v8-recursive-safety-limit!
  [client]
  (let [data
        (try
          (eacl/lookup-resources
           client
           {:subject (->user "recursive-user")
            :permission :read
            :resource/type :folder
            :first 10
            :evaluation :complete-denotation})
          nil
          (catch #?(:clj Exception :cljs :default) error
            (ex-data error)))]
    (is (= :eacl.recursive-traversal/limit-exceeded
           (:eacl/error data)))
    (is (#{:derived-grants :advanced-datoms :queued-work}
         (:limit-kind data))))
  (let [easy {:subject (->user "recursive-user")
              :permission :selfread
              :resource (eacl/spice-object :folder "folder-0")}
        hard {:subject (->user "recursive-user")
              :permission :read
              :resource
              (eacl/spice-object
               :folder
               (str "folder-" (dec recursive-connected-folder-count)))}
        easy-result (eacl/check-permission
                     client (assoc easy :cache? false))
        hard-outcome
        (try
          {:value
           (eacl/check-permission
            client
            (assoc hard
                   :cache? false
                   :evaluation :complete-denotation))}
          (catch #?(:clj Exception :cljs :default) error
            {:error (ex-data error)}))
        batch-outcome
        (try
          {:value
           (eacl/check-permissions
            client
            {:checks [easy hard]
             :cache? false
             :evaluation :complete-denotation})}
          (catch #?(:clj Exception :cljs :default) error
            {:error (ex-data error)}))]
    (is (true? (:allowed? easy-result)))
    (if-let [hard-error (:error hard-outcome)]
      (if-let [batch-error (:error batch-outcome)]
        (do
          (is (= :eacl.recursive-traversal/limit-exceeded
                 (:eacl/error batch-error)))
          (is (= 1 (:demand-index batch-error))
              "a failing scalar envelope names its batch position"))
        (is (= [true true]
               (mapv :allowed? (:value batch-outcome)))
            (str "certified sharing may remove the scalar limit failure "
                 (:limit-kind hard-error))))
      (do
        (is (nil? (:error batch-outcome))
            "batch sharing never makes a successful scalar demand fail")
        (is (= [(:allowed? easy-result)
                (get-in hard-outcome [:value :allowed?])]
               (mapv :allowed? (:value batch-outcome))))))))

(defn assert-v8-cache-disabled!
  [client]
  (let [subject (->user "user-1")
        resource (->server "server-1")
        calls
        [[:can?
          #(eacl/can?
            client
            (assoc % :subject subject
                   :permission :view
                   :resource resource))]
         [:lookup-resources
          #(eacl/lookup-resources
            client
            (assoc % :subject subject
                   :permission :view
                   :resource/type :server
                   :first 10))]
         [:lookup-subjects
          #(eacl/lookup-subjects
            client
            (assoc % :resource resource
                   :permission :view
                   :subject/type :user
                   :first 10))]
         [:count-resources
          #(eacl/count-resources
            client
            (assoc % :subject subject
                   :permission :view
                   :resource/type :server))]
         [:count-subjects
          #(eacl/count-subjects
            client
            (assoc % :resource resource
                   :permission :view
                   :subject/type :user))]
         [:read-relationships
          #(eacl/read-relationships
            client
            (assoc % :resource/type :server))]]]
    (testing "cache-disable mode covers every public read operation"
      (doseq [[label call] calls]
        (let [first-result (call {:cache? false})
              repeated-result (call {:cache? false})
              semantic-view
              (fn [result]
                (if (map? result)
                  (select-keys result [:data :count :limit :truncated?])
                  result))]
          (is (= (semantic-view first-result)
                 (semantic-view repeated-result))
              (str label " remains deterministic with cache disabled"))
          (when (and (map? first-result)
                     (contains? first-result :cached?))
            (is (false? (:cached? first-result))
                (str label " reports a cache miss"))))))
    (testing "every public read operation rejects a non-boolean cache flag"
      (doseq [[label call] calls]
        (is (= :eacl/invalid-request
               (error-category #(call {:cache? :invalid})))
            (str label " rejects a non-boolean :cache?"))))))

(defn assert-v8-request-cache-controls!
  [client _cache-config]
  (let [resource-query
        {:subject (->user "user-1")
         :permission :view
         :resource/type :server
         :first 1}
        subject-query
        {:resource (->server "server-1")
         :permission :reboot
         :subject/type :user
         :first 1}
        relationship-query
        {:subject/type :account
         :subject/id "account-1"
         :resource/type :server
         :resource/relation :account
         :first 1}
        demand
        {:subject (->user "user-1")
         :permission :reboot
         :resource (->server "server-1")}
        permission-tree-query
        {:resource (->server "server-1")
         :permission :view}
        store (:basis-cache-store (:runtime client))
        clear! #(cache/expire-basis-cache! store)
        stats #(cache/basis-cache-stats store)]
    (clear!)

    (testing "request bypass neither reads nor writes and retained entries remain reusable"
      (let [miss (eacl/lookup-resources
                  client (assoc resource-query :cache? true))
            hit (eacl/lookup-resources client resource-query)
            before-bypass (stats)
            bypass (eacl/lookup-resources
                    client (assoc resource-query :cache? false))
            after-bypass (stats)
            retained-hit
            (eacl/lookup-resources
             client (assoc resource-query :cache? true))]
        (is (false? (:cached? miss)))
        (is (true? (:cached? hit)))
        (is (false? (:cached? bypass)))
        (is (= (dissoc before-bypass :bypasses)
               (dissoc after-bypass :bypasses)))
        (is (= (inc (:bypasses before-bypass))
               (:bypasses after-bypass)))
        (is (true? (:cached? retained-hit)))
        (is (= (:data miss) (:data bypass) (:data retained-hit)))))

    (testing "read-without-publication hits existing answers without promoting or writing"
      (clear!)
      (let [miss (eacl/check-permission client demand)
            before-read (stats)
            hit (eacl/check-permission
                 client (assoc demand :populate-cache? false))
            after-read (stats)]
        (is (false? (:cached? miss)))
        (is (true? (:cached? hit)))
        (is (= (:allowed? miss) (:allowed? hit)))
        (is (= (select-keys before-read
                            [:puts :exact-entries :managed-entries
                             :retained-bases :managed-generations])
               (select-keys after-read
                            [:puts :exact-entries :managed-entries
                             :retained-bases :managed-generations]))
            "a read-only hit does not publish or install a generation")))

    (testing "read-without-publication misses evaluate exactly and remain misses"
      (clear!)
      (let [before-misses (stats)
            first-miss
            (eacl/check-permission
             client (assoc demand :populate-cache? false))
            second-miss
            (eacl/check-permission
             client (assoc demand :populate-cache? false))
            after-misses (stats)]
        (is (false? (:cached? first-miss)))
        (is (false? (:cached? second-miss)))
        (is (= (:allowed? first-miss) (:allowed? second-miss)))
        (is (= (select-keys before-misses
                            [:puts :exact-entries :managed-entries
                             :retained-bases :managed-generations])
               (select-keys after-misses
                            [:puts :exact-entries :managed-entries
                             :retained-bases :managed-generations]))
            "read-only misses do not create completed or managed storage")))

    (testing "cache execution control is excluded from cursor identity"
      (let [first-page
            (eacl/lookup-resources
             client (assoc resource-query :cache? true))
            second-page
            (eacl/lookup-resources
             client
             (assoc resource-query
                    :cache? false
                    :after (get-in first-page
                                   [:page-info :end-cursor])))]
        (is (= [(->server "server-2")] (:data second-page)))))

    (testing "publication control is excluded from authenticated cursor identity"
      (let [first-page
            (eacl/lookup-resources
             client
             (assoc resource-query
                    :evaluation :complete-denotation
                    :populate-cache? false))
            second-page
            (eacl/lookup-resources
             client
             (assoc resource-query
                    :evaluation :complete-denotation
                    :populate-cache? true
                    :after (get-in first-page
                                   [:page-info :end-cursor])))]
        (is (= [(->server "server-1")] (:data first-page)))
        (is (= [(->server "server-2")] (:data second-page)))))

    (testing "relationship reads expose miss, hit, bypass, and retained reuse"
      (clear!)
      (let [miss
            (eacl/read-relationships
             client (assoc relationship-query :cache? true))
            hit
            (eacl/read-relationships client relationship-query)
            before-bypass (stats)
            bypass
            (eacl/read-relationships
             client (assoc relationship-query :cache? false))
            after-bypass (stats)
            retained-hit
            (eacl/read-relationships
             client (assoc relationship-query :cache? true))]
        (is (false? (:cached? miss)))
        (is (true? (:cached? hit)))
        (is (false? (:cached? bypass)))
        (is (= (dissoc before-bypass :bypasses)
               (dissoc after-bypass :bypasses)))
        (is (= (inc (:bypasses before-bypass))
               (:bypasses after-bypass)))
        (is (true? (:cached? retained-hit)))
        (is (= (:data miss) (:data bypass) (:data retained-hit)))))

    (testing "detailed permission checks expose miss, hit, and bypass provenance"
      (clear!)
      (let [miss (eacl/check-permission client demand)
            hit (eacl/check-permission client (assoc demand :cache? true))
            before-bypass (stats)
            bypass
            (eacl/check-permission client (assoc demand :cache? false))
            after-bypass (stats)]
        (is (= true (:allowed? miss) (:allowed? hit) (:allowed? bypass)))
        (is (false? (:cached? miss)))
        (is (true? (:cached? hit)))
        (is (false? (:cached? bypass)))
        (is (= (dissoc before-bypass :bypasses)
               (dissoc after-bypass :bypasses)))
        (is (= (inc (:bypasses before-bypass))
               (:bypasses after-bypass)))
        (is (boolean? (eacl/can? client demand)))))

    (testing "cache bypass dominates either publication-control value"
      (let [before (stats)
            first-result
            (eacl/check-permission
             client (assoc demand :cache? false :populate-cache? true))
            middle (stats)
            second-result
            (eacl/check-permission
             client (assoc demand :cache? false :populate-cache? false))
            after (stats)]
        (is (false? (:cached? first-result)))
        (is (false? (:cached? second-result)))
        (is (= (:allowed? first-result) (:allowed? second-result)))
        (is (= (dissoc before :bypasses)
               (dissoc middle :bypasses)
               (dissoc after :bypasses))
            "cache bypass neither looks up nor publishes under either value")
        (is (= (+ 2 (:bypasses before)) (:bypasses after)))))

    (testing "all cache-aware request maps reject non-Boolean controls"
      (doseq [control [:cache? :populate-cache?]
              [operation call]
              [[:can
                #(eacl/can? client (assoc demand control :invalid))]
               [:check-permission
                #(eacl/check-permission
                  client (assoc demand control :invalid))]
               [:check-permissions
                #(eacl/check-permissions
                  client {:checks [demand] control :invalid})]
               [:empty-check-permissions
                #(eacl/check-permissions
                  client {:checks [] control :invalid})]
               [:lookup-resources
                #(eacl/lookup-resources
                  client (assoc resource-query control :invalid))]
               [:count-resources
                #(eacl/count-resources
                  client
                  (assoc (dissoc resource-query :first)
                         control :invalid))]
               [:lookup-subjects
                #(eacl/lookup-subjects
                  client (assoc subject-query control :invalid))]
               [:count-subjects
                #(eacl/count-subjects
                  client
                  (assoc (dissoc subject-query :first)
                         control :invalid))]
               [:read-relationships
                #(eacl/read-relationships
                  client (assoc relationship-query control :invalid))]
               [:expand-permission-tree
                #(eacl/expand-permission-tree
                  client (assoc permission-tree-query control :invalid))]]]
        (is (= :eacl/invalid-request (error-category call))
            (str operation " should reject an invalid " control))
        (is (= control
               (try
                 (call)
                 nil
                 (catch #?(:clj Exception :cljs :default) error
                   (:key (ex-data error)))))
            (str operation " should identify " control))))))
