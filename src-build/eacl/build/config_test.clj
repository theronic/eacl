(ns eacl.build.config-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [eacl.build.config :as config]
            [eacl.build.module :as module]))

(deftest coordinated-module-identities-and-versions
  (testing "the workspace has exactly the requested dependency order"
    (is (= [:eacl :eacl-datomic :eacl-datahike :eacl-datascript
            :eacl-datalevin]
           config/module-order))
    (is (= '[dev.eacl/eacl
             dev.eacl/eacl-datomic
             dev.eacl/eacl-datahike
             dev.eacl/eacl-datascript
             dev.eacl/eacl-datalevin]
           (mapv (comp :lib config/module) config/module-order)))
    (is (true? (config/assert-coordinate-set!))))
  (testing "the release set excludes modules with unpublished dependencies"
    (is (= [:eacl :eacl-datomic :eacl-datahike :eacl-datascript]
           config/release-module-order))
    (is (= :datalevin-fork-artifact-unpublished
           (:release-blocker (config/module :eacl-datalevin)))))
  (testing "one version is applied to every transitive core edge"
    (doseq [module-id (rest config/module-order)]
      (is (= {:mvn/version "8.3.1"}
             (get (config/dependencies module-id "8.3.1")
                  'dev.eacl/eacl)))))
  (testing "the Datalevin module pins the maintained fork exactly"
    (let [module (config/module :eacl-datalevin)
          dependencies
          (config/dependencies :eacl-datalevin "8.3.1")]
      (is (= "eacl/datalevin/core.cljc" (:required-entry module)))
      (is (false? (:release-ready? module)))
      (is (= #{'org.clojure/clojure
               'dev.eacl/eacl
               'com.rpl/specter
               'dev.eacl/datalevin-embedded-eacl}
             (set (keys dependencies))))
      (is (= {:mvn/version "1.0.2-eacl.2"}
             (get dependencies
                  'dev.eacl/datalevin-embedded-eacl)))
      (is (not-any?
           #(contains? dependencies %)
           '[com.datomic/peer
             org.replikativ/datahike
             datascript/datascript
             org.datalevin/datalevin-embedded]))))
  (testing "local and explicit versions are validated"
    (is (= config/default-version (config/version {})))
    (is (= "8.1.0" (config/version {:version "8.1.0"})))
    (is (= "8.0.0-SNAPSHOT"
           (config/version {:version "8.0.0-SNAPSHOT"})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"MAJOR.MINOR.PATCH"
         (config/version {:version "8.0-SNAPSHOT"}))))
  (testing "Java 26 is the default and older bytecode targets are explicit"
    (is (= 26 config/default-java-release))
    (is (= 26 (config/java-release {})))
    (is (= 8 (config/java-release {:java-release 8})))
    (is (= 17 (config/java-release {:java-release "17"})))
    (is (= 52 (config/java-class-major-version {:java-release 8})))
    (is (= 70 (config/java-class-major-version {:java-release 26})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"8 through 26"
         (config/java-release {:java-release 7})))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"8 through 26"
         (config/java-release {:java-release 27})))))

(deftest release-pom-metadata-is-complete
  (doseq [module-id config/module-order]
    (let [pom-data (pr-str (config/pom-data module-id))]
      (is (re-find #"Eclipse Public License 2.0" pom-data))
      (is (re-find #"https://github.com/theronic/eacl" pom-data))
      (is (re-find #"Petrus Theron" pom-data))))
  (is (= "scm:git:https://github.com/theronic/eacl.git"
         (:connection config/scm))))

(deftest standard-cache-release-dependencies-are-explicit
  (let [core-dependencies (config/dependencies :eacl "8.1.0")
        datahike-dependencies
        (config/dependencies :eacl-datahike "8.1.0")]
    (is (= {:mvn/version "3.2.4"}
           (get core-dependencies
                'com.github.ben-manes.caffeine/caffeine)))
    (is (not (contains? core-dependencies 'org.clojure/core.cache)))
    (is (not (contains? core-dependencies
                        'com.github.theronic/cljs-cache))
        "the temporary Git dependency is source-build-only")
    (is (= ['com.github.pkpkpk/cljs-cache]
           (get-in datahike-dependencies
                   ['org.replikativ/datahike :exclusions])))))

(deftest standard-cache-source-dependencies-are-pinned
  (let [root-dependencies (:deps (edn/read-string (slurp "deps.edn")))
        core-dependencies
        (:deps (edn/read-string (slurp "modules/eacl/deps.edn")))
        datahike-dependencies
        (:deps (edn/read-string
                (slurp "modules/eacl-datahike/deps.edn")))
        backend-probe-dependencies
        (:deps
         (edn/read-string
          (slurp "exploration/stable-discovery/backend-probes/deps.edn")))
        cljs-cache
        {:git/url "https://github.com/theronic/cljs-cache.git"
         :git/sha "4143cc036446a47f0c6dfd9f8dde90363835051c"}]
    (doseq [dependencies [root-dependencies core-dependencies]]
      (is (= {:mvn/version "3.2.4"}
             (get dependencies
                  'com.github.ben-manes.caffeine/caffeine)))
      (is (not (contains? dependencies 'org.clojure/core.cache)))
      (is (= cljs-cache
             (get dependencies 'com.github.theronic/cljs-cache))))
    (doseq [dependencies [root-dependencies
                          datahike-dependencies
                          backend-probe-dependencies]]
      (is (= ['com.github.pkpkpk/cljs-cache]
             (get-in dependencies
                     ['org.replikativ/datahike :exclusions]))))))

(deftest production-module-files-use-dev-eacl-coordinates
  (is (true? (module/assert-module-coordinates!))))
