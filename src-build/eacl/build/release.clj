(ns eacl.build.release
  "Builds, audits, cold-installs, smoke-tests, and serially deploys EACL."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.build.api :as b]
            [eacl.build.config :as config]
            [eacl.build.module :as module])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def clojars-group "dev.eacl")
(def clojars-user "theronic")

(def consumer-entry-points
  {:eacl 'eacl.core
   :eacl-datomic 'eacl.datomic.core
   :eacl-datahike 'eacl.datahike.core
   :eacl-datascript 'eacl.datascript.core})

(defn- temporary-directory
  [prefix]
  (.toFile
   (Files/createTempDirectory
    prefix
    (make-array FileAttribute 0))))

(defn build-set!
  "Build every artifact with one version before returning any release set."
  [options]
  (let [version (config/version options)
        java-release (config/java-release options)]
    (module/assert-module-coordinates!)
    (mapv #(module/jar! % {:version version
                           :java-release java-release})
          config/module-order)))

(defn assert-release-set!
  [artifacts expected-version]
  (let [expected
        (mapv
         (fn [module-id]
           [module-id (:lib (config/module module-id)) expected-version])
         config/module-order)
        actual
        (mapv (juxt :module-id :lib :version) artifacts)
        java-targets
        (mapv (juxt :java-release :java-class-major-version) artifacts)]
    (when-not (= expected actual)
      (throw
       (ex-info
        "The release set must contain all four modules in dependency order."
        {:type :eacl.release/invalid-artifact-set
         :expected expected
         :actual actual})))
    (when-not (and (seq java-targets)
                   (apply = java-targets)
                   (every? some? (first java-targets)))
      (throw
       (ex-info
        "The release set must use one explicit generated-Java target."
        {:type :eacl.release/inconsistent-java-target
         :java-targets java-targets})))
    (doseq [artifact artifacts]
      (module/audit-built! (:module-id artifact) artifact))
    true))

(defn- run-command!
  [directory command]
  (let [process
        (-> (ProcessBuilder. ^java.util.List command)
            (.directory (io/file directory))
            (.redirectErrorStream true)
            (.start))
        output (slurp (.getInputStream process))
        exit-code (.waitFor process)]
    (when-not (zero? exit-code)
      (throw
       (ex-info
        "Clean Maven consumer command failed."
        {:type :eacl.release/consumer-command-failed
         :directory (.getPath (io/file directory))
         :command command
         :exit-code exit-code
         :output output})))
    output))

(defn- smoke-source
  [entry-point expected-major]
  (str
   "(ns smoke\n"
   "  (:require [clojure.java.io :as io]\n"
   "            [" entry-point "]\n"
   "            [eacl.formal.production-kernel])\n"
   "  (:import (java.io DataInputStream)\n"
   "           (dafny DafnySequence TypeDescriptor)\n"
   "           (EaclKernel WireResult_Accepted __default)))\n\n"
   "(defn- packaged-class-major []\n"
   "  (with-open [input (DataInputStream.\n"
   "                     (io/input-stream\n"
   "                      (io/resource \"CurrentCache/__default.class\")))]\n"
   "    (.readInt input)\n"
   "    (.readUnsignedShort input)\n"
   "    (.readUnsignedShort input)))\n\n"
   "(defn -main [& _]\n"
   "  (assert (= " expected-major " (packaged-class-major)))\n"
   "  (let [result\n"
   "        (__default/RoundTrip\n"
   "         (DafnySequence/asUnicodeString \"eacl.round-trip/v1\")\n"
   "         (DafnySequence/fromList\n"
   "          TypeDescriptor/BIG_INTEGER\n"
   "          [(biginteger 7)])\n"
   "         (biginteger 1))]\n"
   "    (assert (instance? WireResult_Accepted result))\n"
   "    (assert (= [7N] (mapv bigint (.dtor_items result)))))\n"
   "  (println \"EACL Maven smoke passed\"))\n"))

(defn- write-consumer!
  [consumer-root local-repo version module-id expected-major]
  (let [project (io/file consumer-root (name module-id))
        source-directory (io/file project "src")
        {:keys [lib]} (config/module module-id)]
    (.mkdirs source-directory)
    (spit
     (io/file project "deps.edn")
     (pr-str
      {:paths ["src"]
       :mvn/local-repo (.getCanonicalPath (io/file local-repo))
       :deps {lib {:mvn/version version}}}))
    (spit
     (io/file source-directory "smoke.clj")
     (smoke-source (consumer-entry-points module-id) expected-major))
    project))

