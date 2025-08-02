(ns eacl.datomic.datomic-helpers
  (:require [datomic.api]))

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
         @(datomic.api/transact ~sym ~schema)                         ; can fail.
         (do ~@body)
         (finally
           (datomic.api/release ~sym)
           (datomic.api/delete-database datomic-uri#))))))