(ns eacl.datahike.caveat-schema-test
  (:require [eacl.datahike.core :as api]
            [eacl.datahike.backend :as backend]
            [eacl.cache :as cache]
            [eacl.caveats.hot-path-contract :as hot-path]
            [clojure.test :refer [deftest]]
            [datahike.api :as d]
            [eacl.datahike.db :as db]
            [eacl.datahike.schema :as schema]
            [eacl.caveats.persistence-contract :as contract]
            [eacl.caveats.publication-contract :as publication]
            [eacl.relationships.edge-contract :as edge-contract]
            [eacl.datahike.impl :as scan-impl]
            [eacl.datahike.qualifiers :as qualifiers]))

(defn interleave! [competitor outer]
  (let [native d/transact armed (atom true)
        interrupt #(when (compare-and-set! armed true false) (competitor))]
    (with-redefs [d/transact (fn
                                   ([conn tx] (interrupt) (native conn tx))
                                   ([conn tx metadata] (interrupt) (native conn tx metadata)))]
      (outer))))

(deftest named-caveat-persistence
  (doseq [options [{} {:attribute-refs? true}]]
    (let [conn (schema/create-conn [{:db/ident :app/flag :db/valueType :db.type/long :db/cardinality :db.cardinality/one}] options) config (:config (d/db conn))]
      (try
        (contract/check-persistence!
          {:write! #(schema/write-schema! conn %) :snapshot #(d/db conn) :read-schema schema/read-schema
           :entid db/entid :generation schema/current-schema-generation :transact! #(d/transact conn %)
           :speculative (fn [db source]
                        (let [plan (schema/plan-schema-replacement db source {})]
                          {:db (:db-after (d/with db (:speculative-tx-data plan)))
                           :components (:changed-schema-components plan)}))
         :interleave! interleave! :tempid -101 :history-stable? true})
      (hot-path/check-ordinary!
        {:make-client #(api/make-client conn {:cache cache/no-cache}) :transact! #(d/transact conn %)})
      (publication/check-publication!
        {:write-schema! #(schema/write-schema! conn %) :writer #(qualifiers/writer conn)
         :entid db/entid :strategy :prepared :interleave! interleave!})
        (finally (d/release conn) (d/delete-database config))))))

(deftest compact-qualified-scans
  (doseq [options [{} {:attribute-refs? true}]]
    (let [conn (schema/create-conn [] options) config (:config (d/db conn))]
      (try
      (edge-contract/check!
        {:write-schema! #(schema/write-schema! conn %) :writer #(qualifiers/writer conn)
         :entid db/entid :forward scan-impl/subject->resources
         :reverse scan-impl/resource->subjects :direct scan-impl/direct-edge
         :adapter #(backend/basis-adapter % {})})
        (finally (d/release conn) (d/delete-database config))))))
