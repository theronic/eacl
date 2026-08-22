(ns eacl.build.module
  "Focused tools.build operations shared by every independently published module."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.build.api :as b]
            [eacl.build.config :as config])
  (:import (java.io DataInputStream FileInputStream)
           (java.nio.charset StandardCharsets)
           (java.util.jar JarFile)))

(def required-core-entries
  #{"deps.cljs"
    "eacl/formal/generated_runtime.clj"
    "eacl/formal/production_kernel.clj"
    "eacl/formal/production_kernel_cljs.cljs"
    "AcyclicEngine/__default.class"
    "CurrentCache/__default.class"
    "IndexedTraversal/__default.class"
    "dafny/DafnySequence.class"
    "EaclKernel.browser.js"
    "META-INF/LICENCE"})

(defn repository-root
  []
  (loop [candidate (.getCanonicalFile (io/file "."))]
    (cond
      (and (.isDirectory (io/file candidate "modules" "eacl"))
           (.isDirectory (io/file candidate "formal")))
      candidate

      (nil? (.getParentFile candidate))
      (throw
       (ex-info
        "Could not locate the EACL repository root."
        {:type :eacl.build/repository-root-not-found
         :start (.getCanonicalPath (io/file "."))}))

      :else
      (recur (.getParentFile candidate)))))

(defn- file-path
  [root & components]
  (.getPath (apply io/file root components)))

(defn module-paths
  [module-id options]
  (let [root (repository-root)
        {:keys [directory lib]} (config/module module-id)
        release-version (config/version options)
        java-release (config/java-release options)
        module-root (file-path root directory)
        class-dir (file-path root directory "target" "classes")]
    {:root root
     :module-id module-id
     :module-root module-root
     :src-dir (file-path root directory "src")
     :target-dir (file-path root directory "target")
     :class-dir class-dir
     :jar-file
     (file-path root directory "target"
                (format "%s-%s.jar" (name lib) release-version))
     :pom-file (b/pom-path {:class-dir class-dir :lib lib})
     :lib lib
     :version release-version
     :java-release java-release
     :java-class-major-version
     (config/java-class-major-version {:java-release java-release})}))

(defn module-basis
  [module-id release-version & [local-repo]]
  (cond-> {:libs (config/dependencies module-id release-version)}
    local-repo (assoc :mvn/local-repo local-repo)))

(defn- formal-paths
  [root]
  {:java-classes (file-path root "target" "formal" "java" "classes")
   :browser-bundle
   (file-path root "target" "formal" "browser"
              "EaclKernel.browser.js")})

(defn- prepared-paths
  [module-root]
  {:runtime-dir (file-path module-root "target" "generated")
   :java-classes
   (file-path module-root "target" "generated" "java" "classes")
   :browser-bundle
   (file-path module-root "target" "generated" "browser"
              "EaclKernel.browser.js")})

(defn- require-path!
  [label path]
  (when-not (.exists (io/file path))
    (throw
     (ex-info
      (str "Missing " label ". Published Maven artifacts already contain the "
           "generated runtime. To build from source, explicitly run "
           "`cd modules/eacl && clojure -T:build prep`; this downloads formal "
           "tools and can consume substantial disk space and time. See the "
           "README section 'Source dependencies and formal tooling'.")
      {:type :eacl.build/generated-runtime-missing
       :artifact label
       :path path
       :command "cd modules/eacl && clojure -T:build prep"}))))

(defn- run-formal!
  [root command options]
  (let [script (.getCanonicalPath (io/file root "bin" "formal"))
        java-release (config/java-release options)
        builder (ProcessBuilder. ^java.util.List [script command])
        _ (.put (.environment builder)
                "EACL_JAVA_RELEASE"
                (str java-release))
        process
        (-> builder
            (.directory root)
            (.inheritIO)
            (.start))
        exit-code (.waitFor process)]
    (when-not (zero? exit-code)
      (throw
       (ex-info
        "EACL formal runtime build failed."
        {:type :eacl.build/formal-command-failed
         :command command
         :java-release java-release
         :exit-code exit-code}))))
  true)

(defn prep!
  "Explicitly builds and stages generated JVM/browser runtimes for source use."
  [options]
  (let [root (repository-root)
        module-root (file-path root "modules" "eacl")
        formal (formal-paths root)
        prepared (prepared-paths module-root)]
    (run-formal! root "build-java" options)
    (run-formal! root "browser-bundle" options)
    (b/delete {:path (:runtime-dir prepared)})
    (b/copy-dir {:src-dirs [(:java-classes formal)]
                 :target-dir (:java-classes prepared)})
    (b/copy-file {:src (:browser-bundle formal)
                  :target (:browser-bundle prepared)})
    prepared))

