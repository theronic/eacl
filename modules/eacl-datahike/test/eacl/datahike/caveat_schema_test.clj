(ns eacl.datahike.caveat-schema-test
  (:require [clojure.test :refer [deftest]]
            [datahike.api :as d]
            [eacl.datahike.db :as db]
            [eacl.datahike.schema :as schema]
            [eacl.caveats.persistence-contract :as contract]))

(defn interleave! [competitor outer]
  (let [native d/transact armed (atom true)
        interrupt #(when (compare-and-set! armed true false) (competitor))]
    (with-redefs [d/transact (fn
                                   ([conn tx] (interrupt) (native conn tx))
                                   ([conn tx metadata] (interrupt) (native conn tx metadata)))]
      (outer))))

(deftest named-caveat-persistence
  (doseq [options [{} {:attribute-refs? true}]]
    (let [conn (schema/create-conn nil options) config (:config (d/db conn))]
      (try
        (contract/check-persistence!
          {:write! #(schema/write-schema! conn %) :snapshot #(d/db conn) :read-schema schema/read-schema
           :entid db/entid :generation schema/current-schema-generation :transact! #(d/transact conn %)
           :speculative (fn [db source]
                        (let [plan (schema/plan-schema-replacement db source {})]
                          {:db (:db-after (d/with db (:speculative-tx-data plan)))
                           :components (:changed-schema-components plan)}))
         :interleave! interleave! :tempid -101 :history-stable? true})
        (finally (d/release conn) (d/delete-database config))))))
