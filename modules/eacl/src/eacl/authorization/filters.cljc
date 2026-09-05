(ns eacl.authorization.filters
  "Closed request-shape validation for authorization-aware page routes.")

(def ^:private endpoint-keys #{:type :id :relation})
(def ^:private page-control-keys
  #{:first :last :after :before :cursor :limit :page/basis
    :consistency :cache? :populate-cache? :evaluation
    :timeout-ms :cancellation-token :caveat-context
    :aggregate-limits})
(def ^:private lookup-resource-keys
  (into page-control-keys
        #{:subject :permission :resource/type :resource/relationship}))
(def ^:private lookup-subject-keys
  (into page-control-keys
        #{:resource :permission :subject/type :subject/relationship}))

(def ^:dynamic ^:no-doc *validated-request?* false)

(defn- invalid!
  [message data]
  (throw
   (ex-info
    message
    (merge {:type :eacl.filters/invalid-authorization-clause
            :eacl/error :eacl.filters/invalid-authorization-clause}
           data))))

(defn validate-endpoint!
  [endpoint position]
  (when-not (map? endpoint)
    (invalid! "An authorization route endpoint must be a map."
              {:position position :value endpoint}))
  (when-let [unknown (seq (remove endpoint-keys (keys endpoint)))]
    (invalid! "An authorization route endpoint contains unknown keys."
              {:position position
               :unknown-keys (vec unknown)
               :known-keys endpoint-keys}))
  (when-not (keyword? (:type endpoint))
    (invalid! "An authorization route endpoint :type must be a keyword."
              {:position position :key :type :value (:type endpoint)}))
  (when-not (and (contains? endpoint :id) (some? (:id endpoint)))
    (invalid! "An authorization route endpoint requires a non-nil :id."
              {:position position :key :id :value (:id endpoint)}))
  endpoint)

(defn validate-scan-authorization!
  "Validates read-relationships' closed authorization clause."
  [filters]
  (when (contains? filters :authorization)
    (let [authorization (:authorization filters)]
    (when-not (map? authorization)
      (invalid! ":authorization must be a map."
                {:position :authorization :value authorization}))
    (let [known #{:subject :permission :on}]
      (when-let [unknown (seq (remove known (keys authorization)))]
        (invalid! ":authorization contains unknown keys."
                  {:position :authorization
                   :unknown-keys (vec unknown)
                   :known-keys known}))
      (when-let [missing (seq (remove #(contains? authorization %) known))]
        (invalid! ":authorization is missing required keys."
                  {:position :authorization
                   :missing-keys (vec missing)})))
    (validate-endpoint! (:subject authorization) :authorization/subject)
    (when-not (keyword? (:permission authorization))
      (invalid! ":authorization :permission must be a keyword."
                {:position :authorization
                 :key :permission
                 :value (:permission authorization)}))
    (when-not (contains? #{:subject :resource} (:on authorization))
      (invalid! ":authorization :on must be :subject or :resource."
                {:position :authorization
                 :key :on
                 :value (:on authorization)}))
    (let [required-type (case (:on authorization)
                          :subject :subject/type
                          :resource :resource/type)]
      (when-not (keyword? (get filters required-type))
        (invalid!
         "An authorization scan requires the designated endpoint type."
         {:position :authorization
          :on (:on authorization)
          :required-filter required-type
          :value (get filters required-type)})))))
  filters)

(defn- validate-relationship-clause!
  [clause clause-key anchor-key]
  (when-not (map? clause)
    (invalid! "A lookup relationship clause must be a map."
              {:position clause-key :value clause}))
  (let [known #{:relation anchor-key}]
    (when-let [unknown (seq (remove known (keys clause)))]
      (invalid! "A lookup relationship clause contains unknown keys."
                {:position clause-key
                 :unknown-keys (vec unknown)
                 :known-keys known}))
    (when-let [missing (seq (remove #(contains? clause %) known))]
      (invalid! "A lookup relationship clause is missing required keys."
                {:position clause-key
                 :missing-keys (vec missing)})))
  (when-not (keyword? (:relation clause))
    (invalid! "A lookup relationship :relation must be a keyword."
              {:position clause-key
               :key :relation
               :value (:relation clause)}))
  (validate-endpoint! (get clause anchor-key)
                      (keyword (name clause-key) (name anchor-key)))
  clause)

(defn validate-lookup!
  "Validates a lookup route's closed query and optional direct relationship
  predicate before snapshot selection."
  [operation query]
  (when-not (map? query)
    (invalid! "An authorization lookup query must be a map."
              {:operation operation :value query}))
  (when (and (= :lookup-subjects operation)
             (contains? query :subject/relation))
    (throw
     (ex-info
      ":subject/relation is not supported by lookup-subjects."
      {:eacl/error :eacl.pagination/unsupported-filter
       :filter :subject/relation})))
  (let [[known clause-key anchor-key]
        (case operation
          :lookup-resources
          [lookup-resource-keys :resource/relationship :subject]

          :lookup-subjects
          [lookup-subject-keys :subject/relationship :resource]

          (invalid! "Unknown authorization lookup operation."
                    {:operation operation}))]
    (when-let [unknown (seq (remove known (keys query)))]
      (invalid! "An authorization lookup query contains unknown keys."
                {:operation operation
                 :unknown-keys (vec unknown)
                 :known-keys known}))
    (when (contains? query clause-key)
      (validate-relationship-clause!
       (get query clause-key) clause-key anchor-key)))
  query)
