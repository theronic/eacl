(ns eacl.datalevin.contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.constants :as datalevin-constants]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.backend.snapshot-provider :as snapshot-provider]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.causal-token :as causal-token]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datalevin.backend :as datalevin-backend]
            [eacl.datalevin.core :as datalevin]
            [eacl.datalevin.db :as ddb]
            [eacl.datalevin.schema :as datalevin-schema]
            [eacl.secure-format :as secure-format]
            [eacl.spicedb.consistency :as consistency])
  (:import [java.util.concurrent TimeUnit]))

(def ^:private test-key "01234567890123456789012345678901")

(def ^:private schema
  "definition user {}
   definition document {
     relation viewer: user
     permission view = viewer
   }")

(def ^:private scan-schema
  "definition user {}
   definition group {}
   definition folder {
     relation viewer: user
   }
   definition document {
     relation viewer: user
     relation editor: user
     relation reviewer: group
     permission view = viewer + editor
   }")

(def ^:private schema-with-editor
  "definition user {}
   definition document {
     relation viewer: user
     relation editor: user
     permission view = viewer + editor
   }")

(defn- watermark-options
  ([] (watermark-options 0))
  ([initial]
   (let [state (atom initial)]
     {:revision-watermark state
      :advance-revision-watermark!
      (fn [revision]
        (swap! state max revision))})))

(defn- with-system
  [f]
  (let [dir (u/tmp-dir (str "eacl-datalevin-module-" (random-uuid)))
        conn (datalevin/create-conn dir)]
    (try
      (let [client
            (datalevin/make-client
             conn
             (merge
              (watermark-options)
              {:source-lifecycle "test-lifecycle"
               :datalevin-topology
               datalevin-backend/certified-topology-declaration
               :security-key test-key}))]
        (f {:dir dir :conn conn :client client}))
      (finally
        (d/close conn)
        (u/delete-files dir)))))

(defn- seed!
  [conn client]
  (eacl/write-schema! client schema)
  (d/transact! conn [{:eacl/id "alice"}
                     {:eacl/id "bob"}
                     {:eacl/id "document-1"}]))

(defn- with-connection
  [f]
  (let [dir (u/tmp-dir (str "eacl-datalevin-contract-" (random-uuid)))
        conn (datalevin/create-conn dir)]
    (try
      (f conn)
      (finally
        (d/close conn)
        (u/delete-files dir)))))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- error-record
  [f]
  (try
    (f)
    nil
    (catch Throwable error
      {:message (ex-message error)
       :data (ex-data error)
       :class (str (class error))})))

(defn- halted-writer-process
  [dir watermark-file]
  (let [expression
        (str
         "(do "
         "(require '[eacl.core :as eacl] "
         "         '[eacl.datalevin.backend :as backend] "
         "         '[eacl.datalevin.core :as datalevin]) "
         "(let [watermark (atom 0) "
         "      conn (datalevin/create-conn " (pr-str dir) ") "
         "      client (datalevin/make-client "
         "              conn "
         "              {:source-lifecycle \"process-kill-lifecycle\" "
         "               :revision-watermark watermark "
         "               :advance-revision-watermark! "
         "               (fn [revision] "
         "                 (spit " (pr-str watermark-file) " (str revision)) "
         "                 (swap! watermark max revision)) "
         "               :datalevin-topology "
         "               backend/certified-topology-declaration "
         "               :security-key " (pr-str test-key) "})] "
         "  (eacl/write-schema! client " (pr-str schema) ") "
         "  (.halt (Runtime/getRuntime) 23)))")
        java (str (System/getProperty "java.home") "/bin/java")
        command
        [java
         "--add-opens=java.base/java.lang=ALL-UNNAMED"
         "--add-opens=java.base/java.nio=ALL-UNNAMED"
         "--add-opens=java.base/java.util=ALL-UNNAMED"
         "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
         "--enable-native-access=ALL-UNNAMED"
         "-cp" (System/getProperty "java.class.path")
         "clojure.main" "-e" expression]
        process (-> (ProcessBuilder. ^java.util.List command)
                    (.redirectErrorStream true)
                    (.start))]
    (when-not (.waitFor process 30 TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (ex-info "Datalevin process-kill fixture timed out."
                      {:type :test/process-timeout})))
    {:exit (.exitValue process)
     :output (slurp (.getInputStream process))}))

(defn- client-config
  ([] (client-config {}))
  ([overrides]
   (merge
    (watermark-options)
    {:source-lifecycle "test-lifecycle"
     :datalevin-topology
     datalevin-backend/certified-topology-declaration
     :security-key test-key}
    overrides)))

(defn- issue-token
  [client revision overrides]
  (let [provider (get-in client [:opts :snapshot-provider])]
    (causal-token/issue
     (get-in client [:opts :format-options])
     (merge
      {:backend :datalevin
       :source-lifecycle (snapshot-provider/source-lifecycle provider)
       :revision revision
       :exact-locator nil}
      (snapshot-provider/source-scope provider)
      overrides))))

(defn- collect-exclusive-pages
  [adapter operation prefix direction page-size]
  (loop [bound nil
         values []
         calls 0]
    (let [options (cond-> {:direction direction
                           :inclusive-bound? false
                           :limit page-size}
                    bound (assoc :bound-eid bound))
          page (apply backend/invoke adapter operation
                      (conj prefix options))]
      (if (empty? page)
        {:values values :calls (inc calls)}
        (recur (peek page)
               (into values page)
               (inc calls))))))

(defn- normalize-schema
  [value]
  {:relations
   (into #{}
         (map #(select-keys %
                           [:eacl.relation/resource-type
                            :eacl.relation/relation-name
                            :eacl.relation/subject-type]))
         (:relations value))
   :permissions
   (into #{}
         (map #(select-keys %
                           [:eacl.permission/resource-type
                            :eacl.permission/permission-name
                            :eacl.permission/source-relation-name
                            :eacl.permission/target-type
                            :eacl.permission/target-name]))
         (:permissions value))})

