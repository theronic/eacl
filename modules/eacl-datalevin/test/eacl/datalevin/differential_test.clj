(ns eacl.datalevin.differential-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as datahike]
            [datascript.core :as datascript]
            [datalevin.core :as datalevin-native]
            [datalevin.util :as u]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike-eacl]
            [eacl.datascript.core :as datascript-eacl]
            [eacl.datalevin.backend :as datalevin-backend]
            [eacl.datalevin.core :as datalevin-eacl]))

(def ^:private schema
  "definition user {}
   definition folder {
     relation reader: user
     relation parent: folder
     permission view = reader + parent->view
   }")

(def ^:private schema-with-inspect
  "definition user {}
   definition folder {
     relation reader: user
     relation parent: folder
     permission view = reader + parent->view
     permission inspect = reader + parent->inspect
   }")

(def ^:private key "01234567890123456789012345678901")

(defn- random-relationships
  [seed]
  (let [random (java.util.Random. seed)
        users (mapv #(eacl/spice-object :user (str "u" %)) (range 5))
        folders (mapv #(eacl/spice-object :folder (str "f" %)) (range 12))
        readers
        (for [folder folders
              user users
              :when (< (.nextDouble random) 0.32)]
          (eacl/->Relationship user :reader folder))
        parents
        (for [index (range 1 (count folders))
              :let [parent-index (.nextInt random index)]]
          (eacl/->Relationship
           (nth folders parent-index) :parent (nth folders index)))]
    {:users users
     :folders folders
     :relationships (vec (concat readers parents))}))

(defn- create-systems
  [seed]
  (let [dir (u/tmp-dir (str "eacl-differential-" seed "-" (random-uuid)))
        datalevin-conn (datalevin-eacl/create-conn dir)
        watermark (atom 0)
        datahike-conn (datahike-eacl/create-conn)
        datascript-conn (datascript-eacl/create-conn)]
    {:dir dir
     :systems
     [{:backend :datalevin
       :conn datalevin-conn
       :client
       (datalevin-eacl/make-client
        datalevin-conn
        {:source-lifecycle (str "differential-" seed)
         :revision-watermark watermark
         :advance-revision-watermark! #(swap! watermark max %)
         :datalevin-topology
         datalevin-backend/certified-topology-declaration
         :security-key key})
       :transact! #(datalevin-native/transact! datalevin-conn %)}
      {:backend :datahike
       :conn datahike-conn
       :client (datahike-eacl/make-client datahike-conn {:security-key key})
       :transact! #(datahike/transact datahike-conn %)}
      {:backend :datascript
       :conn datascript-conn
       :client (datascript-eacl/make-client datascript-conn {:security-key key})
       :transact! #(datascript/transact! datascript-conn %)}]}))

(defn- close-systems!
  [{:keys [dir systems]}]
  (doseq [{:keys [backend conn]} systems]
    (case backend
      :datalevin (datalevin-native/close conn)
      :datahike (datahike/release conn)
      :datascript nil))
  (u/delete-files dir))

(defn- page-walk
  [client query]
  (loop [after nil
         seen []]
    (let [page (eacl/lookup-resources
                client
                (cond-> (assoc query :first 3)
                  after (assoc :after after)))
          seen (into seen (map :id) (:data page))]
      (if (get-in page [:page-info :has-next-page?])
        (recur (get-in page [:page-info :end-cursor]) seen)
        seen))))

(defn- normalize-relationships
  [relationships]
  (into #{}
        (map (fn [{:keys [subject relation resource]}]
               [[(:type subject) (:id subject)]
                relation
                [(:type resource) (:id resource)]]))
        relationships))

(defn- observations
  [{:keys [client]} users folders permissions]
  {:checks
   (into {}
         (for [permission permissions]
           [permission
            (mapv (fn [user]
                    (mapv #(boolean (eacl/can?
                                     client user permission %))
                          folders))
                  users)]))
   :resource-pages
   (into {}
         (for [permission permissions]
           [permission
            (mapv (fn [user]
                    (page-walk client
                               {:subject user
                                :permission permission
                                :resource/type :folder}))
                  users)]))
   :resource-counts
   (into {}
         (for [permission permissions]
           [permission
            (mapv (fn [user]
                    ;; Backend-specific cache identities are deliberately
                    ;; opaque across independent databases. Compare only the
                    ;; public count, limit, and cache-hit semantics.
                    (select-keys
                     (eacl/count-resources
                      client
                      {:subject user
                       :permission permission
                       :resource/type :folder})
                     [:count :limit :cached?]))
                  users)]))
   :subject-sets
   (into {}
         (for [permission permissions]
           [permission
            (mapv (fn [folder]
                    (into #{}
                          (map :id)
                          (:data
                           (eacl/lookup-subjects
                            client
                            {:resource folder
                             :permission permission
                             :subject/type :user
                             :first 100}))))
                  folders)]))
   :relationships
   (normalize-relationships
    (:data
     (eacl/read-relationships
      client {:resource/type :folder :first 1000})))})

(defn- assert-equal-observations!
  ([seed phase systems users folders]
   (assert-equal-observations!
    seed phase systems users folders [:view]))
  ([seed phase systems users folders permissions]
  (let [by-backend
        (into {}
              (map (juxt :backend
                         #(observations % users folders permissions)))
              systems)
        expected (:datascript by-backend)]
    (doseq [[backend actual] by-backend]
      (is (= expected actual)
          (str "seed=" seed " phase=" phase " backend=" backend))))))

(deftest randomized-public-api-differential-test
  (doseq [seed [41 820084 20260822]]
    (testing (str "seed " seed)
      (let [{:keys [users folders relationships]}
            (random-relationships seed)
            fixture (create-systems seed)
            systems (:systems fixture)
            objects
            (mapv (fn [{:keys [id]}] {:eacl/id id})
                  (concat users folders))]
        (try
          (doseq [{:keys [client transact!]} systems]
            (eacl/write-schema! client schema)
            (transact! objects)
            (eacl/create-relationships! client relationships))
          (assert-equal-observations!
           seed :initial systems users folders)

          (let [deletions (vec (take-nth 3 relationships))
                touches (vec (take-nth 4 (drop 1 relationships)))]
            (doseq [{:keys [client]} systems]
              (eacl/delete-relationships! client deletions)
              (eacl/write-relationships!
               client
               (mapv #(eacl/->RelationshipUpdate :touch %) touches)))
            (assert-equal-observations!
             seed :mutated systems users folders))

          (doseq [{:keys [client]} systems]
            (eacl/write-schema! client schema-with-inspect))
          (assert-equal-observations!
           seed :schema-changed systems users folders [:view :inspect])

          (doseq [{:keys [client]} systems]
            (eacl/delete-object! client (first folders)))
          (assert-equal-observations!
           seed :object-deleted systems users folders [:view :inspect])
          (finally
            (close-systems! fixture)))))))
