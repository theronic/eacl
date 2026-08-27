(ns eacl.datalevin.adapter-certification-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.adapter-certification :as certification]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as v8]
            [eacl.core :as eacl]
            [eacl.datalevin.backend :as backend]
            [eacl.datalevin.core :as datalevin]))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest datalevin-owned-basis-adapter-certification-test
  (doseq [fixture (certification/coherent-fixtures [820084])]
    (testing (str "seed " (:seed fixture))
      (let [dir (u/tmp-dir (str "eacl-datalevin-cert-" (random-uuid)))
            conn (datalevin/create-conn dir)]
        (try
          (let [watermark (atom 0)
                client
                (datalevin/make-client
                 conn
                 {:source-lifecycle "certification-lifecycle"
                  :revision-watermark watermark
                  :advance-revision-watermark!
                  #(swap! watermark max %)
                  :security-key "01234567890123456789012345678901"})]
            (eacl/write-schema! client (:schema fixture))
            (d/transact!
             conn
             (map-indexed
              (fn [index {:keys [id]}]
                {:db/id (- (inc index)) :eacl/id id})
              (:objects fixture)))
            (eacl/create-relationships! client (:relationships fixture))
            (let [provider (:source client)
                  selected (source/acquire! provider :current)]
              (try
                (let [adapter (source/adapter selected)
                      relation-ids
                      (->> (:relations fixture)
                           (mapcat
                            (fn [{:keys [resource-type relation-name]}]
                              (v8/invoke adapter :relation-defs
                                         resource-type relation-name)))
                           (mapv :relation-id)
                           sort
                           vec)
                      report
                      (certification/certify
                       {:adapter adapter
                        :fixture fixture
                        :runtime :clj})]
                  (is (some? (v8/invoke adapter :schema-generation)))
                  (is (= :certified-scalar-fallback-v1
                         (get-in (v8/operator-capability-identity adapter)
                                 [:direct-membership :mode])))
                  (is (:passed? report) (pr-str (:checks report)))
                  (testing "scalar generations are exposed as an ordered proof frame"
                    (is (= #{:ordered-generations
                             :snapshot-bound
                             :database-visible}
                           (:cache-proofs (v8/capabilities adapter))))
                    (is (v8/supports? adapter
                                      :cache-proofs
                                      :ordered-generations))
                    (is (= relation-ids
                           (mapv first
                                 (v8/invoke adapter :proof-frame
                                            relation-ids)))))
                  (let [relationship (first (:relationships fixture))
                        affected-id
                        (:relation-id
                         (first
                          (v8/invoke
                           adapter :relation-defs
                           (get-in relationship [:resource :type])
                           (:relation relationship))))]
                    (eacl/write-relationship!
                     client
                     {:operation :delete
                      :subject (:subject relationship)
                      :relation (:relation relationship)
                      :resource (:resource relationship)})
                    (let [after-selected (source/acquire! provider :current)]
                      (try
                        (is (= :certified
                               (:status
                                (certification/certify-ordered-generation-transition!
                                 {:before-adapter adapter
                                  :after-adapter (source/adapter after-selected)
                                  :relation-ids relation-ids
                                  :affected-relation-ids [affected-id]}))))
                        (finally
                          (source/release! after-selected))))))
                (finally
                  (source/release! selected)))))
          (finally
            (d/close conn)
            (u/delete-files dir)))))))

(deftest datalevin-durable-source-identity-certification-test
  (let [dir (u/tmp-dir (str "eacl-datalevin-source-cert-" (random-uuid)))
        first-conn (datalevin/create-conn dir)
        watermark (atom 0)
        opts {:source-lifecycle "durable-source-certification"
              :revision-watermark watermark
              :advance-revision-watermark! #(swap! watermark max %)
              :security-key "01234567890123456789012345678901"}
        _ (datalevin/make-client first-conn opts)
        first-scope
        {:source-id (backend/connection-source-id first-conn)
         :branch nil}]
    (d/close first-conn)
    (let [second-conn (datalevin/create-conn dir)]
      (try
        (datalevin/make-client second-conn opts)
        (is (= :certified
               (:status
                (certification/certify-live-source-identity!
                 {:backend :datalevin
                  :durability :durable
                  :first-scope first-scope
                  :second-scope
                  {:source-id (backend/connection-source-id second-conn)
                   :branch nil}}))))
        (finally
          (d/close second-conn)
          (u/delete-files dir))))))
