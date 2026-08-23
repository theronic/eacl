(ns eacl.datascript.contract-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.causal-token :as causal-token]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.v8 :as engine]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.secure-format :as secure]
            [eacl.spicedb.consistency :as consistency]
            [eacl.verified-kernel :as verified]))

(def ^:private permission-tree-schema
  "definition user {}
   definition folder {
     relation reader: user
     permission view = reader
   }
   definition team {
     relation reader: user
     permission view = reader
   }
   definition document {
     relation viewer: user
     relation parent: folder | team
     permission base = viewer
     permission view = base + parent->view
   }")

(deftest default-source-lifecycle-is-cross-client-constant-test
  (let [conn (datascript/create-conn)
        key "01234567890123456789012345678901"
        client-a (datascript/make-client conn {:security-key key})
        client-b (datascript/make-client conn {:security-key key})
        snapshot-a (eacl/snapshot client-a)
        snapshot-b (eacl/snapshot client-b)]
    (try
      (is (= "eacl/initial"
             (get-in client-a [:runtime :source-lifecycle])
             (get-in client-b [:runtime :source-lifecycle])
             (:source-lifecycle (eacl/basis snapshot-a))
             (:source-lifecycle (eacl/basis snapshot-b))))
      (finally
        (eacl/release! snapshot-a)
        (eacl/release! snapshot-b)))
    (eacl/write-schema! client-a contract/smoke-schema)
    (ds/transact!
     conn
     (mapv (fn [{:keys [id]}] {:eacl/id id}) contract/smoke-objects))
    (let [token
          (:zed/token
           (eacl/create-relationship!
            client-a (first contract/smoke-relationships)))]
      (is (true?
           (eacl/can?
            client-b
            (contract/->user "user-1") :admin
            (contract/->account "account-1")
            (consistency/at-least-as-fresh token)))))))

(defn- seed-permission-tree!
  [conn client]
  (eacl/write-schema! client permission-tree-schema)
  (ds/transact! conn
                (mapv (fn [id] {:eacl/id id})
                      ["alice" "bob" "carol" "d1" "f1" "t1"]))
  (eacl/create-relationships!
   client
   [(eacl/->Relationship
     (eacl/spice-object :user "alice") :viewer
     (eacl/spice-object :document "d1"))
    (eacl/->Relationship
     (eacl/spice-object :folder "f1") :parent
     (eacl/spice-object :document "d1"))
    (eacl/->Relationship
     (eacl/spice-object :team "t1") :parent
     (eacl/spice-object :document "d1"))
    (eacl/->Relationship
     (eacl/spice-object :user "bob") :reader
     (eacl/spice-object :folder "f1"))
    (eacl/->Relationship
     (eacl/spice-object :user "carol") :reader
     (eacl/spice-object :team "t1"))]))

(deftest permission-tree-expansion-and-selected-snapshot-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client
                conn
                {:security-key "01234567890123456789012345678901"})
        _ (seed-permission-tree! conn client)
        resource (eacl/spice-object :document "d1")
        response
        (eacl/expand-permission-tree
         client {:resource resource :permission :view})
        root (:tree-root response)
        [base arrow] (get-in root [:intermediate :children])]
    (is (string? (:expanded-at response)))
    (is (= resource (:expanded-object root)))
    (is (= :view (:expanded-relation root)))
    (is (= :base (:expanded-relation base)))
    (is (= [(eacl/spice-object :user "alice")]
           (get-in base
                   [:intermediate :children 0 :leaf :subjects])))
    (is (= :view (:expanded-relation arrow)))
    (is (= #{(eacl/spice-object :folder "f1")
             (eacl/spice-object :team "t1")}
           (set (map :expanded-object
                     (get-in arrow [:intermediate :children])))))
    (is (= {:expanded-object
            (eacl/spice-object :document "missing")
            :expanded-relation :view
            :intermediate
            {:operation :union
             :children
             [{:expanded-object
               (eacl/spice-object :document "missing")
               :expanded-relation :base
               :intermediate
               {:operation :union
                :children
                [{:expanded-object
                  (eacl/spice-object :document "missing")
                  :expanded-relation :viewer
                  :leaf {:subjects []}}]}}
              {:expanded-object
               (eacl/spice-object :document "missing")
               :expanded-relation :view
               :intermediate {:operation :union :children []}}]}}
           (:tree-root
            (eacl/expand-permission-tree
             client
             {:resource (eacl/spice-object :document "missing")
              :permission :view}))))

    (testing "a concurrent write cannot mix the selected tree and token"
      (let [mutated? (atom false)
            late-user (eacl/spice-object :user "late")
            _ (ds/transact! conn [{:eacl/id "late"}])
            captured
            (binding [backend/*invoke-observer*
                      (fn [{:keys [phase operation]}]
                        (when (and (= :before phase)
                                   (= :relation-defs operation)
                                   (compare-and-set! mutated? false true))
                          (eacl/create-relationship!
                           client late-user :viewer resource)))]
              (eacl/expand-permission-tree
               client {:resource resource :permission :base}))
            first-subjects
            (get-in captured
                    [:tree-root :intermediate :children 0 :leaf :subjects])
            token-data
            (causal-token/token-data
             (get-in client [:runtime :format-options])
             (:expanded-at captured))]
        (is (= [(eacl/spice-object :user "alice")] first-subjects))
        (is (< (:revision token-data) (:max-tx (ds/db conn))))
        (is (= #{(eacl/spice-object :user "alice") late-user}
               (set
                (get-in
                 (eacl/expand-permission-tree
                  client {:resource resource :permission :base})
                 [:tree-root :intermediate :children 0 :leaf :subjects]))))))))

(deftest pinned-spicedb-permission-tree-golden-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client contract/permission-tree-golden-schema)
    (ds/transact!
     conn
     (map-indexed
      (fn [index {:keys [id]}]
        {:db/id (- (inc index)) :eacl/id id})
      contract/permission-tree-golden-objects))
    (eacl/create-relationships!
     client contract/permission-tree-golden-relationships)
    (contract/assert-pinned-permission-tree-golden! client)))

(deftest permission-tree-schema-mutation-stays-on-selected-snapshot-test
  (let [old-schema
        "definition user {}
         definition document {
           relation viewer: user
           permission view = viewer
         }"
        new-schema
        "definition user {}
         definition document {
           relation viewer: user
           relation editor: user
           permission view = viewer + editor
         }"
        conn (datascript/create-conn)
        client (datascript/make-client conn {})
        resource (eacl/spice-object :document "d1")
        _ (eacl/write-schema! client old-schema)
        _ (ds/transact! conn [{:eacl/id "d1"}])
        mutated? (atom false)
        captured
        (binding [backend/*invoke-observer*
                  (fn [{:keys [phase operation]}]
                    (when (and (= :before phase)
                               (= :relation-defs operation)
                               (compare-and-set! mutated? false true))
                      (eacl/write-schema! client new-schema)))]
          (eacl/expand-permission-tree
           client {:resource resource :permission :view}))]
    (is (= 1 (count (get-in captured
                            [:tree-root :intermediate :children]))))
    (is (= 2 (count (get-in
                     (eacl/expand-permission-tree
                      client {:resource resource :permission :view})
                     [:tree-root :intermediate :children]))))))

(defn- seed-objects!
  [conn]
  (ds/transact! conn
                (map-indexed (fn [idx {:keys [id]}]
                               {:db/id (- (inc idx))
                                :eacl/id id})
                             contract/smoke-objects)))

(def ^:private custom-codec-cache-schema
  "definition user {}
   definition document {
     relation reader: user
     permission view = reader
   }")

(deftest one-authority-is-the-only-production-engine-test
  (let [conn (datascript/create-conn)
        default-client (datascript/make-client conn {})
        default-selection
        (get-in default-client [:runtime :decision-kernel])
        error
        (try
          (datascript/make-client conn {:engine-selection :anything})
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) exception
            (ex-data exception)))]
    (is (satisfies? verified/DecisionKernel (:kernel default-selection)))
    (is (true? (get-in default-client [:runtime :managed-cache-enabled?])))
    (is (= :eacl/invalid-config (:type error)))
    (is (= [:engine-selection] (:unknown-keys error)))))

(deftest raw-retraction-requires-explicit-cache-expiry-test
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
    (datascript/expire-cache! client)
    (is (false? (eacl/can? client user :view document))
        "unsupported raw mutation is safe after every affected client expires")))

(deftest unsupported-mutation-recovery-requires-every-client-and-data-repair-test
  (let [conn (datascript/create-conn)
        client-a (datascript/make-client conn {})
        client-b (datascript/make-client conn {})
        user (eacl/spice-object :user "recovery-user")
        document (eacl/spice-object :document "recovery-document")
        relationship (eacl/->Relationship user :reader document)]
    (eacl/write-schema! client-a custom-codec-cache-schema)
    (ds/transact! conn [{:eacl/id (:id user)}
                        {:eacl/id (:id document)}])
    (eacl/create-relationship! client-a relationship)
    (doseq [client [client-a client-b]]
      (is (true? (eacl/can? client user :view document)))
      (is (true? (eacl/can? client user :view document))))

    (let [db (ds/db conn)
          raw-retractions
          (into []
                (mapcat
                 (fn [attribute]
                   (map (fn [datom]
                          [:db/retract (:e datom) attribute (:v datom)])
                        (ds/datoms db :aevt attribute))))
                [relationship-storage/forward-attribute
                 relationship-storage/reverse-attribute])]
      (ds/transact! conn raw-retractions))

    (testing "preparation and an identical schema write are not cache flushes"
      (is (false? (:changed?
                   (datascript/prepare-cache-coherence! conn))))
      (eacl/write-schema! client-a custom-codec-cache-schema)
      (is (true? (eacl/can? client-a user :view document)))
      (is (true? (eacl/can? client-b user :view document))))

    (testing "every process-local client must rotate after quiescence"
      (datascript/expire-cache! client-a)
      (is (false? (eacl/can? client-a user :view document)))
      (is (true? (eacl/can? client-b user :view document)))
      (datascript/expire-cache! client-b)
      (is (false? (eacl/can? client-b user :view document))))

    (testing "cache rotation does not repair a surviving peer tuple"
      (eacl/create-relationship! client-a relationship)
      (let [before (ds/db conn)
            user-eid (ds/entid before [:eacl/id (:id user)])
            document-eid (ds/entid before [:eacl/id (:id document)])]
        (ds/transact! conn [[:db.fn/retractEntity user-eid]])
        (datascript/expire-cache! client-a)
        (datascript/expire-cache! client-b)
        (is (seq (ds/datoms (ds/db conn) :eavt document-eid
                            relationship-storage/reverse-attribute)))
        (eacl/delete-object!
         client-a (eacl/spice-object :user user-eid))
        (is (empty? (ds/datoms (ds/db conn) :eavt document-eid
                               relationship-storage/reverse-attribute)))))))

(deftest removed-cache-coherence-options-are-unknown-test
  (let [conn (datascript/create-conn)]
    (doseq [[option values]
            [[:coherence-authority [:unknown :managed]]
             [:proof-mode [:auto :mutation :content :none]]]
            value values]
      (let [error
            (try
              (datascript/make-client conn {option value})
              nil
              (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) cause
                (ex-data cause)))]
        (is (= :eacl/invalid-config (:type error)))
        (is (= [option] (:unknown-keys error)))))))

(defn- custom-codec-options
  ([basis-observations]
   (custom-codec-options basis-observations {}))
  ([basis-observations extra]
   (merge
    {:security-key "01234567890123456789012345678901"
     :object-id->lookup-ref (fn [object-id] [:eacl/id object-id])
     :entid->object-id
     (fn [db eid]
       (swap! basis-observations conj (:max-tx db))
       (:eacl/id (ds/entity db eid)))}
    extra)))

(deftest shared-detailed-check-defaults-to-minimize-latency-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client
                conn
                {:cache cache/no-cache
                 :security-key "01234567890123456789012345678901"})
        user (eacl/spice-object :user "consistency-user")
        document (eacl/spice-object :document "consistency-document")
        demand {:subject user :permission :view :resource document}]
    (eacl/write-schema!
     client
     "definition user {}
      definition document {
        relation reader: user
        permission view = reader
      }")
    (ds/transact! conn [{:eacl/id (:id user)}
                        {:eacl/id (:id document)}])
    (let [default-stats (atom {})]
      (binding [source/*source-op-stats* default-stats]
        (eacl/check-permission client demand))
      (is (zero? (get @default-stats :acquire-authoritative! 0))))
    (let [explicit-stats (atom {})]
      (binding [source/*source-op-stats* explicit-stats]
        (eacl/check-permission
         client (assoc demand :consistency :fully-consistent)))
      (is (pos? (get @explicit-stats :acquire-authoritative! 0))))))

(deftest custom-codec-cache-isolation-and-selected-snapshot-rendering-test
  (let [conn (datascript/create-conn)
        observations (atom [])
        local-client
        (datascript/make-client conn (custom-codec-options observations))
        user (eacl/spice-object :user "codec-user")
        document-1 (eacl/spice-object :document "codec-document-1")
        document-2 (eacl/spice-object :document "codec-document-2")
        relationship-1 (eacl/->Relationship user :reader document-1)
        relationship-2 (eacl/->Relationship user :reader document-2)
        demand {:subject user :permission :view :resource document-1}]
    (eacl/write-schema! local-client custom-codec-cache-schema)
    (ds/transact! conn [{:eacl/id (:id user)}
                        {:eacl/id (:id document-1)}
                        {:eacl/id (:id document-2)}])
    (eacl/create-relationships!
     local-client [relationship-1 relationship-2])

    (testing "an unfingerprinted codec keeps safe client-local exact caching"
      (is (false? (get-in local-client [:runtime :managed-cache-enabled?])))
      (is (some? (get-in local-client [:runtime :basis-cache-store])))
      (is (true? (:allowed? (eacl/check-permission local-client demand))))
      (is (true? (:cached? (eacl/check-permission local-client demand))))
      (let [before (datascript/cache-stats local-client)]
        (ds/transact! conn [{:application/unrelated :one}])
        (is (true? (:allowed? (eacl/check-permission local-client demand))))
        (let [after (datascript/cache-stats local-client)]
          (is (= (:managed-hits before) (:managed-hits after)))
          (is (= (inc (:misses before)) (:misses after))))))

    (testing "a stable deterministic codec gets managed reuse and re-renders"
      (let [stable-observations (atom [])
            stable-client
            (datascript/make-client
             conn
             (custom-codec-options
              stable-observations
              {:adapter-fingerprint {:codec :eacl-id :version 1}
               :adapter-deterministic? true}))
            query {:subject user
                   :permission :view
                   :resource/type :document
                   :first 10}
            first-page (eacl/lookup-resources stable-client query)
            before (datascript/cache-stats stable-client)]
        (is (true? (get-in stable-client [:runtime :managed-cache-enabled?])))
        (is (= #{"codec-document-1" "codec-document-2"}
               (set (map :id (:data first-page)))))
        (ds/transact! conn [{:application/unrelated :two}])
        (reset! stable-observations [])
        (let [selected-basis (:max-tx (ds/db conn))
              second-page (eacl/lookup-resources stable-client query)
              after (datascript/cache-stats stable-client)]
          (is (= (:data first-page) (:data second-page)))
          (is (= (inc (:managed-hits before)) (:managed-hits after)))
          (is (seq @stable-observations))
          (is (every? #{selected-basis} @stable-observations)
              "managed semantic results are externalized from the selected DB"))))

    (testing "the declared codec round-trips injectively on visible objects"
      (let [db (ds/db conn)
            eids (mapv #(ds/entid db [:eacl/id %])
                       [(:id user) (:id document-1) (:id document-2)])
            externalize (get-in local-client [:runtime :entid->object-id])
            external-ids (mapv #(externalize db %) eids)
            internalize (get-in local-client [:runtime :object-id->entid])]
        (is (= (count external-ids) (count (distinct external-ids))))
        (is (= eids (mapv #(internalize db %) external-ids)))))))

(deftest custom-codec-cursors-require-a-stable-shared-fingerprint-test
  (let [conn (datascript/create-conn)
        setup (datascript/make-client conn {})
        user (eacl/spice-object :user "cursor-codec-user")
        documents [(eacl/spice-object :document "cursor-codec-doc-1")
                   (eacl/spice-object :document "cursor-codec-doc-2")]
        _ (eacl/write-schema! setup custom-codec-cache-schema)
        _ (ds/transact! conn (into [{:eacl/id (:id user)}]
                                   (map (fn [document]
                                          {:eacl/id (:id document)}))
                                   documents))
        _ (eacl/create-relationships!
           setup
           (mapv #(eacl/->Relationship user :reader %) documents))
        shared {:source-lifecycle "custom-codec-cursor-lifecycle"
                :security-key "01234567890123456789012345678901"}
        query {:subject user
               :permission :view
               :resource/type :document
               :first 1}
        local-a (datascript/make-client
                 conn (custom-codec-options (atom []) shared))
        local-b (datascript/make-client
                 conn (custom-codec-options (atom []) shared))
        local-cursor (get-in (eacl/lookup-resources local-a query)
                             [:page-info :end-cursor])
        local-error
        (try
          (eacl/lookup-resources local-b (assoc query :after local-cursor))
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
            (ex-data error)))
        stable-options
        (merge shared
               {:adapter-fingerprint {:codec :eacl-id :version 1}
                :adapter-deterministic? true})
        stable-a (datascript/make-client
                  conn (custom-codec-options (atom []) stable-options))
        stable-b (datascript/make-client
                  conn (custom-codec-options (atom []) stable-options))
        stable-cursor (get-in (eacl/lookup-resources stable-a query)
                              [:page-info :end-cursor])
        resumed (eacl/lookup-resources
                 stable-b (assoc query :after stable-cursor))]
    (is (some? local-error)
        "a client-local opaque codec identity cannot cross client lifecycles")
    (is (= 1 (count (:data resumed))))
    (is (not= (:id (first (:data (eacl/lookup-resources stable-a query))))
              (:id (first (:data resumed)))))))

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
        (is (= :eacl.pagination/stale-cursor (:type (ex-data error))))
        (is (= :frame-changed (:reason (ex-data error))))
        (let [fresh-error
              (try
                (eacl/lookup-resources client query)
                nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default)
                       thrown
                  (ex-data thrown)))]
          (is (= :eacl/unknown-relation-or-permission
                 (:type fresh-error))
              "a fresh enumeration validates against the new schema generation")
          (is (= :view (:permission fresh-error))))))))

;; Retired with the old engines (task 9.2): certified the
;; subproblem-cache denotation authority the stable engine does not use.

(deftest datascript-contract-test
  (let [conn   (datascript/create-conn)
        store  (contract/portable-store)
        client (datascript/make-client conn {:cache store})]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (eacl/create-relationships! client contract/smoke-relationships)
    (contract/assert-v8-seeded-contracts! client)
    (contract/assert-v8-permission-tree-contract! client)
    (contract/assert-authorization-target-matrix!
     {:writable client
      :read-only (datascript/make-client conn {:read-only? true})
      :snapshot-db datascript/db
      :direct-snapshot datascript/snapshot})
    (contract/assert-v8-request-cache-controls! client store)
    (contract/assert-v8-cache-disabled!
     (datascript/make-client conn {:cache cache/no-cache}))))

(deftest datascript-certified-generation-plan-reuse-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (eacl/create-relationships! client contract/smoke-relationships)
    (contract/assert-certified-generation-plan-reuse! client)))

(deftest datascript-recursive-v8-contract-test
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn
         {})]
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

;; Retired with the old engines (task 9.2): certified the
;; subproblem-cache denotation authority the stable engine does not use.

;; Retired with the old engines (task 9.2): certified the
;; subproblem-cache denotation authority the stable engine does not use.

(deftest demand-acyclic-cache-miss-matches-bypass-work-test
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn
         {})
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
              (eacl/read-relationships client {:subject/type :user
                                               :subject/id "user-1"})))))

    (testing "unrelated grants remain intact"
      (is (true? (eacl/can? client
                            (contract/->user "super-user")
                            :reboot
                            (contract/->server "server-1")))))))

(deftest datascript-large-relationship-cursor-skips-item-proof-test
  ;; Pinned to the managed/mutation-proof regime: the assertion is that the
  ;; v10 cursor design performs ZERO record-digest work per page. The
  ;; Automatic managed coherence reads ordered generations, never relationship
  ;; content digest per page - a separate, deliberate trade of the fail-safe
  ;; default, not a cursor property.
  (let [relationship-count 1505
        conn (datascript/create-conn)
        client (datascript/make-client
                conn
                {:cache cache/no-cache
                 })
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
    (testing "v8 acyclic cursors carry only the least-path boundary coords"
      ;; Order ABI v2 (acyclic-keyset-pagination): an acyclic root's
      ;; cursor is the boundary result's per-scan coordinate sequence —
      ;; self-contained, no ordinal, no checkpoint reference. Coordinates
      ;; stay INTERNAL inside the authenticated, basis-pinned envelope.
      (is (= [(contract/->server "server-1")] (:data page-1)))
      (is (= [(contract/->server "server-2")] (:data page-2)))
      (is (empty? (:data page-3)))
      (is (= 13 (:v envelope)))
      (is (= :least-path-edge
             (get-in envelope [:edge :kind])))
      (is (= :progress (get-in envelope [:edge :anchor])))
      (is (vector? (get-in envelope [:edge :coords])))
      (is (every? integer? (get-in envelope [:edge :coords])))
      (is (nil? (get-in envelope [:edge :ordinal])))
      (is (nil? (get-in envelope [:edge :result-eid])))
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
