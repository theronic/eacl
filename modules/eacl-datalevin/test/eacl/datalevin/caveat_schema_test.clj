(ns eacl.datalevin.caveat-schema-test
  (:require [eacl.datalevin.core :as api]
            [eacl.cache :as cache]
            [eacl.caveats.hot-path-contract :as hot-path]
            [clojure.test :refer [deftest]]
            [datalevin.core :as d]
            [datalevin.util :as util]
            [eacl.datalevin.schema :as schema]
            [eacl.caveats.persistence-contract :as contract]
            [eacl.caveats.publication-contract :as publication]
            [eacl.relationships.edge-contract :as edge-contract]
            [eacl.datalevin.impl :as scan-impl]
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
      (let [token (:write-token (schema/ensure-physical-schema! conn))
            watermark (atom 0)]
        (contract/check-persistence!
          {:write! #(schema/write-schema! conn % {} (schema/current-schema-generation (d/db conn)) token)
           :snapshot #(d/db conn) :read-schema schema/read-schema
           :entid d/entid :generation schema/current-schema-generation
           :transact! #(d/transact! conn % {:datalevin/write-token token})
           :interleave! interleave! :tempid -101 :history-stable? false})
      (hot-path/check-ordinary!
        {:make-client #(api/make-client conn {:cache cache/no-cache :source-lifecycle "caveat-hot-path"
                                             :security-key "01234567890123456789012345678901"
                                             :revision-watermark watermark
                                             :advance-revision-watermark! (fn [revision] (swap! watermark max revision))}) :transact! #(d/transact! conn % {:datalevin/write-token token})})
      (publication/check-publication!
        {:write-schema! #(schema/write-schema! conn % {} (schema/current-schema-generation (d/db conn)) token) :writer #(qualifiers/writer conn)
         :entid d/entid :strategy :inline :interleave! interleave!
         :allowance-stamps (fn [database]
                             (let [eid (d/entid database [:eacl/id "schema-string"])]
                               [[:db/add eid :eacl.datalevin/schema-generation :db/current-tx]
                                [:db/add eid :eacl.datalevin/schema-write-fence :db/current-tx]]))}))
      (finally (d/close conn) (util/delete-files dir)))))

(deftest compact-qualified-scans
  (let [dir (util/tmp-dir (str "edge-scan-" (random-uuid)))
        conn (schema/create-conn dir {})
        token (:write-token (schema/ensure-physical-schema! conn))]
    (try
      (edge-contract/check!
        {:write-schema! #(schema/write-schema! conn % {} (schema/current-schema-generation (d/db conn)) token) :writer #(qualifiers/writer conn)
         :entid d/entid :forward scan-impl/subject->resources
         :reverse scan-impl/resource->subjects :direct scan-impl/direct-edge})
      (finally (d/close conn) (util/delete-files dir)))))
