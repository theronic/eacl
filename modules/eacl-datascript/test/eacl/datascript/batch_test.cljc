(ns eacl.datascript.batch-test
  #?(:cljs (:require-macros [eacl.core :as eacl]))
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [clojure.string :as str]
            [datascript.core :as ds]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.execution :as execution]
            [eacl.engine.v8 :as engine]
            [eacl.request.counters :as request-counters]))

(def ^:private batch-schema
  "definition user {}

   definition team {
     relation member: user
     permission view = member
   }

   definition folder {
     relation viewer: user
     relation parent: folder
     permission view = viewer + parent->view
   }

   definition document {
     relation viewer: user
     relation parent: folder
     permission view = viewer + parent->view
   }")

(defn- object [type id]
  (eacl/spice-object type id))

(defn- demand [subject permission resource]
  {:subject subject :permission permission :resource resource})

(defn- fixture
  ([] (fixture {}))
  ([client-options]
   (let [conn (datascript/create-conn)
         client (datascript/make-client conn client-options)
         alice (object :user "alice")
         bob (object :user "bob")
         carol (object :user "carol")
         team (object :team "team-1")
         folder-1 (object :folder "folder-1")
         folder-2 (object :folder "folder-2")
         document (object :document "document-1")]
     (eacl/write-schema! client batch-schema)
     (ds/transact!
      conn
      (mapv (fn [id] {:eacl/id id})
            ["alice" "bob" "carol" "team-1" "folder-1" "folder-2"
             "document-1"]))
     (eacl/create-relationships!
      client
      [(eacl/->Relationship alice :viewer document)
       (eacl/->Relationship folder-1 :parent document)
       (eacl/->Relationship bob :viewer folder-1)
       (eacl/->Relationship folder-1 :parent folder-2)
       (eacl/->Relationship folder-2 :parent folder-1)
       (eacl/->Relationship alice :member team)])
     {:conn conn
      :client client
      :alice alice
      :bob bob
      :carol carol
      :team team
      :folder-1 folder-1
      :folder-2 folder-2
      :document document})))

(defn- caught
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Throwable :cljs :default) error
      error)))

