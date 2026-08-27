(ns eacl.datalevin.mutation-race-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.core :as eacl]
            [eacl.datalevin.backend :as backend]
            [eacl.datalevin.core :as datalevin]
            [eacl.relationships.storage :as storage]
            [eacl.schema.model :as model])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private test-key "01234567890123456789012345678901")

(def ^:private schema
  "definition user {}
   definition document {
     relation viewer: user
     permission view = viewer
   }")

(def ^:private schema-with-editor
  "definition user {}
   definition document {
     relation viewer: user
     relation editor: user
     permission view = viewer + editor
   }")

(def ^:private schema-with-owner
  "definition user {}
   definition document {
     relation viewer: user
     relation owner: user
     permission view = viewer + owner
   }")

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- with-system
  [f]
  (let [dir (u/tmp-dir (str "eacl-datalevin-race-" (random-uuid)))
        conn (datalevin/create-conn dir)
        watermark (atom 0)
        client
        (datalevin/make-client
         conn
         {:source-lifecycle "race-lifecycle"
          :revision-watermark watermark
          :advance-revision-watermark! #(swap! watermark max %)
          :datalevin-topology backend/certified-topology-declaration
          :security-key test-key})]
    (try
      (eacl/write-schema! client schema)
      (d/transact! conn [{:eacl/id "alice"}
                         {:eacl/id "document-1"}])
      (f {:conn conn
          :client client
          :alice (eacl/spice-object :user "alice")
          :document (eacl/spice-object :document "document-1")})
      (finally
        (d/close conn)
        (u/delete-files dir)))))

(defn- relationship
  [{:keys [alice document]}]
  (eacl/->Relationship alice :viewer document))

(defn- relationship-state
  [conn]
  (let [db (d/db conn)
        forward
        (into #{}
              (map (fn [{subject :e [_ relation _ resource] :v}]
                     [subject relation resource]))
              (d/datoms db :ave storage/forward-attribute))
        reverse
        (into #{}
              (map (fn [{resource :e [_ relation _ subject] :v}]
                     [subject relation resource]))
              (d/datoms db :ave storage/reverse-attribute))]
    {:forward forward :reverse reverse}))

(defn- assert-paired!
  [conn]
  (let [{:keys [forward reverse]} (relationship-state conn)]
    (is (= forward reverse))
    (is (<= (count forward) 1))))

(defn- ref-value
  [db lookup-ref attribute]
  (when-let [eid (d/entid db lookup-ref)]
    (some-> (d/datoms db :eav eid attribute)
            first
            :v)))

(defn- coherence-state
  [conn]
  (let [db (d/db conn)]
    {:schema-generation
     (ref-value db [:eacl/id "schema-string"] :eacl/schema-generation)
     :schema-write-fence
     (ref-value db [:eacl/id "schema-string"] :eacl/schema-write-fence)
     :viewer-version
     (ref-value db
                [:eacl/id (model/->relation-id :document :viewer :user)]
                :eacl/relation-version)
     :editor-version
     (ref-value db
                [:eacl/id (model/->relation-id :document :editor :user)]
                :eacl/relation-version)}))

(defn- assert-advanced!
  [before after key]
  (let [before-value (get before key)
        after-value (get after key)]
    (is (integer? before-value) (str key " has a baseline value"))
    (is (integer? after-value) (str key " has a committed value"))
    (when (and (integer? before-value) (integer? after-value))
      (is (< before-value after-value) (str key " advances")))))

(defn- run-at-same-commit-boundary
  [intercept? left right]
  (let [ready (CountDownLatch. 2)
        intercepted (atom 0)
        original d/transact!
        transact
        (fn [& args]
          (let [tx-data (second args)
                slot (when (intercept? tx-data)
                       (swap! intercepted inc))]
            (when (and slot (<= slot 2))
              (.countDown ready)
              (when-not (.await ready 5 TimeUnit/SECONDS)
                (throw (ex-info "race barrier timed out"
                                {:type :test/barrier-timeout}))))
            (apply original args)))]
    (with-redefs [d/transact! transact]
      (let [left-result (future (error-data left))
            right-result (future (error-data right))]
        [(deref left-result 10000 {:type :test/future-timeout})
         (deref right-result 10000 {:type :test/future-timeout})]))))

(defn- relationship-transaction?
  [tx-data]
  (boolean
   (some (fn [op]
           (and (vector? op)
                (contains? storage/attributes (nth op 2 nil))))
         tx-data)))

(deftest create-create-create-delete-and-touch-delete-are-serializable-test
  (testing "create/create has exactly one winner"
    (with-system
      (fn [{:keys [conn client] :as system}]
        (let [rel (relationship system)
              results
              (run-at-same-commit-boundary
               relationship-transaction?
               #(eacl/create-relationship! client rel)
               #(eacl/create-relationship! client rel))]
          (is (= 1 (count (filter nil? results))))
          (is (= [:eacl/relationship-conflict]
                 (keep :type results)))
          (assert-paired! conn)))))
  (doseq [left-operation [:create :touch]]
    (testing (str (name left-operation) "/delete")
      (with-system
        (fn [{:keys [conn client] :as system}]
          (let [rel (relationship system)
                left #(case left-operation
                        :create (eacl/create-relationship! client rel)
                        :touch (eacl/write-relationship!
                                client :touch
                                (:subject rel) (:relation rel) (:resource rel)))
                results
                (run-at-same-commit-boundary
                 relationship-transaction?
                 left
                 #(eacl/delete-relationship! client rel))]
            (is (= [nil nil] results))
            (assert-paired! conn)))))))

(deftest relation-removal-create-and-schema-schema-races-are-fenced-test
  (testing "relation removal and relationship creation cannot both commit"
    (with-system
      (fn [{:keys [conn client] :as system}]
        (let [rel (relationship system)
              results
              (run-at-same-commit-boundary
               (constantly true)
               #(eacl/write-schema!
                 client "definition user {} definition document {}")
               #(eacl/create-relationship! client rel))]
          (is (= 1 (count (filter nil? results))))
          (is (= 1 (count (keep :type results))))
          (is (contains? #{:eacl.schema/concurrent-write
                           :eacl/relationship-concurrent-write}
                         (:type (first (remove nil? results)))))
          (assert-paired! conn)))))
  (testing "two schema writers serialize through the schema generation CAS"
    (with-system
      (fn [{:keys [client]}]
        (let [results
              (run-at-same-commit-boundary
               (constantly true)
               #(eacl/write-schema! client schema-with-editor)
               #(eacl/write-schema! client schema-with-owner))]
          (is (= 1 (count (filter nil? results))))
          (is (= [:eacl.schema/concurrent-write]
                 (keep :type results))))))))

(deftest object-delete-rescans-at-commit-after-intervening-create-test
  (with-system
    (fn [{:keys [conn client alice] :as system}]
      (let [rel (relationship system)
            injected? (atom false)
            original d/transact!
            delete-function-call?
            (fn [tx-data]
              (some (fn [op]
                      (and (vector? op)
                           (= :db.fn/call (first op))))
                    tx-data))]
        (with-redefs
         [d/transact!
         (fn [& args]
            (when (and (delete-function-call? (second args))
                       (compare-and-set! injected? false true))
              (eacl/create-relationship! client rel))
            (apply original args))]
          (is (nil? (error-data #(eacl/delete-object! client alice)))))
        (is (true? @injected?))
        (is (= {:forward #{} :reverse #{}}
               (relationship-state conn)))
        (is (= {:active 0 :oldest-age-ms nil}
               (d/active-read-snapshot-info)))))))

(deftest successful-state-changing-mutations-advance-coherence-metadata-test
  (with-system
    (fn [{:keys [conn client alice] :as system}]
      (let [rel (relationship system)
            initial (coherence-state conn)]
        (testing "create atomically writes both tuple directions and advances the relation"
          (eacl/create-relationship! client rel)
          (assert-paired! conn)
          (is (= 1 (count (:forward (relationship-state conn)))))
          (let [after-create (coherence-state conn)]
            (assert-advanced! initial after-create :viewer-version)

            (testing "delete atomically removes both tuple directions and advances the relation"
              (eacl/delete-relationship! client rel)
              (assert-paired! conn)
              (is (= {:forward #{} :reverse #{}}
                     (relationship-state conn)))
              (let [after-delete (coherence-state conn)]
                (assert-advanced! after-create after-delete :viewer-version)

                (testing "touch of an absent tuple creates both halves and advances the relation"
                  (eacl/write-relationship!
                   client :touch (:subject rel) (:relation rel) (:resource rel))
                  (assert-paired! conn)
                  (is (= 1 (count (:forward (relationship-state conn)))))
                  (let [after-touch (coherence-state conn)]
                    (assert-advanced! after-delete after-touch :viewer-version)

                    (testing "object deletion removes both halves and advances every affected relation"
                      (eacl/delete-object! client alice)
                      (assert-paired! conn)
                      (is (= {:forward #{} :reverse #{}}
                             (relationship-state conn)))
                      (let [after-object-delete (coherence-state conn)]
                        (assert-advanced! after-touch after-object-delete
                                          :viewer-version)

                        (testing "schema replacement advances both schema values in its commit"
                          (eacl/write-schema! client schema-with-editor)
                          (assert-paired! conn)
                          (let [after-schema (coherence-state conn)]
                            (assert-advanced! after-object-delete after-schema
                                              :schema-generation)
                            (assert-advanced! after-object-delete after-schema
                                              :schema-write-fence)
                            (is (integer? (:editor-version after-schema)))
                            (is (< (:schema-write-fence after-object-delete)
                                   (:editor-version after-schema)))))))))))))))))
