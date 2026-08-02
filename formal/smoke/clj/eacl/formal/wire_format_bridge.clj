(ns eacl.formal.wire-format-bridge
  (:import
   (dafny DafnySequence)
   (Semantics ObjectRef PermissionNode Query)
   (WireFormat
    DecodeResult_Decoded
    DecodeResult_Rejected
    RequestField
    WireLimits
    WireQuery)))

(defn- dafny-string
  [value]
  (DafnySequence/asUnicodeString value))

(defn- dafny-sequence
  [descriptor values]
  (DafnySequence/fromList descriptor values))

(defn- object-ref
  [type-name object-id]
  (ObjectRef/create
   (dafny-string type-name)
   (dafny-string object-id)))

(defn- permission-node
  [resource-type permission]
  (PermissionNode/create
   (dafny-string resource-type)
   (dafny-string permission)))

(defn- wire-query
  [offset page-size]
  (WireQuery/create
   (Query/create
    (object-ref "user" "u1")
    (permission-node "document" "view")
    (object-ref "document" "d1"))
   (biginteger offset)
   (biginteger page-size)))

(defn- limits
  [{:keys [max-objects]
    :or {max-objects 16}}]
  (WireLimits/create
   (biginteger 16)
   (biginteger max-objects)
   (biginteger 16)
   (biginteger 16)
   (biginteger 16)
   (biginteger 32)
   (biginteger 64)
   (biginteger 100)))

(defn- base-fields
  [offset page-size]
  (let [objects [(object-ref "user" "u1")
                 (object-ref "document" "d1")]]
    [(RequestField/create_VersionField
      (dafny-string "eacl.engine/v1"))
     (RequestField/create_ObjectsField
      (dafny-sequence (ObjectRef/_typeDescriptor) objects))
     (RequestField/create_RelationsField
      (dafny-sequence
       (Semantics.RelationNode/_typeDescriptor)
       []))
     (RequestField/create_PermissionsField
      (dafny-sequence
       (PermissionNode/_typeDescriptor)
       []))
     (RequestField/create_DefinitionsField
      (dafny-sequence
       (Semantics.RuleDefinition/_typeDescriptor)
       []))
     (RequestField/create_RelationshipsField
      (dafny-sequence
       (Semantics.Relationship/_typeDescriptor)
       []))
     (RequestField/create_QueryField
      (wire-query offset page-size))]))

(defn decode-scenario
  "Invokes the generated strict wire decoder for one boundary scenario."
  [scenario]
  (let [base (base-fields
              (case scenario
                :negative-offset -1
                :unsafe-offset 9007199254740992N
                0)
              25)
        fields
        (case scenario
          :valid base
          :duplicate-field (conj base (first base))
          :unknown-field
          (assoc base
                 1
                 (RequestField/create_UnknownField
                  (dafny-string "forged")))
          :invalid-identity
          (assoc
           base
           1
           (RequestField/create_ObjectsField
            (dafny-sequence
             (ObjectRef/_typeDescriptor)
             [(object-ref "" "u1")
              (object-ref "document" "d1")])))
          :oversized-collection base
          :unsafe-offset base
          :negative-offset base)
        result
        (WireFormat.__default/DecodeRequest
         (dafny-sequence (RequestField/_typeDescriptor) fields)
         (limits
          (if (= scenario :oversized-collection)
            {:max-objects 1}
            {})))]
    (cond
      (instance? DecodeResult_Decoded result)
      {:status :decoded
       :page-size
       (bigint (.dtor_pageSize (.dtor_input result)))}

      (instance? DecodeResult_Rejected result)
      {:status :rejected
       :error-class
       (.getSimpleName (class (.dtor_error result)))}

      :else
      (throw
       (ex-info
        "Generated wire decoder returned an unknown result variant."
        {:eacl/error :eacl.formal/unknown-generated-result
         :class (class result)})))))
