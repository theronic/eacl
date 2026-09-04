(ns eacl.datomic.datomic-helpers
  (:require [datomic.api]
            [eacl.datomic.storage :as storage]
            [eacl.relationships.storage :as relationship-storage]))

(defn install-fixture-schema! [conn schema]
  @(datomic.api/transact conn schema)
  (when (some #(= relationship-storage/forward-attribute (:db/ident %)) schema)
    (storage/bootstrap! conn)))

(defmacro with-mem-conn
  "Like with-open for Datomic (for tests).
  Not under ~/test because needed by other modules.

  1. Creates unique in-memory Datomic.
  2. Transacts schema.
  3. Executes body with conn bound to sym.
  3. Deletes database after.

  Usage:
  (with-mem-conn [conn some-schema]
     @(d/transact conn tx-data)
     (is (= 123 (d/q '[:find ...] (d/db conn)))))"
  [[sym schema] & body]
  `(let [random-uuid# (java.util.UUID/randomUUID)
         datomic-uri# (str "datomic:mem://test-" (.toString random-uuid#))
         g#           (datomic.api/create-database datomic-uri#)]     ; can fail, but should not.
     (assert (true? g#) (str "Failed to create in-memory Datomic:" datomic-uri#))
     (let [~sym (datomic.api/connect datomic-uri#)]
       (try
         (install-fixture-schema! ~sym ~schema)
         (do ~@body)
         (finally
           (datomic.api/release ~sym)
           (datomic.api/delete-database datomic-uri#))))))

(defmacro with-mem-conns
  "Creates two independent connections to one temporary in-memory database."
  [[first-sym second-sym schema] & body]
  `(let [random-uuid# (java.util.UUID/randomUUID)
         datomic-uri# (str "datomic:mem://test-" (.toString random-uuid#))
         created?# (datomic.api/create-database datomic-uri#)]
     (assert (true? created?#)
             (str "Failed to create in-memory Datomic:" datomic-uri#))
     (let [~first-sym (datomic.api/connect datomic-uri#)
           ~second-sym (datomic.api/connect datomic-uri#)]
       (try
         (install-fixture-schema! ~first-sym ~schema)
         (do ~@body)
         (finally
           (datomic.api/release ~first-sym)
           (datomic.api/release ~second-sym)
           (datomic.api/delete-database datomic-uri#))))))
