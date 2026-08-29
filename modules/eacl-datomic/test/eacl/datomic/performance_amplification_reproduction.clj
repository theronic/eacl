(ns eacl.datomic.performance-amplification-reproduction
  "Live and controlled Datomic evidence for the v8 performance audit.

  The live inventory is read-only. The exact-acquisition effect probe creates
  and deletes one uniquely named disposable database on the supplied
  transactor; it never writes to the seeded database."
  (:require [datomic.api :as d]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.datomic.backend :as datomic-backend]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.schema :as schema]))

(def source-base "e137dc55512d4eeebcc31cfbe5087d61ab04465b")

(defn- count-datoms
  [db attribute]
  (reduce (fn [total _] (inc total))
          0
          (d/datoms db :aevt attribute)))

(defn- measured
  [f]
  (let [started (System/nanoTime)
        value (f)]
    {:value value :elapsed-nanos (- (System/nanoTime) started)}))

(defn- schema-inventory
  [db]
  (let [value (schema/read-schema db)]
    {:relations
     (mapv #(select-keys
             %
             [:eacl.relation/resource-type
              :eacl.relation/relation-name
              :eacl.relation/subject-type])
           (:relations value))
     :permissions
     (mapv #(select-keys
             %
             [:eacl.permission/resource-type
              :eacl.permission/permission-name
              :eacl.permission/expression-payload])
           (:permissions value))}))

(defn- physical-cardinalities
  [db]
  {:forward-datoms
   (count-datoms
    db :eacl.v7.relationship/subject-type+relation+resource-type+resource)
   :reverse-datoms
   (count-datoms
    db :eacl.v7.relationship/resource-type+relation+subject-type+subject)
   :public-id-datoms (count-datoms db :eacl/id)
   :forward-by-shape
   (vec
    (sort-by
     pr-str
     (d/q
      '[:find ?subject-type ?relation-name ?resource-type (count ?resource)
        :where
        [_ :eacl.v7.relationship/subject-type+relation+resource-type+resource
         ?tuple]
        [(untuple ?tuple)
         [?subject-type ?relation ?resource-type ?resource]]
        [?relation :eacl.relation/relation-name ?relation-name]]
      db)))
   :distinct-resources-by-type
   (vec
    (sort-by
     pr-str
     (d/q
      '[:find ?resource-type (count-distinct ?resource)
        :where
        [_ :eacl.v7.relationship/subject-type+relation+resource-type+resource
         ?tuple]
        [(untuple ?tuple) [_ _ ?resource-type ?resource]]]
      db)))})

(defn- public-count-lanes
  [conn]
  (let [client
        (datomic/make-client
         conn
         {:cache cache/no-cache
          :security-key "performance-amplification-live-count-key"
          :recursive-traversal-limits
          {:max-derived-grants 10000000
           :max-advanced-datoms 10000000
           :max-queued-work 10000000}})
        query {:subject (eacl/spice-object :user "super-user")
               :permission :view
               :resource/type :server
               :cache? false}]
    {:count-30000
     (measured #(eacl/count-resources
                 client (assoc query :count-limit 30000)))
     :count-100000
     (measured #(eacl/count-resources
                 client (assoc query :count-limit 100000)))
     :count-1000000 (measured #(eacl/count-resources client query))}))

(defn- controlled-exact-probe
  [basis-source conn local-db caught-up-db target]
  (let [operation-counts (atom {:db 0 :sync 0 :as-of 0})
        original-as-of d/as-of
        selected
        (with-redefs
         [d/db
          (fn [actual-conn]
            (when-not (identical? conn actual-conn)
              (throw (ex-info "Unexpected exact-probe connection." {})))
            (swap! operation-counts update :db inc)
            local-db)
          d/sync
          (fn [actual-conn actual-target]
            (when-not (and (identical? conn actual-conn)
                           (= target actual-target))
              (throw (ex-info "Unexpected exact-probe synchronization." {})))
            (swap! operation-counts update :sync inc)
            (future caught-up-db))
          d/as-of
          (fn [db actual-target]
            (swap! operation-counts update :as-of inc)
            (original-as-of db actual-target))]
         (source/acquire!
          basis-source :exact
          {:revision target :exact-locator target}
          5000))]
    (try
      {:local-t (d/basis-t local-db)
       :target target
       :caught-up-t (d/basis-t caught-up-db)
       :operation-counts @operation-counts
       :selected-native-revision
       (backend/invoke (source/adapter selected) :native-revision)}
      (finally
        (source/release! selected)))))

(defn- exact-acquisition-effects
  [host port]
  (let [database-name
        (str "eacl-performance-amplification-exact-" (random-uuid))
        uri (str "datomic:dev://" host ":" port "/" database-name)]
    (when-not (d/create-database uri)
      (throw (ex-info "Could not create exact-acquisition probe database."
                      {:database-name database-name})))
    (let [conn (d/connect uri)]
      (try
        (let [local-before (d/db conn)
              _ @(d/transact conn [{:db/ident
                                    :eacl.performance/exact-probe-head}])
              head (d/db conn)
              target (d/basis-t head)
              _ @(d/transact conn [{:db/ident
                                    :eacl.performance/exact-probe-above}])
              above (d/db conn)
              basis-source
              (datomic-backend/source
               conn {:source-lifecycle
                     "performance-amplification-exact-probe"})]
          (when-not (< (d/basis-t local-before) target)
            (throw (ex-info "Disposable Datomic basis did not advance."
                            {:before (d/basis-t local-before)
                             :after target})))
          {:database-name database-name
           :seeded-database-modified? false
           :local-equal
           (controlled-exact-probe
            basis-source conn head head target)
           :local-above
           (controlled-exact-probe
            basis-source conn above above target)
           :local-behind
           (controlled-exact-probe
            basis-source conn local-before head target)})
        (finally
          (d/release conn)
          (when-not (d/delete-database uri)
            (throw
             (ex-info "Could not delete exact-acquisition probe database."
                      {:database-name database-name}))))))))

(defn live-report
  [{:keys [host port database]
    :or {host "localhost" port 4334 database "eacl-solidjs"}}]
  (let [uri (str "datomic:dev://" host ":" port "/" database)
        conn (d/connect uri)]
    (try
      (let [db (d/db conn)
            adapter (datomic-backend/basis-adapter db {})]
        {:evidence/version 1
         :source-base source-base
         :database-uri uri
         :database-names
         (vec (d/get-database-names
               (str "datomic:dev://" host ":" port "/*")))
         :database-identity
         {:database-id (str (.id ^datomic.Database db))
          :basis-t (d/basis-t db)
          :native-revision (backend/invoke adapter :native-revision)
          :snapshot-id (backend/invoke adapter :snapshot-id)
          :schema-generation (backend/invoke adapter :schema-generation)}
         :schema (schema-inventory db)
         :physical-cardinalities (physical-cardinalities db)
         :public-count-lanes (public-count-lanes conn)
         :exact-acquisition-effects
         (exact-acquisition-effects host port)})
      (finally
        (d/release conn)))))
