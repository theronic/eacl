(ns eacl.cache-identity
  "Portable semantic identity normalization for completed EACL results.")

(def invocation-control-keys
  "Request controls that govern one invocation but cannot change a successful
  result's denotation. These controls are validated and enforced before this
  normalization is used; removing them here does not bypass execution policy."
  #{:timeout-ms :cancellation-token :cache? :populate-cache?})

(defn successful-result-query
  "Removes only invocation-local controls from a validated request map.

  Operation-specific identity builders remain responsible for cursor transport,
  consistency externalization, and every result-affecting semantic dimension."
  [query]
  (dissoc query
          :timeout-ms
          :cancellation-token
          :cache?
          :populate-cache?))
