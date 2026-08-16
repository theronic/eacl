(ns eacl.schema.errors
  "Portable structured failures for public requests that name schema entries
  absent from the request's selected immutable snapshot.")

(defn- unknown-definition!
  [operation definition position]
  (throw
   (ex-info
    (str "Unknown schema definition " (pr-str definition) ".")
    (cond->
     {:type :eacl/unknown-definition
      :eacl/error :eacl/unknown-definition
      :operation operation
      :definition definition}
      position (assoc :position position)))))

(defn- unknown-relation-or-permission!
  [operation definition value kind]
  (throw
   (ex-info
    (str "Unknown " (name kind) " " (pr-str value)
         (when definition
           (str " on definition " (pr-str definition))) ".")
    (cond->
     {:type :eacl/unknown-relation-or-permission
      :eacl/error :eacl/unknown-relation-or-permission
      :operation operation
      :definition definition
      :relation-or-permission value
      :schema-kind kind}
      (= :permission kind) (assoc :permission value)
      (= :relation kind) (assoc :relation value)))))

(defn catalog
  "Builds the small name catalog needed by public request validation.

  EACL persists relation and permission declarations rather than empty
  definition shells, so a definition is observable when it owns a declaration
  or appears as a relation subject type."
  [{:keys [relations permissions]}]
  (let [relations (or relations [])
        permissions (or permissions [])]
    {:definitions
     (into
      (into #{}
            (map :eacl.permission/resource-type)
            permissions)
      (mapcat
       (juxt :eacl.relation/resource-type
             :eacl.relation/subject-type)
       relations))
     :relations
     (into #{}
           (map (juxt :eacl.relation/resource-type
                      :eacl.relation/relation-name))
           relations)
     :relation-names
     (into #{} (map :eacl.relation/relation-name) relations)
     :permissions
     (into #{}
           (map (juxt :eacl.permission/resource-type
                      :eacl.permission/permission-name))
           permissions)}))

(defn require-definition!
  [schema operation definition position]
  (when (and (some? definition)
             (not (contains? (:definitions (catalog schema)) definition)))
    (unknown-definition! operation definition position))
  schema)

(defn require-permission!
  [schema operation resource-type permission]
  (let [names (catalog schema)]
    (when-not (contains? (:definitions names) resource-type)
      (unknown-definition! operation resource-type :resource))
    (when-not (contains? (:permissions names) [resource-type permission])
      (unknown-relation-or-permission!
       operation resource-type permission :permission)))
  schema)

(defn validate-permission-request!
  "Validates a permission root and every supplied endpoint type. IDs are data,
  not schema, and deliberately remain outside this validator."
  [schema operation {:keys [resource-type subject-type permission]}]
  (require-permission! schema operation resource-type permission)
  (require-definition! schema operation subject-type :subject)
  schema)

(defn validate-expansion-request!
  "Expansion accepts either a relation or a permission as its root."
  [schema operation resource-type root-name]
  (let [{:keys [definitions relations permissions]} (catalog schema)]
    (when-not (contains? definitions resource-type)
      (unknown-definition! operation resource-type :resource))
    (when-not (or (contains? relations [resource-type root-name])
                  (contains? permissions [resource-type root-name]))
      (unknown-relation-or-permission!
       operation resource-type root-name :relation-or-permission)))
  schema)

(defn- relation-subject-types
  [{:keys [relations]}]
  (reduce (fn [index relation]
            (update index
                    [(:eacl.relation/resource-type relation)
                     (:eacl.relation/relation-name relation)]
                    (fnil conj #{})
                    (:eacl.relation/subject-type relation)))
          {}
          (or relations [])))

(defn validate-relationship-write!
  "Validates the schema names of one relationship update with the same
  typed taxonomy the read side uses: the resource definition, the relation
  declared on it, the subject definition, and that the subject's definition
  is a declared subject type of that relation. IDs are data, not schema, and
  stay outside this validator; a well-typed write may still fail on an
  unknown object."
  [schema operation {:keys [resource-type subject-type relation]}]
  (let [{:keys [definitions relations]} (catalog schema)]
    (when-not (contains? definitions resource-type)
      (unknown-definition! operation resource-type :resource))
    (when-not (contains? relations [resource-type relation])
      (unknown-relation-or-permission! operation resource-type relation :relation))
    (when-not (contains? definitions subject-type)
      (unknown-definition! operation subject-type :subject))
    (when-not (contains? (get (relation-subject-types schema)
                              [resource-type relation])
                         subject-type)
      (throw
       (ex-info
        (str "Relation " (pr-str relation) " on definition "
             (pr-str resource-type) " does not declare subject type "
             (pr-str subject-type) ".")
        {:type :eacl/unknown-relation-or-permission
         :eacl/error :eacl/unknown-relation-or-permission
         :operation operation
         :definition resource-type
         :relation-or-permission relation
         :relation relation
         :schema-kind :relation
         :subject-type subject-type
         :reason :subject-type-not-declared}))))
  schema)

(defn validate-relationship-read!
  [schema filters]
  (let [operation :read-relationships
        {:keys [definitions relations relation-names]} (catalog schema)
        resource-type (:resource/type filters)
        subject-type (:subject/type filters)
        relation (:resource/relation filters)]
    (when (and resource-type
               (not (contains? definitions resource-type)))
      (unknown-definition! operation resource-type :resource))
    (when (and subject-type
               (not (contains? definitions subject-type)))
      (unknown-definition! operation subject-type :subject))
    (when (and relation
               (not (if resource-type
                      (contains? relations [resource-type relation])
                      (contains? relation-names relation))))
      (unknown-relation-or-permission!
       operation resource-type relation :relation)))
  schema)
