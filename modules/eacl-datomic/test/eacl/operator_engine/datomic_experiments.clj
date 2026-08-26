(ns eacl.operator-engine.datomic-experiments
  "Disposable million-resource local-transactor qualification probe.

  This namespace is exploration code. It creates a uniquely named Datomic
  `:dev` database, installs current EACL v8 storage, writes actual forward and
  reverse relationship tuples in bounded transactions, reconnects for reads,
  and deletes only that unique database in `finally`."
  (:refer-clojure :exclude [run!])
  (:require [clojure.set :as set]
            [datomic.api :as d]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.db :as ddb]
            [eacl.datomic.impl :as datomic-impl]
            [eacl.datomic.impl.base :as base]
            [eacl.datomic.schema :as datomic-schema]
            [eacl.migrations.v7-to-v8 :as v7-to-v8]
            [eacl.relationships.storage :as relationship-storage])
  (:import (java.math BigInteger)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(defonce !progress (atom {:status :idle}))
(defonce !run (atom nil))
(defonce !v7-upgrade-progress (atom {:status :idle}))
(defonce !v7-upgrade-run (atom nil))

(defn status [] @!progress)

(defn- elapsed-ms [started]
  (/ (double (- (System/nanoTime) started)) 1000000.0))

(defn- percentile [values fraction]
  (let [sorted (vec (sort values))
        index (min (dec (count sorted))
                   (long (Math/floor (* fraction (count sorted)))))]
    (nth sorted index)))

(defn- sample-nanos [warmups iterations f]
  (dotimes [_ warmups] (f))
  (let [samples
        (vec
         (repeatedly
          iterations
          (fn []
            (let [started (System/nanoTime)
                  result (f)]
              {:nanos (- (System/nanoTime) started)
               :result result}))))
        nanos (mapv :nanos samples)]
    {:median-nanos (percentile nanos 0.50)
     :p95-nanos (percentile nanos 0.95)
     :last-result (:result (peek samples))}))

(defn- tuple-ops
  [subject-eid candidate-relation-eid selective-relation-eid
   batch-start batch-end]
  (let [resources
        (mapv
         (fn [index]
           {:index index
            :tempid (d/tempid :db.part/user)
            :id (format "r-%07d" index)})
         (range batch-start batch-end))
        entity-ops
        (mapv (fn [{:keys [tempid id]}]
                {:db/id tempid :eacl/id id})
              resources)
        relationship-ops
        (into
         []
         (mapcat
          (fn [{:keys [index tempid]}]
            (cond->
              [[:db/add subject-eid relationship-storage/forward-attribute
                [:user candidate-relation-eid :resource tempid]]
               [:db/add tempid relationship-storage/reverse-attribute
                [:resource candidate-relation-eid :user subject-eid]]]
              (zero? (mod index 4))
              (into
               [[:db/add subject-eid relationship-storage/forward-attribute
                 [:user selective-relation-eid :resource tempid]]
                [:db/add tempid relationship-storage/reverse-attribute
                 [:resource selective-relation-eid :user subject-eid]]])))
          resources))]
    {:entity-ops entity-ops
     :relationship-ops
     (conj relationship-ops
           (datomic-impl/tx-relation-version-stamp candidate-relation-eid)
           (datomic-impl/tx-relation-version-stamp selective-relation-eid))}))

(defn- seed-million!
  ([conn resource-count batch-size started]
   (seed-million! conn resource-count batch-size started !progress))
  ([conn resource-count batch-size started progress]
  (let [db (d/db conn)
        subject-eid (d/entid db [:eacl/id "probe-user"])
        candidate-relation-eid
        (:db/id (ddb/find-relation-def db :resource :candidate))
        selective-relation-eid
        (:db/id (ddb/find-relation-def db :resource :selective))
        batches (long (Math/ceil (/ (double resource-count) batch-size)))]
    (loop [batch 0]
      (when (< batch batches)
        (let [batch-start (* batch batch-size)
              batch-end (min resource-count (+ batch-start batch-size))
              db (d/db conn)
              {:keys [entity-ops relationship-ops]}
              (tuple-ops subject-eid candidate-relation-eid
                         selective-relation-eid batch-start batch-end)
              raw-relationship-ops relationship-ops
              relationship-ops
              (datomic-impl/optimistic-relationship-tx-data
               db raw-relationship-ops)
              tx-data (into entity-ops relationship-ops)
              invalid (filterv #(not (or (map? %) (sequential? %))) tx-data)]
          (when (seq invalid)
            (throw
             (ex-info "Datomic experiment produced invalid transaction data."
                      {:type :eacl.operator-engine/invalid-seed-tx
                       :invalid invalid
                       :raw-invalid
                       (filterv #(not (or (map? %) (sequential? %)))
                                raw-relationship-ops)
                       :optimized-tail (take-last 8 relationship-ops)})))
          @(d/transact conn tx-data)
          (reset! progress
                  {:status :seeding
                   :database-uri (:database-uri @progress)
                   :batch (inc batch)
                   :batches batches
                   :resources-committed batch-end
                   :candidate-relationships batch-end
                   :selective-relationships
                   (long (Math/ceil (/ (double batch-end) 4.0)))
                   :elapsed-ms (elapsed-ms started)})
          (recur (inc batch)))))
    {:subject-eid subject-eid
     :candidate-relation-eid candidate-relation-eid
     :selective-relation-eid selective-relation-eid
     :batches batches})))

(defn- exact-decisions
  [db subject-eid relation-eid candidates]
  (mapv #(ddb/direct-match?
          db :user subject-eid relation-eid :resource %)
        candidates))

(defn- prefix-decisions
  [db subject-eid relation-eid candidates]
  (let [last-eid (peek candidates)
        values
        (->> (ddb/subject->resources
              db :user subject-eid relation-eid :resource
              {:direction :asc
               :bound-eid (first candidates)
               :inclusive-bound? true})
             (take-while #(<= % last-eid))
             vec)
        present (set values)]
    {:decisions (mapv #(contains? present %) candidates)
     :values-scanned (count values)}))

(defn- adaptive-decisions
  [probe candidates demand]
  (loop [offset 0 width demand accepted [] physical 0 batches 0]
    (if (or (>= (count accepted) demand) (= offset (count candidates)))
      {:accepted (vec (take demand accepted))
       :physical-candidates physical
       :batches batches}
      (let [end (min (count candidates) (+ offset width))
            batch (subvec candidates offset end)
            decisions (probe batch)
            accepted'
            (into accepted
                  (keep-indexed
                   (fn [index decision]
                     (when decision (nth batch index))))
                  decisions)]
        (recur end
               (min 256 (- (count candidates) end) (* 2 width))
               accepted'
               (+ physical (count batch))
               (inc batches))))))

(defn- count-datoms [db attribute]
  (reduce (fn [count _] (inc count)) 0 (d/datoms db :aevt attribute)))

(defn- read-qualification
  [uri final-basis resource-count fixture started]
  (let [conn (d/connect uri)]
    (try
      @(d/sync conn final-basis)
      (let [db (d/db conn)
            client (datomic/make-client
                    conn {:cache cache/no-cache
                          :security-key
                          "operator-engine-datomic-experiment-key-01"})
            exhaustive-client
            (datomic/make-client
             conn {:cache cache/no-cache
                   :security-key
                   "operator-engine-datomic-experiment-key-02"
                   :recursive-traversal-limits
                   {:max-derived-grants 2000000
                    :max-advanced-datoms 2000000
                    :max-queued-work 2000000}})
            user (eacl/spice-object :user "probe-user")
            first-resource (eacl/spice-object :resource "r-0000000")
            last-resource
            (eacl/spice-object
             :resource (format "r-%07d" (dec resource-count)))
            missing-resource
            (eacl/spice-object :resource "r-missing")
            forward-query
            {:subject user :permission :candidate_view
             :resource/type :resource :first 20 :cache? false}
            intersection-query
            {:subject user :permission :intersection_view
             :resource/type :resource :first 20 :cache? false}
            exclusion-query
            {:subject user :permission :exclusion_view
             :resource/type :resource :first 20 :cache? false}
            first-page (eacl/lookup-resources client forward-query)
            second-page
            (eacl/lookup-resources
             client
             (assoc forward-query
                    :after (get-in first-page [:page-info :end-cursor])))
            point-present
            (sample-nanos 5 21 #(eacl/can? client user :candidate_view
                                           last-resource))
            point-missing
            (sample-nanos 5 21 #(eacl/can? client user :candidate_view
                                           missing-resource))
            first-page-sample
            (sample-nanos 3 11 #(eacl/lookup-resources client forward-query))
            intersection-page-sample
            (sample-nanos
             3 11 #(eacl/lookup-resources client intersection-query))
            exclusion-page-sample
            (sample-nanos 3 11 #(eacl/lookup-resources client exclusion-query))
            bounded-count
            (sample-nanos
             1 5
             #(eacl/count-resources
               client
               {:subject user :permission :candidate_view
                :resource/type :resource :count-limit 1000 :cache? false}))
            intersection-bounded-count
            (sample-nanos
             1 5
             #(eacl/count-resources
               client
               {:subject user :permission :intersection_view
                :resource/type :resource :count-limit 1000 :cache? false}))
            exclusion-bounded-count
            (sample-nanos
             1 5
             #(eacl/count-resources
               client
               {:subject user :permission :exclusion_view
                :resource/type :resource :count-limit 1000 :cache? false}))
            default-exact-count-started (System/nanoTime)
            default-exact-count
            (try
              {:result
               (eacl/count-resources
                client
                {:subject user :permission :candidate_view
                 :resource/type :resource :cache? false})}
              (catch clojure.lang.ExceptionInfo error
                {:error
                 (select-keys
                  (ex-data error)
                  [:eacl/error :limit-kind :limit])}))
            default-exact-count-nanos
            (- (System/nanoTime) default-exact-count-started)
            exhaustive-count-started (System/nanoTime)
            exhaustive-count
            (eacl/count-resources
             exhaustive-client
             {:subject user :permission :candidate_view
              :resource/type :resource :cache? false})
            exhaustive-count-nanos
            (- (System/nanoTime) exhaustive-count-started)
            intersection-exhaustive-count-started (System/nanoTime)
            intersection-exhaustive-count
            (eacl/count-resources
             exhaustive-client
             {:subject user :permission :intersection_view
              :resource/type :resource :cache? false})
            intersection-exhaustive-count-nanos
            (- (System/nanoTime) intersection-exhaustive-count-started)
            exclusion-exhaustive-count-started (System/nanoTime)
            exclusion-exhaustive-count
            (eacl/count-resources
             exhaustive-client
             {:subject user :permission :exclusion_view
              :resource/type :resource :cache? false})
            exclusion-exhaustive-count-nanos
            (- (System/nanoTime) exclusion-exhaustive-count-started)
            dense
            (->> (ddb/subject->resources
                  db :user (:subject-eid fixture)
                  (:candidate-relation-eid fixture) :resource nil)
                 (take 256)
                 vec)
            sparse
            (mapv
             #(d/entid db [:eacl/id (format "r-%07d" %)])
             (map #(quot (* % (dec resource-count)) 255) (range 256)))
            dense-exact-started (System/nanoTime)
            dense-exact
            (exact-decisions db (:subject-eid fixture)
                             (:selective-relation-eid fixture) dense)
            dense-exact-nanos (- (System/nanoTime) dense-exact-started)
            dense-prefix-started (System/nanoTime)
            dense-prefix
            (prefix-decisions db (:subject-eid fixture)
                              (:selective-relation-eid fixture) dense)
            dense-prefix-nanos (- (System/nanoTime) dense-prefix-started)
            sparse-exact-started (System/nanoTime)
            sparse-exact
            (exact-decisions db (:subject-eid fixture)
                             (:selective-relation-eid fixture) sparse)
            sparse-exact-nanos (- (System/nanoTime) sparse-exact-started)
            sparse-prefix-started (System/nanoTime)
            sparse-prefix
            (prefix-decisions db (:subject-eid fixture)
                              (:selective-relation-eid fixture) sparse)
            sparse-prefix-nanos (- (System/nanoTime) sparse-prefix-started)
            adaptive
            (adaptive-decisions
             #(exact-decisions db (:subject-eid fixture)
                               (:selective-relation-eid fixture) %)
             dense 21)
            forward-datoms
            (count-datoms db relationship-storage/forward-attribute)
            reverse-datoms
            (count-datoms db relationship-storage/reverse-attribute)]
        {:database-uri uri
         :basis-t (d/basis-t db)
         :resource-count resource-count
         :relationship-count forward-datoms
         :forward-tuple-datoms forward-datoms
         :reverse-tuple-datoms reverse-datoms
         :seed-batches (:batches fixture)
         :public
         {:present-check (select-keys point-present [:median-nanos :p95-nanos])
          :missing-check (select-keys point-missing [:median-nanos :p95-nanos])
          :first-page
          (assoc (select-keys first-page-sample [:median-nanos :p95-nanos])
                 :items (count (:data (:last-result first-page-sample))))
          :intersection-first-page
          (assoc
           (select-keys intersection-page-sample [:median-nanos :p95-nanos])
           :items (count (:data (:last-result intersection-page-sample)))
           :ids (mapv :id (:data (:last-result intersection-page-sample))))
          :exclusion-first-page
          (assoc
           (select-keys exclusion-page-sample [:median-nanos :p95-nanos])
           :items (count (:data (:last-result exclusion-page-sample)))
           :ids (mapv :id (:data (:last-result exclusion-page-sample))))
          :adjacent-pages
          {:first-items (count (:data first-page))
           :second-items (count (:data second-page))
           :overlap
           (count (set/intersection
                   (set (:data first-page)) (set (:data second-page))))}
          :bounded-count
          (assoc (select-keys bounded-count [:median-nanos :p95-nanos])
                 :result (:last-result bounded-count))
          :intersection-bounded-count
          (assoc
           (select-keys intersection-bounded-count [:median-nanos :p95-nanos])
           :result (:last-result intersection-bounded-count))
          :exclusion-bounded-count
          (assoc
           (select-keys exclusion-bounded-count [:median-nanos :p95-nanos])
           :result (:last-result exclusion-bounded-count))
          :default-exact-count
          {:nanos default-exact-count-nanos
           :outcome default-exact-count}
          :exhaustive-count
          {:nanos exhaustive-count-nanos
           :limits {:max-derived-grants 2000000
                    :max-advanced-datoms 2000000
                    :max-queued-work 2000000}
           :result exhaustive-count}
          :intersection-exhaustive-count
          {:nanos intersection-exhaustive-count-nanos
           :result intersection-exhaustive-count}
          :exclusion-exhaustive-count
          {:nanos exclusion-exhaustive-count-nanos
           :result exclusion-exhaustive-count}
          :first-resource-allowed?
          (eacl/can? client user :candidate_view first-resource)}
         :prototype
         {:dense
          {:candidate-count 256 :span 256
           :equal-decisions (= dense-exact (:decisions dense-prefix))
           :accepted (count (filter true? dense-exact))
           :exact-nanos dense-exact-nanos
           :prefix-nanos dense-prefix-nanos
           :prefix-values (:values-scanned dense-prefix)}
          :sparse
          {:candidate-count 256
           :span (inc (- (peek sparse) (first sparse)))
           :equal-decisions (= sparse-exact (:decisions sparse-prefix))
           :accepted (count (filter true? sparse-exact))
           :exact-nanos sparse-exact-nanos
           :prefix-nanos sparse-prefix-nanos
           :prefix-values (:values-scanned sparse-prefix)}
          :adaptive-demand adaptive}
         :total-elapsed-ms (elapsed-ms started)})
      (finally
        (d/release conn)))))

(defn- expected-selective-count [resource-count]
  (quot (+ resource-count 3) 4))

(defn- expected-first-ids [resource-count select?]
  (->> (range resource-count)
       (filter select?)
       (take 20)
       (mapv #(format "r-%07d" %))))

(defn- qualification-errors [result]
  (let [resource-count (:resource-count result)
        selective-count (expected-selective-count resource-count)
        public (:public result)
        prototype (:prototype result)]
    (cond-> []
      (not= (+ resource-count selective-count)
            (:forward-tuple-datoms result))
      (conj {:invariant :forward-tuple-count
             :expected (+ resource-count selective-count)
             :actual (:forward-tuple-datoms result)})

      (not= (:forward-tuple-datoms result)
            (:reverse-tuple-datoms result))
      (conj {:invariant :forward-reverse-tuple-duality
             :forward (:forward-tuple-datoms result)
             :reverse (:reverse-tuple-datoms result)})

      (not= (expected-first-ids resource-count
                                #(zero? (mod % 4)))
            (get-in public [:intersection-first-page :ids]))
      (conj {:invariant :intersection-first-page
             :actual (get-in public [:intersection-first-page :ids])})

      (not= (expected-first-ids resource-count
                                #(not (zero? (mod % 4))))
            (get-in public [:exclusion-first-page :ids]))
      (conj {:invariant :exclusion-first-page
             :actual (get-in public [:exclusion-first-page :ids])})

      (not= {:count 1000 :limit 1000 :truncated? true
             :cached? false :cache-basis nil}
            (get-in public [:intersection-bounded-count :result]))
      (conj {:invariant :intersection-bounded-count
             :actual (get-in public [:intersection-bounded-count :result])})

      (not= {:count 1000 :limit 1000 :truncated? true
             :cached? false :cache-basis nil}
            (get-in public [:exclusion-bounded-count :result]))
      (conj {:invariant :exclusion-bounded-count
             :actual (get-in public [:exclusion-bounded-count :result])})

      (not= resource-count
            (get-in public [:exhaustive-count :result :count]))
      (conj {:invariant :union-exact-count
             :expected resource-count
             :actual (get-in public [:exhaustive-count :result :count])})

      (not= selective-count
            (get-in public [:intersection-exhaustive-count :result :count]))
      (conj {:invariant :intersection-exact-count
             :expected selective-count
             :actual
             (get-in public [:intersection-exhaustive-count :result :count])})

      (not= (- resource-count selective-count)
            (get-in public [:exclusion-exhaustive-count :result :count]))
      (conj {:invariant :exclusion-exact-count
             :expected (- resource-count selective-count)
             :actual
             (get-in public [:exclusion-exhaustive-count :result :count])})

      (not= 0 (get-in public [:adjacent-pages :overlap]))
      (conj {:invariant :adjacent-page-disjointness
             :actual (get-in public [:adjacent-pages :overlap])})

      (not (true? (get-in prototype [:dense :equal-decisions])))
      (conj {:invariant :dense-scalar-prefix-equivalence})

      (not (true? (get-in prototype [:sparse :equal-decisions])))
      (conj {:invariant :sparse-scalar-prefix-equivalence}))))

(defn run!
  ([] (run! {}))
  ([{:keys [host port resource-count batch-size]
     :or {host "localhost" port 4334
          resource-count 1000000 batch-size 2000}}]
   (let [run-id (str (random-uuid))
         uri (str "datomic:dev://" host ":" port
                  "/eacl-operator-experiment-" run-id)
         started (System/nanoTime)
         created? (d/create-database uri)]
     (when-not created?
       (throw (ex-info "Could not create unique Datomic experiment database."
                       {:database-uri uri})))
     (reset! !progress {:status :installing :database-uri uri
                        :resource-count resource-count
                        :batch-size batch-size})
     (try
       (let [conn (d/connect uri)
             seeded
             (try
               @(d/transact conn datomic-schema/v8-schema)
               (let [client
                     (datomic/make-client
                      conn {:cache cache/no-cache
                            :security-key
                            "operator-engine-datomic-experiment-key-01"})]
                 (eacl/write-schema!
                  client
                  (str "definition user {}\n\n"
                       "definition resource {\n"
                       "  relation candidate: user\n"
                       "  relation selective: user\n"
                       "  permission candidate_view = candidate\n"
                       "  permission selective_view = selective\n"
                       "  permission intersection_view = candidate & selective\n"
                       "  permission exclusion_view = candidate - selective\n"
                       "}"))
                 @(d/transact conn [{:eacl/id "probe-user"}])
                 (seed-million! conn resource-count batch-size started))
               (finally
                 (d/release conn)))
             final-conn (d/connect uri)
             final-basis
             (try
               (d/basis-t (d/db final-conn))
               (finally (d/release final-conn)))]
         (reset! !progress (assoc @!progress :status :qualifying
                                  :final-basis final-basis))
         (let [result
               (read-qualification uri final-basis resource-count seeded started)
               errors (qualification-errors result)]
           (when (seq errors)
             (throw
              (ex-info "Datomic million-resource qualification failed."
                       {:type :eacl.operator-engine/datomic-qualification-failed
                        :errors errors})))
           (let [qualified-result
                 (assoc result :qualification
                        {:status :passed
                         :checked-invariants 12
                         :intersection-count
                         (expected-selective-count resource-count)
                         :exclusion-count
                         (- resource-count
                            (expected-selective-count resource-count))})]
             (reset! !progress {:status :complete :result qualified-result})
             qualified-result)))
       (catch Throwable error
         (let [last-progress @!progress]
           (reset! !progress
                   {:status :failed
                    :database-uri uri
                    :last-progress last-progress
                    :class (.getName (class error))
                    :message (.getMessage error)
                    :data (ex-data error)
                    :elapsed-ms (elapsed-ms started)}))
         (throw error))
       (finally
         (d/delete-database uri))))))

(defn start!
  ([] (start! {}))
  ([options]
   (when-let [running @!run]
     (when-not (future-done? running)
       (throw (ex-info "Datomic experiment is already running."
                       {:progress @!progress}))))
   (let [run (future (run! options))]
     (reset! !run run)
     {:started true :options options})))

;; ---------------------------------------------------------------------------
;; Released-v7 permission upgrade at the million-resource relationship shape
;; ---------------------------------------------------------------------------

(def ^:private v8-only-permission-idents
  #{:eacl/permission-storage-version
    :eacl.permission/expression-payload
    :eacl.permission/resource-type+permission-name})

(def ^:private legacy-permission-index-schema
  [{:db/ident
    :eacl.permission/resource-type+source-relation-name+target-type+permission-name
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/source-relation-name
                    :eacl.permission/target-type
                    :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident
    :eacl.permission/resource-type+source-relation-name+target-type+target-name
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/source-relation-name
                    :eacl.permission/target-type
                    :eacl.permission/target-name]
    :db/cardinality :db.cardinality/one
    :db/index true}
   {:db/ident
    :eacl.permission/resource-type+source-relation-name+target-type+target-name+permission-name
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/source-relation-name
                    :eacl.permission/target-type
                    :eacl.permission/target-name
                    :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}])

(def ^:private released-v7-schema
  (vec
   (concat
    (remove #(contains? v8-only-permission-idents (:db/ident %))
            datomic-schema/v7-schema)
    legacy-permission-index-schema)))

(def ^:private v7-upgrade-schema-string
  (str "definition user {}\n\n"
       "definition resource {\n"
       "  relation candidate: user\n"
       "  relation selective: user\n"
       "  permission candidate_view = candidate\n"
       "  permission selective_view = selective\n"
       "  permission intersection_view = candidate & selective\n"
       "  permission exclusion_view = candidate - selective\n"
       "}"))

(def ^:private released-v7-schema-string
  (str "definition user {}\n\n"
       "definition resource {\n"
       "  relation candidate: user\n"
       "  relation selective: user\n"
       "  permission candidate_view = candidate\n"
       "  permission selective_view = selective\n"
       "}"))

(def ^:private released-v7-definition-rows
  [(base/Relation :resource :candidate :user)
   (base/Relation :resource :selective :user)
   (base/Permission :resource :candidate_view {:relation :candidate})
   (base/Permission :resource :selective_view {:relation :selective})])

(defn- digest-line! [^MessageDigest digest value]
  (.update digest
           (.getBytes (str (pr-str value) "\n")
                      StandardCharsets/UTF_8)))

(defn- relationship-summary
  "Streams both tuple attributes in deterministic AEVT order. This is
  qualification-only before/after evidence, never part of the migration."
  [db]
  (let [digest (MessageDigest/getInstance "SHA-256")
        counts
        (into
         (sorted-map)
         (map
          (fn [attribute]
            [attribute
             (reduce
              (fn [count datom]
                (digest-line! digest [(:e datom) (:a datom) (:v datom)])
                (inc count))
              0
              (d/datoms db :aevt attribute))]))
         (sort relationship-storage/attributes))]
    {:counts counts
     :total (reduce + 0 (vals counts))
     :sha256 (format "%064x" (BigInteger. 1 (.digest digest)))}))

(defn v7-upgrade-status [] @!v7-upgrade-progress)

(defn run-v7-upgrade-million!
  "Creates and deletes one unique :dev database. The migration interval is
  instrumented independently from the before/after streaming digest scans."
  ([] (run-v7-upgrade-million! {}))
  ([{:keys [host port resource-count batch-size]
     :or {host "localhost" port 4334
          resource-count 1000000 batch-size 2000}}]
   (let [run-id (str (random-uuid))
         uri (str "datomic:dev://" host ":" port
                  "/eacl-v7-upgrade-experiment-" run-id)
         started (System/nanoTime)
         deleted? (atom false)]
     (when-not (d/create-database uri)
       (throw (ex-info "Could not create unique v7 upgrade database."
                       {:database-uri uri})))
     (reset! !v7-upgrade-progress
             {:status :installing
              :database-uri uri
              :resource-count resource-count
              :batch-size batch-size})
     (try
       (let [conn (d/connect uri)]
         (try
           @(d/transact conn released-v7-schema)
           (swap! !v7-upgrade-progress assoc :status :installing-definitions)
           @(d/transact conn released-v7-definition-rows)
           (swap! !v7-upgrade-progress assoc :status :installing-v7-stamp)
           @(d/transact conn [{:eacl/id "schema-string"
                               :eacl/schema-string released-v7-schema-string
                               :eacl/schema-version (d/squuid)}
                              {:eacl/id "probe-user"}])
           (swap! !v7-upgrade-progress assoc :status :seeding)
           (let [fixture (seed-million! conn resource-count batch-size started
                                        !v7-upgrade-progress)
                 before (relationship-summary (d/db conn))
                 relationship-index-reads (atom 0)
                 original-datoms d/datoms
                 original-index-range d/index-range]
             (reset! !v7-upgrade-progress
                     {:status :migrating
                      :database-uri uri
                      :resource-count resource-count
                      :before before
                      :elapsed-ms (elapsed-ms started)})
             (let [migration-report
                   (with-redefs
                    [d/datoms
                     (fn [db index & components]
                       (when (some relationship-storage/attributes components)
                         (swap! relationship-index-reads inc))
                       (apply original-datoms db index components))
                     d/index-range
                     (fn [db attribute start end]
                       (when (contains? relationship-storage/attributes
                                        attribute)
                         (swap! relationship-index-reads inc))
                       (original-index-range db attribute start end))]
                    (v7-to-v8/migrate!
                     conn {:schema v7-upgrade-schema-string}))
                   db-after (d/db conn)
                   after (relationship-summary db-after)
                   result
                   {:database-uri uri
                    :resource-count resource-count
                    :seed-batches (:batches fixture)
                    :before before
                    :after after
                    :relationship-index-reads-during-migration
                    @relationship-index-reads
                    :relationship-digest-identical? (= before after)
                    :migration-report migration-report
                    :permission-storage-shape
                    (datomic-schema/permission-storage-shape db-after)
                    :permission-storage-version
                    (v7-to-v8/stamped-permission-storage-version db-after)
                    :total-elapsed-ms (elapsed-ms started)}]
               (when-not (and (= before after)
                              (zero? @relationship-index-reads)
                              (= :expression
                                 (:permission-storage-shape result))
                              (= 8 (:permission-storage-version result)))
                 (throw
                  (ex-info "Datomic v7 upgrade qualification failed."
                           {:type :eacl.migration/qualification-failed
                            :result result})))
               (reset! !v7-upgrade-progress
                       {:status :qualified :result result})
               result))
           (finally
             (d/release conn))))
       (catch Throwable error
         (let [failed-at (:status @!v7-upgrade-progress)]
           (swap! !v7-upgrade-progress assoc
                  :status :failed
                  :failed-at failed-at
                  :class (.getName (class error))
                  :message (.getMessage error)
                  :data (ex-data error)
                  :elapsed-ms (elapsed-ms started)))
         (throw error))
       (finally
         (reset! deleted? (boolean (d/delete-database uri)))
         (swap! !v7-upgrade-progress assoc
                :database-deleted @deleted?
                :database-absence-confirmed
                (not (some #{(last (.split uri "/"))}
                           (d/get-database-names
                            (str "datomic:dev://" host ":" port "/*"))))))))))

(defn start-v7-upgrade-million!
  ([] (start-v7-upgrade-million! {}))
  ([options]
   (when-let [running @!v7-upgrade-run]
     (when-not (future-done? running)
       (throw (ex-info "Datomic v7 upgrade experiment is already running."
                       {:progress @!v7-upgrade-progress}))))
   (let [run (future (run-v7-upgrade-million! options))]
     (reset! !v7-upgrade-run run)
     {:started true :options options})))
