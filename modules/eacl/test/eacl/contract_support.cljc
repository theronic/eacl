(ns eacl.contract-support
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [clojure.string :as str]
            [eacl.authorization-oracle :as oracle]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.spicedb.consistency :as consistency]))

(def ->user (partial eacl/spice-object :user))
(def ->platform (partial eacl/spice-object :platform))
(def ->account (partial eacl/spice-object :account))
(def ->server (partial eacl/spice-object :server))

(defrecord PortableContractStore [entries metrics]
  cache/CacheStore
  (lookup [_ key] (get @entries key))
  (store! [_ key value]
    (if (nil? value)
      false
      (do (swap! entries assoc key value)
          true)))
  (evict! [_ key]
    (let [existed? (contains? @entries key)]
      (swap! entries dissoc key)
      existed?))
  (clear! [_]
    (reset! entries {})
    nil)
  (stats [_]
    (assoc @metrics :entries (count @entries)))
  cache/CacheTelemetry
  (record-validation! [_ metric]
    (swap! metrics update metric (fnil inc 0))
    nil))

(defn portable-store
  "A minimal portable CacheStore stand-in for contract tests.

  The production portable reference store was deleted with the D-6
  answer-tier fold-in; contract suites only need an inert provider adapter
  to prove native completed answers remain client-private and that request
  bypass touches no provider state."
  []
  (->PortableContractStore (atom {}) (atom {})))

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
  (testing "authorization results match the independent curated oracle"
    (assert-authorization-oracle!
     client
     {:objects smoke-objects
      :relationships smoke-relationships
      :rules oracle/smoke-rules}))

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
    (is (= :eacl.permission-tree/invalid-request
           (error-category
            #(eacl/expand-permission-tree
              client
              {:resource (->server "server-1")
               :permission :reboot
               :cache? false}))))
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
              (eacl/lookup-resources client all-query)]
          (is (true? (:cached? after-unrelated-write))
              "an unrelated stamped write preserves the dependency frontier")
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
         (:limit-kind data)))))

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
  [client store]
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
         :resource (->server "server-1")}]
    (cache/clear! store)

    (testing "request bypass neither reads nor writes and retained entries remain reusable"
      (let [miss (eacl/lookup-resources
                  client (assoc resource-query :cache? true))
            hit (eacl/lookup-resources client resource-query)
            before-bypass (cache/stats store)
            bypass (eacl/lookup-resources
                    client (assoc resource-query :cache? false))
            after-bypass (cache/stats store)
            retained-hit
            (eacl/lookup-resources
             client (assoc resource-query :cache? true))]
        (is (false? (:cached? miss)))
        (is (true? (:cached? hit)))
        (is (false? (:cached? bypass)))
        (is (= before-bypass after-bypass))
        (is (true? (:cached? retained-hit)))
        (is (= (:data miss) (:data bypass) (:data retained-hit)))))

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

    (testing "relationship reads expose miss, hit, bypass, and retained reuse"
      (cache/clear! store)
      (let [miss
            (eacl/read-relationships
             client (assoc relationship-query :cache? true))
            hit
            (eacl/read-relationships client relationship-query)
            before-bypass (cache/stats store)
            bypass
            (eacl/read-relationships
             client (assoc relationship-query :cache? false))
            after-bypass (cache/stats store)
            retained-hit
            (eacl/read-relationships
             client (assoc relationship-query :cache? true))]
        (is (false? (:cached? miss)))
        (is (true? (:cached? hit)))
        (is (false? (:cached? bypass)))
        (is (= before-bypass after-bypass))
        (is (true? (:cached? retained-hit)))
        (is (= (:data miss) (:data bypass) (:data retained-hit)))))

    (testing "detailed permission checks expose miss, hit, and bypass provenance"
      (cache/clear! store)
      (let [miss (eacl/check-permission client demand)
            hit (eacl/check-permission client (assoc demand :cache? true))
            before-bypass (cache/stats store)
            bypass
            (eacl/check-permission client (assoc demand :cache? false))
            after-bypass (cache/stats store)]
        (is (= true (:allowed? miss) (:allowed? hit) (:allowed? bypass)))
        (is (false? (:cached? miss)))
        (is (true? (:cached? hit)))
        (is (false? (:cached? bypass)))
        (is (= before-bypass after-bypass))
        (is (boolean? (eacl/can? client demand)))))

    (testing "all cache-aware request maps reject non-Boolean :cache?"
      (doseq [[operation call]
              [[:can
                #(eacl/can? client (assoc demand :cache? :invalid))]
               [:check-permission
                #(eacl/check-permission
                  client (assoc demand :cache? :invalid))]
               [:lookup-resources
                #(eacl/lookup-resources
                  client (assoc resource-query :cache? :invalid))]
               [:count-resources
                #(eacl/count-resources
                  client
                  (assoc (dissoc resource-query :first)
                         :cache? :invalid))]
               [:lookup-subjects
                #(eacl/lookup-subjects
                  client (assoc subject-query :cache? :invalid))]
               [:count-subjects
                #(eacl/count-subjects
                  client
                  (assoc (dissoc subject-query :first)
                         :cache? :invalid))]
               [:read-relationships
                #(eacl/read-relationships
                  client (assoc relationship-query :cache? :invalid))]]]
        (is (= :eacl/invalid-request (error-category call))
            (str operation " should reject an invalid :cache?"))
        (is (= :cache?
               (try
                 (call)
                 nil
                 (catch #?(:clj Exception :cljs :default) error
                   (:key (ex-data error)))))
            (str operation " should identify :cache?"))))))
