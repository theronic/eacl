(ns eacl.datalevin.expression-storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datalevin.core :as datalevin]
            [eacl.datalevin.schema :as schema]
            [eacl.schema.expression-persistence :as expression-persistence]
            [eacl.schema.expression-resolver :as expression-resolver]))

(def ^:private test-key "01234567890123456789012345678901")

(def ^:private operator-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission base = reader + writer
     permission view = base & reader - banned
   }")

(def ^:private replacement-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission base = reader + writer
     permission view = reader - banned
   }")

(def ^:private no-permission-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
   }")

(def ^:private invalid-negative-cycle-schema
  "definition user {}
   definition document {
     relation reader: user
     permission a = reader - b
     permission b = a
   }")

(def ^:private direct-schema
  "definition user {}
   definition document {
     relation reader: user
     permission view = reader
   }")

(defn- options []
  (let [watermark (atom 0)]
    {:revision-watermark watermark
     :advance-revision-watermark! #(swap! watermark max %)
     :source-lifecycle "expression-storage-test"
     :security-key test-key}))

(defn- exception-data [thunk]
  (try
    (thunk)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- write-operator-schema! [client schema-source]
  (binding [orchestration/*operator-expression-writes-enabled?* true]
    (eacl/write-schema! client schema-source)))

(defn- with-store [f]
  (let [dir (u/tmp-dir (str "eacl-expression-storage-" (random-uuid)))
        conn (datalevin/create-conn dir)]
    (try
      (f conn)
      (finally
        (d/close conn)
        (u/delete-files dir)))))

(defn- expression-storage-projection [db]
  {:schema (schema/read-schema db)
   :permissions
   (->> (schema/read-permissions db)
        (map #(select-keys % expression-persistence/expression-attributes))
        (sort-by :eacl/id)
        vec)})

(deftest expression-replacement-and-failed-write-are-atomic-test
  (with-store
    (fn [conn]
      (let [client (datalevin/make-client conn (options))]
        (write-operator-schema! client operator-schema)
        (let [before-db (d/db conn)
              before-snapshot (d/open-read-snapshot conn)]
          (try
            (let [before (schema/read-permissions before-db)
                  view-id
                  (expression-persistence/->expression-id :document :view)
                  view-eid (d/entid before-db [:eacl/id view-id])
                  before-digest
                  (:eacl.permission/expression-digest
                   (first (filter #(= view-id (:eacl/id %)) before)))]
              (is (= 2 (count before)))
              (is (every? #(not-any? (fn [attribute]
                                       (contains? % attribute))
                                     expression-persistence/legacy-flat-attributes)
                          before))
              (is (not (contains? (d/schema conn)
                                  :eacl.permission/full-key)))
              (write-operator-schema! client replacement-schema)
              (let [after-db (d/db conn)
                    after (schema/read-permissions after-db)
                    view (first (filter #(= view-id (:eacl/id %)) after))]
                (is (= view-eid (d/entid after-db [:eacl/id view-id])))
                (is (= 2 (count after)))
                (is (not= before-digest
                          (:eacl.permission/expression-digest view)))
                (is (= :exclusion
                       (:op (:root
                             (expression-persistence/decode-entity view))))))
              (let [stable-db (d/db conn)
                    stable-schema (schema/read-schema stable-db)
                    stable-generation
                    (schema/current-schema-generation stable-db)
                    data
                    (exception-data
                     #(write-operator-schema!
                       client invalid-negative-cycle-schema))
                    after-failure (d/db conn)]
                (is (= :eacl.schema/unstratified-exclusion (:type data)))
                (is (= stable-generation
                       (schema/current-schema-generation after-failure)))
                (is (= stable-schema (schema/read-schema after-failure))))
              (eacl/write-schema! client no-permission-schema)
              (is (empty? (schema/read-permissions (d/db conn))))
              (is (= 2 (count (:permissions
                               (schema/read-schema before-snapshot))))))
            (finally
              (d/close-read-snapshot! before-snapshot))))))))

(deftest raw-expression-storage-fails-closed-on-every-incompatible-shape-test
  (let [permission
        (first
         (:permissions
          (expression-persistence/candidate-schema
           (expression-resolver/validate-schema direct-schema))))]
    (doseq [[label entities reason]
            [["flat-only"
              [{:eacl/id "flat"
                :eacl.permission/resource-type :document
                :eacl.permission/permission-name :view
                :eacl.permission/source-relation-name :self
                :eacl.permission/target-type :relation
                :eacl.permission/target-name :reader}]
              :flat-only-representation]
             ["mixed"
              [(assoc permission :eacl.permission/target-type :relation)]
              :mixed-flat-and-expression]
             ["duplicate"
              [permission (assoc permission :eacl/id "duplicate")]
              :duplicate-expression]
             ["corrupt"
              [(assoc permission
                      :eacl.permission/expression-payload "not-edn")]
              :invalid-payload]]]
      (testing label
        (with-store
          (fn [conn]
            (d/transact! conn entities)
            (is (= reason
                   (:reason
                    (exception-data
                     #(schema/read-schema (d/db conn))))))))))))

(deftest expression-storage-export-import-and-backup-restore-test
  (let [source-dir
        (u/tmp-dir (str "eacl-expression-source-" (random-uuid)))
        import-dir
        (u/tmp-dir (str "eacl-expression-import-" (random-uuid)))
        backup-dir
        (u/tmp-dir (str "eacl-expression-backup-" (random-uuid)))
        source (datalevin/create-conn source-dir)
        imported (atom nil)
        restored (atom nil)]
    (try
      (let [source-client (datalevin/make-client source (options))]
        (write-operator-schema! source-client operator-schema)
        (let [source-db (d/db source)
              expected (expression-storage-projection source-db)
              exported-source
              (:eacl/schema-string
               (d/entity source-db [:eacl/id "schema-string"]))
              import-conn (datalevin/create-conn import-dir)]
          (reset! imported import-conn)
          (write-operator-schema!
           (datalevin/make-client import-conn (options)) exported-source)
          (is (= expected
                 (expression-storage-projection (d/db import-conn)))
              "source export/import preserves canonical expressions")
          (d/copy source-db backup-dir true)
          (let [restore-conn (datalevin/create-conn backup-dir)]
            (reset! restored restore-conn)
            (is (= expected
                   (expression-storage-projection (d/db restore-conn)))
                "Datalevin copy backup/restore preserves expression rows"))))
      (finally
        (when-let [conn @restored]
          (d/close conn))
        (when-let [conn @imported]
          (d/close conn))
        (d/close source)
        (doseq [dir [source-dir import-dir backup-dir]]
          (u/delete-files dir))))))
