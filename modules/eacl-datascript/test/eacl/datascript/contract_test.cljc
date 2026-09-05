(ns eacl.datascript.contract-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.cache.standard-lru :as lru]
            [eacl.causal-token :as causal-token]
            [eacl.client.orchestration :as orchestration]
            [eacl.contract-support :as contract]
            [eacl.security.contract-support :as security-contract]
            [eacl.core :as eacl]
            [eacl.cursor :as cursor]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.schema :as schema]
            [eacl.engine.v8 :as engine]
            [eacl.execution :as execution]
            [eacl.proof-frame :as proof-frame]
            [eacl.relay :as relay]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.secure-format :as secure]
            [eacl.spicedb.consistency :as consistency]
            [eacl.verified-kernel :as verified]))

(defrecord CursorRecordId [value])

(deftest native-speculative-contract-test
  #?(:clj
     (is (nil? (ns-resolve 'eacl.datascript.core 'snapshot)))
     :cljs
     (is true))
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (contract/assert-speculative-contract!
     client
     #(ds/transact! conn [{:eacl/id "speculative-user"}
                          {:eacl/id "speculative-account"}]))))

(deftest portable-cache-api-round-trip-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})
        bounds {:max-entries 64}
        before (datascript/cache-content-revision client)
        snapshot (datascript/export-cache-snapshot client bounds)
        restored
        (datascript/restore-cache-snapshot! client snapshot bounds)]
    (is (= :eacl.cache/basis-snapshot-v2 (:format snapshot)))
    (is (zero? (:entry-count snapshot)))
    (is (true? (:restored? restored)))
    (is (> (datascript/cache-content-revision client) before))))

(deftest default-cache-capacity-has-one-public-authority-test
  (let [client (datascript/make-client (datascript/create-conn) {})
        stats (datascript/cache-stats client)]
    (is (= 1024 (get-in stats [:subproblems :tiers :answer :max-entries])))
    (is (= 1024 (get-in stats [:continuations :max-entries])))))

(deftest missing-anchor-validation-reuses-derived-schema-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})
        missing-user (contract/->user "missing")
        missing-server (contract/->server "missing")
        reads (atom 0)
        original-read-schema schema/read-authorization-schema
        exercise!
        (fn []
          (is (= []
                 (:data
                  (eacl/lookup-resources
                   client {:subject missing-user
                           :permission :view
                           :resource/type :server
                           :first 20
                           :cache? false}))))
          (is (= 0
                 (:count
                  (eacl/count-resources
                   client {:subject missing-user
                           :permission :view
                           :resource/type :server
                           :cache? false}))))
          (is (false?
               (eacl/can? client {:subject missing-user
                                  :permission :view
                                  :resource missing-server
                                  :cache? false}))))]
    (eacl/write-schema! client contract/smoke-schema)
    #?(:clj
       (with-redefs [schema/read-authorization-schema
                     (fn [db & format]
                       (swap! reads inc)
                       (apply original-read-schema db format))]
         (exercise!)
         (is (= 1 @reads)
             "missing-anchor validation decodes one immutable schema generation"))
       :cljs
       (exercise!))))