(defn- with-observation-system
  [f]
  (with-system
    (fn [{:keys [conn client] :as system}]
      (seed! conn client)
      (d/transact! conn [{:eacl/id "document-2"}])
      (let [bob (eacl/spice-object :user "bob")
            document-1 (eacl/spice-object :document "document-1")
            document-2 (eacl/spice-object :document "document-2")]
        (eacl/create-relationship! client bob :viewer document-1)
        (f (assoc system
                  :bob bob
                  :document-1 document-1
                  :document-2 document-2))))))

(defn- observation-schedule
  [kind {:keys [client bob document-2]}]
  (let [add-second! #(eacl/create-relationship!
                      client bob :viewer document-2)
        normalize-page
        (fn [value]
          {:ids (mapv :id (:data value))
           :has-next-page? (get-in value [:page-info :has-next-page?])})]
    (case kind
      :permission
      {:prepare! (constantly nil)
       :invoke #(eacl/can? client bob :view document-2)
       :mutate! add-second!
       :normalize identity}

      :lookup
      {:prepare! (constantly nil)
       :invoke #(eacl/lookup-resources
                 client
                 {:subject bob
                  :permission :view
                  :resource/type :document
                  :first 100})
       :mutate! add-second!
       :normalize normalize-page}

      :count
      {:prepare! (constantly nil)
       :invoke #(eacl/count-resources
                 client
                 {:subject bob
                  :permission :view
                  :resource/type :document})
       :mutate! add-second!
       :normalize #(select-keys % [:count :limit])}

      :schema
      {:prepare! (constantly nil)
       :invoke #(eacl/read-schema client)
       :mutate! #(eacl/write-schema! client schema-with-editor)
       :normalize normalize-schema}

      :proof
      {:prepare! #(eacl/can? client bob :view document-2)
       :invoke #(eacl/can? client bob :view document-2)
       :mutate! add-second!
       :normalize identity}

      :cursor
      {:prepare! (constantly nil)
       :invoke #(eacl/lookup-resources
                 client
                 {:subject bob
                  :permission :view
                  :resource/type :document
                  :first 1})
       :mutate! add-second!
       :normalize normalize-page})))

