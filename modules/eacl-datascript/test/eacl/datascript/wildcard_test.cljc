(ns eacl.datascript.wildcard-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            #?(:clj [eacl.caveats.jvm :as cel])
            [datascript.core :as ds]
            [eacl.cache :as cache]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.schema :as schema]
            [eacl.wildcard-contract-support :as contract]))

(defn- seed-objects!
  [conn]
  (ds/transact! conn (mapv (fn [id] {:eacl/id id}) contract/objects)))

(deftest datascript-wildcard-contract-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (seed-objects! conn)
    (contract/assert-wildcard-contract! client)))

(deftest datascript-wildcard-without-cache-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {:cache cache/no-cache})]
    (seed-objects! conn)
    (contract/assert-wildcard-contract! client)))

#?(:clj
   (deftest datascript-wildcard-caveat-contract-test
     (let [conn (datascript/create-conn)
           now-ms (atom 1790000000000)
           client (datascript/make-client
                   conn {:caveat-evaluator (cel/evaluator)
                         :clock #(deref now-ms)})]
       (seed-objects! conn)
       (contract/assert-wildcard-caveat-contract! client now-ms))))

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
