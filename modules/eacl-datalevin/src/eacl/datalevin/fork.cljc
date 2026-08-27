(ns eacl.datalevin.fork
  "Late-bound access to ordered-generation APIs in the maintained fork.

  Late binding lets an older explicit-snapshot fork load far enough for
  make-client to report a typed capability error instead of failing namespace
  compilation with a missing Var."
  (:require [datalevin.core]))

(defn- api-var
  [symbol]
  #?(:clj (ns-resolve 'datalevin.core symbol)
     :cljs nil))

(defn write-policy-capabilities
  []
  (when-let [operation (api-var 'write-policy-capabilities)]
    (operation)))

(defn- required-operation
  [symbol]
  (or (api-var symbol)
      (throw
       (ex-info
        "The Datalevin artifact lacks required write-policy support."
        {:type :eacl/unsupported-capability
         :eacl/error :eacl/unsupported-capability
         :backend :datalevin
         :capability :ordered-generations
         :missing-operation symbol}))))

(defn write-policy
  [conn]
  ((required-operation 'write-policy) conn))

(defn install-write-policy!
  ([conn policy]
   ((required-operation 'install-write-policy!) conn policy))
  ([conn policy tx-meta]
   ((required-operation 'install-write-policy!) conn policy tx-meta)))

(defn refresh-connection!
  [conn]
  ((required-operation 'refresh-connection!) conn))