(defn- observed-call
  [conn f]
  (let [provider-calls (atom {})
        db-calls (atom 0)
        original-db ds/db
        value
        (with-redefs [ds/db (fn [candidate]
                              (when (identical? conn candidate)
                                (swap! db-calls inc))
                              (original-db candidate))]
          (binding [source/*source-op-stats* provider-calls]
            (f)))]
    {:value value
     :provider-calls @provider-calls
     :db-calls @db-calls}))

(def ^:private scan-schema
  "definition user {}

   definition folder {
     relation viewer: user
     permission view = viewer
   }

   definition document {
     relation candidate: user
     relation viewer: user
     relation parent: folder
     permission view = viewer + viewer + parent->view
   }")

(defn- scan-fixture
  ([] (scan-fixture {}))
  ([client-options]
   (let [conn (datascript/create-conn)
         client (datascript/make-client conn client-options)
         dense (object :user "dense")
         sparse (object :user "sparse")
         recursive (object :user "recursive")
         none (object :user "none")
         marker (object :user "marker")
         folder (object :folder "folder")
         documents (mapv #(object :document (str "document-" %)) (range 6))]
     (eacl/write-schema! client scan-schema)
     (ds/transact!
      conn
      (mapv (fn [id] {:eacl/id id})
            (concat ["dense" "sparse" "recursive" "none" "marker"
                     "folder"]
                    (map :id documents))))
     (eacl/create-relationships!
      client
      (vec
       (concat
        (map #(eacl/->Relationship marker :candidate %) documents)
        (map #(eacl/->Relationship dense :viewer %) documents)
        [(eacl/->Relationship sparse :viewer (nth documents 0))
         (eacl/->Relationship sparse :viewer (nth documents 3))
         (eacl/->Relationship recursive :viewer folder)
         (eacl/->Relationship folder :parent (nth documents 5))])))
     {:conn conn
      :client client
      :dense dense
      :sparse sparse
      :recursive recursive
      :none none
      :marker marker
      :documents documents})))

(defn- page-resource-ids
  [page]
  (mapv #(get-in % [:resource :id]) (:data page)))

(defn- page-object-ids
  [page]
  (mapv :id (:data page)))

(defn- walk-lookup-forward
  [client query]
  (loop [query query
         pages []]
    (let [page (eacl/lookup-resources client query)
          pages (conj pages page)]
      (if (get-in page [:page-info :has-next-page?])
        (recur (assoc query :after (get-in page [:page-info :end-cursor]))
               pages)
        pages))))

(defn- aggregate-cut-point-trace
  [route failure-kind cutoff]
  (let [calls (atom 0)
        trace (atom [])
        token (execution/cancellation-token)
        clock
        (fn []
          (let [call (swap! calls inc)]
            (when (and (= :cancellation failure-kind)
                       (= cutoff call))
              (execution/cancel! token))
            (if (and (= :deadline failure-kind)
                     (>= call cutoff))
              2000000000
              0)))
        invoke!
        (case route
          :batch
          (let [{:keys [client alice carol document]} (fixture)]
            #(eacl/check-permissions
              client
              {:checks [(demand alice :view document)
                        (demand carol :view document)]
               :cache? false
               :timeout-ms 1000
               :cancellation-token token}))

          :enumerate
          (let [{:keys [client dense marker]} (scan-fixture)]
            #(eacl/lookup-resources
              client
              {:subject dense
               :permission :view
               :resource/type :document
               :resource/relationship
               {:relation :candidate :subject marker}
               :first 5
               :cache? false
               :timeout-ms 1000
               :cancellation-token token
               :aggregate-limits {:candidate-window 10}})))
        error
        (binding [execution/*monotonic-nanos* clock
                  backend/*invoke-observer*
                  (fn [{:keys [phase operation]}]
                    (when (= :before phase)
                      (swap! trace conj operation)))]
          (caught invoke!))]
    {:error (:type (ex-data error))
     :trace @trace
     :clock-calls @calls}))

(deftest aggregate-deadline-and-cancellation-share-semantic-cut-points-test
  (doseq [[route cutoff] [[:batch 12] [:enumerate 25]]]
    (let [deadline (aggregate-cut-point-trace route :deadline cutoff)
          cancellation
          (aggregate-cut-point-trace route :cancellation cutoff)]
      (is (= :eacl.execution/deadline-exceeded (:error deadline))
          (name route))
      (is (= :eacl.execution/cancelled (:error cancellation))
          (name route))
      (is (= cutoff (:clock-calls deadline) (:clock-calls cancellation))
          (name route))
      (is (= (:trace deadline) (:trace cancellation))
          (str (name route) " starts no different next semantic command"))
      (is (seq (:trace deadline)) (name route)))))

(deftest ordered-batch-refines-scalar-decisions-test
  (let [{:keys [client alice bob carol folder-2 document]} (fixture)
        checks
        [(demand alice :view document)
         (demand bob :view document)
         (demand carol :view document)
         (demand (object :user "missing") :view document)
         (demand alice :view (object :document "missing"))
         (demand bob :view folder-2)
         (demand alice :view document)]
        result
        (eacl/with-snapshot [snapshot (eacl/snapshot client)]
          (let [oracle
                (mapv #(eacl/check-permission
                        snapshot (assoc % :cache? false))
                      checks)
                actual
                (eacl/check-permissions
                 snapshot {:checks checks :cache? false})]
            {:oracle oracle :actual actual}))]
    (is (= (:oracle result) (:actual result)))
    (is (= [true true false false false true true]
           (mapv :allowed? (:actual result))))
    (is (= (count checks) (count (:actual result))))
    (is (= (first (:actual result)) (last (:actual result))))
    (is (every? #(false? (:cached? %)) (:actual result)))))

(deftest empty-and-composed-batches-have-one-or-zero-snapshot-scopes-test
  (let [{:keys [conn client alice document]} (fixture)
        ledger (request-counters/make-ledger)
        before-cache (datascript/cache-stats client)
        empty-observation
        (binding [request-counters/*ledger* ledger]
          (observed-call
           conn #(eacl/check-permissions client {:checks []})))
        after-empty (request-counters/snapshot ledger)]
    (is (= [] (:value empty-observation)))
    (is (= 0 (:acquire-current! (:provider-calls empty-observation) 0)))
    (is (= 0 (:release! (:provider-calls empty-observation) 0)))
    (is (zero? (:db-calls empty-observation)))
    (is (= before-cache (datascript/cache-stats client)))
    (is (zero? (:public-entries after-empty)))
    (is (zero? (:acquisitions after-empty)))

    (let [ledger (request-counters/make-ledger)
          observation
          (binding [request-counters/*ledger* ledger]
            (observed-call
             conn
             #(eacl/with-snapshot [snapshot (eacl/snapshot client)]
                (eacl/check-permissions
                 snapshot
                 {:checks [(demand alice :view document)]
                  :cache? false}))))
          counts (request-counters/snapshot ledger)]
      (is (true? (get-in observation [:value 0 :allowed?])))
      (is (= 1 (:acquire-current! (:provider-calls observation) 0)))
      (is (= 1 (:release! (:provider-calls observation) 0)))
      (is (= 1 (:db-calls observation)))
      (is (= {:public-entries 1
              :acquisitions 1
              :context-constructions 1
              :releases 1}
             (select-keys counts
                          [:public-entries :acquisitions
                           :context-constructions :releases]))))))

(deftest batch-reuses-one-root-plan-and-one-request-context-test
  (let [{:keys [conn client alice bob team folder-2 document]} (fixture)
        checks [(demand alice :view document)
                (demand bob :view document)
                (demand bob :view folder-2)
                (demand alice :view team)]
        first-ledger (request-counters/make-ledger)
        first-observation
        (binding [request-counters/*ledger* first-ledger]
          (observed-call
           conn #(eacl/check-permissions
                  client {:checks checks :cache? false})))
        first-counts (request-counters/snapshot first-ledger)
        second-ledger (request-counters/make-ledger)
        second-observation
        (binding [request-counters/*ledger* second-ledger]
          (observed-call
           conn #(eacl/check-permissions
                  client {:checks checks :cache? false})))
        second-counts (request-counters/snapshot second-ledger)]
    (is (= [true true true true]
           (mapv :allowed? (:value first-observation))))
    (is (= 1 (:acquire-current! (:provider-calls first-observation) 0)))
    (is (= 1 (:release! (:provider-calls first-observation) 0)))
    (is (= 1 (:public-entries first-counts)))
    (is (= 1 (:context-constructions first-counts)))
    (is (= 3 (:seals first-counts)))
    (is (= 3 (:definition-reads first-counts)))
    (is (= 1 (:acquire-current! (:provider-calls second-observation) 0)))
    (is (= 1 (:release! (:provider-calls second-observation) 0)))
    (is (zero? (:seals second-counts)))
    (is (zero? (:definition-reads second-counts)))))

(deftest per-position-cache-provenance-and-bypass-test
  (let [{:keys [client alice carol document]} (fixture)
        warm (demand alice :view document)
        cold (demand carol :view document)
        _ (eacl/check-permission client warm)
        checks [warm cold cold warm]
        first (eacl/check-permissions client {:checks checks})
        second (eacl/check-permissions client {:checks checks})
        bypassed
        (eacl/check-permissions client {:checks checks :cache? false})]
    (is (= [true false false true] (mapv :allowed? first)))
    (is (= [true false false true] (mapv :cached? first)))
    (is (= [true true true true] (mapv :cached? second)))
    (is (= [false false false false] (mapv :cached? bypassed)))
    (is (every? nil? (mapv :cache-basis bypassed)))))

(deftest source-advance-does-not-mix-a-batch-test
  (let [{:keys [client alice carol document]} (fixture)
        advanced? (atom false)
        checks [(demand alice :view document)
                (demand carol :view document)]
        captured
        (binding [backend/*invoke-observer*
                  (fn [{:keys [phase operation]}]
                    (when (and (= :before phase)
                               (= :resource->subjects operation)
                               (compare-and-set! advanced? false true))
                      (eacl/create-relationship!
                       client carol :viewer document)))]
          (eacl/check-permissions client {:checks checks :cache? false}))]
    (is @advanced?)
    (is (= [true false] (mapv :allowed? captured)))
    (is (= [true true]
           (mapv :allowed?
                 (eacl/check-permissions
                  client {:checks checks :cache? false}))))))

(deftest aggregate-and-request-wide-failures-name-the-pending-demand-test
  (let [{:keys [client alice carol document]} (fixture)
        checks [(demand alice :view document)
                (demand carol :view document)]]
    (testing "aggregate output and command limits"
      (let [output-error
            (caught
             #(eacl/check-permissions
               client
               {:checks checks
                :cache? false
                :aggregate-limits {:max-output-units 1}}))
            command-error
            (caught
             #(eacl/check-permissions
               client
               {:checks checks
                :cache? false
                :aggregate-limits {:max-commands 1}}))]
        (is (= :eacl.execution/resource-limit-exceeded
               (:type (ex-data output-error))))
        (is (= :output-units (:limit-kind (ex-data output-error))))
        (is (= 1 (:demand-index (ex-data output-error))))
        (is (= 2 (get-in (ex-data output-error)
                         [:aggregate-counters :output-units])))
        (is (= :eacl.execution/resource-limit-exceeded
               (:type (ex-data command-error))))
        (is (= :commands (:limit-kind (ex-data command-error))))
        (is (= 1 (:demand-index (ex-data command-error))))
        (is (pos? (get-in (ex-data command-error)
                          [:aggregate-counters :commands])))))

    (testing "pre-cancellation"
      (let [token (execution/cancellation-token)
            _ (execution/cancel! token)
            error
            (caught
             #(eacl/check-permissions
               client {:checks checks :cancellation-token token}))]
        (is (= :eacl.execution/cancelled (:type (ex-data error))))
        (is (= 0 (:demand-index (ex-data error))))
        (is (= 0 (get-in (ex-data error)
                         [:aggregate-counters :output-units])))))

    (testing "deadline and cancellation between demands"
      (doseq [failure-kind [:deadline :cancellation]]
        (let [clock-calls (atom 0)
              token (execution/cancellation-token)
              clock
              (fn []
                (let [call (swap! clock-calls inc)]
                  (when (and (= :cancellation failure-kind)
                             (= 12 call))
                    (execution/cancel! token))
                  (if (and (= :deadline failure-kind)
                           (> call 11))
                    2000000000
                    0)))
              error
              (binding [execution/*monotonic-nanos* clock]
                (caught
                 #(eacl/check-permissions
                   client
                   {:checks checks
                    :cache? false
                    :timeout-ms 1000
                    :cancellation-token token})))]
          (is (= (if (= :deadline failure-kind)
                   :eacl.execution/deadline-exceeded
                   :eacl.execution/cancelled)
                 (:type (ex-data error))))
          (is (= 1 (:demand-index (ex-data error))))
          (is (= 1 (get-in (ex-data error)
                           [:aggregate-counters :output-units]))))))))

(deftest scalar-traversal-limits-reset-per-demand-and-name-failure-test
  (let [{:keys [client bob folder-1 document]}
        (fixture
         {:cache cache/no-cache
          :recursive-traversal-limits {:max-derived-grants 1}})
        easy (demand bob :view folder-1)
        hard (demand bob :view document)
        error
        (caught
         #(eacl/check-permissions
           client {:checks [easy hard] :cache? false}))]
    (is (true? (:allowed? (eacl/check-permission client easy))))
    (is (= :eacl.recursive-traversal/limit-exceeded
           (:eacl/error (ex-data error))))
    (is (= 1 (:demand-index (ex-data error))))
    (is (= 1 (get-in (ex-data error)
                     [:aggregate-counters :output-units]))))

  (let [{:keys [client alice bob carol folder-1 document]}
        (fixture
         {:cache cache/no-cache
          :recursive-traversal-limits {:max-derived-grants 2}})
        checks [(demand alice :view document)
                (demand bob :view document)
                (demand carol :view folder-1)]
        scalar (mapv #(eacl/check-permission client %) checks)
        batched
        (eacl/check-permissions client {:checks checks :cache? false})]
    (is (= scalar batched)
        "batch sharing never tightens an independently successful scalar limit")))

(deftest schema-and-backend-failures-are-whole-and-balanced-test
  (let [{:keys [conn client alice carol document]} (fixture)
        valid (demand alice :view document)
        invalid (demand alice :missing-permission document)
        schema-ledger (request-counters/make-ledger)
        schema-observation
        (binding [request-counters/*ledger* schema-ledger]
          (observed-call
           conn
           #(caught
             (fn []
               (eacl/check-permissions
                client {:checks [valid invalid] :cache? false})))))
        schema-data (ex-data (:value schema-observation))]
    (is (keyword? (:type schema-data)))
    (is (= 1 (:demand-index schema-data)))
    (is (= 1 (:acquire-current! (:provider-calls schema-observation) 0)))
    (is (= 1 (:release! (:provider-calls schema-observation) 0)))

    (let [calls (atom 0)
          backend-ledger (request-counters/make-ledger)
          backend-observation
          (binding [request-counters/*ledger* backend-ledger
                    backend/*invoke-observer*
                    (fn [{:keys [phase operation]}]
                      (when (and (= :before phase)
                                 (= :object-id->internal operation)
                                 (= 3 (swap! calls inc)))
                        (throw
                         (ex-info "injected backend failure"
                                  {:type :test/backend-failure}))))]
            (observed-call
             conn
             #(caught
               (fn []
                 (eacl/check-permissions
                  client
                  {:checks [valid (demand carol :view document)]
                   :cache? false})))))
          backend-data (ex-data (:value backend-observation))]
      (is (= :test/backend-failure (:type backend-data)))
      (is (= 1 (:demand-index backend-data)))
      (is (= 1 (:acquire-current! (:provider-calls backend-observation) 0)))
      (is (= 1 (:release! (:provider-calls backend-observation) 0)))
      (is (= 1 (get-in backend-data
                       [:aggregate-counters :output-units]))))))

(deftest completed-artifacts-before-later-backend-failure-remain-independent-test
  (let [{:keys [client alice carol document]} (fixture)
        first-demand (demand alice :view document)
        second-demand (demand carol :view document)
        backend-calls (atom 0)
        error
        (binding
         [backend/*invoke-observer*
          (fn [{:keys [phase operation]}]
            (when (and (= :before phase)
                       (= :object-id->internal operation)
                       (= 3 (swap! backend-calls inc)))
              (throw
               (ex-info "injected backend failure"
                        {:type :test/backend-failure}))))]
          (caught
           #(eacl/check-permissions
             client {:checks [first-demand second-demand]})))]
    (is (= :test/backend-failure (:type (ex-data error))))
    (is (= 1 (:demand-index (ex-data error))))
    (is (true? (:cached?
                (eacl/check-permission client first-demand))))))

(deftest complete-shape-validation-precedes-selection-test
  (let [{:keys [conn client alice document]} (fixture)
        invalid (assoc (demand alice :view document) :timeout-ms 1)
        observation
        (observed-call
         conn
         #(caught
           (fn []
             (eacl/check-permissions
              client
              {:checks [(demand alice :view document) invalid]}))))]
    (is (= :eacl.batch/invalid-request
           (:type (ex-data (:value observation)))))
    (is (= 1 (:demand-index (ex-data (:value observation)))))
    (is (zero? (:acquire-current! (:provider-calls observation) 0)))
    (is (zero? (:release! (:provider-calls observation) 0)))
    (is (zero? (:db-calls observation)))))

(deftest lookup-page-clauses-are-closed-before-selection-test
  (let [{:keys [conn client alice]} (fixture)
        query {:subject alice :permission :view :resource/type :document
               :resource/relationship {:relation :viewer :subject alice :surprise true}
               :first 1}
        observation (observed-call conn #(caught (fn [] (eacl/lookup-resources client query))))]
    (is (= :eacl.filters/invalid-authorization-clause
           (:type (ex-data (:value observation)))))
    (is (zero? (:acquire-current! (:provider-calls observation) 0)))
    (is (zero? (:db-calls observation)))
    (let [error (caught (fn []
                          (eacl/lookup-resources
                           client
                           (assoc query :resource/relationship
                                  {:relation :missing-relation :subject alice}))))]
      (is (= :eacl/unknown-relation-or-permission (:type (ex-data error))))
      (is (= :missing-relation (:relation (ex-data error)))))))

(deftest relationship-filtered-lookup-windows-and-probes-test
  (let [{:keys [client dense sparse none]} (scan-fixture)
        query
        {:subject dense
         :permission :view
         :resource/type :document
         :resource/relationship {:relation :viewer :subject sparse}
         :first 2
         :aggregate-limits {:candidate-window 2}}
        ledger (request-counters/make-ledger)
        first-page
        (binding [request-counters/*ledger* ledger]
          (eacl/lookup-resources client query))
        counts (request-counters/snapshot ledger)
        pages (walk-lookup-forward client query)]
    (is (= ["document-0"] (page-object-ids first-page)))
    (is (true? (get-in first-page [:page-info :has-next-page?])))
    (is (true? (get-in first-page [:page-info :bounded?])))
    (is (= 2 (:candidates-examined counts)))
    (is (= 2 (:probes counts)))
    (is (= 1 (:public-entries counts)))
    (is (= ["document-0" "document-3"]
           (vec (mapcat page-object-ids pages))))

    (let [none-query
          (assoc-in query [:resource/relationship :subject] none)
          none-pages (walk-lookup-forward client none-query)]
      (is (= [[] [] []] (mapv page-object-ids none-pages)))
      (is (= [true true false]
             (mapv #(get-in % [:page-info :bounded?]) none-pages))))))

(deftest relationship-filtered-lookup-sentinel-and-backward-pages-test
  (let [{:keys [client dense marker]} (scan-fixture)
        dense-query
        {:subject dense
         :permission :view
         :resource/type :document
         :resource/relationship {:relation :candidate :subject marker}
         :first 2
         :aggregate-limits {:candidate-window 10}}
        ledger (request-counters/make-ledger)
        first-page
        (binding [request-counters/*ledger* ledger]
          (eacl/lookup-resources client dense-query))
        all-pages (walk-lookup-forward client dense-query)]
    (is (= ["document-0" "document-1"]
           (page-object-ids first-page)))
    (is (= 3 (:candidates-examined
              (request-counters/snapshot ledger))))
    (is (= 3 (:probes (request-counters/snapshot ledger))))
    (is (false? (get-in first-page [:page-info :bounded?])))
    (is (= (mapv #(str "document-" %) (range 6))
           (vec (mapcat page-object-ids all-pages))))

    (let [backward-query
          (-> dense-query
              (dissoc :first)
              (assoc :last 2 :evaluation :complete-denotation))
          last-page (eacl/lookup-resources client backward-query)
          previous-page
          (eacl/lookup-resources
           client
           (assoc backward-query
                  :before (get-in last-page [:page-info :start-cursor])))]
      (is (= ["document-4" "document-5"]
             (page-object-ids last-page)))
      (is (= ["document-2" "document-3"]
             (page-object-ids previous-page)))
      (is (true? (get-in last-page
                         [:page-info :has-previous-page?]))))))

(deftest relationship-filtered-lookup-subjects-test
  (let [{:keys [client documents]} (scan-fixture)
        resource (first documents)
        query
        {:resource resource
         :permission :view
         :subject/type :user
         :subject/relationship {:relation :viewer :resource resource}
         :first 1
         :aggregate-limits {:candidate-window 10}}
        ledger (request-counters/make-ledger)
        page-1
        (binding [request-counters/*ledger* ledger]
          (eacl/lookup-subjects client query))
        page-2
        (eacl/lookup-subjects
         client
         (assoc query :after (get-in page-1 [:page-info :end-cursor])))
        ids (into (page-object-ids page-1) (page-object-ids page-2))
        counts (request-counters/snapshot ledger)]
    (is (= #{"dense" "sparse"} (set ids)))
    (is (= 2 (:candidates-examined counts)))
    (is (= 2 (:probes counts)))
    (is (true? (get-in page-1 [:page-info :has-next-page?])))
    (is (false? (get-in page-1 [:page-info :bounded?])))))

(deftest relationship-filtered-lookup-cursors-bind-route-clause-and-window-test
  (let [{:keys [client dense sparse none]} (scan-fixture)
        query
        {:subject dense
         :permission :view
         :resource/type :document
         :resource/relationship {:relation :viewer :subject sparse}
         :first 1
         :aggregate-limits {:candidate-window 2}}
        page (eacl/lookup-resources client query)
        cursor (get-in page [:page-info :end-cursor])]
    (doseq [changed [(assoc-in query
                               [:resource/relationship :subject] none)
                     (assoc-in query
                               [:resource/relationship :relation] :candidate)
                     (assoc query :first 2)
                     (assoc-in query
                               [:aggregate-limits :candidate-window] 3)]]
      (let [error
            (caught #(eacl/lookup-resources
                      client (assoc changed :after cursor)))]
        (is (= :eacl.pagination/invalid-cursor
               (:type (ex-data error)))
            (pr-str changed))))

    (let [route-error
          (caught
           #(eacl/read-relationships
             client
             (assoc
              {:resource/type :document :resource/relation :candidate :first 1}
              :after cursor)))]
      (is (= :eacl.pagination/invalid-cursor
             (:type (ex-data route-error)))))))

(deftest direct-relationship-pages-retain-exclusion-in-explicit-checks-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {:clock (constantly 100)})
        viewer (object :user "viewer")
        parent (object :folder "folder")
        documents (mapv #(object :document (str "document-" %)) (range 60))
        query {:subject/type :folder :subject/id "folder"
               :resource/type :document :resource/relation :parent
               :first 25 :cache? false :populate-cache? false}]
    (eacl/write-schema! client
                        "definition user {}
 definition folder {}
 definition document {
 relation parent: folder
 relation reader: user
 relation banned: user
 permission view = reader - banned
 }")
    (ds/transact! conn (mapv (fn [obj] {:eacl/id (:id obj)}) (into [viewer parent] documents)))
    (eacl/create-relationships!
     client
     (into [(eacl/->Relationship viewer :banned (first documents))]
           (mapcat (fn [doc]
                     [(eacl/->Relationship parent :parent doc)
                      (eacl/->Relationship viewer :reader doc)])
                   documents)))
    (eacl/with-snapshot [snapshot (eacl/snapshot client)]
      (let [pages (with-redefs [engine/can? (fn [& _] (throw (ex-info "Direct read evaluated permission" {})))]
                    (loop [request query pages []]
                      (let [page (eacl/read-relationships snapshot request)
                            pages (conj pages page)]
                        (if (get-in page [:page-info :has-next-page?])
                          (recur (assoc query :after (get-in page [:page-info :end-cursor])) pages)
                          pages))))
            rows (vec (mapcat :data pages))
            checks (mapv #(demand viewer :view (:resource %)) rows)
            scalar (mapv #(eacl/can? snapshot (assoc % :cache? false)) checks)
            bulk (eacl/check-permissions snapshot {:checks checks :cache? false})]
        (is (= [25 25 10] (mapv (comp count :data) pages)))
        (is (= (mapv :id documents) (mapv (comp :id :resource) rows)))
        (is (= (into [false] (repeat 59 true)) scalar))
        (is (= scalar (mapv :allowed? bulk)))
        (let [resources (eacl/lookup-resources
                         snapshot
                         {:subject viewer :permission :view :resource/type :document
                          :first 100 :cache? false})
              subjects (eacl/lookup-subjects
                        snapshot
                        {:resource (first documents) :permission :view :subject/type :user
                         :first 10 :cache? false})]
          (is (= (set (map :id (rest documents)))
                 (set (map :id (:data resources)))))
          (is (empty? (:data subjects))))))))
