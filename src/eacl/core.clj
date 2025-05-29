(ns eacl.core
  "Defines the IAuthorization protocol, records & helpers.")

(defprotocol IAuthorization
  ;; For order-dependent calls, we try to maintain the order of [subject permission resource].

  ;; Check Permissions
  ;; We support various arities for convenience.
  (can?
    [this subject permission resource]
    [this subject permission resource consistency]
    [this {:as demand :keys [subject permission resource consistency]}])

  ;; `can?` Example:
  ;
  ;    (can? client (->user "andre") :view (->server 456))
  ;    => true | false
  ;
  ; Records used liberally to avoid typos in subject/object types.
  ; Accepts any map-like with {:keys [type id]}.

  ;; Schema
  (read-schema [this])
  (write-schema! [this schema])

  ;; Relationships
  (read-relationships [this query])
  ; where query is a map with the following keys (defprotocol does not support multiple :namespaced/keys):
  ; {:as            query
  ;  :keys          [limit cursor]
  ;  :subject/keys  [type id relation]
  ;  :resource/keys [type id id-prefix relation]}
  ;
  ; :resource/type is required. The rest is optional.
  ;
  ; When filtering by :subject/relation, subject schema must have the given relation.

  (write-relationships! [this updates])
  ; updates is a seq of RelationshipUpdate maps with {:keys [operation relationship]}, where
  ; operation is one of #{:create :touch :delete :unspecified} and Relationship has {:keys [subject relation resource]}.
  ; Note :touch is like :create but does not throw if a relationship already exists.

  (write-relationship!
    [this operation subject relation resource]
    [this {:as demand :keys [operation subject relation resource]}])

  (create-relationships! [this relationships])
  ; create-relationships! takes a seq of Relationship. Construct via ->Relationship, or use vector.

  (create-relationship!
    [this subject relation resource]
    [this {:as relationship :keys [subject relation resource]}])

  ; delete-relationships! takes the result of read-relationships, or
  ; construct a seq using ->Relationship.
  (delete-relationships! [this relationships])

  (delete-relationship!
    [this subject relation resource]
    [this {:as relationship :keys [subject relation resource]}])

  ;; Subject & Resource & Enumeration
  (lookup-resources [this {:as query :keys [consistency]}])
  ; lookup-resources (formerly 'what-can?') accepts:
  ; - :subject/type – keyword, required.
  ; - :subject/id – required.
  ; - :permission - keyword, required.
  ; - :resource/type – keyword, required.

  (lookup-subjects [this {:as query :keys [consistency]}])
  ; lookup-subjects (formerely 'who-can?') accepts:
  ; - :subject/type (keyword, required)
  ; - :subject/relation (optional keyword, e.g. :member)
  ; - :permission (keyword)
  ; - :resource/type (keyword, required)
  ; - :resource/id (required)

  (expand-permission-tree [this {:as opts :keys [consistency]} permission resource]))

; Spice affordances from previous impl.
(defrecord Relationship [subject relation resource])
(defrecord RelationshipUpdate [operation relationship])

(defrecord SpiceObject [type id relation]) ; where relation means subject_relation, which is distinct from Relationship.relation

(defn spice-object
  "Multi-arity helper for SubjectReference.
  Need a better name for this. Only used internally here."
  ([type id] (->SpiceObject type id nil))
  ([type id relation] (->SpiceObject type id relation)))