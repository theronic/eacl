(ns eacl.datascript.contract-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.cache :as cache]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.secure-format :as secure]
            [eacl.verified-kernel :as verified]))

(defn- seed-objects!
  [conn]
  (ds/transact! conn
                (map-indexed (fn [idx {:keys [id]}]
                               {:db/id (- (inc idx))
                                :eacl/id id})
                             contract/smoke-objects)))

(deftest one-authority-is-the-only-production-engine-test
  (let [conn (datascript/create-conn)
        default-client (datascript/make-client conn {})
        default-selection
        (get-in default-client [:opts :decision-kernel])
        unknown-client
        (datascript/make-client conn {:coherence-authority :unknown})
        error
        (try
          (datascript/make-client conn {:engine-selection :anything})
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) exception
            (ex-data exception)))]
    (is (satisfies? verified/DecisionKernel (:kernel default-selection)))
    (is (= :unknown
           (get-in default-client [:opts :coherence-authority]))
        "managed reuse is an explicit writer contract, never the default")
    (is (= :content
           (get-in default-client [:opts :proof-mode])))
    (is (= :unknown
           (get-in unknown-client [:opts :coherence-authority])))
    (is (= :content
           (get-in unknown-client [:opts :proof-mode])))
    (let [managed-client
          (datascript/make-client
           conn {:coherence-authority :managed})]
      (is (= :managed
             (get-in managed-client [:opts :coherence-authority])))
      (is (= :mutation
             (get-in managed-client [:opts :proof-mode]))))
    (is (= :eacl/invalid-config (:type error)))
    (is (= [:engine-selection] (:unknown-keys error)))))

(deftest raw-retraction-on-default-client-must-deny-test
  ;; D-5 pinning regression (the audited stale-ALLOW): under the old
  ;; :coherence-authority :managed default, one raw ds/transact! retraction
  ;; left every relation stamp untouched, so the next identical check served
  ;; the cached allow. The :unknown default reuses answers only on the exact
  ;; immutable database value they were computed on.
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})
        user (eacl/spice-object :user "raw-write-user")
        document (eacl/spice-object :document "raw-write-doc")]
    (eacl/write-schema!
     client
     "definition user {}
      definition document {
        relation owner: user
        permission view = owner
      }")
    (ds/transact! conn [{:eacl/id (:id user)}
                        {:eacl/id (:id document)}])
    (eacl/create-relationship!
     client (eacl/->Relationship user :owner document))
    (is (true? (eacl/can? client user :view document)))
    (is (true? (eacl/can? client user :view document))
        "the repeated identical check is served from the cache")
    ;; Retract the relationship tuples OUTSIDE every EACL writer.
    (let [db (ds/db conn)
          retractions
          (into []
                (mapcat
                 (fn [attribute]
                   (map (fn [datom]
                          [:db/retract (:e datom) attribute (:v datom)])
                        (ds/datoms db :aevt attribute))))
                [relationship-storage/forward-attribute
                 relationship-storage/reverse-attribute])]
      (is (seq retractions))
      (ds/transact! conn retractions))
    (is (false? (eacl/can? client user :view document))
        "a raw out-of-contract retraction must deny on a default client")))

(defn- reusable-denotation-hits
  [stats]
  (+ (get-in stats [:subproblems :denotation-hits] 0)
     (get-in stats [:subproblems :recursive-component-hits] 0)))

