(ns eacl.relationships.filters
  "The unified read-relationships filter contract, shared by every backend.

  An absent, misspelled, unsupported, or nil-valued filter key must fail
  loudly: silently dropping or wildcarding one degrades the query to a
  broader scan that returns rows the caller did not intend to read.")

(def known-anchor-keys
  #{:subject/type :subject/id
    :resource/type :resource/id :resource/relation})

(def known-filter-keys
  "Filter + pagination + execution keys read-relationships accepts. :cursor
  and :limit are named so their rejection classifies as an unsupported
  pagination option rather than an unknown key; :consistency, :page/basis,
  :cache?, :timeout-ms, and :cancellation-token are validated and consumed by
  the client layer but must be listed, since an unknown key is a hard error
  rather than something to ignore."
  (into known-anchor-keys
        [:first :last :after :before :cursor :limit
         :page/basis :consistency :cache? :evaluation :timeout-ms
         :cancellation-token :aggregate-limits :authorization]))

(defn validate!
  [filters]
  (when-not (map? filters)
    (throw
     (ex-info
      "read-relationships requires a filter map."
      {:type :eacl.filters/invalid-filter
       :eacl/error :eacl.filters/invalid-filter
       :value filters})))
  (doseq [[unsupported-key hint]
          [[:resource/id-prefix
            "Filter on :resource/id, or filter external ids client-side."]
           [:subject/relation
            "EACL does not support subject-relation filters."]
           [:cursor
            "EACL v8 pagination accepts only :first/:after or :last/:before."]
           [:limit
            "EACL v8 pagination accepts only :first/:after or :last/:before."]]]
    (when (contains? filters unsupported-key)
      (throw (ex-info
              (str (pr-str unsupported-key)
                   " is not supported by read-relationships. " hint)
              {:eacl/error :eacl.pagination/unsupported-filter
               :filter unsupported-key}))))
  (when-let [unknown-keys
             (seq (remove known-filter-keys (keys filters)))]
    (throw (ex-info
            (str "read-relationships was passed unknown filter key(s): "
                 (pr-str (vec unknown-keys)) ".")
            {:eacl/error :eacl.filters/unknown-filter
             :unknown-keys (vec unknown-keys)})))
  ;; Value presence, not key presence: a present-but-nil anchor (the shape
  ;; you get from {:subject/id (get-in req [:params :user-id])} with the
  ;; param missing) must not silently widen the read — a nil type or
  ;; relation would otherwise match every relation definition, and a nil id
  ;; would either scan or quietly read empty depending on the backend.
  (let [nil-anchor-keys
        (into []
              (comp (filter #(and (contains? filters %)
                                  (nil? (get filters %)))))
              (sort known-anchor-keys))]
    (when (seq nil-anchor-keys)
      (throw (ex-info
              (str "read-relationships was passed nil anchor filter(s) "
                   (pr-str nil-anchor-keys)
                   ". Supply a value or omit the key.")
              {:eacl/error :eacl.filters/missing-anchor
               :nil-anchor-keys nil-anchor-keys})))
    (when-not (some #(some? (get filters %)) known-anchor-keys)
      (throw (ex-info
              (str "read-relationships requires at least one anchor filter of "
                   (pr-str (vec (sort known-anchor-keys)))
                   ". An unfiltered read would scan the entire relationship"
                   " index.")
              {:eacl/error :eacl.filters/missing-anchor
               :nil-anchor-keys []}))))
  (when (and (some? (:subject/id filters))
             (not (some? (:subject/type filters))))
    (throw (ex-info
            ":subject/id requires :subject/type in read-relationships filters."
            {:type :eacl.filters/missing-subject-type
             :eacl/error :eacl.filters/missing-subject-type
             :operation :read-relationships
             :filter :subject/id
             :required-filter :subject/type})))
  filters)