(defn- assert-isolated-classpath!
  [repository-root classpath]
  (doseq [forbidden [(.getCanonicalPath ^java.io.File repository-root)
                     "target/formal"]]
    (when (string/includes? classpath forbidden)
      (throw
       (ex-info
        "Clean Maven consumer classpath contains an EACL checkout path."
        {:type :eacl.release/non-isolated-classpath
         :fragment forbidden
         :classpath classpath}))))
  true)

(defn install-and-smoke!
  "Install into a fresh Maven repository and exercise each Maven consumer."
  [artifacts options]
  (let [version (:version (first artifacts))
        expected-major (:java-class-major-version (first artifacts))
        local-repo
        (or (:local-repo options)
            (.getPath (temporary-directory "eacl-maven-repo-")))
        consumer-root (temporary-directory "eacl-consumers-")
        repository-root (module/repository-root)]
    (assert-release-set! artifacts version)
    (try
      (doseq [artifact artifacts]
        (module/install-built! artifact local-repo))
      (doseq [module-id config/module-order
              :let [project
                    (write-consumer!
                     consumer-root local-repo version module-id expected-major)
                    classpath
                    (run-command!
                     project ["clojure" "-Srepro" "-Spath"])]]
        (assert-isolated-classpath! repository-root classpath)
        (run-command! project ["clojure" "-Srepro" "-M" "-m" "smoke"]))
      true
      (finally
        (b/delete {:path (.getPath consumer-root)})
        (when-not (:local-repo options)
          (b/delete {:path local-repo}))))))

(defn assert-clojars-group!
  [groups]
  (when-not (contains? (set groups) clojars-group)
    (throw
     (ex-info
      (str "Clojars user theronic is not authorized for the verified "
           "dev.eacl group. Verify eacl.dev DNS ownership and group "
           "membership before deploying these coordinates.")
      {:type :eacl.release/clojars-group-unavailable
       :required-group clojars-group
       :visible-groups (vec (sort groups))})))
  true)

(defn- remote-clojars-groups
  []
  (let [read-json (requiring-resolve 'clojure.data.json/read-str)
        response
        (read-json
         (slurp "https://clojars.org/api/users/theronic")
         :key-fn keyword)]
    (:groups response)))

(defn- credentials
  []
  {:username (System/getenv "CLOJARS_USERNAME")
   :token (System/getenv "CLOJARS_PASSWORD")})

(defn assert-deploy-credentials!
  [{:keys [username token]}]
  (when-not (= clojars-user username)
    (throw
     (ex-info
      "CLOJARS_USERNAME must be the Clojars username theronic, not an email."
      {:type :eacl.release/invalid-clojars-username})))
  (when (string/blank? token)
    (throw
     (ex-info
      "CLOJARS_PASSWORD must contain the reusable Clojars deploy token."
      {:type :eacl.release/missing-clojars-token})))
  true)

(defn- deps-deploy!
  [{:keys [jar-file pom-file]}]
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :sign-releases? false
    :artifact jar-file
    :pom-file pom-file}))

(defn publish-validated!
  "Last in-process guard: re-audit all artifacts before the first upload."
  [artifacts version {:keys [groups deploy-fn deploy-credentials]}]
  (assert-release-set! artifacts version)
  (assert-clojars-group! groups)
  (assert-deploy-credentials! deploy-credentials)
  (doseq [artifact artifacts]
    ((or deploy-fn deps-deploy!) artifact))
  true)

(defn build-install-smoke
  [options]
  (let [artifacts (build-set! options)]
    (install-and-smoke! artifacts options)
    artifacts))

(defn run-release-pipeline!
  "Runs all local phases before the first remote mutation. Injection points
  exist only so phase-order failure behavior can be exhaustively tested."
  [version {:keys [build-fn smoke-fn groups-fn deploy-fn
                   deploy-credentials]}]
  (let [artifacts ((or build-fn build-set!) {:version version})]
    ((or smoke-fn install-and-smoke!) artifacts {})
    (publish-validated!
     artifacts version
     {:groups ((or groups-fn remote-clojars-groups))
      :deploy-fn deploy-fn
      :deploy-credentials (or deploy-credentials (credentials))})
    artifacts))

(defn deploy
  [options]
  (run-release-pipeline! (config/version options) {}))
