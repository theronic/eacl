(ns eacl.operator-engine.datomic-experiments
  "Disposable million-resource local-transactor qualification probe.

  This namespace is exploration code. It creates a uniquely named Datomic
  `:dev` database, installs current EACL v7 storage, writes actual forward and
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
            [eacl.datomic.schema :as datomic-schema]
            [eacl.relationships.storage :as relationship-storage]))

(defonce !progress (atom {:status :idle}))
(defonce !run (atom nil))

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
  [conn resource-count batch-size started]
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
              relationship-ops
              (datomic-impl/optimistic-relationship-tx-data
               db relationship-ops)]
          @(d/transact conn (into entity-ops relationship-ops))
          (reset! !progress
                  {:status :seeding
                   :database-uri (:database-uri @!progress)
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
     :batches batches}))

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
            bounded-count
            (sample-nanos
             1 5
             #(eacl/count-resources
               client
               {:subject user :permission :candidate_view
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
          :adjacent-pages
          {:first-items (count (:data first-page))
           :second-items (count (:data second-page))
           :overlap
           (count (set/intersection
                   (set (:data first-page)) (set (:data second-page))))}
          :bounded-count
          (assoc (select-keys bounded-count [:median-nanos :p95-nanos])
                 :result (:last-result bounded-count))
          :default-exact-count
          {:nanos default-exact-count-nanos
           :outcome default-exact-count}
          :exhaustive-count
          {:nanos exhaustive-count-nanos
           :limits {:max-derived-grants 2000000
                    :max-advanced-datoms 2000000
                    :max-queued-work 2000000}
           :result exhaustive-count}
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
               @(d/transact conn datomic-schema/v7-schema)
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
               (read-qualification uri final-basis resource-count seeded started)]
           (reset! !progress {:status :complete :result result})
           result))
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
