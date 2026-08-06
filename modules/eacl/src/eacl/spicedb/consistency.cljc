(ns eacl.spicedb.consistency
  "Consistency descriptors accepted by EACL clients.")

(def minimize-latency :minimize-latency)
(def fully-consistent :fully-consistent)

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
  "Normalizes a caller value to {:mode keyword :token string-or-nil}.

  Token descriptors contain exactly :consistency/mode and :zed/token;
  unknown fields are rejected before authentication or snapshot selection."
  [value]
  (cond
    (or (nil? value) (= minimize-latency value))
    {:mode :minimize-latency}

    (= fully-consistent value)
    {:mode :fully-consistent}

    (and (map? value)
         (= 2 (count value))
         (contains? value :consistency/mode)
         (contains? value :zed/token)
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