(def ^:private denotation-key-separation-schema
  "definition user {}
   definition group {
     relation member: user
     relation alternate: user
     permission access = member
     permission other = alternate
   }
   definition server {
     relation team: group
     relation backup: group
     permission same_a = team->access
     permission same_b = team->access
     permission different_relation = backup->access
     permission different_target = team->other
   }")

(deftest current-lookup-cursor-is-rejected-across-schema-generations-test
  ;; Re-goldened for cursor-dependency-validity: the cursor scope commits the
  ;; selected snapshot's schema generation, so a cursor minted under another
  ;; generation fails scope validation with the typed error instead of
  ;; silently restarting the walk — recovery mode included.
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn
         {:cache cache/no-cache
          :security-key "01234567890123456789012345678901"})
        user (eacl/spice-object :user "cursor-user")
        document-1 (eacl/spice-object :document "cursor-document-1")
        document-2 (eacl/spice-object :document "cursor-document-2")
        query
        {:subject user
         :permission :view
         :resource/type :document
         :first 1}]
    (eacl/write-schema!
     client
     "definition user {}
      definition document {
        relation owner: user
        permission view = owner
      }")
    (ds/transact!
     conn
     [{:eacl/id (:id user)}
      {:eacl/id (:id document-1)}
      {:eacl/id (:id document-2)}])
    (eacl/create-relationships!
     client
     [(eacl/->Relationship user :owner document-1)
      (eacl/->Relationship user :owner document-2)])
    (let [first-page (eacl/lookup-resources client query)
          cursor (get-in first-page [:page-info :end-cursor])]
      (is (= [document-1] (:data first-page)))
      (eacl/write-schema!
       client
       "definition user {}
        definition document {
          relation owner: user
        }")
      (let [error
            (try
              (eacl/lookup-resources client (assoc query :after cursor))
              nil
              (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                     thrown
                thrown))]
        (is (some? error)
            "a cursor from another schema generation must not resume the walk")
        (is (= :eacl.pagination/invalid-cursor (:type (ex-data error))))
        (is (= :query-mismatch (:reason (ex-data error))))
        (is (empty? (:data (eacl/lookup-resources client query)))
            "a fresh enumeration evaluates the new schema generation")))))

