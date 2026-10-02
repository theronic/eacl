(ns eacl.datalevin.store-upgrade-test
  "A store that an earlier module bootstrapped and froze stays usable when a
  later module adds attributes to the physical EACL schema, or when the
  application adds attributes of its own."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.core :as eacl]
            [eacl.datalevin.core :as datalevin]
            [eacl.datalevin.schema :as schema]
            [eacl.datalevin.storage :as target-storage]
            [eacl.schema.wildcard :as wildcard]))

(def ^:private test-key "01234567890123456789012345678901")

(def ^:private logical-schema
  "definition user {}
   definition document {
     relation viewer: user
     permission view = viewer
   }")

(def ^:private wildcard-schema
  "definition user {}
   definition document {
     relation viewer: user | user:*
     permission view = viewer
   }")

(def ^:private earlier-schema
  "The module's physical schema before wildcard subjects added two Relation
  attributes. Nothing else in the physical schema, the write policy or the
  bootstrap changed with them."
  (apply dissoc schema/datalevin-schema wildcard/attributes))

(def ^:private alice (eacl/spice-object :user "alice"))
(def ^:private bob (eacl/spice-object :user "bob"))
(def ^:private document-1 (eacl/spice-object :document "document-1"))
(def ^:private document-2 (eacl/spice-object :document "document-2"))

(defn- client-options
  [watermark]
  {:security-key test-key
   :source-lifecycle #uuid "3f0a7c52-6f0b-5b6e-9c1d-2a4e8d7b9f10"
   :revision-watermark watermark
   :advance-revision-watermark! #(swap! watermark max %)})

(defn- storage-attributes
  "The physical attributes the module's write policy must cover."
  [conn]
  (into #{}
        (filter (fn [attribute]
                  (let [attribute-namespace (namespace attribute)]
                    (and (not= :eacl/id attribute)
                         (or (= "eacl" attribute-namespace)
                             (str/starts-with? attribute-namespace "eacl."))))))
        (keys (d/schema conn))))

(defn- wildcard-attributes-present?
  [conn]
  (every? #(contains? (d/schema conn) %) wildcard/attributes))

(defn- policy-covers?
  [conn attributes]
  (let [{:keys [guarded-attributes frozen-attributes]} (d/write-policy conn)]
    (every? #(and (contains? guarded-attributes %)
                  (contains? frozen-attributes %))
            attributes)))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(def ^:private reserved-namespace-schema
  "An application attribute in an EACL namespace is storage to the write
  policy, which covers it from bootstrap."
  {:eacl.application/tenant {:db/valueType :db.type/string}})

(defn- open-with-earlier-module
  "Opens a directory as the module did before it declared the wildcard
  attributes: it gave Datalevin its whole physical schema at open and
  bootstrapped a fresh store."
  ([dir] (open-with-earlier-module dir nil))
  ([dir extra-schema]
   (let [conn (d/get-conn dir (merge earlier-schema extra-schema))]
     (when-not (:version (target-storage/evidence (d/db conn)))
       (target-storage/bootstrap! conn))
     conn)))

(defn- seed-earlier-store!
  "Bootstraps, freezes and serves from a store with the earlier module's
  attribute set, then closes it. Returns the store's final revision.

  Client construction is today's code over that attribute set. On a store
  without a write policy it does what the earlier module did."
  [dir watermark extra-schema]
  (with-redefs [schema/datalevin-schema earlier-schema]
    (let [conn (open-with-earlier-module dir extra-schema)]
      (try
        (let [client (datalevin/make-client conn (client-options watermark))]
          (eacl/write-schema! client logical-schema)
          (d/transact! conn [{:eacl/id "alice"} {:eacl/id "bob"}
                             {:eacl/id "document-1"} {:eacl/id "document-2"}])
          (eacl/create-relationship!
           client (eacl/->Relationship alice :viewer document-1))
          (when (or (wildcard-attributes-present? conn)
                    (not (policy-covers? conn (storage-attributes conn))))
            (throw (ex-info "The fixture is not an earlier frozen store." {})))
          (:max-tx (d/db conn)))
        (finally
          (d/close conn))))))

(defn- with-earlier-store
  ([f] (with-earlier-store nil f))
  ([extra-schema f]
   (let [dir (u/tmp-dir (str "eacl-datalevin-store-upgrade-" (random-uuid)))
         watermark (atom 0)]
     (try
       (f {:dir dir
           :watermark watermark
           :revision (seed-earlier-store! dir watermark extra-schema)})
       (finally
         (u/delete-files dir))))))

(defn- first-relation-eid
  [conn]
  (:e (first (d/datoms
              (d/db conn) :ave
              :eacl.relation/resource-type+relation-name+subject-type))))

(defn- with-reopened
  "Opens the directory with the current module and closes it afterwards."
  ([dir f] (with-reopened dir nil f))
  ([dir extra-schema f]
   (let [conn (schema/create-conn dir extra-schema)]
     (try
       (f conn)
       (finally
         (d/close conn))))))

(defn- add-attributes-outside-the-policy!
  "Leaves the store as an upgrade interrupted after adding the attributes and
  before extending the write policy would."
  [conn]
  (d/update-schema conn (select-keys schema/datalevin-schema
                                     wildcard/attributes)))

(deftest earlier-frozen-store-reopens-and-gains-added-attributes-test
  (with-earlier-store
    (fn [{:keys [dir watermark revision]}]
      (with-reopened dir
        (fn [conn]
          (let [client (datalevin/make-client conn (client-options watermark))]
            (testing "stored authorization data is served unchanged"
              (is (true? (eacl/can? client alice :view document-1)))
              (is (false? (eacl/can? client bob :view document-1))))
            (testing "the added attributes exist under the write policy"
              (is (wildcard-attributes-present? conn))
              (is (policy-covers? conn (storage-attributes conn))))
            (testing "no transaction was committed, so revisions stay comparable"
              (is (= revision (:max-tx (d/db conn)))))
            (testing "a write to an added attribute needs the admitted writer"
              (is (= :datalevin/guarded-attribute-write
                     (:type
                      (error-data
                       #(d/transact!
                         conn
                         [[:db/add (first-relation-eid conn)
                           wildcard/unqualified-attribute true]]))))))
            (testing "the store accepts the schema the attributes exist for"
              (eacl/write-schema! client wildcard-schema)
              (eacl/create-relationship!
               client
               (eacl/->Relationship
                (eacl/spice-object :user wildcard/object-id)
                :viewer document-2))
              (is (true? (eacl/can? client bob :view document-2)))
              (is (false? (eacl/can? client bob :view document-1)))))))
      (testing "the upgraded store restarts like one bootstrapped today"
        (with-reopened dir
          (fn [conn]
            (testing "with the extended policy already persisted"
              (is (policy-covers? conn (storage-attributes conn))))
            (let [client (datalevin/make-client conn (client-options watermark))]
              (is (true? (eacl/can? client alice :view document-1)))
              (is (true? (eacl/can? client bob :view document-2))))))))))

(deftest earlier-store-with-other-covered-attributes-gains-added-attributes-test
  (with-earlier-store reserved-namespace-schema
    (fn [{:keys [dir watermark]}]
      (with-reopened dir reserved-namespace-schema
        (fn [conn]
          (is (policy-covers? conn (keys reserved-namespace-schema)))
          (let [client (datalevin/make-client conn (client-options watermark))]
            (is (wildcard-attributes-present? conn))
            (is (policy-covers? conn (storage-attributes conn)))
            (is (true? (eacl/can? client alice :view document-1)))))))))

(deftest upgraded-store-still-opens-with-the-earlier-attribute-set-test
  (with-earlier-store
    (fn [{:keys [dir watermark]}]
      (with-reopened dir
        (fn [conn]
          (datalevin/make-client conn (client-options watermark))
          (is (policy-covers? conn wildcard/attributes))))
      ;; Today's client construction stands in for the earlier module's. With
      ;; nothing missing and nothing uncovered it compares the persisted policy
      ;; with the one computed from the physical schema, as that module did.
      (with-redefs [schema/datalevin-schema earlier-schema]
        (let [conn (open-with-earlier-module dir)]
          (try
            (let [client (datalevin/make-client conn (client-options watermark))]
              (is (true? (eacl/can? client alice :view document-1))))
            (finally
              (d/close conn))))))))

(deftest application-attribute-added-after-bootstrap-opens-test
  (let [dir (u/tmp-dir (str "eacl-datalevin-application-schema-"
                            (random-uuid)))
        watermark (atom 0)
        first-schema {:app/name {:db/valueType :db.type/string}}
        grown-schema (assoc first-schema
                            :app/email {:db/valueType :db.type/string})]
    (try
      (with-reopened dir first-schema
        (fn [conn]
          (datalevin/make-client conn (client-options watermark))))
      (with-reopened dir grown-schema
        (fn [conn]
          (is (contains? (d/schema conn) :app/email))
          (d/transact! conn [{:eacl/id "alice" :app/email "alice@example.com"}])
          (is (= "alice@example.com"
                 (:app/email (d/entity (d/db conn) [:eacl/id "alice"]))))
          (is (map? (datalevin/make-client conn (client-options watermark))))))
      (finally
        (u/delete-files dir)))))

(deftest interrupted-upgrade-completes-on-the-next-start-test
  (with-earlier-store
    (fn [{:keys [dir watermark]}]
      (with-reopened dir add-attributes-outside-the-policy!)
      (with-reopened dir
        (fn [conn]
          (is (wildcard-attributes-present? conn))
          (is (not (policy-covers? conn wildcard/attributes)))
          (let [client (datalevin/make-client conn (client-options watermark))]
            (is (policy-covers? conn (storage-attributes conn)))
            (is (true? (eacl/can? client alice :view document-1)))))))))

(deftest data-written-outside-the-policy-is-not-adopted-test
  (with-earlier-store
    (fn [{:keys [dir watermark]}]
      (with-reopened dir
        (fn [conn]
          (add-attributes-outside-the-policy! conn)
          ;; Admitted by Datalevin only because no policy covers it yet.
          (d/transact! conn [[:db/add (first-relation-eid conn)
                              wildcard/unqualified-attribute true]])
          (let [error (error-data
                       #(datalevin/make-client conn (client-options watermark)))]
            (is (= :eacl.datalevin/write-policy-drift (:type error)))
            (is (= [wildcard/unqualified-attribute]
                   (:populated-attributes error))))
          (is (not (policy-covers? conn wildcard/attributes))))))))

(deftest foreign-write-policy-is-not-extended-test
  (with-earlier-store
    (fn [{:keys [dir watermark]}]
      (with-reopened dir
        (fn [conn]
          (let [policy (d/write-policy conn)
                token (:write-token (d/install-write-policy! conn policy))
                tampered (assoc policy :guarded-write-hint "tampered policy")]
            (d/install-write-policy! conn tampered
                                     {:datalevin/write-token token})
            (let [error (error-data
                         #(datalevin/make-client
                           conn (client-options watermark)))]
              (is (= :eacl.datalevin/write-policy-drift (:type error)))
              (is (= (vec (sort wildcard/attributes))
                     (:missing-attributes error))))
            (testing "the store is left exactly as it was found"
              (is (not (wildcard-attributes-present? conn)))
              (is (= tampered (d/write-policy conn))))))))))

(deftest store-without-source-identity-is-refused-unchanged-test
  (with-earlier-store
    (fn [{:keys [dir watermark]}]
      (with-reopened dir
        (fn [conn]
          ;; :eacl/id is application identity, which the policy leaves open.
          (d/transact! conn [[:db/retract [:eacl/id "datalevin-metadata"]
                              :eacl/id "datalevin-metadata"]])
          (is (= :eacl/invalid-source-identity
                 (:type
                  (error-data
                   #(datalevin/make-client conn (client-options watermark))))))
          (is (not (wildcard-attributes-present? conn))))))))

(deftest store-with-incomplete-generations-is-refused-unchanged-test
  (with-earlier-store
    (fn [{:keys [dir watermark]}]
      (with-reopened dir
        (fn [conn]
          (let [policy (d/write-policy conn)
                token (:write-token (d/install-write-policy! conn policy))
                relation-eid (first-relation-eid conn)
                generation (:eacl.datalevin/relation-generation
                            (d/entity (d/db conn) relation-eid))]
            (d/transact! conn
                         [[:db/retract relation-eid
                           :eacl.datalevin/relation-generation generation]]
                         {:datalevin/write-token token})
            (is (= :eacl.cache/generation-unprepared
                   (:type
                    (error-data
                     #(datalevin/make-client
                       conn (client-options watermark))))))
            (is (not (wildcard-attributes-present? conn)))
            (is (= policy (d/write-policy conn)))))))))

(deftest store-that-is-not-embedded-is-left-for-client-construction-test
  (let [dir (u/tmp-dir (str "eacl-datalevin-not-embedded-" (random-uuid)))
        watermark (atom 0)]
    (try
      ;; Datalevin reports a remote store like this and cannot read a write
      ;; policy from it.
      (with-redefs [d/read-snapshot-capabilities
                    (constantly {:supported? false
                                 :reason :remote-or-non-embedded-store})
                    d/write-policy
                    (fn [_]
                      (throw (ClassCastException. "not an embedded store")))]
        (with-reopened dir
          (fn [conn]
            (is (every? #(contains? (d/schema conn) %)
                        (keys schema/datalevin-schema)))
            (is (= :eacl/unsupported-topology
                   (:type
                    (error-data
                     #(datalevin/make-client
                       conn (client-options watermark)))))))))
      (finally
        (u/delete-files dir)))))

(deftest attribute-outside-the-module-schema-is-not-admitted-test
  (let [dir (u/tmp-dir (str "eacl-datalevin-foreign-attribute-"
                            (random-uuid)))
        watermark (atom 0)]
    (try
      (with-reopened dir
        (fn [conn]
          (datalevin/make-client conn (client-options watermark))
          (d/update-schema conn {:eacl.custom/flag
                                 {:db/valueType :db.type/boolean}})
          (let [error (error-data
                       #(datalevin/make-client
                         conn (client-options watermark)))]
            (is (= :eacl.datalevin/write-policy-drift (:type error)))
            (is (= [:eacl.custom/flag] (:uncovered-attributes error))))
          (is (not (policy-covers? conn [:eacl.custom/flag])))))
      (finally
        (u/delete-files dir)))))

(deftest unqualified-store-gains-missing-attributes-at-open-test
  (let [dir (u/tmp-dir (str "eacl-datalevin-unqualified-store-"
                            (random-uuid)))
        watermark (atom 0)]
    (try
      (let [conn (open-with-earlier-module dir)]
        (try
          (is (not (wildcard-attributes-present? conn)))
          (is (nil? (d/write-policy conn)))
          (finally
            (d/close conn))))
      (with-reopened dir
        (fn [conn]
          (is (wildcard-attributes-present? conn))
          (is (nil? (d/write-policy conn)))
          (datalevin/make-client conn (client-options watermark))
          (is (policy-covers? conn (storage-attributes conn)))))
      (finally
        (u/delete-files dir)))))
