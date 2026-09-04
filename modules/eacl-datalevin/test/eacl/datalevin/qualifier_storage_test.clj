(ns eacl.datalevin.qualifier-storage-test
  (:require [clojure.test :refer [deftest is]]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.datalevin.core :as api]
            [eacl.datalevin.impl :as impl]
            [eacl.datalevin.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.storage-contract :as contract]))

(defn direct-probe [& args]
  (let [calls (atom 0)
        seek d/seek-datoms]
    (try
      (with-redefs [d/seek-datoms (fn [& args] (swap! calls inc) (apply seek args))]
        (apply impl/direct-match? args))
      (finally (is (= 1 @calls) "one native seek per identity probe")))))

(deftest qualified-storage-fails-closed-and-cleans-exactly-test
  (let [dir (u/tmp-dir (str "qualifier-contract-" (random-uuid)))
        conn (api/create-conn dir)
        watermark (atom 0)]
    (try
      (let [client (api/make-client conn {:security-key "01234567890123456789012345678901"
                                         :source-lifecycle "qualifier-contract"
                                         :revision-watermark watermark
                                         :advance-revision-watermark! #(swap! watermark max %)})
            token (:write-token (d/install-write-policy! conn (d/write-policy conn)))]
        (contract/exercise-qualified-corruption!
         {:client client :direct-probe direct-probe
          :plan-create #(impl/tx-update-relationship %1 {:operation :create :relationship %2})
          :snapshot #(d/db conn) :entid d/entid
          :transact! (fn [operations]
                       (let [relations (into #{} (keep #(when (and (vector? %) (storage/attributes (nth % 2 nil)))
                                                         (nth (nth % 3) 1))) operations)]
                         (d/transact! conn
                                      (into (vec operations)
                                            (map #(vector :db/add % :eacl.datalevin/relation-generation :db/current-tx))
                                            relations)
                                      {:datalevin/write-token token})))
          :rows #(d/datoms %1 :ave %2)
          :safe-retract! #(safe/transact-retract-entity! client %)}))
      (finally (d/close conn) (u/delete-files dir)))))
