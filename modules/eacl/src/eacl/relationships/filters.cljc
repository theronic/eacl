(ns eacl.relationships.filters)

(def known-anchor-keys
  #{:subject/type :subject/id
    :resource/type :resource/id :resource/relation})

(defn validate!
  [filters]
  (doseq [[unsupported-key hint]
          [[:resource/id-prefix
            "Filter on :resource/id, or filter external ids client-side."]
           [:subject/relation
            "EACL does not support subject-relation filters."]]]
    (when (contains? filters unsupported-key)
      (throw (ex-info
              (str (pr-str unsupported-key)
                   " is not supported by read-relationships. " hint)
              {:eacl/error :eacl.pagination/unsupported-filter
               :filter unsupported-key}))))
  (when-let [unknown-keys
             (seq (remove known-anchor-keys (keys filters)))]
    (throw (ex-info
            (str "read-relationships was passed unknown filter key(s): "
                 (pr-str (vec unknown-keys)) ".")
            {:eacl/error :eacl.filters/unknown-filter
             :unknown-keys (vec unknown-keys)})))
  (when-not (some #(contains? filters %) known-anchor-keys)
    (throw (ex-info
            "read-relationships requires at least one anchor filter."
            {:eacl/error :eacl.filters/missing-anchor})))
  filters)