(defn- capture-observation-schedule
  [kind]
  (with-observation-system
    (fn [system]
      (let [{:keys [prepare! invoke mutate! normalize]}
            (observation-schedule kind system)
            observations (atom [])]
        (prepare!)
        (let [before
              (binding [backend/*invoke-observer*
                        (fn [{:keys [phase operation]}]
                          (when (= :after phase)
                            (swap! observations conj operation)))]
                (normalize (invoke)))]
          (mutate!)
          {:before before
           :after (normalize (invoke))
           :observations @observations})))))

(defn- run-observation-boundary
  [kind boundary]
  (with-observation-system
    (fn [system]
      (let [{:keys [prepare! invoke mutate! normalize]}
            (observation-schedule kind system)
            observations (atom [])
            mutated? (atom false)]
        (prepare!)
        (let [captured
              (binding [backend/*invoke-observer*
                        (fn [{:keys [phase operation]}]
                          (when (= :after phase)
                            (let [index (count @observations)]
                              (swap! observations conj operation)
                              (when (and (= boundary index)
                                         (compare-and-set!
                                          mutated? false true))
                                (binding [backend/*invoke-observer* nil]
                                  (mutate!))))))]
                (normalize (invoke)))]
          {:captured captured
           :after (normalize (invoke))
           :mutated? @mutated?
           :observations @observations})))))

(deftest construction-requires-certified-topology-and-monotonic-state-test
  (with-connection
    (fn [conn]
      (let [before (d/active-read-snapshot-info)]
        (testing "every unsupported declared topology fails closed"
          (doseq [topology
                  [(assoc datalevin-backend/certified-topology-declaration
                          :deployment :remote)
                   (assoc datalevin-backend/certified-topology-declaration
                          :jvms 2)
                   (assoc datalevin-backend/certified-topology-declaration
                          :connections 2)
                   (assoc datalevin-backend/certified-topology-declaration
                          :writers 2)
                   (assoc datalevin-backend/certified-topology-declaration
                          :writer-ownership :external)
                   (assoc datalevin-backend/certified-topology-declaration
                          :commit-mode :wal)
                   (assoc datalevin-backend/certified-topology-declaration
                          :physical-schema :mutable)
                   (assoc datalevin-backend/certified-topology-declaration
                          :request-threads :virtual)
                   (assoc datalevin-backend/certified-topology-declaration
                          :wal true)]]
            (is (= :eacl/unsupported-topology
                   (:type
                    (error-data
                     #(datalevin/make-client
                       conn (client-config {:datalevin-topology topology}))))))))
        (testing "watermark is mandatory, bounded, exact, and monotonic"
          (is (= :eacl/invalid-config
                 (:type
                  (error-data
                   #(datalevin/make-client
                     conn (dissoc (client-config) :revision-watermark))))))
          (is (= :eacl/invalid-config
                 (:type
                  (error-data
                   #(datalevin/make-client
                     conn (dissoc (client-config)
                                  :advance-revision-watermark!))))))
          (is (= :eacl/invalid-config
                 (:type
                  (error-data
                   #(datalevin/make-client
                     conn (client-config {:revision-watermark 0}))))))
          (doseq [watermark [nil -1 1.5 9007199254740992]]
            (is (= :eacl/invalid-config
                   (:type
                    (error-data
                     #(datalevin/make-client
                       conn (client-config
                             {:revision-watermark (atom watermark)})))))))
          (is (= :eacl.datalevin/revision-regression
                 (:type
                  (error-data
                   #(datalevin/make-client
                     conn (client-config
                           {:revision-watermark
                            (atom 9007199254740991)}))))))
          (is (map? (datalevin/make-client conn (client-config)))))
        (testing "lifecycle and signing material must be externally supplied"
          (doseq [config [(dissoc (client-config) :source-lifecycle)
                          (client-config {:source-lifecycle nil})
                          (dissoc (client-config) :security-key)]]
            (is (= :eacl/invalid-config
                   (:type
                    (error-data
                     #(datalevin/make-client conn config)))))))
        (is (= before (d/active-read-snapshot-info)))))))

(deftest process-local-lifecycle-rotation-is-rejected-test
  (with-system
    (fn [{:keys [client]}]
      (doseq [invoke [#(datalevin/expire-cache! client)
                      #(datalevin/expire-cache! client "new-life")]]
        (is (= :eacl.datalevin/source-lifecycle-persistence-required
               (:type (error-data invoke))))))))

(deftest revision-watermark-advances-before-success-and-fails-closed-test
  (testing "every acknowledged bootstrap and EACL commit advances the watermark"
    (with-system
      (fn [{:keys [conn client]}]
        (let [watermark (get-in client [:opts :revision-watermark])]
          (is (= (:max-tx (d/db conn)) @watermark))
          (eacl/write-schema! client schema)
          (is (= (:max-tx (d/db conn)) @watermark))
          (d/transact! conn [{:eacl/id "alice"}
                             {:eacl/id "document-1"}])
          ;; Out-of-band object fixture writes are deliberately outside EACL's
          ;; authorization mutation acknowledgement contract.
          (let [before @watermark]
            (eacl/create-relationship!
             client
             (eacl/->Relationship
              (eacl/spice-object :user "alice")
              :viewer
              (eacl/spice-object :document "document-1")))
            (is (> @watermark before))
            (is (= (:max-tx (d/db conn)) @watermark)))))))
  (doseq [failure-mode [:no-op :throw]]
    (testing (name failure-mode)
      (let [dir (u/tmp-dir (str "eacl-watermark-failure-" (random-uuid)))
            conn (datalevin/create-conn dir)
            watermark (atom 0)
            mode (atom :ok)
            client
            (datalevin/make-client
             conn
             {:source-lifecycle "watermark-failure"
              :revision-watermark watermark
              :advance-revision-watermark!
              (fn [revision]
                (case @mode
                  :ok (swap! watermark max revision)
                  :no-op nil
                  :throw (throw (ex-info "injected" {:mode :throw}))))
              :datalevin-topology
              datalevin-backend/certified-topology-declaration
              :security-key test-key})]
        (try
          (reset! mode failure-mode)
          (let [before @watermark
                error (error-data #(eacl/write-schema! client schema))]
            (is (= (if (= :throw failure-mode)
                     :eacl.datalevin/revision-watermark-persistence-failed
                     :eacl.datalevin/revision-watermark-not-persisted)
                   (:type error)))
            (is (= before @watermark))
            (is (> (:max-tx (d/db conn)) @watermark))
            (is (= {:active 0 :oldest-age-ms nil}
                   (d/active-read-snapshot-info))))
          (finally
            (d/close conn)
            (u/delete-files dir)))))))

(deftest restart-and-backup-restore-enforce-lifecycle-continuity-test
  (let [dir (u/tmp-dir (str "eacl-datalevin-restart-" (random-uuid)))
        backup (u/tmp-dir (str "eacl-datalevin-backup-" (random-uuid)))
        watermark (atom 0)
        lifecycle "continuity-life-1"
        options
        {:source-lifecycle lifecycle
         :revision-watermark watermark
         :advance-revision-watermark! #(swap! watermark max %)
         :datalevin-topology
         datalevin-backend/certified-topology-declaration
         :security-key test-key}
        conn (datalevin/create-conn dir)]
    (try
      (let [client (datalevin/make-client conn options)]
        (eacl/write-schema! client schema)
        (d/transact! conn [{:eacl/id "alice"}
                           {:eacl/id "document-1"}])
        (d/copy (d/db conn) backup true)
        (let [old-token
              (:zed/token
               (eacl/create-relationship!
                client
                (eacl/->Relationship
                 (eacl/spice-object :user "alice")
                 :viewer
                 (eacl/spice-object :document "document-1"))))
              live-watermark @watermark]
          (d/close conn)
          (testing "ordinary restart preserves identity and monotonic state"
            (let [reopened (datalevin/create-conn dir)]
              (try
                (is (map? (datalevin/make-client reopened options)))
                (is (<= live-watermark @watermark))
                (finally
                  (d/close reopened)))))
          (testing "restoring an older backup under the old lifecycle fails"
            (let [restored (datalevin/create-conn backup)]
              (try
                (is (= :eacl.datalevin/revision-regression
                       (:type
                        (error-data
                         #(datalevin/make-client restored options)))))
                (testing "authorized lifecycle rotation with a new watermark succeeds"
                  (let [rotated-watermark (atom 0)
                        rotated
                        (datalevin/make-client
                         restored
                         (assoc options
                                :source-lifecycle "continuity-life-2"
                                :revision-watermark rotated-watermark
                                :advance-revision-watermark!
                                #(swap! rotated-watermark max %)))]
                    (is (map? rotated))
                    (is (= :eacl.consistency/incomparable-scope
                           (:type
                            (error-data
                             #(eacl/can?
                               rotated
                               (eacl/spice-object :user "alice")
                               :view
                               (eacl/spice-object :document "document-1")
                               (consistency/at-least-as-fresh
                                old-token))))))))
                (finally
                  (d/close restored)))))))
      (finally
        (d/close conn)
        (u/delete-files dir)
        (u/delete-files backup)))))

(deftest abrupt-process-kill-recovers-committed-schema-and-watermark-test
  (let [dir (u/tmp-dir (str "eacl-datalevin-process-kill-"
                            (random-uuid)))
        watermark-file (str dir "-watermark")]
    (try
      (let [{:keys [exit output]}
            (halted-writer-process dir watermark-file)]
        (is (= 23 exit) output)
        (is (.exists (java.io.File. watermark-file)))
        (let [persisted (parse-long (slurp watermark-file))
              watermark (atom persisted)
              conn (datalevin/create-conn dir)]
          (try
            (let [client
                  (datalevin/make-client
                   conn
                   {:source-lifecycle "process-kill-lifecycle"
                    :revision-watermark watermark
                    :advance-revision-watermark!
                    (fn [revision]
                      (spit watermark-file (str revision))
                      (swap! watermark max revision))
                    :datalevin-topology
                    datalevin-backend/certified-topology-declaration
                    :security-key test-key})]
              (is (pos? persisted))
              (is (<= persisted @watermark))
              (is (= #{[:document :viewer :user]}
                     (into #{}
                           (map (juxt :eacl.relation/resource-type
                                      :eacl.relation/relation-name
                                      :eacl.relation/subject-type))
                           (:relations (eacl/read-schema client)))))
              (is (= {:active 0 :oldest-age-ms nil}
                     (d/active-read-snapshot-info))))
            (finally
              (d/close conn)))))
      (finally
        (u/delete-files dir)
        (u/delete-files watermark-file)))))

(deftest corrupt-and-incompatible-backup-version-metadata-fail-closed-test
  (doseq [[label version expected-message expected-data]
          [[:corrupt "not-a-version" #"Corrupt VERSION file"
            {:input "not-a-version"}]
           [:newer "999.0.0" #"newer Datalevin version"
            {:database-version "999.0.0"
             :current-version datalevin-constants/version}]]]
    (testing (name label)
      (let [dir (u/tmp-dir (str "eacl-datalevin-backup-source-"
                                (random-uuid)))
            backup (u/tmp-dir (str "eacl-datalevin-backup-invalid-"
                                   (random-uuid)))
            conn (datalevin/create-conn dir)]
        (try
          (let [client (datalevin/make-client conn (client-config))]
            (eacl/write-schema! client schema)
            (d/copy (d/db conn) backup true))
          (d/close conn)
          (spit (str backup java.io.File/separator "VERSION") version)
          (let [{:keys [message data]}
                (error-record #(datalevin/create-conn backup))]
            (is (re-find expected-message message))
            (is (= expected-data (select-keys data (keys expected-data)))))
          (finally
            (d/close conn)
            (u/delete-files dir)
            (u/delete-files backup)))))))

(deftest actual-wal-and-unsafe-lmdb-flags-are-rejected-test
  (doseq [[label store-options]
          [[:wal {:wal? true :wal-durability-profile :strict}]
           [:unsafe-flags {:kv-opts {:flags #{:nosync}}}]]]
    (testing (name label)
      (let [dir (u/tmp-dir (str "eacl-datalevin-unsafe-" (random-uuid)))
            conn (datalevin/create-conn dir nil store-options)]
        (try
          (let [error (error-data
                       #(datalevin/make-client conn (client-config)))]
            (is (= :eacl/unsupported-topology (:type error)))
            (when (= label :unsafe-flags)
              (is (= #{:nosync} (:unsafe-env-flags error)))))
          (finally
            (d/close conn)
            (u/delete-files dir)))))))

(deftest physical-schema-drift-is-rejected-test
  (let [dir (u/tmp-dir (str "eacl-datalevin-drift-" (random-uuid)))
        conn (d/get-conn
              dir
              {:eacl/id {:db/valueType :db.type/long
                         :db/unique :db.unique/identity}})]
    (try
      (is (= :eacl.datalevin/physical-schema-drift
             (:type
              (error-data
               #(datalevin/make-client conn (client-config))))))
      (finally
        (d/close conn)
        (u/delete-files dir)))))

(deftest exact-integer-domain-is-enforced-at-every-adapter-boundary-test
  (with-connection
    (fn [conn]
      (let [too-large 9007199254740992
            snapshot (d/open-read-snapshot conn)
            info (d/read-snapshot-info snapshot)
            adapter-opts {:native-source-id "source"
                          :source-lifecycle "lifecycle"}]
        (try
          (doseq [field [:max-tx :max-eid]]
            (is (= :eacl/numeric-domain-error
                   (:type
                    (with-redefs [d/read-snapshot-info
                                  (fn [_] (assoc info field too-large))]
                      (error-data
                       #(datalevin-backend/snapshot-adapter
                         snapshot adapter-opts)))))))
          (let [adapter
                (datalevin-backend/snapshot-adapter snapshot adapter-opts)]
            (doseq [[operation args]
                    [[:object-id->internal [too-large]]
                     [:internal-id->object [too-large]]
                     [:subject->resources
                      [:user too-large 1 :document {:direction :asc}]]
                     [:subject->resources
                      [:user 1 too-large :document {:direction :asc}]]
                     [:subject->resources
                      [:user 1 1 :document
                       {:direction :asc :bound-eid too-large}]]
                     [:resource->subjects
                      [:document too-large 1 :user {:direction :asc}]]
                     [:resource->subjects
                      [:document 1 too-large :user {:direction :asc}]]
                     [:resource->subjects
                      [:document 1 1 :user
                       {:direction :asc :bound-eid too-large}]]
                     [:direct-match?
                      [:user too-large 1 :document 1]]
                     [:direct-match?
                      [:user 1 too-large :document 1]]
                     [:direct-match?
                      [:user 1 1 :document too-large]]]]
              (is (= :eacl/numeric-domain-error
                     (:type
                      (error-data
                       #(apply backend/invoke adapter operation args)))))))
          (finally
            (d/close-read-snapshot! snapshot)))))))

(deftest provider-owns-and-closes-explicit-snapshots-test
  (with-system
    (fn [{:keys [client]}]
      (let [provider (get-in client [:opts :snapshot-provider])
            before (d/active-read-snapshot-info)
            selected (snapshot-provider/acquire! provider :current)]
        (is (= :owned (snapshot-provider/ownership selected)))
        (is (= :datalevin
               (:backend (snapshot-provider/semantic-identity selected))))
        (is (= (inc (:active before))
               (:active (d/active-read-snapshot-info))))
        (is (true? (snapshot-provider/release! selected)))
        (is (false? (snapshot-provider/release! selected)))
        (is (= before (d/active-read-snapshot-info)))))))

(deftest provider-memoizes-schema-digests-with-structural-drift-detection-test
  (with-system
    (fn [{:keys [conn client]}]
      (let [provider (get-in client [:opts :snapshot-provider])
            digest secure-format/canonical-digest
            calls (atom 0)
            acquire-identity
            (fn []
              (let [selected (snapshot-provider/acquire! provider :current)]
                (try
                  (:schema-identity
                   (backend/invoke
                    (snapshot-provider/adapter selected) :snapshot-id))
                  (finally
                    (snapshot-provider/release! selected)))))]
        (with-redefs [secure-format/canonical-digest
                      (fn [& args]
                        (swap! calls inc)
                        (apply digest args))]
          (let [first-identity (acquire-identity)
                second-identity (acquire-identity)]
            (is (= first-identity second-identity))
            (is (= 1 @calls))
            ;; This mutation violates the certified frozen-schema topology, but
            ;; the memo must still fail safe if an owner does it between reads.
            (d/update-schema
             conn {:test/physical-drift {:db/valueType :db.type/string}})
            (let [drifted-identity (acquire-identity)]
              (is (not= first-identity drifted-identity))
              (is (= 2 @calls)))))))))

(deftest partial-relationship-pagination-is-native-bounded-and-lossless-test
  (with-system
    (fn [{:keys [conn client]}]
      (eacl/write-schema! client schema)
      (let [user-ids (mapv #(str "user-" %) (range 7))
            document (eacl/spice-object :document "document-1")]
        (d/transact!
         conn
         (into [{:eacl/id "document-1"}]
               (map (fn [id] {:eacl/id id}) user-ids)))
        (eacl/create-relationships!
         client
         (mapv
          #(eacl/->Relationship
            (eacl/spice-object :user %) :viewer document)
          user-ids))
        (let [native-limits (atom [])
              scan ddb/avet-endpoint-prefix]
          (with-redefs [ddb/avet-endpoint-prefix
                        (fn [& args]
                          (when (number? (last args))
                            (swap! native-limits conj (last args)))
                          (apply scan args))]
            (let [relationships
                  (loop [after nil
                         acc []]
                    (let [page
                          (eacl/read-relationships
                           client
                           (cond->
                            {:subject/type :user
                             :resource/type :document
                             :resource/relation :viewer
                             :first 2
                             :cache? false}
                             after (assoc :after after)))
                          acc' (into acc (:data page))]
                      (if (get-in page [:page-info :has-next-page?])
                        (recur (get-in page [:page-info :end-cursor]) acc')
                        acc')))]
              (is (= (set user-ids)
                     (into #{} (map (comp :id :subject)) relationships)))
              (is (= (count user-ids) (count relationships)))
              (is (seq @native-limits))
              (is (every? #(<= % 4) @native-limits)))))))))

(deftest public-reads-writes-and-consistency-release-all-readers-test
  (with-system
    (fn [{:keys [conn client]}]
      (seed! conn client)
      (let [alice (eacl/spice-object :user "alice")
            document (eacl/spice-object :document "document-1")
            write-response
            (eacl/create-relationship! client alice :viewer document)
            token (:zed/token write-response)]
        (is (true? (eacl/can? client alice :view document)))
        (is (true?
             (eacl/can? client alice :view document
                        consistency/fully-consistent)))
        (is (true?
             (eacl/can?
              client alice :view document
              (consistency/at-least-as-fresh token))))
        (is (= ["document-1"]
               (mapv (comp :id :resource)
                     (:data
                      (eacl/read-relationships
                       client {:subject/type :user
                               :subject/id "alice"})))))
        (is (= {:active 0 :oldest-age-ms nil}
               (d/active-read-snapshot-info)))
        (let [error
              (try
                (eacl/can?
                 client alice :view document
                 (consistency/at-exact-snapshot token))
                nil
                (catch clojure.lang.ExceptionInfo error error))]
          (is (= :eacl.consistency/exact-snapshot-unavailable
                 (:type (ex-data error))))
          (is (= {:active 0 :oldest-age-ms nil}
                 (d/active-read-snapshot-info))))))))

(deftest composed-snapshot-view-is-single-snapshot-read-only-and-non-escaping-test
  (with-system
    (fn [{:keys [conn client]}]
      (seed! conn client)
      (let [alice (eacl/spice-object :user "alice")
            document (eacl/spice-object :document "document-1")]
        (eacl/create-relationship! client alice :viewer document)
        (let [opens (atom 0)
              schema-reads (atom 0)
              open-read-snapshot d/open-read-snapshot
              read-schema datalevin-schema/read-schema
              escaped (atom nil)]
          (with-redefs [d/open-read-snapshot
                        (fn [connection]
                          (swap! opens inc)
                          (open-read-snapshot connection))
                        datalevin-schema/read-schema
                        (fn [db]
                          (swap! schema-reads inc)
                          (read-schema db))]
            (eacl/with-snapshot
             client
             (fn [view]
               (reset! escaped view)
               (is (= 1 (count (:relations (eacl/read-schema view)))))
               (is (= 1
                      (count
                       (:data
                        (eacl/read-relationships
                         view {:subject/type :user
                               :subject/id "alice"
                               :resource/type :document
                               :resource/relation :viewer
                               :first 10
                               :cache? false})))))
               (dotimes [_ 4]
                 (is (:allowed?
                      (eacl/check-permission
                       view {:subject alice
                             :permission :view
                             :resource document
                             :cache? false}))))
               (is (= :eacl/read-only-snapshot-view
                      (:type
                       (error-data
                        #(eacl/delete-relationship!
                          view alice :viewer document)))))
               (is (= :eacl/snapshot-view-thread-violation
                      (:type
                       @(future
                          (error-data
                           #(eacl/can? view alice :view document))))))))
            (is (= 1 @opens))
            (is (= 2 @schema-reads)
                "one public schema read plus one shared request-local parse"))
          (is (= :eacl/snapshot-view-closed
                 (:type
                  (error-data
                   #(eacl/can? @escaped alice :view document)))))
          (is (= {:active 0 :oldest-age-ms nil}
                 (d/active-read-snapshot-info))))))))

(deftest composed-snapshot-view-does-not-mix-a-concurrent-commit-test
  (with-system
    (fn [{:keys [conn client]}]
      (seed! conn client)
      (let [alice (eacl/spice-object :user "alice")
            document (eacl/spice-object :document "document-1")]
        (eacl/with-snapshot
         client consistency/fully-consistent
         (fn [view]
           (is (false? (eacl/can? view alice :view document)))
           @(future
              (eacl/create-relationship! client alice :viewer document))
           (is (false? (eacl/can? view alice :view document)))))
        (is (true? (eacl/can? client alice :view document)))
        (is (= {:active 0 :oldest-age-ms nil}
               (d/active-read-snapshot-info)))))))

(deftest composed-snapshot-request-budget-covers-selection-and-nested-reads-test
  (with-system
    (fn [{:keys [client]}]
      (let [token (eacl/cancellation-token)
            opens (atom 0)
            open-read-snapshot d/open-read-snapshot]
        (eacl/cancel! token)
        (with-redefs [d/open-read-snapshot
                      (fn [connection]
                        (swap! opens inc)
                        (open-read-snapshot connection))]
          (is (= :eacl.execution/cancelled
                 (:type
                  (error-data
                   #(eacl/with-snapshot
                     client nil {:cancellation-token token}
                     (fn [_] :unreachable)))))))
        (is (zero? @opens)
            "cancellation is enforced before native snapshot acquisition")
        (is (= {:active 0 :oldest-age-ms nil}
               (d/active-read-snapshot-info)))))))

(deftest at-least-timeout-cancellation-and-scope-validation-test
  (with-system
    (fn [{:keys [conn client]}]
      (seed! conn client)
      (let [alice (eacl/spice-object :user "alice")
            document (eacl/spice-object :document "document-1")
            revision (:max-tx (d/db conn))
            demand
            (fn [consistency-value request-options]
              (eacl/can?
               client
               (merge
                {:subject alice
                 :permission :view
                 :resource document
                 :consistency consistency-value}
                request-options)))]
        (testing "authenticated incomparable scope and lifecycle fail before acquisition"
          (doseq [[label overrides]
                  [[:source {:source-id "another-source"}]
                   [:lifecycle {:source-lifecycle "another-lifecycle"}]]]
            (let [before (d/active-read-snapshot-info)
                  error
                  (error-data
                   #(demand
                     (consistency/at-least-as-fresh
                      (issue-token client revision overrides))
                     {:timeout-ms 100}))]
              (is (= :eacl.consistency/incomparable-scope (:type error))
                  (name label))
              (is (= before (d/active-read-snapshot-info))))))
        (testing "a future revision becomes selectable after a concurrent commit"
          (let [target (inc revision)
                result
                (future
                  (error-data
                   #(demand
                     (consistency/at-least-as-fresh
                      (issue-token client target {}))
                     {:timeout-ms 2000})))]
            (Thread/sleep 10)
            (d/transact! conn [{:eacl/id "future-object"}])
            (is (nil? (deref result 3000 ::timed-out)))
            (is (= {:active 0 :oldest-age-ms nil}
                   (d/active-read-snapshot-info)))))
        (testing "an unreachable future revision times out and releases candidates"
          (is (= :eacl.execution/deadline-exceeded
                 (:type
                  (error-data
                   #(demand
                     (consistency/at-least-as-fresh
                      (issue-token client (+ revision 1000) {}))
                     {:timeout-ms 10})))))
          (is (= {:active 0 :oldest-age-ms nil}
                 (d/active-read-snapshot-info))))
        (testing "cooperative cancellation stops an at-least retry loop"
          (let [cancellation (eacl/cancellation-token)
                result
                (future
                  (error-data
                   #(demand
                     (consistency/at-least-as-fresh
                      (issue-token client (+ revision 2000) {}))
                     {:timeout-ms 2000
                      :cancellation-token cancellation})))]
            (Thread/sleep 10)
            (eacl/cancel! cancellation)
            (is (= :eacl.execution/cancelled
                   (:type (deref result 3000 ::timed-out))))
            (is (= {:active 0 :oldest-age-ms nil}
                   (d/active-read-snapshot-info)))))))))

(deftest one-request-remains-on-one-snapshot-across-concurrent-commit-test
  (with-system
    (fn [{:keys [conn client]}]
      (seed! conn client)
      (let [alice (eacl/spice-object :user "alice")
            bob (eacl/spice-object :user "bob")
            document (eacl/spice-object :document "document-1")
            mutated? (atom false)
            captured
            (binding [backend/*invoke-observer*
                      (fn [{:keys [phase operation]}]
                        (when (and (= :before phase)
                                   (= :relation-defs operation)
                                   (compare-and-set! mutated? false true))
                          (eacl/create-relationship!
                           client bob :viewer document)))]
              (eacl/can? client bob :view document))]
        (testing "the in-flight request cannot see the later commit"
          (is (false? captured)))
        (testing "a later request obtains a fresh explicit snapshot"
          (is (true? (eacl/can? client bob :view document))))
        (is (= {:active 0 :oldest-age-ms nil}
               (d/active-read-snapshot-info)))))))

(deftest every-backend-observation-boundary-retains-one-request-snapshot-test
  (doseq [kind [:permission :lookup :count :schema :proof :cursor]]
    (testing (name kind)
      (let [{:keys [before after observations]}
            (capture-observation-schedule kind)]
        (is (<= 2 (count observations))
            (str kind " exposes at least one inter-observation boundary"))
        (is (not= before after)
            (str kind " fixture mutation changes the next request"))
        (doseq [boundary (range (dec (count observations)))]
          (let [result (run-observation-boundary kind boundary)]
            (is (:mutated? result)
                (str kind " mutation ran at boundary " boundary))
            (is (= (subvec observations 0 (inc boundary))
                   (subvec (:observations result) 0 (inc boundary)))
                (str kind " reached the certified boundary " boundary))
            (is (= before (:captured result))
                (str kind " mixed snapshots at boundary " boundary))
            (is (= after (:after result))
                (str kind " later request missed commit at boundary " boundary))))))))

(deftest exhaustive-bounded-forward-and-reverse-scan-test
  (with-system
    (fn [{:keys [conn client]}]
      (eacl/write-schema! client scan-schema)
      (let [large-safe-id 2147483659]
      (d/transact!
       conn
       (conj
        (mapv (fn [id] {:eacl/id id})
              ["alice" "bob" "carol" "dave" "eve" "group-one"
               "document-1" "document-2" "document-3"
               "document-4" "document-5" "folder-1"])
        {:db/id large-safe-id :eacl/id "document-large"}))
      (let [users (mapv #(eacl/spice-object :user %)
                        ["alice" "bob" "carol" "dave" "eve"])
            alice (first users)
            document-1 (eacl/spice-object :document "document-1")
            documents
            (conj
             (mapv #(eacl/spice-object :document (str "document-" %))
                   (range 1 6))
             (eacl/spice-object :document "document-large"))]
        (eacl/create-relationships!
         client
         (into
          (mapv #(eacl/->Relationship alice :viewer %) documents)
          (concat
           (map #(eacl/->Relationship % :viewer document-1)
                (rest users))
           ;; Adjacent prefixes that must never escape into viewer/user/document
           ;; scans in either direction.
           [(eacl/->Relationship
             alice :editor (eacl/spice-object :document "document-2"))
            (eacl/->Relationship
             alice :viewer (eacl/spice-object :folder "folder-1"))
            (eacl/->Relationship
             (eacl/spice-object :group "group-one")
             :reviewer document-1)])))
        ;; Reassertions exercise storage-level set semantics and adapter-level
        ;; duplicate suppression without creating another logical tuple.
        (dotimes [_ 2]
          (eacl/write-relationship!
           client :touch alice :viewer document-1))
        (let [provider (get-in client [:opts :snapshot-provider])
              selected (snapshot-provider/acquire! provider :current)
              adapter (snapshot-provider/adapter selected)]
          (try
            (let [subject-id
                  (backend/invoke adapter :object-id->internal "alice")
                  relation-id
                  (:relation-id
                   (first
                    (backend/invoke
                     adapter :relation-defs :document :viewer)))
                  resource-ids (->> ["document-1" "document-2" "document-3"
                                     "document-4" "document-5" "document-large"]
                                    (mapv #(backend/invoke
                                            adapter :object-id->internal %))
                                    sort
                                    vec)
                  document-id
                  (backend/invoke adapter :object-id->internal "document-1")
                  subject-ids (->> ["alice" "bob" "carol" "dave" "eve"]
                                   (mapv #(backend/invoke
                                           adapter :object-id->internal %))
                                   sort
                                   vec)
                  forward-prefix [:user subject-id relation-id :document]
                  reverse-prefix [:document document-id relation-id :user]
                  scan (fn [operation prefix options]
                         (apply backend/invoke adapter operation
                                (conj prefix options)))]
              (testing "complete ordering, uniqueness, large safe IDs, and replay"
                (doseq [[operation prefix expected]
                        [[:subject->resources forward-prefix resource-ids]
                         [:resource->subjects reverse-prefix subject-ids]]]
                  (let [ascending (scan operation prefix {:direction :asc})
                        descending (scan operation prefix {:direction :desc})]
                    (is (= expected ascending))
                    (is (= (vec (reverse expected)) descending))
                    (is (= ascending (scan operation prefix {:direction :asc})))
                    (is (= (count ascending) (count (distinct ascending))))))
                (is (= large-safe-id (peek resource-ids))))

              (testing "inclusive and exclusive bounds in both directions"
                (doseq [[operation prefix expected]
                        [[:subject->resources forward-prefix resource-ids]
                         [:resource->subjects reverse-prefix subject-ids]]
                        bound expected]
                  (is (= (filterv #(<= bound %) expected)
                         (scan operation prefix
                               {:direction :asc :bound-eid bound
                                :inclusive-bound? true})))
                  (is (= (filterv #(< bound %) expected)
                         (scan operation prefix
                               {:direction :asc :bound-eid bound
                                :inclusive-bound? false})))
                  (is (= (->> expected (filterv #(>= bound %)) reverse vec)
                         (scan operation prefix
                               {:direction :desc :bound-eid bound
                                :inclusive-bound? true})))
                  (is (= (->> expected (filterv #(> bound %)) reverse vec)
                         (scan operation prefix
                               {:direction :desc :bound-eid bound
                                :inclusive-bound? false})))))

              (testing "all page sizes use an exclusive sentinel step without skips"
                (doseq [[operation prefix expected]
                        [[:subject->resources forward-prefix resource-ids]
                         [:resource->subjects reverse-prefix subject-ids]]
                        direction [:asc :desc]
                        page-size [1 2 3 5 20]]
                  (let [expected (if (= :desc direction)
                                   (vec (reverse expected))
                                   expected)
                        result (collect-exclusive-pages
                                adapter operation prefix direction page-size)]
                    (is (= expected (:values result)))
                    (is (= (inc (long (Math/ceil
                                      (/ (double (count expected)) page-size))))
                           (:calls result))))))

              (testing "zero and oversized limits are exact"
                (is (= [] (scan :subject->resources forward-prefix
                                {:direction :asc :limit 0})))
                (is (= resource-ids
                       (scan :subject->resources forward-prefix
                             {:direction :asc :limit 1000}))))

              (testing "missing and adjacent prefixes never leak"
                (is (= []
                       (scan :subject->resources
                             [:user subject-id relation-id :missing]
                             {:direction :asc :limit 100})))
                (is (= []
                       (scan :resource->subjects
                             [:document document-id relation-id :missing]
                             {:direction :desc :limit 100}))))

              (is (true?
                   (backend/invoke
                    adapter :direct-match?
                    :user subject-id relation-id :document
                    (first resource-ids))))
              (snapshot-provider/release! selected)
              (is (= {:active 0 :oldest-age-ms nil}
                     (d/active-read-snapshot-info))))
            (finally
              (snapshot-provider/release! selected)))))))))

(deftest shared-v8-backend-contract-test
  (with-connection
    (fn [conn]
      (let [store (contract/portable-store)
            client
            (datalevin/make-client
             conn
             (merge
              (watermark-options)
              {:cache store
               :security-key test-key
               :datalevin-topology
               datalevin-backend/certified-topology-declaration
               :source-lifecycle "shared-contract"}))]
        (eacl/write-schema! client contract/smoke-schema)
        (d/transact!
         conn
         (map-indexed
          (fn [index {:keys [id]}]
            {:db/id (- (inc index)) :eacl/id id})
          contract/smoke-objects))
        (eacl/create-relationships! client contract/smoke-relationships)
        (contract/assert-v8-seeded-contracts! client)
        (contract/assert-v8-permission-tree-contract! client)
        (contract/assert-unified-filter-validation! client)
        (contract/assert-v8-request-cache-controls! client store)
        (contract/assert-v8-cache-disabled!
         (datalevin/make-client
          conn
          (merge
           (watermark-options)
           {:cache cache/no-cache
            :security-key test-key
            :datalevin-topology
            datalevin-backend/certified-topology-declaration
            :source-lifecycle "shared-contract"})))
        (is (= {:active 0 :oldest-age-ms nil}
               (d/active-read-snapshot-info)))))))

(deftest shared-v8-recursive-contract-test
  (with-connection
    (fn [conn]
      (let [client
            (datalevin/make-client
             conn
             (merge
              (watermark-options)
              {:security-key test-key
               :datalevin-topology
               datalevin-backend/certified-topology-declaration
               :source-lifecycle "recursive-contract"}))]
        (eacl/write-schema! client contract/recursive-schema)
        (d/transact!
         conn
         (map-indexed
          (fn [index {:keys [id]}]
            {:db/id (- (inc index)) :eacl/id id})
          contract/recursive-objects))
        (eacl/create-relationships! client contract/recursive-relationships)
        (contract/assert-v8-recursive-contracts! client)
        (is (= {:active 0 :oldest-age-ms nil}
               (d/active-read-snapshot-info)))))))