(defn clean!
  [module-id options]
  (let [{:keys [target-dir]} (module-paths module-id options)
        target (io/file target-dir)]
    (if (= :eacl module-id)
      ;; Explicit source preparation lives below target/generated. A normal
      ;; JAR clean must not silently undo it and break the IDE classpath.
      (doseq [child (or (seq (.listFiles target)) [])
              :when (not= "generated" (.getName ^java.io.File child))]
        (b/delete {:path (.getPath ^java.io.File child)}))
      (b/delete {:path target-dir}))))

(defn- relative-entry
  [root file]
  (-> (.toPath (io/file root))
      (.relativize (.toPath file))
      str
      (string/replace java.io.File/separator "/")))

(defn- generated-class-entries
  [class-root]
  (let [root (io/file class-root)]
    (when-not (.isDirectory root)
      (require-path! "generated JVM classes" class-root))
    (into
     #{}
     (comp
      (filter #(.isFile ^java.io.File %))
      (filter #(string/ends-with? (.getName ^java.io.File %) ".class"))
      (map #(relative-entry root %)))
     (file-seq root))))

(defn- read-class-major-version
  [input path]
  (let [input (DataInputStream. input)
        magic (.readInt input)]
    (when-not (= 0xCAFEBABE (bit-and 0xffffffff magic))
      (throw
       (ex-info
        "Generated artifact contains an invalid Java class file."
        {:type :eacl.build/invalid-class-file
         :path path})))
    (.readUnsignedShort input)
    (.readUnsignedShort input)))

(defn- class-major-version
  [class-file]
  (with-open [input (FileInputStream. ^java.io.File class-file)]
    (read-class-major-version input (.getPath ^java.io.File class-file))))

(defn- assert-jar-bytecode!
  [jar-file java-release expected-major]
  (with-open [archive (JarFile. jar-file)]
    (let [class-entries
          (filter
           #(and (not (.isDirectory ^java.util.jar.JarEntry %))
                 (string/ends-with?
                  (.getName ^java.util.jar.JarEntry %)
                  ".class"))
           (enumeration-seq (.entries archive)))
          wrong
          (into
           []
           (keep
            (fn [entry]
              (with-open [input (.getInputStream archive entry)]
                (let [major
                      (read-class-major-version input (.getName entry))]
                  (when-not (= expected-major major)
                    {:entry (.getName entry) :major major})))))
           class-entries)]
      (when-not (seq class-entries)
        (throw
         (ex-info
          "The core EACL JAR contains no generated classes."
          {:type :eacl.build/missing-generated-bytecode
           :jar jar-file})))
      (when (seq wrong)
        (throw
         (ex-info
          (format
           "Packaged EACL classes must target Java %d class-file major %d."
           java-release expected-major)
          {:type :eacl.build/unsupported-bytecode
           :java-release java-release
           :expected-major expected-major
           :jar jar-file
           :wrong wrong})))
      true)))

(defn- textual-jar-entry?
  [entry-name]
  (or (contains? #{"deps.cljs" "META-INF/LICENCE"} entry-name)
      (some #(string/ends-with? entry-name %)
            [".clj" ".cljc" ".cljs" ".edn" ".xml"])))

(def ^:private credential-patterns
  [#"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----"
   #"AKIA[0-9A-Z]{16}"
   #"gh[pousr]_[A-Za-z0-9_]{20,}"])

(declare jar-entries)

(defn- assert-no-workspace-content!
  [{:keys [root jar-file pom-file]}]
  (let [root-path (.getCanonicalPath ^java.io.File root)
        forbidden [root-path "target/formal" "cloudafrica/"
                   "/Users/" "/home/" "C:\\Users\\"]
        pom (slurp pom-file)]
    (doseq [fragment forbidden]
      (when (string/includes? pom fragment)
        (throw
         (ex-info
          "Generated POM contains checkout-only or obsolete content."
          {:type :eacl.build/workspace-content
           :path pom-file
           :fragment fragment}))))
    (with-open [archive (JarFile. jar-file)]
      (doseq [entry (enumeration-seq (.entries archive))
              :let [entry-name (.getName ^java.util.jar.JarEntry entry)]
              :when (and (not (.isDirectory entry))
                         (textual-jar-entry? entry-name))]
        (with-open [input (.getInputStream archive entry)]
          (let [content
                (String. (.readAllBytes input) StandardCharsets/UTF_8)]
            (doseq [fragment forbidden]
              (when (string/includes? content fragment)
                (throw
                 (ex-info
                  "Generated JAR contains checkout-only or obsolete content."
                  {:type :eacl.build/workspace-content
                   :path jar-file
                   :entry entry-name
                   :fragment fragment}))))
            (doseq [pattern credential-patterns]
              (when (re-find pattern content)
                (throw
                 (ex-info
                  "Generated JAR contains credential-shaped material."
                  {:type :eacl.build/credential-content
                   :path jar-file
                   :entry entry-name
                   :pattern (str pattern)}))))))))
    true))

(defn assert-adapter-jar-isolation!
  "Rejects backend implementation or native payloads outside the selected
  adapter's source namespace. Datalevin's native runtime belongs exclusively
  to its pinned dependency artifact, never to the EACL adapter JAR."
  [module-id jar-file]
  (when (= :eacl-datalevin module-id)
    (let [entries (jar-entries jar-file)
          forbidden-prefixes
          ["eacl/datomic/"
           "eacl/datahike/"
           "eacl/datascript/"
           "datalevin/"
           "org/bytedeco/"
           "META-INF/native-image/"]
          forbidden
          (into
           []
           (filter
            (fn [entry]
              (and (not (string/ends-with? entry "/"))
                   (some #(string/starts-with? entry %)
                         forbidden-prefixes))))
           entries)]
      (when (seq forbidden)
        (throw
         (ex-info
          "The Datalevin EACL adapter JAR contains another backend or native runtime payload."
          {:type :eacl.build/backend-isolation-failed
           :module module-id
           :entries (vec (sort forbidden))})))))
  true)

(defn assert-generated-bytecode!
  ([class-root]
   (assert-generated-bytecode! class-root {}))
  ([class-root options]
   (let [java-release (config/java-release options)
        expected-major (config/java-class-major-version
                        {:java-release java-release})
        root (io/file class-root)
        classes
        (filter
         #(and (.isFile ^java.io.File %)
               (string/ends-with? (.getName ^java.io.File %) ".class"))
         (file-seq root))
        wrong
        (into
         []
         (keep
          (fn [class-file]
            (let [major (class-major-version class-file)]
              (when-not (= expected-major major)
                {:entry (relative-entry root class-file)
                 :major major}))))
         classes)]
    (when-not (seq classes)
      (require-path! "generated JVM classes" class-root))
    (when (seq wrong)
      (throw
       (ex-info
        (format
         "Generated EACL classes must target Java %d class-file major %d."
         java-release expected-major)
        {:type :eacl.build/unsupported-bytecode
         :java-release java-release
         :expected-major expected-major
         :wrong wrong})))
    true)))

(defn- jar-entries
  [jar-file]
  (with-open [archive (JarFile. jar-file)]
    (into #{} (map #(.getName ^java.util.jar.JarEntry %))
          (enumeration-seq (.entries archive)))))

(defn- assert-core-jar!
  [{:keys [root jar-file java-release java-class-major-version]}]
  (let [{:keys [java-classes browser-bundle]} (formal-paths root)
        _ (require-path! "generated browser/CLJS runtime" browser-bundle)
        generated-entries (generated-class-entries java-classes)
        entries (jar-entries jar-file)
        expected (into required-core-entries generated-entries)
        missing (vec (sort (remove entries expected)))]
    (assert-generated-bytecode! java-classes {:java-release java-release})
    (assert-jar-bytecode!
     jar-file java-release java-class-major-version)
    (when (seq missing)
      (throw
       (ex-info
        "Published EACL artifact is missing generated-authority entries."
        {:type :eacl.build/incomplete-core-artifact
         :jar jar-file
         :missing missing})))
    true))

(defn- assert-pom!
  [module-id {:keys [pom-file lib version]}]
  (let [pom (slurp pom-file)
        expected-dependencies (config/dependencies module-id version)
        expected-dependency-set
        (into
         #{}
         (keep
          (fn [[dependency {:mvn/keys [version]}]]
            (when version
              [(namespace dependency) (name dependency) version])))
         expected-dependencies)
        actual-dependency-set
        (into
         #{}
         (map (fn [[_ group artifact dependency-version]]
                [group artifact dependency-version]))
         (re-seq
          #"(?s)<dependency>\s*<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>\s*<version>([^<]+)</version>\s*</dependency>"
          pom))
        scm-tag (or (System/getenv "GITHUB_SHA") "HEAD")]
    (doseq [required
            [(str "<groupId>" (namespace lib) "</groupId>")
             (str "<artifactId>" (name lib) "</artifactId>")
             (str "<version>" version "</version>")
             (str "<description>"
                  (:description (config/module module-id))
                  "</description>")
             "<url>https://github.com/theronic/eacl</url>"
             "<name>Eclipse Public License 2.0</name>"
             "<id>theronic</id>"
             "<name>Petrus Theron</name>"
             "<connection>scm:git:https://github.com/theronic/eacl.git</connection>"
             "<developerConnection>scm:git:ssh://git@github.com/theronic/eacl.git</developerConnection>"
             (str "<tag>" scm-tag "</tag>")]]
      (when-not (string/includes? pom required)
        (throw
         (ex-info
          "Generated EACL POM is missing required release metadata."
          {:type :eacl.build/invalid-pom
           :module module-id
           :pom pom-file
           :missing required}))))
    (when-not (= expected-dependency-set actual-dependency-set)
      (throw
       (ex-info
        "Generated POM direct dependencies do not match release configuration."
        {:type :eacl.build/dependency-set-mismatch
         :module module-id
         :expected expected-dependency-set
         :actual actual-dependency-set})))
    (doseq [[dependency {:mvn/keys [version]}] expected-dependencies
            :when version
            :let [dependency-pattern
                  (re-pattern
                   (str "(?s)<dependency>\\s*<groupId>"
                        (java.util.regex.Pattern/quote
                         (namespace dependency))
                        "</groupId>\\s*<artifactId>"
                        (java.util.regex.Pattern/quote (name dependency))
                        "</artifactId>\\s*<version>"
                        (java.util.regex.Pattern/quote version)
                        "</version>\\s*</dependency>"))]]
      (when-not (re-find dependency-pattern pom)
        (throw
         (ex-info
          "Generated POM is missing an exact direct dependency."
          {:type :eacl.build/dependency-mismatch
           :module module-id
           :dependency dependency
           :version version}))))
    true))

(defn audit-built!
  [module-id {:keys [jar-file] :as artifact}]
  (let [{:keys [generated-runtime? required-entry]}
        (config/module module-id)
        entries (jar-entries jar-file)
        required #{"META-INF/LICENCE" required-entry}
        missing (vec (sort (remove entries required)))]
    (assert-pom! module-id artifact)
    (assert-no-workspace-content! artifact)
    (assert-adapter-jar-isolation! module-id jar-file)
    (when (seq missing)
      (throw
       (ex-info
        "EACL module JAR is missing required entries."
        {:type :eacl.build/incomplete-module-artifact
         :module module-id
         :missing missing})))
    (when generated-runtime?
      (assert-core-jar! artifact))
    true))

(defn assert-module-coordinates!
  []
  (let [root (repository-root)
        module-root (io/file root "modules")
        production-files
        (filter
         #(and (.isFile ^java.io.File %)
               (contains? #{"build.clj" "deps.edn" "README.md"}
                          (.getName ^java.io.File %)))
         (file-seq module-root))
        obsolete-coordinate-markers ["cloudafrica/" "theronic/eacl"]
        stale
        (into
         []
         (keep
          (fn [file]
            (when (some #(string/includes? (slurp file) %)
                        obsolete-coordinate-markers)
              (.getPath ^java.io.File file))))
         production-files)]
    (config/assert-coordinate-set!)
    (when (seq stale)
      (throw
       (ex-info
        "EACL modules still contain obsolete Maven coordinates."
        {:type :eacl.build/stale-coordinate
         :markers obsolete-coordinate-markers
         :files stale})))
    true))

(defn jar!
  [module-id options]
  (let [{:keys [lib description generated-runtime?]}
        (config/module module-id)
        {:keys [root src-dir class-dir jar-file version] :as paths}
        (module-paths module-id options)
        basis (module-basis module-id version)
        formal (formal-paths root)]
    (when generated-runtime?
      (require-path! "generated JVM classes" (:java-classes formal))
      (require-path! "generated browser/CLJS runtime" (:browser-bundle formal)))
    (clean! module-id options)
    (b/write-pom
     {:class-dir class-dir
      :lib lib
      :version version
      :basis basis
      :scm (assoc config/scm :tag (or (System/getenv "GITHUB_SHA") "HEAD"))
      :pom-data (config/pom-data module-id)})
    (b/copy-dir {:src-dirs [src-dir] :target-dir class-dir})
    (b/copy-file {:src (file-path root "LICENCE")
                  :target (file-path class-dir "META-INF" "LICENCE")})
    (when generated-runtime?
      (b/copy-dir {:src-dirs [(:java-classes formal)]
                   :target-dir class-dir})
      (b/copy-file {:src (:browser-bundle formal)
                    :target (file-path class-dir "EaclKernel.browser.js")}))
    (b/jar {:class-dir class-dir :jar-file jar-file})
    (let [artifact (assoc paths :description description :basis basis)]
      (audit-built! module-id artifact)
      artifact)))

(defn install-built!
  [{:keys [class-dir jar-file lib version basis]} & [local-repo]]
  (b/install
   {:class-dir class-dir
    :jar-file jar-file
    :lib lib
    :version version
    :basis (cond-> basis
             local-repo (assoc :mvn/local-repo local-repo))})
  true)

(defn install!
  [module-id options]
  (let [artifact (jar! module-id options)]
    (install-built! artifact (:local-repo options))
    artifact))
