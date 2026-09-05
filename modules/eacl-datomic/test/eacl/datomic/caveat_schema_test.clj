(ns eacl.datomic.caveat-schema-test
  (:require [clojure.test :refer [deftest]]
            [datomic.api :as d]
            [eacl.datomic.schema :as schema]
            [eacl.caveats.persistence-contract :as contract]))

(defn interleave! [competitor outer]
  (let [native d/transact armed (atom true)
        interrupt #(when (compare-and-set! armed true false) (competitor))]
    (with-redefs [d/transact (fn
                                   ([conn tx] (interrupt) (native conn tx))
                                   ([conn tx metadata] (interrupt) (native conn tx metadata)))]
      (outer))))

(deftest named-caveat-persistence
  (let [uri (str "datomic:mem://caveat-schema-" (random-uuid))
        _ (d/create-database uri) conn (d/connect uri)]
    (try
      (schema/install! conn)
      (contract/check-persistence!
        {:write! #(schema/write-schema! conn %) :snapshot #(d/db conn) :read-schema schema/read-schema
         :entid d/entid :generation #(get (d/entity % [:eacl/id "schema-string"]) :eacl/schema-version)
         :transact! #(deref (d/transact conn %))
         :speculative (fn [db source]
                        (let [plan (schema/plan-schema-replacement db source {})]
                          {:db (:db-after (d/with db (:speculative-tx-data plan)))
                           :components (:changed-schema-components plan)}))
         :interleave! interleave! :tempid "qualifier" :history-stable? true})
      (finally (d/release conn) (d/delete-database uri)))))
