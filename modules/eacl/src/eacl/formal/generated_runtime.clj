(ns eacl.formal.generated-runtime
  "Fails clearly when a source checkout has not explicitly prepared the
  generated authority runtime. Published artifacts contain these classes.")

(def preparation-command
  "cd modules/eacl && clojure -T:build prep")

(def preparation-documentation
  "https://github.com/theronic/eacl#source-dependencies-and-formal-tooling")

(defn assert-available!
  "Checks that one generated class is present without installing or running
  any tool. Throws an actionable source-consumer error when it is absent."
  ([]
   (assert-available! "EaclKernel.__default"))
  ([class-name]
   (try
     (Class/forName class-name false
                    (.getContextClassLoader (Thread/currentThread)))
     true
     (catch ClassNotFoundException cause
       (throw
        (ex-info
         (str "EACL's generated runtime is missing. Published Maven artifacts "
              "already include it. Source consumers must explicitly prepare "
              "their checkout with `" preparation-command "`; this opt-in "
              "command downloads formal tools and can take substantial disk "
              "space and time. See " preparation-documentation ".")
         {:type :eacl.formal/generated-runtime-missing :eacl/error :eacl.formal/generated-runtime-missing
          :class class-name
          :command preparation-command
          :documentation preparation-documentation}
         cause))))))

(assert-available!)
