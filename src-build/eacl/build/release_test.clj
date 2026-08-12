(ns eacl.build.release-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [eacl.build.config :as config]
            [eacl.build.module :as module]
            [eacl.build.release :as release]))

(deftest release-workflow-maps-the-configured-environment-secrets
  (let [workflow (slurp ".github/workflows/release.yml")]
    (is (string/includes? workflow "secrets.CLOJARS_USERNAME"))
    (is (string/includes? workflow "secrets.CLOJARS_DEPLOY_TOKEN"))
    (is (not (string/includes? workflow "secrets.CLOJURE_")))
    (is (string/includes? workflow "run: clojure -X:deploy"))
    (is (string/includes? workflow "EACL_JAVA_RELEASE: '26'"))))

(deftest release-build-propagates-one-explicit-java-target
  (let [builds (atom [])]
    (with-redefs [module/assert-module-coordinates! (constantly true)
                  module/jar!
                  (fn [module-id options]
                    (swap! builds conj [module-id options])
                    {:module-id module-id
                     :lib (:lib (config/module module-id))
                     :version (:version options)
                     :java-release (:java-release options)
                     :java-class-major-version
                     (config/java-class-major-version options)})]
      (release/build-set! {:version "8.1.0" :java-release 17})
      (is (= config/module-order (mapv first @builds)))
      (is (every? #(= {:version "8.1.0" :java-release 17} (second %))
                  @builds)))))

(deftest clojars-preflight-requires-the-exact-group-and-user
  (is (true? (release/assert-clojars-group! ["dev.eacl"])))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"verified dev.eacl"
       (release/assert-clojars-group!
        ["org.clojars.theronic" "net.clojars.theronic"])))
  (is (true?
       (release/assert-deploy-credentials!
        {:username "theronic" :token "not-a-real-token"})))
  (is (thrown? clojure.lang.ExceptionInfo
               (release/assert-deploy-credentials!
                {:username "theronic@example.test" :token "token"}))))

(deftest invalid-release-set-cannot-start-an-upload
  (let [uploads (atom [])
        fake-artifacts
        (mapv
         (fn [module-id]
           {:module-id module-id
            :lib (:lib (config/module module-id))
            :version "8.1.0"
            :java-release 26
            :java-class-major-version 70})
         config/module-order)]
    (testing "a local audit failure occurs before the first deploy call"
      (with-redefs [module/audit-built!
                    (fn [module-id _]
                      (when (= :eacl-datahike module-id)
                        (throw (ex-info "bad JAR" {:module module-id}))))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"bad JAR"
             (release/publish-validated!
              fake-artifacts "8.1.0"
              {:groups ["dev.eacl"]
               :deploy-credentials
               {:username "theronic" :token "not-a-real-token"}
               :deploy-fn #(swap! uploads conj %)})))
        (is (empty? @uploads))))))

(defn- fake-artifacts
  []
  (mapv
   (fn [module-id]
     {:module-id module-id
      :lib (:lib (config/module module-id))
      :version "8.1.0"
      :java-release 26
      :java-class-major-version 70})
   config/module-order))

(deftest every-local-release-failure-precedes-all-uploads
  (let [credentials {:username "theronic" :token "not-a-real-token"}]
    (doseq [failure-type
            [:invalid-jar
             :invalid-pom
             :missing-generated-entry
             :unsupported-bytecode
             :invalid-coordinate]]
      (testing (name failure-type)
        (let [uploads (atom [])]
          (with-redefs [module/audit-built!
                        (fn [_ _]
                          (throw
                           (ex-info
                            "artifact audit failed"
                            {:type failure-type})))]
            (is (thrown? clojure.lang.ExceptionInfo
                         (release/run-release-pipeline!
                          "8.1.0"
                          {:build-fn (fn [_] (fake-artifacts))
                           :smoke-fn (fn [_ _] true)
                           :groups-fn (fn [] ["dev.eacl"])
                           :deploy-credentials credentials
                           :deploy-fn #(swap! uploads conj %)})))
            (is (empty? @uploads))))))
    (doseq [failure-type [:isolated-resolution :generated-kernel-smoke]]
      (testing (name failure-type)
        (let [uploads (atom [])]
          (with-redefs [module/audit-built! (fn [_ _] true)]
            (is (thrown? clojure.lang.ExceptionInfo
                         (release/run-release-pipeline!
                          "8.1.0"
                          {:build-fn (fn [_] (fake-artifacts))
                           :smoke-fn
                           (fn [_ _]
                             (throw
                              (ex-info
                               "consumer validation failed"
                               {:type failure-type})))
                           :groups-fn (fn [] ["dev.eacl"])
                           :deploy-credentials credentials
                           :deploy-fn #(swap! uploads conj %)})))
            (is (empty? @uploads))))))))
