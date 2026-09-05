(ns eacl.datalevin.caveat-schema-test
  (:require [clojure.test :refer [deftest]]
            [datalevin.core :as d]
            [datalevin.util :as util]
            [eacl.datalevin.schema :as schema]
            [eacl.caveats.persistence-contract :as contract]))

(defn interleave! [competitor outer]
  (let [native d/transact! armed (atom true)
        interrupt #(when (compare-and-set! armed true false) (competitor))]
    (with-redefs [d/transact! (fn
                                   ([conn tx] (interrupt) (native conn tx))
                                   ([conn tx metadata] (interrupt) (native conn tx metadata)))]
      (outer))))

(deftest named-caveat-persistence
  (let [dir (util/tmp-dir (str "caveat-schema-" (random-uuid))) conn (schema/create-conn dir)]
    (try
      (let [token (:write-token (schema/ensure-physical-schema! conn))]
        (contract/check-persistence!
          {:write! #(schema/write-schema! conn % {} (schema/current-schema-generation (d/db conn)) token)
           :snapshot #(d/db conn) :read-schema schema/read-schema
           :entid d/entid :generation schema/current-schema-generation
           :transact! #(d/transact! conn % {:datalevin/write-token token})
           :interleave! interleave! :tempid -101 :history-stable? false}))
      (finally (d/close conn) (util/delete-files dir)))))