(deftest structural-metrics-refresh-preserves-results-and-cursors-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client
                conn {:security-key
                      "metrics-refresh00000000000000000"})
        alice (eacl/spice-object :user "metrics-alice")
        documents (mapv #(eacl/spice-object
                          :document (str "metrics-d" %))
                        (range 6))
        query {:subject alice
               :permission :view
               :resource/type :document
               :first 2
               :cache? false}]
    (eacl/write-schema!
     client
     "definition user {}
      definition document {
        relation reader: user
        permission view = reader
      }")
    (ds/transact!
     conn
     (into [{:eacl/id (:id alice)}]
           (map (fn [document] {:eacl/id (:id document)}) documents)))
    (eacl/create-relationships!
     client
     (mapv #(eacl/->Relationship alice :reader %) documents))
    (let [cold (eacl/lookup-resources client query)
          cold-stats (datascript/cache-stats client)]
      (is (= (subvec documents 0 2) (:data cold)))
      (is (not (contains? cold-stats :relationship-observations)))
      (is (pos? (get-in cold-stats
                        [:structural-metrics :entry-count])))

      (testing "default refresh clears only structural artifacts"
        (let [observed (atom [])
              refresh-report
              (binding [backend/*invoke-observer* #(swap! observed conj %)]
                (datascript/refresh-metrics! client))]
          (is (empty? @observed))
          (is (:structural-refreshed? refresh-report))
          (is (zero? (get-in refresh-report
                             [:structural-metrics :entry-count])))
          (let [continued
                (eacl/lookup-resources
                 client (assoc query :after
                               (get-in cold [:page-info :end-cursor])))]
            (is (= (subvec documents 2 4) (:data continued))))))

      (testing "eager refresh derives structural metrics without durable data"
        (let [report (datascript/refresh-metrics! client {:eager? true})]
          (is (:structural-refreshed? report))
          (is (pos? (get-in report [:structural-metrics :entry-count])))
          (is (pos? (get-in (datascript/cache-stats client)
                            [:structural-metrics :entry-count])))))

      (testing "removed relationship observation options fail closed"
        (let [data
              (try
                (datascript/make-client
                 conn {:relationship-observations? true})
                nil
                (catch #?(:clj Exception :cljs :default) error
                  (ex-data error)))]
          (is (= :eacl/invalid-config (:type data)))
          (is (= [:relationship-observations?]
                 (:unknown-keys data))))))))

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
        mutable-identity-client
        (datascript/make-client conn {:identity-immutable? false})
        default-selection
        (get-in default-client [:runtime :decision-kernel])
        identity-option-error
        (try
          (datascript/make-client conn {:identity-immutable? :assumed})
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) exception
            (ex-data exception)))
        error
        (try
          (datascript/make-client conn {:engine-selection :anything})
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) exception
            (ex-data exception)))]
    (is (satisfies? verified/DecisionKernel (:kernel default-selection)))
    (is (true? (get-in default-client [:runtime :managed-cache-enabled?])))
    (is (true? (get-in default-client
                       [:runtime :proof-equivalent-cursors?])))
    (is (= :selected-internal/immutable-external-injective-v3
           (get-in default-client [:runtime :identity-contract])))
    (is (false? (get-in mutable-identity-client
                        [:runtime :proof-equivalent-cursors?])))
    (is (= :eacl/invalid-config (:type identity-option-error)))
    (is (= :identity-immutable? (:key identity-option-error)))
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
      (is (= (not orchestration/*qualified-authorization-enabled?*)
             (eacl/can? client-a user :view document)))
      (is (= (not orchestration/*qualified-authorization-enabled?*)
             (eacl/can? client-b user :view document))))

    (testing "every process-local client must rotate after quiescence"
      (datascript/expire-cache! client-a)
      (is (false? (eacl/can? client-a user :view document)))
      (is (= (not orchestration/*qualified-authorization-enabled?*)
             (eacl/can? client-b user :view document)))
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
               :adapter-deterministic? true
               :identity-immutable? true}))
            query {:subject user
                   :permission :view
                   :resource/type :document
                   :first 10}
            first-page (eacl/lookup-resources stable-client query)
            before (datascript/cache-stats stable-client)]
        (is (true? (get-in stable-client [:runtime :managed-cache-enabled?])))
        (is (true? (get-in stable-client
                           [:runtime :proof-equivalent-cursors?])))
        (is (= #{"codec-document-1" "codec-document-2"}
               (set (map :id (:data first-page)))))
        (ds/transact! conn [{:application/unrelated :two}])
        (reset! stable-observations [])
        (let [selected-basis (:max-tx (ds/db conn))
              second-page (eacl/lookup-resources stable-client query)
              after (datascript/cache-stats stable-client)]
          (is (= (:data first-page) (:data second-page)))
          (is (= (cond-> (:managed-hits before)
                   (not orchestration/*qualified-authorization-enabled?*) inc)
                 (:managed-hits after)))
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

(deftest immutable-codec-rendered-hit-skips-per-item-identity-and-proof-work-test
  (let [conn (datascript/create-conn)
        identity-observations (atom [])
        internalization-calls (atom 0)
        client
        (datascript/make-client
         conn
         (custom-codec-options
          identity-observations
          {:adapter-fingerprint {:codec :rendered-fast-path :version 1}
           :adapter-deterministic? true
           :identity-immutable? true
           :object-id->lookup-ref
           (fn [object-id]
             (swap! internalization-calls inc)
             [:eacl/id object-id])}))
        user (eacl/spice-object :user "rendered-user")
        documents
        (mapv #(eacl/spice-object :document (str "rendered-document-" %))
              (range 65))
        query {:subject user
               :permission :view
               :resource/type :document
               :first 64}
        backend-work (atom {})
        cursor-work (atom {})]
    (eacl/write-schema! client custom-codec-cache-schema)
    (ds/transact!
     conn
     (into [{:eacl/id (:id user)}]
           (map (fn [document] {:eacl/id (:id document)}))
           documents))
    (eacl/create-relationships!
     client
     (mapv #(eacl/->Relationship user :reader %) documents))
    (binding [backend/*backend-op-stats* backend-work
              cursor/*codec-work* cursor-work]
      (let [cold (eacl/lookup-resources client query)]
        (is (= 64 (count (:data cold))))
        (is (false? (:cached? cold)))
        (reset! identity-observations [])
        (reset! internalization-calls 0)
        (reset! backend-work {})
        (reset! cursor-work {})
        (let [hit (eacl/lookup-resources client query)]
          (is (= (:data cold) (:data hit)))
          (is (true? (:cached? hit)))
          (is (zero? (count @identity-observations))
              "items and unsigned cursor edges are fully rendered on miss")
          (is (zero? @internalization-calls)
              "an exact first-page hit does not internalize its subject")
          (is (zero? (:proof-frame @backend-work 0))
              "the complete transport page eliminates proof work on a hit")
          (is (zero? (:decode-calls @cursor-work 0))))
        (let [continued
              (eacl/lookup-resources
               client
               (assoc query :after
                      (get-in cold [:page-info :end-cursor])))
              continued-query
              (assoc query :after
                     (get-in cold [:page-info :end-cursor]))]
          (is (= 1 (count (:data continued))))
          (is (= (set documents)
                 (into (set (:data cold)) (:data continued)))
              "the retained external edge round-trips into the next page")
          (reset! identity-observations [])
          (reset! internalization-calls 0)
          (reset! backend-work {})
          (reset! cursor-work {})
          (let [continued-hit
                (eacl/lookup-resources client continued-query)]
            (is (= (:data continued) (:data continued-hit)))
            (is (true? (:cached? continued-hit)))
            (is (zero? (count @identity-observations)))
            (is (zero? @internalization-calls)
                "the exact raw token is keyed before cursor decode")
            (is (zero? (:proof-frame @backend-work 0)))
            (is (zero? (:decode-calls @cursor-work 0))))
          (let [before-query
                (-> query
                    (dissoc :first)
                    (assoc :last 64
                           :before
                           (get-in continued [:page-info :start-cursor])))
                before-page (eacl/lookup-resources client before-query)]
            (is (= (:data cold) (:data before-page)))
            (reset! identity-observations [])
            (reset! internalization-calls 0)
            (reset! backend-work {})
            (reset! cursor-work {})
            (let [before-hit
                  (eacl/lookup-resources client before-query)]
              (is (= (:data before-page) (:data before-hit)))
              (is (true? (:cached? before-hit)))
              (is (zero? (count @identity-observations)))
              (is (zero? @internalization-calls))
              (is (zero? (:proof-frame @backend-work 0)))
              (is (zero? (:decode-calls @cursor-work 0))))))))
    (is (= 3 (:rendered-page-entries
              (datascript/cache-stats client))))))

(deftest mutable-codec-same-basis-bypasses-rendered-pages-test
  (let [conn (datascript/create-conn)
        prefix (atom "first-")
        identity-calls (atom 0)
        client
        (datascript/make-client
         conn
         {:security-key "01234567890123456789012345678901"
          :adapter-fingerprint {:codec :mutable-rendering :version 1}
          :adapter-deterministic? true
          :identity-immutable? false
          :object-id->lookup-ref (fn [object-id] [:eacl/id object-id])
          :entid->object-id
          (fn [db eid]
            (swap! identity-calls inc)
            (str @prefix (:eacl/id (ds/entity db eid))))})
        user (eacl/spice-object :user "mutable-rendered-user")
        document (eacl/spice-object :document "mutable-rendered-document")
        query {:subject user
               :permission :view
               :resource/type :document
               :first 10}]
    (eacl/write-schema! client custom-codec-cache-schema)
    (ds/transact! conn [{:eacl/id (:id user)}
                        {:eacl/id (:id document)}])
    (eacl/create-relationship!
     client (eacl/->Relationship user :reader document))
    (is (= ["first-mutable-rendered-document"]
           (mapv :id (:data (eacl/lookup-resources client query)))))
    (reset! prefix "second-")
    (reset! identity-calls 0)
    (let [same-basis-hit (eacl/lookup-resources client query)]
      (is (true? (:cached? same-basis-hit))
          "the safe internal completed answer remains reusable")
      (is (= ["second-mutable-rendered-document"]
             (mapv :id (:data same-basis-hit)))
          "mutable projection code reruns even at an unchanged DB basis")
      (is (pos? @identity-calls)))
    (is (= :selected-internal/current-external-injective-v2
           (get-in client [:runtime :identity-contract])))
    (is (zero? (:rendered-page-entries
                (datascript/cache-stats client))))))

(deftest custom-record-identities-are-rejected-before-cursor-type-erasure-test
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn
         {:security-key "01234567890123456789012345678901"
          :adapter-fingerprint {:codec :record-id :version 1}
          :adapter-deterministic? true
          :identity-immutable? true
          :object-id->lookup-ref
          (fn [object-id]
            [:eacl/id (if (record? object-id)
                        (:value object-id)
                        object-id)])
          :entid->object-id
          (fn [db eid]
            (->CursorRecordId (:eacl/id (ds/entity db eid))))})
        user (eacl/spice-object :user "record-user")
        query-user
        (eacl/spice-object :user (->CursorRecordId (:id user)))
        document-1 (eacl/spice-object :document "record-document-1")
        document-2 (eacl/spice-object :document "record-document-2")
        query {:subject query-user
               :permission :view
               :resource/type :document
               :first 1}]
    (eacl/write-schema! client custom-codec-cache-schema)
    (ds/transact! conn [{:eacl/id (:id user)}
                        {:eacl/id (:id document-1)}
                        {:eacl/id (:id document-2)}])
    (eacl/create-relationships!
     client
     [(eacl/->Relationship user :reader document-1)
      (eacl/->Relationship user :reader document-2)])
    (is (true?
         (eacl/can?
          client user :view
          (eacl/spice-object
           :document (->CursorRecordId (:id document-1)))))
        "record IDs remain usable for non-cursor operations")
    (let [error
          (try
            (eacl/lookup-resources client query)
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo) thrown
              (ex-data thrown)))]
      (is (= :eacl.pagination/unsupported-cursor-identity (:type error)))
      (is (= :query (:position error))))
    (is (zero? (:rendered-page-entries (datascript/cache-stats client))))))

(deftest list-and-vector-custom-identities-cannot-alias-transport-authority-test
  (let [conn (datascript/create-conn)
        list-user-id '("same")
        vector-user-id ["same"]
        stored-list-user "stored-list-user"
        stored-vector-user "stored-vector-user"
        document-list (eacl/spice-object :document "document-list")
        document-vector (eacl/spice-object :document "document-vector")
        client
        (datascript/make-client
         conn
         {:security-key "01234567890123456789012345678901"
          :adapter-fingerprint {:codec :sequential-type :version 1}
          :adapter-deterministic? true
          :identity-immutable? true
          :object-id->lookup-ref
          (fn [object-id]
            [:eacl/id
             (cond
               (list? object-id) stored-list-user
               (vector? object-id) stored-vector-user
               :else object-id)])
          :entid->object-id
          (fn [db eid]
            (:eacl/id (ds/entity db eid)))})
        list-user (eacl/spice-object :user list-user-id)
        vector-user (eacl/spice-object :user vector-user-id)
        query
        (fn [subject]
          {:subject subject
           :permission :view
           :resource/type :document
           :first 10})]
    (is (= list-user-id vector-user-id)
        "Clojure key equality deliberately equates these identity values")
    (eacl/write-schema! client custom-codec-cache-schema)
    (ds/transact! conn [{:eacl/id stored-list-user}
                        {:eacl/id stored-vector-user}
                        {:eacl/id (:id document-list)}
                        {:eacl/id (:id document-vector)}])
    (eacl/create-relationships!
     client
     [(eacl/->Relationship
       (eacl/spice-object :user stored-list-user) :reader document-list)
      (eacl/->Relationship
       (eacl/spice-object :user stored-vector-user) :reader document-vector)])
    (let [list-error
          (try
            (eacl/lookup-resources client (query list-user))
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo) thrown
              (ex-data thrown)))
          vector-page (eacl/lookup-resources client (query vector-user))
          vector-hit (eacl/lookup-resources client (query vector-user))]
      (is (= :eacl.pagination/unsupported-cursor-identity
             (:type list-error)))
      (is (= :query (:position list-error)))
      (is (= [document-vector] (:data vector-page)))
      (is (= [document-vector] (:data vector-hit)))
      (is (true? (:cached? vector-hit))))))

(deftest rendered-keys-copy-caller-owned-query-containers-test
  #?(:clj
     (let [conn (datascript/create-conn)
           client
           (datascript/make-client
            conn
            {:security-key "01234567890123456789012345678901"})
           user (eacl/spice-object :user "plain-key-user")
           document (eacl/spice-object :document "plain-key-document")
           request-owned (atom :request)
           comparator
           (fn [left right]
             ;; Deliberately retain request state in the collection policy.
             @request-owned
             (compare right left))
           query
           (into
            (sorted-map-by comparator)
            {:subject user
             :permission :view
             :resource/type :document
             :first 10})]
       (eacl/write-schema! client custom-codec-cache-schema)
       (ds/transact! conn [{:eacl/id (:id user)}
                           {:eacl/id (:id document)}])
       (eacl/create-relationship! client user :reader document)
       (is (= [document] (:data (eacl/lookup-resources client query))))
       (is (true? (:cached? (eacl/lookup-resources client query))))
       (let [runtime-lifecycle
             @(get-in client [:runtime :runtime-lifecycle-state])
             basis-store (:basis-cache-store runtime-lifecycle)
             rendered-store (:rendered-pages @(:lifecycle basis-store))
             resident-keys (map first (lru/entries rendered-store))
             children
             (fn [value]
               (cond
                 (map? value) (concat (keys value) (vals value))
                 (sequential? value) value
                 (set? value) value
                 :else nil))
             reachable
             (mapcat
              #(tree-seq (comp boolean seq children) children %)
              resident-keys)]
         (is (seq resident-keys))
         (is (not-any? #(identical? query %) reachable))
         (is (not-any? #(instance? clojure.lang.PersistentTreeMap %)
                       reachable)
             "the Caffeine key cannot retain the caller's comparator closure")))
     :cljs
     (is true)))

(deftest integer-representations-cannot-alias-cursor-authority-test
  #?(:clj
     (let [conn (datascript/create-conn)
           client
           (datascript/make-client
            conn
            {:security-key "01234567890123456789012345678901"
             :adapter-fingerprint {:codec :integer-class :version 1}
             :adapter-deterministic? true
             :identity-immutable? true
             :object-id->lookup-ref
             (fn [object-id]
               [:eacl/id
                (cond
                  (instance? clojure.lang.BigInt object-id) "integer-user-big"
                  (integer? object-id) "integer-user-long"
                  :else object-id)])
             :entid->object-id
             (fn [db eid]
               (let [stored-id (:eacl/id (ds/entity db eid))]
                 (case stored-id
                   "integer-user-big" 1N
                   "integer-user-long" 1
                   stored-id)))})
           stored-long-user (eacl/spice-object :user "integer-user-long")
           stored-big-user (eacl/spice-object :user "integer-user-big")
           long-document (eacl/spice-object :document "integer-document-long")
           big-document (eacl/spice-object :document "integer-document-big")
           long-user (eacl/spice-object :user 1)
           big-user (eacl/spice-object :user 1N)
           query
           (fn [subject]
             {:subject subject
              :permission :view
              :resource/type :document
              :first 1})]
       (is (= (:id long-user) (:id big-user))
           "Clojure key equality deliberately equates Long and BigInt")
       (eacl/write-schema! client custom-codec-cache-schema)
       (ds/transact! conn [{:eacl/id (:id stored-long-user)}
                           {:eacl/id (:id stored-big-user)}
                           {:eacl/id (:id long-document)}
                           {:eacl/id (:id big-document)}])
       (eacl/create-relationships!
        client
        [(eacl/->Relationship stored-long-user :reader long-document)
         (eacl/->Relationship stored-big-user :reader big-document)])
       (is (true? (eacl/can? client long-user :view long-document)))
       (is (false? (eacl/can? client long-user :view big-document)))
       (is (true? (eacl/can? client big-user :view big-document)))
       (is (false? (eacl/can? client big-user :view long-document))
           "non-cursor authorization may still use the custom numeric codec")
       (let [long-page (eacl/lookup-resources client (query long-user))
             token (get-in long-page [:page-info :end-cursor])
             error
             (try
               (eacl/lookup-resources
                client (assoc (query big-user) :after token))
               nil
               (catch clojure.lang.ExceptionInfo thrown
                 (ex-data thrown)))]
         (is (= [long-document] (:data long-page)))
         (is (= :eacl.pagination/unsupported-cursor-identity (:type error)))
         (is (= :query (:position error)))))
     :cljs
     (is true)))

(deftest noncanonical-permission-tree-root-identities-do-not-alias-test
  #?(:clj
     (let [conn (datascript/create-conn)
           client
           (datascript/make-client
            conn
            {:security-key "01234567890123456789012345678901"
             :adapter-fingerprint {:codec :tree-sequential-class :version 1}
             :adapter-deterministic? true
             :identity-immutable? true
             :object-id->lookup-ref
             (fn [object-id]
               [:eacl/id
                (cond
                  (list? object-id) "tree-document-list"
                  (vector? object-id) "tree-document-vector"
                  :else object-id)])
             :entid->object-id
             (fn [db eid]
               (:eacl/id (ds/entity db eid)))})
           alice (eacl/spice-object :user "tree-alice")
           stored-vector (eacl/spice-object :document "tree-document-vector")
           stored-list (eacl/spice-object :document "tree-document-list")
           tree-subjects
           (fn [root-id]
             (get-in
              (eacl/expand-permission-tree
               client
               {:resource (eacl/spice-object :document root-id)
                :permission :view})
              [:tree-root :intermediate :children 0 :leaf :subjects]))]
       (eacl/write-schema! client custom-codec-cache-schema)
       (ds/transact! conn [{:eacl/id (:id alice)}
                           {:eacl/id (:id stored-vector)}
                           {:eacl/id (:id stored-list)}])
       (eacl/create-relationship!
        client alice :reader stored-vector)
       (is (= [alice] (tree-subjects ["same"])))
       (is (empty? (tree-subjects '("same")))
           "equal host values distinguished by the codec must not share a completed tree"))
     :cljs
     (is true)))

(deftest metadata-sensitive-identities-cannot-alias-page-cursors-test
  (let [conn (datascript/create-conn)
        setup (datascript/make-client conn {})
        stored-user-a (eacl/spice-object :user "metadata-user-a")
        stored-user-b (eacl/spice-object :user "metadata-user-b")
        document-a (eacl/spice-object :document "metadata-document-a")
        document-b (eacl/spice-object :document "metadata-document-b")
        _ (eacl/write-schema! setup custom-codec-cache-schema)
        _ (ds/transact! conn [{:eacl/id (:id stored-user-a)}
                              {:eacl/id (:id stored-user-b)}
                              {:eacl/id (:id document-a)}
                              {:eacl/id (:id document-b)}])
        _ (eacl/create-relationships!
           setup
           [(eacl/->Relationship stored-user-a :reader document-a)
            (eacl/->Relationship stored-user-b :reader document-b)])
        request-owned-a (atom :request-a)
        request-owned-b (atom :request-b)
        external-id
        (fn [stored-id request-owned]
          (with-meta [:metadata-sensitive-user]
            {:stored-id stored-id
             :request-owned request-owned}))
        user-a
        (eacl/spice-object
         :user (external-id (:id stored-user-a) request-owned-a))
        user-b
        (eacl/spice-object
         :user (external-id (:id stored-user-b) request-owned-b))
        client
        (datascript/make-client
         conn
         {:security-key "01234567890123456789012345678901"
          :adapter-fingerprint {:codec :metadata-sensitive :version 1}
          :adapter-deterministic? true
          :identity-immutable? true
          :object-id->lookup-ref
          (fn [object-id]
            [:eacl/id (or (:stored-id (meta object-id)) object-id)])
          :entid->object-id
          (fn [db eid]
            (:eacl/id (ds/entity db eid)))})
        query
        (fn [subject]
          {:subject subject
           :permission :view
           :resource/type :document
           :first 10})
        error-data
        (fn [f]
          (try
            (f)
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo) error
              (ex-data error))))]
    (is (= (:id user-a) (:id user-b))
        "Clojure equality deliberately ignores metadata")
    (is (true? (eacl/can? client user-a :view document-a)))
    (is (false? (eacl/can? client user-a :view document-b)))
    (is (true? (eacl/can? client user-b :view document-b)))
    (is (false? (eacl/can? client user-b :view document-a))
        "non-cursor operations may still use a request-local metadata codec")
    (let [legacy-page
          ;; Simulate a token minted before metadata-free cursor identities
          ;; became mandatory. Its canonical query scope cannot distinguish
          ;; these two metadata-sensitive external IDs.
          (with-redefs [cache/cursor-cache-data? (constantly true)
                        cache/canonical-cursor-identity? (constantly true)]
            (eacl/lookup-resources
             client (assoc (query user-a) :cache? false)))
          legacy-token (get-in legacy-page [:page-info :end-cursor])
          cross-query-error
          (error-data
           #(eacl/lookup-resources
             client (assoc (query user-b) :after legacy-token)))]
      (is (string? legacy-token))
      (is (= :eacl.pagination/unsupported-cursor-identity
             (:type cross-query-error)))
      (is (= :query (:position cross-query-error))
          "a metadata-equal cursor cannot resume another principal's page"))
    (doseq [subject [user-a user-b]]
      (let [data
            (error-data
             #(eacl/lookup-resources client (query subject)))]
        (is (= :eacl.pagination/unsupported-cursor-identity
               (:type data)))
        (is (= :query (:position data)))))
    (is (zero? (:rendered-page-entries
                (datascript/cache-stats client)))
        "metadata-bearing public identities are never retained as pages")))

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
  (get-in stats [:subproblems :denotation-hits] 0))

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
    (contract/assert-v8-cache-differential! client)
    (contract/assert-v8-permission-tree-contract! client)
    (contract/assert-authorization-target-matrix!
     {:writable client
      :read-only (datascript/make-client conn {:read-only? true})})
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
        {:cache {}
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
    (let [_before (datascript/cache-stats client)]
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
        (is (not (contains? (:subproblems after)
                            :managed-projection-hits))
            "demand mode has no shared partial-projection cache")))

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
                {:cache cache/no-cache})
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
  ([]
   (seeded-client {}))
  ([options]
   (let [conn   (datascript/create-conn)
         client (datascript/make-client conn options)]
     (eacl/write-schema! client contract/smoke-schema)
     (seed-objects! conn)
     (eacl/create-relationships! client contract/smoke-relationships)
     client)))

(deftest expiring-cursors-bypass-complete-transport-page-reuse-test
  (let [base-client
        (seeded-client
         {:security-key "01234567890123456789012345678901"
          :cursor-ttl-seconds 10})
        at-time
        (fn [now]
          ;; Test-only runtime extension consumed by eacl.cursor/now-seconds.
          (assoc base-client
                 :runtime (assoc (:runtime base-client) :now-seconds now)))
        query {:subject (contract/->user "user-1")
               :permission :view
               :resource/type :server
               :first 1}
        first-page (eacl/lookup-resources (at-time 100) query)
        continued-query
        (assoc query :after (get-in first-page [:page-info :end-cursor]))
        _ (eacl/lookup-resources (at-time 100) continued-query)
        hit (eacl/lookup-resources (at-time 100) continued-query)
        expired
        (try
          (eacl/lookup-resources (at-time 110) continued-query)
          nil
          (catch #?(:clj clojure.lang.ExceptionInfo
                    :cljs cljs.core.ExceptionInfo) thrown
            (ex-data thrown)))]
    (is (true? (:cached? hit)))
    (is (= :eacl.pagination/expired-cursor (:type expired))
        "TTL policy rechecks the authenticated cursor even after a warm hit")
    (is (= 110 (:expired-at expired)))
    (is (zero? (:rendered-page-entries
                (datascript/cache-stats base-client)))
        "complete transport values are used only for non-expiring cursors")))

(deftest foreign-ttl-cursor-never-becomes-a-no-ttl-transport-hit-test
  (let [conn (datascript/create-conn)
        security-key "01234567890123456789012345678901"
        shared {:security-key security-key
                :source-lifecycle "cross-policy-cursor-expiry"}
        setup (datascript/make-client conn shared)
        _ (eacl/write-schema! setup contract/smoke-schema)
        _ (seed-objects! conn)
        _ (eacl/create-relationships! setup contract/smoke-relationships)
        ttl-base (datascript/make-client
                  conn (assoc shared :cursor-ttl-seconds 1))
        current-base (datascript/make-client conn shared)
        at-time
        (fn [client now]
          ;; Test-only runtime extension consumed by eacl.cursor/now-seconds.
          (assoc client :runtime (assoc (:runtime client)
                                        :now-seconds now)))
        query {:subject (contract/->user "user-1")
               :permission :view
               :resource/type :server
               :first 1}
        ttl-first (eacl/lookup-resources (at-time ttl-base 100) query)
        token (get-in ttl-first [:page-info :end-cursor])
        continued-query (assoc query :after token)
        live-page
        (eacl/lookup-resources (at-time current-base 100) continued-query)]
    (is (= 1 (count (:data live-page))))
    (is (zero? (:rendered-page-entries
                (datascript/cache-stats current-base)))
        "authenticated input expiry forbids pre-decode transport publication")
    (let [error
          (try
            (eacl/lookup-resources
             (at-time current-base 101) continued-query)
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo) thrown
              (ex-data thrown)))]
      (is (= :eacl.pagination/expired-cursor (:type error)))
      (is (= 101 (:expired-at error))))
    (is (zero? (:rendered-page-entries
                (datascript/cache-stats current-base))))))

(deftest transport-page-key-retains-the-authenticated-freshness-floor-test
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn {:security-key "01234567890123456789012345678901"})
        user (eacl/spice-object :user "floor-user")
        unrelated-user (eacl/spice-object :user "floor-unrelated-user")
        documents
        (mapv #(eacl/spice-object :document (str "floor-document-" %))
              (range 1 4))
        unrelated-document
        (eacl/spice-object :document "floor-unrelated-document")]
    (eacl/write-schema!
     client
     "definition user {}
      definition document {
        relation reader: user
        relation owner: user
        permission view = reader
      }")
    (ds/transact!
     conn
     (mapv (fn [object] {:eacl/id (:id object)})
           (into [user unrelated-user unrelated-document] documents)))
    (let [lower-floor
          (:zed/token
           (eacl/create-relationships!
            client
            (mapv #(eacl/->Relationship user :reader %) documents)))
          first-query
          {:subject user
           :permission :view
           :resource/type :document
           :first 1
           :consistency (consistency/at-least-as-fresh lower-floor)}
          first-page (eacl/lookup-resources client first-query)
          raw-cursor (get-in first-page [:page-info :end-cursor])
          lower-request (assoc first-query :after raw-cursor :first 2)
          lower-page (eacl/lookup-resources client lower-request)
          higher-floor
          (:zed/token
           (eacl/create-relationship!
            client
            (eacl/->Relationship
             unrelated-user :owner unrelated-document)))
          before-high (datascript/cache-stats client)
          error
          (try
            (eacl/lookup-resources
             client
             (assoc lower-request
                    :consistency
                    (consistency/at-least-as-fresh higher-floor)))
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo) thrown
              (ex-data thrown)))
          after-high (datascript/cache-stats client)]
      (is (= (subvec documents 1 3) (:data lower-page)))
      (is (= :eacl.consistency/cursor-consistency-conflict (:type error)))
      (is (= (:rendered-page-hits before-high)
             (:rendered-page-hits after-high))
          "a lower-floor page cannot predecode-hit a stricter request"))))

(deftest rendered-lookup-preserves-invalid-boundary-and-size-errors-test
  (let [client (seeded-client)
        base {:subject (contract/->user "user-1")
              :permission :view
              :resource/type :server}
        forward (assoc base :first 1)
        backward (assoc base :last 1)
        first-page (eacl/lookup-resources client forward)
        last-page (eacl/lookup-resources client backward)
        after-query
        (assoc forward :after (get-in first-page
                                      [:page-info :end-cursor]))
        before-query
        (assoc backward :before (get-in last-page
                                        [:page-info :start-cursor]))
        error-data
        (fn [query]
          (try
            (eacl/lookup-resources client query)
            nil
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo) error
              (ex-data error))))]
    ;; Populate every boundary-free and one-boundary key that a present nil
    ;; could otherwise alias after raw token fields are removed.
    (doseq [query [forward backward after-query before-query]]
      (eacl/lookup-resources client query)
      (is (true? (:cached? (eacl/lookup-resources client query)))))

    (doseq [query [(assoc forward :after nil)
                   (assoc backward :before nil)]]
      (is (= :eacl.pagination/invalid-cursor
             (:type (error-data query)))))

    (doseq [query [(assoc after-query :before nil)
                   (assoc before-query :after nil)]]
      (is (contains? #{:eacl.pagination/invalid-cursor
                       :eacl.pagination/invalid-page-request}
                     (:type (error-data query)))
          "a present nil second boundary must not hit a one-boundary page"))

    (doseq [invalid-size ["64" {:value 64}]]
      (is (= :eacl.pagination/invalid-page-size
             (:type
              (error-data (assoc base :first invalid-size))))))))

(deftest cache-disabled-reentrant-evaluation-clears-outer-denotation-context-test
  (let [nested-client (seeded-client)
        outer-conn (datascript/create-conn)
        outer-client* (atom nil)
        armed? (atom false)
        callback-count (atom 0)
        nested-observation (atom nil)
        object-id->lookup-ref
        (fn [object-id]
          (when (and @armed?
                     (= 2 (swap! callback-count inc)))
            ;; The relationship-filtered route performs this second conversion
            ;; inside the outer cached computation. Re-entering through a
            ;; supported codec callback must not let the nested bypass inherit
            ;; the outer request's exact-denotation store or key constructor.
            (let [before (:subproblems
                          (datascript/cache-stats @outer-client*))
                  allowed?
                  (eacl/can?
                   nested-client
                   (assoc {:subject (contract/->user "user-1")
                           :permission :reboot
                           :resource (contract/->server "server-1")}
                          :cache? false))
                  after (:subproblems
                         (datascript/cache-stats @outer-client*))]
              (reset! nested-observation
                      {:allowed? allowed?
                       :before (select-keys before
                                            [:lookup-probes :puts])
                       :after (select-keys after
                                           [:lookup-probes :puts])
                       :before-entries
                       (get-in before [:tiers :denotation :entries])
                       :after-entries
                       (get-in after [:tiers :denotation :entries])})))
          [:eacl/id object-id])
        outer-client
        (datascript/make-client
         outer-conn
         {:object-id->lookup-ref object-id->lookup-ref})]
    (reset! outer-client* outer-client)
    (eacl/write-schema! outer-client contract/smoke-schema)
    (seed-objects! outer-conn)
    (eacl/create-relationships!
     outer-client contract/smoke-relationships)
    (reset! callback-count 0)
    (reset! armed? true)
    (let [page
          (eacl/lookup-resources
           outer-client
           {:subject (contract/->user "user-1")
            :permission :view
            :resource/type :server
            :resource/relationship
            {:relation :account
             :subject (contract/->account "account-1")}
            :first 1})]
      (is (= ["server-1"] (mapv :id (:data page))))
      (is (= true (:allowed? @nested-observation)))
      (is (= (:before @nested-observation)
             (:after @nested-observation))
          "a cache-disabled nested request performs no outer-store probes or puts")
      (is (= (:before-entries @nested-observation)
             (:after-entries @nested-observation))
          "a cache-disabled nested request cannot publish under the outer basis"))))

(defn- stable-page-answer
  [page]
  {:data (:data page)
   :page-info
   (select-keys (:page-info page)
                [:has-next-page? :has-previous-page?])})

(defn- stable-count-answer
  [answer]
  (select-keys answer [:count :limit :truncated?]))

(declare thrown-data)

(deftest successful-result-caches-ignore-invocation-controls-test
  (let [client (seeded-client)
        user (contract/->user "user-1")
        server (contract/->server "server-1")
        cases
        [[:read-relationships
          #(eacl/read-relationships client %)
          {:resource/type :server :first 1}
          stable-page-answer]
         [:lookup-resources
          #(eacl/lookup-resources client %)
          {:subject user
           :permission :view
           :resource/type :server
           :first 1}
          stable-page-answer]
         [:lookup-subjects
          #(eacl/lookup-subjects client %)
          {:resource server
           :permission :view
           :subject/type :user
           :first 1}
          stable-page-answer]
         [:count-resources
          #(eacl/count-resources client %)
          {:subject user
           :permission :view
           :resource/type :server}
          stable-count-answer]
         [:count-subjects
          #(eacl/count-subjects client %)
          {:resource server
           :permission :view
           :subject/type :user}
          stable-count-answer]]]
    (doseq [[label invoke request answer] cases]
      (testing (name label)
        (let [token-a (eacl/cancellation-token)
              token-b (eacl/cancellation-token)
              first-request
              (assoc request
                     :timeout-ms 11000
                     :cancellation-token token-a
                     :cache? true
                     :populate-cache? true)
              second-request
              (assoc request
                     :timeout-ms 23000
                     :cancellation-token token-b
                     :cache? true
                     :populate-cache? false)
              cold (invoke first-request)
              hit (invoke second-request)
              bypass (invoke (assoc second-request :cache? false))
              hit-after-bypass (invoke first-request)]
          (is (= (answer cold) (answer hit) (answer bypass)
                 (answer hit-after-bypass)))
          (is (false? (:cached? cold)))
          (is (true? (:cached? hit))
              "a changed live timeout/token must not fragment identity")
          (is (false? (:cached? bypass))
              "lookup bypass remains an execution policy")
          (is (true? (:cached? hit-after-bypass))
              "bypass does not alter or evict the compatible resident value"))))
    (is (pos? (:exact-hits (datascript/cache-stats client))))))

(deftest warm-cache-reuse-obeys-current-deadline-and-cancellation-test
  (let [client (seeded-client)
        user (contract/->user "user-1")
        count-query {:subject user
                     :permission :view
                     :resource/type :server}
        page-query (assoc count-query :first 1)
        _ (eacl/count-resources client count-query)
        _ (is (true? (:cached?
                      (eacl/count-resources client count-query))))
        cancelled (eacl/cancellation-token)]
    (eacl/cancel! cancelled)
    (is (= :eacl.execution/cancelled
           (:type
            (thrown-data
             #(eacl/count-resources
               client (assoc count-query
                             :timeout-ms 1000
                             :cancellation-token cancelled))))))

    (testing "expiry before lookup cannot turn a warm value into a response"
      (let [clock-calls (atom 0)]
        (is (= :eacl.execution/deadline-exceeded
               (:type
                (binding
                 [execution/*monotonic-nanos*
                  #(if (= 1 (swap! clock-calls inc)) 0 1000000)]
                  (thrown-data
                   #(eacl/count-resources
                     client (assoc count-query :timeout-ms 1)))))))))

    (testing "expiry after completed-cache acquisition is still authoritative"
      (let [clock (atom 0)
            original cache/resolve-basis!
            data
            (binding [execution/*monotonic-nanos* #(deref clock)]
              (with-redefs
               [cache/resolve-basis!
                (fn [& args]
                  (let [result (apply original args)]
                    (reset! clock 100000000)
                    result))]
                (thrown-data
                 #(eacl/count-resources
                   client (assoc count-query :timeout-ms 100)))))]
        (is (= :eacl.execution/deadline-exceeded (:type data)))))

    (testing "cancellation after completed-cache acquisition is authoritative"
      (let [token (eacl/cancellation-token)
            original cache/resolve-basis!
            data
            (with-redefs
             [cache/resolve-basis!
              (fn [& args]
                (let [result (apply original args)]
                  (eacl/cancel! token)
                  result))]
              (thrown-data
               #(eacl/count-resources
                 client (assoc count-query
                               :timeout-ms 1000
                               :cancellation-token token))))]
        (is (= :eacl.execution/cancelled (:type data)))))

    (testing "expiry after transport-page acquisition prevents a response"
      (eacl/lookup-resources client page-query)
      (let [clock (atom 0)
            original cache/lookup-rendered-page!
            data
            (binding [execution/*monotonic-nanos* #(deref clock)]
              (with-redefs
               [cache/lookup-rendered-page!
                (fn [& args]
                  (let [result (apply original args)]
                    (reset! clock 100000000)
                    result))]
                (thrown-data
                 #(eacl/lookup-resources
                   client (assoc page-query :timeout-ms 100)))))]
        (is (= :eacl.execution/deadline-exceeded (:type data)))))))

(defn- thrown-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) ex
      (ex-data ex))))

(deftest oversized-raw-cursor-bypasses-rendered-cache-key-construction-test
  (let [client (seeded-client)
        lookups (atom 0)
        oversized-token
        (str cursor/cursor-prefix
             (apply str (repeat 65536 "x")))
        original cache/lookup-rendered-page!
        error
        (with-redefs
         [cache/lookup-rendered-page!
          (fn [& args]
            (swap! lookups inc)
            (apply original args))]
          (thrown-data
           #(eacl/lookup-resources
             client
             {:subject (contract/->user "user-1")
              :permission :view
              :resource/type :server
              :first 1
              :after oversized-token})))]
    (is (= :eacl.pagination/invalid-cursor (:type error)))
    (is (= :malformed-token (:reason error)))
    (is (zero? @lookups)
        "oversized unauthenticated input never reaches the transport cache")))

(deftest oversized-object-identities-bypass-rendered-cache-key-construction-test
  (let [client (seeded-client)
        lookups (atom 0)
        deep-id (nth (iterate vector "user-1") 40)
        wide-id (vec (range 1100))
        original cache/lookup-rendered-page!]
    (with-redefs
     [cache/lookup-rendered-page!
      (fn [& args]
        (swap! lookups inc)
        (apply original args))]
      (doseq [object-id [deep-id wide-id]]
        (let [page
              (eacl/lookup-resources
               client
               {:subject (eacl/spice-object :user object-id)
                :permission :view
                :resource/type :server
                :first 1})]
          (is (empty? (:data page)))
          (is (false? (:cached? page))))))
    (is (zero? @lookups)
        "oversized IDs are rejected before hashing a rendered cache key")))

(deftest expression-admission-limits-are-client-local-test
  (let [schema-source
        "definition user {}
         definition document {
           relation reader: user
           permission view = reader
         }"
        conn (datascript/create-conn)
        permissive
        (datascript/make-client
         conn {:security-key "client-limits-permissive00000000"})]
    (eacl/write-schema! permissive schema-source)
    ;; Warm the permissive client's derived generation before constructing a
    ;; stricter peer. The caches are client-owned and cannot cross this boundary.
    (is (= 1 (count (:permissions (eacl/read-schema permissive)))))
    (let [strict
          (datascript/make-client
           conn {:security-key "client-limits-strict000000000000"
                 :expression-limits {:maximum-source-nodes 0}})
          failure (thrown-data #(eacl/read-schema strict))]
      (is (= :eacl.schema/expression-limit (:type failure)))
      (is (= :node-count (:dimension failure)))
      (is (= 0 (:maximum failure)))
      (is (= 1 (:actual failure))))
    (is (= 1 (count (:permissions (eacl/read-schema permissive))))))
  (let [conn (datascript/create-conn)
        strict
        (datascript/make-client
         conn {:security-key "client-limits-writer000000000000"
               :expression-limits {:maximum-source-nodes 0}})
        failure
        (thrown-data
         #(eacl/write-schema!
           strict
           "definition user {}
            definition document {
              relation reader: user
              permission view = reader
            }"))]
    (is (= :eacl.schema/expression-limit (:type failure)))
    (is (empty? (:permissions (schema/read-schema (ds/db conn)))))))

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

(deftest live-security-keyring-rotation-contract-test
  (let [conn (datascript/create-conn)]
    (security-contract/assert-client-security!
     #(datascript/make-client conn %)
     #(ds/transact! conn (mapv (fn [{:keys [id]}] {:eacl/id id}) contract/smoke-objects))
     datascript/export-authenticated-cache-snapshot datascript/restore-authenticated-cache-snapshot!)))
