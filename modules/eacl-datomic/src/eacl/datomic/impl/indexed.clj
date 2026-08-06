(ns eacl.datomic.impl.indexed
  "Compatibility façade over Datomic storage primitives and the shared v8
  authorization engine. No authorization traversal is implemented here."
  (:require [eacl.datomic.backend :as backend]
            [eacl.datomic.db :as ddb]
            [eacl.engine.v8 :as engine]))

(def default-recursive-traversal-limits
  engine/default-recursive-traversal-limits)

(def ^:dynamic *schema-cache* nil)
(def ^:dynamic *recursive-traversal-limits*
  default-recursive-traversal-limits)
(def ^:dynamic *recursive-traversal-stats* nil)
(def ^:dynamic *count-stats* nil)

(defn object-eid
  [db object-id]
  (ddb/object-eid db object-id))

(defn subject->resources
  [db subject-type subject-id relation-id resource-type cursor-or-options]
  (ddb/subject->resources
   db subject-type subject-id relation-id resource-type cursor-or-options))

(defn resource->subjects
  [db resource-type resource-id relation-id subject-type cursor-or-options]
  (ddb/resource->subjects
   db resource-type resource-id relation-id subject-type cursor-or-options))

(defn relation-datoms
  [db resource-type relation-name]
  (ddb/relation-datoms db resource-type relation-name))

(defn find-relation-def
  [db resource-type relation-name]
  (ddb/find-relation-def db resource-type relation-name))

(defn find-permission-defs
  [db resource-type permission-name]
  (ddb/find-permission-defs db resource-type permission-name))

(defn all-permission-nodes
  [db]
  (ddb/all-permission-nodes db))

(defn direct-match-datoms-in-relationship-index
  [db subject-type subject-id relation-id resource-type resource-id]
  (if (ddb/direct-match?
       db subject-type subject-id relation-id resource-type resource-id)
    [true]
    []))

(defn schema-version
  [db]
  (ddb/schema-version db))

(defn schema-version-stamp
  [db]
  (some-> (schema-version db) str))

(defn make-schema-cache
  ([db]
   (make-schema-cache db (schema-version db)))
  ([db known-schema-version]
   {:database-id (str (.id ^datomic.Database db))
    :schema-version known-schema-version
    :permission-paths (atom {})
    :traversal-permissions (atom {})
    :traversal-analysis (atom nil)
    :relationship-dependencies (atom {})
    :recursive-plans (atom {})
    :direct-grant-relations (atom {})}))

(defn evict-permission-paths-cache!
  ([]
   (when *schema-cache*
     (evict-permission-paths-cache! *schema-cache*)))
  ([schema-cache]
   (reset! (:permission-paths schema-cache) {})
   (reset! (:traversal-permissions schema-cache) {})
   (some-> (:traversal-analysis schema-cache) (reset! nil))
   (reset! (:relationship-dependencies schema-cache) {})
   (some-> (:recursive-plans schema-cache) (reset! {}))
   (some-> (:direct-grant-relations schema-cache) (reset! {}))
   nil))

(defn- snapshot-adapter
  [db]
  (backend/snapshot-adapter
   db
   {:object-eid-fn object-eid
    :subject->resources-fn subject->resources
    :resource->subjects-fn resource->subjects}))

(defmacro ^:private with-engine-bindings
  [& body]
  `(binding [engine/*schema-cache* *schema-cache*
             engine/*recursive-traversal-limits*
             *recursive-traversal-limits*
             engine/*recursive-traversal-stats*
             *recursive-traversal-stats*
             engine/*count-stats* *count-stats*]
     ~@body))

(defn normalize-page-request
  [query]
  (engine/normalize-page-request query))

(defn calc-permission-paths
  [db resource-type permission-name]
  (with-engine-bindings
    (engine/calc-permission-paths
     (snapshot-adapter db) resource-type permission-name)))

(defn get-permission-paths
  [db resource-type permission-name]
  (if-not (some? (:schema-version *schema-cache*))
    (calc-permission-paths db resource-type permission-name)
    (let [key [resource-type permission-name]
          cache (:permission-paths *schema-cache*)
          cached @cache]
      (if (contains? cached key)
        (get cached key)
        (let [paths (calc-permission-paths
                     db resource-type permission-name)]
          (get (swap! cache
                      #(if (contains? % key)
                         %
                         (assoc % key paths)))
               key))))))

(defn permission-relationship-eids
  [db resource-type permission-name]
  (with-engine-bindings
    (engine/permission-relationship-eids
     (snapshot-adapter db) resource-type permission-name)))

(defn permission-schema-nodes
  [db resource-type permission-name]
  (with-engine-bindings
    (engine/permission-schema-nodes
     (snapshot-adapter db) resource-type permission-name)))

(defn traversal-permission?
  [db resource-type permission-name]
  (with-engine-bindings
    (engine/traversal-permission?
     (snapshot-adapter db) resource-type permission-name)))

(defn traversal-nodes
  [db]
  (with-engine-bindings
    (engine/traversal-nodes (snapshot-adapter db))))

(defn can?
  ([db subject permission resource]
   (with-engine-bindings
     (engine/can?
      (snapshot-adapter db) subject permission resource)))
  ([db {:keys [subject permission resource]}]
   (can? db subject permission resource)))

(defn lookup-resources
  ([db query]
   (lookup-resources db query nil))
  ([db query opts]
   (with-engine-bindings
     (engine/lookup-resources
      (snapshot-adapter db) query opts))))

(defn lookup-subjects
  ([db query]
   (lookup-subjects db query nil))
  ([db query opts]
   (with-engine-bindings
     (engine/lookup-subjects
      (snapshot-adapter db) query opts))))

(defn count-resources
  [db query]
  (with-engine-bindings
    (engine/count-resources (snapshot-adapter db) query)))

(defn count-subjects
  [db query]
  (with-engine-bindings
    (engine/count-subjects (snapshot-adapter db) query)))

(def continuation-weight engine/continuation-weight)
