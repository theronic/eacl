(ns eacl.datomic.impl.indexed
  "Compatibility façade over Datomic storage primitives and the shared v8
  authorization engine. No authorization traversal is implemented here."
  (:require [eacl.datomic.backend :as backend]
            [eacl.datomic.db :as ddb]
            [eacl.engine.v8 :as engine]))

(def default-recursive-traversal-limits
  engine/default-recursive-traversal-limits)

(def ^:dynamic *recursive-traversal-limits*
  default-recursive-traversal-limits)
(def ^:dynamic *recursive-traversal-stats* nil)

(defn object-eid
  [db object-id]
  (ddb/object-eid db object-id))

(defn relation-datoms
  [db resource-type relation-name]
  (ddb/relation-datoms db resource-type relation-name))

(defn find-relation-def
  [db resource-type relation-name]
  (ddb/find-relation-def db resource-type relation-name))

(defn find-permission-defs
  [db resource-type permission-name]
  (ddb/find-permission-defs db resource-type permission-name))

(defn schema-version
  [db]
  (ddb/schema-version db))

(defn- basis-adapter
  [db]
  (backend/basis-adapter db {}))

(defmacro ^:private with-engine-bindings
  [& body]
  `(binding [engine/*schema-cache* (engine/request-schema-cache)
             engine/*recursive-traversal-limits*
             *recursive-traversal-limits*
             engine/*recursive-traversal-stats*
             *recursive-traversal-stats*]
     ~@body))

(defn calc-permission-paths
  [db resource-type permission-name]
  (with-engine-bindings
    (engine/calc-permission-paths
     (basis-adapter db) resource-type permission-name)))

(defn get-permission-paths
  [db resource-type permission-name]
  (with-engine-bindings
    (engine/get-permission-paths
     (basis-adapter db) resource-type permission-name)))

(defn can?
  ([db subject permission resource]
   (with-engine-bindings
     (engine/can?
      (basis-adapter db) subject permission resource)))
  ([db {:keys [subject permission resource]}]
   (can? db subject permission resource)))

(defn lookup-resources
  ([db query]
   (lookup-resources db query nil))
  ([db query opts]
   (with-engine-bindings
     (engine/lookup-resources
      (basis-adapter db) query opts))))

(defn lookup-subjects
  ([db query]
   (lookup-subjects db query nil))
  ([db query opts]
   (with-engine-bindings
     (engine/lookup-subjects
      (basis-adapter db) query opts))))

(defn count-resources
  [db query]
  (with-engine-bindings
    (engine/count-resources (basis-adapter db) query)))

(defn count-subjects
  [db query]
  (with-engine-bindings
    (engine/count-subjects (basis-adapter db) query)))
