(ns eacl.caveats.portable.corpus
  "The shared Caveat corpus from the JVM module's test resources. The macro
   reads it at compile time, so ClojureScript on Node checks the same file."
  #?(:clj (:require [clojure.edn :as edn]
                    [clojure.java.io :as io]))
  #?(:cljs (:require-macros [eacl.caveats.portable.corpus :refer [corpus-cases]])))

#?(:clj
   (defmacro corpus-cases []
     (let [resource (or (io/resource "eacl/caveats/corpus.edn")
                        (throw (ex-info "eacl/caveats/corpus.edn is not on the classpath."
                                        {:classpath-entry "modules/eacl-caveats-jvm/test"})))]
       (list 'quote (:cases (edn/read-string (slurp resource)))))))

(def cases (corpus-cases))
