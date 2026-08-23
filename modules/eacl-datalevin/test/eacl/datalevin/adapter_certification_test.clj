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
                  :datalevin-topology backend/certified-topology-declaration
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
                      report
                      (certification/certify
                      {:adapter adapter
                        :fixture fixture
                        :runtime :clj})]
                  (is (some? (v8/invoke adapter :schema-generation)))
                  (is (:passed? report) (pr-str (:checks report)))
                  (is (= :not-claimed
                         (:status
                          (certification/certify-ordered-generation-transition!
                           {:before-adapter adapter
                            :after-adapter adapter
                            :relation-ids []
                            :affected-relation-ids []})))
                      "the executable temporal gate records Datalevin's current conservative non-claim")
                  (testing "persistent Datalevin transaction IDs are not exposed as proof generations"
                    (is (= #{:snapshot-bound :database-visible}
                           (:cache-proofs (v8/capabilities adapter))))
                    (is (not (v8/supports? adapter
                                           :cache-proofs
                                           :ordered-generations)))
                    (is (= {:type :eacl/unsupported-capability
                            :capability :operation
                            :requested :proof-frame}
                           (select-keys
                            (error-data #(v8/operation adapter :proof-frame))
                            [:type :capability :requested])))))
                (finally
                  (source/release! selected)))))
          (finally
            (d/close conn)
            (u/delete-files dir)))))))

(deftest datalevin-durable-source-identity-certification-test
  (let [dir (u/tmp-dir (str "eacl-datalevin-source-cert-" (random-uuid)))
        first-conn (datalevin/create-conn dir)
        first-scope
        {:source-id (backend/connection-source-id first-conn)
         :branch nil}]
    (d/close first-conn)
    (let [second-conn (datalevin/create-conn dir)]
      (try
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
