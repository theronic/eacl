(ns eacl.datascript.production-bundle-entry
  "Minimal release-build entry used by the browser payload audit."
  (:require [eacl.core]
            [eacl.datascript.core]))

(defn -main [] nil)

(set! *main-cli-fn* -main)
