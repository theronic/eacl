(ns eacl.build.config
  "Single source of truth for EACL Maven module identity and release metadata."
  (:require [clojure.string :as string]))

(def default-version "8.0.0-SNAPSHOT")
(def default-java-release 26)
(def minimum-java-release 8)

(def module-order
  [:eacl :eacl-datomic :eacl-datahike :eacl-datascript])

(def modules
  {:eacl
   {:lib 'dev.eacl/eacl
    :directory "modules/eacl"
    :description "Backend-neutral EACL authorization engine"
    :required-entry "eacl/core.cljc"
    :dependencies
    {'org.clojure/clojure {:mvn/version "1.11.4"}
     'instaparse/instaparse {:mvn/version "1.5.0"}}
    :generated-runtime? true}

   :eacl-datomic
   {:lib 'dev.eacl/eacl-datomic
    :directory "modules/eacl-datomic"
    :description "Datomic adapter for the EACL authorization engine"
    :required-entry "eacl/datomic/core.clj"
    :dependencies
    {'org.clojure/clojure {:mvn/version "1.11.4"}
     'dev.eacl/eacl ::eacl-version
     'com.rpl/specter {:mvn/version "1.1.4"}
     'com.datomic/peer {:mvn/version "1.0.7705"}}}

   :eacl-datahike
   {:lib 'dev.eacl/eacl-datahike
    :directory "modules/eacl-datahike"
    :description "Datahike adapter for the EACL authorization engine"
    :required-entry "eacl/datahike/core.clj"
    :dependencies
    {'org.clojure/clojure {:mvn/version "1.11.4"}
     'dev.eacl/eacl ::eacl-version
     'com.rpl/specter {:mvn/version "1.1.4"}
     'org.replikativ/datahike {:mvn/version "0.8.1759"}}}

   :eacl-datascript
   {:lib 'dev.eacl/eacl-datascript
    :directory "modules/eacl-datascript"
    :description "DataScript adapter for the EACL authorization engine"
    :required-entry "eacl/datascript/core.cljc"
    :dependencies
    {'org.clojure/clojure {:mvn/version "1.11.4"}
     'dev.eacl/eacl ::eacl-version
     'com.rpl/specter {:mvn/version "1.1.4"}
     'datascript/datascript {:mvn/version "1.7.8"}}}})

(def scm
  {:connection "scm:git:https://github.com/theronic/eacl.git"
   :developerConnection "scm:git:ssh://git@github.com/theronic/eacl.git"
   :url "https://github.com/theronic/eacl"})

(defn valid-version?
  [candidate]
  (boolean
   (and (string? candidate)
        (re-matches #"[0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT)?"
                    candidate))))

(defn version
  "Returns one validated version for every module in a build invocation.
  Explicit task input wins, followed by EACL_VERSION, then the local default."
  [options]
  (let [candidate
        (or (:version options)
            (System/getenv "EACL_VERSION")
            default-version)]
    (when-not (valid-version? candidate)
      (throw
       (ex-info
        "EACL version must be MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-SNAPSHOT."
        {:type :eacl.build/invalid-version
         :version candidate})))
    candidate))

(defn- parse-java-release
  [candidate]
  (cond
    (integer? candidate) candidate
    (and (string? candidate) (re-matches #"[0-9]+" candidate))
    (parse-long candidate)
    :else nil))

(defn java-release
  "Returns the bytecode release for generated Java. Java 26 is the pinned
  default; source builds may explicitly target Java 8 through 26."
  [options]
  (let [candidate
        (or (:java-release options)
            (System/getenv "EACL_JAVA_RELEASE")
            default-java-release)
        release (parse-java-release candidate)]
    (when-not (and release
                   (<= minimum-java-release release default-java-release))
      (throw
       (ex-info
        "EACL Java release must be an integer from 8 through 26."
        {:type :eacl.build/invalid-java-release
         :java-release candidate
         :minimum minimum-java-release
         :maximum default-java-release})))
    release))

(defn java-class-major-version
  [options]
  (+ 44 (java-release options)))

(defn module
  [module-id]
  (or (get modules module-id)
      (throw
       (ex-info
        "Unknown EACL Maven module."
        {:type :eacl.build/unknown-module
         :module module-id
         :known-modules module-order}))))

(defn dependencies
  [module-id release-version]
  (update-vals
   (:dependencies (module module-id))
   (fn [coordinate]
     (if (= ::eacl-version coordinate)
       {:mvn/version release-version}
       coordinate))))

(defn pom-data
  [module-id]
  (let [{:keys [description]} (module module-id)]
    [[:description description]
     [:url "https://github.com/theronic/eacl"]
     [:licenses
      [:license
       [:name "Eclipse Public License 2.0"]
       [:url "https://www.eclipse.org/legal/epl-2.0/"]
       [:distribution "repo"]]]
     [:developers
      [:developer
       [:id "theronic"]
       [:name "Petrus Theron"]
       [:url "https://github.com/theronic"]]]]))

(defn assert-coordinate-set!
  []
  (let [libs (map (comp :lib module) module-order)
        expected
        '#{dev.eacl/eacl
           dev.eacl/eacl-datomic
           dev.eacl/eacl-datahike
           dev.eacl/eacl-datascript}]
    (when-not (= expected (set libs))
      (throw
       (ex-info
        "EACL module coordinates do not match the release contract."
        {:type :eacl.build/invalid-coordinate-set
         :expected expected
         :actual (set libs)})))
    true))
