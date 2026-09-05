(ns eacl.datalevin.caveat-schema-test
  (:require [clojure.test :refer [deftest]]
            [datalevin.core :as d]
            [datalevin.util :as util]
            [eacl.datalevin.schema :as schema]
            [eacl.caveats.persistence-contract :as contract]
            [eacl.caveats.publication-contract :as publication]
            [eacl.datalevin.qualifiers :as qualifiers]))

(defn interleave! [competitor outer]
  (let [native d/transact! armed (atom true)
        interrupt #(when (compare-and-set! armed true false) (competitor))]
    (with-redefs [d/transact! (fn
                                   ([conn tx] (interrupt) (native conn tx))
                                   ([conn tx metadata] (interrupt) (native conn tx metadata)))]
      (outer))))

(deftest named-caveat-persistence
  (let [dir (util/tmp-dir (str "caveat-schema-" (random-uuid))) conn (schema/create-conn dir {:app/flag {:db/valueType :db.type/long}})]
    (try
      (let [token (:write-token (schema/ensure-physical-schema! conn))]
        (contract/check-persistence!
          {:write! #(schema/write-schema! conn % {} (schema/current-schema-generation (d/db conn)) token)
           :snapshot #(d/db conn) :read-schema schema/read-schema
           :entid d/entid :generation schema/current-schema-generation
           :transact! #(d/transact! conn % {:datalevin/write-token token})
           :interleave! interleave! :tempid -101 :history-stable? false})
      (publication/check-publication!
        {:write-schema! #(schema/write-schema! conn % {} (schema/current-schema-generation (d/db conn)) token) :writer #(qualifiers/writer conn)
         :entid d/entid :strategy :inline :interleave! interleave!
         :allowance-stamps (fn [database]
                             (let [eid (d/entid database [:eacl/id "schema-string"])]
                               [[:db/add eid :eacl.datalevin/schema-generation :db/current-tx]
                                [:db/add eid :eacl.datalevin/schema-write-fence :db/current-tx]]))}))
      (finally (d/close conn) (util/delete-files dir)))))
