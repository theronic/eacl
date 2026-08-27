(ns eacl.formal.semantics-bridge
  (:require
   [eacl.core :as eacl])
  (:import
   (dafny DafnySequence TypeDescriptor)
   (Semantics
    Grant
    ObjectRef
    PermissionNode
    Query
    RelationNode
    Relationship
    RuleDefinition
    __default)
   (RecursiveEngine TraversalLimits)))

(defn- dafny-string
  [value]
  (DafnySequence/asUnicodeString (name value)))

(defn- dafny-sequence
  [type-descriptor values]
  (DafnySequence/fromList type-descriptor values))

(defn- object->dafny
  [{:keys [type id]}]
  (ObjectRef/create
   (dafny-string type)
   (dafny-string id)))

(defn- relationship->dafny
  [{:keys [resource relation subject]}]
  (Relationship/create
   (object->dafny resource)
   (dafny-string relation)
   (object->dafny subject)))

(defn- relation-signatures
  [relationships]
  (reduce
   (fn [signatures {:keys [resource relation subject]}]
     (update
      signatures
      [(:type resource) relation]
      (fnil conj #{})
      (:type subject)))
   {}
   relationships))

(defn- permission-node
  [[resource-type permission]]
  (PermissionNode/create
   (dafny-string resource-type)
   (dafny-string permission)))

(declare expression->definitions)

(defn- expression->definitions
  [signatures [resource-type :as head] expression]
  (let [[operator & operands] expression]
    (case operator
      :union
      (mapcat
       #(expression->definitions signatures head %)
       operands)

      :relation
      (let [relation (first operands)]
        (map
         #(RuleDefinition/create_DirectRelation
           (permission-node head)
           (dafny-string relation)
           (dafny-string %))
         (get signatures [resource-type relation] #{})))

      :permission
      [(RuleDefinition/create_SelfPermission
        (permission-node head)
        (dafny-string (first operands)))]

      :arrow
      (let [[via target] operands
            [target-operator target-name] target]
        (case target-operator
          :relation
          (let [intermediate-types
                (get signatures [resource-type via] #{})
                subject-types
                (into
                 #{}
                 (mapcat
                  #(get signatures [% target-name] #{})
                  intermediate-types))]
            (map
             #(RuleDefinition/create_ArrowRelation
               (permission-node head)
               (dafny-string via)
               (dafny-string target-name)
               (dafny-string %))
             subject-types))

          :permission
          [(RuleDefinition/create_ArrowPermission
            (permission-node head)
            (dafny-string via)
            (dafny-string target-name))]

          (throw
           (ex-info
            "Unknown arrow target in formal fixture."
            {:target target}))))

      (throw
       (ex-info
        "Unknown authorization-oracle expression."
        {:expression expression})))))

(defn- definitions
  [rules signatures]
  (into
   []
   (mapcat
    (fn [[head expression]]
      (expression->definitions signatures head expression)))
   rules))

(defn- relation-nodes
  [signatures]
  (for [[[resource-type relation] subject-types] signatures
        subject-type subject-types]
    (RelationNode/create
     (dafny-string resource-type)
     (dafny-string relation)
     (dafny-string subject-type))))

(defn- dafny-string->keyword
  [value]
  (keyword (.verbatimString value)))

(defn- dafny-object->object
  [object]
  (eacl/spice-object
   (dafny-string->keyword (.dtor_typeName object))
   (.verbatimString (.dtor_objectId object))))

(defn- grant->value
  [grant]
  [(dafny-object->object (.dtor_subject grant))
   (dafny-string->keyword (.dtor_permissionName (.dtor_node grant)))
   (dafny-object->object (.dtor_resource grant))])

(defn well-formed?
  [{:keys [objects relationships rules]}]
  (let [signatures (relation-signatures relationships)
        permissions (mapv permission-node (keys rules))
        normalized-definitions (vec (definitions rules signatures))]
    (__default/WellFormedSchema
     (dafny-sequence
      (ObjectRef/_typeDescriptor)
      (mapv object->dafny objects))
     (dafny-sequence
      (RelationNode/_typeDescriptor)
      (vec (relation-nodes signatures)))
     (dafny-sequence
      (PermissionNode/_typeDescriptor)
      permissions)
     (dafny-sequence
      (RuleDefinition/_typeDescriptor)
      normalized-definitions)
     (dafny-sequence
      (Relationship/_typeDescriptor)
      (mapv relationship->dafny relationships)))))

(defn authorization-set
  [{:keys [objects relationships rules]}]
  (let [signatures (relation-signatures relationships)
        result
        (__default/AuthorizationSemantics
         (dafny-sequence
          (ObjectRef/_typeDescriptor)
          (mapv object->dafny objects))
         (dafny-sequence
          (PermissionNode/_typeDescriptor)
          (mapv permission-node (keys rules)))
         (dafny-sequence
          (RuleDefinition/_typeDescriptor)
          (vec (definitions rules signatures)))
         (dafny-sequence
          (Relationship/_typeDescriptor)
          (mapv relationship->dafny relationships)))]
    (into #{} (map grant->value) (.Elements result))))

(defn- formal-inputs
  [{:keys [objects relationships rules]}]
  (let [signatures (relation-signatures relationships)]
    {:objects
     (dafny-sequence
      (ObjectRef/_typeDescriptor)
      (mapv object->dafny objects))
     :permissions
     (dafny-sequence
      (PermissionNode/_typeDescriptor)
      (mapv permission-node (keys rules)))
     :definitions
     (dafny-sequence
      (RuleDefinition/_typeDescriptor)
      (vec (definitions rules signatures)))
     :relationships
     (dafny-sequence
      (Relationship/_typeDescriptor)
      (mapv relationship->dafny relationships))}))

(defn compiled-path-count
  [fixture]
  (let [{:keys [definitions]} (formal-inputs fixture)]
    (.length
     (AcyclicEngine.__default/CompilePaths definitions))))

(defn direct-can?
  [fixture subject permission resource]
  (let [{:keys [objects permissions definitions relationships]}
        (formal-inputs fixture)]
    (AcyclicEngine.__default/DirectCan
     objects
     permissions
     definitions
     relationships
     (Query/create
      (object->dafny subject)
      (permission-node [(:type resource) permission])
      (object->dafny resource)))))

(defn acyclic-forward
  [fixture subject resource-type permission]
  (let [{:keys [objects permissions definitions relationships]}
        (formal-inputs fixture)]
    (mapv
     dafny-object->object
     (AcyclicEngine.__default/AcyclicForward
      objects
      permissions
      definitions
      relationships
      (object->dafny subject)
      (permission-node [resource-type permission])))))

(defn acyclic-reverse
  [fixture resource subject-type permission]
  (let [{:keys [objects permissions definitions relationships]}
        (formal-inputs fixture)]
    (->> (AcyclicEngine.__default/AcyclicReverse
          objects
          permissions
          definitions
          relationships
          (object->dafny resource)
          (permission-node [(:type resource) permission]))
         (map dafny-object->object)
         (filter #(= subject-type (:type %)))
         vec)))

(defn acyclic-count
  [fixture subject resource-type permission count-limit]
  (let [{:keys [objects permissions definitions relationships]}
        (formal-inputs fixture)
        result
        (AcyclicEngine.__default/CountForward
         objects
         permissions
         definitions
         relationships
         (object->dafny subject)
         (permission-node [resource-type permission])
         (biginteger count-limit))]
    {:count (.longValue (.dtor__0 result))
     :truncated? (.dtor__1 result)}))

(defn- traversal-limits
  [{:keys [max-derived-grants
           max-advanced-datoms
           max-queued-work]
    :or {max-derived-grants 100000
         max-advanced-datoms 100000
         max-queued-work 100000}}]
  (TraversalLimits/create
   (biginteger max-derived-grants)
   (biginteger max-advanced-datoms)
   (biginteger max-queued-work)))

(defn- limit-kind
  [kind]
  (cond
    (.is_DerivedGrants kind) :derived-grants
    (.is_AdvancedDatoms kind) :advanced-datoms
    (.is_QueuedWork kind) :queued-work
    :else :unknown))

(defn- work-counters
  [counters]
  {:derived-grants
   (.longValue (.dtor_derivedGrants counters))
   :advanced-datoms
   (.longValue (.dtor_advancedDatoms counters))
   :queued-work
   (.longValue (.dtor_queuedWork counters))})

(defn- sequence-outcome
  [outcome]
  (if (.is_SequenceComplete outcome)
    {:status :complete
     :items (mapv dafny-object->object (.dtor_items outcome))
     :counters (work-counters (.dtor_counters outcome))}
    {:status :limit-exceeded
     :limit-kind (limit-kind (.dtor_kind outcome))
     :counters (work-counters (.dtor_counters outcome))}))

(defn recursive-forward
  ([fixture subject resource-type permission]
   (recursive-forward fixture subject resource-type permission {}))
  ([fixture subject resource-type permission limits]
   (let [{:keys [objects permissions definitions relationships]}
         (formal-inputs fixture)]
     (sequence-outcome
      (RecursiveEngine.__default/RecursiveForward
       objects
       permissions
       definitions
       relationships
       (object->dafny subject)
       (permission-node [resource-type permission])
       (traversal-limits limits))))))

(defn recursive-reverse
  ([fixture resource subject-type permission]
   (recursive-reverse fixture resource subject-type permission {}))
  ([fixture resource subject-type permission limits]
   (let [{:keys [objects permissions definitions relationships]}
         (formal-inputs fixture)
         outcome
         (sequence-outcome
          (RecursiveEngine.__default/RecursiveReverse
           objects
           permissions
           definitions
           relationships
           (object->dafny resource)
           (permission-node [(:type resource) permission])
           (traversal-limits limits)))]
     (if (= :complete (:status outcome))
       (update outcome :items
               (fn [items]
                 (filterv #(= subject-type (:type %)) items)))
       outcome))))
