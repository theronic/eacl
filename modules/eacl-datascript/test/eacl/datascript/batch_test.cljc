(ns eacl.datascript.batch-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [clojure.string :as str]
            [datascript.core :as ds]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.authorization.filters :as authorization-filters]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.execution :as execution]
            [eacl.request.counters :as request-counters]
            [eacl.subproblem-cache :as subproblem]))

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

(defn- scan-query
  [principal page]
  (merge
   {:resource/type :document
    :resource/relation :candidate
    :authorization {:subject principal
                    :permission :view
                    :on :resource}}
   page))

(defn- page-resource-ids
  [page]
  (mapv #(get-in % [:resource :id]) (:data page)))

(defn- walk-scan-forward
  [client query]
  (loop [query query
         pages []]
    (let [page (eacl/read-relationships client query)
          pages (conj pages page)]
      (if (get-in page [:page-info :has-next-page?])
        (recur (assoc query :after (get-in page [:page-info :end-cursor]))
               pages)
        pages))))

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

          :scan
          (let [{:keys [client dense]} (scan-fixture)]
            #(eacl/read-relationships
              client
              (assoc
               (scan-query
                dense
                {:first 5
                 :cache? false
                 :timeout-ms 1000
                 :aggregate-limits {:candidate-window 10}})
               :cancellation-token token)))

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
  (doseq [[route cutoff] [[:batch 12] [:scan 16] [:enumerate 25]]]
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

(deftest completed-artifacts-before-publication-failure-remain-independent-test
  (let [{:keys [client alice carol document]} (fixture)
        first-demand (demand alice :view document)
        second-demand (demand carol :view document)
        original-publish subproblem/publish!
        answer-attempts (atom 0)
        error
        (with-redefs
         [subproblem/publish!
          (fn [& args]
            (when (and (= :answer (second args))
                       (= 3 (swap! answer-attempts inc)))
              (throw
               (ex-info "injected publication failure"
                        {:type :test/publication-failure})))
            (apply original-publish args))]
         (caught
          #(eacl/check-permissions
            client {:checks [first-demand second-demand]})))]
    (is (= :test/publication-failure (:type (ex-data error))))
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

(deftest authorization-page-clauses-are-closed-before-selection-test
  (let [{:keys [conn client alice]} (fixture)
        invalid-scan
        {:resource/relation :viewer
         :authorization {:subject alice :permission :view :on :resource}
         :first 1}
        invalid-lookup
        {:subject alice
         :permission :view
         :resource/type :document
         :resource/relationship
         {:relation :viewer :subject alice :surprise true}
         :first 1}]
    (doseq [call [#(eacl/read-relationships client invalid-scan)
                  #(eacl/lookup-resources client invalid-lookup)]]
      (let [observation (observed-call conn #(caught call))]
        (is (= :eacl.filters/invalid-authorization-clause
               (:type (ex-data (:value observation)))))
        (is (zero? (:acquire-current! (:provider-calls observation) 0)))
        (is (zero? (:db-calls observation)))))

    (testing "selected-snapshot schema validation covers both clauses"
      (let [scan-error
            (caught
             #(eacl/read-relationships
               client
               {:resource/type :team
                :authorization
                {:subject alice :permission :admin :on :resource}
                :first 1}))
            lookup-error
            (caught
             #(eacl/lookup-resources
               client
               {:subject alice
                :permission :view
                :resource/type :document
                :resource/relationship
                {:relation :missing-relation :subject alice}
                :first 1}))]
        (is (= :eacl/unknown-relation-or-permission
               (:type (ex-data scan-error))))
        (is (= :admin (:permission (ex-data scan-error))))
        (is (= :eacl/unknown-relation-or-permission
               (:type (ex-data lookup-error))))
        (is (= :missing-relation (:relation (ex-data lookup-error))))))

    (is (= {:subject alice :permission :view :on :resource}
           (:authorization
            (authorization-filters/validate-scan-authorization!
             {:resource/type :document
              :authorization
              {:subject alice :permission :view :on :resource}}))))))

(deftest authorization-scan-dense-sentinel-pages-do-not-skip-test
  (let [{:keys [client dense documents]} (scan-fixture)
        query (scan-query dense {:first 2
                                 :aggregate-limits {:candidate-window 10}})
        ledger (request-counters/make-ledger)
        first-page
        (binding [request-counters/*ledger* ledger]
          (eacl/read-relationships client query))
        pages (walk-scan-forward client query)
        ids (mapcat page-resource-ids pages)
        counts (request-counters/snapshot ledger)]
    (is (= (mapv :id (take 2 documents))
           (page-resource-ids first-page)))
    (is (true? (get-in first-page [:page-info :has-next-page?])))
    (is (false? (get-in first-page [:page-info :bounded?])))
    (is (= 3 (:candidates-examined counts))
        "the dense page examines exactly the N+1 accepted sentinel")
    (is (= 1 (:public-entries counts)))
    (is (= 1 (:context-constructions counts)))
    (is (= (mapv :id documents) (vec ids))
        "the inclusive sentinel anchor is replayed, never omitted")
    (is (= (count ids) (count (distinct ids))))))

(deftest authorization-scan-sparse-and-all-rejected-windows-progress-test
  (let [{:keys [client sparse none]} (scan-fixture)
        sparse-query
        (scan-query sparse {:first 2
                            :aggregate-limits {:candidate-window 2}})
        sparse-pages (walk-scan-forward client sparse-query)]
    (is (= [["document-0"] ["document-3"] []]
           (mapv page-resource-ids sparse-pages)))
    (is (= [true true false]
           (mapv #(get-in % [:page-info :has-next-page?]) sparse-pages)))
    (is (= [true true false]
           (mapv #(get-in % [:page-info :bounded?]) sparse-pages)))
    (is (every? string?
                (map #(get-in % [:page-info :end-cursor])
                     (butlast sparse-pages))))

    (let [none-query
          (scan-query none {:first 2
                            :aggregate-limits {:candidate-window 2}})
          none-pages (walk-scan-forward client none-query)]
      (is (= [[] [] []] (mapv page-resource-ids none-pages)))
      (is (= [true true false]
             (mapv #(get-in % [:page-info :has-next-page?]) none-pages)))
      (is (= [true true false]
             (mapv #(get-in % [:page-info :bounded?]) none-pages)))
      (is (every? string?
                  (map #(get-in % [:page-info :end-cursor])
                       (butlast none-pages)))))))

(deftest authorization-scan-backward-and-recursive-permission-test
  (let [{:keys [client dense recursive]} (scan-fixture)
        dense-query
        (scan-query dense {:last 2
                           :aggregate-limits {:candidate-window 10}})
        last-page (eacl/read-relationships client dense-query)
        previous-page
        (eacl/read-relationships
         client
         (assoc dense-query
                :before (get-in last-page [:page-info :start-cursor])))]
    (is (= ["document-4" "document-5"]
           (page-resource-ids last-page)))
    (is (true? (get-in last-page [:page-info :has-previous-page?])))
    (is (false? (get-in last-page [:page-info :bounded?])))
    (is (= ["document-2" "document-3"]
           (page-resource-ids previous-page)))

    (is (= ["document-5"]
           (->> (walk-scan-forward
                 client
                 (scan-query
                  recursive
                  {:first 2
                   :aggregate-limits {:candidate-window 10}}))
                (mapcat page-resource-ids)
                vec)))))

(deftest authorization-scan-cursor-and-window-scope-are-closed-test
  (let [{:keys [client dense sparse]} (scan-fixture)
        query (scan-query dense {:first 1
                                 :aggregate-limits {:candidate-window 2}})
        page (eacl/read-relationships client query)
        cursor (get-in page [:page-info :end-cursor])]
    (doseq [changed [(assoc query :authorization
                            {:subject sparse :permission :view :on :resource})
                     (assoc-in query [:authorization :permission] :missing)
                     (-> query
                         (assoc :subject/type :user)
                         (assoc-in [:authorization :on] :subject))
                     (assoc query :first 2)
                     (assoc-in query [:aggregate-limits :candidate-window] 3)]]
      (let [error (caught #(eacl/read-relationships
                            client (assoc changed :after cursor)))]
        (is (= :eacl.pagination/invalid-cursor
               (:type (ex-data error)))
            (pr-str changed)))))

  (let [{:keys [conn client]} (scan-fixture)]
    (doseq [filters [{:resource/type :document :authorization nil :first 1}
                     {:resource/type :document
                      :authorization {:subject (object :user "dense")
                                      :permission :view
                                      :on :subject}
                      :first 1}]]
      (let [observation (observed-call
                         conn #(caught
                                (fn []
                                  (eacl/read-relationships client filters))))]
        (is (= :eacl.filters/invalid-authorization-clause
               (:type (ex-data (:value observation)))))
        (is (zero? (:acquire-current! (:provider-calls observation) 0)))))))

(deftest authorization-scan-cursor-proof-covers-stream-and-permission-test
  (testing "one complete cursor proof is derived for the combined closure"
    (let [{:keys [client dense]} (scan-fixture)
          backend-stats (atom {})]
      (binding [backend/*backend-op-stats* backend-stats]
        (eacl/read-relationships
         client
         (scan-query dense {:first 2
                            :aggregate-limits {:candidate-window 10}})))
      (is (= 1 (:proof-frame @backend-stats 0)))))

  (testing "candidate-stream mutation invalidates continuation"
    (let [{:keys [client dense marker documents]} (scan-fixture)
          query (scan-query dense {:first 2
                                   :aggregate-limits {:candidate-window 10}})
          page (eacl/read-relationships client query)
          _ (eacl/delete-relationship!
             client
             (eacl/->Relationship marker :candidate (last documents)))
          error
          (caught #(eacl/read-relationships
                    client
                    (assoc query :after
                           (get-in page [:page-info :end-cursor]))))]
      (is (= :eacl.pagination/stale-cursor
             (:type (ex-data error))))))

  (testing "authorization dependency mutation invalidates continuation"
    (let [{:keys [client dense documents]} (scan-fixture)
          query (scan-query dense {:first 2
                                   :aggregate-limits {:candidate-window 10}})
          page (eacl/read-relationships client query)
          _ (eacl/delete-relationship!
             client
             (eacl/->Relationship dense :viewer (nth documents 4)))
          error
          (caught #(eacl/read-relationships
                    client
                    (assoc query :after
                           (get-in page [:page-info :end-cursor]))))]
      (is (= :eacl.pagination/stale-cursor
             (:type (ex-data error)))))))

(deftest authorization-scan-boundaries-unknowns-and-confidentiality-test
  (let [{:keys [client dense sparse documents]} (scan-fixture)
        one-page
        (eacl/read-relationships
         client
         (scan-query sparse {:first 1
                             :aggregate-limits {:candidate-window 2}}))
        cursor (get-in one-page [:page-info :end-cursor])]
    (is (= ["document-0"] (page-resource-ids one-page)))
    (is (true? (get-in one-page [:page-info :bounded?])))
    (is (not (str/includes? (pr-str one-page) "document-1"))
        "a rejected candidate is absent from public row metadata")
    (is (not (str/includes? cursor "document-1"))
        "the progress anchor is encrypted")

    (let [maximum-page
          (eacl/read-relationships
           client
           (scan-query sparse {:first 10000
                               :aggregate-limits {:candidate-window 10000}}))]
      (is (= ["document-0" "document-3"]
             (page-resource-ids maximum-page)))
      (is (false? (get-in maximum-page [:page-info :has-next-page?])))
      (is (false? (get-in maximum-page [:page-info :bounded?]))))

    (doseq [page-control [{:first 0} {:first 10001}]]
      (is (= :eacl.pagination/invalid-cursor
             (:type
              (ex-data
               (caught
                #(eacl/read-relationships
                  client
                  (scan-query sparse page-control))))))))

    (let [missing-principal (object :user "missing-principal")
          missing-pages
          (walk-scan-forward
           client
           (scan-query
            missing-principal
            {:first 1 :aggregate-limits {:candidate-window 3}}))]
      (is (= [[] []] (mapv page-resource-ids missing-pages)))
      (is (= [true false]
             (mapv #(get-in % [:page-info :bounded?]) missing-pages))))

    (let [missing-anchor
          (eacl/read-relationships
           client
           (assoc
            (scan-query sparse {:first 1})
            :resource/id "missing-document"))]
      (is (= [] (:data missing-anchor)))
      (is (= false (get-in missing-anchor [:page-info :bounded?])))
      (is (nil? (get-in missing-anchor [:page-info :end-cursor]))))

    (is (= (mapv :id documents)
           (->> (walk-scan-forward
                 client
                 (scan-query
                  dense
                  {:first 1 :aggregate-limits {:candidate-window 2}}))
                (mapcat page-resource-ids)
                vec))
        "one-row pages preserve every candidate boundary")))

(deftest authorization-scan-failures-are-atomic-test
  (testing "cancellation inside a candidate decision returns no partial page"
    (let [{:keys [conn client dense]} (scan-fixture)
          token (execution/cancellation-token)
          cancelled? (atom false)
          observation
          (observed-call
           conn
           #(binding [backend/*invoke-observer*
                      (fn [{:keys [phase operation]}]
                        (when (and (= :before phase)
                                   (= :resource->subjects operation)
                                   (compare-and-set! cancelled? false true))
                          (execution/cancel! token)))]
              (caught
               (fn []
                 (eacl/read-relationships
                  client
                  (assoc
                   (scan-query dense {:first 2})
                   :cancellation-token token))))))]
      (is @cancelled?)
      (is (= :eacl.execution/cancelled
             (:type (ex-data (:value observation)))))
      (is (= 1 (:acquire-current! (:provider-calls observation) 0)))
      (is (= 1 (:release! (:provider-calls observation) 0)))))

  (testing "a candidate traversal limit is an error, never a denial"
    (let [{:keys [client recursive]}
          (scan-fixture
           {:cache cache/no-cache
            :recursive-traversal-limits {:max-advanced-datoms 1}})
          error
          (caught
           #(eacl/read-relationships
             client
             (scan-query recursive
                         {:first 2
                          :aggregate-limits {:candidate-window 10}})))]
      (is (= :eacl.recursive-traversal/limit-exceeded
             (:eacl/error (ex-data error)))))))

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

(deftest scan-and-enumerate-routes-have-the-same-resource-set-test
  (let [{:keys [client dense sparse recursive none]} (scan-fixture)]
    (doseq [relationship-subject [dense sparse recursive none]]
      (let [scan-ids
            (->> (walk-scan-forward
                  client
                  {:subject/type :user
                   :subject/id (:id relationship-subject)
                   :resource/type :document
                   :resource/relation :viewer
                   :authorization {:subject dense
                                   :permission :view
                                   :on :resource}
                   :first 1
                   :aggregate-limits {:candidate-window 2}})
                 (mapcat page-resource-ids)
                 set)
            enumerate-ids
            (->> (walk-lookup-forward
                  client
                  {:subject dense
                   :permission :view
                   :resource/type :document
                   :resource/relationship
                   {:relation :viewer :subject relationship-subject}
                   :first 1
                   :aggregate-limits {:candidate-window 2}})
                 (mapcat page-object-ids)
                 set)]
        (is (= scan-ids enumerate-ids)
            (pr-str relationship-subject))))))

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
              (scan-query dense {:first 1})
              :after cursor)))]
      (is (= :eacl.pagination/invalid-cursor
             (:type (ex-data route-error)))))))