(deftest semantic-root-denotation-key-is-cross-target-exact-test
  (testing "equal root bodies share and distinct indexed bodies stay separate"
    (let [conn (datascript/create-conn)
          writer
          (datascript/make-client
           conn
           {:cache cache/no-cache
            :security-key "01234567890123456789012345678901"})
          client
          (datascript/make-client
           conn
           {:coherence-authority :managed
            :security-key "01234567890123456789012345678901"})
          alice (eacl/spice-object :user "shared-user")
          bob (eacl/spice-object :user "other-user")
          primary (eacl/spice-object :group "primary")
          backup (eacl/spice-object :group "backup")
          server (eacl/spice-object :server "server-0")
          decision
          (fn [permission]
            (eacl/can?
             client
             {:subject alice
              :permission permission
              :resource server
              :evaluation :complete-denotation
              :cache? true}))]
      (eacl/write-schema! writer denotation-key-separation-schema)
      (ds/transact!
       conn
       (mapv (fn [object] {:eacl/id (:id object)})
             [alice bob primary backup server]))
      (eacl/create-relationships!
       writer
       [(eacl/->Relationship alice :member primary)
        (eacl/->Relationship bob :alternate primary)
        (eacl/->Relationship bob :member backup)
        (eacl/->Relationship primary :team server)
        (eacl/->Relationship backup :backup server)])

      (is (true? (decision :same_a)))
      (let [before-stats (datascript/cache-stats client)
            before-hits
            (get-in
             before-stats
             [:subproblems :denotation-hits]
             0)
            before-acyclic-hits
            (get-in
             before-stats
             [:subproblems :acyclic-denotation-hits]
             0)]
        (is (true? (decision :same_b)))
        (let [equal-stats (datascript/cache-stats client)
              equal-hits
              (get-in
               equal-stats
               [:subproblems :denotation-hits]
               0)
              equal-acyclic-hits
              (get-in
               equal-stats
               [:subproblems :acyclic-denotation-hits]
               0)
              count-work (atom {})
              count-result
              (binding [engine/*backend-work-stats* count-work]
                (eacl/count-resources
                 client
                 {:subject alice
                  :permission :same_b
                  :resource/type :server
                  :evaluation :complete-denotation}))
              lookup-work (atom {})
              lookup-result
              (binding [engine/*backend-work-stats* lookup-work]
                (eacl/lookup-resources
                 client
                 {:subject alice
                  :permission :same_b
                  :resource/type :server
                  :evaluation :complete-denotation
                  :first 1}))
              reused-acyclic-hits
              (get-in
               (datascript/cache-stats client)
               [:subproblems :acyclic-denotation-hits]
               0)]
          (is (> equal-hits before-hits))
          (is (> equal-acyclic-hits before-acyclic-hits)
              "the metric must identify an actual complete acyclic denotation")
          (is (= 1 (:count count-result)))
          (is (empty? @count-work)
              "complete count must reuse the denotation across operations")
          (is (= [server] (:data lookup-result)))
          (is (empty? @lookup-work)
              "complete lookup must reuse the denotation across operations")
          (is (false? (decision :different_relation)))
          (is (= reused-acyclic-hits
                 (get-in
                  (datascript/cache-stats client)
                  [:subproblems :acyclic-denotation-hits]
                  0)))
          (is (false? (decision :different_target)))
          (is (= reused-acyclic-hits
                 (get-in
                  (datascript/cache-stats client)
                  [:subproblems :acyclic-denotation-hits]
                  0))))))))

(deftest datascript-contract-test
  (let [conn   (datascript/create-conn)
        store  (contract/portable-store)
        client (datascript/make-client conn {:cache store})]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (eacl/create-relationships! client contract/smoke-relationships)
    (contract/assert-v8-seeded-contracts! client)
    (contract/assert-v8-request-cache-controls! client store)
    (contract/assert-v8-cache-disabled!
     (datascript/make-client conn {:cache cache/no-cache}))))

(deftest datascript-recursive-v8-contract-test
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn
         {:coherence-authority :managed})]
    (eacl/write-schema! client contract/recursive-schema)
    (ds/transact! conn
                  (map-indexed
                   (fn [index {:keys [id]}]
                     {:db/id (- (inc index))
                      :eacl/id id})
                   contract/recursive-objects))
    (eacl/create-relationships! client contract/recursive-relationships)
    (contract/assert-v8-recursive-contracts! client)
    (doseq [limit-key [:max-derived-grants
                       :max-advanced-datoms
                       :max-queued-work]]
      (contract/assert-v8-recursive-safety-limit!
       (datascript/make-client
        conn
        {:cache cache/no-cache
         :recursive-traversal-limits {limit-key 1}})))))

(deftest completed-recursive-denotation-is-reused-by-point-check-test
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn
         {:coherence-authority :managed})
        subject (contract/->user "recursive-user")
        last-folder
        (eacl/spice-object
         :folder
         (str "folder-" (dec contract/recursive-connected-folder-count)))]
    (eacl/write-schema! client contract/recursive-schema)
    (ds/transact!
     conn
     (map-indexed
      (fn [index {:keys [id]}]
        {:db/id (- (inc index))
         :eacl/id id})
      contract/recursive-objects))
    (eacl/create-relationships! client contract/recursive-relationships)
    (is (= contract/recursive-connected-folder-count
           (:count
            (eacl/count-resources
             client
             {:subject subject
              :permission :read
              :resource/type :folder
              :evaluation :complete-denotation}))))
    (is (= 1
           (:count
            (eacl/count-subjects
             client
             {:resource last-folder
              :permission :read
              :subject/type :user
              :evaluation :complete-denotation}))))
    (let [before (datascript/cache-stats client)
          page-work (atom {})
          page
          (binding [engine/*backend-work-stats* page-work]
            (eacl/lookup-resources
             client
             {:subject subject
              :permission :read
              :resource/type :folder
              :evaluation :complete-denotation
              :first 3}))
          page-2
          (eacl/lookup-resources
           client
           {:subject subject
            :permission :read
            :resource/type :folder
            :evaluation :complete-denotation
            :first 3
            :after (get-in page [:page-info :end-cursor])})
          previous
          (eacl/lookup-resources
           client
           {:subject subject
            :permission :read
            :resource/type :folder
            :evaluation :complete-denotation
            :last 3
            :before (get-in page-2 [:page-info :start-cursor])})
          reverse-work (atom {})
          reverse-page
          (binding [engine/*backend-work-stats* reverse-work]
            (eacl/lookup-subjects
             client
             {:resource last-folder
              :permission :read
              :subject/type :user
              :evaluation :complete-denotation
              :first 10}))
          work (atom {})
          allowed?
          (binding [engine/*backend-work-stats* work]
            (eacl/can?
             client
             {:subject subject
              :permission :read
              :resource last-folder
              :evaluation :complete-denotation}))
          after (datascript/cache-stats client)]
      (is (= ["folder-0" "folder-1" "folder-2"]
             (mapv :id (:data page))))
      (is (= ["folder-3" "folder-4" "folder-5"]
             (mapv :id (:data page-2))))
      (is (= (:data page) (:data previous)))
      (is (empty? @page-work)
          "the completed fixed point renders a distinct page without scans")
      (is (= [subject] (:data reverse-page)))
      (is (empty? @reverse-work)
          "the reverse fixed point also renders without scans")
      (is (true? allowed?))
      (is (empty? @work)
          "a completed fixed point answers the distinct point query")
      (is (< (reusable-denotation-hits before)
             (reusable-denotation-hits after))
          "the point check must reuse the complete recursive denotation"))))

(deftest demand-point-check-does-not-publish-complete-fixed-point-test
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn
         {:coherence-authority :managed})
        subject (contract/->user "recursive-user")
        folder
        #(eacl/spice-object :folder (str "folder-" %))]
    (eacl/write-schema! client contract/recursive-schema)
    (ds/transact!
     conn
     (map-indexed
      (fn [index {:keys [id]}]
        {:db/id (- (inc index))
         :eacl/id id})
      contract/recursive-objects))
    (eacl/create-relationships! client contract/recursive-relationships)
    (is (true?
         (eacl/can?
          client subject :read
          (folder (dec contract/recursive-connected-folder-count)))))
    (eacl/create-relationship!
     client
     (contract/->user "denied-user")
     :auditor
     (folder 0))
    (let [before (datascript/cache-stats client)
          work (atom {})
          allowed?
          (binding [engine/*backend-work-stats* work]
            (eacl/can? client subject :read (folder 1)))
          after (datascript/cache-stats client)]
      (is (true? allowed?))
      (is (pos? (get @work :executed-backend-operations 0))
          "a demand decision performs only the new target's required work")
      (is (= (get-in before
                     [:subproblems :managed-denotation-hits])
             (get-in after
                     [:subproblems :managed-denotation-hits]))
          "demand mode neither publishes nor lifts a complete denotation"))))

(deftest demand-acyclic-cache-miss-matches-bypass-work-test
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn
         {:coherence-authority :managed})
        user (eacl/spice-object :user "shared-user")
        other-user (eacl/spice-object :user "other-user")
        group (eacl/spice-object :group "shared-group")
        server-1 (eacl/spice-object :server "server-1")
        server-2 (eacl/spice-object :server "server-2")
        document-1 (eacl/spice-object :document "document-1")
        document-2 (eacl/spice-object :document "document-2")
        decision
        (fn [subject permission resource cache?]
          (eacl/can?
           client
           {:subject subject
            :permission permission
            :resource resource
            :cache? cache?}))]
    (eacl/write-schema!
     client
     "definition user {}
      definition group {
        relation member: user
        permission access = member
      }
      definition server {
        relation team: group
        permission read_a = team->access
        permission read_b = team->access
        permission read_c = team->access
      }
      definition document {
        relation owner: user
        permission read = owner
      }")
    (ds/transact!
     conn
     (map-indexed
      (fn [index object]
        {:db/id (- (inc index))
         :eacl/id (:id object)})
      [user other-user group server-1 server-2 document-1 document-2]))
    (eacl/create-relationships!
     client
     [(eacl/->Relationship user :member group)
      (eacl/->Relationship group :team server-1)
      (eacl/->Relationship other-user :owner document-1)])

    (is (true? (decision user :read_a server-1 true)))
    (let [before (datascript/cache-stats client)]
      ;; This advances the exact graph generation without touching either
      ;; relation used by the server permission.
      (eacl/create-relationships!
       client
       [(eacl/->Relationship other-user :owner document-2)])
      (let [cached-work (atom {})
            bypass-work (atom {})
            cached-allowed?
            (binding [engine/*backend-work-stats* cached-work]
              (decision user :read_b server-1 true))
            bypass-allowed?
            (binding [engine/*backend-work-stats* bypass-work]
              (decision user :read_b server-1 false))
            after (datascript/cache-stats client)]
        (is (true? cached-allowed?))
        (is (= cached-allowed? bypass-allowed?))
        (is (= (:executed-backend-operations @bypass-work)
               (:executed-backend-operations @cached-work))
            "a cold demand cache attempt performs the same semantic work as bypass")
        (is (= (get-in before
                       [:subproblems :managed-projection-hits])
               (get-in after
                       [:subproblems :managed-projection-hits]))
            "demand mode does not lift partial projections across generations")))

    ;; A write to the depended-on relation must select a different managed key,
    ;; not reuse the previous negative projection.
    (is (false? (decision user :read_c server-2 true)))
    (eacl/create-relationships!
     client
     [(eacl/->Relationship group :team server-2)])
    (is (true? (decision user :read_c server-2 true)))))

(deftest datascript-delete-object-contract-test
  (let [conn   (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (eacl/create-relationships! client contract/smoke-relationships)
    (eacl/delete-object! client (contract/->user "user-1"))

    (testing "delete-object! removes touching relationships but retains the object"
      (is (some? (ds/entid (ds/db conn) [:eacl/id "user-1"])))
      (is (false? (eacl/can? client
                             (contract/->user "user-1")
                             :reboot
                             (contract/->server "server-1"))))
      (is (= []
             (:data
              (eacl/read-relationships client {:subject/id "user-1"})))))

    (testing "unrelated grants remain intact"
      (is (true? (eacl/can? client
                            (contract/->user "super-user")
                            :reboot
                            (contract/->server "server-1")))))))

(deftest datascript-large-relationship-cursor-skips-item-proof-test
  ;; Pinned to the managed/mutation-proof regime: the assertion is that the
  ;; v10 cursor design performs ZERO record-digest work per page. The
  ;; :unknown default (D-5) selects content proofs, which pay one schema
  ;; content digest per page - a separate, deliberate trade of the fail-safe
  ;; default, not a cursor property.
  (let [relationship-count 1505
        conn (datascript/create-conn)
        client (datascript/make-client
                conn
                {:cache cache/no-cache
                 :coherence-authority :managed})
        user-ids (mapv #(str "bulk-user-" %) (range relationship-count))
        server-ids (mapv #(str "bulk-server-" %) (range relationship-count))
        object-ids (into user-ids server-ids)]
    (eacl/write-schema!
     client
     "definition user {}

      definition server {
        relation owner: user
      }")
    (ds/transact!
     conn
     (map-indexed
      (fn [index object-id]
        {:db/id (- (inc index))
         :eacl/id object-id})
      object-ids))
    (eacl/create-relationships!
     client
     (mapv (fn [index]
             (eacl/->Relationship
              (contract/->user (nth user-ids index))
              :owner
              (contract/->server (nth server-ids index))))
           (range relationship-count)))
    (let [proof-count (atom 0)
          canonical-records-digest secure/canonical-records-digest
          read-page
          (fn [filters]
            (reset! proof-count 0)
            (let [page
                  (with-redefs
                   [secure/canonical-records-digest
                    (fn [& args]
                      (swap! proof-count inc)
                      (apply canonical-records-digest args))]
                    (eacl/read-relationships client filters))]
              [page @proof-count]))
          [page-1 page-1-proof-count]
          (read-page {:subject/type :user
                      :first 1000})
          [page-2 page-2-proof-count]
          (read-page
           {:subject/type :user
            :first 1000
            :after (get-in page-1 [:page-info :end-cursor])})]
      (is (= 1000 (count (:data page-1))))
      (is (= 505 (count (:data page-2))))
      (is (false? (get-in page-2 [:page-info :has-next-page?])))
      (is (zero? page-1-proof-count)
          "v10 commits to the immutable snapshot, not every result item")
      (is (zero? page-2-proof-count)
          "continuation must not rebuild a linear relationship proof"))))

(defn- seeded-client
  []
  (let [conn   (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (eacl/create-relationships! client contract/smoke-relationships)
    client))

(defn- thrown-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) ex
      (ex-data ex))))

(deftest v7-3-parser-hardening-test
  (testing "identifiers that merely start with reserved words remain legal"
    (let [client (datascript/make-client (datascript/create-conn) {})
          schema "definition user {}

                  definition allocation {
                    relation relationship: user
                    permission allowed = relationship
                  }"]
      (eacl/write-schema! client schema)
      (let [{:keys [relations permissions]} (eacl/read-schema client)]
        (is (= #{[:allocation :relationship]}
               (set (map (juxt :eacl.relation/resource-type
                               :eacl.relation/relation-name)
                         relations))))
        (is (= #{[:allocation :allowed]}
               (set (map (juxt :eacl.permission/resource-type
                               :eacl.permission/permission-name)
                         permissions)))))))

  (testing "duplicate permissions fail closed instead of silently unioning"
    (let [client (datascript/make-client (datascript/create-conn) {})
          schema "definition user {}

                  definition document {
                    relation reader: user
                    permission view = reader
                    permission view = reader
                  }"]
      (is (= :eacl.schema/duplicate-permission
             (:type (thrown-data #(eacl/write-schema! client schema))))))))

(deftest unified-filter-validation-contract-test
  (contract/assert-unified-filter-validation! (seeded-client)))

(deftest v7-3-query-validation-test
  (let [client (seeded-client)]
    (testing "relationship reads require a known anchor and reject broadened scans"
      (is (= :eacl.filters/missing-anchor
             (:eacl/error
              (thrown-data #(eacl/read-relationships client {})))))
      (is (= :eacl.filters/unknown-filter
             (:eacl/error
              (thrown-data #(eacl/read-relationships
                             client
                             {:resource/type :server
                              :resouce/id "typo"})))))
      (is (= :eacl.pagination/unsupported-filter
             (:eacl/error
              (thrown-data #(eacl/read-relationships
                             client
                             {:resource/type :server
                              :resource/id-prefix "server-"}))))))

    (testing "list operations honor consistency but reject unsupported filters"
      (is (map?
           (eacl/lookup-resources
            client
            {:subject (contract/->user "user-1")
             :permission :view
             :resource/type :server
             :consistency :minimize-latency})))
      (is (= :eacl.pagination/unsupported-filter
             (:eacl/error
              (thrown-data #(eacl/lookup-subjects
                             client
                             {:resource (contract/->server "server-1")
                              :permission :view
                              :subject/type :user
                              :subject/relation :member}))))))))

(deftest v7-3-empty-first-page-test
  (let [conn   (datascript/create-conn)
        client (datascript/make-client conn {})
        query  {:subject (contract/->user "user-1")
                :permission :view
                :resource/type :server
                :first 100}]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (testing "an empty first page does not mint a boundary-less cursor"
      (let [page (eacl/lookup-resources client query)]
        (is (= [] (:data page)))
        (is (= {:start-cursor nil
                :end-cursor nil
                :has-next-page? false
                :has-previous-page? false}
               (:page-info page))))
      (is (= {:count 0 :limit -1}
             (select-keys
              (eacl/count-resources client (dissoc query :first))
              [:count :limit]))))))

(deftest v8-certified-acyclic-cursor-is-public-state-minimal-test
  (let [client (seeded-client)
        query {:subject (contract/->user "user-1")
               :permission :view
               :resource/type :server
               :first 1}
        page-1 (eacl/lookup-resources client query)
        cursor-1 (get-in page-1 [:page-info :end-cursor])
        envelope (datascript/token->cursor cursor-1)
        page-2 (eacl/lookup-resources client
                                      (assoc query :after cursor-1))
        page-3 (eacl/lookup-resources client
                                      (assoc query
                                             :after
                                             (get-in page-2
                                                     [:page-info :end-cursor])))]
    (testing "v8 acyclic cursors carry only the stable result boundary"
      (is (= [(contract/->server "server-1")] (:data page-1)))
      (is (= [(contract/->server "server-2")] (:data page-2)))
      (is (empty? (:data page-3)))
      (is (= 11 (:v envelope)))
      (is (= :lookup-eid
             (get-in envelope [:edge :kind])))
      (is (= "server-1"
             (get-in envelope [:edge :result-eid])))
      (is (nil? (get-in envelope [:edge :direction])))
      (is (nil? (get-in envelope [:edge :path-frontiers])))
      (is (nil? (get-in envelope [:edge :heads]))))

    (testing "a forward cursor cannot be reused for reverse traversal"
      (is (= :query-mismatch
             (:reason
              (thrown-data #(eacl/lookup-subjects
                             client
                             {:resource (contract/->server "server-1")
                              :permission :view
                              :subject/type :user
                              :first 1
                              :after cursor-1}))))))))
