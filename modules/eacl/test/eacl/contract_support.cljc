(ns eacl.contract-support
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [is testing]]
            [clojure.string :as str]
            [eacl.authorization-oracle :as oracle]
            [eacl.core :as eacl]))

(def ->user (partial eacl/spice-object :user))
(def ->platform (partial eacl/spice-object :platform))
(def ->account (partial eacl/spice-object :account))
(def ->server (partial eacl/spice-object :server))

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
    (is (false? (eacl/can? client (->user "user-2") :reboot (->server "server-1"))))))

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

    (testing "managed relation stamps retain unrelated writes and invalidate relevant writes"
      (let [all-query (assoc query :first 20)
            miss (eacl/lookup-resources client all-query)
            hit (eacl/lookup-resources client all-query)]
        (is (false? (:cached? miss)))
        (is (true? (:cached? hit)))

        (eacl/create-relationship!
         client denied :auditor (folder 0))
        (let [after-unrelated-write
              (eacl/lookup-resources client all-query)]
          (is (true? (:cached? after-unrelated-write)))
          (is (= (mapv folder (range recursive-connected-folder-count))
                 (:data after-unrelated-write))))

        (eacl/create-relationship!
         client
         (folder (dec recursive-connected-folder-count))
         :parent
         (folder recursive-connected-folder-count))

        (let [stale-cursor
              (get-in (last pages) [:page-info :end-cursor])
              stale-result
              (eacl/lookup-resources
               client
               (assoc query :after stale-cursor))]
          (is (= :restarted
                 (get-in stale-result
                         [:page-info :cursor-recovery])))
          (is (= [(folder 0) (folder 1)]
                 (:data stale-result))
              "graph-specific recursive state restarts safely on current"))

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
            :first 10})
          nil
          (catch #?(:clj Exception :cljs :default) error
            (ex-data error)))]
    (is (= :eacl.recursive-traversal/limit-exceeded
           (:eacl/error data)))
    (is (#{:derived-grants :advanced-datoms :queued-work}
         (:limit-kind data)))))

(defn assert-v8-cache-disabled!
  [client]
  (let [query {:subject (->user "user-1")
               :permission :view
               :resource/type :server
               :first 10}
        first-result (eacl/lookup-resources client query)
        repeated-result (eacl/lookup-resources client query)]
    (testing "cache-disable mode never retains an authorization answer"
      (is (false? (:cached? first-result)))
      (is (false? (:cached? repeated-result)))
      (is (= (:data first-result) (:data repeated-result))))))
