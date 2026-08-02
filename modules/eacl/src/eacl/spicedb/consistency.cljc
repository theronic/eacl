(ns eacl.spicedb.consistency
  "Consistency descriptors accepted by EACL clients.")

(def local-snapshot :local-snapshot)
(def minimize-latency :minimize-latency)
(def fully-consistent :fully-consistent)
(def synchronized-head :synchronized-head)

(defn at-least-as-fresh
  [token]
  {:consistency/mode :at-least-as-fresh
   :zed/token token})

(def fresh
  "Compatibility alias for at-least-as-fresh."
  at-least-as-fresh)

(defn at-exact-snapshot
  [token]
  {:consistency/mode :at-exact-snapshot
   :zed/token token})

(defn descriptor
  "Normalizes a caller value to {:mode keyword :token string-or-nil}."
  [value]
  (cond
    (or (nil? value) (= local-snapshot value))
    {:mode :local-snapshot}

    (= fully-consistent value)
    {:mode :fully-consistent}

    (= synchronized-head value)
    {:mode :synchronized-head}

    (= minimize-latency value)
    {:mode :minimize-latency}

    (and (map? value)
         (#{:at-least-as-fresh :at-exact-snapshot}
          (:consistency/mode value))
         (string? (:zed/token value))
         (not-empty (:zed/token value)))
    {:mode (:consistency/mode value)
     :token (:zed/token value)}

    :else
    (throw (ex-info "Unsupported EACL consistency descriptor."
                    {:type :eacl/unsupported-consistency
                     :consistency value}))))
