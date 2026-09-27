(ns eacl.datascript.wildcard-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            #?(:clj [clojure.java.io :as io])
            [datascript.core :as ds]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.cache :as cache]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.schema :as schema]
            [eacl.wildcard-contract-support :as contract]))

(defn- seed!
  [conn ids]
  (ds/transact! conn (mapv (fn [id] {:eacl/id id}) ids)))

(defn- seed-objects!
  [conn]
  (seed! conn contract/objects))

(deftest datascript-wildcard-contract-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (seed-objects! conn)
    (contract/assert-wildcard-contract! client #(seed! conn %))))

(deftest datascript-wildcard-without-cache-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {:cache cache/no-cache})]
    (seed-objects! conn)
    (contract/assert-wildcard-contract! client #(seed! conn %))))

(deftest datascript-wildcard-speculative-contract-test
  (let [conn (datascript/create-conn)]
    (seed-objects! conn)
    (contract/assert-wildcard-speculative-contract!
     (datascript/make-client conn {}))))

(deftest datascript-wildcard-portable-caveat-contract-test
  (let [conn (datascript/create-conn)
        now-ms (atom 1790000000000)
        client (datascript/make-client
                conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))
                      :clock #(deref now-ms)})]
    (seed-objects! conn)
    (contract/assert-wildcard-caveat-contract! client now-ms)))

#?(:clj
   (defn- jvm-evaluator
     "The certified JVM CEL evaluator. The workspace test classpath has
     eacl-caveats-jvm; an isolated eacl-datascript classpath does not."
     []
     (when-let [evaluator (try (requiring-resolve 'eacl.caveats.jvm/evaluator)
                               (catch java.io.FileNotFoundException _ nil))]
       (evaluator))))

#?(:clj
   (deftest datascript-wildcard-jvm-caveat-contract-test
     (if-let [evaluator (jvm-evaluator)]
       (let [conn (datascript/create-conn)
             now-ms (atom 1790000000000)
             client (datascript/make-client
                     conn {:caveat-evaluator evaluator
                           :clock #(deref now-ms)})]
         (seed-objects! conn)
         (contract/assert-wildcard-caveat-contract! client now-ms))
       (is (nil? (io/resource "eacl/caveats/jvm.clj"))
           "only an isolated module classpath lacks the JVM evaluator"))))

(deftest datascript-connection-without-wildcard-attributes-test
  ;; A connection created from an older EACL schema cannot store wildcard
  ;; Caveat refs; the schema write fails before any transaction.
  (let [conn (ds/create-conn
              (dissoc (schema/merge-schema) :eacl.relation/wildcard-caveats))]
    (is (= :eacl.schema/wildcard-attributes-missing
           (:type
            (try
              (schema/write-schema!
               conn contract/caveat-schema {:allow-caveats? true})
              nil
              (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
                (ex-data error))))))))
