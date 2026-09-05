(ns eacl.datascript.caveat-schema-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest]]
            [datascript.core :as ds]
            [eacl.datascript.schema :as schema]
            [eacl.caveats.persistence-contract :as contract]))

(defn interleave! [competitor outer]
  (let [native ds/transact! armed (atom true)
        interrupt #(when (compare-and-set! armed true false) (competitor))]
    (with-redefs [ds/transact! (fn
                                   ([conn tx] (interrupt) (native conn tx))
                                   ([conn tx metadata] (interrupt) (native conn tx metadata)))]
      (outer))))

(deftest named-caveat-persistence
  (let [conn (schema/create-conn)]
    (contract/check-persistence!
      {:write! #(schema/write-schema! conn %) :snapshot #(ds/db conn) :read-schema schema/read-schema
       :entid ds/entid :generation schema/current-schema-generation :transact! #(ds/transact! conn %)
       :speculative (fn [db source]
                        (let [plan (schema/plan-schema-replacement db source {})]
                          {:db (:db-after (ds/with db (:speculative-tx-data plan)))
                           :components (:changed-schema-components plan)}))
         :interleave! interleave! :tempid "qualifier" :history-stable? true})))
