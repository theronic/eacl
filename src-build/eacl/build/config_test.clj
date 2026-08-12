(ns eacl.build.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.build.config :as config]
            [eacl.build.module :as module]))

(deftest coordinated-module-identities-and-versions
  (testing "the release set has exactly the requested dependency order"
    (is (= [:eacl :eacl-datomic :eacl-datahike :eacl-datascript]
           config/module-order))
    (is (= '[dev.eacl/eacl
             dev.eacl/eacl-datomic
             dev.eacl/eacl-datahike
             dev.eacl/eacl-datascript]
           (mapv (comp :lib config/module) config/module-order)))
    (is (true? (config/assert-coordinate-set!))))
  (testing "one version is applied to every transitive core edge"
    (doseq [module-id (rest config/module-order)]
      (is (= {:mvn/version "8.3.1"}
             (get (config/dependencies module-id "8.3.1")
                  'dev.eacl/eacl)))))
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

(deftest production-module-files-use-dev-eacl-coordinates
  (is (true? (module/assert-module-coordinates!))))
